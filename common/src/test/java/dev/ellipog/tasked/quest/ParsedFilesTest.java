package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read cache: which reads are answered from what was held, and which must not be.
 *
 * <h2>What the counts are for</h2>
 *
 * <p>"The cache did nothing" and "the cache was not consulted" are indistinguishable from the outside —
 * a load either way produces the same tree — so the interesting assertions are about the <b>number of
 * reads</b>. The counts are the instrument, and the case that matters most is the one where the clock
 * cannot tell that a file changed: it is asserted here as the hazard it is, beside the explicit
 * invalidation that closes it.
 */
@DisplayName("the parsed-file cache")
class ParsedFilesTest {

    @TempDir
    Path directory;

    @BeforeEach
    @AfterEach
    void clean() {
        ParsedFiles.reset();
    }

    private Path file(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Optional<JsonDocument> parse(Path path, Problems problems) {
        return QuestFiles.parseFile(path, path.getFileName().toString(), problems);
    }

    private static int value(Optional<JsonDocument> document) {
        return document.orElseThrow().root().getAsJsonObject().get("v").getAsInt();
    }

    @Test
    @DisplayName("a second read of an unchanged file is answered from what was held")
    void anUnchangedFileIsNotReadAgain() throws IOException {
        Path path = file("quest.json", "{\"v\": 1}");

        assertEquals(1, value(parse(path, new Problems())));
        assertEquals(1, value(parse(path, new Problems())));

        assertEquals(1, ParsedFiles.misses(), "the file was read once");
        assertEquals(1, ParsedFiles.hits(), "and the second read was answered from it");
    }

    @Test
    @DisplayName("a file whose size or modified time moved is read again")
    void aChangedFileIsReadAgain() throws IOException {
        Path path = file("quest.json", "{\"v\": 1}");
        parse(path, new Problems());

        // The size, which is the half a file system will always report.
        Files.writeString(path, "{\"v\": 22}", StandardCharsets.UTF_8);
        assertEquals(22, value(parse(path, new Problems())));

        // And the clock, which is the half that can be wrong -- a write that keeps a file's length. Here
        // it is set forward explicitly rather than raced for, so the case is about the key and not about
        // how fast this machine can write two files.
        Files.setLastModifiedTime(path, FileTime.fromMillis(
                Files.getLastModifiedTime(path).toMillis() + 5_000L));
        assertEquals(22, value(parse(path, new Problems())));

        assertEquals(3, ParsedFiles.misses());
        assertEquals(0, ParsedFiles.hits());
    }

    @Test
    @DisplayName("what a file said is said again on every load")
    void problemsAreReplayed() throws IOException {
        // The trap this exists for: a cache that returned the document and dropped the messages would
        // make a pack's problems appear on the first load and vanish afterwards, which reads as a server
        // that fixed itself.
        Path path = file("broken.json", "{\"v\": }");

        Problems first = new Problems();
        assertTrue(parse(path, first).isEmpty());
        int said = first.all().size();
        assertTrue(said > 0, "a file that does not parse says so");

        Problems again = new Problems();
        assertTrue(parse(path, again).isEmpty());
        assertEquals(said, again.all().size(), "and says the same the second time, from what was held");
        assertEquals(first.all().get(0).message(), again.all().get(0).message());
        assertEquals(1, ParsedFiles.hits(), "the second load did not re-read it");
    }

    @Test
    @DisplayName("a file's own problems are held with it rather than leaking into the load's")
    void aHeldEntryCarriesOnlyItsOwnProblems() throws IOException {
        Path good = file("good.json", "{\"v\": 1}");
        Path bad = file("bad.json", "{\"v\": }");

        Problems first = new Problems();
        parse(good, first);
        parse(bad, first);
        int total = first.all().size();

        // A second load of the good file alone: it must bring back none of the bad file's messages.
        Problems second = new Problems();
        parse(good, second);
        assertTrue(second.all().isEmpty(),
                "a hit replays what that file said, not what the load before it said");

        Problems third = new Problems();
        parse(bad, third);
        assertEquals(total, third.all().size(),
                "and the bad file's own message is still there when it is the one being read");
        assertEquals(2, ParsedFiles.hits(), "one hit for each of the two files read a second time");
    }

    @Test
    @DisplayName("a file this process wrote is forgotten, and that is what closes the same-tick case")
    void aWriteIsCaughtByTheForgetRatherThanTheClock() throws IOException {
        Path path = file("quest.json", "{\"v\": 1}");
        parse(path, new Problems());
        FileTime when = Files.getLastModifiedTime(path);

        // A write that keeps the file's length and lands in the same tick of a coarse clock: exactly the
        // edit the modification time cannot see. `0.5` to `0.7` is this shape.
        Files.writeString(path, "{\"v\": 2}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(path, when);

        assertEquals(1, value(parse(path, new Problems())),
                "the stat says nothing changed, so what was held is served -- the hazard, pinned");

        // And the fix, which is not a better key but the writer saying so. This is what JsonFile.write
        // does for every file the editor writes.
        int hitsBefore = ParsedFiles.hits();
        ParsedFiles.forget(path);
        assertEquals(2, value(parse(path, new Problems())),
                "forgetting the file is what makes the next read see the edit");
        assertEquals(hitsBefore, ParsedFiles.hits(),
                "and that read was not answered from the stale entry");
    }

    @Test
    @DisplayName("a structural edit forgets everything, because the set of files may have changed")
    void clearForgetsEveryFile() throws IOException {
        Path one = file("one.json", "{\"v\": 1}");
        Path two = file("two.json", "{\"v\": 2}");
        parse(one, new Problems());
        parse(two, new Problems());
        assertEquals(2, ParsedFiles.held());

        // A folder moved, a manifest written, a chapter set aside: no single path describes it.
        ParsedFiles.clear();
        assertEquals(0, ParsedFiles.held());

        parse(one, new Problems());
        parse(two, new Problems());
        assertEquals(4, ParsedFiles.misses(), "both are read again rather than trusted");
    }

    @Test
    @DisplayName("a file that is not there is reported by the read, and is not held")
    void aMissingFileIsNotHeld() {
        Path gone = directory.resolve("nothing.json");

        Problems problems = new Problems();
        assertTrue(parse(gone, problems).isEmpty());
        assertEquals(1, problems.all().size(), "the read says what happened, in the author's terms");
        assertEquals(0, ParsedFiles.held(),
                "there is no stamp to key it by, so nothing is remembered about it");
    }
}
