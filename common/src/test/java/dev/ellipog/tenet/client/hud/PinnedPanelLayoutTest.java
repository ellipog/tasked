package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ui.kit.Measure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pinned boxes' shape, over a sweep of content.
 *
 * <h2>Why this is arithmetic worth asserting</h2>
 *
 * <p>Because every one of these facts is a way a HUD stack goes wrong without saying so, and none of them can
 * be seen in a screenshot of one stack:
 *
 * <ul>
 *   <li>each quest is its own box rather than one slab, so two pins cannot merge into one unreadable list;</li>
 *   <li>every box shows its own tasks, so a pin is not a name that says nothing about what is left to do;</li>
 *   <li>the stack is as tall as what it holds, so a box that grew without bound would run off the bottom of
 *       somebody's window -- and a box that did not grow would draw its last row over its own edge;</li>
 *   <li>a long title is <b>truncated</b> rather than allowed to widen the column, because a HUD element that
 *       grows with its content is an element that leaves the window;</li>
 *   <li>a task's count and tick own the right-hand end of their row, so the text is what gives way -- the
 *       other order puts the count off the edge of its own box;</li>
 *   <li>and the editor's empty stack is still a box, because a zero-sized element is one nobody can grab,
 *       which is the one arrangement that traps a player in that screen.</li>
 * </ul>
 *
 * <p>The font is {@code Measure.monospace}, so every number here is checkable by hand: six pixels a
 * character, ten a line. That is the same stand-in the wrap rules are asserted against, and for the same
 * reason -- a proportional fake would let a wrong-by-one reserve hide behind a plausible width.
 */
@DisplayName("the pinned boxes' layout")
class PinnedPanelLayoutTest {

    private static final Measure FONT = Measure.monospace(6, 10);

    /** The words, in the shape the painter supplies them: resolved, with the one counted sentence as one. */
    private static PinnedPanelLayout.Words words() {
        return new PinnedPanelLayout.Words("Complete", "Claimable", "sample", n -> "+" + n + " more");
    }

    private static PinnedPanelLayout.Task task(String text, int progress, int count, boolean done) {
        return new PinnedPanelLayout.Task(text, progress, count, done);
    }

    private static PinnedPanelLayout.Pin pin(String title, boolean complete, PinnedPanelLayout.Task... tasks) {
        return new PinnedPanelLayout.Pin(title, "Chapter One", complete, false, List.of(tasks));
    }

    private static PinnedPanelLayout.Column column(PinnedPanelLayout.Pin... pins) {
        return PinnedPanelLayout.column(List.of(pins), words(), FONT, false);
    }

    /** The one box of a one-pin stack, which is what most of these cases are about. */
    private static PinnedPanelLayout.Box only(PinnedPanelLayout.Column column) {
        assertEquals(1, column.boxes().size(), "one pin is one box");
        return column.boxes().get(0).box();
    }

    @Test
    @DisplayName("nothing pinned draws nothing at all, and the editor draws a sample instead")
    void emptyIsNothingLive() {
        PinnedPanelLayout.Column live = PinnedPanelLayout.column(List.of(), words(), FONT, false);
        assertTrue(live.empty(), "an element with nothing to say draws no frame");
        assertEquals(0, live.width());
        assertEquals(0, live.height());

        PinnedPanelLayout.Column editor = PinnedPanelLayout.column(List.of(), words(), FONT, true);
        assertFalse(editor.empty(), "and in the editor it is a box, or it could not be grabbed");
        assertEquals(1, editor.boxes().size());
        assertEquals(1, editor.boxes().get(0).box().rows().size(), "one sample line and nothing else");
        assertEquals(PinnedPanelLayout.Kind.SAMPLE, editor.boxes().get(0).box().rows().get(0).kind());
        assertEquals(PinnedPanelLayout.MIN_WIDTH, editor.width(),
                "a short sample still gets the narrowest a box may be");
        assertEquals("sample", editor.boxes().get(0).box().rows().get(0).text());
    }

    @Test
    @DisplayName("every pin is drawn in full, in the list's order, each in its own box")
    void everyPinIsInFull() {
        PinnedPanelLayout.Column column = column(
                pin("Head", false, task("Logs", 3, 8, false), task("Planks", 1, 4, false)),
                pin("Second", false),
                pin("Third", true));

        assertEquals(3, column.boxes().size(), "three pins are three boxes, not one slab");

        List<PinnedPanelLayout.Kind> head = column.boxes().get(0).box().rows().stream()
                .map(PinnedPanelLayout.Row::kind).toList();
        assertEquals(List.of(PinnedPanelLayout.Kind.TITLE,
                PinnedPanelLayout.Kind.TASK, PinnedPanelLayout.Kind.TASK), head);

        List<PinnedPanelLayout.Kind> second = column.boxes().get(1).box().rows().stream()
                .map(PinnedPanelLayout.Row::kind).toList();
        assertEquals(List.of(PinnedPanelLayout.Kind.TITLE), second, "a quest with no tasks is its name");

        List<PinnedPanelLayout.Kind> third = column.boxes().get(2).box().rows().stream()
                .map(PinnedPanelLayout.Row::kind).toList();
        assertEquals(List.of(PinnedPanelLayout.Kind.TITLE, PinnedPanelLayout.Kind.COMPLETE), third,
                "and a finished quest says so in a row of its own");

        assertEquals("Head", column.boxes().get(0).box().rows().get(0).text());
        assertEquals("Logs", column.boxes().get(0).box().rows().get(1).text());
        assertEquals("Planks", column.boxes().get(0).box().rows().get(2).text());
        assertEquals("Second", column.boxes().get(1).box().rows().get(0).text());
        assertEquals("Third", column.boxes().get(2).box().rows().get(0).text());
        assertEquals("Chapter One", column.boxes().get(1).box().rows().get(0).note(),
                "a title carries its chapter");
    }

    @Test
    @DisplayName("the boxes are stacked with a gap between them, and share one width")
    void boxesAreStackedAndOneWidth() {
        PinnedPanelLayout.Column column = column(pin("Head", false), pin("Second", false));

        PinnedPanelLayout.PlacedBox first = column.boxes().get(0);
        PinnedPanelLayout.PlacedBox second = column.boxes().get(1);
        assertEquals(0, first.y(), "the first box starts the stack");
        assertEquals(first.box().height() + PinnedPanelLayout.BOX_GAP, second.y(),
                "and the second starts after the first plus the gap that tells them apart");
        assertEquals(second.y() + second.box().height(), column.height(),
                "the stack ends where its last box does");
        assertEquals(first.box().width(), second.box().width(), "one column is one width");
        assertEquals(first.box().width(), column.width());
    }

    @Test
    @DisplayName("an unfinished quest is not marked finished, anywhere")
    void unfinishedIsUnmarked() {
        PinnedPanelLayout.Column column = column(pin("Head", false, task("Logs", 3, 8, false)));
        assertFalse(only(column).rows().stream()
                        .anyMatch(row -> row.kind() == PinnedPanelLayout.Kind.COMPLETE),
                "an unfinished quest is not marked finished");
    }

    @Test
    @DisplayName("a finished quest says Claimable while rewards are out, Complete once they are not")
    void finishedSaysWhatIsLeft() {
        PinnedPanelLayout.Box waiting = only(column(
                new PinnedPanelLayout.Pin("Head", "", true, true, List.of())));
        assertEquals("Claimable", waiting.rows().stream()
                        .filter(row -> row.kind() == PinnedPanelLayout.Kind.COMPLETE)
                        .findFirst().orElseThrow(() -> new AssertionError("no last line"))
                        .text(),
                "done is not the news, collectable is");

        PinnedPanelLayout.Box collected = only(column(
                new PinnedPanelLayout.Pin("Head", "", true, false, List.of())));
        assertEquals("Complete", collected.rows().stream()
                        .filter(row -> row.kind() == PinnedPanelLayout.Kind.COMPLETE)
                        .findFirst().orElseThrow(() -> new AssertionError("no last line"))
                        .text());
    }

    @Test
    @DisplayName("past six tasks a box counts the rest rather than growing past the window")
    void theTaskListIsCapped() {
        List<PinnedPanelLayout.Task> many = new ArrayList<>();
        for (int i = 0; i < PinnedPanelLayout.MAX_TASK_ROWS + 3; i++) {
            many.add(task("Task " + i, 0, 1, false));
        }
        PinnedPanelLayout.Box box = only(column(new PinnedPanelLayout.Pin("Head", "", false, false, many)));

        long listed = box.rows().stream().filter(row -> row.kind() == PinnedPanelLayout.Kind.TASK).count();
        assertEquals(PinnedPanelLayout.MAX_TASK_ROWS, listed, "a quest with twenty tasks is not twenty rows");

        PinnedPanelLayout.Row more = box.rows().stream()
                .filter(row -> row.kind() == PinnedPanelLayout.Kind.MORE).findFirst().orElseThrow();
        assertEquals("+3 more", more.text(), "and the sentence carries the count, which is why it is a function");
    }

    @Test
    @DisplayName("a long title is truncated to the box rather than widening it")
    void longTextIsTruncated() {
        PinnedPanelLayout.Box box = only(column(pin("A title long enough to need cutting down to size", false)));

        assertEquals(PinnedPanelLayout.MAX_WIDTH, box.width(), "the box stops at its own ceiling");
        String drawn = box.rows().get(0).text();
        assertTrue(drawn.endsWith("\u2026"), "and the title says it was cut: " + drawn);
        assertTrue(FONT.width(drawn) <= box.width() - PinnedPanelLayout.PAD * 2,
                "the drawn title fits the content width: " + FONT.width(drawn));
    }

    @Test
    @DisplayName("a narrow title gets the narrowest box, not a box the width of its text")
    void shortTextGetsTheFloor() {
        PinnedPanelLayout.Box box = only(column(new PinnedPanelLayout.Pin("Hi", "", false, false, List.of())));
        assertEquals(PinnedPanelLayout.MIN_WIDTH, box.width(),
                "a box four characters wide is not a box");
    }

    @Test
    @DisplayName("a task's text gives way to its count and its tick, which own the right-hand end")
    void aTaskReservesItsRightHandEnd() {
        PinnedPanelLayout.Box box = only(column(
                pin("Head", false, task("Collect a very great many oak logs", 3, 12, true))));
        PinnedPanelLayout.Row row = box.rows().get(1);
        int content = box.width() - PinnedPanelLayout.PAD * 2;
        String count = PinnedPanelLayout.countText(row);
        int right = PinnedPanelLayout.COLUMN_GAP + FONT.width(count)
                + PinnedPanelLayout.COLUMN_GAP + FONT.width(PinnedPanelLayout.TICK);

        assertTrue(FONT.width(row.text()) <= content - PinnedPanelLayout.ICON
                        - PinnedPanelLayout.ICON_GAP - right,
                "the sentence is cut to what is left: " + row.text());
        assertTrue(row.text().endsWith("\u2026"), "and it visibly was: " + row.text());
    }

    @Test
    @DisplayName("a task counting to one draws no count, which is what the book does too")
    void aCountOfOneIsNoCount() {
        PinnedPanelLayout.Box box = only(column(pin("Head", false, task("Say hello", 0, 1, false))));
        PinnedPanelLayout.Row row = box.rows().get(1);
        assertFalse(PinnedPanelLayout.hasCount(row));

        PinnedPanelLayout.Row eight = only(column(
                pin("Head", false, task("Logs", 3, 8, false)))).rows().get(1);
        assertTrue(PinnedPanelLayout.hasCount(eight));
        assertEquals("3 / 8", PinnedPanelLayout.countText(eight));
    }

    @Test
    @DisplayName("a count is clamped to its target, because a counter can overshoot one")
    void aCountIsClamped() {
        PinnedPanelLayout.Row row = only(column(
                pin("Head", false, task("Logs", 12, 8, true)))).rows().get(1);
        assertEquals("8 / 8", PinnedPanelLayout.countText(row));
    }

    @Test
    @DisplayName("a row knows which quest and which task it is, so the painter can find that task's icon")
    void aRowNamesItsTask() {
        PinnedPanelLayout.Column column = column(
                pin("Head", false,
                        task("One", 0, 1, false), task("Two", 0, 1, false), task("Three", 0, 1, false)),
                pin("Second", false, task("Four", 0, 1, false)));

        PinnedPanelLayout.Box head = column.boxes().get(0).box();
        assertEquals(0, head.rows().get(1).pinIndex());
        assertEquals(0, head.rows().get(1).taskIndex());
        assertEquals(0, head.rows().get(2).pinIndex());
        assertEquals(1, head.rows().get(2).taskIndex());
        assertEquals(0, head.rows().get(3).pinIndex());
        assertEquals(2, head.rows().get(3).taskIndex());
        assertEquals(-1, head.rows().get(0).taskIndex(), "a title is not a task");

        PinnedPanelLayout.Box second = column.boxes().get(1).box();
        assertEquals(1, second.rows().get(1).pinIndex(), "the second quest's task names the second quest");
        assertEquals(0, second.rows().get(1).taskIndex());
    }

    @Test
    @DisplayName("the chapter is cut against the title as drawn, so the two cannot overlap")
    void theNoteFollowsTheDrawnTitle() {
        PinnedPanelLayout.Box box = only(column(new PinnedPanelLayout.Pin(
                "A long quest name that will certainly be truncated somewhere", "A long chapter name too",
                false, false, List.of())));
        PinnedPanelLayout.Row row = box.rows().get(0);
        int content = box.width() - PinnedPanelLayout.PAD * 2;

        assertTrue(FONT.width(row.text()) + FONT.width(PinnedPanelLayout.NOTE_SEPARATOR)
                        + FONT.width(row.note()) <= content,
                "the two halves fit the row: " + row.text() + " | " + row.note());
        assertTrue(row.note().endsWith("\u2026"), "the chapter gives way first: " + row.note());
    }

    @Test
    @DisplayName("each box is as tall as its last row and no taller, and the stack is the boxes plus the gaps")
    void theHeightIsTheContent() {
        PinnedPanelLayout.Box one = only(column(pin("Head", false)));
        assertEquals(PinnedPanelLayout.PAD + titleRow() + PinnedPanelLayout.PAD, one.height());

        PinnedPanelLayout.Column two = column(pin("Head", false), pin("Second", false));
        assertEquals(one.height() + PinnedPanelLayout.BOX_GAP + one.height(), two.height());

        PinnedPanelLayout.Box tasks = only(column(pin("Head", false, task("Logs", 0, 1, false))));
        assertTrue(tasks.height() > one.height(), "and a task row is taller than a bare title");
        assertEquals(tasks.rows().get(tasks.rows().size() - 1).y()
                        + tasks.rows().get(tasks.rows().size() - 1).height() + PinnedPanelLayout.PAD,
                tasks.height(), "the last row's own foot, plus the closing pad");
    }

    @Test
    @DisplayName("a task row is tall enough for its text and its bar, and names where the bar sits")
    void taskRowsCarryTheirBars() {
        PinnedPanelLayout.Box box = only(column(
                pin("Head", false, task("Logs", 3, 8, false), task("Say hello", 0, 1, false))));
        PinnedPanelLayout.Row barred = box.rows().get(1);
        PinnedPanelLayout.Row barless = box.rows().get(2);

        int line = Math.max(8, FONT.lineHeight());
        assertEquals(Math.max(PinnedPanelLayout.ICON, PinnedPanelLayout.TEXT_TOP + line
                        + PinnedPanelLayout.BAR_GAP + PinnedPanelLayout.BAR_HEIGHT
                        + PinnedPanelLayout.TEXT_TOP) + PinnedPanelLayout.GAP,
                barred.height(), "text, gap, bar, breathing room -- and the icon still fits");
        assertEquals(PinnedPanelLayout.TEXT_TOP + line + PinnedPanelLayout.BAR_GAP,
                barred.barY(), "the bar sits under the text it belongs to, in the row's own pixels");
        assertTrue(barred.barY() + PinnedPanelLayout.BAR_HEIGHT <= barred.height(),
                "and inside its own row: " + barred.barY());
        assertEquals(-1, barless.barY(), "a row with no count draws no bar");
        assertEquals(Math.max(PinnedPanelLayout.ICON, line) + PinnedPanelLayout.GAP, barless.height(),
                "and it does not pay for the bar's band either: short row, same as a title");
        assertEquals(-1, box.rows().get(0).barY(), "and neither does a title");
    }

    @Test
    @DisplayName("the rows are in order, inside their box, and do not overlap each other")
    void theRowsAreOrderedAndInside() {
        PinnedPanelLayout.Column column = column(
                pin("Head", true, task("Logs", 3, 8, false), task("Planks", 0, 1, true)),
                pin("Second", false));

        for (PinnedPanelLayout.PlacedBox placed : column.boxes()) {
            PinnedPanelLayout.Box box = placed.box();
            int previousBottom = 0;
            for (PinnedPanelLayout.Row row : box.rows()) {
                assertTrue(row.y() >= previousBottom,
                        "row " + row.kind() + " starts before the last one finished");
                assertTrue(row.y() + row.height() <= box.height(),
                        "row " + row.kind() + " runs past the box's own foot");
                assertTrue(row.y() >= PinnedPanelLayout.PAD, "and no row is drawn in the inset");
                assertEquals(box.width() - PinnedPanelLayout.PAD * 2, row.width(),
                        "every row is given the content width");
                previousBottom = row.y() + row.height();
            }
        }
    }

    @Test
    @DisplayName("every box is within the column's bounds, whatever is in it")
    void aSweepOfContents() {
        for (int pins = 0; pins <= PinnedQuests.MAX_PINS; pins++) {
            for (int tasks = 0; tasks <= 9; tasks++) {
                List<PinnedPanelLayout.Pin> content = new ArrayList<>();
                for (int p = 0; p < pins; p++) {
                    List<PinnedPanelLayout.Task> rows = new ArrayList<>();
                    for (int t = 0; t < tasks; t++) {
                        rows.add(task("Task number " + t + " of a great many", t, tasks + 1, t % 2 == 0));
                    }
                    content.add(new PinnedPanelLayout.Pin("Quest " + p, "Chapter", p == 0, false, rows));
                }
                PinnedPanelLayout.Column column = PinnedPanelLayout.column(content, words(), FONT, false);

                if (pins == 0) {
                    assertTrue(column.empty(), "nothing pinned draws nothing");
                    continue;
                }
                assertTrue(column.width() >= PinnedPanelLayout.MIN_WIDTH
                                && column.width() <= PinnedPanelLayout.MAX_WIDTH,
                        "the width is inside its own bounds: " + column.width());
                assertTrue(column.height() > 0);
                int previousBottom = 0;
                for (PinnedPanelLayout.PlacedBox placed : column.boxes()) {
                    assertTrue(placed.y() >= previousBottom, "one box starts inside another");
                    assertEquals(column.width(), placed.box().width(), "one column is one width");
                    for (PinnedPanelLayout.Row row : placed.box().rows()) {
                        assertTrue(row.y() + row.height() <= placed.box().height(),
                                "a row runs past its box at " + pins + " pins and " + tasks + " tasks");
                        if (row.barY() >= 0) {
                            // As the painter computes it -- the row's top plus its row-local bar offset --
                            // so a bar that escapes its box fails here rather than in a screenshot.
                            assertTrue(row.y() + row.barY() + PinnedPanelLayout.BAR_HEIGHT
                                            <= placed.box().height(),
                                    "a bar runs past its box at " + pins + " pins and " + tasks + " tasks");
                        }
                    }
                    previousBottom = placed.y() + placed.box().height();
                }
                assertEquals(previousBottom, column.height(), "the stack ends where its last box does");
            }
        }
    }

    /** One title row's height: an icon's box, plus the gap after it. */
    private static int titleRow() {
        return Math.max(PinnedPanelLayout.ICON, 10) + PinnedPanelLayout.GAP;
    }
}
