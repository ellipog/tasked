package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.editor.EditorOp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a drag's release plans — the rules two real faults broke.
 *
 * <h2>Why these tests exist at all, and why they are here rather than on the screen</h2>
 *
 * <p>The release that commits a drag has been wrong twice, and <b>no test could see either</b>:
 *
 * <ul>
 *   <li>every other selected quest was written to <b>(0,0)</b> — the loop read a pending position without
 *       asking whether one was held, and {@code movedX} answers zero for an id it does not have;</li>
 *   <li>then a single-node drag <b>committed nothing</b> — the dragged node's id came from a field the caller
 *       had already cleared, so it never entered the list and no operation was sent.</li>
 * </ul>
 *
 * <p>Both lived in {@code QuestBookScreen}, which nothing instantiates, so 1,565 passing tests were blind to
 * them and a player found them instead. The decision is now a pure function of its inputs, which is what
 * makes each rule below something a test can state — and each one is a fault that actually happened, not a
 * hypothetical.
 */
@DisplayName("what a drag's release plans")
class MoveCommitTest {

    /** A lookup that puts every id in one chapter, which is the ordinary case. */
    private static final Function<String, String> ONE_CHAPTER = id -> "first_steps";

    /** A lookup that knows nothing, which is a cache that has not arrived. */
    private static final Function<String, String> KNOWS_NOTHING = id -> null;

    @Test
    @DisplayName("a single-node drag plans a move for that node")
    void aSingleNodeDragPlansThatNode() {
        // **The silent no-op, pinned.** The dragged node's id was read from a field the caller cleared before
        // calling, so it never reached the list and nothing was sent -- the node snapped back on the next
        // frame with nothing in any log. Here the id is an argument, so there is no field to be cleared.
        List<MoveCommit.Chapter> plan = MoveCommit.plan("quest", 64, 32, List.of(), ONE_CHAPTER);

        assertEquals(1, plan.size(), "one chapter, one operation");
        assertEquals("first_steps", plan.get(0).chapterId());
        assertInstanceOf(EditorOp.Move.class, plan.get(0).op(),
                "a chapter's share of one is that plain op, not a one-element batch");
        assertEquals(new EditorOp.Move("quest", 64, 32), plan.get(0).op(),
                "and it carries the position the author dropped it at");
    }

    @Test
    @DisplayName("nothing held plans nothing")
    void nothingHeldPlansNothing() {
        // A release with no drag: the caller passes null and the selection is empty. It must not invent a
        // move, and in particular must not invent one for the empty id.
        assertTrue(MoveCommit.plan(null, 0, 0, List.of(), ONE_CHAPTER).isEmpty(),
                "a release that held nothing plans nothing");
        assertTrue(MoveCommit.carried(null, 0, 0, List.of(), ONE_CHAPTER).isEmpty(),
                "and there is nothing to record either");
    }

    @Test
    @DisplayName("a node the chapter lookup does not know is dropped, not sent to the origin")
    void anUnknownNodeIsDropped() {
        // **The (0,0) fault, from the other side.** The old loop read `movedX(id)` for every id it planned,
        // and that answers zero for an id it does not hold -- so a node with no position was written to the
        // origin. Here a node with no resolved position is simply not passed, and one the lookup does not know
        // is dropped, so there is no path that can invent a coordinate.
        List<MoveCommit.Chapter> plan = MoveCommit.plan("quest", 64, 32, List.of(), KNOWS_NOTHING);

        assertTrue(plan.isEmpty(), "a node no chapter claims is not sent anywhere");
        assertTrue(MoveCommit.carried("quest", 64, 32, List.of(), KNOWS_NOTHING).isEmpty(),
                "and it is not recorded either, so the canvas cannot hold a move the server never got");
    }

    @Test
    @DisplayName("the dragged node comes first, and is taken once")
    void theDraggedNodeComesFirstAndOnce() {
        // The batch applies in order and the node under the pointer is the one being looked at. It can also
        // appear in the selection -- a selected node that is also the one dragged -- and it is taken once,
        // from the release, because that is the position the author last saw.
        List<MoveCommit.Move> selection = List.of(
                new MoveCommit.Move("other", 10, 10),
                new MoveCommit.Move("quest", 1, 1));
        List<MoveCommit.Chapter> plan = MoveCommit.plan("quest", 64, 32, selection, ONE_CHAPTER);

        assertEquals(1, plan.size());
        EditorOp.Batch batch = assertInstanceOf(EditorOp.Batch.class, plan.get(0).op(),
                "two nodes in one chapter is a batch");
        assertEquals(2, batch.ops().size(), "the dragged node is taken once, not twice");
        assertEquals(new EditorOp.Move("quest", 64, 32), batch.ops().get(0),
                "the dragged node is first, at the release position");
        assertEquals(new EditorOp.Move("other", 10, 10), batch.ops().get(1),
                "and the selection follows at the position the canvas was drawing it");
    }

    @Test
    @DisplayName("a selection spanning two chapters is one operation each")
    void twoChaptersAreTwoOperations() {
        // The history lives in a chapter's editor, so one batch per chapter is the honest granularity: two
        // steps, which is what the model can offer. One step for both would be a history the model cannot
        // express.
        Map<String, String> chapters = Map.of("a", "one", "b", "two");
        List<MoveCommit.Chapter> plan = MoveCommit.plan("a", 5, 5,
                List.of(new MoveCommit.Move("b", 6, 6)), chapters::get);

        assertEquals(2, plan.size(), "one operation per chapter");
        assertEquals(List.of("one", "two"), plan.stream().map(MoveCommit.Chapter::chapterId).toList(),
                "in the order the gesture met them");
        for (MoveCommit.Chapter chapter : plan) {
            assertInstanceOf(EditorOp.Move.class, chapter.op(),
                    "each chapter's share is one node, so each is a plain op");
        }
    }

    @Test
    @DisplayName("what is carried and what is sent are the same nodes in the same order")
    void carriedMatchesThePlan() {
        // The recording and the sending read one grouping, so they cannot drift. Recording without sending
        // leaves the tree wrong for a round trip; sending without recording leaves the canvas showing the
        // server's old answer -- and the fault this replaced was worse than either, because it sent an
        // operation for a node it had never recorded, at a position it invented.
        List<MoveCommit.Move> selection = List.of(
                new MoveCommit.Move("other", 10, 10),
                new MoveCommit.Move("third", 20, 20));

        List<MoveCommit.Move> carried = MoveCommit.carried("quest", 64, 32, selection, ONE_CHAPTER);
        List<MoveCommit.Chapter> plan = MoveCommit.plan("quest", 64, 32, selection, ONE_CHAPTER);

        assertEquals(3, carried.size());
        EditorOp.Batch batch = assertInstanceOf(EditorOp.Batch.class, plan.get(0).op());
        assertEquals(carried.size(), batch.ops().size(), "the same count, from one grouping");
        for (int i = 0; i < carried.size(); i++) {
            assertEquals(new EditorOp.Move(carried.get(i).id(),
                            (long) carried.get(i).x(), (long) carried.get(i).y()),
                    batch.ops().get(i),
                    "and the same nodes in the same order, so the record and the op agree");
        }
    }

    @Test
    @DisplayName("a blank id is dropped rather than sent as an empty move")
    void aBlankIdIsDropped() {
        // `BatchPlan` drops these for the same reason: an operation naming no quest is one the server has to
        // refuse, and a refusal for something the author did not do reads as a fault in the editor.
        assertTrue(MoveCommit.plan("", 5, 5, List.of(new MoveCommit.Move("  ", 6, 6)), ONE_CHAPTER).isEmpty(),
                "neither a blank dragged id nor a blank selection id is planned");
    }
}
