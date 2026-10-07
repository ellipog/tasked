package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The files this process has already read, by what they were when it read them.
 *
 * <h2>The shape of the cost this removes</h2>
 *
 * <p>An accepted edit writes <b>one</b> file and then asks for a reload — and a reload reads every file
 * in the pack. So the work of an edit scaled with the size of the questline rather than with the size of
 * the edit, and an author with a few hundred quests paid for all of them to change a count. What is
 * cached here is the read and the parse of each file, keyed by the two things that say whether it is
 * still the file that was read: its <b>last-modified time</b> and its <b>size</b>.
 *
 * <h2>The problems are replayed, and that is not an optimisation</h2>
 *
 * <p>A file that did not parse, or that parsed with a warning, has to say so on <i>every</i> load. A
 * cache that returned the document and dropped the messages would make a pack's problems appear on the
 * first load and vanish afterwards — which reads as a server that fixed itself, and is the opposite of
 * what a problem list is for. So each entry carries the messages the read produced, against the file's
 * own display name, and a hit adds them back in the same place in the walk. A warm load therefore
 * reports exactly what a cold one does, which is asserted rather than intended.
 *
 * <h2>What a hit hands back is read-only</h2>
 *
 * <p>The parsed {@link JsonDocument} is shared with whoever asked, and a document held here must not be
 * mutated: the next load would hand the same mutated tree to a caller that expects what is on disk. That
 * is the assumption this class rests on, and it holds because the one place in this project that edits
 * JSON builds its own tree from its own read — see {@code JsonFile} — rather than borrowing the loader's.
 *
 * <h2>The one way this can be wrong, and what closes it</h2>
 *
 * <p>Modified-time and size are what the file system will tell you cheaply, and on a file system with
 * coarse timestamps — two seconds is the classic — a write that lands in the same tick and keeps the
 * same length is indistinguishable from no write at all. A value edited from {@code 0.5} to {@code 0.7}
 * is exactly that shape, and serving the old parse for it would look like an edit that did nothing.
 *
 * <p>So the mod's own writes do not rely on the clock: every file this project writes is forgotten
 * explicitly as it is written — see {@link #forget}, which the editor's own write path calls. The stat
 * remains the answer for a file changed <i>outside</i> the game, which is the case where a hand edit and
 * a stale tick are the author's own doing and a reload is a keystroke away.
 *
 * <h2>What is deliberately not cached</h2>
 *
 * <p>Validation and decoding. Both are CPU-only, both run per file after the read, and neither touches
 * the disk — which is the line this class draws. Caching them as well would mean holding decoded models
 * across loads and reasoning about which of them a structural edit invalidates, and that is a larger
 * claim than "do not read a file twice". The instrument says which half is worth doing next; see
 * {@code QuestLoader}'s phase timings.
 */
public final class ParsedFiles {

    /**
     * What a file was when it was read: the two numbers that say whether it is still that file.
     *
     * <p>Equality is the whole interface: a stamp held in an entry is compared with a stamp taken now,
     * and the entry is stale when they differ.
     */
    record Stamp(long modified, long size) {
    }

    /** One file as it was read: the stamp it carried, what the read produced, and what it said. */
    record Held(Stamp stamp, Optional<JsonDocument> document, List<DataProblem> problems) {
    }

    private static final Map<String, Held> HELD = new HashMap<>();

    /** Read counts, for the instrument and for a test: how a cache is known to be doing anything. */
    private static int hits;
    private static int misses;

    private ParsedFiles() {
    }

    /**
     * What the file system says about one file now, or null when it cannot be asked.
     *
     * <p>The time is taken at the <b>finest resolution the file system reports</b> rather than in whole
     * milliseconds. That is not a nicety: two writes a millisecond apart are two different files, and a
     * key that rounded them together would call the second one unchanged. Where the file system is
     * coarse the extra digits are zeroes and nothing is lost — the explicit invalidation below is what
     * covers that case.
     *
     * <p>Null is not a failure to report here: a file that cannot be stat-ed is one that is not there,
     * and the read that follows says so in the terms the author needs — "could not be read", against
     * the file's own name. This class answering instead would be a second message for one fault.
     */
    static Stamp stamp(Path file) {
        try {
            return new Stamp(Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.NANOSECONDS),
                    Files.size(file));
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The parse of this file as it stands, or null when it is not held or has changed.
     *
     * <p>Misses are counted, not just hits, because "the cache did nothing" and "the cache was not
     * consulted" look identical from the outside — and the second is the failure worth being able to
     * see.
     */
    static Held held(Path file, Stamp stamp) {
        Held held = HELD.get(file.toString());
        if (held == null || !held.stamp().equals(stamp)) {
            misses++;
            return null;
        }
        hits++;
        return held;
    }

    /** Remembers one file's read. Called only for a file that could be stat-ed. */
    static void hold(Path file, Stamp stamp, Optional<JsonDocument> document,
                     List<DataProblem> problems) {
        HELD.put(file.toString(), new Held(stamp, document, List.copyOf(problems)));
    }

    /**
     * Forgets one file, because this process has just written it.
     *
     * <p>Called by the editor's write path rather than by a timer, and that is the point: the file's own
     * modification time is not reliable enough to be the only thing that says "this changed" — see this
     * class's note — so the one writer that knows calls here instead of hoping the clock moved.
     */
    public static void forget(Path file) {
        HELD.remove(file.toString());
    }

    /**
     * Forgets everything: a file was moved, created or set aside, so what is on disk is a different
     * <i>set</i> of files rather than different contents in the same ones.
     *
     * <p>Blunt on purpose, and used only where the set can change — a chapter or a group made, moved,
     * copied or deleted, and an undo, which can have done any of those. A per-file forget is what the
     * ordinary edit uses, because that is the case that happens hundreds of times a session.
     */
    public static void clear() {
        HELD.clear();
    }

    /** How many reads were answered from what was already held. For a test and for the instrument. */
    static int hits() {
        return hits;
    }

    /** How many were not: a file that changed, a file that is new, and a file that is gone. */
    static int misses() {
        return misses;
    }

    /** Forgets everything and the counts with it. For a test, so one cannot read another's numbers. */
    static void reset() {
        clear();
        hits = 0;
        misses = 0;
    }

    /** How many files are held. For a test asserting that a forget forgot rather than re-read. */
    static int held() {
        return HELD.size();
    }
}
