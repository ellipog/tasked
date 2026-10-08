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
 * The pinned panel's shape, over a sweep of content.
 *
 * <h2>Why this is arithmetic worth asserting</h2>
 *
 * <p>Because every one of these facts is a way a HUD panel goes wrong without saying so, and none of them can
 * be seen in a screenshot of one panel:
 *
 * <ul>
 *   <li>the panel is as tall as what it holds, so a list that grew without bound would run off the bottom of
 *       somebody's window -- and a list that did not grow would draw its last row over its own edge;</li>
 *   <li>the head is drawn in full and the rest as names, which is the whole of what "collapsible" means on a
 *       surface that cannot be clicked;</li>
 *   <li>a long title is <b>truncated</b> rather than allowed to widen the box, because a HUD element that
 *       grows with its content is an element that leaves the window;</li>
 *   <li>a task's count and tick own the right-hand end of their row, so the text is what gives way -- the
 *       other order puts the count off the edge of its own box;</li>
 *   <li>and the editor's empty panel is still a box, because a zero-sized element is one nobody can grab,
 *       which is the one arrangement that traps a player in that screen.</li>
 * </ul>
 *
 * <p>The font is {@code Measure.monospace}, so every number here is checkable by hand: six pixels a
 * character, ten a line. That is the same stand-in the wrap rules are asserted against, and for the same
 * reason -- a proportional fake would let a wrong-by-one reserve hide behind a plausible width.
 */
@DisplayName("the pinned panel's layout")
class PinnedPanelLayoutTest {

    private static final Measure FONT = Measure.monospace(6, 10);

    /** The words, in the shape the painter supplies them: resolved, with the one counted sentence as one. */
    private static PinnedPanelLayout.Words words() {
        return new PinnedPanelLayout.Words("Pinned", "Complete", "sample", n -> "+" + n + " more");
    }

    private static PinnedPanelLayout.Task task(String text, int progress, int count, boolean done) {
        return new PinnedPanelLayout.Task(text, progress, count, done);
    }

    private static PinnedPanelLayout.Pin pin(String title, boolean complete, PinnedPanelLayout.Task... tasks) {
        return new PinnedPanelLayout.Pin(title, "Chapter One", complete, List.of(tasks));
    }

    private static PinnedPanelLayout.Box box(PinnedPanelLayout.Pin... pins) {
        return PinnedPanelLayout.box(List.of(pins), words(), FONT, false);
    }

    @Test
    @DisplayName("nothing pinned draws nothing at all, and the editor draws a sample instead")
    void emptyIsNothingLive() {
        PinnedPanelLayout.Box live = PinnedPanelLayout.box(List.of(), words(), FONT, false);
        assertTrue(live.empty(), "an element with nothing to say draws no frame");
        assertEquals(0, live.width());
        assertEquals(0, live.height());

        PinnedPanelLayout.Box editor = PinnedPanelLayout.box(List.of(), words(), FONT, true);
        assertFalse(editor.empty(), "and in the editor it is a box, or it could not be grabbed");
        assertEquals(2, editor.rows().size(), "the panel's own name and one sample line under it");
        assertEquals(PinnedPanelLayout.Kind.HEADER, editor.rows().get(0).kind());
        assertEquals(PinnedPanelLayout.Kind.SAMPLE, editor.rows().get(1).kind());
        assertEquals(PinnedPanelLayout.MIN_WIDTH, editor.width(),
                "a short sample still gets the narrowest a panel may be");
        assertEquals("sample", editor.rows().get(1).text());
    }

    @Test
    @DisplayName("the head's tasks are listed, the rest are names, and the order is the list's")
    void theHeadIsTheOnlyOneInFull() {
        PinnedPanelLayout.Box panel = box(
                pin("Head", false, task("Logs", 3, 8, false), task("Planks", 1, 4, false)),
                pin("Second", false),
                pin("Third", true));

        List<PinnedPanelLayout.Kind> kinds = panel.rows().stream()
                .map(PinnedPanelLayout.Row::kind).toList();
        assertEquals(List.of(PinnedPanelLayout.Kind.HEADER, PinnedPanelLayout.Kind.TITLE,
                PinnedPanelLayout.Kind.TASK, PinnedPanelLayout.Kind.TASK, PinnedPanelLayout.Kind.TITLE,
                PinnedPanelLayout.Kind.TITLE), kinds);

        assertEquals("Head", panel.rows().get(1).text());
        assertEquals("Logs", panel.rows().get(2).text());
        assertEquals("Planks", panel.rows().get(3).text());
        assertEquals("Second", panel.rows().get(4).text());
        assertEquals("Third", panel.rows().get(5).text());
        assertEquals("Chapter One", panel.rows().get(4).note(), "a title carries its chapter");
    }

    @Test
    @DisplayName("a finished quest that is not the head is a word in a different ink, not a row of its own")
    void theRestWearTheirStateOnTheirTitle() {
        PinnedPanelLayout.Box panel = box(pin("Head", false), pin("Done", true));

        assertFalse(panel.rows().get(1).done(), "the head's own completion is the row below it");
        assertTrue(panel.rows().get(2).done(), "and every other pin wears it on its name");
    }

    @Test
    @DisplayName("a finished head says so in a row of its own, and only when it is finished")
    void theHeadIsMarkedComplete() {
        assertFalse(box(pin("Head", false)).rows().stream()
                        .anyMatch(row -> row.kind() == PinnedPanelLayout.Kind.COMPLETE),
                "an unfinished quest is not marked finished");

        PinnedPanelLayout.Box panel = box(pin("Head", true, task("Logs", 8, 8, true)));
        List<PinnedPanelLayout.Kind> kinds = panel.rows().stream()
                .map(PinnedPanelLayout.Row::kind).toList();
        assertEquals(List.of(PinnedPanelLayout.Kind.HEADER, PinnedPanelLayout.Kind.TITLE,
                PinnedPanelLayout.Kind.TASK, PinnedPanelLayout.Kind.COMPLETE), kinds);
        assertEquals("Complete", panel.rows().get(3).text());
    }

    @Test
    @DisplayName("past six tasks the panel counts the rest rather than growing past the window")
    void theTaskListIsCapped() {
        List<PinnedPanelLayout.Task> many = new ArrayList<>();
        for (int i = 0; i < PinnedPanelLayout.MAX_TASK_ROWS + 3; i++) {
            many.add(task("Task " + i, 0, 1, false));
        }
        PinnedPanelLayout.Box panel = box(new PinnedPanelLayout.Pin("Head", "", false, many));

        long listed = panel.rows().stream().filter(row -> row.kind() == PinnedPanelLayout.Kind.TASK).count();
        assertEquals(PinnedPanelLayout.MAX_TASK_ROWS, listed, "a quest with twenty tasks is not twenty rows");

        PinnedPanelLayout.Row more = panel.rows().stream()
                .filter(row -> row.kind() == PinnedPanelLayout.Kind.MORE).findFirst().orElseThrow();
        assertEquals("+3 more", more.text(), "and the sentence carries the count, which is why it is a function");
    }

    @Test
    @DisplayName("a long title is truncated to the box rather than widening it")
    void longTextIsTruncated() {
        PinnedPanelLayout.Box panel = box(pin("A title long enough to need cutting down to size", false));

        assertEquals(PinnedPanelLayout.MAX_WIDTH, panel.width(), "the box stops at its own ceiling");
        String drawn = panel.rows().get(1).text();
        assertTrue(drawn.endsWith("\u2026"), "and the title says it was cut: " + drawn);
        assertTrue(FONT.width(drawn) <= panel.width() - PinnedPanelLayout.PAD * 2,
                "the drawn title fits the content width: " + FONT.width(drawn));
    }

    @Test
    @DisplayName("a narrow title gets the narrowest box, not a box the width of its text")
    void shortTextGetsTheFloor() {
        PinnedPanelLayout.Box panel = box(new PinnedPanelLayout.Pin("Hi", "", false, List.of()));
        assertEquals(PinnedPanelLayout.MIN_WIDTH, panel.width(),
                "a panel four characters wide is not a panel");
    }

    @Test
    @DisplayName("a task's text gives way to its count and its tick, which own the right-hand end")
    void aTaskReservesItsRightHandEnd() {
        PinnedPanelLayout.Box panel = box(pin("Head", false, task("Collect a very great many oak logs", 3, 12, true)));
        PinnedPanelLayout.Row row = panel.rows().get(2);
        int content = panel.width() - PinnedPanelLayout.PAD * 2;
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
        PinnedPanelLayout.Box panel = box(pin("Head", false, task("Say hello", 0, 1, false)));
        PinnedPanelLayout.Row row = panel.rows().get(2);
        assertFalse(PinnedPanelLayout.hasCount(row));

        PinnedPanelLayout.Row eight = box(pin("Head", false, task("Logs", 3, 8, false))).rows().get(2);
        assertTrue(PinnedPanelLayout.hasCount(eight));
        assertEquals("3 / 8", PinnedPanelLayout.countText(eight));
    }

    @Test
    @DisplayName("a count is clamped to its target, because a counter can overshoot one")
    void aCountIsClamped() {
        PinnedPanelLayout.Row row = box(pin("Head", false, task("Logs", 12, 8, true))).rows().get(2);
        assertEquals("8 / 8", PinnedPanelLayout.countText(row));
    }

    @Test
    @DisplayName("a row knows which task it is, so the painter can find that task's icon")
    void aRowNamesItsTask() {
        PinnedPanelLayout.Box panel = box(pin("Head", false,
                task("One", 0, 1, false), task("Two", 0, 1, false), task("Three", 0, 1, false)));

        assertEquals(0, panel.rows().get(2).taskIndex());
        assertEquals(1, panel.rows().get(3).taskIndex());
        assertEquals(2, panel.rows().get(4).taskIndex());
        assertEquals(-1, panel.rows().get(1).taskIndex(), "a title is not a task");
        assertEquals(-1, panel.rows().get(0).taskIndex(), "and neither is the panel's own name");
    }

    @Test
    @DisplayName("the chapter is cut against the title as drawn, so the two cannot overlap")
    void theNoteFollowsTheDrawnTitle() {
        PinnedPanelLayout.Box panel = box(new PinnedPanelLayout.Pin(
                "A long quest name that will certainly be truncated somewhere", "A long chapter name too",
                false, List.of()));
        PinnedPanelLayout.Row row = panel.rows().get(1);
        int content = panel.width() - PinnedPanelLayout.PAD * 2;

        assertTrue(FONT.width(row.text()) + FONT.width(PinnedPanelLayout.NOTE_SEPARATOR)
                        + FONT.width(row.note()) <= content,
                "the two halves fit the row: " + row.text() + " | " + row.note());
        assertTrue(row.note().endsWith("\u2026"), "the chapter gives way first: " + row.note());
    }

    @Test
    @DisplayName("the box is as tall as its last row and no taller")
    void theHeightIsTheContent() {
        PinnedPanelLayout.Box one = box(pin("Head", false));
        PinnedPanelLayout.Box two = box(pin("Head", false), pin("Second", false));

        assertTrue(two.height() > one.height(), "a second pin has to make room for itself");
        assertEquals(threeRows(), two.height(),
                "header, two titles and the pad: " + two.height());

        PinnedPanelLayout.Box tasks = box(pin("Head", false, task("Logs", 0, 1, false)));
        assertTrue(tasks.height() > one.height(), "and a task row is taller than a word");
        assertEquals(tasks.rows().get(tasks.rows().size() - 1).y()
                        + tasks.rows().get(tasks.rows().size() - 1).height() + PinnedPanelLayout.PAD,
                tasks.height(), "the last row's own foot, plus the closing pad");
    }

    @Test
    @DisplayName("the rows are in order, inside the box, and do not overlap each other")
    void theRowsAreOrderedAndInside() {
        PinnedPanelLayout.Box panel = box(
                pin("Head", true, task("Logs", 3, 8, false), task("Planks", 0, 1, true)),
                pin("Second", false));

        int previousBottom = 0;
        for (PinnedPanelLayout.Row row : panel.rows()) {
            assertTrue(row.y() >= previousBottom, "row " + row.kind() + " starts before the last one finished");
            assertTrue(row.y() + row.height() <= panel.height(),
                    "row " + row.kind() + " runs past the box's own foot");
            assertTrue(row.y() >= PinnedPanelLayout.PAD, "and no row is drawn in the inset");
            assertEquals(panel.width() - PinnedPanelLayout.PAD * 2, row.width(),
                    "every row is given the content width");
            previousBottom = row.y() + row.height();
        }
    }

    @Test
    @DisplayName("every panel is within the box's own bounds, whatever is in it")
    void aSweepOfContents() {
        for (int pins = 0; pins <= PinnedQuests.MAX_PINS; pins++) {
            for (int tasks = 0; tasks <= 9; tasks++) {
                List<PinnedPanelLayout.Pin> content = new ArrayList<>();
                for (int p = 0; p < pins; p++) {
                    List<PinnedPanelLayout.Task> rows = new ArrayList<>();
                    for (int t = 0; t < tasks; t++) {
                        rows.add(task("Task number " + t + " of a great many", t, tasks + 1, t % 2 == 0));
                    }
                    content.add(new PinnedPanelLayout.Pin("Quest " + p, "Chapter", p == 0, rows));
                }
                PinnedPanelLayout.Box panel = PinnedPanelLayout.box(content, words(), FONT, false);

                if (pins == 0) {
                    assertTrue(panel.empty(), "nothing pinned draws nothing");
                    continue;
                }
                assertTrue(panel.width() >= PinnedPanelLayout.MIN_WIDTH
                                && panel.width() <= PinnedPanelLayout.MAX_WIDTH,
                        "the width is inside its own bounds: " + panel.width());
                assertTrue(panel.height() > 0);
                for (PinnedPanelLayout.Row row : panel.rows()) {
                    assertTrue(row.y() + row.height() <= panel.height(),
                            "a row runs past the box at " + pins + " pins and " + tasks + " tasks");
                }
            }
        }
    }

    /** The height of a header and two titles: each line, the gap after it, and four either end. */
    private static int threeRows() {
        int line = 10;
        int title = Math.max(PinnedPanelLayout.ICON, line) + PinnedPanelLayout.GAP;
        return PinnedPanelLayout.PAD + line + PinnedPanelLayout.GAP
                + title + PinnedPanelLayout.GAP + title + PinnedPanelLayout.PAD;
    }
}
