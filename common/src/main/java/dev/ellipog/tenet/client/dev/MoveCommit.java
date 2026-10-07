package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.editor.EditorOps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a drag's release should send, worked out from its inputs alone.
 *
 * <h2>Why this is a separate class, and it is not tidiness</h2>
 *
 * <p>The release that commits a drag has been wrong twice, both times in ways no test could see:
 *
 * <ul>
 *   <li>every other selected quest was written to <b>(0,0)</b>, because the loop read a pending position
 *       without asking whether one was still held — and {@code movedX} answers zero for an id it does not
 *       have;</li>
 *   <li>then a single-node drag <b>committed nothing at all</b>, because the dragged node's id was read from
 *       a field the caller had already cleared, so the node never entered the list, never got recorded, and
 *       no operation was sent. It snapped back on the next frame.</li>
 * </ul>
 *
 * <p>Both were in {@code QuestBookScreen}, which <b>no test instantiates</b> — so 1,565 passing tests were
 * blind to them and a player found them instead. The cause of that blindness is that the decision and its
 * side effects live in the same method: the decision is a pure function of a handful of values, and the side
 * effects are a write into a pending map and a packet.
 *
 * <p>This class is the decision. It takes the gesture and a way to find a chapter, and returns the
 * operations; it touches no cache, no session, no wire and no game. That leaves the screen with two side
 * effects and nothing to decide, which is a far smaller surface to be wrong in — and it makes every rule the
 * two faults broke into something a test can state.
 *
 * <h2>The rule about positions, which is where the (0,0) fault lived</h2>
 *
 * <p>A move is planned <b>only for a node that has a resolved position</b>. That is the whole of the fix for
 * the first fault, expressed once: there is no path here that can invent a coordinate, because the
 * coordinates are the input. A caller that has no position for a node does not pass it, and a node the
 * chapter lookup does not know is dropped — the same rule {@code BatchPlan} applies, and for the same
 * reason: a move the server was never asked for leaves the canvas holding a position until the next tree and
 * then snapping back, which reads as an edit that undid itself.
 *
 * <h2>Why the dragged node goes first</h2>
 *
 * <p>The batch is applied in order, and the node the author is holding is the one they are looking at. It is
 * also the only node whose position came from the release rather than from a pending map, so it is the one
 * whose move is certain.
 */
public final class MoveCommit {

    private MoveCommit() {
    }

    /**
     * One node's move, with the position already resolved by the caller.
     *
     * @param id the quest
     * @param x  where it was dropped, in canvas coordinates
     * @param y  the same for y
     */
    public record Move(String id, float x, float y) {
    }

    /** One chapter's share of the gesture, as the operation to send. */
    public record Chapter(String chapterId, EditorOp op) {
    }

    /**
     * The operations a release should send, in the order they should be sent.
     *
     * @param dragged   the node under the pointer, or null when nothing was held
     * @param draggedX  where it was dropped
     * @param draggedY  the same for y
     * @param selected  the rest of the selection, each with the pending position the canvas is drawing it at
     * @param chapterOf the chapter a quest belongs to, or null when nothing knows it
     */
    public static List<Chapter> plan(String dragged, float draggedX, float draggedY,
                                     List<Move> selected,
                                     java.util.function.Function<String, String> chapterOf) {
        Map<String, List<Move>> byChapter = grouped(dragged, draggedX, draggedY, selected, chapterOf);
        List<Chapter> plan = new ArrayList<>(byChapter.size());
        for (Map.Entry<String, List<Move>> chapter : byChapter.entrySet()) {
            List<Move> moves = chapter.getValue();
            // **A chapter's share of one is that plain op rather than a one-element batch**, which is the
            // rule `sendBulk` already follows for duplicate and delete: a batch is the gesture's shape, and a
            // gesture with one node in this chapter is one edit.
            EditorOp op = moves.size() == 1
                    ? new EditorOp.Move(moves.get(0).id(), (long) moves.get(0).x(), (long) moves.get(0).y())
                    : EditorOps.batch(moves.stream()
                            .map(move -> (EditorOp) new EditorOp.Move(move.id(), (long) move.x(), (long) move.y()))
                            .toList());
            plan.add(new Chapter(chapter.getKey(), op));
        }
        return plan;
    }

    /**
     * The nodes a plan actually carries, in the order it carries them.
     *
     * <p>For the caller's recording step, and it exists so the recording and the sending cannot disagree:
     * the screen records these positions and sends these operations, from one grouping. Recording without
     * sending leaves the tree disagreeing for a round trip; sending without recording leaves the canvas
     * showing the server's old answer for the same round trip — and the fault this replaces was worse than
     * either, because it sent an operation for a node it had never recorded and the position it invented was
     * {@code (0,0)}.
     */
    public static List<Move> carried(String dragged, float draggedX, float draggedY,
                                     List<Move> selected,
                                     java.util.function.Function<String, String> chapterOf) {
        List<Move> carried = new ArrayList<>();
        for (List<Move> moves : grouped(dragged, draggedX, draggedY, selected, chapterOf).values()) {
            carried.addAll(moves);
        }
        return carried;
    }

    /**
     * The gesture, ordered and grouped by chapter — the one place either answer is worked out.
     *
     * <p>Both public methods read this, so "what is recorded" and "what is sent" cannot drift: they are the
     * same nodes in the same order by construction rather than by two loops that agree today.
     */
    private static Map<String, List<Move>> grouped(String dragged, float draggedX, float draggedY,
                                                   List<Move> selected,
                                                   java.util.function.Function<String, String> chapterOf) {
        // The dragged node first, then the selection, so the batch reads in the order the gesture happened.
        // The same id can appear in both -- a selected node that is also the one being dragged -- and it is
        // taken once, from the release, because that is the position the author last saw.
        List<Move> ordered = new ArrayList<>();
        if (dragged != null) {
            ordered.add(new Move(dragged, draggedX, draggedY));
        }
        for (Move move : selected) {
            if (move != null && !move.id().equals(dragged)) {
                ordered.add(move);
            }
        }

        // Grouped by chapter, keeping the order within each. A node with no id, or one the lookup does not
        // know, is dropped rather than sent -- see the class note.
        Map<String, List<Move>> byChapter = new LinkedHashMap<>();
        for (Move move : ordered) {
            if (move.id() == null || move.id().isBlank()) {
                continue;
            }
            String chapter = chapterOf.apply(move.id());
            if (chapter == null || chapter.isBlank()) {
                continue;
            }
            byChapter.computeIfAbsent(chapter, key -> new ArrayList<>()).add(move);
        }
        return byChapter;
    }
}
