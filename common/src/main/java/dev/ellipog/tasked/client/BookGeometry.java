package dev.ellipog.tasked.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything on the quest book screen goes — and nothing else.
 *
 * <h2>Why this is a separate class with no Minecraft in it</h2>
 *
 * <p>Because the bug that produced it could not be tested. Two controls were drawn on top of each
 * other in the bottom-right corner, and the cause was that "near the bottom right" had been written
 * <b>twice</b>, as two expressions that were equal only by coincidence:
 *
 * <pre>
 * Open: canvasRight() - 78,               canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two x values were ten pixels apart
 * and the two y values about fifteen. The fix was to compute each shared position once — but that
 * fix lived inside {@code QuestBookScreen}, which extends {@code Screen} and needs a running
 * Minecraft to instantiate. So the property "these controls do not overlap" was still not testable,
 * and a future edit to those private helpers would have re-broken it with nothing to notice.
 *
 * <p>This class holds only that arithmetic: rectangles, and integers. No {@code GuiGraphics}, no
 * {@code Minecraft}, no rendering. It loads and runs in a plain JVM, so
 * {@code BookGeometryTest} can assert the properties that matter —
 * <b>no two controls overlap, and every control is inside the surface it belongs to</b> — across a
 * sweep of window sizes. That is a test of the exact failure that was reported.
 *
 * <h2>The shape of the screen, and what changed</h2>
 *
 * <pre>
 * +--------------------------------------------------------------+
 * | Quest Book                       20 quests . 100%      [ x ] |  header, close at the right
 * +-------------+------------------------------------------------+
 * | First Steps | [+]                                            |
 * | Toolsmith   | [-]                                            |
 * | Desert Road | [o]                                            |
 * |             |                                                |
 * |             |          (the graph, the whole canvas)         |
 * +-------------+------------------------------------------------+
 * </pre>
 *
 * <p>Two things moved, and each replaced something that was spending space without earning it:
 *
 * <ul>
 *   <li><b>The sidebar footer is gone.</b> It held four controls across two rows — zoom in, zoom out,
 *       re-centre and Done — in 116 pixels of a 132-pixel column. They are map controls, so they now
 *       sit on the map, as small square icon buttons in the canvas's top-left corner. That is where a
 *       player looks for them, it costs the chapter list no room at all, and it frees both footer
 *       rows.</li>
 *   <li><b>Done is now a close button in the header</b>, top-right, beside the quest count. A modal
 *       panel is closed by the thing in its corner, and the header had a mostly empty right end.</li>
 * </ul>
 *
 * <h2>And the summary strip is gone entirely</h2>
 *
 * <p>There used to be a third thing here: a bar across the bottom describing the selected quest with
 * an <b>Open</b> button. It went through two forms — a 46-pixel band reserved below the graph, then a
 * floating bar that appeared only when something was selected — and both were waste, for a reason
 * neither form addressed.
 *
 * <p><b>Clicking a node already opens the quest.</b> That is what a graph UI does, and the code has
 * done it since the pan/select/open gesture was written: a press that does not move selects
 * <i>and</i> opens, so the strip was a second route to a place you were already standing. A summary
 * of what you are looking at is only useful if you are not looking at it, and you just clicked it.
 *
 * <p>So the canvas is the whole area below the header, and there is nothing to keep clear of. That
 * also removes the class's only reason to distinguish a <i>canvas</i> from a <i>view port</i>: content
 * is centred in the canvas, because there is nothing on top of it.
 *
 * <h2>Screens are smaller than you think</h2>
 *
 * <p>Minecraft's GUI space is the window divided by the GUI scale, and an auto scale on a small
 * window gives remarkably little of it: the screenshot this was debugged from was
 * <b>427 × 240</b> GUI pixels. At that size the panel is 387 × 200. Anything that assumes 800 × 480
 * works on the developer's monitor and breaks on a laptop.
 *
 * <p>So the panel has a minimum size that can physically hold its own chrome, and below that minimum
 * the panel is allowed to run off the edge of the screen rather than overlapping itself. Off-screen is
 * clipped and obviously wrong; overlapping controls look like a rendering fault and are not obviously
 * anything.
 */
public final class BookGeometry {

    // ------------------------------------------------------------------
    // Dimensions
    // ------------------------------------------------------------------

    /**
     * The chapter list down the left.
     *
     * <p>156 rather than the 132 it was, and the reason is the grouped sidebar rather than taste. A
     * heading's label and a chapter's are now at two different indents inside one column, so the room
     * a title has is {@code 156 - EDGE*2 - SIDEBAR_SCROLLBAR - SIDEBAR_INDENT} = 124 rather than the
     * 116 the flat list had — and the flat list was already truncating {@code "Getting Started"}.
     * Grouping the list without widening it would have made the truncation worse and blamed it on the
     * grouping.
     *
     * <p>Worth recording what the number is measured against, since a sidebar is easy to over-widen: the
     * gallery's longest chapter title is {@code "High Contrast"} and the longest heading is
     * {@code "Getting Started"}, so 124 is roughly twice what the content asks for. That margin is
     * deliberate — a pack author's titles are not this file's — and it costs the canvas 24 pixels out
     * of a 200-pixel minimum, which is why {@link #MIN_PANEL_WIDTH} still fits a screenshot-sized
     * window comfortably.
     */
    public static final int SIDEBAR_WIDTH = 156;

    /** The title bar across the top of the panel. Holds the title, the count and Close. */
    public static final int HEADER_HEIGHT = 26;

    /** A control's height. One number, so a row of them lines up. */
    public static final int ROW_HEIGHT = 18;

    /** Between two stacked controls. */
    public static final int ROW_GAP = 4;

    /** The `[Edit]` half of the author's split control: a word, so wider than a square. */
    public static final int EDIT_BUTTON_WIDTH = 44;

    /** The `[gear]` half: square, the height of a control. */
    public static final int TOOLS_BUTTON_SIZE = ROW_HEIGHT;

    /** Between the panel's edge and the controls inside it. */
    public static final int EDGE = 8;

    /**
     * How far the header's **text** sits from the panel's edge — the title at the left, the quest count
     * at the right.
     */
    public static final int HEADER_INSET = 10;

    /**
     * How far a header **control's box** sits from the panel's edge. See {@link #HEADER_INSET}.
     *
     * <h2>Why a box is inset by less than a label</h2>
     *
     * <p>Because a label starts where it is drawn and a control's glyph sits inside its own box: put the
     * box at the label's inset and the resting gap to the X reads wider than the gap to the title. How
     * much wider is the button's own padding, and it was measured by eye across two reports rather than
     * derived — "a tiny bit too much space to the right of the x", then "move them 4 pixels to the right",
     * then two more. Six, in total, and it is subtracted once here rather than at any call site.
     */
    public static final int HEADER_CONTROL_INSET = HEADER_INSET - 6;


    // --- the sidebar's list --------------------------------------------------

    // These four replace a single CHAPTER_ROW_PITCH, and the reason is the whole of what changed here.
    // A pitch is a *geometry* decision -- "row n is at y + n * pitch" -- and it can only be made by a
    // class that knows how many rows there are and which of them are on screen. That was true while the
    // chapter list was a flat map the screen walked with an index. It is not true now: the rows come
    // from a collapsible outline, are nested, and are scrollable, so the arithmetic belongs to the
    // Stack that places them and the ScrollView that clips them.
    //
    // So what is left here is what is genuinely this class's: how tall a row is, how far apart two of
    // them sit, how far one level of nesting is indented, and how much of the column the scrollbar
    // needs. Those are metrics. The *positions* are not.

    /**
     * The height of one row in the sidebar's list, chapter or group.
     *
     * <p>Equal to {@link #ROW_HEIGHT} rather than a second 18, because a sidebar row and a control are
     * the same kind of thing drawn at the same size, and the previous arrangement had them as two
     * numbers that agreed by inspection.
     */
    public static final int SIDEBAR_ROW_HEIGHT = ROW_HEIGHT;

    /**
     * Between two rows of the sidebar's list.
     *
     * <h2>Half {@link #ROW_GAP}, and this is where the two stop agreeing</h2>
     *
     * <p>This was {@code ROW_GAP} — 4 — on the reasoning that a sidebar row and a control are the same
     * kind of thing. They are, and they are not laid out the same way. A stack of buttons is two or
     * three controls that each do something different; a sidebar is <b>nineteen rows in one list</b>,
     * and spacing between list items is not spacing between unrelated controls. Four pixels of gap
     * around eighteen-pixel rows spends a fifth of the column on nothing, and the report was exact:
     * <i>"not utilizing its space well enough"</i>.
     *
     * <p>Two, so the pitch is 20 rather than 22. On a 174-pixel sidebar that is eight full rows instead
     * of seven, and the rows still read as separate items because each has its own box.
     */
    public static final int SIDEBAR_ROW_GAP = 2;

    /**
     * How far a row is indented per level of nesting.
     *
     * <p>One level exists today — a chapter under its group — so a chapter row is indented by this and a
     * group row is not. It is a per-level number rather than a single chapter indent because the
     * outline that places these rows is depth-general: {@code Outline.depth} already answers for a tree
     * of any depth, and a second constant here would be this class disagreeing with it about a question
     * it does not need to have an opinion on.
     *
     * <p>Eight, which is a little under half a row's height. Wide enough that the indent reads as
     * nesting rather than as a random left margin, and narrow enough that the widest chapter title in
     * the shipped examples still fits — see {@link #SIDEBAR_WIDTH} for that arithmetic. It was ten,
     * and it came down by two when the column widened: the indent has to be <i>visible</i> rather than
     * large, and every pixel of it is taken off the label.
     */
    public static final int SIDEBAR_INDENT = 8;

    /**
     * The width the scroll view's bar needs, taken off the right of the list.
     *
     * <p>{@code ScrollView.drawScrollbar} draws a three-pixel bar four pixels right of the viewport's
     * right edge, so the bar occupies {@code viewRight() + 4} to {@code viewRight() + 7}. Eight leaves
     * that a pixel clear of {@link #EDGE}, which is the whole of the arithmetic: the bar has to sit in
     * the sidebar's inner margin and not on a row.
     *
     * <p>Named here rather than in {@code ScrollView} because it is this screen's column that has to
     * reserve it. A view that draws a bar in space its caller did not leave is a view drawing over the
     * caller's content, which is why the bar's position is the kit's business and the room for it is
     * not.
     */
    public static final int SIDEBAR_SCROLLBAR = 8;

    /** Between the header and the top of the sidebar's list. */
    public static final int CHAPTER_GAP = 6;


    /** The gap inside the full-screen overlay, between its edge and its content. */
    public static final int OVERLAY_MARGIN = 24;

    // --- the view cluster ----------------------------------------------------

    /**
     * One button of the view cluster, and so the cluster's width.
     *
     * <p>Square and small, because it is a map control: the graph is the content and these are a tool
     * for looking at it. They used to be 30 pixels wide with the word "Centre" in one of them, which
     * is a label earning its keep in a footer and not on a map.
     */
    public static final int VIEW_BUTTON = ROW_HEIGHT;

    /** Between two buttons of the cluster. Small, because they are one control group. */
    public static final int VIEW_GAP = 2;

    /** The height of the three stacked buttons. */
    public static final int VIEW_COLUMN_HEIGHT = VIEW_BUTTON * 3 + VIEW_GAP * 2;

    /**
     * How far the cluster's backing panel extends past the buttons.
     *
     * <p>The buttons have their own fills, so this is not what makes them visible — it is what makes
     * them read as <b>one</b> cluster rather than as three controls that happen to be stacked. Three
     * pixels, and the three of them sit inside a single raised panel.
     */
    public static final int VIEW_MAT = 3;

    /**
     * The smallest panel that can hold its own chrome, derived rather than written out.
     *
     * <p>Two constraints, and the taller one wins. Both are re-derived here rather than carried over,
     * because the thing the sidebar's term was measuring has changed.
     *
     * <h2>The sidebar term, re-derived</h2>
     *
     * <p>The sidebar needs the header, a gap, <b>one row</b> and the bottom edge:
     * {@code CHAPTER_GAP + SIDEBAR_ROW_HEIGHT + EDGE}. A list with no rows is a book whose only chapter
     * cannot be selected, which is a blank screen with no way forward.
     *
     * <p>What is guaranteed is now <i>weaker and more honest</i> than it was. It used to be "at least one
     * chapter fits above the bottom", measured by {@code chapterRows()} against a pitch — so the promise
     * was about the list being <b>complete</b>, and the arithmetic had to be right about how many rows
     * fit or the last one was drawn off the end. Now it is "the viewport is at least one row tall", and
     * everything past that scrolls. The consequences, both of them improvements:
     *
     * <ul>
     *   <li>There is <b>no pitch in the term</b>, and no row count. The pitch is the Stack's (see
     *       {@link #SIDEBAR_ROW_HEIGHT} for where it went) and the count is the outline's, so this
     *       constant cannot be invalidated by a change to either. That is the pressure that has gone,
     *       and it is why this is a re-derivation rather than a renumbering.</li>
     *   <li>The number is <b>unchanged</b> at {@code 6 + 18 + 8 = 32}, because the first row's position
     *       and its height are the same as they were. It is worth saying plainly that the figure not
     *       moving is not evidence that nothing changed: the sum that produces it is a different sum
     *       describing a different guarantee, which is exactly the kind of coincidence that makes an
     *       unchanged test count reassuring and meaningless.</li>
     * </ul>
     *
     * <p>A third thing went out of this term too: it used to be measured to the <i>appearance rows'</i>
     * top, because those were anchored to the panel's bottom and a row counted past them would have been
     * drawn underneath. They are gone, and the list measures to the panel's edge now — see
     * {@link #sidebarViewport()}.
     *
     * <h2>The canvas term, re-derived</h2>
     *
     * <p>{@code (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + VIEW_MAT * 2) + EDGE}. It is the view cluster,
     * which is the tallest thing that sits <i>on</i> the canvas: three square buttons, the gaps between
     * them, and the mat that makes them read as one group, all inside the canvas's own {@link #EDGE}.
     * Without it the cluster runs past the bottom of a short canvas, which is the same class of fault as
     * a row drawn underneath a footer — a control outside the surface it belongs to, and therefore
     * clickable outside it.
     *
     * <p>This term is <b>arithmetically unchanged</b>, and unlike the sidebar's it is not a coincidence:
     * nothing about scrolling touches the canvas. Writing it out again is the point of the exercise
     * rather than a formality — a "re-derive both terms" that quietly copied one of them would not be a
     * check, and the useful half of this paragraph is that the canvas's term is the one that did not
     * move, for a reason that can be stated.
     *
     * <p>The numbers: the sidebar needs {@code 6 + 18 + 4 + 18 + 8 = 54} -- a gap, one row, the gap
     * above the party strip, the strip, and the panel's edge -- and the canvas needs
     * {@code (8 - 3) + (58 + 6) + 8} = <b>77</b>. So the canvas decides it, and
     * {@code MIN_PANEL_HEIGHT} is {@code 26 + 77 = 103}. Changing {@link #VIEW_BUTTON} therefore cannot
     * silently invalidate it: the sum is written in terms of that constant.
     *
     * <p><b>The margin narrowing is the number to watch, and it is why both terms are written out
     * rather than one being carried.</b> It was 45 when the theme controls left the sidebar; the party
     * strip spent 22 of it, and 23 is what is left. The next feature that wants a home in this column
     * has 23 pixels of room before the sidebar starts deciding the minimum -- and a reader who does not
     * know that finds out from a screenshot.
     */
    public static final int MIN_PANEL_HEIGHT = HEADER_HEIGHT + Math.max(
            CHAPTER_GAP + SIDEBAR_ROW_HEIGHT + EDGE,
            (EDGE - VIEW_MAT) + (VIEW_COLUMN_HEIGHT + VIEW_MAT * 2) + EDGE);

    /** Enough canvas to be worth showing beside the sidebar. */
    public static final int MIN_CANVAS_WIDTH = 80;

    public static final int MIN_PANEL_WIDTH = SIDEBAR_WIDTH + MIN_CANVAS_WIDTH;

    /** The largest the panel gets, however big the window is. */
    public static final int MAX_PANEL_WIDTH = 800;

    public static final int MAX_PANEL_HEIGHT = 480;

    /** How far the panel is inset from the window's edge, when the window is big enough for that. */
    public static final int PANEL_MARGIN = 20;

    // --- the modal card ------------------------------------------------------

    /**
     * The widest a modal card gets.
     *
     * <p>The overlay used to be <code>screenWidth - 48</code> by <code>screenHeight - 48</code>, which
     * at a 640-pixel GUI is a 592-by-300 card: not a panel but a second screen with a border. A six-row
     * roster inside one is a small list in a large empty room, and a column of prose inside one is
     * mostly margin.
     *
     * <p>380 is a compromise with a reason rather than a round number: the quest overlay's prose wraps
     * at this width to about sixty characters, which is where a line of text stays readable, and a
     * roster two hundred pixels wider than its longest name is the empty room the report was about.
     */
    public static final int MAX_MODAL_WIDTH = 520;

    /** The tallest a modal card gets. Past this the body scrolls rather than the card growing. */
    public static final int MAX_MODAL_HEIGHT = 340;

    /** Below this a card cannot hold a control and its label without the two colliding. */
    public static final int MIN_MODAL_WIDTH = 200;

    public static final int MIN_MODAL_HEIGHT = 120;

    /** How far a modal card is inset from the window's edge, when the window is too small for the cap. */
    public static final int MODAL_MARGIN = 24;

    /**
     * How far a modal's content sits from its card's edge — the same on all four sides.
     *
     * <h2>One number, and it replaced four that disagreed</h2>
     *
     * <p>A card's padding was 16 on the left and right ({@code EDGE * 2}), 10 at the top, 8 at the
     * bottom, and the footer's controls were nudged by a further half a control's height on the right
     * for no stated reason. The report was exact and is the reason this is one constant: <i>"there is
     * more space on the x axis border than y, fix that, make it basically equal"</i>.
     *
     * <p>It is worth saying why four numbers ever looked acceptable. Each of them was arrived at
     * separately and each was defensible on its own — {@code EDGE * 2} because a row inside the card
     * should line up with a row inside the panel, 10 because a title's line box is only nine pixels
     * tall, 8 because it was {@code EDGE}, reused. None of them was ever compared with the others,
     * which is the whole of the fault: padding is a <b>single</b> decision, and four decisions that
     * happen to be plausible are four different answers to one question.
     *
     * <p>Twelve, because it has to be at least the panel's own {@link #EDGE} to read as a card rather
     * than as content touching its frame, and because it is the figure a footer's controls already sat
     * close to — so the card's height changes by a hair and its look changes completely.
     */
    public static final int MODAL_INSET = 12;

    /**
     * Between the body's bottom and the footer's controls. Not an inset: it separates two things.
     *
     * <p>Twelve, and that it equals {@link #MODAL_INSET} is the point rather than a coincidence: the
     * overlay draws its footer as one band, the controls are {@code MODAL_INSET} above the card's bottom,
     * and a band with anything else above them has them off its centre — which is what a report about a
     * Back button sitting high turned out to be. Ten was a number chosen on its own, next to twelve
     * chosen on its own, and the two never met until the band was drawn around both.
     */
    public static final int MODAL_FOOTER_GAP = 12;

    /**
     * The height of a control in the overlay's footer. Taller than a sidebar row: the overlay is the
     * screen where a player hands something in, so its controls are the primary thing on it.
     *
     * <h2>Why it is declared above the modal constants rather than with the overlay's own</h2>
     *
     * <p>Because {@link #MODAL_CHROME} is derived from it, and a static initializer cannot read a field
     * declared below it. That is a rule about the language rather than a preference, and it points the
     * right way here: a modal reserves exactly one control's height for its footer whether it is the
     * full-screen overlay or a content-sized card, so this number belongs to both and is declared
     * between them rather than with one.
     */
    public static final int OVERLAY_CONTROL_HEIGHT = 20;

    /**
     * The card's own chrome: the top inset, the body-to-footer gap, the controls, and the bottom inset.
     *
     * <p>What {@link #modalFramed} adds to a body's height, and it is now <b>derived</b> from the four
     * numbers that actually place things rather than written out as a figure of its own. It used to be
     * 84, a number that agreed with none of them: a body built for content plus 84 was 30 pixels taller
     * than the footer and the padding accounted for, so a card sized to a short roster had a hand's
     * width of nothing between its last row and its buttons. That is half of what the reported
     * "still almost blank" panel was.
     *
     * <p>Written as the sum because the sum is the definition. A reader who wants to know how tall a
     * card has to be should be able to read it off the four constants that decide it, not multiply
     * {@code MODAL_CHROME} by one and check {@link #modalControls} by eye.
     */
    public static final int MODAL_CHROME =
            MODAL_INSET + MODAL_FOOTER_GAP + OVERLAY_CONTROL_HEIGHT + MODAL_INSET;

    /**
     * The footer's whole region: the gap above the controls, the controls, and the inset below them.
     *
     * <p>What the card reserves for its footer, and what the overlay draws as the footer's band. One
     * constant rather than two expressions, because the band's height and the row's position are the
     * same decision: a band computed separately is a band the controls are not centred in.
     */
    public static final int MODAL_FOOTER_HEIGHT =
            MODAL_FOOTER_GAP + OVERLAY_CONTROL_HEIGHT + MODAL_INSET;

    // --- the full-screen overlay ---------------------------------------------

    /**
     * Submit's width, and Back's.
     *
     * <p>Named rather than written at the call site because the screen used to pass <b>130</b> here
     * while this class said <b>120</b>. That is the whole reason this class exists: two descriptions of
     * one control, which agree until somebody edits one. The overlap test would have passed on 120
     * while the screen drew 130 and ran into Back — a green test guarding a layout that was not on
     * screen, which is worse than no test, because it would have been believed.
     */
    public static final int SUBMIT_WIDTH = 130;

    public static final int BACK_WIDTH = 88;

    /**
     * Leave's and Disband's width, in the party panel's footer.
     *
     * <h2>Why these are not {@link #SUBMIT_WIDTH}</h2>
     *
     * <p>Because a 130-pixel button is sized for the one action a screen is about -- handing a task in.
     * "Leave" and "Disband" are six- and seven-letter words with a whole roster above them, and two of
     * them at Submit's width would take 260 of the 379 pixels a 427-wide window gives the card, pushing
     * Back onto a second row for no reason.
     *
     * <p>Wide enough for the longer word with the control's own insets: "Disband" is seven characters,
     * around 42 pixels at the font this UI draws with, plus the button's padding either side. The margin
     * above that is deliberate rather than measured to the pixel -- a translated label is longer than
     * the English one in most languages, and a button that truncates its own label reads as a fault in
     * whichever language noticed it first.
     */
    /**
     * How wide a control has to be for a short word, at the font this UI draws with.
     *
     * <h2>Why this exists, and what it is a fix for</h2>
     *
     * <p>64 and 88 were both chosen against an estimate of the font, and the estimate was short. The
     * screenshot came back with Leave drawn as "e" and Back as "Bac", because {@code ArmatureButton}
     * truncates against the real font at the client's GUI scale. A width written out by hand is a width
     * that is wrong on somebody else's window, and wrong in the direction that reads as a bug in the
     * button rather than in the constant.
     *
     * <p>Six pixels a character is close to the advance of the default font for the characters in these
     * labels, and it is measured rather than invented: "Disband" is seven characters, "Leave" five,
     * "Back" four. The padding either side is the button's own and is larger than a bare control's,
     * because the label sits inside a bordered box.
     *
     * <p>This deliberately does not measure the real font, because a geometry class has no font and must
     * not grow one; {@code Measure} exists for that and lives in the kit. So it is a margin of safety
     * for a known set of short English words, and a longer translation will be tight.
     */
    public static final int PARTY_SHORT_LABEL_WIDTH = 7 * 6 + 24;

    /** Leave's and Disband's width, in the party panel's footer. See {@link #PARTY_SHORT_LABEL_WIDTH}. */
    public static final int PARTY_ACTION_WIDTH = PARTY_SHORT_LABEL_WIDTH;

    /**
     * The party button's width, in the header.
     *
     * <p>Its own constant rather than a reuse of the footer's, because the two are sized from different
     * labels that happen to be a similar length today -- and a button whose width followed a word it
     * does not contain would be a coincidence rather than a measurement.
     */
    public static final int PARTY_BUTTON_WIDTH = 52;

    /**
     * How wide the party panel's card wants to be.
     *
     * <p>Wider than a paragraph needs and wider than the default cap was, because a roster is rows of
     * two things -- a name on the left and a rank on the right -- and the two drift apart into a gap
     * nobody reads across if the card is narrow. Its own constant rather than the panel's width,
     * because "how wide should this card be" is a question each modal answers for itself; see
     * {@link #modalFramed(int, int)}.
     */
    public static final int PARTY_MODAL_WIDTH = 320;

    // --- labels --------------------------------------------------------------

    /** A node label is never wider than this, however much room there is. */
    public static final int MAX_LABEL_WIDTH = 120;

    /** Below this there is no room for a readable label, so none are drawn. */
    public static final int MIN_LABEL_WIDTH = 30;

    /** Kept between a label and the node beside it. */
    public static final int LABEL_GAP = 4;

    // ------------------------------------------------------------------
    // A rectangle, and the three questions anything asks of one
    // ------------------------------------------------------------------

    /**
     * A rectangle in GUI coordinates.
     *
     * <p>{@link #intersects} is <b>exclusive</b> at the edges: two rectangles that share only an edge
     * do not intersect. That is what makes it usable for "do these two controls collide" — a pair
     * drawn edge to edge is touching, not overlapping, and reporting it as a collision would make the
     * test useless.
     */
    public record Rect(int x, int y, int width, int height) {

        public static Rect at(int x, int y, int width, int height) {
            return new Rect(x, y, width, height);
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean intersects(Rect other) {
            return x < other.right() && other.x < right()
                    && y < other.bottom() && other.y < bottom();
        }

        /**
         * Whether a point is inside. {@code double} because the mouse coordinates are, and a
         * {@code double} silently truncated to an int puts the pointer one pixel off at a boundary.
         */
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** Whether this rectangle is wholly inside {@code other}. */
        public boolean isInside(Rect other) {
            return x >= other.x && y >= other.y && right() <= other.right() && bottom() <= other.bottom();
        }

        @Override
        public String toString() {
            return x + "," + y + " " + width + "x" + height;
        }
    }

    // ------------------------------------------------------------------
    // The screen this was built for
    // ------------------------------------------------------------------

    private final int screenWidth;
    private final int screenHeight;
    private final Rect panel;
    private final Rect header;
    private final Rect canvas;
    private final Rect sidebar;
    private final Rect overlay;

    public BookGeometry(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        // Clamped to a minimum, and allowed to exceed the window below it. A window smaller than this
        // cannot show the book sensibly at any layout, so the choice is between a panel that runs off
        // the edge -- which is clipped, and obviously the window being too small -- and a panel whose
        // own contents overlap, which looks like a bug in the drawing code.
        int panelWidth = clamp(Math.min(MAX_PANEL_WIDTH, screenWidth - PANEL_MARGIN * 2),
                MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        int panelHeight = clamp(Math.min(MAX_PANEL_HEIGHT, screenHeight - PANEL_MARGIN * 2),
                MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);

        // Integer division, so a panel of odd width sits one pixel further left than right rather
        // than leaving a half pixel. Every other rectangle is derived from these two, so an odd
        // panel width is a one-pixel asymmetry everywhere and never a rounding drift.
        int panelLeft = (screenWidth - panelWidth) / 2;
        int panelTop = (screenHeight - panelHeight) / 2;

        this.panel = Rect.at(panelLeft, panelTop, panelWidth, panelHeight);
        this.header = Rect.at(panelLeft, panelTop, panelWidth, HEADER_HEIGHT);

        // The canvas is everything below the header, to the bottom of the panel. Nothing floats over
        // it any more: the summary strip went, so there is no band to reserve and no view port to keep
        // clear of one.
        this.canvas = Rect.at(panelLeft + SIDEBAR_WIDTH, panelTop + HEADER_HEIGHT,
                panelWidth - SIDEBAR_WIDTH, panelHeight - HEADER_HEIGHT);
        this.sidebar = Rect.at(panelLeft, panelTop + HEADER_HEIGHT, SIDEBAR_WIDTH,
                panelHeight - HEADER_HEIGHT);
        this.overlay = Rect.at(OVERLAY_MARGIN, OVERLAY_MARGIN,
                Math.max(MIN_PANEL_WIDTH, screenWidth - OVERLAY_MARGIN * 2),
                Math.max(MIN_PANEL_HEIGHT, screenHeight - OVERLAY_MARGIN * 2));
    }

    public int screenWidth() {
        return screenWidth;
    }

    public int screenHeight() {
        return screenHeight;
    }

    /** The whole book. */
    public Rect panel() {
        return panel;
    }

    /** The title bar, which holds the title, the quest count and Close. */
    public Rect header() {
        return header;
    }

    /** The graph, inside the panel. */
    public Rect canvas() {
        return canvas;
    }

    /** The chapter list, inside the panel. */
    public Rect sidebar() {
        return sidebar;
    }

    /** The full-screen quest view. */
    /**
     * The modal card: a centred box with caps.
     *
     * <p>See {@link #MAX_MODAL_WIDTH} for the report this answers. Two consequences worth stating: a
     * modal no longer changes shape when the window is resized past the cap, and a caller that needs to
     * know how much room it has asks this rather than subtracting a margin of its own, which is the
     * fault this class exists to prevent one level up.
     *
     * <p>The floors matter as much as the caps. A window smaller than the cap plus its margins gets what
     * is left rather than the cap, so the card never runs off the screen.
     */
    public Rect modal() {
        int width = Math.max(MIN_MODAL_WIDTH, Math.min(MAX_MODAL_WIDTH, screenWidth - MODAL_MARGIN * 2));
        int height = Math.max(MIN_MODAL_HEIGHT, Math.min(MAX_MODAL_HEIGHT, screenHeight - MODAL_MARGIN * 2));
        return Rect.at((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    /**
     * A modal card sized to the content it will hold, within the caps.
     *
     * <h2>Why a second entry point exists</h2>
     *
     * <p>Because a roster and a quest's prose want opposite things from a box. A roster is a short list:
     * three rows is three rows, and a card two hundred and sixty pixels tall holding sixty pixels of
     * content is the thing the report complained about. Prose fills whatever it is given and then
     * scrolls. So a caller that knows its content's height says so, and one that does not gets
     * {@link #modal()}.
     *
     * <p>The result is centred on the same centre, so a card that grows as members join grows in both
     * directions rather than downwards from a fixed top. A list that added a row and moved every row
     * above it would be the alternative.
     *
     * <h2>Why the width is a parameter rather than one number for every modal</h2>
     *
     * <p>Because a roster and a quest's prose want different cards, and a single width makes one of them
     * wrong. A roster is a name, a gap, and a rank: too wide and the eye has to cross the gap to read
     * them as one row. Prose is lines of text: too narrow and every paragraph wraps twice as often.
     *
     * <p>So each caller says what it wants and this clamps it to what the window can hold, which is the
     * half a caller cannot know. A width larger than {@link #MAX_MODAL_WIDTH} is capped rather than
     * refused, so a caller asking for "as wide as you can" gets the cap and not an exception.
     *
     * <h2>The width does not depend on the height, and a caller relies on it</h2>
     *
     * <p>{@code modalFramed(0, width).width() == modalFramed(anything, width).width()}, always. It reads
     * as an incidental truth and it is a load-bearing one: a panel whose height comes from its own
     * layout cannot know that height until it has built the layout, and it cannot build the layout
     * without knowing how wide the card is. So it asks for the width first, with the height unknown, and
     * that is only meaningful because the height is not an input to the width.
     *
     * <p>Stated here and asserted in {@code BookGeometryTest} rather than left to be noticed. A caller
     * asking twice and getting two answers would place every row it drew against a card that is not the
     * one on screen — which is the class of fault this whole file exists to prevent, arriving through a
     * parameter that reads as harmless.
     *
     * @param contentHeight   how much room the body needs, in pixels
     * @param preferredWidth  how wide the card would like to be
     */
    public Rect modalFramed(int contentHeight, int preferredWidth) {
        Rect base = modal();
        int width = Math.max(MIN_MODAL_WIDTH, Math.min(base.width(), preferredWidth));
        int height = Math.max(MIN_MODAL_HEIGHT, Math.min(base.height(), contentHeight + MODAL_CHROME));
        return Rect.at((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
    }

    /**
     * The region inside a card that its content may use: the card, inset, less the footer's row.
     *
     * <h2>Why this is here and the row height is not</h2>
     *
     * <p>Because "where inside this card may content go" is a framing question — this class's whole
     * subject — and "where do row <i>n</i> of my list go" is not, since that depends on how many rows
     * there are. So this answers the first and a {@code Stack} answers the second, and the two compose:
     * a caller builds its layout at {@code modalBody(card).width()} and skips a row whose bottom is past
     * {@code modalBody(card).height()}.
     *
     * <p>That is what replaced {@code bodyRows(card, count, top, bottom)}, which took a count and handed
     * back rectangles. It had a fault that no test caught and no reading would find: its {@code top} and
     * {@code bottom} arguments were the <i>caller's</i> idea of where the body starts and ends, so the
     * card's height and the rows inside it came from two different sums — the exact fault this class
     * exists to prevent, living inside the class. It has no callers now, and the panel it was written
     * for sizes its card from its layout instead.
     *
     * <p>The footer's row is {@link #OVERLAY_CONTROL_HEIGHT}, not the footer's actual controls: this says
     * how much room is <b>reserved</b> for them, and a caller that wanted to know where a specific button
     * went asks {@link #modalControls} instead. Two questions, two methods, and neither re-derives the
     * other's answer.
     */
    public static Rect modalBody(Rect card) {
        int height = card.height() - MODAL_INSET * 2 - MODAL_FOOTER_GAP - OVERLAY_CONTROL_HEIGHT;
        return Rect.at(card.x() + MODAL_INSET, card.y() + MODAL_INSET,
                Math.max(0, card.width() - MODAL_INSET * 2), Math.max(0, height));
    }

    /**
     * The modal card, for a caller that does not know how tall its content is.
     *
     * <p>An alias rather than a second rectangle: the quest overlay was written against a much larger
     * box and every position inside it is relative, so pointing it at the card shrinks the whole panel
     * with no arithmetic of its own changing. Two rectangles would be two things to keep in step.
     */
    public Rect overlay() {
        return modal();
    }

    // ------------------------------------------------------------------
    // The positions that the drawing and the controls both need
    // ------------------------------------------------------------------

    /** Where the sidebar's list starts. The top of {@link #sidebarViewport()}, and nothing else. */
    public int chapterListTop() {
        return panel.y() + HEADER_HEIGHT + CHAPTER_GAP;
    }

    /**
     * The region the sidebar's rows are scrolled within.
     *
     * <h2>What this replaces, and why a rectangle is the right thing to hand out</h2>
     *
     * <p>Two methods used to be here: {@code chapterRows()}, which answered how many rows fit, and
     * {@code chapterRowY(int)}, which answered where row {@code n} is. Both are gone, and the reason is
     * not that they were wrong — they were right, and asserted — but that they were <b>a second place
     * that knew where a row goes</b>. The rows are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack}
     * now, so that it, and the
     * {@link dev.ellipog.armature.client.ui.kit.ScrollView} over it, own hit-testing and culling. A
     * {@code chapterRowY} still standing here would be an arithmetic that agrees with the Stack on the
     * day it is written and drifts the first time either changes — the failure mode this whole class
     * exists to make impossible.
     *
     * <p>What is left that genuinely belongs to the framing is the <b>region</b>: where the list is
     * allowed to draw. That is a fact about the panel, which is this class's business, and it is the one
     * thing a caller cannot derive from the rows themselves — a list cannot know where its own edge is.
     *
     * <h2>The three subtractions, each with a reason</h2>
     *
     * <ul>
     *   <li><b>Top: {@link #chapterListTop()}.</b> Below the header and its gap.</li>
     *   <li><b>Bottom: the panel's own {@link #EDGE}.</b> Measured to the panel's edge, because there is
     *       nothing else below the list any more — see {@link #MIN_PANEL_HEIGHT} for the two appearance
     *       rows that used to be anchored there and the bug their anchoring caused.</li>
     *   <li><b>Right: {@link #SIDEBAR_SCROLLBAR}.</b> Room for the scrollbar, so the bar is drawn in the
     *       sidebar's margin rather than over the end of a row. The alternative — a bar drawn over the
     *       content, or a viewport that stops short of one — puts the bar and the row it belongs to in
     *       the same pixels, and a row that a bar can be read through is a row whose text is cut off.</li>
     * </ul>
     *
     * <p>Both dimensions are floored at zero rather than assumed. A window can be dragged smaller than
     * {@link #MIN_PANEL_WIDTH}, and a negative-width rectangle places a widget at a position that reads
     * correctly everywhere it is used — which is the shape of bug that survives to a screenshot. A
     * zero-area viewport draws nothing and can be clicked through, which is visibly wrong.
     */
    public Rect sidebarViewport() {
        int top = chapterListTop();
        // To the party strip, not to the panel's edge, and that is what the strip costs the list. The
        // alternative -- letting the rows run the full height and drawing the strip over them -- is the
        // class of fault this whole class exists to prevent: a row that stays clickable under a control
        // drawn on top of it.
        int bottom = panel.bottom() - EDGE;
        return Rect.at(panel.x() + EDGE, top,
                Math.max(0, sidebarInner() - SIDEBAR_SCROLLBAR),
                Math.max(0, bottom - top));
    }


    /** The close button: a row-height square in the header, against the panel's right edge. */
    public Rect closeRect() {
        return Rect.at(panel.right() - HEADER_CONTROL_INSET - ROW_HEIGHT,
                panel.y() + (HEADER_HEIGHT - ROW_HEIGHT) / 2, ROW_HEIGHT, ROW_HEIGHT);
    }

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>One expression, used by the drawing and by nothing else — but it is here rather than in the
     * screen because that is the rule this class exists to enforce, and because the alternative is a
     * second {@code panelWidth() - 12} that agrees until somebody moves the button.
     */
    public int headerRightLimit() {
        // Measured from the party button rather than from Close, because that is now the leftmost thing
        // in the header's right-hand cluster. When Close was the only control there, ten pixels left of
        // it was correct; adding a second control beside it would have left the quest count running
        // underneath, which is exactly the class of mistake this method exists to prevent.
        return partyButton().x() - 10;
    }

    /**
     * The party button, in the header to the left of Close.
     *
     * <h2>Why it moved here from the sidebar's foot</h2>
     *
     * <p>The column is a list of chapters and everything in it is about the questline; a party is not.
     * The header is where a screen puts the controls that act on the screen rather than on its content,
     * which is what Close is doing there -- and the request named the spot precisely: a small button top
     * right, left of the close, next to where a Claim All will go.
     *
     * <p>It also gives the sidebar its twenty-two pixels back. The strip and its gap were taken off the
     * chapter list; nothing needs that room now, so {@link #sidebarViewport} measures to the panel's
     * edge again.
     *
     * <p>Anchored right to left from Close, so a future Claim All goes to its left and pushes nothing.
     * A cluster laid out forwards from an origin would move every control when one was added.
     */
    public Rect partyButton() {
        return Rect.at(closeRect().x() - ROW_GAP - PARTY_BUTTON_WIDTH, closeRect().y(),
                PARTY_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The author's split control: `[Edit]` toggles edit mode, and the square `⚙` opens the tools panel.
     *
     * <h2>Why one unit rather than two buttons</h2>
     *
     * <p>Because they are one thing -- "the author's controls" -- and both are drawn only for a player who
     * may edit the questline, which is the same permission `/tasked reload` asks for. Placed left of the
     * party button, so the header reads: `[Edit] [gear] [Party] [Close]`.
     *
     * <p>The square half is the height of a control, so the two read as a unit rather than as a wide
     * button and an accident.
     */
    public Rect toolsButton() {
        return Rect.at(partyButton().x() - ROW_GAP - TOOLS_BUTTON_SIZE, partyButton().y(),
                TOOLS_BUTTON_SIZE, ROW_HEIGHT);
    }

    /** The `[Edit]` half. Wider: it carries a word. */
    public Rect editButton() {
        return Rect.at(toolsButton().x() - ROW_GAP - EDIT_BUTTON_WIDTH, toolsButton().y(),
                EDIT_BUTTON_WIDTH, ROW_HEIGHT);
    }

    /**
     * The backing panel behind the three view buttons.
     *
     * <p>Three pixels larger than the buttons on every side, and it is what makes them read as one
     * cluster rather than three controls that happen to be stacked.
     */
    public Rect viewControls() {
        return Rect.at(canvas.x() + EDGE - VIEW_MAT, canvas.y() + EDGE - VIEW_MAT,
                VIEW_BUTTON + VIEW_MAT * 2, VIEW_COLUMN_HEIGHT + VIEW_MAT * 2);
    }

    /** The width available inside the sidebar, between its two edges. */
    private int sidebarInner() {
        return SIDEBAR_WIDTH - EDGE * 2;
    }

    // ------------------------------------------------------------------
    // The controls
    // ------------------------------------------------------------------

    /**
     * Every control on the book screen, by name.
     *
     * <p>This is the method the screen and the test share, and that sharing is the whole point. The
     * screen creates its widgets from these rectangles; the test asserts they do not overlap. A
     * layout change therefore cannot move a control without the test seeing it.
     *
     * <p>Note what is <b>not</b> here: nothing named for the footer that used to hold these, no control
     * at all whose only job is to close a panel that Escape already closes, and nothing for the summary
     * strip that used to sit across the bottom. Close is one square in the header, and the four former
     * footer controls are three map buttons and that square.
     *
     * <h2>The chapter rows used to be handed out from here, and they are not any more</h2>
     *
     * <p>This was {@code controls(int chapters)}, and it put {@code chapter0} … {@code chapterN} into the
     * map, capped at the number that fitted. The map now holds only the controls whose positions are
     * <b>fixed</b> — the header's close button and the three map buttons — and the argument that told it
     * how many chapters there are is gone with them.
     *
     * <p>The reason is worth stating precisely, because it is not "the rows moved elsewhere" but
     * "something else owns them now". A chapter row's position depends on the row's own index (its y),
     * on whether its group is collapsed (whether it is drawn at all), and on the scroll (how far it has
     * been moved) — and the last two of those are properties of a <i>view</i>, not of a layout. A map
     * built once from a size cannot express "row 5, currently scrolled out of view" without also
     * becoming the thing that decides it, which is what a {@code Stack}, a {@code Layout} and a
     * {@code ScrollView} already are.
     *
     * <p>So this method keeps the half of its job it can still do honestly — the fixed chrome — and
     * {@link #sidebarViewport()} says where the rows are allowed to go. What the overlap test sweeps
     * therefore shrinks to four controls, which is correct rather than a loss: those are the four that
     * can collide, and the rows cannot collide with each other because a Stack places them in a column
     * one after another.
     */
    public Map<String, Rect> controls() {
        Map<String, Rect> out = new LinkedHashMap<>();

        // Close, in the header's right corner.
        out.put("close", closeRect());

        // The party strip, at the foot of the column. In this map rather than placed by the drawing,
        // for the reason every other entry is: the overlap sweep walks this, so a control that is not
        // in it is a control nothing checks against the ones that are.
        out.put("party", partyButton());

        // The author's split control, left of the party button. Always in the map and drawn only for a
        // player who may edit: geometry is what the overlap sweep checks, and a control that appeared in
        // the map only sometimes would be a control the sweep tests in one build and not the next.
        out.put("edit", editButton());
        out.put("tools", toolsButton());

        // The theme and motion controls are deliberately absent, and their absence is a decision worth
        // recording because the geometry for them existed and worked.
        //
        // They were the theme picker and the motion switch, anchored at the foot of the sidebar as two
        // full-width rows -- the only part of the whole overlay system that was not part of the design.
        // They are dev tools now: the same operations, reached from a mode rather than from a row sitting
        // permanently under the chapter list of every book. The picker belongs next to the editor, and
        // the motion switch belongs next to the accessibility settings it duplicates.
        //
        // Nothing here needs a replacement. `chapterRows()` measures to the panel's edge now, so the
        // space the rows occupied went back to the chapter list -- which is the honest use for it, since
        // it was always a compromise between a list of chapters and two controls that are not chapters.

        // The view cluster, top-left inside the canvas. A column, so it reads as one tool group and
        // leaves the canvas's width for the graph.
        int vx = canvas.x() + EDGE;
        int vy = canvas.y() + EDGE;
        out.put("zoomIn", Rect.at(vx, vy, VIEW_BUTTON, VIEW_BUTTON));
        out.put("zoomOut", Rect.at(vx, vy + VIEW_BUTTON + VIEW_GAP, VIEW_BUTTON, VIEW_BUTTON));
        out.put("centre", Rect.at(vx, vy + (VIEW_BUTTON + VIEW_GAP) * 2, VIEW_BUTTON, VIEW_BUTTON));

        return out;
    }

    /**
     * Every control on the full-screen overlay, by name.
     *
     * <p>Stacked when the overlay is too narrow for both side by side — rather than letting Submit
     * and Back collide on a small window, which is the same bug in a third place. A narrow window is
     * a real case here: the overlay is only {@code screenWidth - 48} wide, so a 427-wide window gives
     * it 379 and the two fit; a 300-wide one gives 252 and they would not.
     */
    public Map<String, Rect> overlayControls(boolean hasSubmit) {
        // **Measured against the card, not against the `overlay` field** -- and that was a real
        // fault rather than a tidy-up. The field is the old full-screen rectangle, computed once
        // in the constructor; the card is `modal()`, which is a different rectangle now. So Submit
        // and Back were placed outside the card they belong to: Back just past its bottom-right
        // corner, which is what the screenshot showed.
        //
        // The fault is the one this class exists to prevent, one level up: two rectangles describing
        // one panel, agreeing until the panel changed shape.
        return questFooter(modal(), hasSubmit);
    }

    /**
     * The quest overlay's footer, inside a card the caller supplies.
     *
     * <p>Submit on the left and Back on the right, and Back moves up a row when the two would
     * collide. Kept separate from {@link #modalControls} because the two cards hold different
     * controls -- see that method's note -- and this one takes its own width for Submit rather
     * than the short party actions'.
     */
    public Map<String, Rect> questFooter(Rect card, boolean hasSubmit) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int height = OVERLAY_CONTROL_HEIGHT;
        // The same inset as the body and the footer, and the same on both sides. See MODAL_INSET: this
        // row used to be 8 from the bottom edge and 16 from the left, with Back a further half a
        // control's height in from the right for no reason anybody could state.
        int rowY = card.bottom() - MODAL_INSET - height;

        Rect back = Rect.at(card.right() - MODAL_INSET - BACK_WIDTH, rowY, BACK_WIDTH, height);

        if (hasSubmit) {
            Rect submit = Rect.at(card.x() + MODAL_INSET, rowY, SUBMIT_WIDTH, height);
            if (submit.intersects(back)) {
                back = Rect.at(back.x(), rowY - height - ROW_GAP, BACK_WIDTH, height);
            }
            out.put("submit", submit);
        }

        out.put("back", back);
        return out;
    }

    /**
     * The party panel's footer: up to two short actions on the left, Back on the right.
     *
     * <h2>Why this is a separate shape rather than reusing the quest one</h2>
     *
     * <p>Because its controls are not a Submit/Back pair, and pretending they are would put a 130-pixel
     * button in a card whose real actions are "Leave" and "Disband". Reusing {@code overlayControls(boolean)}
     * would also mean asking it a question it cannot answer -- whether the viewer may leave is a fact about
     * a roster, not about a quest -- and the overloads say so in their signatures.
     *
     * <h2>The two actions share a row, and Back moves rather than collides</h2>
     *
     * <p>Same fallback the quest overlay uses, applied to a different set: measure against the card, and
     * move Back up a row if the three do not fit. One expression for the decision rather than a
     * per-control check, so two controls cannot disagree about whether they collided.
     *
     * <p>The action origin is the same whether there is one action or two, so a party of one and a party
     * of three put "Leave" in the same place. That matters because the panel's footer is the one part of
     * it whose position does not move when the membership does.
     *
     * @param actions  how many of Leave and Disband the viewer may use, 0 to 2
     * @param hasBack  whether to place Back at all -- a caller drawing a preview may not want it
     */
    public Map<String, Rect> overlayControls(int actions, boolean hasBack) {
        return modalControls(modal(), actions, hasBack);
    }

    /**
     * The same, placed inside a caller's own card.
     *
     * <h2>Why this takes the card rather than using {@link #overlay()}</h2>
     *
     * <p>Because a modal sized to its content has a rectangle the caller computed, and a footer placed
     * against a *different* rectangle is a footer outside its card -- the fault this class exists to
     * prevent, and one that a smaller card makes reachable: at the old size the two rectangles were
     * nearly the same, so using the wrong one looked almost right.
     */
    public Map<String, Rect> modalControls(Rect card, int actions, boolean hasBack) {
        Map<String, Rect> out = new LinkedHashMap<>();
        int height = OVERLAY_CONTROL_HEIGHT;
        // The same equal inset as the quest footer and as the panel's own rows. See MODAL_INSET for the
        // four numbers this replaced.
        int rowY = card.bottom() - MODAL_INSET - height;

        Rect back = null;
        if (hasBack) {
            back = Rect.at(card.right() - MODAL_INSET - BACK_WIDTH, rowY, BACK_WIDTH, height);
        }

        if (actions <= 0) {
            if (back != null) {
                out.put("back", back);
            }
            return out;
        }

        Rect first = Rect.at(card.x() + MODAL_INSET, rowY, PARTY_ACTION_WIDTH, height);
        out.put("leave", first);
        if (actions >= 2) {
            out.put("disband", Rect.at(first.right() + ROW_GAP, rowY, PARTY_ACTION_WIDTH, height));
        }

        if (back != null) {
            if (back.x() < first.right() + ROW_GAP) {
                // Not room for all three on one row, so Back moves up and keeps its right alignment.
                back = Rect.at(back.x(), rowY - height - ROW_GAP, BACK_WIDTH, height);
            }
            out.put("back", back);
        }

        return out;
    }

    // ------------------------------------------------------------------
    // Pure arithmetic the screen also uses
    // ------------------------------------------------------------------

    // A `Zoom` record and a `zoomAbout` method used to live here, with four tests of their own in
    // BookGeometryTest. They are gone, and the reason is worth keeping because it is the rule this
    // whole round was about rather than tidiness.
    //
    // They were a second implementation of zoom-about-the-pointer. The first was in
    // QuestBookScreen itself, as three static fields and the same algebra written out twice more --
    // in the pan, in the centring. The transform is `ui.kit`'s `Viewport` now, which the screen
    // calls, and `ViewportTest.zoomAtKeepsTheContentPointUnderThePointer` asserts the same
    // invariant this copy's suite did -- swept over more pans and scales than it managed, and
    // asserted against the transform rather than against a re-derivation of it.
    //
    // So this copy had no callers. Nothing said so: it compiled, its tests passed, and a reader
    // would reasonably have taken it for the one the screen uses. That is the failure mode of a
    // duplicate — not that it is wrong, but that it is *plausible*, and it stays plausible after
    // the thing it duplicates has moved on.
    //
    // What stays here is the framing: rectangles, the control map, label room, hit-test bounds.
    // That is one screen's own layout and nothing else's. Content-to-screen mapping is not framing,
    // it is a viewport, and it lives in the kit where a scrolling list can use it too.

    /**
     * The horizontal room a label may take, given the columns of nodes that are on screen.
     *
     * <h2>The invariant that makes crowding survivable</h2>
     *
     * <p>The result is <b>strictly less than the narrowest gap between columns</b>, because a margin is
     * subtracted at each side. That one fact is what stops two labels touching: each is truncated to
     * the room, and every room is narrower than the distance between the nodes it labels.
     *
     * <p>So a questline authored 64 pixels apart carrying 90-pixel titles yields 56-pixel labels, not
     * overlapping ones. That is the fix for the reported symptom — three whole titles centred on nodes
     * 64 apart ran through each other and read as one corrupted string — and it is why the answer here
     * is 56 rather than 0.
     *
     * <h2>Two thresholds, and they are not the same one</h2>
     *
     * <p>This returns zero only when a gap is no wider than the two margins together — 8 pixels at the
     * usual margin of 4. Between that and a comfortable gap sits a band where the room is positive and
     * still too narrow to be worth drawing; there the caller skips the labels, on its own
     * {@link #MIN_LABEL_WIDTH} test. Conflating the two is an easy mistake and was made in this file's
     * own first test.
     *
     * <p>Fewer than two columns returns {@link #MAX_LABEL_WIDTH}: there is no neighbour to crowd. For
     * an empty list that is moot — the caller iterates no quests — so it is the same branch rather than
     * a special case with no consequences.
     */
    public static int labelRoom(List<Integer> columnXs, int labelGap, int maxWidth) {
        List<Integer> sorted = new ArrayList<>(columnXs);
        sorted.sort(Integer::compareTo);

        if (sorted.size() < 2) {
            // One column, so no neighbour to crowd. The cap still applies.
            return maxWidth;
        }
        int gap = Integer.MAX_VALUE;
        for (int i = 1; i < sorted.size(); i++) {
            gap = Math.min(gap, sorted.get(i) - sorted.get(i - 1));
        }
        return clamp(gap - labelGap * 2, 0, maxWidth);
    }

    /**
     * Which of {@code rects} the pointer is in, or -1.
     *
     * <p>Backwards, so the last drawn — and therefore the topmost — wins. Two quests may legitimately
     * be at the same position, which the index reports as a warning, and picking the first would
     * select whichever happened to be authored earlier rather than the one you can see.
     */
    public static int hitTest(List<Rect> rects, double px, double py) {
        for (int i = rects.size() - 1; i >= 0; i--) {
            if (rects.get(i).contains(px, py)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether two controls are clear of each other *and* of a margin, for a sanity check. */
    public static boolean clearOf(Rect a, Rect b, int margin) {
        return !a.intersects(Rect.at(b.x() - margin, b.y() - margin,
                b.width() + margin * 2, b.height() + margin * 2));
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
