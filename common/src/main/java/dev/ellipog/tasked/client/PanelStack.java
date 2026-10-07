package dev.ellipog.tasked.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Which panel occupies which column, and what each kind is worth in width — the book's own rules, with no
 * client and no rectangles in them.
 *
 * <h2>Three rails: the dock, what you opened, and what that opened</h2>
 *
 * <p>The right of the canvas carries at most three things, and the order they are anchored in is the whole
 * of the arrangement. {@code dock} is the author's own tools, pinned to the canvas's right edge whenever the
 * Author pill is latched. {@code left} is the panel the player asked for — a quest, the rewards inbox, the
 * pack's files — and {@code right} is the child that panel opened: a picker, a texture list, a table. Each
 * sits one {@code PANEL_GAP} to the left of the one before it, so the dock is always outermost and a child
 * always innermost.
 *
 * <h2>The dock invariant, which is what this class now exists for</h2>
 *
 * <p><b>No transition may empty the dock, hide it, or fold it.</b> It was the fallback occupant of the first
 * column before this — {@code overlay == NONE && dockOpen} — so opening a panel took its rail and closing
 * that panel gave the rail back, and the author watched their tools disappear and reappear in the same
 * rectangle. That is the fault this shape fixes: the dock is written by {@link #withDock} and by nothing
 * else, every other transition carries {@code now.dock()} through untouched, and {@link #presented} appends
 * it last whatever the fold says. A reader never sees it at all, because a reader has no edit permission and
 * so never latches the pill.
 *
 * <h2>The depth rule, in one place</h2>
 *
 * <p><b>Column 1 is what you clicked; column 2 is what it opened.</b> Asking for a panel — a quest, the
 * rewards inbox, the pack's assets, the settings card, a naming card, a table — replaces column 1 and
 * empties column 2, because asking for one of those is asking to move on. A {@linkplain #isChild child}
 * fills column 2 when there is already a panel for it to belong to, and a further child replaces it rather
 * than growing a fourth rail: a stack of columns would need a stack of scroll positions, and the back arrow
 * already answers "where did I come from".
 *
 * <p><b>A child with no panel under it is not a child.</b> A picker opened from the dock's Chapter tab has
 * nothing to belong to, so it takes column 1 and sits beside the dock — which is also what stops it folding
 * the dock away, since there is no pair to fold. That is why {@link #afterOpen} asks whether column 1 holds
 * anything rather than whether the kind is a child.
 *
 * <p>A node click is its own case: it swaps column 1 and <b>empties column 2</b>. The child belonged to the
 * quest that was open, and leaving it there would point it at a quest it was never about.
 *
 * <h2>Folding, and why the fold is a preference rather than a fact</h2>
 *
 * <p>Two columns of sensible width do not fit in a reader's book — 340 plus 260 plus the gap is 612, and the
 * canvas beside a 156-pixel sidebar leaves at most 644 — so folding is the <i>common</i> case, not an edge
 * one. {@link Fold} is what the player asked for; {@code PanelLayout} decides what the window actually
 * allows, and {@link #presented} is the one answer both the drawing and the widget build read, so a folded
 * rail cannot have a column's controls registered behind the column that is showing.
 *
 * <p><b>Folding pages a child into its parent, and never touches the dock.</b> The fold is about the pair
 * "the panel you opened and the thing it opened"; the dock is not part of that pair, so it is drawn whether
 * the pair is folded or not. Folding one panel away is a page turn, not a panel hidden behind another.
 *
 * <h2>One presentation, and therefore no switch</h2>
 *
 * <p>There used to be two: a centred card and this column, chosen by a client setting and flipped by Ctrl+P.
 * The card is gone — every kind occupies a rail now, including the reward question, which is why
 * {@link #isChild} is the only classification left and {@code isDocked} is not a question at all.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The subjects. Which quest a panel is about, which table it is editing, what a picker is picking for —
 * those stay the screen's typed fields, because they are per-kind state with per-kind types and folding them
 * into one record would make every reader of one kind carry the others.
 */
public final class PanelStack {

    /**
     * What the player asked a panel's second column to do.
     *
     * <p>Three states rather than a boolean, and the third is the point: {@link #AUTO} is the default and
     * means "whenever the window has room", which is a question only the layout can answer. A switch with
     * two positions would have to choose a default the player did not ask for -- always folded on a wide
     * screen, or never folded on a small one.
     */
    public enum Fold {
        /** Let the window decide: two columns when both fit beside a minimum first column. */
        AUTO,
        /** Always folded: the child is paged into the first column, with a way back. */
        ALWAYS,
        /** Never folded: the second column is drawn even when it covers the canvas. */
        NEVER
    }

    /**
     * What occupies each rail.
     *
     * <p>{@link PanelKind#NONE} is "nothing", and every rail holding it is the book with no panel open at
     * all. The fold travels with the rails because it is a property of the arrangement rather than of any
     * occupant: switching which quest is shown must not lose the player's choice about the column.
     *
     * <p><b>{@code dock} is {@link PanelKind#TOOLS} or nothing</b>, and that is the only value it ever
     * holds: the dock is not a kind that can appear in either panel column, and a panel column is not a
     * thing that can appear in the dock's rail. Keeping it a {@code PanelKind} rather than a boolean is what
     * lets {@link #presented} name what is on screen as one list of kinds — the drawing, the widget build
     * and the press dispatch all walk that list and none of them has to know which rail a kind came from.
     */
    public record Columns(PanelKind dock, PanelKind left, PanelKind right, Fold fold) {

        /** Nothing open anywhere, and the window deciding the fold. */
        public static final Columns EMPTY =
                new Columns(PanelKind.NONE, PanelKind.NONE, PanelKind.NONE, Fold.AUTO);

        /** The arrangement with no dock, which is every reader's and most tests'. */
        public static Columns of(PanelKind left, PanelKind right, Fold fold) {
            return new Columns(PanelKind.NONE, left, right, fold);
        }
    }

    // ------------------------------------------------------------------
    // Widths
    // ------------------------------------------------------------------

    /**
     * An ordinary panel's default width, and the one the drag starts from.
     *
     * <p>340 rather than the tools dock's old 300: this column carries prose -- a description, a task list --
     * and the reading card's own comfortable width is wider than a list of colour swatches. The dock takes it
     * too: its rows are labelled fields and switches rather than prose, and it shares every panel's floor.
     */
    public static final int WIDTH = 340;

    /** A list's default width: a picker's rows are names and icons, not sentences. */
    public static final int SECOND_WIDTH = 260;

    /** The narrowest any panel is drawn, whatever the drag asks for. */
    public static final int MIN_WIDTH = 240;

    /** The widest an ordinary panel is dragged to. Wide kinds go beyond it; see {@link #isWide}. */
    public static final int MAX_WIDTH = 420;

    /**
     * What a wide kind wants: the width the centred card gave it.
     *
     * <p>Not a new number. {@code BookGeometry.MAX_WIDE_MODAL_WIDTH} is what these four layouts were
     * drawn against -- the rewards inbox's four columns, a table's entries, the pack's listing -- so the
     * panel asks for the width its own tested arithmetic already assumes.
     */
    public static final int WIDE_WIDTH = BookGeometry.MAX_WIDE_MODAL_WIDTH;

    /**
     * The floor a wide kind keeps, so a table is never squeezed into a column of prose width.
     *
     * <p>Below this the layouts would clamp themselves and the rows would read as broken rather than as
     * narrow, which is the failure this number exists to prevent.
     *
     * <p><b>What this floor binds, and what it does not.</b> It binds the drag ({@link #clampWidth}) and the
     * width a kind asks for, which is what a player's own hand and a remembered preference are held to. It
     * does not bind the drawing: a column is anchored inside the canvas and may never reach the chapter list,
     * nor step over the dock beside it, so a window too small for the floor gets a column the width of what
     * is left rather than a panel that covers the navigation. Two classes, two questions, one answer each --
     * and {@code BookGeometryTest} asserts the drawing's half, because the arithmetic that hid it was a floor
     * nobody applied.
     */
    public static final int WIDE_MIN_WIDTH = 420;

    private PanelStack() {
    }

    // ------------------------------------------------------------------
    // Which kind is which
    // ------------------------------------------------------------------

    /**
     * Whether this kind is opened <i>from</i> something: a picker, or a table.
     *
     * <p>All four are lists of the pack's or the registry's things rather than the thing itself, and every
     * one of them has somewhere to return to -- which is why they can be emptied without losing anything.
     * It is the only classification left, and it answers exactly one question: does this kind fill column 2
     * when column 1 already holds a panel, or does it take column 1 itself. {@link #afterOpen} states why
     * that question is about the arrangement and not only about the kind.
     */
    public static boolean isChild(PanelKind kind) {
        return kind == PanelKind.PICKER || kind == PanelKind.TEXTURE
                || kind == PanelKind.TABLE_BROWSER || kind == PanelKind.TABLE_EDITOR;
    }

    /**
     * Whether this kind needs the width the centred card used to give it.
     *
     * <p>These carry columns, entries or a listing that does not reflow into a narrow rail — see
     * {@link #WIDE_WIDTH}. <b>The party is not one of them</b> and used to be: it was docked as a wide
     * column so its roster and its management could sit side by side, and a tall sidebar has no reason to
     * spend width on a second column when it has all of the canvas's height to spend instead. The party
     * stacks its faces down one column now, so it takes the ordinary width.
     */
    public static boolean isWide(PanelKind kind) {
        return kind == PanelKind.REWARDS || kind == PanelKind.TABLE_EDITOR
                || kind == PanelKind.ASSETS;
    }

    /**
     * Whether a second column is showing, which is what Escape's first press peels.
     *
     * <p>The dock is not part of this question. It is a column of its own, so Escape's first press on a
     * folded pair turns the page and the dock stays where it was.
     */
    public static boolean hasChild(Columns now) {
        return now.right() != PanelKind.NONE;
    }

    // ------------------------------------------------------------------
    // The transitions
    // ------------------------------------------------------------------

    /**
     * Opens one kind, following the depth rule.
     *
     * <p><b>A child fills column 2 — but only under a panel; everything else replaces column 1.</b> Written
     * as "a child is the exception" rather than "a panel is the rule", because the classification is not
     * exhaustive and must not need to be: {@link PanelKind#CHOICE} is neither a child nor a panel you asked
     * for (it is a question the player is asked rather than a place they went), and asking for one of those
     * plainly means "show me this instead".
     *
     * <p><b>And "under a panel" is asked of the arrangement, not of the kind.</b> A picker opened from the
     * dock's Chapter tab has no panel under it, so it takes column 1: filed into column 2 over the dock it
     * would be a child of the author's tools, and folding the pair would take the dock away — the one thing
     * this class now forbids.
     *
     * <p>{@link PanelKind#NONE} is not an opening — it is the absence of one — so it changes nothing;
     * closing goes through {@link #afterClose} or {@link #afterNodeClick}.
     */
    public static Columns afterOpen(Columns now, PanelKind opened) {
        if (opened == PanelKind.NONE) {
            return now;
        }
        if (opened == PanelKind.TOOLS) {
            // The dock is a rail rather than a panel column, so "open the dock" is the dock's own transition.
            // Written as a delegation rather than as a special case at each caller, because the alternative is
            // a `TOOLS` in a panel column -- two rails holding the author's tools, one of them drawn under a
            // panel -- and because it makes this function total: every kind may be asked for here.
            return withDock(now, true);
        }
        if (opened == now.left() || opened == now.right()) {
            // **Asking for what is already open changes nothing**, and this is a fix rather than a
            // formality. A pick is armed again while its own panel is column 1 -- the dock's picker opened
            // twice, or a field re-picked before the first list is closed -- and the rule below would have
            // filed the same kind into column 2 as well: `presented` would name it twice, so two surfaces
            // would be drawn in one rail and both would answer one press. The arrangement cannot hold a kind
            // twice, so this is the line that keeps it from having to.
            return now;
        }
        if (isChild(opened) && now.left() != PanelKind.NONE) {
            return new Columns(now.dock(), now.left(), opened, now.fold());
        }
        return new Columns(now.dock(), opened, PanelKind.NONE, now.fold());
    }

    /**
     * A kind that takes the first column outright, whatever its classification.
     *
     * <h2>Why this is not {@link #afterOpen}</h2>
     *
     * <p>Because a kind's classification says where it <i>may</i> sit, not how it is reached. The two table
     * kinds are children by classification -- something opens them -- yet the screen's own roads to them (a
     * reward's table chip, the pack's assets panel) arrive with no parent at all, and they must replace the
     * arrangement rather than be filed into a second column by a rule that only knows their class.
     *
     * <p>It is the transition every opening of a panel already was, written once so that the screen's
     * branches can stop assigning the fields directly -- two writers of one arrangement is how a stale
     * second column outlives the panel that owned it. {@link PanelKind#NONE} closes both panel columns and
     * keeps the dock, which is what makes "close the panel" and "put the dock away" two different acts.
     */
    public static Columns asRoot(Columns now, PanelKind opened) {
        if (opened == PanelKind.NONE) {
            return afterClose(now, false);
        }
        if (opened == PanelKind.TOOLS) {
            // The same delegation {@link #afterOpen} makes, and for the same reason: `TOOLS` is a rail rather
            // than a panel, so a caller reaching here with it means "give me the dock", not "put the dock in a
            // panel column". Nothing in the screen does -- the Author pill calls {@link #withDock} -- and this
            // is what makes the pair of transitions total rather than a pair of caller contracts.
            return withDock(now, true);
        }
        return new Columns(now.dock(), opened, PanelKind.NONE, now.fold());
    }

    /**
     * What a node click does: the quest takes column 1 and the second column is emptied.
     *
     * <p>The child was opened from the quest that was showing, so it cannot survive the swap -- a picker
     * left open would be picking for a quest nobody is looking at. The fold is the player's and stays, and
     * so is the dock: an author clicking through a chapter keeps their tools.
     */
    public static Columns afterNodeClick(Columns now) {
        return asRoot(now, PanelKind.QUEST);
    }

    /**
     * Closes one column.
     *
     * <p>{@code child} is {@code true} for the outermost press -- Escape, the X, the fold's breadcrumb --
     * which drops the second column and, with it, the fold: the child is what was showing, so closing it
     * shows the parent again. {@code false} closes the first column, and the second goes with it, because a
     * second column is always something the first one opened. <b>Neither touches the dock</b>, which is why
     * this is not the transition a blank-canvas click reaches for the dock with: putting the tools away is
     * {@link #withDock}, and it is a press on the Author pill, the X or Escape.
     */
    public static Columns afterClose(Columns now, boolean child) {
        if (child) {
            return new Columns(now.dock(), now.left(), PanelKind.NONE, now.fold());
        }
        return new Columns(now.dock(), PanelKind.NONE, PanelKind.NONE, now.fold());
    }

    /**
     * The author's dock, on or off — the one transition that writes that rail.
     *
     * <h2>Why this is a transition rather than a field the screen owns</h2>
     *
     * <p>Because it is part of the arrangement, and the arrangement has one writer ({@code applyColumns} in
     * the screen). A latch the screen toggled directly was how a second writer appeared the last time, and
     * the cost of a second writer is a stale rail: the drawing, the widget build and the press dispatch all
     * walk {@link #presented}, so a dock that left the record without leaving the field was a column drawn
     * with no controls, or controls with no column.
     *
     * <p>Putting the dock away is <b>not</b> closing a panel: the panel arrangement is carried through
     * untouched, so an author who puts their tools away while reading a quest gets the quest back at the
     * same width and the same scroll.
     */
    public static Columns withDock(Columns now, boolean open) {
        return new Columns(open ? PanelKind.TOOLS : PanelKind.NONE, now.left(), now.right(), now.fold());
    }

    /**
     * Which kind a single folded rail shows: the child if there is one, else the parent.
     *
     * <p>Only meaningful while folded -- see {@link #presented} for what is drawn when it is not -- and it
     * is the answer the folded header's breadcrumb has to name. The dock is never this answer: it is not one
     * of the folded pair.
     */
    public static PanelKind shown(Columns now) {
        return hasChild(now) ? now.right() : now.left();
    }

    /**
     * What is on screen, in <b>draw order</b>, which is the order both callers depend on.
     *
     * <h2>Right to left, so each rail's edge sits over the one inside it</h2>
     *
     * <p>The list is innermost first: the child, then the panel it belongs to, then the dock. Each is
     * anchored one gap to the left of the one after it, so drawing in this order puts the outer rail's edge
     * and its rounded corners over the inner one's -- the same reasoning that puts a panel above the book.
     *
     * <h2>The dock is appended whatever the fold says</h2>
     *
     * <p>Two things follow, and both are the point of this round. <b>Folding cannot hide the dock</b>, so
     * the fold stays what it says it is -- a page turn between a panel and the child it opened -- rather
     * than becoming a way to take the author's tools away. And <b>a folded rail shows one panel</b>: the
     * parent is not drawn behind the child, which is the whole reason a folded rail cannot have a hidden
     * column's controls answering the pointer, taking the keyboard or appearing in the narration order.
     *
     * <p>Empty rather than a list holding {@link PanelKind#NONE}: a caller that has to skip the absence of
     * a panel is a caller that can forget to.
     */
    public static List<PanelKind> presented(Columns now, boolean folded) {
        List<PanelKind> order = new ArrayList<>(3);
        if (folded) {
            PanelKind only = shown(now);
            if (only != PanelKind.NONE) {
                order.add(only);
            }
        }
        else {
            if (now.right() != PanelKind.NONE) {
                order.add(now.right());
            }
            if (now.left() != PanelKind.NONE) {
                order.add(now.left());
            }
        }
        // Last, and unconditionally: outermost, and never a thing a transition may take away. See the class
        // comment's dock invariant.
        if (now.dock() != PanelKind.NONE) {
            order.add(now.dock());
        }
        return List.copyOf(order);
    }

    // ------------------------------------------------------------------
    // Width policy
    // ------------------------------------------------------------------

    /**
     * What this kind opens at, before the player has dragged anything.
     *
     * <h2>Why this is per kind, and what the one number used to cost</h2>
     *
     * <p>There was one remembered width for every panel, and it was column 1's: column 2 took its kind's
     * fixed preferred width and a drag of its edge was deliberately forgotten. That made a wide panel's
     * width and a list's width fight over one number -- drag the rewards inbox wide, open a picker, and the
     * picker's rail was 260 because the picker is not a thing you read. Per kind is the honest shape: each
     * panel comes back at the width it was last given, and its own floor and ceiling still have the last
     * word through {@link #clampWidth}.
     *
     * <p>The defaults are the numbers the layouts were drawn against: a list is a list, a table is a table,
     * and everything that carries prose takes {@link #WIDTH}. The dock is one of those -- its rows are
     * labelled fields and switches -- which is also why it shares every panel's floor.
     *
     * <p><b>The browser is a list rather than a wide kind</b>, which is the one entry worth reading twice.
     * {@link #isWide} excludes it -- it is rows of table names with three small controls on each row, not
     * columns of entries -- and as a child it was always drawn at {@link #SECOND_WIDTH}. Now that its width
     * is its own rather than its column's, the default has to say the same thing the column used to.
     */
    public static int defaultWidth(PanelKind kind) {
        if (kind == PanelKind.NONE) {
            return 0;
        }
        if (kind == PanelKind.PICKER || kind == PanelKind.TEXTURE || kind == PanelKind.TABLE_BROWSER) {
            return SECOND_WIDTH;
        }
        return isWide(kind) ? WIDE_WIDTH : WIDTH;
    }

    /** The narrowest this kind may be drawn. */
    public static int minimumWidth(PanelKind kind) {
        return isWide(kind) ? WIDE_MIN_WIDTH : MIN_WIDTH;
    }

    /**
     * The drag's clamp, for one kind.
     *
     * <p>One function rather than a floor and a ceiling at the call site: a drag that clamped to the wrong
     * pair would let a rewards inbox shrink into a column that cannot hold its four columns, and the fault
     * would look like a broken layout rather than like a drag. It is also what a width read from the
     * settings file goes through, because that file is hand-editable and a remembered number is only as
     * legal as this function makes it.
     */
    public static int clampWidth(int wanted, PanelKind kind) {
        int least = minimumWidth(kind);
        int most = isWide(kind) ? WIDE_WIDTH : MAX_WIDTH;
        return Math.max(least, Math.min(most, wanted));
    }

    /**
     * The width a drag has reached.
     *
     * <p>The edge being dragged is the column's <b>inner</b> one, so moving the pointer left makes the
     * column wider and the arithmetic is a subtraction from where the press grabbed it. Pure, so the
     * gesture is asserted without a mouse: the test sweeps the pointer across the column and out of both
     * sides, and the clamp is what stops the sweep at something a panel can be.
     *
     * @param startWidth the width when the press landed
     * @param startX     where the press landed
     * @param currentX   where the pointer is now
     * @param kind       what the column holds, whose own floor and ceiling apply
     */
    public static int widthWhileDragging(int startWidth, double startX, double currentX,
            PanelKind kind) {
        return clampWidth((int) Math.round(startWidth + (startX - currentX)), kind);
    }

    /**
     * The narrowest a footer of these controls can be and still read.
     *
     * <h2>Measured rather than guessed</h2>
     *
     * <p>The floor this replaces was a constant — four labels of "about fifty pixels" — which is a guess
     * about a font standing in for a measurement of one. Measuring costs nothing here: the labels are four
     * fixed strings, so this is asked for once and remembered, which is the shape {@code Measure.cached}
     * exists for in the kit.
     *
     * <p>The widths passed in are each control's <b>widest state</b>, not the one it happens to be showing:
     * the editor's Delete reads "Really delete?" while it is armed, half again as wide as the word resting
     * there, and a floor computed from "Delete" would let the column be dragged to a width where the armed
     * button's own label no longer fits — a control that breaks exactly when it is asking a question.
     *
     * @param labelWidths the measured width of every action's widest label, in drawing order
     * @param gap         the air between two actions, and between the actions and the one control beside them
     * @param backWidth   what that last control needs — Back, or Done
     * @param inset       the surface's own inset, on each side
     */
    public static int footerMinimumWidth(List<Integer> labelWidths, int gap, int backWidth, int inset) {
        int actions = gap * Math.max(0, labelWidths.size() - 1);
        for (int width : labelWidths) {
            actions += Math.max(0, width);
        }
        return Math.max(0, inset) * 2 + actions + Math.max(0, gap) * 2 + Math.max(0, backWidth);
    }

    /**
     * The width one column is actually drawn at.
     *
     * <p>{@code stored} is the player's for this kind — the width the drag writes and the file remembers —
     * and the kind has the last word through {@link #clampWidth}: a rewards inbox opened at somebody's
     * 260-pixel prose preference is drawn at {@link #WIDE_MIN_WIDTH} instead, which is the whole reason the
     * clamp is per kind rather than one range for everything.
     *
     * <p>Nothing has no width, and a caller asking for one is asking a question with no answer rather than
     * a very small one.
     */
    public static int columnWidth(PanelKind kind, int stored) {
        if (kind == PanelKind.NONE) {
            return 0;
        }
        return clampWidth(stored, kind);
    }

    // ------------------------------------------------------------------
    // The words the settings file uses
    // ------------------------------------------------------------------

    /** The word {@code panelFold} carries for this state. */
    public static String foldWord(Fold fold) {
        return switch (fold) {
            case AUTO -> "auto";
            case ALWAYS -> "on";
            case NEVER -> "off";
        };
    }

    /**
     * The state a word names, tolerant of anything else.
     *
     * <p>Unknown text is {@link Fold#AUTO} rather than an error: this file is hand-edited, and a typo
     * should cost a preference -- the window deciding, which is the behaviour a player who never opened
     * the file gets -- rather than a client that will not start.
     */
    public static Fold foldOf(String word) {
        if (word == null) {
            return Fold.AUTO;
        }
        return switch (word.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "always" -> Fold.ALWAYS;
            case "off", "never" -> Fold.NEVER;
            default -> Fold.AUTO;
        };
    }
}
