package dev.ellipog.tasked.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Which overlays occupy which columns, and what each kind is worth in width — the book's own rules,
 * with no client and no rectangles in them.
 *
 * <h2>Two presentations, one set of rules</h2>
 *
 * <p>A {@link PanelKind} says <i>what</i> is open. This says where it goes: a centred card holds one kind
 * at a time, and a docked side column can hold two — the thing you asked for, and the thing that thing
 * opened. Both presentations read these rules, so neither can invent its own answer to "what happens when
 * a picker opens over a quest".
 *
 * <h2>The depth rule, in one place</h2>
 *
 * <p><b>Column 1 is what you clicked; column 2 is what it opened.</b> A {@linkplain #isRoot root} — a
 * quest, the rewards inbox, the pack's assets, the settings card, a naming card — replaces column 1 and
 * empties column 2, because asking for one of those is asking to move on. A {@linkplain #isChild child} —
 * a picker, a table — fills column 2, and a further child replaces it rather than growing a third column:
 * a stack of columns would need a stack of scroll positions, and the back arrow already answers "where did
 * I come from".
 *
 * <p>A node click is its own case: it swaps column 1 and <b>empties column 2</b>. The child belonged to
 * the quest that was open, and leaving it there would point it at a quest it was never about.
 *
 * <h2>Folding, and why the fold is a preference rather than a fact</h2>
 *
 * <p>Two columns of sensible width do not fit in a reader's book — 340 plus 260 plus the gap is 612, and
 * the canvas beside a 156-pixel sidebar leaves at most 644 — so folding is the <i>common</i> case, not an
 * edge one. {@link Fold} is what the player asked for; {@code PanelLayout} decides what the window
 * actually allows, and {@link #presented} is the one answer both the drawing and the widget build read,
 * so a folded rail cannot have a column's controls registered behind the column that is showing.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The subjects. Which quest a card is about, which table a panel is editing, what a picker is picking
 * for — those stay the screen's typed fields, because they are per-kind state with per-kind types and
 * folding them into one record would make every reader of one kind carry the others.
 */
public final class PanelStack {

    /**
     * What the player asked for the second column to do.
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
     * What occupies each column.
     *
     * <p>{@link PanelKind#NONE} is "nothing", and both columns holding it is the book with no panel open.
     * The fold travels with the columns because it is a property of the arrangement rather than of either
     * occupant: switching which quest is shown must not lose the player's choice about the column.
     */
    public record Columns(PanelKind left, PanelKind right, Fold fold) {

        /** No panel open, and the window deciding the fold. */
        public static final Columns EMPTY = new Columns(PanelKind.NONE, PanelKind.NONE, Fold.AUTO);
    }

    // ------------------------------------------------------------------
    // Widths
    // ------------------------------------------------------------------

    /**
     * An ordinary root's default width, and the one the drag starts from.
     *
     * <p>340 rather than the tools dock's 300: this column carries prose -- a description, a task list --
     * and the reading card's own comfortable width is wider than a list of colour swatches.
     */
    public static final int WIDTH = 340;

    /** A child's default width. Narrower: a picker's list is names and icons, not sentences. */
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
     * so a window too small for the floor gets a column the width of its canvas rather than a panel that
     * covers the navigation. Two classes, two questions, one answer each — and {@code BookGeometryTest}
     * asserts the drawing's half, because the arithmetic that hid it was a floor nobody applied.
     */
    public static final int WIDE_MIN_WIDTH = 420;

    private PanelStack() {
    }

    // ------------------------------------------------------------------
    // Which kind is which
    // ------------------------------------------------------------------

    /**
     * Whether this kind is something the player asked for, rather than something opened from it.
     *
     * <p>The roots are the quest, the rewards inbox, the pack's assets, the settings card and a naming
     * card. Every one of them is reachable from the book's own chrome or its canvas, which is what makes
     * them roots: nothing has to be open for them to be asked for.
     */
    public static boolean isRoot(PanelKind kind) {
        return kind == PanelKind.QUEST || kind == PanelKind.TOOLS || kind == PanelKind.PARTY
                || kind == PanelKind.REWARDS || kind == PanelKind.ASSETS || kind == PanelKind.SETTINGS
                || kind == PanelKind.NAMING;
    }

    /**
     * Whether this kind is opened <i>from</i> something: a picker, or a table.
     *
     * <p>All four are lists of the pack's or the registry's things rather than the thing itself, and every
     * one of them has somewhere to return to -- which is why they can be emptied without losing anything.
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
     * Whether this kind has been converted to the docked presentation.
     *
     * <h2>Why the list is here rather than in the screen</h2>
     *
     * <p>Because it is this mode's one safety-critical fact. A kind that is presented in a column but is not
     * on this list would draw its <i>card</i> inside a column's frame — a crisp card over a crisp book, with
     * its footer controls placed in a column that is not there — and in the screen that list is unreachable
     * by any test. Here it is asserted, including the assertion that matters most: {@link PanelKind#CHOICE}
     * and {@link PanelKind#PARTY} are never on it. Both ask the player a question, both arrive on their own
     * rather than being asked for, and neither belongs beside a canvas.
     *
     * <h2>What "converted" means, and where the list stands</h2>
     *
     * <p>A kind is converted when the rectangle it draws itself into comes from {@code surfaceCard}, so that
     * the same drawing serves a card and a column: the two pickers take their body from {@code overlayBody},
     * the two tables read one expression ({@code tableBody}) for their bodies, their clips, their hints and
     * their hit tests, and the five roots — the quest, the rewards inbox, the settings card, a naming card and
     * the pack's assets — all ask for the surface being drawn. <b>Everything but the two questions is on this
     * list</b>, and it got there one kind at a time, each addition being that kind's own work.
     *
     * <p><b>This list is a fact about kinds, not about the client.</b> It says a kind <i>can</i> live in a
     * column; whether columns are in force is the player's switch, and the two must not be confused — see
     * {@link #afterOpen}, which takes the mode as a parameter for exactly that reason.
     */
    public static boolean isDocked(PanelKind kind) {
        return kind == PanelKind.QUEST
                || kind == PanelKind.TOOLS
                || kind == PanelKind.PARTY
                || kind == PanelKind.REWARDS
                || kind == PanelKind.SETTINGS
                || kind == PanelKind.NAMING
                || kind == PanelKind.ASSETS
                || isChild(kind);
    }

    /**
     * Whether this kind is in a column whatever the player's switch says, because it has no card form.
     *
     * <h2>The one kind that is not the switch's business</h2>
     *
     * <p>The mode decides how an <i>overlay</i> is presented: a card is the default and a column is the other
     * shape the same thing can take. The author's dock is neither — it is a panel that exists while edit mode
     * is on, and a "tools screen" was tried first and rejected, because the tool exists to watch the canvas it
     * floats over. So the switch must not be able to turn it into a centred card, and this is the predicate
     * that says so. It is asked twice: by {@link #afterOpen} when a child wants to sit beside a dock, and by
     * the screen when it decides whether a kind is presented in a column.
     *
     * <p>One kind, and {@code PanelStackTest} asserts it against every other kind — so a kind added later
     * cannot quietly claim an exemption from the switch.
     */
    public static boolean isAlwaysDocked(PanelKind kind) {
        return kind == PanelKind.TOOLS;
    }

    /**
     * Whether a second column is showing, which is what Escape's first press peels.
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
     * <p><b>A child fills column 2 — but only over a panel that can hold it; everything else replaces
     * column 1.</b> Written as "a child is the exception" rather than "a root is the rule", because the
     * classification is not exhaustive and must not need to be: {@link PanelKind#PARTY} and
     * {@link PanelKind#CHOICE} are neither a root nor a child (both are questions the player is asked
     * rather than places they went), and asking for one of those plainly means "show me this instead".
     *
     * <p>And "can hold it" is {@link #isDocked}, which is not a formality: a kind that still draws a card is
     * drawn <i>instead of</i> the columns, so a picker filed beside one would be in no picture at all —
     * invisible, and answering presses that the card behind it would also answer. So a child over a card
     * replaces it, exactly as it did before there were columns, and a child over a docked panel sits beside
     * it.
     *
     * <h2>Whether a column is in force is the caller's fact, and it has to be passed in</h2>
     *
     * <p>{@link #isDocked} answers "can this kind live in a column", which is a fact about the kind and
     * nothing else. It is <b>not</b> the same question as "is a column in force", which only the client's
     * own switch can answer -- and the two were confused here once, with the consequence the class comment
     * warns about: with the mode off, a child was filed beside a panel that was drawing its <i>card</i>, so
     * it was in no picture at all and its Escape had nothing to peel. A rule that read a preference would
     * stop being a rule a test can run without a client, so the preference arrives as a parameter, and the
     * two-argument overload -- every rule, as if the columns were in force -- stays for those tests.
     *
     * <p>{@link PanelKind#NONE} is not an opening — it is the absence of one — so it changes nothing;
     * closing goes through {@link #afterClose} or {@link #afterNodeClick}.
     */
    public static Columns afterOpen(Columns now, PanelKind opened) {
        return afterOpen(now, opened, true);
    }

    /**
     * The same, told whether the docked presentation is in force on this client.
     *
     * @param panelsOn whether this client is presenting panels in columns at all. False means every kind
     *                 draws its card, so a child has nowhere to sit and takes the first column instead --
     *                 which is exactly what it did before there were columns.
     */
    public static Columns afterOpen(Columns now, PanelKind opened, boolean panelsOn) {
        if (opened == PanelKind.NONE) {
            return now;
        }
        // **"Can hold it" is the mode, and only the mode.** A kind that is always docked is a column whatever
        // the switch says -- but that is about *its own* presentation, and a child is not it: the child has a
        // card, the switch decides whether a card is what an opened overlay gets, and with the switch off a
        // child over the author's dock therefore replaces it and is drawn as the card it has always been.
        // Reading `isAlwaysDocked` here as well was the tempting mistake: it would have put a picker into a
        // column on a client that had never turned columns on.
        if (isChild(opened) && panelsOn && isDocked(now.left())) {
            return new Columns(now.left(), opened, now.fold());
        }
        return new Columns(opened, PanelKind.NONE, now.fold());
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
     * <p>It is the transition every opening of a root already was, written once so that the screen's
     * branches can stop assigning the fields directly -- two writers of one arrangement is how a stale
     * second column outlives the panel that owned it.
     */
    public static Columns asRoot(Columns now, PanelKind opened) {
        if (opened == PanelKind.NONE) {
            return afterClose(now, false);
        }
        return new Columns(opened, PanelKind.NONE, now.fold());
    }

    /**
     * What a node click does: the quest takes column 1 and the second column is emptied.
     *
     * <p>The child was opened from the quest that was showing, so it cannot survive the swap -- a picker
     * left open would be picking for a quest nobody is looking at. The fold is the player's and stays.
     */
    public static Columns afterNodeClick(Columns now) {
        return asRoot(now, PanelKind.QUEST);
    }

    /**
     * Closes one column.
     *
     * <p>{@code child} is {@code true} for the outermost press -- Escape, the X, the fold's breadcrumb --
     * which drops the second column and, with it, the fold: the child is what was showing, so closing it
     * shows the parent again. {@code false} closes the first column, and both go with it, because a second
     * column is always something the first one opened.
     */
    public static Columns afterClose(Columns now, boolean child) {
        if (child) {
            return new Columns(now.left(), PanelKind.NONE, now.fold());
        }
        return new Columns(PanelKind.NONE, PanelKind.NONE, now.fold());
    }

    /**
     * The same arrangement, read by the other presentation.
     *
     * <h2>Into a card: the child is promoted, and nothing is lost</h2>
     *
     * <p>A card holds one kind, so something has to give, and the answer is the innermost thing the author
     * was on -- which is what they were looking at. The way back is not a stack of our own: every child
     * kind already has its own return path (a picker knows the field it was opened for, a table knows the
     * reward it came from), and those paths are what the card's Back arrow and its closers use.
     *
     * <h2>Into a panel: the card's kind becomes column 1</h2>
     *
     * <p>Column 2 starts empty either way: a mode flip is a fresh single-column arrangement rather than a
     * rearrangement of a stack that the other presentation could not have been holding.
     */
    public static Columns afterModeFlip(Columns now, boolean toPanels) {
        PanelKind carried = toPanels || !hasChild(now) ? now.left() : now.right();
        return new Columns(carried, PanelKind.NONE, now.fold());
    }

    /**
     * Which kind a single rail shows: the child if there is one, else the parent.
     *
     * <p>Only meaningful while folded -- see {@link #presented} for what is drawn when it is not -- and it
     * is the answer the folded header's breadcrumb has to name.
     */
    public static PanelKind shown(Columns now) {
        return hasChild(now) ? now.right() : now.left();
    }

    /**
     * What is on screen, in <b>draw order</b>, which is the order both callers depend on.
     *
     * <p>Two columns draw the second first, so the first column's edge and its rounded corners sit over
     * it -- the same reasoning that puts a card above the book. One column when folded, and that one is
     * {@link #shown}: the parent is not drawn behind it, which is the whole reason a folded rail cannot
     * have a hidden column's controls answering the pointer, taking the keyboard or appearing in the
     * narration order.
     *
     * <p>Empty rather than a list holding {@link PanelKind#NONE}: a caller that has to skip the absence of
     * a panel is a caller that can forget to.
     */
    public static List<PanelKind> presented(Columns now, boolean folded) {
        if (folded) {
            PanelKind only = shown(now);
            return only == PanelKind.NONE ? List.of() : List.of(only);
        }
        List<PanelKind> order = new ArrayList<>(2);
        if (hasChild(now)) {
            order.add(now.right());
        }
        if (now.left() != PanelKind.NONE) {
            order.add(now.left());
        }
        return List.copyOf(order);
    }

    // ------------------------------------------------------------------
    // Width policy
    // ------------------------------------------------------------------

    /** What this kind wants in column 1. */
    public static int preferredWidth(PanelKind kind) {
        if (kind == PanelKind.NONE) {
            return 0;
        }
        return isWide(kind) ? WIDE_WIDTH : WIDTH;
    }

    /** What this kind wants in column 2. A wide child wants its full width, and folds when it cannot. */
    public static int secondPreferredWidth(PanelKind kind) {
        if (kind == PanelKind.NONE) {
            return 0;
        }
        return isWide(kind) ? WIDE_WIDTH : SECOND_WIDTH;
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
     * would look like a broken layout rather than like a drag.
     */
    public static int clampWidth(int wanted, PanelKind kind) {
        int least = minimumWidth(kind);
        int most = isWide(kind) ? WIDE_WIDTH : MAX_WIDTH;
        return Math.max(least, Math.min(most, wanted));
    }

    /**
     * The clamp for a width with no kind behind it, which is the one read from the settings file.
     *
     * <p>Wider than any ordinary panel: the player's remembered width may have been chosen for a rewards
     * inbox, and clamping it to a prose column on the way in would silently change what they chose.
     */
    public static int clampStoredWidth(int wanted) {
        return Math.max(MIN_WIDTH, Math.min(WIDE_WIDTH, wanted));
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
     * <h2>One stored number, and which column owns it</h2>
     *
     * <p>{@code storedWidth} is the player's — the width the drag writes and the file remembers — and it
     * is <b>column 1's</b>, because column 1 is the thing the player was reading. Column 2 is transient:
     * it is a picker or a table opened from a field, its width is a property of what it holds, and a
     * second remembered number would be a second thing to explain and a second thing to get out of step.
     *
     * <p>Either way the kind has the last word, through {@link #clampWidth}: a rewards inbox opened at
     * somebody's 260-pixel prose preference is drawn at {@link #WIDE_MIN_WIDTH} instead, which is the
     * whole reason the clamp is per kind rather than one range for everything.
     *
     * @param kind        what the column holds
     * @param root        whether this is column 1 — the one the stored width belongs to
     * @param storedWidth the player's width, as the settings file holds it
     */
    public static int columnWidth(PanelKind kind, boolean root, int storedWidth) {
        if (kind == PanelKind.NONE) {
            return 0;
        }
        return clampWidth(root ? storedWidth : secondPreferredWidth(kind), kind);
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
