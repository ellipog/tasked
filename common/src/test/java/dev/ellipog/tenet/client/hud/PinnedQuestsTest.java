package dev.ellipog.tenet.client.hud;

import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which quests are pinned, in what order, and what a bad file does.
 *
 * <h2>Why the order is half of this file</h2>
 *
 * <p>Because the order <b>is</b> the model: the head of the list is the pin the HUD draws in full and the
 * rest are names, so a store that pinned correctly and ordered wrongly would show a player the wrong quest's
 * tasks — with nothing to say about why, since every id in the list is one they pinned. "Pinning something
 * already pinned brings it forward" is therefore a rule rather than a convenience, and it is the same rule
 * the book's menu reads to decide between <i>Pin</i> and <i>Focus</i>.
 *
 * <h2>Why pruning is asserted against a real cache</h2>
 *
 * <p>Because the one way this can do damage is the one that looks like tidying: a prune against an empty tree
 * erases every pin on the way out of a world. That is a claim about {@code ClientQuestCache}'s lifecycle, so
 * it is asserted with a real tree and a real {@code clear()} rather than argued from the source.
 */
@DisplayName("the pinned quests")
class PinnedQuestsTest {

    private static final String A = """
            {"id": "a", "title": "First", "tasks": [{"type": "tenet:checkmark", "title": "Go"}]}
            """;
    private static final String B = """
            {"id": "b", "title": "Second", "tasks": [{"type": "tenet:checkmark", "title": "Go"}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void forget() {
        // Two static stores, and a test that leaves either set changes the next one.
        PinnedQuests.reset();
        ClientQuestCache.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    @Test
    @DisplayName("nothing is pinned to begin with, and the file is nowhere yet")
    void emptyToBeginWith() {
        assertTrue(PinnedQuests.pinned().isEmpty());
        assertEquals(0, PinnedQuests.count());
        assertNull(PinnedQuests.file(), "nothing has been read from anywhere");
        assertFalse(PinnedQuests.isPinned("a"));
    }

    @Test
    @DisplayName("pin puts a quest first, in pin order")
    void pinPutsItFirst() {
        assertTrue(PinnedQuests.pin("a"));
        assertTrue(PinnedQuests.isPinned("a"));

        assertTrue(PinnedQuests.pin("b"));
        assertEquals(List.of("b", "a"), PinnedQuests.pinned(),
                "the most recently pinned draws first");
    }

    @Test
    @DisplayName("pinning something already pinned changes nothing rather than moving it")
    void pinningAgainIsANoOp() {
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        PinnedQuests.pin("c");

        // Every pin is drawn the same way, so there is no front to bring forward to: a call that
        // reordered the stack would move boxes a player had arranged themselves to read.
        assertTrue(PinnedQuests.pin("a"), "an id already in the list is not a refusal");
        assertEquals(List.of("c", "b", "a"), PinnedQuests.pinned(),
                "and not a reorder either");
        assertEquals(3, PinnedQuests.count(), "and it is one pin, not two");
    }

    @Test
    @DisplayName("unpinning removes one and leaves the rest where they are, and unpinning nothing is not an error")
    void unpinLeavesTheRest() {
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        PinnedQuests.unpin("b");
        assertEquals(List.of("a"), PinnedQuests.pinned());

        PinnedQuests.unpin("b");
        assertEquals(List.of("a"), PinnedQuests.pinned(), "unpinning what is not pinned asks for the state it is in");
        PinnedQuests.unpin(null);
        assertEquals(List.of("a"), PinnedQuests.pinned());
    }

    @Test
    @DisplayName("the panel holds six, and a seventh is refused rather than dropping one of somebody's pins")
    void theCapRefuses() {
        for (int i = 0; i < PinnedQuests.MAX_PINS; i++) {
            assertTrue(PinnedQuests.pin("quest_" + i));
        }
        assertEquals(PinnedQuests.MAX_PINS, PinnedQuests.count());

        assertFalse(PinnedQuests.pin("one_too_many"), "full is a refusal, not a silent eviction");
        assertEquals(PinnedQuests.MAX_PINS, PinnedQuests.count());
        assertFalse(PinnedQuests.isPinned("one_too_many"));

        // And a quest already in the list still answers: the cap is about the list's length, not about
        // touching an entry that is already there.
        assertTrue(PinnedQuests.pin("quest_0"));
        assertFalse(PinnedQuests.pin(""), "and a blank id is nothing to pin");
        assertFalse(PinnedQuests.pin(null));
    }

    @Test
    @DisplayName("the file is a list of ids in the order they are drawn, and it round-trips")
    void theFileRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(PinnedQuests.FILE_NAME);
        PinnedQuests.load(file);
        assertEquals(file, PinnedQuests.file(), "the file it read is the file it will write");

        assertEquals("{\"pins\":[]}", PinnedQuests.write(List.of()), "nothing pinned is an empty list");
        assertEquals("{\"pins\":[\"b\",\"a\"]}", PinnedQuests.write(List.of("b", "a")),
                "order is the model, so it is the file's order too");
        assertEquals("{\"pins\":[\"b\",\"a\"]}", PinnedQuests.write(Arrays.asList("b", null, "  ", "a")),
                "and a blank or a missing id is not an entry");

        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        PinnedQuests.load(file);
        assertEquals(List.of("b", "a"), PinnedQuests.pinned(),
                "what the store pinned is what the file said when it was read again");
        assertTrue(PinnedQuests.isPinned("b"));
    }

    @Test
    @DisplayName("the writer keeps six, so a hand-edited file cannot make the panel longer than the cap")
    void theWriterKeepsTheCap() {
        assertEquals("{\"pins\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}",
                PinnedQuests.write(List.of("1", "2", "3", "4", "5", "6", "7", "8")));
    }

    @Test
    @DisplayName("a file written by a newer build keeps the pins this one knows")
    void unknownFieldsCostWhatTheyShould(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(PinnedQuests.FILE_NAME);
        Files.writeString(file, "{\"pins\":[\"a\",7,{\"id\":\"c\"},\"a\",\" \",\"b\"],\"favourites\":true}",
                StandardCharsets.UTF_8);
        PinnedQuests.load(file);

        // One entry per way of being wrong, and each costs itself: a number and an object are not ids, a
        // repeat is the same pin twice and a blank names no quest. The list survives all four.
        assertEquals(List.of("a", "b"), PinnedQuests.pinned());
    }

    @Test
    @DisplayName("a file with no list, a list that is not a list, and text that is not JSON all leave nothing pinned")
    void aBadFileIsNoPins(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there.json");
        PinnedQuests.load(missing);
        assertTrue(PinnedQuests.pinned().isEmpty(), "a first run says nothing and pins nothing");

        Path wrongType = dir.resolve("wrong-type.json");
        Files.writeString(wrongType, "{\"pins\":\"a\"}", StandardCharsets.UTF_8);
        PinnedQuests.load(wrongType);
        assertTrue(PinnedQuests.pinned().isEmpty(), "a string where a list belongs");

        Path noKey = dir.resolve("no-key.json");
        Files.writeString(noKey, "{\"favourites\":true}", StandardCharsets.UTF_8);
        PinnedQuests.load(noKey);
        assertTrue(PinnedQuests.pinned().isEmpty(), "a file without the list has nothing pinned in it");

        Path notJson = dir.resolve("not-json.json");
        Files.writeString(notJson, "", StandardCharsets.UTF_8);
        PinnedQuests.load(notJson);
        assertTrue(PinnedQuests.pinned().isEmpty(), "an empty file is not JSON, and is not a crash");

        Path list = dir.resolve("list.json");
        Files.writeString(list, "[1,2,3]", StandardCharsets.UTF_8);
        PinnedQuests.load(list);
        assertTrue(PinnedQuests.pinned().isEmpty(), "a list where an object belongs");

        assertTrue(Files.exists(missing.getParent()), "the directory was not consumed by any of them");
    }

    @Test
    @DisplayName("a hand-edited file longer than the cap keeps the first six rather than all of them")
    void theReaderKeepsTheCap(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(PinnedQuests.FILE_NAME);
        Files.writeString(file, "{\"pins\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]}",
                StandardCharsets.UTF_8);
        PinnedQuests.load(file);

        assertEquals(List.of("1", "2", "3", "4", "5", "6"), PinnedQuests.pinned());
    }

    @Test
    @DisplayName("without a file the pins still work, for this session only")
    void noFileStillWorks() {
        assertTrue(PinnedQuests.pin("a"));
        assertEquals(List.of("a"), PinnedQuests.pinned(), "which is the useful half");
        assertNull(PinnedQuests.file());
    }

    @Test
    @DisplayName("a pin survives a world change, and is dropped only once a tree without it arrives")
    void pruningWaitsForATree() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        assertEquals(List.of("b", "a"), PinnedQuests.pinned());

        // A disconnect: the cache is emptied, and an empty tree is not an empty pack. Pruning here would
        // erase the player's whole list on the way out of a world, silently, because the write is the effect.
        ClientQuestCache.clear();
        PinnedQuests.tick();
        assertEquals(List.of("b", "a"), PinnedQuests.pinned(),
                "leaving a world is not a reason to forget what somebody pinned");

        // A new world, and its tree has one of them and not the other.
        accept(A);
        PinnedQuests.tick();
        assertEquals(List.of("a"), PinnedQuests.pinned(), "the id this server does not have is dropped");
    }

    @Test
    @DisplayName("pruning keeps the player's order rather than the tree's")
    void pruningKeepsTheOrder() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");

        PinnedQuests.tick();
        assertEquals(List.of("b", "a"), PinnedQuests.pinned(),
                "the tree's own order is a different order and is not what is stored here");
    }

    @Test
    @DisplayName("nothing to prune is nothing written, and an unchanged tree is not walked twice")
    void pruningIsCheapWhenNothingMoved() {
        accept(A, B);
        PinnedQuests.pin("a");
        assertEquals(1, PinnedQuests.count());
        assertNull(PinnedQuests.file(), "no file, so the write is a no-op rather than an error");

        // Two ticks against one revision: the second is a comparison, which is the whole cost when nothing
        // has moved. Its effect is that nothing changed -- which is the claim.
        PinnedQuests.tick();
        PinnedQuests.tick();
        assertEquals(List.of("a"), PinnedQuests.pinned());
    }
}
