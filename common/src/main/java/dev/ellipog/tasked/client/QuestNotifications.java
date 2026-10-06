package dev.ellipog.tasked.client;

import dev.ellipog.tasked.progress.QuestState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The diff behind the completion and claim notices: what the cache held, against what it holds now.
 *
 * <h2>Why the client derives this rather than being told</h2>
 *
 * <p>A progress sync carries a state, not a change — the same shape arrives whether a quest finished
 * a moment ago or was already finished when the player joined. The server's own line about a
 * completion goes to chat, and chat is not readable while a screen is open. So the difference
 * between "this quest is finished" and "this quest <i>just</i> finished" can only be the client
 * comparing two samples, and this is that comparison, kept game-free so it can be asserted without
 * a client.
 *
 * <h2>The three silences, and each is a rule rather than an accident</h2>
 *
 * <ul>
 *   <li><b>The first sample seeds and says nothing.</b> It is a join, and a join into a world whose
 *       book is half finished must not announce the whole finished half.</li>
 *   <li><b>An empty sample is a reset, not a hundred removals.</b> A disconnect clears the cache and
 *       bumps its revision; a notifier holding the last map across that would read the next server's
 *       tree against the previous server's states — and a quest that was locked there and is
 *       complete here would be announced as a completion that never happened in front of this
 *       player. Empty means "no news", so the map is cleared and the next sample seeds again.</li>
 *   <li><b>A silent auto-claim mode suppresses the completion.</b> An author who turned auto-claim
 *       on for fifty starter quests asked for exactly this; see {@code RewardAutoClaim.notifies}.
 *       The flag is computed by the caller, which is where the enum lives.</li>
 * </ul>
 *
 * <h2>A claim is a different transition from a completion</h2>
 *
 * <p>A claim is the moment the server stops owing: the quest was COMPLETED at both samples, what was
 * claimable is not any more. Requiring the state at both ends is what keeps a reset (COMPLETED →
 * LOCKED) or a fresh completion (UNLOCKED → COMPLETED) from reading as a claim, and it means the
 * sound fires when the server has confirmed the claim rather than when the button was pressed —
 * a refusal is not a claim.
 */
public final class QuestNotifications {

    /** What a notice is about. */
    public enum Kind {

        /** A quest that was not finished and now is. */
        COMPLETED,

        /** Rewards that were waiting and now are not: the server confirmed a claim. */
        CLAIMED
    }

    /**
     * One quest as the diff needs it.
     *
     * @param questId   the quest's id
     * @param state     the state the server last reported
     * @param claimable whether this player has rewards waiting on it
     * @param silent    whether the author asked for a completion to go unannounced
     */
    public record Snapshot(String questId, QuestState state, boolean claimable, boolean silent) {
    }

    /** One thing that just happened. The caller resolves the title it shows; this carries the id. */
    public record Notice(Kind kind, String questId) {
    }

    /** What one quest was at the last sample. */
    private record Sample(QuestState state, boolean claimable) {
    }

    private final Map<String, Sample> last = new LinkedHashMap<>();

    /**
     * The notices since the last sample, oldest first.
     *
     * <p>Also the seeding: the first sample after a reset returns nothing and remembers everything,
     * which is what makes a join quiet.
     */
    public List<Notice> sample(List<Snapshot> now) {
        if (now.isEmpty()) {
            // See the class note: a cleared or not-yet-arrived cache is not "everything was just
            // removed". Forgetting is the honest reading, and the next sample seeds silently.
            last.clear();
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<Notice> notices = compare(now, seen);
        // A quest the tree no longer carries is forgotten rather than kept: a reload that removes one
        // must not leave its id behind to announce on a later reappearance, and an unbounded map of
        // every id a long session has ever seen is a leak with no reader.
        last.keySet().retainAll(seen);
        return List.copyOf(notices);
    }

    /**
     * The notices for a sample that names only <b>some</b> of the quests — the ids one delta carried.
     *
     * <h2>Why this is not {@link #sample} with a shorter list</h2>
     *
     * <p>Because of what the two do with the ids they were <i>not</i> given, and the two answers are
     * opposites rather than variations:
     *
     * <ul>
     *   <li>The full sample <b>forgets</b> every quest it does not name, because it is a statement about
     *       the whole cache: a quest the tree no longer carries must not be left behind to announce on
     *       a later reappearance.</li>
     *   <li>A partial sample must <b>keep</b> every quest it does not name, because those quests did
     *       not move. Forgetting them is not a tidy-up, it is a blindness: this diff only announces a
     *       transition it has a previous sample for, so a quest dropped from the baseline can never be
     *       announced again — a completion would arrive and be compared against nothing.</li>
     * </ul>
     *
     * <p>And an <b>empty</b> partial sample means the opposite of an empty full one. The full reading is
     * "the cache is empty, forget it"; this reading is "the message named nothing, so nothing
     * happened", which is a delta about a quest that turned out to be unchanged. Both are pinned in
     * {@code QuestNotificationsTest}, because the two methods differ in exactly the places where being
     * wrong is silent.
     *
     * <h2>What a partial sample still cannot see</h2>
     *
     * <p>It forgets the ids <b>the message named</b> that the tree no longer holds, which is everything
     * a delta can say about a removal. A quest removed by a <i>tree</i> edit, with no progress message
     * naming it, keeps its baseline until the next full sync — a join or a reload, both of which send
     * one. That is a bounded residue rather than a leak: it is at most the ids this session has been
     * told about, and it is cleared by the first full sample after it. Stated here rather than left to
     * be discovered, because a baseline kept for a quest that no longer exists is exactly the kind of
     * thing that announces a completion nobody just made.
     *
     * @param named the ids the message named — a delta's keys plus anything it removed
     * @param some  the pictures taken for those of them the tree still holds
     */
    public List<Notice> sampleSome(Set<String> named, List<Snapshot> some) {
        // What the message named and the tree no longer holds. The full sample forgets these by
        // omission — it walks the tree, so a quest that is not there is not in its sample — and a
        // partial one has to be told, because the ids it was given came from the message rather than
        // from the tree.
        Set<String> pictured = new HashSet<>();
        for (Snapshot snapshot : some) {
            pictured.add(snapshot.questId());
        }
        for (String id : named) {
            if (!pictured.contains(id)) {
                last.remove(id);
            }
        }
        if (some.isEmpty()) {
            return List.of();
        }
        // The set is filled and dropped: pruning is the full sample's business, and passing it here is
        // the price of one comparison rule rather than two.
        return List.copyOf(compare(some, new HashSet<>()));
    }

    /**
     * The transitions, in one place: what each named quest is now against what it was, with every
     * snapshot becoming the new baseline as it is compared.
     *
     * @param seen filled with every id this sample names, for a caller that prunes afterwards
     */
    private List<Notice> compare(List<Snapshot> now, Set<String> seen) {
        List<Notice> notices = new ArrayList<>();
        for (Snapshot snapshot : now) {
            seen.add(snapshot.questId());
            Sample was = last.put(snapshot.questId(),
                    new Sample(snapshot.state(), snapshot.claimable()));
            if (was == null) {
                continue;   // a join, or a quest the tree just gained: nothing to compare against
            }
            if (was.state() != QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED
                    && !snapshot.silent()) {
                notices.add(new Notice(Kind.COMPLETED, snapshot.questId()));
            }
            if (was.state() == QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED
                    && was.claimable() && !snapshot.claimable()) {
                notices.add(new Notice(Kind.CLAIMED, snapshot.questId()));
            }
        }
        return notices;
    }

    /** Forgets everything: a disconnect, or a change of team. */
    public void reset() {
        last.clear();
    }
}
