package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Stack;

import java.util.List;
import java.util.Objects;

/**
 * How the quest overlay's body stacks up: a description, a task list, a reward list, and what the
 * quest requires.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>It is the <i>particular</i> composition of one screen's reading pane — three headings, an empty
 * state under each, and a row per entry. It is not a layout engine: the stacking itself is
 * {@link Stack}'s, in Armature, and this is a caller of it. The split is worth keeping because the
 * generic half is what a second screen can reuse, and nothing below is true of anything but this pane.
 *
 * <h2>Why it is not inside the screen</h2>
 *
 * <p>Because {@code QuestBookScreen} extends {@code Screen} and needs a running Minecraft to
 * instantiate, so nothing in it can be asserted on. This is the same argument that moved the book's
 * control rectangles into {@link BookGeometry}, and it is here for the same reason: the height this
 * class produces is what the scrollbar's range is computed from, and a scrollbar that disagrees with
 * the drawing is the defect the whole extraction was about.
 *
 * <p>So the composition is a pure function of four counts and a list of strings, and
 * {@code OverlayLayoutTest} can ask it how tall it is without a client. What that test asserts is the
 * property that matters — that the height reported and the rows placed are one computation rather
 * than two that agree — plus the specific advances this pane's appearance was built on.
 *
 * <h2>The advances are deliberate, and are not the kit's defaults</h2>
 *
 * <p>{@link Stack#heading} reserves four pixels below its text, which is a good default. This pane
 * reserves six, because its rule is drawn at ten pixels and there were six below it before any of
 * this was a stack. Using the kit's default would have moved every row of the overlay by two pixels
 * per section, for a reason that has nothing to do with removing the duplication. So the headings
 * here are built from a gap, a line and a gap — the same three elements, with this screen's numbers.
 *
 * <p>The total is still the kit's, because the kit still computes it. That is the whole point, and it
 * is why the numbers being different from the kit's defaults costs nothing.
 *
 * <h2>The one place this file is a behaviour claim rather than a refactor</h2>
 *
 * <p>{@link #BODY_TAIL} exists so that the height this produces is <b>exactly</b> what the
 * hand-written {@code measureOverlay} returned. Every other term here is a one-for-one replacement of
 * a line in it. If that claim is wrong the scroll range moves, which is why
 * {@code OverlayLayoutTest} asserts the total against the old arithmetic written out longhand — see
 * its {@code theHeightMatchesTheHandWrittenMeasureItReplaced}.
 */
public final class OverlayLayout {

    // ------------------------------------------------------------------
    // The advances. Numbers the drawing and the measurement both need.
    // ------------------------------------------------------------------

    /** The item box in a task or reward row: {@code 18} pixels square. */
    public static final int ROW_ICON = 18;

    /**
     * The row pitch: the icon box plus the gap under it.
     *
     * <p>The old code had a 13-pixel pitch under a 16-pixel icon, so consecutive icons overlapped each
     * other and the measurement was wrong in the same direction. One number, used to draw *and* to
     * measure, is what stops that.
     */
    public static final int ROW_ADVANCE = ROW_ICON + 6;

    /** The pitch for a text-only row — a dependency, which has a tick but no icon. */
    public static final int DEP_ADVANCE = 14;

    /**
     * The indent a row's content takes inside the body.
     *
     * <p>Applied as an {@link Insets} on the row rather than added by whoever draws it, and that is the
     * point: the drawing reads the slot's x and width, so the indent exists once. The version this
     * replaces wrote {@code bodyLeft + 8} and {@code bodyWidth - 16} at every one of seven call sites,
     * which is one indent expressed as two numbers in seven places — and an edit to either number
     * would have moved the text inside its row's box for some rows and not others.
     */
    public static final int ROW_INDENT = 8;

    /**
     * The height of one line of body text.
     *
     * <p>Every advance below is a multiple of this. It is here rather than in the screen because the
     * {@code Measure} the layout is built with has to report it, and the caller is the one that builds
     * the measure — see {@code QuestBookScreen.textMeasure}. Two numbers that must be equal but live in
     * different classes is the fault this whole file is an answer to, so it lives in one place and the
     * screen asks for it.
     */
    public static final int LINE_HEIGHT = 10;

    /** What an empty description says, and how much room saying it takes. */
    public static final int DESCRIPTION_ADVANCE = 12;

    /** The space under a description paragraph. After the last one too — see {@link #stack}. */
    public static final int PARAGRAPH_GAP = 5;

    /** The space above the first section heading, which follows prose. */
    public static final int SECTION_GAP = 8;

    /**
     * Where a heading's rule is drawn, measured from the top of the heading's line.
     *
     * <p>Equal to {@link #LINE_HEIGHT} because that is what it is: the rule sits immediately under the
     * line of text, which is where it has always been drawn. Written as the sum rather than as a second
     * ten, so a change to the line box moves the rule with it instead of leaving it behind.
     */
    public static final int SECTION_LEAD = LINE_HEIGHT;

    /** The space under a heading, before its first row. */
    public static final int SECTION_TAIL = 6;

    /** The space above a section that follows content rather than prose. */
    public static final int SECTION_GAP_AFTER_CONTENT = 10;

    /**
     * Padding at the end of the body. Nothing is drawn in it.
     *
     * <p>Sixteen pixels, and it is here only because the hand-written measure this replaces ended with
     * {@code return height + 16}. Keeping it means the scroll range covers the same distance it did:
     * the migration's job is to make the range and the drawing come from one computation, not to change
     * how far the pane scrolls. Removing it would be a defensible tidy-up and a separate decision.
     *
     * <p><b>It has to be a placed row, not a closing {@code gap}.</b> A gap places no slot, and
     * {@code Layout.height()} is the bottom edge of the lowest slot — so a trailing gap is invisible to
     * the height, and the height is what the viewport is told the content is tall. Written as a gap
     * first, this reserved nothing: the scroll range came out sixteen short, which nothing reported
     * because the sixteen pixels it lost are the empty ones at the end. See {@link #stack} for the
     * call itself, and {@code OverlayLayoutTest.theTailIsAPlacedSlotBecauseAGapWouldNotBeCounted} for
     * the assertion that pins it.
     *
     * <h2>Where this is not a pixel-for-pixel replacement, stated rather than hoped</h2>
     *
     * <p>Every <i>advance</i> here is a one-for-one replacement of a line in the old
     * {@code measureOverlay}: the eight and the sixteen around a heading, the row pitch, the ten and
     * sixteen before REWARDS, the tail. What is <b>not</b> identical is the wrap rule, because the
     * layout wraps with {@code TextWrap} now and the old screen had its own {@code wrap} — and the two
     * differ on exactly three inputs: a paragraph that is empty or blank (the old one produced no line,
     * this produces one), a word wider than the column (the old one overflowed, this splits it), and a
     * run of spaces (the old one kept it in the line's width, this does not). On ordinary prose they
     * agree line for line, which is what {@code OverlayLayoutTest} pins.
     *
     * <p>The difference is bounded and self-consistent: a blank paragraph is ten pixels taller than it
     * was, and the drawing is ten pixels lower with it, because the height and the position come from
     * the same pass. The old arrangement could not have said that — the height came from one function
     * and the drawing from another.
     */
    public static final int BODY_TAIL = 16;

    // ------------------------------------------------------------------
    // Keys. What a caller looks a placed element up by.
    // ------------------------------------------------------------------

    /** The lines a caller draws itself: prose, and the two empty states. */
    public static final String NO_DESCRIPTION = "prose:none";
    public static final String NO_TASKS = "task:none";
    public static final String NO_REWARDS = "reward:none";

    /** The section headings, so the screen can draw its own rule under each. */
    public static final String TASKS_HEADING = "heading:tasks";
    public static final String REWARDS_HEADING = "heading:rewards";
    public static final String REQUIRES_HEADING = "heading:requires";

    private OverlayLayout() {
    }

    // ------------------------------------------------------------------
    // Keys, generated rather than hand-written
    // ------------------------------------------------------------------

    /**
     * The key a description paragraph is placed under.
     *
     * <p>Generated here rather than written as a string literal at both ends. The screen looks a
     * paragraph up by index and the test counts the paragraphs it placed; if those two built their own
     * keys, a change to one would leave the other looking for something that is not there — and the
     * symptom is a slot that silently does not exist, which reads as a paragraph that was never drawn.
     */
    public static String proseKey(int index) {
        return "prose:" + index;
    }

    /** The key a task's row is placed under. */
    public static String taskKey(int index) {
        return "task:" + index;
    }

    /** The key a reward's row is placed under. */
    public static String rewardKey(int index) {
        return "reward:" + index;
    }

    /** The key a dependency's row is placed under. */
    public static String dependencyKey(int index) {
        return "requires:" + index;
    }

    // ------------------------------------------------------------------
    // The composition
    // ------------------------------------------------------------------

    /**
     * The body's elements, in order, as a stack the caller builds with its own column width and font.
     *
     * <p>Returning a {@link Stack} rather than a {@code Layout} is deliberate: a stack has not been
     * measured yet, so the caller supplies the width and the {@code Measure}, and the test can supply a
     * known width per character. The height is produced by the same {@code build} call that produces
     * the rows, which is the property that makes the scrollbar honest.
     *
     * <p>The gap after every paragraph includes the last, which looks like a mistake and is not: the
     * old measure added five pixels after each one and the scroll range was computed from that total.
     * Dropping the last gap would leave the drawing and the range disagreeing by five pixels, which is
     * precisely the class of defect this class exists to remove.
     *
     * @param description      the prose paragraphs, in order. Empty means the empty state is placed.
     * @param taskCount        how many tasks. Zero means the empty state is placed.
     * @param rewardCount      how many rewards. Zero means the empty state is placed.
     * @param dependencyCount  how many prerequisites. Zero places no REQUIRES section at all.
     */
    public static Stack stack(List<String> description, int taskCount, int rewardCount, int dependencyCount) {
        Objects.requireNonNull(description, "description");
        if (taskCount < 0 || rewardCount < 0 || dependencyCount < 0) {
            throw new IllegalArgumentException("counts must not be negative: " + taskCount + ", "
                    + rewardCount + ", " + dependencyCount);
        }

        Stack stack = Stack.stack();

        if (description.isEmpty()) {
            stack.row(NO_DESCRIPTION, DESCRIPTION_ADVANCE);
        }
        for (int i = 0; i < description.size(); i++) {
            stack.text(proseKey(i), description.get(i), Stack.Align.LEFT).gap(PARAGRAPH_GAP);
        }

        // The heading by hand rather than through Stack.heading() -- see the class note on why this
        // pane's tail is six and not four.
        stack.gap(SECTION_GAP)
                .text(TASKS_HEADING, "TASKS", Stack.Align.LEFT)
                .gap(SECTION_TAIL);
        if (taskCount == 0) {
            // A row either way, so an empty list and a one-item list take the same space. The old code
            // had to be reminded of that in a comment; here there is no separate measure to remind.
            stack.row(NO_TASKS, ROW_ADVANCE, inset());
        }
        for (int i = 0; i < taskCount; i++) {
            stack.row(taskKey(i), ROW_ADVANCE, inset());
        }

        stack.gap(SECTION_GAP_AFTER_CONTENT)
                .text(REWARDS_HEADING, "REWARDS", Stack.Align.LEFT)
                .gap(SECTION_TAIL);
        if (rewardCount == 0) {
            stack.row(NO_REWARDS, ROW_ADVANCE, inset());
        }
        for (int i = 0; i < rewardCount; i++) {
            stack.row(rewardKey(i), ROW_ADVANCE, inset());
        }

        // Absent entirely when there is nothing to require, which is a real difference from the other
        // two: a quest with no prerequisites should not say "REQUIRES: nothing", it should not have the
        // section. That asymmetry is the screen's design and is stated here so the test can hold it.
        if (dependencyCount > 0) {
            stack.gap(SECTION_GAP_AFTER_CONTENT)
                    .text(REQUIRES_HEADING, "REQUIRES", Stack.Align.LEFT)
                    .gap(SECTION_TAIL);
            for (int i = 0; i < dependencyCount; i++) {
                stack.row(dependencyKey(i), DEP_ADVANCE, inset());
            }
        }

        // A placed row rather than a closing gap, and that is a bug fix rather than a preference.
        //
        // Layout.height() is derived from its slots -- the bottom edge of the lowest one -- so a gap
        // contributes nothing to it, because a gap places no slot at all. A trailing gap is therefore
        // invisible to the height, and the height is what ScrollView tells the viewport the content is
        // tall. With a gap here the reported content height was sixteen pixels short of what the old
        // hand-written measure returned, so the scroll range was short by sixteen -- silently, because
        // the sixteen pixels it lost are the empty ones at the end and nothing is drawn in them.
        //
        // It is exactly the fault this class exists to remove: a number that is *nearly* right, in a
        // direction that loses a little reachable space, with nothing anywhere reporting it. A row with
        // a null key is what the kit offers for space that is part of the content -- placed, counted,
        // and skipped by a hit test.
        return stack.row(null, BODY_TAIL);
    }

    /**
     * A row's indent, top and bottom included because a row's content sits on its own top edge.
     *
     * <p>One method rather than {@code Insets.symmetric(ROW_INDENT, 0)} written into six row calls: the
     * vertical part is zero on purpose — a row's {@code y} is where its icon is drawn, and the old code
     * drew at exactly the row's top edge — so the pair belongs together where it cannot be taken apart.
     */
    private static Insets inset() {
        return Insets.symmetric(ROW_INDENT, 0);
    }
}
