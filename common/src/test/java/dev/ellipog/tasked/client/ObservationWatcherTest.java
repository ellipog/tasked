package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.QuestSync;
import dev.ellipog.tasked.quest.Fixtures;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.task.ObservationTask;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The list of tasks a client tick can possibly observe.
 *
 * <h2>The bug this exists for, and the one it must not become</h2>
 *
 * <p>The tick used to walk every quest and every task in the book, every tick, with no screen open —
 * so a player who was mining paid for a book nobody was reading, and the price grew with the pack. It
 * now walks the tasks whose {@code observeType} is one this build knows, gathered once per tree.
 *
 * <p>The regression the second half of that guards is the quiet one: a list keyed on the wrong thing
 * looks correct and walks less, while a task that should be watched goes unwatched. So the tests below
 * are about the <b>key</b> as much as the contents — the list must survive a progress message (or it
 * is a whole-book walk per message), and it must not survive a tree change (or a quest added after the
 * reload is never watched).
 *
 * <p>Deliberately headless: the tick's own half needs a {@code Minecraft}, a level and a crosshair,
 * which no test here can build. What is asserted is the half that decides the tick's cost.
 */
@DisplayName("The observation watch list")
class ObservationWatcherTest {

    /** One observation task, one of another kind, and one item task: only the first is watchable. */
    private static final String WATCHING = """
            {"id": "watch", "title": "Look at a rock", "tasks": [
              {"type": "tasked:checkmark", "title": "Say hello"},
              {"type": "tasked:observation", "observeType": "block",
               "toObserve": "minecraft:stone", "timer": 3},
              {"type": "tasked:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String SECOND = """
            {"id": "other", "title": "Look at a rock twice", "tasks": [
              {"type": "tasked:observation", "observeType": "entity_type",
               "toObserve": "minecraft:cow", "timer": 0}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
        ObservationWatcher.reset();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    @Test
    @DisplayName("only the tasks whose kind this build knows are watchable")
    void onlyTheObservableTasks() {
        accept(WATCHING);

        List<ObservationWatcher.Watchable> watchables = ObservationWatcher.watchables();

        assertEquals(1, watchables.size(),
                "the checkmark and the item task can never be watched, so the tick must not carry them");
        ObservationWatcher.Watchable watchable = watchables.get(0);
        assertEquals("watch", watchable.questId());
        assertEquals(1, watchable.index(), "the observation task is the second one in the file");
        assertEquals(ObservationTask.ObserveType.BLOCK, watchable.kind());
        assertEquals("minecraft:stone", watchable.target());
        assertEquals(3, watchable.ticks(), "the timer travels with the list, so the tick reads no task");
    }

    @Test
    @DisplayName("a kind this build does not know is not watchable, and is not an error either")
    void unknownKindsAreSkipped() {
        // Mutated on the *wire* rather than written in a file, and that is the point of the case: the
        // file codec refuses an unknown kind outright (its enum is the validator), so a kind this build
        // cannot name can only reach a client from a server that is ahead of it. That is the whole
        // reason `TaskEntry.observation()` answers null instead of throwing, and it is a case no fixture
        // file can build.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(WATCHING));
        String wire = new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8);
        String mutated = wire.replace("\"observeType\":\"block\"", "\"observeType\":\"telepathy\"");
        assertNotEquals(wire, mutated,
                "the mutation must have applied, or this case asserts nothing at all");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                mutated.getBytes(StandardCharsets.UTF_8));

        assertTrue(ObservationWatcher.watchables().isEmpty(),
                "the row still draws, and nothing is watched for it");
    }

    @Test
    @DisplayName("the list is kept between ticks, and rebuilt when the tree moves")
    void keptUntilTheTreeMoves() {
        accept(WATCHING);
        List<ObservationWatcher.Watchable> first = ObservationWatcher.watchables();

        assertSame(first, ObservationWatcher.watchables(),
                "gathering it per tick would be the whole-book walk this exists to remove");

        accept(WATCHING);
        List<ObservationWatcher.Watchable> second = ObservationWatcher.watchables();
        assertNotSame(first, second, "a new tree is a new answer");
        assertEquals(first, second, "and for the same file it is the same answer");
    }

    @Test
    @DisplayName("a progress message does not rebuild it")
    void progressDoesNotRebuild() {
        accept(WATCHING);
        List<ObservationWatcher.Watchable> first = ObservationWatcher.watchables();
        long before = ClientQuestCache.progressRevision();

        progress("{\"quests\":{\"watch\":{\"state\":\"STARTED\",\"tasks\":[0,0,0],"
                + "\"claimable\":false}}}");

        assertNotEquals(before, ClientQuestCache.progressRevision(),
                "the test is worthless unless the progress actually moved");
        assertSame(first, ObservationWatcher.watchables(),
                "the list holds no per-player fact, so a player's progress is no reason to walk the book");
    }

    @Test
    @DisplayName("a second quest's observation task joins the list")
    void theListGrowsWithTheTree() {
        accept(WATCHING, SECOND);

        assertEquals(2, ObservationWatcher.watchables().size(),
                "and the second one's timer is read as the file wrote it, zero included");
        assertEquals(0, ObservationWatcher.watchables().get(1).ticks());
    }

    @Test
    @DisplayName("a finished quest is not watched for")
    void finishedIsNotEligible() {
        accept(WATCHING);
        progress(state("COMPLETED", "[0,0,0]"));

        assertFalse(ObservationWatcher.eligible("watch", 1));
    }

    @Test
    @DisplayName("a task that is already counted is not watched for")
    void countedIsNotEligible() {
        accept(WATCHING);
        // Position one is the observation task's own row, and one is all it needs.
        progress(state("STARTED", "[0,1,0]"));

        assertFalse(ObservationWatcher.eligible("watch", 1));
    }

    @Test
    @DisplayName("a row this player's conditions shut is not watched for")
    void lockedIsNotEligible() {
        accept(WATCHING);
        progress("{\"quests\":{\"watch\":{\"state\":\"STARTED\",\"tasks\":[0,0,0],"
                + "\"taskLocks\":{\"1\":[0]},\"claimable\":false}}}");

        assertFalse(ObservationWatcher.eligible("watch", 1),
                "the server would refuse the submission, and sending it anyway would tell the player "
                        + "their conditions are unmet once per watching cycle");
    }

    @Test
    @DisplayName("an open task is watched for, and a quest locked by its prerequisites still is")
    void openIsEligible() {
        accept(WATCHING);
        progress(state("UNLOCKED", "[0,0,0]"));
        assertTrue(ObservationWatcher.eligible("watch", 1));

        // LOCKED is a quest gated by its own prerequisites, and that has never been a reason to stop
        // watching: the question is "is there anything left to watch for", not "may this player submit
        // it". Pinned here because the plan's wording for this list said "prerequisites met", and the
        // code has never asked that -- so this test records what the behaviour is rather than what a
        // summary of it said.
        progress(state("LOCKED", "[0,0,0]"));
        assertTrue(ObservationWatcher.eligible("watch", 1));
    }

    @Test
    @DisplayName("a task with no progress reported yet is still watched for")
    void noProgressYetIsStillWatchable() {
        accept(WATCHING);

        // A join delivers the tree and the progress as two messages, and the window between them is
        // real. Nothing is reported for this quest yet, so nothing is known to be finished, counted or
        // shut -- and an empty progress record reads as exactly that. The server is the half that
        // refuses a submission it does not want, which is why this direction is the safe one.
        assertFalse(ClientQuestCache.entries().isEmpty(), "the tree arrived");
        assertTrue(ObservationWatcher.eligible("watch", 1));
    }

    /** One quest's progress, with the state and the per-task counts given. */
    private static String state(String questState, String tasks) {
        return "{\"quests\":{\"watch\":{\"state\":\"" + questState + "\",\"tasks\":" + tasks
                + ",\"claimable\":false}}}";
    }

    /** A full progress sync, as the payload handler delivers it. */
    private static void progress(String json) {
        ClientQuestCache.acceptProgress(UUID.randomUUID(), 100L,
                json.getBytes(StandardCharsets.UTF_8), 50L);
    }
}
