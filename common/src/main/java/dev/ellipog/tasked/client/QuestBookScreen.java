package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.Appearance;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.ArmatureLive;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tasked.client.dev.ToolsLayout;
import dev.ellipog.tasked.client.dev.ToolsPanel;
import dev.ellipog.tasked.client.editor.EditorSession;
import dev.ellipog.tasked.client.editor.QuestEditor;
import dev.ellipog.tasked.net.PartySnapshot;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.client.ClientPartyCache;
import dev.ellipog.tasked.net.ClaimRewardPayload;
import dev.ellipog.tasked.net.SubmitTaskPayload;
import dev.ellipog.tasked.progress.QuestState;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The quest book: a chapter list, a canvas of quests, and a full-screen reading view.
 *
 * <h2>The blur, and how it was caused</h2>
 *
 * <p>The first version of this screen was blurred, along with the world behind it, on both loaders.
 * The cause is not guessable from the symptom, so it is written down. From Minecraft 1.21.1's own
 * source:
 *
 * <pre>
 * Screen.render(g, mx, my, pt) {
 *     renderBackground(g, mx, my, pt);      // &lt;-- this
 *     for (Renderable r : renderables) r.render(...);
 * }
 *
 * Screen.renderBackground(g, mx, my, pt) {
 *     if (minecraft.level == null) renderPanorama(g, pt);
 *     renderBlurredBackground(pt);          // &lt;-- gameRenderer.processBlurEffect(pt)
 *     renderMenuBackground(g);              // &lt;-- MENU_BACKGROUND over everything
 * }
 * </pre>
 *
 * <p>So {@code super.render()} calls {@code renderBackground} whether the subclass wants it or not,
 * and that is where vanilla's menu blur comes from. This screen called {@code super.render()}
 * <b>last</b>, so the blur post-effect processed a framebuffer that already contained this screen's
 * text — which is why the text was soft as well as the world. Overriding {@code renderBackground} to
 * do nothing is the whole fix, and it is one method — which now lives on {@code ArmatureScreen}, so
 * that the next panel cannot forget it: the developer screen did, and shipped washed out.
 *
 * <h2>Three ways to look around</h2>
 *
 * <ul>
 *   <li><b>Scroll</b> zooms, about the pointer — so the thing under the cursor stays under it.</li>
 *   <li><b>Hold left and drag</b> pans. A press that does not move is a click, and a click on a node
 *       selects it — so selecting and panning are the same gesture, told apart by whether the pointer
 *       moved. Nothing is selected on press, only on release.</li>
 *   <li><b>Hold middle and drag</b> pans from anywhere, including over a node, so it can never be
 *       read as a click.</li>
 * </ul>
 *
 * <h2>Layout, and the bug that made this a separate concern</h2>
 *
 * <h2>The selected chapter is a fill, and getting that wrong is worth knowing about</h2>
 *
 * <p>The chapter list marked the current chapter with {@code .flat(true)} — no fill, no border — on the
 * reasoning that a control with nothing behind it stands out among controls that have something. It does
 * stand out, and as the wrong thing: in a list of filled rows, the one with no box reads as the
 * <i>disabled</i> or missing entry. A selection has to be something that is there, not something that is
 * absent. It is {@code .selected(true)} now, and {@code ArmatureControlStyle} decides what that looks
 * like so the preview draws the same thing this screen does.
 *
 * <p>The first working version drew two controls on top of each other in the bottom-right corner. The
 * cause was writing "near the bottom right" twice, as two different expressions that were not equal:
 *
 * <pre>
 * Open: canvasRight() - 78,  canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two x values are 10px apart, and the
 * two y values are ~15px apart — a collision. So every position that the drawing and the controls both
 * need now comes from a helper method: {@link #footerRow1Y()}, {@link #footerRow2Y()},
 * {@link #stripButtonX()}, {@link #stripButtonY()}, {@link #sidebarViewport()}. One expression, used twice,
 * rather than two expressions that happen to agree until someone changes one.
 *
 * <h2>Every colour is eight digits</h2>
 *
 * <p>{@code 0xAARRGGBB}, alpha included, and all of them live in {@link ArmatureTheme}. On 1.21.1 a
 * colour written without alpha happens to come out opaque; from 1.21.6 it does not.
 */
public final class QuestBookScreen extends ArmatureScreen {

    // ------------------------------------------------------------------
    // Layout constants. See the class comment for why these are shared.
    // ------------------------------------------------------------------

    // Every one of these is an alias, not a number. The number lives in BookGeometry, and this class
    // reads it from there -- because a second copy of "the sidebar is 132 wide" is a copy that can
    // disagree with the one the overlap test checks. That disagreement is precisely how two buttons
    // ended up drawn on top of each other: "near the bottom right" written twice.
    private static final int SIDEBAR_WIDTH = BookGeometry.SIDEBAR_WIDTH;
    private static final int HEADER_HEIGHT = BookGeometry.HEADER_HEIGHT;
    private static final int OVERLAY_MARGIN = BookGeometry.OVERLAY_MARGIN;

    /** The sidebar's footer, as two rows of its own. Four controls do not fit across one. */
    private static final int ROW_HEIGHT = BookGeometry.ROW_HEIGHT;
    private static final int ROW_GAP = BookGeometry.ROW_GAP;

    /**
     * The layout, rebuilt when the window changes size.
     *
     * <h2>Why the screen does not compute its own coordinates any more</h2>
     *
     * <p>Because it did, and the arithmetic was untestable. {@code QuestBookScreen} extends
     * {@code Screen}, so it needs a running Minecraft to instantiate — which means a test can never ask
     * it where its controls are, and "these two do not overlap" cannot be asserted at all. That is not
     * a gap in the tests, it is the reason the bug got as far as a screenshot.
     *
     * <p>So the positions come from {@link BookGeometry}, which has no Minecraft in it and can be
     * tested (see {@code BookGeometryTest}). Rebuilt on demand rather than in {@code init()} because
     * {@code render} and {@code mouseClicked} both need it, and neither is guaranteed to run after the
     * other — a cache keyed on the size is correct however the call order falls.
     */
    private BookGeometry geometry;
    private int geometryWidth = -1;
    private int geometryHeight = -1;

    private BookGeometry geometry() {
        if (geometry == null || geometryWidth != width || geometryHeight != height) {
            // Full-bleed for an author, a card for a reader. See `BookGeometry`'s constructor: the margin
            // and the maximum size exist so a reading panel does not read as a wall, and an author is not
            // reading -- every spare pixel is canvas or colour list.
            geometry = new BookGeometry(width, height, mayEdit());
            geometryWidth = width;
            geometryHeight = height;
        }
        return geometry;
    }

    /**
     * Item boxes, and the row pitch derived from them.
     *
     * <h2>Why these are constants and not numbers at the call sites</h2>
     *
     * <p>Because the boxes and the item inside them were sized independently and disagreed. A node's
     * box scales with the zoom, but the item in it was pinned at vanilla's 16px and centred in
     * whatever space was left — so at 100% a 26px node held a 16px item, and at 220% a 105px node held
     * the same 16px item, which reads as a rendering fault rather than as a small picture. And a row's
     * pitch was 13px while the icon in it was 16px tall, so consecutive icons overlapped.
     *
     * <p>So one number per box, and everything around it derived: the row pitch, the gap under an icon
     * and the box an icon fills are {@link OverlayLayout}'s, and this screen reads them from there —
     * because those same numbers are what the scrollbar's range is computed from, and a copy here is a
     * copy that can disagree with the height the layout reports.
     *
     * <p>{@link #NODE_INSET} stays: it is about a node on the canvas, which no layout knows about.
     */
    private static final int NODE_INSET = 3;

    /** The item box in an overlay row. The layout reserved this box; the icon fills it. */
    private static final int ROW_ICON = OverlayLayout.ROW_ICON;

    /** A contributor's face in a task row, its gaps, and how many rows of them fit before "+n". */
    private static final int CONTRIBUTOR_FACE = 8;
    private static final int CONTRIBUTOR_COUNT_GAP = 2;
    private static final int CONTRIBUTOR_GAP = 6;
    private static final int MAX_CONTRIBUTORS = 3;

    /**
     * How far a contributor's number sits below the row's own text line.
     *
     * <p>The same optical point as the party panel's {@link PartyPanelLayout#NAME_DROP}: a label's line
     * box carries descender space under the baseline, so a number sharing a line with a face centred on
     * its own box reads a pixel high. One, rather than that panel's two, because this row's text already
     * sits above the icon's centre.
     */
    private static final int CONTRIBUTOR_TEXT_DROP = 1;

    private static final int HEADER_ICON = 20;

    /**
     * Where the overlay's body sits inside its card: inset from the sides, and the stops above and
     * below it.
     *
     * <p>Above is the header's height plus a gap; below is the footer's rule plus its own. The drawing
     * and the clip used to write these numbers out separately — {@code left + 18} and {@code top + 54}
     * in two places each — which is two expressions for one rectangle, and the same class of mistake as
     * the two controls that were once drawn on top of each other, one surface up.
     */
    private static final int BODY_INSET = 18;
    private static final int BODY_TOP = 54;
    private static final int BODY_BOTTOM = 46;

    /**
     * Node labels, and the room they are allowed to take.
     *
     * <h2>The garbled-text bug this replaces</h2>
     *
     * <p>Node titles were truncated to {@code (int) (size / zoom / 5)} <b>characters</b> — a number with
     * no relationship to the font, to the title, or to the space between nodes. And the space between
     * nodes was never consulted at all, so with a questline authored 64px apart, three labels of about
     * 90px were drawn centred on nodes 64px apart and ran straight into each other. In the screenshot
     * that read as <em>"Punch a SomewherStone To…"</em>, which is three quest titles interleaved and
     * looks like a corrupt string rather than like three labels that are too wide.
     *
     * <p>So the room is <b>measured</b> — the smallest horizontal gap between node columns, in screen
     * pixels — and every label is truncated to it by <b>width</b>
     * ({@code Font.plainSubstrByWidth}), not by character count. Where there is not enough room for
     * even a few characters, no labels are drawn at all and the hover caption carries the name. One
     * measured rule, instead of a zoom threshold that was a guess.
     */
    // Aliases again, for the same reason as the layout constants above: the number lives in
    // BookGeometry, where BookGeometryTest can assert the invariant that depends on it -- that the
    // room is always strictly less than the gap between two nodes.
    private static final int MAX_LABEL_WIDTH = BookGeometry.MAX_LABEL_WIDTH;

    /** Below this there is no room for a readable label, so none are drawn. */
    private static final int MIN_LABEL_WIDTH = BookGeometry.MIN_LABEL_WIDTH;

    /** The gap kept between a label and the node beside it. */
    private static final int LABEL_GAP = BookGeometry.LABEL_GAP;

    /** The smallest item box worth drawing an item into. Below it, a block instead of a smudge. */
    private static final int MIN_ITEM_BOX = 12;

    private static final float MIN_ZOOM = 0.35F;
    private static final float MAX_ZOOM = 2.2F;

    /** How far the pointer must move before a press counts as a drag rather than a click. */
    private static final double DRAG_THRESHOLD = 4.0;

    /** Which panel is covering the book, if any. */
    private enum Overlay { NONE, QUEST, PARTY }

    // --- view state, kept between openings -----------------------------------

    /**
     * How hovered each node looks, and each row of the overlay's body.
     *
     * <h2>Two trackers rather than one, because two things are never hovered at once</h2>
     *
     * <p>A pointer is either over the canvas or over the overlay — the overlay covers the canvas
     * entirely — so one tracker would work. Two are used anyway because the keys would otherwise share
     * a namespace: a quest id and a row key like {@code "task:0"} can never collide today, and nothing
     * would notice if a future key scheme made them collide, because a hover that lit the wrong thing
     * for one frame is not a thing anyone reports.
     *
     * <p>Instances rather than static, unlike the selection above: a hover is <b>per screen</b>, and a
     * screen that reopened with the last one's hovered row still lit would point at whatever now sits
     * at that index. {@code clear()} is called when the overlay opens and closes for the same reason.
     */
    private final Hover nodeHover = new Hover();
    private final Hover rowHover = new Hover();

    private static String selectedChapter;
    private static String selectedQuest;

    /**
     * The chapter whose missing theme was last reported, so the warning is not repeated every frame.
     *
     * <h2>What this guards, and what it used to</h2>
     *
     * <p>It used to guard the <i>application</i> of a chapter's theme, because applying one was a global
     * side effect that {@link #init} would otherwise repeat on every resize and every widget rebuild.
     * A chapter's theme is now applied by opening a scope around the canvas and the overlay, which costs
     * nothing to re-open and resets no tween — so there is nothing left to guard except the message.
     *
     * <p>The message does still need guarding: it is emitted from inside a per-frame drawing path, and a
     * chapter with a misspelled theme name would otherwise write a line of log per frame, which buries
     * everything else in it. Once per chapter, cleared on disconnect, is the useful amount.
     */
    private static String warnedThemeFor;

    /**
     * The canvas transform: pan, zoom, the mapping from a quest's own coordinates to the screen, and
     * the rectangle currently visible. Shared across openings, like the selection above it.
     *
     * <h2>Where the four things that used to be here went</h2>
     *
     * <p>This was {@code float zoom}, {@code int panX}, {@code int panY} and the bound arithmetic
     * beside them — and every place that needed a coordinate wrote the conversion out itself: the two
     * methods that turn a quest into a screen position, the hit test, the pan on drag, the zoom about
     * the pointer, and {@link #centreCanvas}, which solved the same equation a second time from the
     * content's bounding box. Five copies of one transform, and the copy that decided what a click
     * selected was the one nothing could check.
     *
     * <p>It is {@link Viewport}'s now — the same object a scrolling list uses at a smaller range, which
     * is why it is in the kit rather than in {@code ui.graph}. Two things follow, and both are the
     * reason rather than a side effect: the zoom-about-the-pointer arithmetic is asserted against a
     * known transform in Armature's own tests instead of being judged in a screenshot, and there is
     * exactly one place in this repo that knows how to map content space onto screen space.
     *
     * <p><b>No content size is ever set on it, deliberately.</b> A canvas may be dragged off into
     * empty space: a player exploring a large questline should not be stopped at an invisible edge, and
     * a chapter's bounding box is not a wall. A view that wants a clamp asks for one by saying how much
     * content it has; this one never does, so the offset is whatever the drag put there.
     */
    private static final Viewport VIEW = Viewport.of(MIN_ZOOM, MAX_ZOOM);

    private static String pannedChapter;
    private static boolean centred;

    // --- per-open state ------------------------------------------------------

    private Overlay overlay = Overlay.NONE;

    /** The quest whose overlay is open, by id. */
    private String overlayQuest;

    /**
     * The party panel's laid-out rows, and the scroll view that places its controls from them.
     *
     * <h2>Why the layout is a field rather than a local of the widget pass</h2>
     *
     * <p>Because the Remove buttons and the action buttons are <b>widgets</b>, and a widget's position is
     * fixed at construction. So the layout that placed those buttons has to be the one the drawing reads,
     * or the row a member's name is written on and the button that removes them would come from two
     * computations that agree until one of them changed — and the panel scrolls, so the two also have to
     * agree about how far it has been scrolled.
     *
     * <p>The scroll is a {@link ScrollView} rather than a pair of origin fields, and that is the whole of
     * what closed the panel's stated limitation: it was "clipped rather than scrollable", which meant a
     * roster taller than its card simply lost its last rows — the drawing skipped them and the widgets
     * were never placed. A control now gets its rectangle from the same viewport the drawing reads, and
     * the ones outside it are hidden rather than misplaced, which is also what stops a row scrolled past
     * the card's edge from answering the pointer. See {@code PartyPanelLayout} for the layout and
     * {@code ScrollView} for the placement; between them there is one expression for where a row is.
     */
    private Layout partyLayout;
    private final ScrollView partyView = ScrollView.of(Viewport.fixed());

    /**
     * The card the panel was built into: what the widgets were placed inside, and what the drawing and
     * the click-outside test read.
     *
     * <h2>Why this is a field rather than a second computation</h2>
     *
     * <p>Because the card is now sized from the panel's own layout -- {@code modalFramed(layout.height(),
     * ...)} -- so "where is the card" and "where do the rows go" come from one pass. A drawing that
     * re-derived it would be the second arithmetic this whole round removed: the two agree until a row
     * is added, and the disagreement is a card that does not hold what is drawn in it.
     */
    private BookGeometry.Rect partyCard;

    /**
     * The panel's action rows: Create, an Accept per invitation, an Invite per online player, and the
     * mode row.
     *
     * <h2>Why kept, when the widgets already carry their own positions</h2>
     *
     * <p>Because each row's *label* is drawn by the panel rather than by a widget -- a row is a label
     * with an optional button beside it, and only the button is a control. So the drawing needs the
     * same rows the buttons were made from, and a second derivation of them is how a name ends up
     * beside somebody else's button.
     */
    private List<PartyPanelLayout.Action> partyRows = new ArrayList<>();

    /**
     * The footer's Disband control, and whether it has been pressed once and is waiting for a second.
     *
     * <h2>Why Disband asks and the rest do not</h2>
     *
     * <p>Because it is the only control on the panel whose mistake cannot be undone by pressing
     * something else: Remove takes one player out and Leave takes you out, and the party survives both.
     * Disband ends it for everybody in it, including the members who are offline and cannot be asked.
     *
     * <p>The control is kept rather than found by key because the label is changed <b>on it</b>. A
     * rebuild would be the other way to change a label, and it cannot be: a rebuild goes through
     * {@code init}, which clears this flag -- so arming and rebuilding would be the same press, and the
     * second press would send.
     */
    private ArmatureButton disbandButton;
    private boolean disbandArmed;

    /**
     * The member row's own metrics live in {@link PartyPanelLayout}, where the row's composition is.
     *
     * <p>They were six private constants here, which is where they were unreadable from: a drawing
     * metric in the one class in either mod that cannot be asked anything. See that class's note.
     */

    /**
     * The party strip's control, so the drawing can keep its label current.
     *
     * <p>Nulled in {@code init} with the list it comes from, exactly as {@link #closeButton} is: leaving
     * it set across a rebuild would have {@code drawPartyStrip} writing a message onto a control the
     * screen no longer owns.
     */
    private ArmatureButton partyButton;

    /**
     * The overlay body's scroll, as a scroll view rather than an int.
     *
     * <h2>What this replaced</h2>
     *
     * <p>An {@code int overlayScroll} that the wheel wrote <b>unclamped</b>, and that
     * {@link #drawOverlay} clamped much later against a height produced by a separate
     * {@code measureOverlay} pass. Three places holding one number — the writer, the clamp and the
     * scrollbar — which agreed only by hand, and only until somebody scrolled. A flick past the bottom
     * left the offset out of range until the next frame happened to correct it.
     *
     * <p>Here the clamp, the position and the thumb are one object's, and {@link #overlayLayout} is the
     * single call that says how much content there is, so the bar cannot claim a range the drawing does
     * not fill.
     *
     * <p>The viewport is fixed-scale: there is no canvas inside the overlay, so a zoom control here
     * would be a control for something that does not exist.
     *
     * <p>No widgets are registered in it. The overlay's two controls are placed by {@link BookGeometry}
     * from the card's own rectangles rather than by the scroll, so what this contributes is the scroll
     * range, the clamp and the scrollbar.
     */
    private final ScrollView overlayView = ScrollView.of(Viewport.fixed());

    /**
     * The chapter list, as a scroll view over {@link BookGeometry#sidebarViewport()}.
     *
     * <h2>Why the rows are widgets rather than drawn lines</h2>
     *
     * <p>Because they are the thing a click lands on, and a hand-drawn row cannot take focus, cannot be
     * narrated, and has no hover state unless this class reimplements one per row. Every one of those is
     * already solved by {@code AbstractWidget}, and the price of getting them is a class that moves the
     * widget when the content scrolls — which is {@link ScrollView}'s whole job.
     *
     * <p>The viewport is fixed-scale: a sidebar does not zoom, and a view that reported a zoom range of
     * one would be a view whose zoom controls are drawn, enabled, and do nothing.
     *
     * <p>The bar this view draws is also <b>draggable</b>, which the view owns rather than this screen:
     * {@link ScrollView#scrollbarHit} decides whether a press belongs to it and
     * {@link ScrollView#dragThumbTo} maps a pointer's y back onto a scroll offset. That mapping is the
     * inverse of the drawing's own formula, so it lives beside it — see that class's note.
     *
     * <p>It is <b>not</b> cleared on a resize, and that is deliberate. {@code init} runs on every resize
     * and on every rebuild, and the outline's expansion state — and so the player's collapses — lives in
     * {@link #sidebar}, which is keyed on the tree rather than on the window. The scroll offset lives in
     * this object's viewport, so it survives a resize too: a window dragged narrower while the list is
     * scrolled keeps its place, which is what every other list on any platform does.
     */
    private final ScrollView sidebarView = ScrollView.of(Viewport.fixed());

    /**
     * The sidebar's outline, and the tree revision it was built from.
     *
     * <h2>Why the outline is kept rather than rebuilt</h2>
     *
     * <p>Because it holds the player's collapsed groups. Rebuilding it from the server's data on every
     * <code>init</code> would re-seed it from the authored defaults, so a resize would silently reopen
     * every group the player had closed — and a rebuild happens on every window resize, every click that
     * selects a chapter, and every quest the screen opens.
     *
     * <p>The revision is what decides when to rebuild, and it is the counter
     * {@code ClientQuestCache.treeRevision()} exists to provide: it moves when a tree arrives and when a
     * cache is cleared, and <b>not</b> when a screen merely reads one. So a resize, a scroll, a toggle
     * and a redraw all leave the outline alone, and a reload — which is a tree arriving — builds a new
     * one. See that method's own note for why the unit is "a tree arrived" rather than "the tree
     * differs".
     *
     * <p>{@code -1} rather than {@code 0} for the initial value, because a revision is only ever
     * compared and a real one could legitimately be zero on the first tree. A sentinel that cannot be
     * mistaken for a real value is the difference between "definitely stale" and "probably stale".
     */
    private static SidebarLayout sidebar;
    private static long sidebarRevision = -1;

    private boolean dragging;
    private boolean pressMoved;
    private double pressX;
    private double pressY;

    /**
     * The content point the pan is holding, not the offset it started at.
     *
     * <p>In content coordinates because a drag says "I am holding this bit of the world", and a zoom
     * during the drag moves the world. The offset captured at press was the first version and it had a
     * visible fault: hold the canvas and turn the wheel, and each mouse move slid the canvas back to
     * where the drag began -- because the pan put back an offset that the zoom had deliberately
     * changed. See {@code Viewport.dragTo}, which is that arithmetic with a test.
     */
    private float panContentX;
    private float panContentY;

    /** The node under the pointer when the press began, if any. */
    private String pressedNode;

    /**
     * The editors this session has open, by chapter. Created on first use.
     *
     * <p>Null until the book is in a state where editing is possible at all — see {@link #editor()} — and
     * a field rather than a local because an editor holds unsaved work: a rebuild, a resize or a glance at
     * another chapter must not throw it away.
     */
    private EditorSession editors;

    /** Whether the tools panel is open. See {@link #buildToolsWidgets} and {@link #drawTools}. */
    private boolean toolsOpen;

    /** The colour the band is editing, or null. */
    private String toolsSelected;

    /** The panel's own one-line status, and whether it is bad news. */
    private String toolsFeedback;
    private boolean toolsFeedbackIsError;

    /** Whether the colour section is unfolded. Open by default: a folded section is one the author
     *  cannot see is there. */
    private boolean toolsColoursOpen = true;

    /** The panel's list: its rows are widgets, so they move when it scrolls. */
    private final dev.ellipog.armature.client.ui.kit.ScrollView toolsView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());

    private ToolsLayout.Frame toolsFrame;
    private Layout toolsLayout;
    private List<ToolsLayout.Action> toolsRows = List.of();

    /** The panel's own controls, so the chrome layer can draw them: the header's pair. */
    private ArmatureButton editButton;
    private ArmatureButton toolsButton;

    /** The node being dragged on the canvas, if developer mode is on and the press landed on one. */
    private String draggedNode;

    /** Where that node is now, in content coordinates, and where inside it the pointer grabbed it. */
    private float dragX;
    private float dragY;
    private float dragGrabX;
    private float dragGrabY;

    /**
     * This screen's own controls, in the order they were created.
     *
     * <p>Kept because {@code Screen.renderables} is private — established by the compiler after being
     * assumed otherwise. Keeping the list is the better arrangement anyway: a screen that knows which
     * widgets are its own can ask them questions the vanilla base class has no concept of, which is
     * what {@link #drawTooltips} does.
     */
    private final List<ArmatureButton> buttons = new ArrayList<>();

    /**
     * How many of {@link #buttons} belong to the book rather than to an open overlay.
     *
     * <h2>What this is for, and why an index rather than a rectangle</h2>
     *
     * <p>The book is drawn <b>behind</b> a modal rather than replaced by it, so its sidebar rows are
     * built and visible while one is open. Visible is right -- that is what "dont close whats behind
     * them" asked for -- but *interactive* is not, and the one place that leaked is
     * {@link #drawTooltips}: it walks every button and draws the hovered one's tooltip, so a pointer
     * over a row underneath the scrim would draw a chapter name on top of the party roster.
     *
     * <p>An index rather than a test on each button's position, and the distinction is the point: which
     * buttons belong to the overlay is a fact about how they were constructed, and a rectangle would be
     * a layout fact that happens to agree. {@code buildSidebarWidgets} records this before the overlay's
     * own controls are added, so the first N are always the book's.
     *
     * <p>Clipped input is not affected: {@code mouseClicked} returns early for an open overlay and never
     * reaches the sidebar, which is why this is needed for tooltips alone -- they are drawn from
     * {@code render}, which has no such early return.
     */
    private int bookButtonCount;

    /**
     * How many rosters had arrived when the party panel was last built.
     *
     * <h2>Why a counter, and why the panel needs one at all</h2>
     *
     * <p>Because a party's roster is <b>pushed while the panel describing it is open</b>.
     * {@code PartySyncPayload}'s own note names that as the case that stops the roster being
     * request-only: somebody accepts an invite or an officer removes somebody, and the person looking
     * at the panel is owed a redraw. The widgets are created in {@code init} and placed once, so a
     * message arriving in between changes {@link ClientPartyCache} and nothing else — the panel goes on
     * drawing the roster it was built with, which is a party that has since gained or lost a member.
     * Every row in it is still a real name, which is what makes it convincing.
     *
     * <p>Compared against a counter rather than against the two snapshots, and that is
     * {@code ClientQuestCache.treeRevision}'s argument read the other way: "a roster arrived" is a fact
     * about a message, while "the roster differs" is a judgement this screen would have to make with
     * its own answer for an identical re-send. {@code -1} rather than {@code 0} so the initial value
     * cannot be mistaken for a real roster that has been drawn.
     */
    /**
     * Whether this client has told Armature what the book's panels watch.
     *
     * <h2>Why the list is here, and why it is guarded</h2>
     *
     * <p>Because a panel that watches nothing is exactly the failure this replaces — the one that has to
     * be closed and reopened — and the sources are <b>this screen's own</b>: each is a cache it draws
     * from. Registering on the first {@code init} keeps the list beside the drawing that depends on it,
     * rather than in a loader's setup file, once per loader, which is where the third panel's entry gets
     * forgotten.
     *
     * <p>Guarded because {@code init} runs on every resize and after every automatic rebuild, and the
     * registry is a list: registering again on each pass would grow it with the number of times the
     * window was resized.
     *
     * <p>What each one buys: the <b>tree</b> is the sidebar's rows, so a reload rebuilds them; the
     * <b>progress</b> is what the rows say and whether a Claim button belongs on the screen, which is
     * the one part of progress that is a widget rather than something drawn; the <b>party</b> is the
     * roster's rows and the Remove buttons placed in them.
     */
    private static boolean watchingSources;

    private static void watchSources() {
        if (watchingSources) {
            return;
        }
        watchingSources = true;
        ArmatureLive.watch("tasked.tree", ClientQuestCache::treeRevision);
        ArmatureLive.watch("tasked.progress", ClientQuestCache::progressRevision);
        ArmatureLive.watch("tasked.party", ClientPartyCache::rosterRevision);
    }

    /**
     * Close, which is in {@link #buttons} but is not drawn by the widget pass.
     *
     * <h2>Why this is a field rather than one more entry in the list</h2>
     *
     * <p>Because Close is the only control that lives in the header, and the widget pass is clipped
     * from the sidebar's list top downwards — so that a scrolled row can never be drawn through the
     * title bar. That clip would swallow Close, which is why it is drawn by hand in the chrome layer
     * instead. The field is what lets that happen; see `buildHeaderChrome`.
     *
     * <p>It is still in {@link #buttons} as well, and that is deliberate rather than redundant:
     * {@code drawTooltips} walks that list, so a control missing from it would draw and respond and
     * simply have no tooltip.
     */
    private ArmatureButton closeButton;

    public QuestBookScreen() {
        super(Component.translatable("tasked.screen.quest_book.title"));
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    // Every position below now comes from BookGeometry, which has no Minecraft in it and so can be
    // tested. These stay as named methods because the drawing code reads better for them --
    // `canvasRight()` says more at a call site than `geometry().canvas().right()` -- and because
    // renaming them all would be a large diff for no behavioural gain. The *bodies* are what had to
    // change, and that is the fix: there is now exactly one expression per position, and the tests
    // assert the properties of it.

    private int panelWidth() {
        return geometry().panel().width();
    }

    private int panelHeight() {
        return geometry().panel().height();
    }

    private int panelLeft() {
        return geometry().panel().x();
    }

    private int panelTop() {
        return geometry().panel().y();
    }

    private int canvasLeft() {
        return geometry().canvas().x();
    }

    /**
     * The canvas' right edge, less whatever the tools panel is holding.
     *
     * <h2>Docked, and this is the whole of it</h2>
     *
     * <p>The panel does not float over the canvas; the canvas gives up the strip it sits in, and the
     * graph re-clamps into what is left. That is what the first version got wrong: it drew the panel on
     * top of the nodes, so the thing an author was looking at disappeared behind the tool for looking at
     * it. The cost is that opening the panel moves the graph -- which is what docking means, and it is
     * the half that does not surprise anybody.
     *
     * <p>Everything that reads the canvas reads this: the viewport (so panning and zooming clamp to the
     * narrow canvas), the hit test and the node drawing. One expression, so a node cannot be drawn in the
     * strip the panel occupies.
     */
    private int canvasRight() {
        return geometry().canvas().right() - toolsWidth();
    }

    /** How much of the canvas the tools panel takes, or nothing when it is shut. */
    private int toolsWidth() {
        return toolsOpen ? ToolsLayout.frame(geometry().canvas()).panel().width() + ToolsLayout.GAP : 0;
    }

    private int canvasTop() {
        return geometry().canvas().y();
    }

    private int canvasBottom() {
        return geometry().canvas().bottom();
    }

    private boolean inCanvas(double mouseX, double mouseY) {
        return geometry().canvas().contains(mouseX, mouseY);
    }

    // --- the shared positions. One expression each, used by drawing and by the controls ---

    /**
     * Where the header's right-hand text has to stop, so the quest count does not run under Close.
     *
     * <p>There is no footer to ask about any more. The four controls that were in it are three square
     * map buttons in the canvas's own corner and a close button in the header — see
     * {@link BookGeometry}'s class comment for why each moved, and what each replaced.
     */
    private int headerRightLimit() {
        return geometry().headerRightLimit();
    }

    /** The backing panel behind the three view buttons, drawn so they read as one cluster. */
    private BookGeometry.Rect viewControls() {
        return geometry().viewControls();
    }

    /** Where a chapter row starts, and so the top of the list. */
    private int chapterListTop() {
        return geometry().chapterListTop();
    }
    /**
     * The local player's id, or null when there is none.
     *
     * <p>A roster needs a <i>viewer</i>: it decides which row is yours and which rows carry a Remove
     * button. {@code minecraft.player} is null for a screen driven by a test's recording renderer, and
     * null is passed through rather than substituted -- {@code PartyRoster} reads "no viewer" as "may
     * remove nobody", and a made-up id would offer somebody a button the server refuses.
     */
    private UUID viewerId() {
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
    }

    /** The roster this client was last sent, as seen by the player looking at it. */
    private PartyRoster partyRoster() {
        return ClientPartyCache.roster(viewerId());
    }

    /**
     * The region the sidebar's rows scroll within, with its bounds taken from the current window.
     *
     * <p>Re-applied on every use, like {@link #viewport()} and for the same reason: a resize is announced
     * to {@code init} and to nothing else, and {@link #render} and {@code mouseClicked} are not in an
     * order this class could rely on — so a bound rectangle stored once would be describing the previous
     * window for an unknown number of frames.
     *
     * <h2>This is what replaced {@code chapterRows()}</h2>
     *
     * <p>That answered "how many rows fit above the bottom", which is the question a screen asks when it
     * has to decide where row <i>n</i> goes. The rows are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack}
     * inside a {@link ScrollView} now, so a count is no longer part of the contract and the arithmetic
     * that produced it has no owner. What the framing still owes the list is the <b>region</b> — a list
     * cannot know where its own edge is — and that is what this returns.
     *
     * <p>Both the scrolling and the culling read it, so it is the one rectangle that decides what is on
     * screen: {@link ScrollView#apply} places against it and hides what falls outside, and
     * {@link #mouseScrolled} routes the wheel by it.
     */
    private Viewport sidebarViewport() {
        BookGeometry.Rect rect = geometry().sidebarViewport();
        return sidebarView.viewport().bounds(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /**
     * The sidebar's scrollbar, when there is more to scroll than fits.
     *
     * <h2>The colours are the theme's own tokens now, and they were not</h2>
     *
     * <p>This passed {@code ArmatureTheme.panelEdge()} and {@code ArmatureTheme.available()} — a border
     * colour and the <b>bright blue that means "this quest can be started"</b>. The bar came out as a
     * saturated strip down the side of a dark panel, and the report was immediate: <i>"make it not such
     * an obnoxious colour by default"</i>.
     *
     * <p>The theme had {@code scrollTrack} and {@code scrollThumb} the whole time, and their own
     * javadoc says why this happened: <i>"a colour borrowed for a job it was not chosen for is a colour
     * that will be wrong for one of the two jobs."</i> That is exactly what {@code available} was —
     * chosen to read as a ring around a node, used as a scrollbar grip.
     *
     * <p>The replacement pair is a token per theme, so a light theme gets a bar that reads on light.
     * That is the whole point of the token, and it was being bypassed.
     */
    private void drawSidebarScrollbar(GuiRenderer r) {
        // Re-bound here, not assumed. The scrollbar's geometry comes from the viewport, so the viewport
        // has to describe the current window before it is drawn -- and this is the first thing in the
        // frame that needs it, because the drawing itself happens before the widget pass.
        sidebarViewport();
        sidebarView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    // ------------------------------------------------------------------
    // The sidebar's outline, and what a click on a row does
    // ------------------------------------------------------------------

    /**
     * The sidebar's outline: the group headings, the chapters under them, and which the player has open.
     *
     * <h2>Rebuilt when a tree arrives, and never when a screen merely reads one</h2>
     *
     * <p>Keyed on {@link ClientQuestCache#treeRevision()}, which moves when a tree arrives and when the
     * cache is cleared, and not when a screen reads one. So a resize, a scroll, a toggle and a redraw all
     * find the same outline — with the player's collapses still in it — and a reload builds a new one
     * whose groups are open or closed as their files say.
     *
     * <p>That is the whole reason the revision exists rather than this comparing the cache's entries.
     * Comparing contents would answer the same question a second way, and the two answers would disagree
     * about one real case: a reload that re-sent an identical tree. "A tree arrived" is a fact; "the tree
     * is different" is a judgement, and it is the wrong one for the authored defaults to hang on.
     *
     * <p>The outline is <b>static</b>, unlike the scroll view beside it, and the difference is what each
     * describes. A scroll view describes a window — it holds widgets, and a widget belongs to one screen.
     * An outline describes a questline: the same tree drawn twice should be one outline, or closing and
     * reopening the book would discard every collapse the player had made. {@link #forgetViewState()}
     * clears it on a disconnect, alongside the selection and the pan, because the collapses are a fact
     * about a server this client is no longer connected to.
     */
    private static SidebarLayout sidebar() {
        long revision = ClientQuestCache.treeRevision();
        if (sidebar == null || sidebarRevision != revision) {
            sidebar = buildSidebar();
            sidebarRevision = revision;
        }
        return sidebar;
    }

    /**
     * Builds an outline from what the server sent.
     *
     * <h2>Two lists from two places, and the asymmetry is real</h2>
     *
     * <p>The headings come from {@link ClientQuestCache#groups()}, which the server sends explicitly — so
     * a group with no chapters is still a heading, and the rows say so. The chapters are <b>derived from
     * the quest entries</b>, because there is no chapter list on the wire: a chapter reaches the client
     * only as a property of the quests in it.
     *
     * <p>That has one consequence worth stating rather than discovering, and it is a consequence of the
     * wire's shape rather than of this method: <b>a chapter with no quests is invisible here.</b> It
     * cannot be otherwise, because nothing was sent about it. It is the same limitation the flat chapter
     * list had before the sidebar existed, so nothing has regressed — but a sidebar is exactly where
     * somebody would expect to see an empty chapter, which makes it worth writing down.
     *
     * <p>Deduped by chapter id while keeping the order of first sighting, so a chapter's row sits where
     * its first quest is and appears once however many quests it has.
     *
     * <p>A server older than groups sends no headings at all, so every chapter arrives with an empty
     * group id — which {@link SidebarLayout} reads as "no group" and adds as a root. That draws exactly
     * the flat chapter list this screen had before, which is why there is no branch here for the old
     * case and nothing to notice at.
     */
    private static SidebarLayout buildSidebar() {
        List<SidebarLayout.Group> groups = new ArrayList<>();
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            groups.add(new SidebarLayout.Group(group.id(), group.title(), group.collapsedByDefault()));
        }

        Map<String, SidebarLayout.ChapterRow> chapters = new LinkedHashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            chapters.putIfAbsent(entry.chapterId(), new SidebarLayout.ChapterRow(
                    entry.chapterId(), entry.chapterTitle(), entry.chapterGroupId()));
        }

        return SidebarLayout.of(groups, List.copyOf(chapters.values()));
    }

    /**
     * What a click on a sidebar row does.
     *
     * <p>A heading is toggled and a chapter is selected, and the two are told apart by the row's
     * <b>key</b> rather than by a flag on the button — see {@link SidebarLayout} on why a group and a
     * chapter share one namespace and are kept apart by a prefix.
     *
     * <p>A toggle that changed nothing does <b>not</b> rebuild, and that is the reason
     * {@link SidebarLayout#toggle} answers a boolean rather than being void. A heading with no chapters
     * under it is drawn without a chevron and is still clickable — the whole row is the target, which is
     * the point of chevroning the label rather than adding a second control beside it — so pressing it
     * is a real thing a player can do. Rebuilding every button for a press that meant nothing costs a
     * frame and can lose focus.
     */
    private void pressSidebarRow(String key) {
        if (SidebarLayout.isGroupKey(key)) {
            if (sidebar().toggle(key)) {
                rebuildWidgets();
            }
            return;
        }

        selectedChapter = SidebarLayout.idOf(key);
        // The selection belongs to the chapter being left, so it closes. The rule is unchanged from the
        // flat list, and it is repeated here because the rows are built by different code now: leaving
        // it open would show a quest that is not on screen, with a Submit button, for a chapter you have
        // walked away from.
        selectedQuest = null;
        centred = false;
        rebuildWidgets();
    }

    /**
     * Scrolls the sidebar by a screen-space delta, snapped so a row is never left half past the top.
     *
     * <h2>Why the snapping is here rather than in the viewport</h2>
     *
     * <p>Because it is this list's decision and not the kit's. A viewport scrolls by pixels, because that
     * is what a pan-and-zoom canvas wants; a list of rows wants to stop with a row's top edge against the
     * list's top edge, and a list left a third of a row out of position reads as a drawing fault rather
     * than as a scroll. So every offset this produces is a whole multiple of
     * {@link SidebarLayout#pitch()}.
     *
     * <p>That pitch is derived from the same two numbers the rows are spaced with, which is the point:
     * a scroll rate written out at the call site drifts against the spacing by a couple of pixels a
     * notch, and the symptom arrives slowly — a row that ends up half under the header after a while,
     * with nothing in the code looking wrong.
     *
     * <p>A delta too small to move a whole row still moves one, in the direction asked for. The
     * alternative is a trackpad whose small deltas each round back to where they started, which reads as
     * the wheel being broken; one row per notch is what every other list does.
     */
    private void scrollSidebar(int dy) {
        if (sidebar == null || dy == 0) {
            return;
        }

        int pitch = SidebarLayout.pitch();
        int current = sidebarView.viewport().scrollY();
        int wanted = current + dy;
        int snapped = Math.round(wanted / (float) pitch) * pitch;
        if (snapped == current) {
            snapped = current + (dy > 0 ? pitch : -pitch);
        }
        sidebarView.scrollTo(snapped);
    }

    /** The full-screen overlay's bounds. */
    private int overlayLeft() {
        return geometry().overlay().x();
    }

    private int overlayTop() {
        return geometry().overlay().y();
    }

    private int overlayWidth() {
        return geometry().overlay().width();
    }

    private int overlayHeight() {
        return geometry().overlay().height();
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    private static Map<String, String> chapters() {
        Map<String, String> chapters = new LinkedHashMap<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            chapters.putIfAbsent(entry.chapterId(), entry.chapterTitle());
        }
        return chapters;
    }

    private static String effectiveChapter() {
        Map<String, String> chapters = chapters();
        if (chapters.isEmpty()) {
            return null;
        }
        if (selectedChapter != null && chapters.containsKey(selectedChapter)) {
            return selectedChapter;
        }
        return chapters.keySet().iterator().next();
    }

    private static List<ClientQuestCache.Entry> questsIn(String chapterId) {
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.chapterId().equals(chapterId))
                // An invisible quest stays hidden until it is done. The flag travels with the tree
                // rather than the server withholding the quest, so a quest that becomes visible
                // because it was completed needs no second sync.
                .filter(entry -> !entry.invisible()
                        || ClientQuestCache.stateOf(entry.id()) == QuestState.COMPLETED)
                .toList();
    }

    private ClientQuestCache.Entry entryFor(String questId) {
        if (questId == null) {
            return null;
        }
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.id().equals(questId))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Canvas coordinates
    // ------------------------------------------------------------------

    /**
     * The canvas transform, with its bounds taken from the current window.
     *
     * <p>Re-applied on every use rather than cached, so the transform cannot outlive the canvas it
     * describes. A resize is announced to {@code init} and to nothing else, and {@link #render} and
     * {@code mouseClicked} are not in an order this class could rely on — so a bound rectangle stored
     * once would be describing the previous window for an unknown number of frames.
     */
    private Viewport viewport() {
        return VIEW.bounds(canvasLeft(), canvasTop(), canvasRight() - canvasLeft(),
                canvasBottom() - canvasTop());
    }

    /**
     * The overlay's body rectangle, as a viewport.
     *
     * <p>Re-applied on every use, like {@link #viewport()} and for the same reason. Its offset is the
     * scroll, its content height is whatever the last {@link #overlayLayout} said, and it is the one
     * rectangle the clip, the scroll clamp and the scrollbar all read — which is the point, because
     * they used to be three.
     */
    private Viewport overlayBody() {
        return overlayView.viewport().bounds(
                overlayLeft() + BODY_INSET,
                overlayTop() + BODY_TOP,
                overlayWidth() - BODY_INSET * 2,
                overlayHeight() - BODY_TOP - BODY_BOTTOM);
    }

    /**
     * The party card's body rectangle, as a viewport: the one rectangle the clip, the scroll clamp and
     * the scrollbar all read.
     *
     * <p>Re-applied on every use, like {@link #overlayBody()} and for the same reason — a resize gives a
     * new card, and bounds from the previous window would clamp a scroll against a rectangle that is no
     * longer on screen. Empty rather than absent for a card that has not been built: the input handlers
     * read this too, and a click before the first frame is a click on a panel that does not exist yet.
     */
    private Viewport partyBody() {
        if (partyCard == null) {
            return partyView.viewport().bounds(0, 0, 0, 0);
        }
        BookGeometry.Rect body = BookGeometry.modalBody(partyCard);
        return partyView.viewport().bounds(body.x(), body.y(), body.width(), body.height());
    }

    private int nodeSize(ClientQuestCache.Entry entry) {
        int base = Mth.clamp(entry.size(), 26, 48);
        return Math.max(12, Math.round(base * viewport().scale()));
    }

    private int nodeScreenX(ClientQuestCache.Entry entry) {
        return viewport().screenX(nodeX(entry));
    }

    private int nodeScreenY(ClientQuestCache.Entry entry) {
        return viewport().screenY(nodeY(entry));
    }

    /**
     * Where a node's x is, for the drawing and for the hit test.
     *
     * <h2>Three answers, in this order</h2>
     *
     * <p>The node being dragged right now; else a position the editor has changed but the server has not
     * sent back yet; else what the server sent. The first two exist because the canvas draws the
     * <i>server's</i> tree and the editor writes <i>files</i>: between a drag and the reload that follows
     * it, the server's answer is stale, and a canvas that used it would snap the node back under the
     * pointer — which reads as a drag that did nothing.
     *
     * <p>One method, used by the drawing, the hit test and the dependency lines, because the alternative
     * is three places that each have to remember the same exception. A node dragged with its line left
     * behind is the picture of what that costs.
     */
    private float nodeX(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragX;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedX(entry.id()) : entry.x();
    }

    /** The same, for y. See {@link #nodeX}. */
    private float nodeY(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragY;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedY(entry.id()) : entry.y();
    }

    /**
     * The editor for the chapter on screen, or null.
     *
     * <h2>Two conditions, and both are about whether an edit could work at all</h2>
     *
     * <p><b>Developer mode</b>, because this is a tool. And <b>a singleplayer host</b>, because the quest
     * files are on the server's disk: a client joined to somebody else's server can read its own
     * {@code config/tasked/quests}, which is not the questline it is looking at — an editor that wrote
     * there would appear to work and change nothing anybody would ever see. So it refuses, and the
     * developer screen says why rather than leaving a dead key.
     *
     * <p>Null is the answer for both, and every caller treats it as "no editing here" rather than as an
     * error: this is the state of every player who is not a pack author.
     */
    private QuestEditor editor() {
        if (minecraft == null || !DevMode.on() || !minecraft.hasSingleplayerServer()) {
            return null;
        }
        if (editors == null) {
            editors = new EditorSession(EditorSession.root());
        }
        return editors.editor(effectiveChapter());
    }

    /**
     * Centres the content the first time a chapter is shown, and on a reset.
     *
     * <p>Computed from the content's own bounding box rather than from zero, so a questline authored
     * at negative coordinates — which the editor will produce, since it grows in every direction —
     * still lands in the middle of the canvas instead of off the edge.
     */
    private void centreCanvas() {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return;
        }
        if (centred && chapter.equals(pannedChapter)) {
            return;
        }

        List<ClientQuestCache.Entry> quests = questsIn(chapter);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (ClientQuestCache.Entry entry : quests) {
            minX = Math.min(minX, entry.x());
            maxX = Math.max(maxX, entry.x() + 48);
            minY = Math.min(minY, entry.y());
            maxY = Math.max(maxY, entry.y() + 48);
        }
        if (quests.isEmpty()) {
            minX = maxX = minY = maxY = 0;
        }

        // Centred in the view port rather than in the canvas: the canvas minus the floating strip. The
        // strip covers nothing, at the cost of a chapter looking slightly high when nothing is selected
        // -- which is the right trade against the alternative, where the bottom row of a chapter slides
        // under the bar the instant a player clicks a node, because clicking is what makes it appear.
        // Centred in the canvas, which is the whole area below the header. There used to be a summary
        // strip floating over its bottom, and content was centred in the canvas minus that strip so the
        // bar could not cover the last row of a chapter -- but the strip is gone (clicking a node opens
        // the quest), so there is nothing on the canvas for content to be kept clear of.
        // Given the bounding box rather than a size and an origin, which is what makes the
        // negative-coordinate case above fall out instead of needing the handling it used to get. No
        // clamp is applied, deliberately: this is the call that defines where a canvas starts, so
        // applying one would move content the caller had just positioned.
        viewport().centreOn(minX, minY, maxX, maxY);
        pannedChapter = chapter;
        centred = true;
    }

    /**
     * Applies a new zoom while keeping the world point under {@code (mouseX, mouseY)} fixed.
     *
     * <p>The arithmetic is the kit's, and it is asserted there rather than here: the content point
     * under the pointer is the same point after the zoom, the limits clamp, and a zoom already at a
     * limit does not drift. This method exists only to name the canvas for it — which is the whole
     * argument for the transform living in {@link Viewport}, because in a {@code Screen} that
     * calculation could only ever be judged by eye.
     */
    private void zoomAt(double mouseX, double mouseY, float factor) {
        viewport().zoomAt(mouseX, mouseY, factor);
    }

    /** Zooms about the view port's centre, for the buttons, which have no pointer position. */
    private void zoomCentre(float factor) {
        viewport().zoomAboutCentre(factor);
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    /**
     * Creates a control, adds it to the screen, and remembers it.
     *
     * <p>One method rather than {@code addRenderableWidget(new ArmatureButton(...))} at each site,
     * because forgetting the remembering half is silent: the control draws and responds and simply has
     * no tooltip. Which is the sort of bug that survives a lot of testing.
     */
    private ArmatureButton control(int x, int y, int w, int h, Component label, Runnable onPress) {
        ArmatureButton button = new ArmatureButton(x, y, w, h, label, onPress);
        buttons.add(button);
        return addRenderableWidget(button);
    }

    /**
     * The same, from a rectangle {@link BookGeometry} produced.
     *
     * <p>This overload is what ties the screen to the test. {@code BookGeometryTest} can only assert
     * about rectangles it can be given, so the screen has to *create its controls from* those same
     * rectangles rather than from numbers written beside them. Otherwise the test would verify a
     * parallel description of the layout while the real one drifted — passing while the screen
     * overlapped, which is worse than having no test, because it would have been believed.
     *
     * <p>{@code null} is tolerated rather than asserted: a rectangle can legitimately be absent (no
     * quest selected, so no Open button) and {@code controls()} says so by omitting the key. A control
     * is skipped, not drawn at 0,0.
     */
    private ArmatureButton control(BookGeometry.Rect rect, Component label, Runnable onPress) {
        if (rect == null) {
            return null;
        }
        return control(rect.x(), rect.y(), rect.width(), rect.height(), label, onPress);
    }

    /**
     * Creates a control in the header: added for <b>input</b>, drawn by hand instead.
     *
     * <h2>Why a second registration path exists at all</h2>
     *
     * <p>{@code Screen} keeps two lists, and until now nothing here cared. {@code addRenderableWidget}
     * puts a widget in both: {@code children} for input — clicks, hover, keyboard, narration — and
     * {@code renderables} for the base class's single drawing pass. {@code addWidget} puts it in
     * {@code children} only, which is exactly the arrangement a control in the header needs.
     *
     * <p>The reason is the clip. The widget pass is now clipped to start at the sidebar's list top, so
     * that a scrolled row cannot be drawn through the title bar — see {@code render}. Close sits above
     * that line, so the base pass would never draw it. Registering it here keeps every part of it that
     * is about <i>behaviour</i> with the base class, and moves only the drawing to the chrome layer,
     * which is also where a close button belongs: above the canvas, at the raised Z.
     *
     * <p>The alternative was to duplicate press and hover handling for one button, which would be a
     * second implementation of {@code AbstractWidget}'s own logic — the class of mistake this screen
     * has already been bitten by once, when the preview carried its own copy of the control styles.
     *
     * <p>Remembered in {@link #buttons} as well, so tooltips still find it.
     */
    /**
     * The party panel's controls: a Remove button per member the roster permits, plus Leave, Disband and
     * Back.
     *
     * <h2>What is deliberately not a widget, and why</h2>
     *
     * <p>The member <b>rows</b>. A press on a member would either do nothing or remove somebody, and a
     * control that removes a player on one click with no confirmation is not something to add merely
     * because a row is clickable. So the rows are drawn and the Remove buttons are controls, which is
     * the shape the roster itself describes -- {@code PartyRoster.removeSlot} returns null for a member
     * the viewer may not remove, and this makes a widget only where a slot exists.
     *
     * <h2>Why the member geometry is not from BookGeometry</h2>
     *
     * <p>Because a roster's rows are not a fixed layout: how many there are depends on who is in the
     * party. {@code BookGeometry} holds what is true of every window; this is what is true of one roster
     * in one window, and {@code PartyPanelLayout} -- Armature's, and game-free -- produces it. The
     * footer's own controls <i>are</i> fixed, so those do come from the geometry map, as every other
     * fixed control does.
     *
     * <h2>A limit, stated rather than implied</h2>
     *
     * <p>A roster taller than the card is <b>clipped rather than scrollable</b>, and the drawing is what
     * enforces it -- it skips a row that would fall outside the body rather than drawing it over the
     * header. That is a real limitation and an acceptable one: a party of more than six or seven is not
     * the case this is for, and the alternative is a second {@code ScrollView} that moves these widgets
     * the way the sidebar's does. Named here so whoever raises the limit knows what they are raising.
     */
    /**
     * The party button's tooltip: the roster, in words.
     *
     * <h2>Why the state lives here and not on the label</h2>
     *
     * <p>A label is fixed at construction and a tooltip is read per frame, and the roster is the thing
     * that changes. So the button says "Party", true whether you are in one or not, and the answer to
     * which party is one hover away. That also keeps the button's width a property of its label rather
     * than of the longest possible roster, which is what made the hand-written widths in this file wrong
     * in the first place.
     *
     * <p>Three states, and the middle one is why {@code isReal} exists rather than a member count: alone
     * in a party of one is not the same as having no party, and the two have the same number of members.
     */
    private List<Component> partyTooltip() {
        PartyRoster roster = partyRoster();

        if (!roster.isReal()) {
            return List.of(Component.literal("No party"),
                    Component.literal("Click to see how to make one"));
        }

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(roster.memberCount() == 1
                ? "Your party (just you)"
                : "Your party (" + roster.memberCount() + ")"));
        for (PartyRoster.Member member : roster.members()) {
            lines.add(Component.literal("  " + member.label() + " - " + member.roleLabel()));
        }
        return List.copyOf(lines);
    }

    /**
     * The rows the panel shows, as the panel's own class decides them.
     *
     * <p>The rule moved to {@link PartyPanelLayout#actions}, and it moved because a screen cannot be
     * asked anything: while it lived here, the only way to find out what a party of three with two
     * players online offers was to open the book and look. What stays is the one thing this class knows
     * and that method cannot -- who is looking.
     */
    private List<PartyPanelLayout.Action> partyRows(PartyRoster roster, PartySnapshot snapshot) {
        String self = minecraft == null || minecraft.player == null
                ? "" : minecraft.player.getScoreboardName();
        return PartyPanelLayout.actions(roster, snapshot, self);
    }

    private void buildPartyWidgets() {
        PartyRoster roster = partyRoster();
        List<PartyPanelLayout.Action> wanted = partyRows(roster, ClientPartyCache.snapshot());

        // The width first, with the height unknown. `modalFramed`'s width does not depend on its height
        // -- asserted in `BookGeometryTest` -- and that is what lets a card be sized from a layout which
        // needs the card's width to be built. Both numbers are then the layout's, and the body is the
        // card's own inset rather than a margin written here.
        int bodyWidth = Math.max(0, geometry().modalFramed(0, BookGeometry.PARTY_MODAL_WIDTH).width()
                - BookGeometry.MODAL_INSET * 2);

        // One layout for the whole panel -- title, roster, actions -- and it is what the card is sized
        // from. The measure is never consulted: every element `PartyPanelLayout` contributes is a `row`,
        // whose height is declared rather than wrapped, and `SidebarLayout` uses the same stand-in for
        // the same reason.
        partyLayout = PartyPanelLayout.build(roster, wanted, bodyWidth, Measure.monospace(6, 9));

        BookGeometry.Rect card = geometry().modalFramed(partyLayout.height(),
                BookGeometry.PARTY_MODAL_WIDTH);
        partyCard = card;
        partyRows = wanted;

        // The scroll view is emptied and refilled per build, and its bounds are set from the card
        // before anything is registered -- `apply` clamps the offset against them, so registering
        // first would place the rows against the previous window's body on a resize.
        partyView.clear();
        Viewport body = partyBody();

        Map<String, BookGeometry.Rect> footer =
                geometry().modalControls(card, partyActionCount(roster), true);

        // The Remove buttons, one per member the roster permits. The permission is asked once, at the
        // moment the widget is made, and the button's rectangle is a *strip* of its row -- so the
        // derivation travels to the scroll view with the registration rather than being computed here.
        // A widget created at 0,0 with no size is the sidebar's own shape (`buildSidebarWidgets`):
        // `apply` sets x, y, width and height from the slot, and a button created at its final size
        // would be a second description of where a row goes.
        for (PartyRoster.Member member : roster.members()) {
            if (!member.canRemove()) {
                continue;
            }
            ArmatureButton remove = control(0, 0, 0, 0,
                    Component.translatable("tasked.screen.party.remove"),
                    () -> runPartyCommand("/tasked party kick " + member.name()));
            if (remove != null) {
                remove.tooltip(List.of(
                        Component.literal("Remove " + member.name() + " from the party"),
                        Component.literal("They keep their own progress, as always")));
                partyView.put(member.key(), remove, row -> PartyRoster.removeSlot(member, row));
            }
        }

        // The action rows, placed by the same layout their labels are drawn from -- so a row's label and
        // its button cannot come from two computations that agree until one of them changes.
        for (PartyPanelLayout.Action action : wanted) {
            if (!action.hasButton()) {
                continue;
            }
            ArmatureButton button = control(0, 0, 0, 0,
                    Component.literal(action.buttonLabel()),
                    () -> runPartyCommand(action.command()));
            if (button != null) {
                button.tooltip(List.of(Component.literal(action.buttonLabel() + ": " + action.label()),
                        Component.literal(action.command())));
                partyView.put(action.key(), button, PartyPanelLayout::buttonStrip);
            }
        }

        // One call that sets the scroll range from the layout's height, places every control at the
        // rectangle its own derivation gave it, and hides the ones outside the card. The drawing re-runs
        // it each frame, so a scroll moves the widgets with the rows they belong to.
        partyView.apply(partyLayout, body.viewWidth());

        ArmatureButton leave = control(footer.get("leave"),
                Component.translatable("tasked.screen.party.leave"),
                () -> runPartyCommand("/tasked party leave"));
        if (leave != null) {
            leave.tooltip(List.of(Component.literal("Leave the party"),
                    Component.literal("Your own progress is never touched by it")));
        }

        ArmatureButton disband = control(footer.get("disband"),
                Component.translatable("tasked.screen.party.disband"),
                this::pressDisband);
        if (disband != null) {
            disband.tooltip(List.of(Component.literal("Dissolve the party"),
                    Component.literal("Only the owner may do this")));
            disbandButton = disband;
        }

        control(footer.get("back"), Component.translatable("tasked.screen.party.back"),
                this::closeOverlay);
    }

    /**
     * The tools panel's controls: its switches, its rows, its channel steppers and its two actions.
     *
     * <h2>What is a widget and what is drawn</h2>
     *
     * <p>A row is a widget when pressing it does something -- a theme, a colour, a fold. The colour a row
     * *shows* is drawn by {@link ToolsPanel}, because a swatch is a rectangle and no widget draws those;
     * the rows are flat, so the two do not fight over the same pixels. That is the same split the
     * developer screen learned the hard way, one screen earlier.
     *
     * <p>The panel's geometry is {@link ToolsLayout}'s, and the widgets are placed from its layout by the
     * scroll view -- so a row added there appears here, and the two cannot disagree about where a row is.
     */
    private void buildToolsWidgets() {
        toolsFrame = ToolsLayout.frame(geometry().canvas());
        toolsRows = ToolsLayout.rows(DevMode.on(), Appearance.motion(), toolsColoursOpen);
        toolsLayout = ToolsLayout.build(toolsRows, toolsFrame.list().width(), Measure.monospace(6, 9));

        toolsView.clear();
        toolsView.viewport().bounds(toolsFrame.list().x(), toolsFrame.list().y(),
                toolsFrame.list().width(), toolsFrame.list().height());

        for (ToolsLayout.Action row : toolsRows) {
            if (row.hasButton()) {
                ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.buttonLabel()),
                        () -> pressToolsSwitch(row.key()));
                toolsView.put(row.key(), button, ToolsLayout::strip);
            }
            else if (row.isControl()) {
                ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                        () -> pressToolsRow(row.key()));
                button.alignLeft(true).flat(true);
                toolsView.put(row.key(), button);
            }
            else if (row.key().equals(ToolsLayout.COLOUR_SECTION)) {
                // A section's heading is the only heading that is pressable: it folds. A group's name
                // inside the colours is drawn and does nothing, because a row that selects nothing is a
                // row that lies.
                ArmatureButton button = control(0, 0, 0, 0, Component.literal(""), () -> fold(row.key()));
                button.flat(true);
                toolsView.put(row.key(), button);
            }
        }

        Map<String, dev.ellipog.armature.client.ui.kit.Slot> beats = ToolsLayout.beats(toolsFrame.channels());
        for (String channel : ToolsLayout.CHANNELS) {
            for (String way : List.of("down", "up")) {
                var slot = beats.get(way + ":" + channel);
                int step = way.equals("down") ? -8 : 8;
                ArmatureButton button = control(slot.x(), slot.y(), slot.width(), slot.height(),
                        // `-` and `+`, which this UI already uses and the font certainly has: the
                        // first version's triangles are not in Minecraft's default font and drew as
                        // missing-glyph boxes.
                        Component.literal(way.equals("down") ? "\u2212" : "+"),
                        () -> nudgeChannel(channel, step));
                button.textColour(ArmatureTheme.body());
            }
        }

        control(ToolsLayout.revert(toolsFrame.actions()),
                Component.translatable("tasked.dev.reset"), this::revertColour);
        control(ToolsLayout.save(toolsFrame.actions()),
                Component.translatable("tasked.dev.save"), this::saveTheme);

        toolsView.apply(toolsLayout, toolsFrame.list().width());
    }

    /** One of the two switches. */
    private void pressToolsSwitch(String key) {
        if (key.equals(ToolsLayout.EDIT)) {
            setEditing(!DevMode.on());
        }
        else if (key.equals(ToolsLayout.MOTION)) {
            Appearance.setMotion(!Appearance.motion());
            status(Appearance.motion() ? "Motion on" : "Motion off", false);
            rebuildWidgets();
        }
    }

    /** A theme row, or a colour row. */
    private void pressToolsRow(String key) {
        String token = ToolsLayout.tokenId(key);
        if (token != null) {
            toolsSelected = token.equals(toolsSelected) ? null : token;
            rebuildWidgets();
        }
    }

    /** The section's heading: folds or unfolds it. */
    private void fold(String key) {
        if (key.equals(ToolsLayout.COLOUR_SECTION)) {
            toolsColoursOpen = !toolsColoursOpen;
            rebuildWidgets();
        }
    }

    /** One channel stepper: eight steps of one channel of the selected colour. */
    private void nudgeChannel(String channel, int step) {
        String token = toolsSelected;
        if (token == null) {
            return;
        }
        int argb = Appearance.main().colour(token);
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        int value = net.minecraft.util.Mth.clamp(((argb >>> shift) & 0xFF) + step, 0, 255);
        Appearance.setCustom(token, (argb & ~(0xFF << shift)) | (value << shift));
        rebuildWidgets();
    }

    /** Undoes the edits to the selected colour alone. */
    private void revertColour() {
        if (toolsSelected == null) {
            status("Press a colour first", true);
            rebuildWidgets();
            return;
        }
        Appearance.clearCustom(toolsSelected);
        status("Reverted " + labelOfToken(toolsSelected), false);
        rebuildWidgets();
    }

    /**
     * Writes the edits out as a theme and switches to it.
     *
     * <p>No name is passed: {@code saveAsTheme} derives one from the theme being edited, which is why a
     * save never has to be refused for want of a text field -- and the button's tooltip names the file
     * before it is written.
     */
    private void saveTheme() {
        String saved = Appearance.saveAsTheme(null);
        status(saved == null ? "The theme could not be written - see the log" : "Saved as " + saved,
                saved == null);
        rebuildWidgets();
    }

    private static String labelOfToken(String token) {
        var found = dev.ellipog.armature.client.ui.ThemeToken.byId(token);
        return found == null ? token : found.label();
    }

    /** The panel's status line, and the chat, because a panel can be covered by the inventory. */
    private void status(String message, boolean error) {
        toolsFeedback = message;
        toolsFeedbackIsError = error;
        if (error) {
            say("\u00a7c" + message);
        }
    }

    /** Where the pointer is, relative to the panel. Null when the panel is shut. */
    private boolean inTools(double mouseX, double mouseY) {
        if (!toolsOpen || toolsFrame == null) {
            return false;
        }
        return toolsFrame.panel().contains(mouseX, mouseY);
    }

        /**
     * Disband, which takes two presses. See {@link #disbandArmed} for why it is the only one that asks.
     *
     * <p>The label is changed on the control rather than by rebuilding the panel, and that is not a
     * shortcut: a rebuild goes through {@code init}, which clears the flag, so arming and rebuilding
     * cannot be the same press.
     */
    private void pressDisband() {
        if (disbandArmed) {
            disarmDisband();
            runPartyCommand("/tasked party disband");
            return;
        }
        disbandArmed = true;
        if (disbandButton != null) {
            disbandButton.setMessage(Component.translatable("tasked.screen.party.confirm"));
            disbandButton.tooltip(List.of(Component.literal("Press again to dissolve the party"),
                    Component.literal("Everybody keeps the progress they earned")));
        }
    }

    /** Undoes an armed Disband. Every other press on the panel does this, and so does a rebuild. */
    private void disarmDisband() {
        disbandArmed = false;
        if (disbandButton != null) {
            disbandButton.setMessage(Component.translatable("tasked.screen.party.disband"));
        }
    }

    /**
     * A panel row's key, at the position it is drawn and placed at.
     *
     * <h2>Why the viewport and not an origin</h2>
     *
     * <p>Because the panel scrolls, so "where does this row go" is the card's body origin <i>minus the
     * scroll</i>. Both halves are the viewport's, and the widget placement asks the same object the same
     * question — so a row's label, its hover wash and the button beside it cannot come from two
     * expressions that agree until the panel is scrolled.
     */
    private static Slot screenSlot(Viewport body, Slot slot) {
        return new Slot(slot.key(), body.screenX(slot.x()), body.screenY(slot.y()),
                slot.width(), slot.height());
    }

    /** How many of Leave and Disband the viewer may use, 0 to 2. What the footer is built for. */
    private static int partyActionCount(PartyRoster roster) {
        int count = 0;
        if (roster.canLeave()) {
            count++;
        }
        if (roster.canDisband()) {
            count++;
        }
        return count;
    }

    private void openPartyOverlay() {
        overlay = Overlay.PARTY;
        overlayQuest = null;
        // Opened at the top, and reset before the rebuild rather than after: the rebuild's own `apply`
        // clamps against the new content, and an offset left from a previous party would be clamped
        // into range rather than forgotten -- a panel that opened halfway down for no visible reason.
        partyView.scrollTo(0);
        rebuildWidgets();
    }

    /**
     * Runs a party command as this player.
     *
     * <h2>Why the panel's buttons are commands rather than a payload of their own</h2>
     *
     * <p>Because the command <b>is</b> the operation, and it is the server's. {@code /tasked party kick}
     * already checks party rank, refuses with a reason, and tells every client whose roster moved. A
     * payload doing the same three things would be a second implementation of all of it, and the two
     * would drift the first time one gained a check -- most likely the check, since a client-side "may
     * I" test is the one thing here that cannot be trusted.
     *
     * <p>So a press sends exactly what a typed command sends, through the connection the server already
     * validates. A modified client asking for an unpermitted kick gets the refusal the command would
     * have given it, and nothing changes.
     *
     * <h2>Why the panel stays open, and why the roster is not updated here</h2>
     *
     * <p>The panel used to close on every press, and that was the wrong shape for what these actions
     * are: inviting somebody, accepting an invite, changing your mind about a kick -- all of them mean
     * pressing more than one button, and a panel that shut after each press made every step cost a
     * reopen. The report was plain: <i>"stop making it close every time i do something, just auto
     * update"</i>.
     *
     * <p>So it stays open, and the redraw is not done here either. The server answers a party change by
     * pushing the new roster to everyone it concerns, the cache moves its revision when that arrives,
     * and the screen rebuilds itself -- see {@link ArmatureLive}, which is where that noticing lives now
     * that it is every panel's problem rather than this one's.
     *
     * <p>Updating the panel from the press would be the tempting alternative and the wrong one: it is a
     * second source of truth for one fact, and the one that is wrong whenever the server refuses. A
     * refused kick would be drawn as a successful one for as long as it took the real roster to land.
     */
    private void runPartyCommand(String command) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        // **No leading slash.** `ClientPacketListener.sendCommand` takes a bare command -- which is
        // why `ChatScreen` strips the slash before calling it -- and passing one is not harmless:
        // the server's dispatcher reads the first token as the command name, so `/tasked party create
        // X` was parsed as a command called `/tasked` and answered "Unknown or incomplete command".
        //
        // That was every button on this panel, because they all go through here. `sendCommand` rather
        // than `sendChat` for the other half of the same reason: no chat message is produced or
        // logged, because a control press is not something the player said.
        minecraft.getConnection().sendCommand(
                command.startsWith("/") ? command.substring(1) : command);
        // No close. The panel redraws from the roster the server sends back -- see the note above.
    }

    /**
     * Creates the sidebar's rows as widgets, over the current outline.
     *
     * <h2>The four steps, in this order, and why the order is this method's business</h2>
     *
     * <ol>
     *   <li>The outline is fetched — {@link #sidebar()} rebuilds it if a tree has arrived since the last
     *       build, which is the only thing that discards the player's collapses.</li>
     *   <li>The scroll view is cleared, so a row that no longer exists cannot linger.</li>
     *   <li>The viewport is <b>bound before anything is placed</b>. {@link ScrollView#apply} positions
     *       against the viewport, so it has to describe the current window first. This is the one place
     *       that ordering is this class's responsibility rather than a drawing pass's, because
     *       {@code init} runs before any frame has drawn.</li>
     *   <li>{@code apply} places every row from the layout and tells the viewport how tall the content
     *       is. Those are one call on purpose: the scrollbar's range and the row positions cannot
     *       disagree if they come from one computation.</li>
     * </ol>
     *
     * <h2>Why the rows are widgets and not drawn lines</h2>
     *
     * <p>Because they are the thing a click lands on. A hand-drawn row cannot take focus, cannot be
     * narrated, and has no hover state unless this class reimplements one per row — and the price of
     * getting all three is a class that moves the widget when the content scrolls, which is exactly
     * {@link ScrollView}'s job.
     *
     * <p>Each button is created at <b>0,0 with no size</b>, and that is deliberate rather than lazy: its
     * rectangle is not this method's to decide. {@code apply} sets x, y, width and height from the row's
     * slot, so a button created at its final size would be a second description of where a row goes —
     * which is the class of mistake this whole round is about.
     *
     * <h2>A heading is flat, and a chapter is filled or not</h2>
     *
     * <p>A group row gets no fill and a chapter row gets {@link ArmatureButton#selected} when it is the
     * one being shown. The heading is a heading that happens to be clickable; giving it a selection fill
     * would make it read as the current chapter — the confusion the fill exists to prevent, one level up.
     */
    private void buildSidebarWidgets() {
        SidebarLayout layout = sidebar();
        sidebarView.clear();

        sidebarViewport();
        int width = geometry().sidebarViewport().width();

        for (SidebarLayout.Row row : layout.rows()) {
            boolean heading = row.group();
            boolean isSelected = !heading && row.id().equals(effectiveChapter());

            ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                    () -> pressSidebarRow(row.key()));

            // Left-aligned, every row. A column of centred labels has a ragged left edge, so nothing
            // lines up and a short title floats away from the row it names -- see ArmatureButton on
            // why the alignment is the caller's decision rather than a heuristic in the control.
            button.alignLeft(true);

            if (heading) {
                // A section, not flat. Flat draws nothing at all, which is what the headings were, and
                // a row whose whole width is clickable reading as plain text is the report that came
                // back: "no like thing to make the categories look like buttons".
                button.section(true);
            }
            else {
                button.selected(isSelected)
                        .textColour(isSelected ? ArmatureTheme.title() : ArmatureTheme.body());
            }

            sidebarView.put(row.key(), button);
        }

        sidebarView.apply(layout.stack(width), width);
    }

    @Override
    protected void init() {
        // First, because everything below is built from the caches it names -- and the frame that
        // must notice them moving is the one after this returns. See `watchSources`.
        watchSources();
        clearWidgets();
        buttons.clear();
        // Nulled with the list it came from. `init` rebuilds Close only on the branch that builds the
        // book, so leaving this set would have `render` draw a control the screen no longer owns — see
        // the guard at the drawing site for what that looks like.
        closeButton = null;
        partyButton = null;
        // Cleared with them: the rows are drawn from this list, so a rebuild that left it alone would
        // draw the previous panel's rows over the new one. The card and the layout go with it, because
        // one pass records them together and a card left behind would draw a box for a panel that no
        // longer exists.
        partyRows = new ArrayList<>();
        partyCard = null;
        partyLayout = null;
        // And the scroll view's widgets, for the reason the three above are cleared: they belong to the
        // panel being replaced. `buildPartyWidgets` clears it too, but only on the branch that builds a
        // panel — a rebuild for a quest would otherwise leave the scroll view holding controls that are
        // no longer on the screen.
        partyView.clear();
        // And the armed Disband, because a rebuild is a new panel: the row a player armed may not be
        // there any more, and a control that says "Confirm" for a press it no longer remembers is
        // worse than one that forgot.
        disbandButton = null;
        disbandArmed = false;

        // No theme is applied here, and there used to be one call. A chapter's palette is now a scope
        // opened and closed within a single frame -- see `drawCanvas` and `renderWith` -- so there is
        // nothing to establish before the controls are built, and the label below reads from
        // `Appearance`, which no chapter can influence.
        //
        // That is a real simplification rather than a relocation. The sequence here used to matter: the
        // theme had to be applied before the controls were made, because each control reads its colours
        // when it is constructed. With the two palettes separated by region, a control reads the chrome
        // and a canvas reads the chapter, and neither has an order dependency on the other.

        if (overlay == Overlay.QUEST) {
            // The book's own controls as well, because the book is drawn *behind* the modal rather than
            // replaced by it -- so the column behind the card is not empty.
            buildSidebarWidgets();
            // And the header's. This is the fix for a Close button that vanished exactly when a dialog
            // was open: it was built by the book's branch alone, so opening a modal cleared every widget
            // and left `closeButton` pointing at one no longer in `children`.
            buildHeaderChrome();
            // And the view cluster, which the book's branch alone used to build. That is why the three
            // map buttons went missing behind a modal: the modal branches built the sidebar and the
            // header and stopped, so the cluster was simply absent. It belongs to the book, and the
            // book is drawn behind the card, so it is built here too and made inert with the rest.
            buildViewCluster();
            // Where the book's controls end and this modal's begin. See `bookButtonCount`.
            bookButtonCount = buttons.size();

            buildOverlayWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.PARTY) {
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            bookButtonCount = buttons.size();

            buildPartyWidgets();
            setBookControlsActive(false);
            return;
        }

        if (toolsOpen) {
            buildToolsWidgets();
        }

        // Every rectangle below comes from BookGeometry's control map, which is what BookGeometryTest
        // asserts on. That sharing is the whole point: the test cannot see the screen, so the screen
        // has to build itself from the thing the test can see. The previous version created controls
        // from numbers written here by hand, and a test asserting on a *parallel* description would
        // have passed while the screen still overlapped -- which is worse than no test at all, because
        // it would have been believed.
        // The chapter list, which is no longer this method's to place.
        //
        // It used to be a loop right here: BookGeometry handed out chapter0, chapter1, ... and this
        // method made a button from each rectangle until the rectangles ran out. It cannot be that any
        // more, and not because the code moved -- because the *question* changed. A row's position now
        // depends on its own index, on whether its group is collapsed, and on how far the list has
        // been scrolled, and the last two are properties of a view rather than of a layout. A map built
        // once from a window size cannot say "row five, currently scrolled out of view" without also
        // becoming the thing that decides it.
        //
        // So the sidebar builds its own widgets, from a Stack inside a ScrollView, and this method's
        // job is to say so. What the chapter loop's comment said about selection is still true and now
        // lives in `buildSidebarWidgets`, because that is where the chapter rows are made.
        buildSidebarWidgets();

        // Close, in the header's right corner. A modal panel is closed by the thing in its corner, and
        // the header had a mostly empty right end.
        //
        // This replaces a full-width "Done" button in the sidebar's footer — the only control on the
        // screen whose label was a whole word occupying a whole row. Escape still closes the book, so
        // this is the discoverable half of a pair rather than the only way out.
        //
        // Nothing is built at the foot of the sidebar below this, and there used to be two rows there:
        // the theme picker and the motion switch. They are dev tools now, reached from a mode rather than
        // sitting permanently under the chapter list — the picker beside the editor, and the motion
        // switch beside the accessibility settings it duplicates. See BookGeometry.controls for why the
        // geometry went with them and why the space went back to the chapter list.
        buildHeaderChrome();

        // The view cluster: three square buttons in the canvas's own top-left corner.
        //
        // These were four controls across two rows of the sidebar's footer — 116 pixels of a 132-pixel
        // column, which is *why* the footer needed two rows at all. They are map controls, so they
        // belong on the map: that is where a player looks for them, and it costs the chapter list
        // nothing. The rectangles come from the same map the overlap test walks.
        buildViewCluster();

        // Every other branch of this method returns early, so this is the book's own end: past here
        // there is nothing but the book's controls, and no overlay's. See `bookButtonCount`.
        bookButtonCount = buttons.size();
        setBookControlsActive(true);
    }

    /**
     * The three map buttons, in the canvas's own top-left corner.
     *
     * <h2>Why this is a method rather than three calls in one branch of {@code init}</h2>
     *
     * <p>Because it used to be three calls in one branch, and the branch that mattered was another one.
     * The cluster was built by the book's branch alone, so opening a modal -- which rebuilds every
     * widget -- left the canvas without its three buttons. That is a control that is <i>absent</i>
     * rather than inert, and it is what "the main UI buttons top left" being missing was: the modal
     * branches built the sidebar and the header and stopped.
     *
     * <p>So the cluster is built by every branch, like the sidebar and the header, and it is made inert
     * with them -- it belongs to the book, and the book is drawn behind the card. The order in
     * {@code init} is what makes that work: this runs before {@code bookButtonCount} is recorded, so
     * the cluster is on the book's side of that boundary and is not drawn a second time by the modal's
     * own redraw.
     *
     * <p>The rectangles come from the same map the overlap test walks, which is the reason the three
     * calls were written this way to begin with.
     */
    private void buildViewCluster() {
        Map<String, BookGeometry.Rect> controls = geometry().controls();

        control(controls.get("zoomIn"), Component.literal("+"), () -> zoomCentre(1.25F))
                .tooltip(List.of(Component.literal("Zoom in"),
                        Component.literal("Or scroll up over the canvas")))
                .textColour(ArmatureTheme.body());

        control(controls.get("zoomOut"), Component.literal("\u2212"), () -> zoomCentre(0.8F))
                .tooltip(List.of(Component.literal("Zoom out"),
                        Component.literal("Or scroll down over the canvas")))
                .textColour(ArmatureTheme.body());

        // A glyph rather than the word "Centre", because it is an 18-pixel square: "Centre" in that
        // box would be cut off by the button's own font measurement -- and it was that measurement that
        // fixed the chapter titles, so the fix here is to pass a label that fits rather than to widen
        // the control back out. The tooltip carries the word.
        control(controls.get("centre"), Component.literal("\u25c9"), () -> {
            centred = false;
            centreCanvas();
        })
                .tooltip(List.of(Component.literal("Re-centre the view"),
                        Component.literal("Drag with left or middle to pan")))
                .textColour(ArmatureTheme.body());
    }

    /**
     * The two controls in the header: Close and the party button.
     *
     * <h2>Why this is a method rather than a block in one branch of `init`</h2>
     *
     * <p>Because all three branches need it, and only one of them had it. The header chrome was built
     * by the branch that builds the book, so opening a modal -- which clears every widget and builds
     * the modal's controls instead -- left both fields pointing at controls that were no longer in
     * `children`. That is why Close disappeared exactly when a dialog was open: the one control every
     * dialog has, gone at the moment it was wanted.
     *
     * <h2>Both are ordinary controls, and that is a fix</h2>
     *
     * <p>They are in the header, and the widget pass is clipped to the sidebar's column so that a
     * scrolled row cannot be drawn through the title bar. That clip used to span the whole panel, which
     * swallowed these two: they were drawn by hand in the raised layer instead -- and a control drawn by
     * hand is one the base class never tells about the pointer, so neither of them ever faded under it.
     * The clip is the sidebar's column now, and the pass draws them like everything else.
     */
    private void buildHeaderChrome() {
        Map<String, BookGeometry.Rect> controls = geometry().controls();

        // Close closes the *modal* when one is open, and the book otherwise. That is what Escape does
        // already -- see `keyPressed` -- and the two have to agree, because they are the same gesture
        // and a player will use whichever they reach for.
        closeButton = control(controls.get("close"), Component.literal("\u2715"), () -> {
            if (overlay != Overlay.NONE) {
                closeOverlay();
            }
            else {
                onClose();
            }
        });
        if (closeButton != null) {
            closeButton.textColour(ArmatureTheme.body());
        }

        // Labelled here rather than by the drawing, and that is a fix rather than a preference. The
        // strip this replaces had its message written in `drawBook`, which runs before the widget pass
        // within a frame -- so the first frame after `init` drew an empty control, and any frame where
        // the widget pass came first drew nothing at all. The report was exactly that: no text on the
        // party button.
        //
        // What changes with the roster is not the label -- "Party" says the same thing whoever is in it
        // -- but the tooltip, and a stale tooltip costs a hover line where a stale label cost the whole
        // control.
        partyButton = control(controls.get("party"),
                Component.translatable("tasked.screen.party.button"), this::openPartyOverlay);
        if (partyButton != null) {
            partyButton.textColour(ArmatureTheme.body());
        }

        // The author's split control, and it exists only for a player who may edit the questline -- the
        // same permission `/tasked reload` asks for, which is what makes "who may edit" one rule rather
        // than two. A player who is not an operator sees the book exactly as it was: two controls in the
        // header and nothing else, which is the point of gating it here rather than greying it out.
        editButton = null;
        toolsButton = null;
        if (mayEdit()) {
            editButton = control(controls.get("edit"),
                    Component.literal("Edit"), () -> setEditing(!DevMode.on()));
            if (editButton != null) {
                editButton.textColour(ArmatureTheme.body())
                        .selected(DevMode.on())
                        .tooltip(List.of(Component.literal("Edit this questline"),
                                Component.literal("Drag nodes, create, duplicate, delete"),
                                Component.literal("Ctrl+S saves, Ctrl+Z undoes")));
            }

            toolsButton = control(controls.get("tools"),
                    Component.literal("Tools"), () -> {
                        toolsOpen = !toolsOpen;
                        rebuildWidgets();
                    });
            if (toolsButton != null) {
                toolsButton.textColour(ArmatureTheme.body())
                        .selected(toolsOpen)
                        .tooltip(List.of(Component.literal("Tools"),
                                Component.literal("Theme, colours and the preview")));
            }
        }
    }

    /**
     * Whether this player may edit the questline.
     *
     * <p>Permission level two: the level `/tasked reload` declares, read here through the player rather
     * than through a command source. The two cannot drift because there is one number and this comment
     * names it -- and the day the rule changes, the command is where it should change.
     */
    private boolean mayEdit() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    /** Edit mode on or off, with the header control and the panel following. */
    private void setEditing(boolean on) {
        DevMode.setOn(on);
        report(on ? "Edit mode on" : "Edit mode off");
        rebuildWidgets();
    }

    /**
     * Makes the book's own controls inert while a modal is open.
     *
     * <h2>Why `active` and not a check in `mouseClicked`</h2>
     *
     * <p>Because this method already returns early for an open overlay -- and that was not enough. The
     * early return stops <i>this</i> method reaching its own logic, but the modal's controls are reached
     * through {@code super.mouseClicked}, which walks every widget. So the report was right: a sidebar
     * row under the card still took a click and still scrolled.
     *
     * <p>{@code AbstractWidget.mouseClicked} returns false for an inactive widget and {@code draw}
     * returns immediately, so one flag per button turns off input and painting together -- which is the
     * point. Disabling input alone would leave the rows drawn over the card wherever the two overlap.
     *
     * <p>The buttons stay in {@code children} rather than being cleared, so the scroll view's own state
     * survives: closing the modal has to restore a chapter list scrolled where the player left it.
     */
    /**
     * Makes the book's own controls answer the pointer, or not.
     *
     * <h2>Everything the book built, and nothing the modal did</h2>
     *
     * <p>The book's controls are everything before {@link #bookButtonCount} -- the sidebar's rows, the
     * header's Close and Party, and the view cluster -- and a modal's are everything after it. An open
     * modal makes the first group inert and leaves the second live, which is the whole of the rule.
     *
     * <h2>The header's two used to be exempt, and that was the reported fault</h2>
     *
     * <p>The exemption's argument was that Close closes the dialog and Party opens the panel, so both
     * are wanted <i>because</i> a modal is up. What it missed is that the header is drawn <b>under</b>
     * the card like everything else: a control the player cannot see is a control they cannot aim at,
     * so a press there is a press on whatever happens to be under the pointer at the time. The report
     * was exactly that -- <i>"i can click on party and x in top right from behind a modal"</i> -- and
     * the fix is to stop special-casing them. A modal is left by its own Back, by Escape, or by a
     * click outside it, which are the three ways the card itself offers.
     *
     * <p>With the exemption gone so are the two indices it needed, and that is the simplification
     * rather than a loss: "the book's controls" is one range, and one range is one boundary to get
     * wrong instead of three.
     */
    private void setBookControlsActive(boolean active) {
        int end = Math.min(bookButtonCount, buttons.size());
        for (int i = 0; i < end; i++) {
            buttons.get(i).active = active;
        }
    }

    private void buildOverlayWidgets() {
        ClientQuestCache.Entry entry = entryFor(overlayQuest);
        if (entry == null) {
            overlay = Overlay.NONE;
            rebuildWidgets();
            return;
        }

        // The footer, from BookGeometry's control map -- the same source the dashboard uses, and the
        // same one BookGeometryTest asserts on. See the comment in init() for why that sharing is the
        // point rather than a nicety.
        //
        // The overlay is where the mismatch was real: this method used to pass 130 for Submit's width
        // while BookGeometry said 120, so the overlap test would have gone on passing while the control
        // on screen ran into Back.
        // Submit and Claim share the bottom-left control, and the geometry is asked for a control if
        // *either* of them wants one. They cannot both want it: Submit needs a task a player can hand
        // over, which means the quest is unfinished, and Claim needs the quest to be finished. So it is
        // one rectangle with two possible meanings rather than two controls fighting over one corner --
        // which is precisely the collision BookGeometry exists to prevent.
        int taskIndex = firstManualTask(entry);
        boolean claimable = ClientQuestCache.canClaim(entry.id());
        Map<String, BookGeometry.Rect> controls = geometry().overlayControls(taskIndex >= 0 || claimable);

        if (claimable) {
            // Collecting a finished quest's rewards. No permission, no confirmation: it is the player
            // asking for something the server already knows they earned, and the server re-checks that
            // before handing anything over -- so a client that shows this wrongly gets a refusal.
            ArmatureButton claim = control(controls.get("submit"),
                    Component.translatable("tasked.screen.quest_book.claim"),
                    () -> claim(entry.id()));
            if (claim != null) {
                claim.accent(true)
                        .tooltip(List.of(Component.literal("Collect this quest's rewards"),
                                Component.literal("Nothing more is needed - it is already finished")));
                // The first reward's own icon, when there is one to draw. A Claim button wearing the
                // thing it is about says what it is for without a word of label.
                if (!entry.rewards().isEmpty()) {
                    ClientQuestCache.RewardEntry first = entry.rewards().get(0);
                    claim.icon(first.hasItem() ? first.item() : first.icon());
                }
            }
        }
        else if (taskIndex >= 0) {
            final String questId = entry.id();
            final int index = taskIndex;
            ArmatureButton submit = control(controls.get("submit"),
                    Component.translatable("tasked.screen.quest_book.submit"),
                    () -> submit(questId, index));
            if (submit != null) {
                submit.accent(true)
                        .icon(entry.tasks().get(index).icon())
                        .tooltip(List.of(Component.literal("Hand over task " + (index + 1)),
                                Component.literal("The server checks the items are really there")));
            }
        }

        ArmatureButton back = control(controls.get("back"), Component.literal("Back"), this::closeOverlay);
        if (back != null) {
            back.textColour(ArmatureTheme.body())
                    .tooltip(Component.literal("Escape also closes this"));
        }
    }

    /**
     * Whether a point is outside whichever card is open.
     *
     * <h2>One expression, for two cards of two sizes</h2>
     *
     * <p>The two modals are different rectangles -- the quest's is `modal()`, and a roster's is the card
     * the widget pass built, sized from its own layout -- so a caller asking "is this outside" has to
     * ask about the right one. This reads the party's card from {@link #partyCard}, which is the same
     * object the drawing draws and the widgets were placed inside: a second computation would be a
     * click measured against a card that is not on screen, which is the class of fault this file's
     * geometry exists to prevent.
     *
     * <p>A click inside a card but not on a control does nothing, deliberately: it belongs to the
     * panel. A click outside closes it, which is what every dialog does.
     */
    private boolean clickedOutsideCard(double mouseX, double mouseY) {
        BookGeometry.Rect card = overlay == Overlay.PARTY && partyCard != null
                ? partyCard
                : geometry().modal();

        return mouseX < card.x() || mouseX > card.right()
                || mouseY < card.y() || mouseY > card.bottom();
    }

    private void openOverlay(String questId) {
        overlay = Overlay.QUEST;
        overlayQuest = questId;
        overlayView.scrollTo(0);
        // The row keys mean something else now -- "task:0" was the last quest's first task -- so a
        // hover carried over would light up a row nobody is pointing at for a fifth of a second.
        rowHover.clear();
        rebuildWidgets();
    }

    private void closeOverlay() {
        overlay = Overlay.NONE;
        overlayQuest = null;
        overlayView.scrollTo(0);
        rowHover.clear();
        rebuildWidgets();
    }

    /** The first task a player hands over by hand, or -1. */
    /**
     * The first task a player hands over by hand <b>and still has to</b>, or -1.
     *
     * <h2>Why "still has to" is part of the question</h2>
     *
     * <p>Because a checkmark task stays a manual task after it has been handed in -- that is its type,
     * not its state -- so a caller asking only "is there a manual task" was answered yes forever, and the
     * Submit button stayed on screen after the press that used it. The report was exact: <i>"submit
     * should disappear after it has been submitted, it doesn't now"</i>.
     *
     * <p>Reaching the task's count is the test rather than a submitted flag, because that is what the
     * server records and what the button's own effect produces: submitting moves the progress, the
     * progress sync arrives, and the screen rebuilds -- so the button goes because the reason it existed
     * has gone, and not because a copy of the answer was kept here.
     */
    private static int firstManualTask(ClientQuestCache.Entry quest) {
        for (int i = 0; i < quest.tasks().size(); i++) {
            ClientQuestCache.TaskEntry task = quest.tasks().get(i);
            if (task.manual() && ClientQuestCache.taskProgressOf(quest.id(), i) < task.count()) {
                return i;
            }
        }
        return -1;
    }

    private static void submit(String questId, int taskIndex) {
        ArmatureNetwork.sendToServer(new SubmitTaskPayload(questId, taskIndex));
        Constants.LOG.debug("tasked: asked the server to submit task {} of {}", taskIndex, questId);
        // No local change. The server answers with a progress sync, and showing the outcome before it
        // arrives would mean showing something the server may refuse.
    }

    /**
     * Asks the server to hand over a finished quest's rewards.
     *
     * <p>Sends the quest id and nothing else, and makes no local change, for the same reason
     * {@link #submit} does not: whether anything is owed is the server's decision, and it is the one
     * that has the stored progress. Showing the items before it answers would mean showing something
     * it may refuse -- and the refusal is a case that exists, because a stale client can be showing a
     * Claim button for a quest it collected a minute ago.
     */
    private static void claim(String questId) {
        ArmatureNetwork.sendToServer(new ClaimRewardPayload(questId));
        Constants.LOG.debug("tasked: asked the server to hand over the rewards for {}", questId);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * Deliberately empty.
     *
     * <p>The background this screen draws over the world is its own scrim, and vanilla's blurred menu
     * background is suppressed on {@code ArmatureScreen} — the base class — so no panel has to remember
     * it. See this class's comment for the mechanism and that class's for why it moved.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The one forced signature in this class for drawing, and the whole of the seam at this call
        // site: a GuiGraphics arrives because Minecraft's Screen hands over one and there is no other
        // override, so it is wrapped and handed on. Nothing below this line names the type.
        //
        // The widget pass stays here rather than moving into renderWith, and that is deliberate: the
        // controls are AbstractWidgets and the base class draws them from the context it was given, so
        // there is no version of "draw the widgets" that takes a renderer. Keeping it on this side is
        // what lets renderWith — the part that decides what the book looks like — be driven by a
        // RecordingRenderer with no client at all.
        //
        // And the tooltip is drawn last of all, after the controls, which is the whole of the fix for
        // a tooltip that appeared *underneath* the button it described.
        GuiRenderer renderer = new GuiGraphicsRenderer(graphics);

        // The world behind the book, softened before the book is drawn over it -- the same look a
        // vanilla menu has, and the thing that stops an open book from looking like a window cut into a
        // live world. `blur` restores the pipeline afterwards, so the book itself is drawn crisp.
        //
        // Only when no modal is open, and that is the whole of the placement: with a card up, the blur
        // below runs once over the world *and* the book, and blurring here as well would put the world
        // through two passes while the book got one -- a seam at the card's edge that nothing explains.
        if (overlay == Overlay.NONE) {
            renderer.blur(partialTick);
        }

        renderWith(renderer, mouseX, mouseY, partialTick);


        // Everything from here on is the **chrome layer**, and it is drawn at a raised Z. Read the
        // next few paragraphs before moving any of it: this is a fix for a defect that no draw ORDER
        // can fix, and it was misdiagnosed once already.
        //
        // An item icon is not a fill. `GuiGraphics.renderItem` translates the pose to Z = 150 and then
        // calls `flush()` itself, and `flush()` is `disableDepthTest(); endBatch(); enableDepthTest()`
        // -- so depth testing is switched back ON the moment an icon is drawn, and the icon has
        // written depth 150. GUI fills draw at Z = 0 and do not write depth at all.
        //
        // The consequence is the whole of the bug: a fill drawn *after* an icon at Z = 0 fails the
        // depth test where the icon is and is **never drawn there**, however late it is drawn. That is
        // why the node's icon punched through the cluster panel and hid the third view button -- and
        // why the `flush()` that used to be the fix for this changed nothing, because the ordering was
        // never wrong. Verified against the compiled bytecode rather than recalled:
        // `tmp-verify/probe_managed.py` prints both methods.
        //
        // So the chrome is translated up instead: at Z = 400 a control beats an icon at Z = 150 by
        // depth rather than by order, which is what "this is the top layer" actually means.
        //
        // The pose is pushed here rather than inside `renderWith` because the pose is a `GuiGraphics`
        // thing and `renderWith` deliberately sees only a `GuiRenderer` -- that is what lets it be
        // driven by a RecordingRenderer with no client. This override is the one place that has the
        // unwrapped context, so it is the one place this can be done.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0F, 0F, CHROME_Z);
        try {
            // The view cluster's backing panel, and it is drawn here rather than in `drawBook` for
            // exactly this reason: it has to be inside the raised Z, and `drawBook` cannot raise it.
            //
            // Nothing else moved: the panel is still positioned and sized by `BookGeometry`, and it is
            // still drawn before the widgets so the three buttons sit on top of it.
            // Guarded on having data, which is the same condition `drawBook` returns early on: with no
            // quests there are no widgets in the cluster, so the panel would be a raised box with three
            // things missing from it. `drawBook` used to reach this line only after that early return,
            // and moving the panel here would have quietly dropped the guard with it.
            // Drawn whether or not a modal is open, because the cluster is built in every branch now
            // and the mat is what makes the three buttons read as one group. The guard used to include
            // `overlay == Overlay.NONE`, which was right while only the book's branch built the buttons
            // and wrong the moment the modal branches did too: three controls with no panel behind them.
            if (ClientQuestCache.hasData()) {
                BookGeometry.Rect cluster = viewControls();
                ArmatureTheme.panel(renderer, cluster.x(), cluster.y(), cluster.width(),
                        cluster.height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());
            }

            // The widget pass, clipped from the sidebar's list top downwards.
            //
            // **Clipping at the list top rather than at the panel is the fix, not a tightening.**
            //
            // This used to clip to the panel and rely on the *scroll* to keep rows off the header:
            // every wheel notch was snapped to a whole row pitch, so a row was never left half past the
            // top edge. That was true, and then the scrollbar became draggable — which sets arbitrary
            // offsets, because the thumb has to follow the pointer. The first drag left a row sitting
            // through the title bar, and the picture of it is what this comment is answering. The old
            // note here said "what keeps rows off the header is therefore the scroll rather than the
            // clip", which was a correct description of an arrangement that one input later stopped
            // holding.
            //
            // So the clip does the work now, which is the only version that survives a second way to
            // scroll: snapping is a property of one input and a clip is a property of the drawing.
            // Whatever a row does, it does it below `chapterListTop()`.
            //
            // `chapterListTop()` rather than the sidebar's own viewport rectangle, because the base
            // class draws every widget in one call and there is no way to clip one group of them and
            // not another — so this is one rectangle for the whole screen. Using the list's *top* as
            // the bound is what makes one rectangle do the job: it excludes the header and the gap
            // under it, and the only widgets it has to spare are the three view buttons, which start
            // below it. `BookGeometryTest` asserts that clearance, because two pixels of margin is
            // exactly the kind of thing a later layout change eats without anybody noticing.
            //
            // Close is above this line, so it is drawn by hand in the chrome layer below — see
            // the narrow clip, which is what keeps it out of the pass's own drawing.
            BookGeometry.Rect book = panelRect();
            // The clip is a **horizontal band**: the chapter list's top edge down, across the panel. It
            // is what keeps a scrolled row from being drawn up through the title bar.
            //
            // It therefore excludes the header, which is above it -- and that is why the header's two
            // controls cannot be drawn by this pass. A round tried narrowing it to the sidebar's column
            // as a fix for their hover; that was the wrong axis (the header is above the band, not beside
            // it) and it cut the canvas's view cluster out of the pass as well, since the cluster is to
            // the right of that column. Reverted, and their hover is fixed where it belongs: see the
            // chrome layer below, which now draws them through the widget's own `render`.
            try (GuiRenderer.Scoped clip = renderer.clip(book.x(), geometry().chapterListTop(),
                    book.right(), book.bottom())) {
                super.render(graphics, mouseX, mouseY, partialTick);
            }

            // Close, drawn by hand rather than by the widget pass above.
            //
            // It is in the header, and the widget clip now starts at the list's top edge so that
            // sidebar rows cannot be drawn through the title bar. That clip would swallow Close, so
            // the one control that lives above it is drawn here instead -- at the chrome Z, which is
            // also where a close button belongs.
            //
            // It is still a widget in every other respect: it is registered, so presses reach it
            // through the base class's own input handling, and -- since this round -- so does hover,
            // which `addWidget` alone never gave it. What it does not do is get *drawn* by that pass:
            // the clip discards it, and the drawing that counts is here.
            // Guarded on the overlay being shut, because `closeButton` is only rebuilt by the branch of
            // `init` that builds the book. Opening an overlay clears every widget and builds the
            // overlay's two controls instead, so without this the field would still be pointing at a
            // control that has been removed — and `draw` checks `visible`, not membership, so it would
            // cheerfully draw Close over the quest card.
            // Drawn whether or not an overlay is open, and its absence here was a reported fault: the
            // guard read `overlay == Overlay.NONE`, so the one control every dialog has disappeared at
            // the moment a dialog was open. It closes the modal rather than the book when one is up --
            // see `buildHeaderChrome` -- which is what Escape already does.
            // Close and Party are drawn here rather than by the pass -- and now they are drawn by the
            // widget's **own `render`**, which is the difference between a control that fades under the
            // pointer and one that never did.
            //
            // The pass cannot draw them: its clip is the band below the header, so anything in the header
            // is discarded. And a control the pass never renders is a control whose hover is never worked
            // out -- `isHovered` is set in `render`, from the pointer -- which is why these two were the
            // only controls in the book that did not fade.
            //
            // So this layer tells them, on the way past: `hoverTold` is a control's hover said out loud
            // by the caller that draws it, and `isMouseOver` is vanilla's own test -- which answers false
            // for an inert control too, so neither of these fades while a modal has the book behind it
            // and neither of them can be pressed.
            if (closeButton != null) {
                closeButton.hoverTold(closeButton.isMouseOver(mouseX, mouseY)).draw(renderer);
            }
            if (partyButton != null) {
                partyButton.hoverTold(partyButton.isMouseOver(mouseX, mouseY)).draw(renderer);
            }
            if (editButton != null) {
                // The author's split control. Drawn here for the same reason Close and Party are: the
                // widget pass is clipped to the band below the header, so a control in the header that
                // relied on it would never be drawn.
                editButton.hoverTold(editButton.isMouseOver(mouseX, mouseY)).draw(renderer);
            }
            if (toolsButton != null) {
                toolsButton.hoverTold(toolsButton.isMouseOver(mouseX, mouseY)).draw(renderer);
            }
            if (overlay != Overlay.NONE) {
                // A modal softens what is behind it, and this is the moment that does it: everything
                // behind the card is drawn by now -- the book, its scrim, and the widget pass above,
                // which is where the buttons are. Blurring any earlier left them crisp, which was the
                // report: "doesn't blur buttons etc".
                //
                // Outside the clip above, deliberately: a post-process leaves the scissor set for its
                // own passes, and `blur` puts it back -- which it can only do honestly when no clip of
                // ours is open. See `GuiRenderer.blur`, including the card that vanished before it did.
                //
                // The modal's own controls are drawn below, on top of the card, so the copy of them the
                // widget pass just drew is blurred and then covered. Which is the right way round: they
                // belong to the card, and the card is crisp.
                renderer.blur(partialTick);

                drawModal(renderer, mouseX, mouseY, net.minecraft.Util.getMillis());

                // And the modal's **own controls, redrawn on top of the card**.
                //
                // **This is the ordering fix, and it is what "buttons invisible" was.** `super.render`
                // above drew every widget -- including the overlay's Create, Accept and Invite buttons
                // -- and then this layer painted the card over them. So they existed, were placed
                // correctly, took clicks, and could not be seen.
                //
                // Redrawing is right rather than resorting the pass: a widget knows how to paint
                // itself, so drawing one again is idempotent and costs a rounded box. The alternative
                // is asking the base class to do half its job -- draw some renderables but not others
                // -- which is the version of this that breaks the next time anything is added to the
                // list.
                //
                // From `bookButtonCount` to the end, which is exactly the controls this `init` built
                // for the modal rather than for the book.
                for (int i = Math.min(bookButtonCount, buttons.size()); i < buttons.size(); i++) {
                    buttons.get(i).draw(renderer);
                }
            }

            // Inside the raised Z as well, and that is not tidiness. A tooltip is a panel and some
            // text at Z = 0, so one overlapping a node's icon would have a hole punched in it by the
            // same mechanism -- and a tooltip is the last thing on the screen that should be see-through.
            //
            // Outside the clip, deliberately: a tooltip belongs over everything, including the edge it
            // happens to reach past.
            drawTooltips(renderer, mouseX, mouseY);
        }
        finally {
            pose.popPose();
        }
    }

    /**
     * How far above the canvas the chrome layer is drawn.
     *
     * <h2>Why a Z at all, in a 2D interface</h2>
     *
     * <p>Because vanilla's GUI is not flat: {@code GuiGraphics.renderItem} puts item icons at <b>150</b>
     * precisely so they render over text and fills, and it writes that depth. Every other GUI primitive
     * is at 0 and does not write depth. So an icon and a control are ordered by depth whether anybody
     * asked for that or not — and a control drawn afterwards at 0 loses to the icon.
     *
     * <p>400 is above the item layer and below nothing that matters. Vanilla's own tooltips sit around
     * the same figure, which is a coincidence rather than a dependency; what matters is only that it is
     * greater than 150, and the value is a named constant so that the two figures can be compared by
     * reading rather than by recalling.
     *
     * <p>It would be better if this were not needed, and the honest alternative is worse: clip the
     * canvas around the cluster, which means drawing the canvas twice, splitting any node that straddles
     * the boundary, and re-rendering every item icon for the privilege. Raising the chrome's Z costs a
     * pose push.
     */
    private static final float CHROME_Z = 400F;

    /** The whole book, as a rectangle. Used by the render clip and by nothing else. */
    private BookGeometry.Rect panelRect() {
        return geometry().panel();
    }

    /**
     * Draws the book. Takes a renderer, so this is the method a test can drive.
     *
     * <p>Separated from {@link #render} for exactly the reason the rest of this round exists: the
     * forced override cannot be tested — it needs a real {@code Screen} with a real client behind it —
     * and everything that decides <i>what is drawn</i> can be, once it is expressed in terms of the
     * seam. {@code RecordingRenderer} feeds this method and asserts on the result without a window.
     *
     * What it does <b>not</b> draw is the controls, and the honest reason is that they belong to
     * {@code AbstractWidget}: the base class iterates its renderables and hands each one the context it
     * was given. So a test driving this method sees the book and not its buttons. That is a real limit
     * worth stating rather than hiding — what it means is that button <i>placement</i> is
     * {@code BookGeometryTest}'s job, and button <i>appearance</i> is {@code ArmatureButton.draw}'s,
     * which does take a renderer and is testable on its own.
     *
     * <h2>Two palettes in one frame, and a test can tell</h2>
     *
     * <p>This is where the black is drawn and where the chapter's colours are drawn, and they are
     * different palettes on purpose — see {@code ArmatureTheme.scope}. The consequence for a test driving
     * this method is worth knowing: every colour it records is the one in force <i>at the point it was
     * drawn</i>, so a scope that leaks shows up as chrome drawn in a chapter's colours rather than as a
     * missing call. {@code ArmatureTheme.scopeDepth} is the blunt check for the same thing.
     */
    public void renderWith(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        centreCanvas();

        // A tree that arrived since the sidebar was built means the outline is describing a questline
        // this client no longer holds. Rebuilt here rather than from a payload handler, because the
        // handlers run before this screen exists as often as after it, and because this is the first
        // point in a frame where clearing and recreating the widgets is safe — `super.render` has not
        // started iterating them yet.
        //
        // Guarded on the overlay being closed: with one open, `init` builds the overlay's two controls
        // and nothing reads the sidebar, so the revision would stay stale and this would rebuild every
        // frame. `closeOverlay` rebuilds on the way out, which is where the sidebar comes back.
        if (overlay == Overlay.NONE && sidebarRevision != ClientQuestCache.treeRevision()) {
            rebuildWidgets();
        }

        // The animation clock, read once per frame and handed down. Nothing in this screen or in the
        // toolkit reads a clock itself -- see Tween's javadoc for why that is the property that makes
        // every animation here testable, and `Motion.tween` for how the client's setting reaches it.
        long now = Util.getMillis();

        // The book first, always, and then a scrim over it. It used to be drawn *only* when no
        // overlay was open, so opening a modal replaced the whole book with a flat dim -- and the
        // request was to keep it: "dont close whats behind them, just have it in the background".
        //
        // A scrim under a blur, and the two are one decision made in two places: the scrim is what says
        // the book is inert, and the blur -- the call in `render`, once this method has drawn the book --
        // is what makes the card readable over it.
        //
        // This note used to say the blur was a thing deliberately not done, and then that it had been
        // tried and erased the card. Both are worth keeping in view: the fault was real, and its cause
        // was that a post-process leaves the pipeline set for its own passes, so the one layer drawn
        // with no clip of its own had nothing to draw into. `GuiRenderer.blur` puts the pipeline back.
        drawBook(renderer, mouseX, mouseY, now);
        if (overlay != Overlay.NONE) {
            renderer.fill(0, 0, width, height, ArmatureTheme.dim());
        }

        // The tools panel: over the book, under its own controls, and shut while a modal is open --
        // two things in front is one too many, and the panel keeps its state either way.
        drawTools(renderer, mouseX, mouseY);

        // The overlay is NOT drawn here any more. It is chrome, and chrome is drawn by
        // `render`, in the raised-Z layer, after the widget pass -- see `drawModal`. Two things
        // follow, and both were reported faults: the card is genuinely on top of the quest
        // canvas's item icons (which write depth at Z = 150 and so beat a card at Z = 0 whatever
        // the draw order), and the sidebar's rows behind it are *under* it rather than over it.
        //
        // What stays here is the scrim, because the scrim belongs to the book: it is the thing
        // that says the book is inert, and it is drawn while the book's own pixels are still the
        // most recent ones.

        // Tooltips are deliberately NOT drawn here, and that is a bug fix rather than a preference.
        //
        // They belong over the controls, and the controls are drawn by `render` *after* this returns.
        // A tooltip drawn at this point is therefore underneath the widget it describes — which is
        // what it looked like on screen: hovering a chapter drew its name in a box that the chapter
        // button then painted over, so the tooltip showed as a ghost of text on the button's top edge
        // and read as a rendering fault rather than as a tooltip.
        //
        // Worth recording how it got here, because the comment that caused it is worth reading: this
        // method used to end with drawTooltips, annotated "kept here as well because a test driving
        // renderWith wants to see them". That is a rationalisation rather than a reason — a test can
        // call drawTooltips itself, and now does — and the cost was a visible defect that no test
        // could catch, because drawing order over widgets is not something RecordingRenderer observes.
        // A screenshot found it in five seconds, which is the honest argument for looking at the UI
        // as well as testing it.
    }
    /**
     * The party panel's card: a title, the roster, and the actions.
     *
     * <h2>Every row comes from the layout the widgets were placed from</h2>
     *
     * <p>Which is the reason {@link #partyLayout} is a field. A drawing that recomputed its own rows
     * would put a member's name beside a Remove button belonging to somebody else, and that failure only
     * appears when a party has the wrong number of members in it -- the case a single-member test never
     * reaches.
     *
     * <h2>Scrolled, and clipped by a scissor</h2>
     *
     * <p>The body is a {@link ScrollView}'s viewport, so a roster taller than its card is reachable
     * rather than lost. Three things come from that one rectangle: the clip the drawing is wrapped in,
     * the scroll clamp, and where each row lands — because the panel's rows are placed by
     * {@code apply} and drawn here, and both ask the viewport. A row half past the card's edge is cut
     * off by the clip rather than skipped, which is the shape the old "skip a row past the body"
     * comparison had, generalised to a region that moves.
     *
     * <p>The clip is the one place a scissor is right, and it replaced a comparison: skipping whole
     * rows cannot express a partially visible one, and a partially visible row is what a scroll is.
     */
    private void drawPartyOverlay(GuiRenderer r, int mouseX, int mouseY, long now) {
        PartyRoster roster = partyRoster();
        BookGeometry.Rect card = partyCard;
        Layout layout = partyLayout;
        if (card == null || layout == null) {
            // Not built: `buildPartyWidgets` records the card and the layout together, so neither can
            // be set without the other, and a card derived here would be the second arithmetic this
            // round removed.
            return;
        }

        // Re-applied here rather than only at build time, like the overlay's own body: a scroll that
        // happened since the last frame is already in the widget positions, and a resize cannot leave
        // the clamp measuring the previous window.
        Viewport body = partyBody();
        partyView.apply(layout, body.viewWidth());

        // No dim: `renderWith` fills one before dispatching to an overlay, and a second put two
        // translucent blacks over a world that is not otherwise drawn.
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // Everything below is content, and content is clipped to the body. The card is not: it is the
        // frame the content moves inside.
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            drawPartyRows(r, layout, roster, body, now);
        }

        // The bar, from the same viewport as the clip and the clamp, and drawn only when there is more
        // than fits. Like the quest overlay's, it is not draggable -- the sidebar's is, because a list is
        // a thing a pointer is already in; a modal's bar is read, and the wheel is how it is moved.
        partyView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /** The panel's content, drawn at the positions the viewport puts its rows in. */
    private void drawPartyRows(GuiRenderer r, Layout layout, PartyRoster roster, Viewport body,
                               long now) {
        // The title, placed by the layout like every other row. Its key *is* its translation key -- see
        // `PartyPanelLayout.TITLE` -- so there is no second table mapping one to the other.
        rowLabel(r, layout, PartyPanelLayout.TITLE, body,
                Component.translatable(PartyPanelLayout.TITLE).getString(), ArmatureTheme.title());

        if (roster.isReal()) {
            for (PartyRoster.Member member : roster.members()) {
                Slot slot = layout.slot(member.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = screenSlot(body, slot);

                rowHover.update(member.key(), now);
                float hover = rowHover.amount(member.key(), now);
                if (hover > 0F) {
                    rowWash(r, onScreen, onScreen.right(), hover);
                }

                // The portrait, then the marker for who is connected, then the name. The marker is drawn
                // whether or not the face drew, so a player with no skin cannot take the one part of
                // this row that is about presence with it. Where the fact comes from: the roster's own
                // member, which the server answered -- see `PartyRoster.Member`.
                int portraitX = onScreen.x() + PartyPanelLayout.HEAD_INSET;
                r.face(member.id(), portraitX,
                        onScreen.y() + (onScreen.height() - PartyPanelLayout.HEAD_BOX) / 2,
                        PartyPanelLayout.HEAD_BOX);

                int markerX = portraitX + PartyPanelLayout.HEAD_BOX + PartyPanelLayout.HEAD_GAP;
                int dotY = onScreen.y() + (onScreen.height() - PartyPanelLayout.STATUS_DOT) / 2;
                r.fill(markerX, dotY, markerX + PartyPanelLayout.STATUS_DOT,
                        dotY + PartyPanelLayout.STATUS_DOT,
                        member.online() ? ArmatureTheme.title() : ArmatureTheme.faint());

                int nameX = markerX + PartyPanelLayout.STATUS_DOT + PartyPanelLayout.STATUS_GAP;
                int textY = onScreen.y() + (onScreen.height() - r.lineHeight()) / 2
                        + PartyPanelLayout.NAME_DROP;
                // The name stops short of the room the row reserved for its Remove button, so it cannot
                // run under the button or the rank that sits inside it.
                r.text(Measure.truncate(member.label(),
                                Math.max(0, onScreen.width() - (nameX - onScreen.x()) - 4), textMeasure(r)),
                        nameX, textY,
                        member.self() ? ArmatureTheme.title() : ArmatureTheme.body());

                // The rank, right-aligned in the room the row reserved for its Remove button -- the same
                // reservation the button is placed inside, so the word and the button cannot overlap.
                String role = member.roleLabel();
                int roleX = onScreen.right() - PartyRoster.REMOVE_WIDTH
                        - PartyRoster.REMOVE_INSET * 2 - r.textWidth(role);
                if (roleX > onScreen.x() + 4) {
                    r.text(role, roleX, textY, ArmatureTheme.faint());
                }
            }
        }
        else {
            // The empty state's two lines, from the same layout -- see `PartyPanelLayout` for why a
            // player with no party is shown a panel rather than nothing.
            rowLabel(r, layout, PartyPanelLayout.NO_PARTY, body,
                    Component.translatable(PartyPanelLayout.NO_PARTY).getString(), ArmatureTheme.body());
            rowLabel(r, layout, PartyPanelLayout.HINT, body,
                    Component.translatable(PartyPanelLayout.HINT).getString(), ArmatureTheme.faint());
        }

        // The rule between the roster and the actions: a one-pixel row the layout placed, so where the
        // two lists meet is not a second expression of how tall the roster was.
        Slot rule = layout.slot(PartyPanelLayout.RULE);
        if (rule != null) {
            Slot onScreen = screenSlot(body, rule);
            r.fill(onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom(),
                    ArmatureTheme.panelEdge());
        }

        // The action rows' labels. Their buttons are widgets; only the text is drawn here, and the room
        // it may use is the slot the button was placed from -- so a label cannot be given the width of a
        // row that already spent part of it on a control.
        for (PartyPanelLayout.Action action : partyRows) {
            rowLabel(r, layout, action.key(), body, action.label(), ArmatureTheme.body());
        }
    }

    /**
     * One row's label, drawn where its slot is.
     *
     * <p>Centred in the row and truncated to the row's own width, with a small pad either side. Every row
     * of the party panel goes through here, so a title and an action label cannot disagree about where a
     * line of text sits in its row.
     */
    private void rowLabel(GuiRenderer r, Slot onScreen, String text, int colour) {
        int textY = onScreen.y() + (onScreen.height() - r.lineHeight()) / 2;
        r.text(Measure.truncate(text, Math.max(0, onScreen.width() - 8), textMeasure(r)),
                onScreen.x() + 4, textY, colour);
    }

    /**
     * The same, for a row looked up by its key in the panel's own layout.
     *
     * <p>Rows past the body are clipped by the caller's scissor rather than skipped here, because a
     * scrolled row is partly visible: a test that only asked "is it past the bottom" could not describe
     * one. A key the layout does not hold draws nothing, as before.
     */
    private void rowLabel(GuiRenderer r, Layout layout, String key, Viewport body, String text,
                          int colour) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return;
        }
        rowLabel(r, screenSlot(body, slot), text, colour);
    }

    /**
     * The tools panel, over the canvas.
     *
     * <p>Drawn after the book and before the widget pass, which is the only order that works: the panel's
     * own fills and text are {@link ToolsPanel}'s, and its controls are widgets the base class draws
     * afterwards -- the same split every panel here has.
     *
     * <p>Shut while a modal is open. The panel and a card are both "the thing in front", and two of them
     * at once is a stack nobody asked for; the panel keeps its state, so closing the card brings it back
     * exactly as it was.
     */
    private void drawTools(GuiRenderer r, int mouseX, int mouseY) {
        if (!toolsOpen || overlay != Overlay.NONE || toolsFrame == null || toolsLayout == null) {
            return;
        }
        ToolsPanel.draw(r, toolsFrame, toolsView.viewport(), toolsLayout, toolsRows,
                new ToolsPanel.State(toolsSelected, toolsFeedback, toolsFeedbackIsError),
                mouseX, mouseY);
        // The bar, from the kit's own rectangles and drawn only when there is more than fits -- the same
        // call the sidebar and the party panel make. It was missing entirely, which left a list that
        // scrolled with nothing on screen saying so.
        toolsView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    private void drawBook(GuiRenderer r, int mouseX, int mouseY, long now) {
        if (editors != null) {
            // The server's own answer for a moved node arrives with a tree, and that is the moment the
            // editor's remembered position is no longer needed. Noticed here rather than in a handler
            // because this is a comparison of two numbers on the frame path that already reads them.
            editors.onRevision(ClientQuestCache.treeRevision());
        }

        int left = panelLeft();
        int top = panelTop();
        int panelW = panelWidth();
        int panelH = panelHeight();

        ArmatureTheme.panel(r, left, top, panelW, panelH, ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // The two surfaces inside the panel, and both of them are now rounded on the corners they share
        // with it. Their rounds are one pixel tighter than the panel's, which is what keeps the gap
        // between the two curves even: reusing the panel's radius would make the panel's border thicker
        // at the corners than along its sides.
        //
        // The masks are the part that has to be right, and each says which corners the surface is
        // actually touching. The sidebar runs the full height of the left edge, so it rounds its two
        // left corners and leaves the right-hand pair square -- its right edge is interior, and rounding
        // it would leave two notches in the middle of the header strip. The header starts where the
        // sidebar ends and runs to the right edge, so it rounds its top-right corner and nothing else.
        //
        // Getting a mask wrong is visible and diagnosable rather than subtle, which is the whole reason
        // the mask is a value at the call site instead of a guess inside `fillSurface`.
        int innerRadius = Math.max(0, ArmatureTheme.current().cornerRadius() - 1);
        ArmatureTheme.fillSurface(r, left + 1, top + 1, SIDEBAR_WIDTH - 1, panelH - 2,
                ArmatureTheme.recessed(), innerRadius, ArmatureTheme.CORNERS_LEFT);
        ArmatureTheme.fillSurface(r, left + SIDEBAR_WIDTH, top + 1, panelW - SIDEBAR_WIDTH - 1,
                HEADER_HEIGHT - 2, ArmatureTheme.raised(), innerRadius, ArmatureTheme.TOP_RIGHT);
        r.fill(left + 1, top + HEADER_HEIGHT - 1, left + panelW - 1, top + HEADER_HEIGHT,
                ArmatureTheme.panelEdge());
        // A divider between the sidebar and everything else, so the two read as separate surfaces
        // rather than as one dark field with things floating in it.
        r.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, top + panelH - 1,
                ArmatureTheme.panelEdge());

        // The sidebar's scrollbar. Drawn here rather than by the widget pass, because it is chrome
        // rather than a widget -- though it *is* clickable, and `mouseClicked`/`mouseDragged` route its
        // drag through the same ScrollView. Every number it needs comes from the viewport, so the thumb
        // and the rows it describes come from one object.
        drawSidebarScrollbar(r);

        // The party strip, drawn here beside the sidebar's scrollbar because it is chrome rather than
        // content: it describes the player's own situation, not the questline on screen, so no chapter's
        // theme may reach it. Drawing it in the book's pass is what keeps it outside every scope.
        // The party button's tooltip, refreshed here, because that is where the roster's state goes now:
        // the label says "Party" and the tooltip says who is in it. A tooltip the drawing rebuilds cannot
        // go stale, which is the one thing a label set at construction can do.
        if (partyButton != null) {
            partyButton.tooltip(partyTooltip());
        }

        // The header's own inset, shared with Close's rectangle -- see `BookGeometry.HEADER_INSET`,
        // where the two ends of the header are one number rather than a literal here and a margin there.
        r.text(title.getString(), left + BookGeometry.HEADER_INSET, top + 9, ArmatureTheme.title());
        if (ClientQuestCache.hasData()) {
            String summary = ClientQuestCache.questCount() + " quests  \u00b7  "
                    + Math.round(viewport().scale() * 100) + "%";
            // Right-aligned to the Close button, not to the panel's edge. The old version measured
            // from `left + panelW - 12`, which is where the panel's own inset is — a position the
            // button knew nothing about. That is the same class of mistake as the two controls that
            // collided, one surface up, and it only needed the button to move.
            //
            // Dropped entirely when the header is too narrow to hold both, rather than overlapping the
            // title. A missing count is a smaller fault than a title with a number drawn through it.
            int summaryX = headerRightLimit() - r.textWidth(summary);
            if (summaryX > left + 10 + r.textWidth(title.getString()) + 12) {
                r.text(summary, summaryX, top + 9, ArmatureTheme.faint());
            }
        }

        if (!ClientQuestCache.hasData()) {
            // Two empty states. Saying "waiting" when it is really "nothing loaded" sends someone
            // hunting a sync bug that does not exist.
            Component message = Component.translatable(ClientQuestCache.hasTree()
                    ? "tasked.screen.quest_book.no_quests"
                    : "tasked.screen.quest_book.waiting");
            r.centredText(message.getString(), left + SIDEBAR_WIDTH + (panelW - SIDEBAR_WIDTH) / 2,
                    top + panelH / 2, ArmatureTheme.body());
            return;
        }

        String chapter = effectiveChapter();
        // Skipped while a modal is open, and that is belt as well as braces. The card is drawn in the
        // chrome layer now, so it is genuinely on top of the canvas -- but the canvas's item icons are
        // 3D renders that write depth, and a graph *behind* a modal is a distracting thing to see
        // moving under a scrim. The sidebar and the header stay, because those are what "have it in the
        // background" is about: where you are, not what you were looking at.
        if (chapter != null && overlay == Overlay.NONE) {
            drawCanvas(r, mouseX, mouseY, questsIn(chapter), now);
        }

        // The canvas is done, and the view cluster's backing panel is *not* drawn here any more.
        //
        // It used to be, with a `flush()` in front of it and a comment claiming the flush was what put
        // the panel over the nodes. That comment was wrong and the flush was not the fix: a node's icon
        // is at Z=150 and writes depth, so a panel drawn after it at Z=0 is rejected by the depth test
        // whatever the draw order is. The panel now lives in `render`, inside the raised chrome Z, which
        // is where the ordering it actually needs can be expressed. See CHROME_Z.
        //
        // The flush stays, because draining the canvas here is still right: it puts the nodes on screen
        // before the chrome layer is queued, so the two are in separate batches and nothing about the
        // chrome depends on how the canvas happened to batch.
        r.flush();
    }

    /**
     * Draws the hovered control's tooltip, last of all.
     *
     * <p>Not vanilla's tooltip: that would draw in vanilla's style, which is the thing this UI avoids.
     * Drawn here, after the widgets, so it is over everything and not clipped by the canvas scissor.
     */
    public void drawTooltips(GuiRenderer r, int mouseX, int mouseY) {
        // With an overlay open, the book's controls are drawn behind the scrim but must not answer a
        // hover -- see `bookButtonCount`. The overlay's own controls come after that index and are the
        // only ones whose tooltips belong on top of it.
        for (int i = overlay == Overlay.NONE ? 0 : bookButtonCount; i < buttons.size(); i++) {
            ArmatureButton button = buttons.get(i);
            if (button.tooltip() != null && button.isMouseOver(mouseX, mouseY)) {
                drawTooltip(r, button.tooltip(), mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * A themed tooltip box.
     *
     * <p>Hand-drawn for the same reason the buttons are: vanilla's renderer paints its own frame and
     * background, and a tooltip is the most visible piece of chrome a screen has. Flipped to the other
     * side of the pointer when it would run off the right or the bottom, which is the one piece of
     * behaviour worth copying from vanilla's positioner.
     *
     * <p>Lines are plain strings. They were {@code FormattedCharSequence} until the seam landed, which
     * is a shape that cannot cross it without dragging {@code Style} along — and every tooltip in both
     * mods is built from {@code Component.literal}, so there was no style to lose. See
     * {@code ArmatureButton.tooltip} for the same note on the other side of the call.
     */
    private void drawTooltip(GuiRenderer r, List<String> lines, int mouseX, int mouseY) {
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, r.textWidth(line));
        }

        int boxWidth = textWidth + 8;
        int boxHeight = lines.size() * r.lineHeight() + 6;
        int x = mouseX + 10;
        int y = mouseY - 11;
        if (x + boxWidth > width) {
            x = mouseX - boxWidth - 4;
        }
        if (y + boxHeight > height) {
            y = height - boxHeight - 2;
        }
        y = Math.max(2, y);

        ArmatureTheme.panel(r, x, y, boxWidth, boxHeight, ArmatureTheme.panel(),
                ArmatureTheme.controlEdgeBright());
        int lineY = y + 4;
        for (String line : lines) {
            r.text(line, x + 4, lineY, ArmatureTheme.body());
            lineY += r.lineHeight();
        }
    }

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

    private void drawCanvas(GuiRenderer r, int mouseX, int mouseY, List<ClientQuestCache.Entry> quests,
                            long now) {
        ClientQuestCache.Entry hovered;

        // Clipped to the canvas, so a node panned past the edge is cut off at the edge rather than
        // drawn over the sidebar. A scoped clip rather than a raw enable/disable pair: the pop is
        // placed by the compiler on every exit path, so the two calls cannot come apart -- and a
        // missing pop does not fail loudly, it leaves every later draw in the frame clipped to a
        // rectangle nobody chose.
        //
        // The scope is the seam's own type, so this file no longer knows what a scissor is. What that
        // bought beyond the port: `RecordingRenderer` can now assert that the clip was opened and
        // closed exactly once, which a screen cannot be asked without one.
        // Two scopes, and they are different kinds of thing. The clip is where the drawing is allowed
        // to land; the theme is what it is drawn in. Both are closed by the compiler on every exit path,
        // which is the property that matters for each — a clip left open cuts the rest of the frame off,
        // and a theme left open paints every later screen in one chapter's colours.
        //
        // The chapter's palette covers the canvas and everything on it: the backdrop, the connector
        // lines, the nodes, their rings, their washes. The sidebar beside it is chrome and keeps the
        // player's own theme — that is what lets a themed canvas and an ordinary sidebar be visible in
        // the same frame with no precedence rule between them.
        //
        // The node caption below is deliberately outside. It is a label floating over the canvas in the
        // same family as a tooltip, and a caption that changed colour with the chapter would read as
        // part of the node it names rather than as a label about it.
        try (GuiRenderer.Scoped clip = r.clip(canvasLeft(), canvasTop(), canvasRight(), canvasBottom());
             ArmatureTheme.Scope theme = ArmatureTheme.scope(viewportTheme())) {
            hovered = drawCanvasContents(r, mouseX, mouseY, quests, now);
        }

        // The hovered quest's name, drawn outside the clip so it is never cut off by the canvas edge.
        // A node is an icon and nothing else, so without this the canvas is a wall of unlabelled
        // squares until you click one.
        if (hovered != null) {
            drawNodeCaption(r, hovered);
        }
    }

    /** The canvas's own drawing, inside the clip. Returns the hovered node, for the caption above. */
    private ClientQuestCache.Entry drawCanvasContents(GuiRenderer r, int mouseX, int mouseY,
                                                      List<ClientQuestCache.Entry> quests, long now) {
        r.fill(canvasLeft(), canvasTop(), canvasRight(), canvasBottom(), ArmatureTheme.canvas());

        // Dependency lines first, so nodes draw over them.
        for (ClientQuestCache.Entry quest : quests) {
            for (String dependencyId : quest.dependencies()) {
                ClientQuestCache.Entry dependency = quests.stream()
                        .filter(candidate -> candidate.id().equals(dependencyId))
                        .findFirst()
                        .orElse(null);
                if (dependency == null) {
                    // A dependency in another chapter, or one filtered out. Not drawn: a line to
                    // nowhere reads as a rendering fault, and the overlay names the dependency.
                    continue;
                }
                boolean done = ClientQuestCache.stateOf(dependencyId) == QuestState.COMPLETED;
                drawConnector(r, dependency, quest,
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
            }
        }

        // A linear chapter's road. Drawn from the list order rather than from dependencies, because a
        // linear chapter declares none -- that is what makes it linear. Without this the client would
        // draw five unconnected boxes for a chapter that is unmistakably a sequence, which reads as a
        // missing feature rather than as a missing line.
        //
        // Consecutive pairs only, so the road has no shortcuts across it.
        if (!quests.isEmpty() && quests.get(0).chapterLinear()) {
            List<ClientQuestCache.Entry> ordered = quests.stream()
                    .sorted(java.util.Comparator.comparingInt(ClientQuestCache.Entry::orderInChapter))
                    .toList();
            for (int i = 1; i < ordered.size(); i++) {
                ClientQuestCache.Entry previous = ordered.get(i - 1);
                boolean done = ClientQuestCache.stateOf(previous.id()) == QuestState.COMPLETED;
                drawConnector(r, previous, ordered.get(i),
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
            }
        }

        ClientQuestCache.Entry hovered = nodeAt(mouseX, mouseY, quests);

        // Told which node the pointer is over, once, before any node is drawn. Then every node asks how
        // hovered it is -- which is what makes the ring ease in as the pointer arrives and ease out as
        // it leaves, and what makes a fast sweep across a chapter look like following the pointer
        // rather than like flicker. See Hover for why both halves have to ease.
        nodeHover.update(hovered == null ? null : hovered.id(), now);

        for (ClientQuestCache.Entry quest : quests) {
            drawNode(r, quest, nodeHover.amount(quest.id(), now));
        }
        // Titles in their own pass, after every node, so a label can see the other nodes -- see the
        // comment on drawLabels for what happened when it could not.
        drawLabels(r, quests);

        if (quests.isEmpty()) {
            r.text("No quests in this chapter", canvasLeft() + 10, canvasTop() + 10,
                    ArmatureTheme.faint());
        }

        // A hint, only until the player has zoomed. Then it would be clutter on a canvas they
        // demonstrably already know how to drive.
        //
        // In the canvas's top-right corner, and not the bottom-left. The bottom-left was where the
        // strip's own text lives, so the two were drawn on top of each other: the hint at y=917 and the
        // strip's first line at y=928, eleven pixels apart, which in the screenshot is a line of text
        // with another line of text through it. The top-right is empty — the view cluster is
        // top-*left*, and the header's quest count is a surface above this one.
        //
        // Behind a LABEL_BACKDROP, which is that colour's entire purpose: text drawn over a canvas that
        // may have a node underneath it. It composites to exactly the canvas colour, so it is invisible
        // except by what it prevents.
        if (Math.abs(viewport().scale() - 1.0F) < 0.001F) {
            String hint = "scroll to zoom  \u00b7  drag to pan  \u00b7  click a quest";
            int hintX = canvasRight() - 8 - r.textWidth(hint);
            int hintY = canvasTop() + 8;
            // Only when it clears the cluster. On a small window these two would meet, and a hint
            // overlapping the buttons it is describing is worse than no hint at all.
            if (hintX > viewControls().right() + 6) {
                r.fill(hintX - 3, hintY - 2, canvasRight() - 5, hintY + 10,
                        ArmatureTheme.labelBackdrop());
                r.text(hint, hintX, hintY, ArmatureTheme.faint());
            }
        }

        return hovered;
    }

    /** The hovered node's title, under the pointer. */
    private void drawNodeCaption(GuiRenderer r, ClientQuestCache.Entry entry) {
        int size = nodeSize(entry);
        int x = nodeScreenX(entry);
        int y = nodeScreenY(entry);

        String title = entry.title();
        int boxWidth = r.textWidth(title) + 10;
        int boxX = Mth.clamp(x + size / 2 - boxWidth / 2, canvasLeft() + 2, canvasRight() - boxWidth - 2);
        int boxY = y + size + 4;
        if (boxY + 14 > canvasBottom()) {
            boxY = y - 18;
        }

        ArmatureTheme.panel(r, boxX, boxY, boxWidth, 14, ArmatureTheme.panel(),
                ArmatureTheme.controlEdgeBright());
        r.text(title, boxX + 5, boxY + 3, ArmatureTheme.title());
    }

    // drawIcon(GuiGraphics, ...) used to be here, delegating to ArmatureTheme's copy. Both are gone:
    // the operation is `GuiRenderer.icon` now, so the screen calls it on the renderer it was handed
    // rather than on a helper, and there is no static method on either side to pass the wrong thing to.
    //
    // Worth keeping as a note because of what the indirection cost. This method existed only to forward
    // to another class's static helper, and that helper existed only because a colour table had been
    // asked to solve a rendering problem. Two layers of forwarding around one pose-stack manipulation,
    // and the manipulation is the only part that had anything to say.

    private void drawNode(GuiRenderer r, ClientQuestCache.Entry entry, float hover) {
        QuestState state = ClientQuestCache.stateOf(entry.id());
        int size = nodeSize(entry);
        int x = nodeScreenX(entry);
        int y = nodeScreenY(entry);

        int edge = switch (state) {
            case COMPLETED -> ArmatureTheme.complete();
            case STARTED -> ArmatureTheme.inProgress();
            case UNLOCKED -> ArmatureTheme.available();
            case LOCKED -> ArmatureTheme.nodeEdgeBlocked();
        };

        boolean isSelected = entry.id().equals(selectedQuest);
        ArmatureTheme.RowSpans spans = entry.shape()::span;

        // The hover and selection ring, drawn FIRST and one pixel larger, so the node's own panel
        // covers all but its outer edge. What shows is a one-pixel ring that follows the shape.
        //
        // It used to be `ArmatureTheme.outline(...)`, a rectangle drawn around a circle. On a round or
        // hexagonal node that is a box drawn round a disc -- which reads as two unrelated things
        // stacked, and it is the third thing in the screenshot that looks unfinished. Following the
        // shape is also what FTB Quests does, and for the same reason: the ring is the node saying
        // "this one", so it has to be the node's shape saying it.
        //
        // The hover ring's alpha is scaled by the eased hover, so it fades in and out rather than
        // appearing. `translucent` rather than `alphaOf`: HOVER_RING is already 0x80 alpha, and
        // `alphaOf` would discard that and make a fully-hovered ring twice as bright as it has always
        // been. Selection is not animated at all -- the row you are on is a state, not a transition.
        if (hover > 0F || isSelected) {
            int ring = isSelected
                    ? ArmatureTheme.selectedRing()
                    : Colour.translucent(ArmatureTheme.hoverRing(), hover);
            ArmatureTheme.shapePanel(r, x - 1, y - 1, size + 2, ArmatureTheme.nodeFill(), ring, spans);
        }

        // The node, in its own shape. A shape is a row-to-span lookup and nothing else, so the fill,
        // the border, the ring and the hit test all come from one place -- which is why a click lands
        // on exactly the pixels that were drawn and not on a bounding box around them.
        //
        // This draws a square for ROUNDED and a circle, hexagon or book for the others. Before, every
        // node was a square whatever the file said, because the shape never crossed the wire.
        ArmatureTheme.shapePanel(r, x, y, size, ArmatureTheme.nodeFill(), edge, spans);

        // The icon's corner and its size, from ONE inset -- `iconBox`, not two numbers here.
        //
        // The version that shipped took the size from `shape.iconInset(size)` and the position from the
        // constant `NODE_INSET`, so a 36-pixel item was drawn 3 pixels in from the corner instead of 6:
        // off centre in both axes, with its corner through the rounded outline. Same mistake as the
        // colliding buttons and the label and its room -- one value, two places -- and the fix is the
        // same: compute the pair together, somewhere a caller cannot take one and invent the other.
        // The share of the node comes from the quest file, so the size is the quest's decision and the
        // placement is still the shape's. `iconBox` keeps the two together -- see its javadoc for why
        // splitting them is what put an item through the outline the first time.
        int[] iconBox = entry.shape().iconBox(x, y, size, entry.iconScale());
        boolean drewItem = iconBox[2] >= MIN_ITEM_BOX
                && r.icon(entry.icon(), iconBox[0], iconBox[1], iconBox[2]);

        if (!drewItem) {
            // No icon, or one the client cannot resolve, or a node too small to hold one. A block in the
            // state colour still reads as a node in a graph, where an empty one reads as a bug -- and it
            // follows the shape, so a small circle is a small circle rather than a square inside it.
            int inset = Math.max(1, size / 4);
            ArmatureTheme.fillShape(r, x + inset, y + inset, size - inset * 2,
                    (edge & 0x00FFFFFF) | 0xB0000000, spans);
        }

        // The state, as a wash over the node. It used to be a chip with a ✖ in the node's bottom-right
        // corner, and at node scale that chip was a black square pasted over the artwork. Dimming what
        // is already there says "not yet" without hiding what the quest is, which is the only reason
        // the icon is here.
        //
        // The wash FOLLOWS THE SHAPE, and that is the whole point of drawing it here rather than with a
        // `fill` rectangle over the icon's box. A rectangle over a circular node is a black square on a
        // round thing -- the second fault in the screenshot, and the one that reads as a rendering
        // glitch rather than as a style. Inset by one so the state-coloured border stays crisp; the
        // item is inside this and is dimmed by it, which is intended.
        int wash = switch (state) {
            case LOCKED -> ArmatureTheme.nodeDim();
            case COMPLETED -> ArmatureTheme.nodeDoneWash();
            case STARTED, UNLOCKED -> 0;
        };
        if (wash != 0) {
            // Drawn after the item, which is safe: every fill in GuiGraphics ends by flushing the buffer
            // (fill -> flushIfUnmanaged -> flush -> bufferSource.endBatch), so the item is submitted
            // first and the wash lands on top of it. Verified in Stage 4 rather than assumed -- an
            // overlay that draws *under* the thing it overlays is invisible, which is a bug that looks
            // like the overlay was never called.
            //
            // The spans are looked up at `size - 2`, because that is the size this call passes -- a
            // shape is a function of (row, size), not a fixed table, so the same method reference gives
            // the smaller outline for free. That is the design paying for itself.
            ArmatureTheme.fillShape(r, x + 1, y + 1, size - 2, wash, spans);
        }
    }

    /**
     * Node titles, drawn once after every node.
     *
     * <p>A separate pass rather than part of {@link #drawNode}, because a label needs to know about the
     * <i>other</i> nodes: how much room there is beside it, and whether the space under it is occupied.
     * Drawing labels inside the node loop is what produced the garbled text in the screenshot — each
     * label knew only about its own node, so three of them were drawn straight through each other.
     */
    private void drawLabels(GuiRenderer r, List<ClientQuestCache.Entry> quests) {
        // Only the quests that asked for a name. The rest are named on hover, which the caption below
        // the canvas does for every node -- so nothing is unreachable, and the canvas is not a wall of
        // text.
        //
        // The room is measured from *these* nodes, not from every node, which is a real difference:
        // a named quest next to an unnamed one has the whole gap to itself, because the unnamed one
        // draws nothing there to collide with.
        List<ClientQuestCache.Entry> named = quests.stream()
                .filter(ClientQuestCache.Entry::showTitle)
                .toList();
        if (named.isEmpty()) {
            return;
        }

        int room = labelRoom(named);
        if (room < MIN_LABEL_WIDTH) {
            // Not enough room for a readable label anywhere in this chapter, so none are drawn and the
            // hover caption carries the name. Drawing them anyway is what "Punch a SomewherStone To…"
            // was: three titles interleaved, which reads as a corrupt string rather than as crowding.
            return;
        }

        for (ClientQuestCache.Entry entry : named) {
            int size = nodeSize(entry);
            int x = nodeScreenX(entry);
            int y = nodeScreenY(entry);

            String shown = Measure.truncate(entry.title(), room, textMeasure(r));
            int width = r.textWidth(shown);
            // Clamped inward so a label on the edge node is not half off the canvas, but never so far
            // that it slides away from the node it belongs to.
            int textX = Mth.clamp(x + size / 2 - width / 2, canvasLeft() + 2, canvasRight() - width - 2);
            int textY = y + size + LABEL_GAP;

            // Checked against **every** node, not just the named ones, and the difference is real.
            //
            // `named` is the right list to *measure the room* from -- an unnamed quest draws nothing
            // between two nodes, so it cannot crowd a label. It is the wrong list to test a collision
            // against: a label drawn over an unnamed quest's icon is just as unreadable as one drawn
            // over a named node, and the unnamed node is still there on the screen. Passing `named`
            // here was a regression introduced with the default, and it would have shown up as a label
            // sitting across a neighbour's icon in exactly the chapters that opt in to names.
            if (textY + 9 > canvasBottom() || overlapsAnotherNode(quests, entry, textX, textY, width)) {
                // A label drawn over the node below it, or out of the canvas, is worse than no label.
                continue;
            }

            QuestState state = ClientQuestCache.stateOf(entry.id());
            int textColour = switch (state) {
                case LOCKED -> ArmatureTheme.blocked();
                case COMPLETED -> ArmatureTheme.complete();
                default -> entry.id().equals(selectedQuest) ? ArmatureTheme.title() : ArmatureTheme.body();
            };

            // A backdrop, so a label sitting over a connector line is still readable. Opaque rather
            // than shadowed: a shadow does not help against a line of similar brightness.
            r.fill(textX - 2, textY - 1, textX + width + 2, textY + 9, ArmatureTheme.labelBackdrop());
            r.text(shown, textX, textY, textColour);
        }
    }

    /**
     * The narrowest horizontal gap between node columns, in screen pixels, less a margin.
     *
     * <p>Measured from the nodes that are actually on screen rather than fixed, because the answer
     * depends on the chapter's authored spacing and on the zoom — and a fixed answer is what let three
     * labels overlap. One column means no neighbour to crowd, so the cap applies.
     */
    private int labelRoom(List<ClientQuestCache.Entry> quests) {
        // The arithmetic is BookGeometry's, which BookGeometryTest asserts the invariant of: the room
        // is strictly less than the gap, so two labels truncated to it cannot touch. This method only
        // does the part that needs the screen — turning nodes into their screen columns.
        //
        // `distinct` matters: two quests on the same column, which the index warns about, would
        // otherwise contribute a gap of 0 and crush every label in the chapter to nothing.
        List<Integer> columns = quests.stream().map(this::nodeScreenX).distinct().toList();
        return BookGeometry.labelRoom(columns, LABEL_GAP, MAX_LABEL_WIDTH);
    }

    /** Whether a label's box would be drawn over a node that is not the one it belongs to. */
    private boolean overlapsAnotherNode(List<ClientQuestCache.Entry> quests, ClientQuestCache.Entry owner,
                                        int textX, int textY, int width) {
        for (ClientQuestCache.Entry other : quests) {
            if (other.id().equals(owner.id())) {
                continue;
            }
            int size = nodeSize(other);
            int x = nodeScreenX(other);
            int y = nodeScreenY(other);
            if (textX < x + size && textX + width > x && textY < y + size && textY + 9 > y) {
                return true;
            }
        }
        return false;
    }

    // trimToWidth(Font, String, int) used to live here, and it is now `Measure.truncate`.
    //
    // The move is worth a note because of what it was before *that*: the version before this one
    // divided the node size by the zoom and used the result as a character count, which has nothing to
    // do with how wide text is. Not imprecise -- the wrong kind of quantity. Then it became a wrapper
    // around `Font.plainSubstrByWidth`, which was right and lived in a screen, so a control that needed
    // the same thing grew its own copy.
    //
    // It is in the kit now, beside `TextWrap`, where both rules that answer "what fits in this width"
    // sit together -- one breaking a paragraph, one cutting a line. Neither needs a renderer: both take
    // a `Measure`, and `Measure.of(renderer::textWidth, renderer.lineHeight())` is how a caller with a
    // renderer gets one.

    /**
     * A connector from one node to another.
     *
     * <p>A step function rather than a diagonal. {@code GuiGraphics} has no line drawing, so a diagonal
     * has to be approximated by many single-pixel fills — and at node scale a staircase reads as a
     * mistake rather than as a line. Axis-aligned fills look deliberate and cost three calls.
     */
    private void drawConnector(GuiRenderer r, ClientQuestCache.Entry from,
                               ClientQuestCache.Entry to, int colour) {
        int ax = nodeScreenX(from) + nodeSize(from) / 2;
        int ay = nodeScreenY(from) + nodeSize(from) / 2;
        int bx = nodeScreenX(to) + nodeSize(to) / 2;
        int by = nodeScreenY(to) + nodeSize(to) / 2;

        if (ax == bx) {
            r.fill(ax, Math.min(ay, by), ax + 1, Math.max(ay, by), colour);
            return;
        }
        if (ay == by) {
            r.fill(Math.min(ax, bx), ay, Math.max(ax, bx), ay + 1, colour);
            return;
        }

        // Vertical out of the source, across, then vertical into the target. Vertical-first because a
        // quest chain runs left to right: a short vertical stub reads as a branch, where a long
        // horizontal run would pass through a neighbouring node's space.
        int midY = ay + (by - ay) / 2;
        r.fill(ax, Math.min(ay, midY), ax + 1, Math.max(ay, midY), colour);
        r.fill(Math.min(ax, bx), midY, Math.max(ax, bx), midY + 1, colour);
        r.fill(bx, Math.min(midY, by), bx + 1, Math.max(midY, by), colour);
    }

    /** The node under the pointer, or null. */
    private ClientQuestCache.Entry nodeAt(double mouseX, double mouseY, List<ClientQuestCache.Entry> quests) {
        // Reverse order, so the node drawn last -- and therefore on top -- is the one picked. Two
        // quests may legitimately share a position, and the validator warns about it.
        for (int i = quests.size() - 1; i >= 0; i--) {
            ClientQuestCache.Entry entry = quests.get(i);
            int size = nodeSize(entry);
            // The shape's own test, not a bounding box. A circle's corners are outside it, so a
            // bounding-box hit test would let a click land on a node's transparent corner -- and with
            // two diagonal neighbours 34 pixels apart, that click belongs to neither of them.
            //
            // `contains` asks the same span table the renderer fills from, so a click lands on exactly
            // the pixels that were drawn. That is the whole reason the geometry lives on the enum.
            if (entry.shape().contains(mouseX, mouseY, nodeScreenX(entry), nodeScreenY(entry), size)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The overlay
    // ------------------------------------------------------------------

    /**
     * The full-screen quest view.
     *
     * <p>Laid out as a fixed header, a scrolling body and a fixed footer, because the body can be
     * arbitrarily long and the controls that close it must not scroll away. The body's clipping rect is
     * what makes that work: a clip around the body cuts a long description off at the footer rather
     * than letting it draw over the controls, and being scoped it cannot be left open by an early
     * return.
     */
    /**
     * Draws whichever modal is open, in the raised-Z chrome layer.
     *
     * <h2>Why this is not called from `renderWith`</h2>
     *
     * <p>Because a modal is chrome and `renderWith` draws the book. The card has to be over the widget
     * pass -- the sidebar's rows are widgets and they stay built behind the scrim -- and over the quest
     * canvas's item icons, which are 3D renders that translate to Z = 150 and write depth. A card drawn
     * at Z = 0 before either of them loses to both, whatever the draw order, which is exactly the fault
     * the view cluster's backing panel had and the reason `CHROME_Z` exists.
     *
     * <p>So this is the one drawing call that belongs beside Close and the party button rather than
     * beside the book, and the scrim stays in `renderWith` because the scrim is about the *book* being
     * inert rather than about the card being present.
     */
    private void drawModal(GuiRenderer r, int mouseX, int mouseY, long now) {
        if (overlay == Overlay.PARTY) {
            drawPartyOverlay(r, mouseX, mouseY, now);
        }
        else if (overlay == Overlay.QUEST) {
            // The quest overlay is content: it describes the quest you opened, so it is drawn in the
            // palette that quest belongs to rather than in the chrome's. The controls stay chrome and
            // are drawn by the widget pass, which is outside this scope -- so the button that leaves a
            // chapter cannot be recoloured by the chapter. That is the property the feature rests on.
            try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(viewportTheme())) {
                drawOverlay(r, mouseX, mouseY, now);
            }
        }
    }

    private void drawOverlay(GuiRenderer r, int mouseX, int mouseY, long now) {
        ClientQuestCache.Entry entry = entryFor(overlayQuest);
        if (entry == null) {
            return;
        }

        int left = overlayLeft();
        int top = overlayTop();
        int w = overlayWidth();
        int h = overlayHeight();

        ArmatureTheme.panel(r, left, top, w, h, ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        // The overlay's header strip, rounded on its two top corners to match the panel -- see
        // `drawBook` for why the mask is explicit and why the radius is one tighter than the panel's.
        // This one spans the overlay's full width, so it is the one surface in this screen whose two top
        // corners are both its own.
        ArmatureTheme.fillSurface(r, left + 1, top + 1, w - 2, 45, ArmatureTheme.raised(),
                Math.max(0, ArmatureTheme.current().cornerRadius() - 1), ArmatureTheme.CORNERS_TOP);

        // And the two rules. Square, deliberately: they are one pixel tall, and a radius applied to a
        // one-pixel rule would either vanish or produce a dotted line.
        r.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.panelEdge());
        // The footer band is exactly the region the card's own chrome reserves for the footer -- the
        // gap above the controls, the controls, and the inset below them -- so the band and the row it
        // holds come from one arithmetic. It was 38, an eighth number that agreed with none of the
        // three, and the report was the control sitting three pixels high in its own band.
        r.fill(left + 1, top + h - BookGeometry.MODAL_FOOTER_HEIGHT, left + w - 1,
                top + h - BookGeometry.MODAL_FOOTER_HEIGHT + 1, ArmatureTheme.panelEdge());

        // --- header ---

        QuestState state = ClientQuestCache.stateOf(entry.id());
        // The header icon is drawn to its own box, and the text starts after that box, so the two are
        // the same layout decision. The title used to start at a hardcoded left+38 with a 16px icon at
        // left+14, which is 8px of gap -- close enough to look intentional and not derived from
        // anything, so moving the icon would have moved the text by accident.
        int iconBox = HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        r.icon(entry.icon(), iconX, iconY, iconBox);

        int textX = iconX + iconBox + 6;
        r.text(entry.title(), textX, top + 12, ArmatureTheme.title());
        r.text(stateLabel(state), textX + r.textWidth(entry.title()) + 10, top + 12,
                stateColour(state));

        String where = entry.chapterTitle() + (entry.subtitle().isEmpty() ? "" : "  \u00b7  " + entry.subtitle());
        r.text(where, textX, top + 26, ArmatureTheme.faint());

        // --- body: one layout, one clip, one scroll ---

        // Bounds first, then the layout at that width, then the scroll range from the layout's own
        // height. The order matters: apply() clamps the offset against the view's height, so clamping
        // against a stale bounds would clamp to the previous window's body.
        Viewport body = overlayBody();
        Measure measure = textMeasure(r);
        Layout layout = overlayLayout(r, entry);
        overlayView.apply(layout, body.viewWidth());

        // Which row the pointer is over, resolved once for the whole body before anything is drawn.
        // `update` is called exactly once per frame rather than once per row: a pointer is a single
        // point, so there is at most one hovered row and asking per row would be the same answer
        // computed three times while never being told that the answer is "none".
        rowHover.update(rowAt(layout, body, rowKeysOf(entry), mouseX, mouseY), now);

        // The clip, the scroll clamp and the scrollbar all read this one rectangle. They used to be
        // three expressions for it — bodyLeft/bodyRight/bodyTop/bodyBottom written out in the drawing
        // and again in the clamp — which is the same class of mistake as the two controls that were
        // once drawn on top of each other.
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            drawDescription(r, entry, layout, body, measure);
            drawTasks(r, entry, layout, body, now);
            drawRewards(r, entry, layout, body, now);
            drawDependencies(r, entry, layout, body);
        }

        // The bar, and it draws nothing when the content fits. Its geometry comes from the same
        // viewport as the clip and the clamp, so a thumb that stops short of the end is not a thing
        // that can happen here.
        // The theme's own scrollbar tokens, for the same reason the sidebar's uses them: the overlay's
        // bar was drawn from `recessed` and `controlEdge`, neither of which was chosen for the job.
        overlayView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /**
     * The body's elements, in order, at the width the body actually has.
     *
     * <p>The one call that decides both where every row goes and how tall the content is. That is the
     * property this round was about: {@code measureOverlay} was a second pass over the same fields,
     * kept in step with the drawing by a comment.
     */
    private Layout overlayLayout(GuiRenderer r, ClientQuestCache.Entry entry) {
        Viewport body = overlayBody();
        return OverlayLayout.stack(entry.description(), entry.tasks().size(),
                entry.rewards().size(), entry.dependencies().size())
                .build(body.viewWidth(), textMeasure(r));
    }

    /**
     * The prose, at the slot the layout placed it in.
     *
     * <p>Wrapped again here, with the same {@link Measure} and the same column width the layout wrapped
     * it with. That is not a second description of the wrap rule — it is the same pure function called
     * with the same arguments, which is the property {@code TextWrap.height} exists to guarantee.
     *
     * <p>The width is the <b>body's</b>, not the slot's: a left-aligned text slot is as wide as its
     * longest line, so wrapping to it would narrow the paragraph a little more on every pass.
     */
    private void drawDescription(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout,
                                 Viewport body, Measure measure) {
        if (entry.description().isEmpty()) {
            Slot slot = placed(layout, body, OverlayLayout.NO_DESCRIPTION);
            if (slot != null) {
                r.text("No description.", slot.x(), slot.y(), ArmatureTheme.faint());
            }
            return;
        }

        for (int i = 0; i < entry.description().size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.proseKey(i));
            if (slot == null) {
                continue;
            }
            int lineY = slot.y();
            for (String line : TextWrap.wrap(entry.description().get(i), body.viewWidth(), measure)) {
                r.text(line, slot.x(), lineY, ArmatureTheme.body());
                lineY += measure.lineHeight();
            }
        }
    }

    private void drawTasks(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout, Viewport body,
                           long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.TASKS_HEADING), "TASKS");

        if (entry.tasks().isEmpty()) {
            Slot slot = placed(layout, body, OverlayLayout.NO_TASKS);
            if (slot != null) {
                r.text("Nothing required", slot.x(), slot.y(), ArmatureTheme.faint());
            }
            return;
        }
        for (int i = 0; i < entry.tasks().size(); i++) {
            String key = OverlayLayout.taskKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                drawTaskRow(r, entry, i, slot, rowHover.amount(key, now));
            }
        }
    }

    /**
     * Every key a row of the body could be drawn under, in reading order.
     *
     * <p>Built here rather than asked of {@link OverlayLayout}, because the layout's business is where
     * things go and the hover tracker's is which one the pointer is over — and a method on the layout
     * that listed its own keys would be a second place the key naming has to be kept in step with
     * {@code stack}'s. It is not: both call the same key generators.
     */
    private static List<String> rowKeysOf(ClientQuestCache.Entry entry) {
        List<String> keys = new ArrayList<>(entry.tasks().size() + entry.rewards().size());
        for (int i = 0; i < entry.tasks().size(); i++) {
            keys.add(OverlayLayout.taskKey(i));
        }
        for (int i = 0; i < entry.rewards().size(); i++) {
            keys.add(OverlayLayout.rewardKey(i));
        }
        return keys;
    }

    /**
     * The key of the row under the pointer, or null.
     *
     * <p>Asks each key's <b>placed slot</b> rather than recomputing a rectangle, which is the same
     * principle the whole drawing pass is built on: the row a click would land on and the row that
     * lights up come from one description of where a row is. A second piece of arithmetic here would
     * agree with the layout almost everywhere, and the place it disagreed would be a row that
     * highlights but does not respond.
     *
     * <p>Uses {@link Slot#contains}, so a row scrolled out of the view is null from {@code placed} and
     * is therefore unhoverable — which is right, since it is not on screen to point at.
     */
    private static String rowAt(Layout layout, Viewport body, List<String> keys,
                                double mouseX, double mouseY) {
        for (String key : keys) {
            Slot slot = placed(layout, body, key);
            if (slot != null && rowBox(slot).contains(mouseX, mouseY)) {
                return key;
            }
        }
        return null;
    }

    /**
     * The row's own box inside its slot: the icon's height, at the slot's top.
     *
     * <h2>Because a slot is an advance, not a row</h2>
     *
     * <p>{@code OverlayLayout} places a task or a reward as {@code ROW_ADVANCE} tall — the row, plus the
     * space under it before the next one starts — so a hit test against the slot whole reaches six pixels
     * into the gap below the row and none of the gap above it. That is the report exactly: <i>"I hold
     * under a task and it glows"</i>. Every row draws its content from the slot's top edge, so the box a
     * pointer should find is the top {@code ROW_ICON} of it, and the wash and the test both come from
     * here rather than from two readings of one slot.
     */
    private static Slot rowBox(Slot slot) {
        return new Slot(slot.key(), slot.x(), slot.y(), slot.width(),
                Math.min(ROW_ICON, slot.height()));
    }

    private void drawRewards(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout, Viewport body,
                             long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.REWARDS_HEADING), "REWARDS");

        if (entry.rewards().isEmpty()) {
            Slot slot = placed(layout, body, OverlayLayout.NO_REWARDS);
            if (slot != null) {
                r.text("Nothing", slot.x(), slot.y(), ArmatureTheme.faint());
            }
            return;
        }
        for (int i = 0; i < entry.rewards().size(); i++) {
            String key = OverlayLayout.rewardKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                drawRewardRow(r, entry.rewards().get(i), slot, rowHover.amount(key, now));
            }
        }
    }

    private void drawDependencies(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout,
                                  Viewport body) {
        if (entry.dependencies().isEmpty()) {
            // No REQUIRES section at all, rather than one saying nothing. The layout omits it for the
            // same reason, so there is no heading to draw and no room reserved for one.
            return;
        }
        drawHeading(r, placed(layout, body, OverlayLayout.REQUIRES_HEADING), "REQUIRES");

        for (int i = 0; i < entry.dependencies().size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.dependencyKey(i));
            if (slot == null) {
                continue;
            }
            String dependency = entry.dependencies().get(i);
            ClientQuestCache.Entry other = entryFor(dependency);
            boolean met = ClientQuestCache.stateOf(dependency) == QuestState.COMPLETED;
            String label = other != null ? other.title() : dependency;
            r.text((met ? "\u2714" : "\u2716") + "  " + label, slot.x(), slot.y(),
                    met ? ArmatureTheme.complete() : ArmatureTheme.blocked());
        }
    }

    /** A section label and its rule, at the slot the layout reserved for it. */
    private void drawHeading(GuiRenderer r, Slot slot, String label) {
        if (slot == null) {
            return;
        }
        r.text(label, slot.x(), slot.y(), ArmatureTheme.heading());
        r.fill(slot.x(), slot.y() + OverlayLayout.SECTION_LEAD, slot.x() + r.textWidth(label),
                slot.y() + OverlayLayout.SECTION_LEAD + 1, ArmatureTheme.panelEdge());
    }

    /**
     * A key's slot, moved onto the screen, or null if it was not placed or is scrolled out of view.
     *
     * <p>Null for two different reasons, and a caller treats them the same: an element this layout did
     * not place at all — a REQUIRES heading on a quest with no prerequisites — and one that is off the
     * screen right now. Both mean "do not draw it here".
     *
     * <p>This is the whole of the culling, and it is why the drawing can be written against keys: the
     * dispatch is on what an element <i>is</i>, so a row that moves when a metric changes takes its
     * drawing with it. Matching on coordinates is the version of this that leaves a working test naming
     * the wrong row.
     */
    private static Slot placed(Layout layout, Viewport body, Object key) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return null;
        }
        // Wholly off screen: above the top of the view or below its bottom. A slot that is only partly
        // on screen is drawn and cut off by the clip, which is what the clip is for.
        if (slot.bottom() <= body.visibleTop() || slot.y() >= body.visibleBottom()) {
            return null;
        }
        // Moved by the viewport's own mapping, so the placement and the culling cannot disagree about
        // where a row is. The overlay's viewport is fixed-scale, so a slot's width is its screen width.
        return slot.moved(body.screenX(slot.x()) - slot.x(), body.screenY(slot.y()) - slot.y());
    }

    /**
     * The font as the overlay's layout measures it: the same face, at the line box this pane draws.
     *
     * <h2>What this used to be, and why the change matters</h2>
     *
     * <p>It was {@code FontMeasure.of(font).withLeading(...)} — a kit class whose whole job was to wrap
     * a Minecraft {@code Font} in a {@code Measure}. That class is gone, and the reason is the
     * clearest illustration of what the seam bought: a renderer <i>is</i> a measure of text. It answers
     * {@code textWidth} and {@code lineHeight} already, so the adapter that existed only to bridge a
     * font to the layout interface has nothing left to bridge.
     *
     * <p>The leading is still added, and still belongs inside the measure rather than at the drawing
     * site: this pane draws a line of body text every {@link OverlayLayout#LINE_HEIGHT} pixels, and a
     * layout measuring at the font's own height over a drawing advancing by ten is short by a line per
     * paragraph — which arrives as a scrollbar that stops early with nothing anywhere reporting it.
     */
    private static Measure textMeasure(GuiRenderer r) {
        return Measure.of(r::textWidth, OverlayLayout.LINE_HEIGHT);
    }

    /**
     * The wash behind a hovered row.
     *
     * <h2>Why it hugs the content rather than the row</h2>
     *
     * <p>{@code slot} is stretched to the width of the body, because that is what a row of a stack is
     * — and that makes a wash out to {@code slot.right()} a bar the <b>full width of the panel</b>.
     * On a wide window that is a highlight clear across the screen for a row that draws an icon and
     * two words, which reads as the row being selected rather than as a hint about where a click
     * lands. It looked like an overflow, and it was one: the wash was drawn to the row's box, and the
     * row's box is not what the row <i>draws</i>.
     *
     * <p>So it runs from the row's left edge to just past the furthest thing the row actually puts on
     * screen. {@code contentRight} is that edge, measured by the caller before it draws anything —
     * see {@code drawTaskRow} for why the measurement has to come first.
     *
     * <h2>Why it is not the full row height either</h2>
     *
     * <p>{@code slot} is {@link OverlayLayout#ROW_ADVANCE} tall — the icon's box <b>plus the gap under
     * it</b> — so washing the whole slot puts the highlight two pixels into the row below and leaves
     * it sitting low under the icon. This brackets the icon instead: symmetric about it, and unable to
     * reach a neighbour even during a crossfade where two washes are on screen at once.
     */
    private static void rowWash(GuiRenderer r, Slot slot, int contentRight, float hover) {
        if (hover <= 0F) {
            return;
        }
        int right = Math.min(slot.right() + 3, contentRight + 6);
        r.fill(slot.x() - 3, slot.y() - 2, right, slot.y() + ROW_ICON + 2,
                Colour.translucent(ArmatureTheme.rowHover(), hover));
    }

    /**
     * One task, with a highlight that eases in and out as the pointer arrives and leaves.
     *
     * <h2>Why a row highlights at all</h2>
     *
     * <p>Because the row is the thing a click lands on. A task in the list is not a button — there is
     * nothing to press on most of them — but it is the anchor for <b>Hand over</b>, and without any
     * feedback the only way to know which task that button refers to is to click it and see. The
     * highlight is the row saying "this one", which is the same job the hover ring does on a node.
     *
     * <p>It is a wash rather than a fill, and at low alpha. A task row that brightened as much as a
     * button would compete with the quest's actual selection, and the row is a hint rather than a
     * choice.
     *
     * <h2>Everything is measured before anything is drawn</h2>
     *
     * <p>Not a style choice: the wash has to be <b>behind</b> the icon, so it cannot be drawn after the
     * row is painted — which means the width it needs must be known before the row exists on screen.
     * The measurement block below therefore computes every string and x-position the row will use, and
     * the drawing block consumes those same values rather than recomputing them. Two descriptions of
     * one extent is the failure this codebase keeps paying for, and the highlight is exactly the kind
     * of thing where the two would drift unnoticed.
     *
     * <p>One value is assumed rather than known: the text's indent. Whether an item resolves is only
     * answerable by asking the renderer to draw it, and the wash has to come first — so the measurement
     * uses the indented position, which is the wider case. A row whose icon cannot be resolved gets a
     * wash a couple of characters wider than its text, which is invisible.
     *
     * @param slot the placed row, so the highlight is anchored to the box the layout reserved — and so
     *     the box the hover test asks about is the box the highlight is drawn from
     * @param hover 0 at rest, 1 fully hovered, eased. See {@link Hover}
     */
    private void drawTaskRow(GuiRenderer r, ClientQuestCache.Entry entry, int index, Slot slot,
                             float hover) {
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        int progress = ClientQuestCache.taskProgressOf(entry.id(), index);
        boolean satisfied = progress >= task.count();

        int x = slot.x();
        int y = slot.y();
        int availableWidth = slot.width();

        // The text sits on the centre line of the icon's box, and the box is ROW_ICON square. Before,
        // the text was on the row's top edge while the icon was drawn 4px above it at 16px tall, so
        // neither lined up with the other and the icon bled into the row above.
        int textY = y + (ROW_ICON - 8) / 2;

        // --- measured, so the wash can be drawn behind the row at the width it will occupy ---

        String text = task.text().getString();
        int measuredTextX = x + ROW_ICON + 5;
        int measuredTextRight = measuredTextX + r.textWidth(text);

        String count = task.count() > 1
                ? Math.min(progress, task.count()) + " / " + task.count()
                : null;
        int barX = count == null
                ? measuredTextRight + 8
                : measuredTextRight + 8 + r.textWidth(count) + 8;
        int barWidth = count == null ? 0 : Mth.clamp(availableWidth - (barX - x) - 70, 24, 120);

        // Both tags are right-aligned to the row, which is the one case where the content really does
        // reach the row's far edge -- so a task that can be handed in has a highlight that spans it,
        // and that is correct rather than a leftover of the old full-width wash.
        boolean optional = task.optional();
        boolean manual = task.manual();
        String tag = manual ? "hand in" : (optional ? "optional" : null);
        int tagX = tag == null ? 0 : x + availableWidth - r.textWidth(tag)
                - (manual && optional ? r.textWidth("optional") + 6 : 0);

        // Who is contributing, right-aligned before the tag. Placed here rather than down at the
        // drawing, because the wash behind the row reaches the same edge -- two expressions of "where
        // the faces end" that happen to agree today is the fault this file's geometry exists against.
        int facesRight = tag == null ? x + availableWidth : tagX - 6;
        List<Contributor> contributors = placeContributors(r, entry.id(), index, facesRight,
                barX + barWidth + 8);

        int contentRight = Math.max(measuredTextRight, barX + barWidth);
        if (tag != null) {
            contentRight = Math.max(contentRight, tagX + r.textWidth(tag));
        }
        if (!contributors.isEmpty()) {
            contentRight = Math.max(contentRight, facesRight);
        }

        // The row's own box rather than its slot: see `rowBox` for the six pixels of gap between them.
        rowWash(r, rowBox(slot), contentRight, hover);

        // --- drawn ---

        int textX = x;
        ItemStack toDraw = task.hasItem() ? task.item() : task.icon();
        if (r.icon(toDraw, x, y, ROW_ICON)) {
            // Only indent the text when something was actually drawn, so a task whose item the client
            // cannot resolve is not left with a gap where an icon should be.
            textX = x + ROW_ICON + 5;
        }

        int colour = satisfied ? ArmatureTheme.complete()
                : ClientQuestCache.stateOf(entry.id()) == QuestState.LOCKED ? ArmatureTheme.blocked()
                : ArmatureTheme.body();
        r.text(text, textX, textY, colour);

        int after = textX + r.textWidth(text) + 8;

        // The progress, and a bar for it. The bar is what makes "5 / 8" readable at a glance rather
        // than something you have to stop and parse.
        if (count != null) {
            r.text(count, after, textY, satisfied ? ArmatureTheme.complete() : ArmatureTheme.faint());
            after += r.textWidth(count) + 8;

            int barY = textY + 1;
            int filled = Math.round(barWidth * Math.min(1F, progress / (float) task.count()));
            r.fill(after, barY, after + barWidth, barY + 6, ArmatureTheme.recessed());
            ArmatureTheme.outline(r, after, barY, barWidth, 6, ArmatureTheme.panelEdge());
            if (filled > 0) {
                r.fill(after + 1, barY + 1, after + Math.max(2, filled), barY + 5,
                        satisfied ? ArmatureTheme.complete() : ArmatureTheme.available());
            }
        }

        // The contributors, from the list placed above: each member's face and what they hold. The face
        // is the part that answers "who", and the number beside it is what makes the answer worth
        // reading -- a party's total says how the quest is going, this says who is doing it.
        //
        // Drawn for a finished task as well, on purpose: the picture is frozen at the evaluation that
        // completed the task, so a done row says who did it. See `ProgressService.CONTRIBUTIONS`.
        for (Contributor contributor : contributors) {
            r.face(contributor.who(), contributor.x(), y + (ROW_ICON - CONTRIBUTOR_FACE) / 2,
                    CONTRIBUTOR_FACE);
            r.text(contributor.count(), contributor.countX(), textY + CONTRIBUTOR_TEXT_DROP,
                    ArmatureTheme.faint());
        }

        if (optional) {
            r.text("optional", x + availableWidth - r.textWidth("optional"), textY, ArmatureTheme.faint());
        }
        if (manual) {
            r.text("hand in", tagX, textY, ArmatureTheme.available());
        }
    }

    /** One member's mark in a task row: their face, what they hold, and where both go. */
    private record Contributor(UUID who, String count, int x, int countX) {
    }

    /**
     * The contributing members a task row can show, placed right-aligned from {@code right}.
     *
     * <h2>Measured once, drawn from the result</h2>
     *
     * <p>The alternative is measuring the cluster to decide the wash's width and then re-walking the
     * members to draw them, which is two expressions of one layout -- and the second one is the one
     * that would drift. So this returns positions and the drawing reads them.
     *
     * <h2>Empty rather than squeezed</h2>
     *
     * <p>A row with no room for faces draws none: a narrow overlay, or a long task name. Returning
     * nothing is the honest answer where overlapping the name would be a fault, and the caller has one
     * thing to check instead of a special case per reason.
     */
    private static List<Contributor> placeContributors(GuiRenderer r, String questId, int taskIndex,
                                                       int right, int leftLimit) {
        Map<UUID, Integer> picture = ClientQuestCache.contributorsOf(questId, taskIndex);
        if (picture.isEmpty()) {
            return List.of();
        }

        List<Contributor> placed = new ArrayList<>();
        int x = right;
        int shown = 0;
        for (Map.Entry<UUID, Integer> each : picture.entrySet()) {
            String count = String.valueOf(each.getValue());
            if (shown == MAX_CONTRIBUTORS) {
                // The rest as a count of their own, so a party of eight does not push the row's own
                // text off it -- and so the row still says that somebody is missing from it.
                count = "+" + (picture.size() - MAX_CONTRIBUTORS);
                x -= r.textWidth(count);
                placed.add(new Contributor(null, count, x, x));
                break;
            }
            int width = CONTRIBUTOR_FACE + CONTRIBUTOR_COUNT_GAP + r.textWidth(count);
            x -= width + CONTRIBUTOR_GAP;
            placed.add(new Contributor(each.getKey(), count, x,
                    x + CONTRIBUTOR_FACE + CONTRIBUTOR_COUNT_GAP));
            shown++;
        }

        // Measured against what the row already holds -- its name, its count and its bar -- and not
        // against the row's edge. A cluster that reaches back past them would be printed over the
        // task's own name, and "no faces" is a smaller fault than a face on top of a word.
        if (x < leftLimit) {
            return List.of();
        }
        return List.copyOf(placed);
    }

    /**
     * One reward, with the same eased wash a task row gets.
     *
     * <p>A reward is no more clickable than a task is, but the two sit in one list one above the other:
     * a highlight on one kind and not the other reads as the list being broken partway down.
     *
     * <p>Measured before drawn, for the reason {@code drawTaskRow} explains at length — the wash is
     * behind the icon, so its width has to be known before the row is painted. A reward's content is
     * simpler than a task's: an icon, a name, and a count when there is one, with no tags reaching the
     * row's far edge.
     */
    private void drawRewardRow(GuiRenderer r, ClientQuestCache.RewardEntry reward, Slot slot,
                               float hover) {
        int x = slot.x();
        int y = slot.y();
        int textY = y + (ROW_ICON - 8) / 2;

        String text = reward.text().getString();
        String count = reward.hasItem() && reward.count() > 1 ? "x" + reward.count() : null;

        int contentRight = x + ROW_ICON + 5 + r.textWidth(text);
        if (count != null) {
            contentRight += 5 + r.textWidth(count);
        }

        // The row's own box rather than its slot: see `rowBox` for the six pixels of gap between them.
        rowWash(r, rowBox(slot), contentRight, hover);

        int textX = x;
        ItemStack toDraw = reward.hasItem() ? reward.item() : reward.icon();
        if (r.icon(toDraw, x, y, ROW_ICON)) {
            textX = x + ROW_ICON + 5;
        }

        r.text(text, textX, textY, ArmatureTheme.body());
        if (count != null) {
            r.text(count, textX + r.textWidth(text) + 5, textY, ArmatureTheme.faint());
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // **The modal first, before the widgets**, and this is a fix rather than an ordering
        // preference. `super.mouseClicked` walks every widget, and the book's own controls are still
        // built behind the scrim -- so a sidebar row underneath the card took the click before this
        // method ever reached its overlay branch. The card is modal in the sense that matters: nothing
        // behind it answers the pointer at all.
        //
        // What stops the book's controls taking this click is `setBookControlsActive`, not the branch
        // below: `super` walks *every* widget, so a book control left active is a book control that
        // answers. That is why the header's Close and Party are deactivated with the rest -- see that
        // method for the report that made them so.
        if (overlay != Overlay.NONE) {
            // Whether this press is the armed Disband's, asked before the press is handled: a press on
            // anything else is a change of mind, and the label a player is looking at has to match what
            // the next press on Disband will do.
            boolean onDisband = disbandButton != null && disbandButton.isMouseOver(mouseX, mouseY);
            if (super.mouseClicked(mouseX, mouseY, button)) {
                // One of the modal's own controls took it. Everything behind stays untouched.
                if (!onDisband) {
                    disarmDisband();
                }
                return true;
            }
            // The panel's bar first, and on the same terms as the sidebar's: a press anywhere on the
            // track jumps the thumb to the pointer and then drags from there. `beginThumbDrag` before
            // `dragThumbTo` matters -- without the first, the second returns immediately because no drag
            // is in progress.
            //
            // Before the outside test rather than after, because the grab band is deliberately wider
            // than the three-pixel bar (`ScrollView.scrollbarHit`) and its outer edge reaches one pixel
            // past the card -- so the order decides whether that pixel drags the bar or closes the
            // panel, and a scrollbar you can miss by a pixel is the thing the wide band exists to fix.
            if (overlay == Overlay.PARTY && partyView.scrollbarHit(mouseX, mouseY)) {
                partyView.beginThumbDrag(mouseY);
                partyView.dragThumbTo(mouseY);
            }
            else if (clickedOutsideCard(mouseX, mouseY)) {
                closeOverlay();
            }
            return true;
        }

        // Widgets first. A control that was clicked must keep the event.
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Then the sidebar's scrollbar, which is not a widget -- it is chrome this screen draws and
        // hit-tests itself, in the margin to the right of the rows. It has to be checked here rather
        // than left to the widget pass for that reason, and it cannot steal a click from a row because
        // its grab band starts just past the viewport's right edge.
        //
        // A press anywhere on the track jumps the thumb to the pointer and then drags from there, which
        // is what every list on every platform does. `beginThumbDrag` before `dragThumbTo` matters:
        // without the first, the second returns immediately because no drag is in progress.
        // The panel first, and it swallows everything inside its own rectangle: a press on the panel is
        // never a press on the canvas, or choosing a colour would pan the graph underneath it.
        if (inTools(mouseX, mouseY)) {
            if (toolsView.scrollbarHit(mouseX, mouseY)) {
                toolsView.beginThumbDrag(mouseY);
                toolsView.dragThumbTo(mouseY);
            }
            return true;
        }

        if (overlay == Overlay.NONE && sidebarView.scrollbarHit(mouseX, mouseY)) {
            sidebarView.beginThumbDrag(mouseY);
            sidebarView.dragThumbTo(mouseY);
            return true;
        }

        if (overlay == Overlay.QUEST) {
            // Anywhere outside the overlay's card closes it, which is what a full-screen panel should
            // do. Inside it, the click belongs to the panel and does nothing.
            if (mouseX < overlayLeft() || mouseX > overlayLeft() + overlayWidth()
                    || mouseY < overlayTop() || mouseY > overlayTop() + overlayHeight()) {
                closeOverlay();
                return true;
            }
            return false;
        }

        // Left or middle on the canvas: begin a pan, or — in developer mode — pick a node up. Whether it
        // becomes a pan or a click is decided by whether the pointer moves, which is why nothing is
        // selected yet — and why a pan that happens to start on a node does not change the selection.
        if ((button == 0 || button == 2) && inCanvas(mouseX, mouseY)) {
            dragging = true;
            pressMoved = false;
            pressX = mouseX;
            pressY = mouseY;
            panContentX = viewport().contentX(mouseX);
            panContentY = viewport().contentY(mouseY);

            String chapter = effectiveChapter();
            ClientQuestCache.Entry under = chapter == null ? null
                    : nodeAt(mouseX, mouseY, questsIn(chapter));
            pressedNode = under == null ? null : under.id();

            // Middle-drag is a pan and never a click, so forget the node immediately. Otherwise a
            // middle-click that happens not to move would select whatever it landed on.
            if (button == 2) {
                pressedNode = null;
            }

            // A left press *on* a node, with an editor open, picks the node up rather than panning. The
            // two cannot share the button, and this is the split every graph editor makes: a press on a
            // node is about that node, and a press on the canvas is about the view. The middle button
            // still pans from anywhere, which is what a trackpad-less mouse reaches for.
            if (button == 0 && under != null && editor() != null) {
                draggedNode = under.id();
                dragX = nodeX(under);
                dragY = nodeY(under);
                // Where inside the node the pointer took hold, so the node does not jump to put its
                // corner under the pointer.
                dragGrabX = viewport().contentX(mouseX) - dragX;
                dragGrabY = viewport().contentY(mouseY) - dragY;
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // The scrollbar's drag, before the canvas pan and before the widgets. It has to be first
        // because a drag that started on the bar must stay on the bar: the pan would otherwise take
        // the movement, and the canvas would slide sideways while the pointer was over a scrollbar.
        if (toolsView.draggingThumb()) {
            toolsView.dragThumbTo(mouseY);
            return true;
        }

        if (sidebarView.draggingThumb() || partyView.draggingThumb()) {
            if (sidebarView.draggingThumb()) {
                sidebarView.dragThumbTo(mouseY);
            }
            else {
                partyView.dragThumbTo(mouseY);
            }
            return true;
        }

        if (draggedNode != null) {
            // A picked-up node follows the pointer, in content coordinates so the zoom does not matter.
            // Committed on release rather than here: a drag is one edit, and a file write per mouse move
            // would be a file write per mouse move.
            pressMoved = true;
            dragX = viewport().contentX(mouseX) - dragGrabX;
            dragY = viewport().contentY(mouseY) - dragGrabY;
            return true;
        }

        if (dragging) {
            if (Math.abs(mouseX - pressX) > DRAG_THRESHOLD || Math.abs(mouseY - pressY) > DRAG_THRESHOLD) {
                pressMoved = true;
            }
            // The grabbed content point goes under the pointer, from the scale as it is *now*. Written
            // this way rather than as `panAtPress + (mouse - press)` for two reasons, and both of them
            // were faults: an offset captured at press is thrown away by anything else that moves the
            // view -- a zoom while the drag is held slid the canvas back to where the drag started, once
            // per wheel notch -- and the delta form accumulates a pixel per event unless it is anchored.
            // `Viewport.dragTo` is the one expression, and its test sweeps it against both.
            viewport().dragTo(mouseX, mouseY, panContentX, panContentY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Consumed unconditionally once a drag is in progress, and deliberately not "only if the
        // pointer is still over the bar". Letting go outside the bar is how a drag ends in every
        // program ever written, and releasing on the last position the bar saw is what makes the end
        // of a drag land where the pointer was when it was let go.
        if (sidebarView.endThumbDrag() || partyView.endThumbDrag() || toolsView.endThumbDrag()) {
            return true;
        }

        if (draggedNode != null) {
            String id = draggedNode;
            float x = dragX;
            float y = dragY;
            boolean moved = pressMoved;
            draggedNode = null;
            dragging = false;
            pressedNode = null;

            if (moved) {
                commitMove(id, x, y);
            }
            else {
                // A press that never moved is still a click: it selects and opens, exactly as it did
                // before there was a drag to tell it apart from. The gesture is one gesture, and the
                // developer mode only changed what "moved" means.
                selectedQuest = id.equals(selectedQuest) ? null : id;
                openOverlay(id);
            }
            return true;
        }

        if (dragging) {
            dragging = false;

            // A press that never moved is a click. Selecting on release rather than on press is what
            // makes "hold to pan" and "click to select" one gesture.
            if (!pressMoved && pressedNode != null && button == 0) {
                selectedQuest = pressedNode.equals(selectedQuest) ? null : pressedNode;

                // A click on a node opens it, rather than only selecting it.
                //
                // Selecting first and opening second is a two-gesture read: click, then find the
                // Open button. Nobody wants that, so one click opens the quest -- which is also what a
                // graph UI is expected to do. There is no longer a second route to it: the summary
                // strip and its Open button are gone, because a button that opens what you just
                // clicked is a button for a place you are already standing.
                //
                // The pan is unaffected: a press that moved is a pan and never reaches here, so
                // holding to look around still works and cannot open anything by accident.
                openOverlay(pressedNode);
                return true;
            }
            pressedNode = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Over the panel, the wheel scrolls it -- and does not zoom the canvas behind it, for the same
        // reason a press on it does not pan: it is a surface, not a hole.
        if (inTools(mouseX, mouseY)) {
            toolsView.scrollBy(-(int) (scrollY * 30));
            return true;
        }

        if (overlay == Overlay.PARTY) {
            // Scrolls the panel, and absorbs the wheel whether or not there is anywhere to go: this
            // method used to test only for QUEST, so the wheel went on scrolling the sidebar and zooming
            // the canvas behind a party panel. A modal that answers the pointer but not the wheel is a
            // modal with a hole in it -- and one that answers the wheel by doing nothing is still
            // answering it, which is what stops the list behind it from moving.
            partyView.scrollBy(-(int) (scrollY * 30));
            return true;
        }

        if (overlay == Overlay.QUEST) {
            // Inside the overlay the wheel scrolls the text, which is what a long description wants.
            // Zooming here would be wrong: there is no canvas to zoom.
            // Clamped by the viewport rather than here, and that is the fix rather than a tidy-up:
            // the previous version wrote the offset unclamped and left the drawing pass to clamp it
            // against a height computed somewhere else, so a flick past the bottom sat out of range
            // until the next frame happened to correct it.
            overlayBody().scrollBy(-(int) (scrollY * 30));
            return true;
        }

        // The wheel over the sidebar scrolls the list, and over the canvas it still zooms. Routed by
        // region rather than by a modifier, because the two regions are visibly separate things and a
        // player pointing at one does not want the other: a list that zoomed the graph behind it, or a
        // canvas that scrolled a sidebar it is not over, is a control answering a question nobody asked.
        //
        // Checked before the canvas, and the two cannot both match -- the sidebar is to the left of the
        // canvas's own left edge -- so the order is for the reader rather than for the logic.
        if (sidebarViewport().containsScreen(mouseX, mouseY)) {
            scrollSidebar(-(int) (scrollY * SidebarLayout.pitch()));
            return true;
        }

        if (inCanvas(mouseX, mouseY)) {
            // Zoom, about the pointer. Not a scroll: a canvas that pans by dragging and also scrolls is
            // two controls for one idea, and the wheel is the one people reach for to zoom.
            zoomAt(mouseX, mouseY, scrollY > 0 ? 1.15F : 1F / 1.15F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Escape closes the overlay rather than the book, if one is open. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Any overlay, not just the quest one. This tested `overlay == Overlay.QUEST`, which was the
        // whole of the truth while there was one overlay and stopped being true the moment a second
        // existed: the party panel could then be left by clicking outside or pressing Back, and not by
        // the key every player reaches for first. A key that closes a dialog closes the dialog.
        if (overlay != Overlay.NONE && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay();
            return true;
        }

        // The editor's keys, and they are all modified or unclaimed: Ctrl+S is a save in every program
        // ever written, Ctrl+Z and Ctrl+Y are undo and redo, Ctrl+D duplicates, Ctrl+N is new. Delete is
        // the one bare key, and it only does anything when a node is selected — which is a state the
        // author put the screen in by clicking one.
        QuestEditor editor = editor();
        if (editor != null && keysForEditor(keyCode, editor)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** @return whether the key was one of the editor's, and was handled */
    private boolean keysForEditor(int keyCode, QuestEditor editor) {
        boolean ctrl = Screen.hasControlDown();

        if (ctrl && keyCode == GLFW.GLFW_KEY_S) {
            saveEditor();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
            if (Screen.hasShiftDown() ? editor.redo() : editor.undo()) {
                // An undo moves files -- it can put a deleted one back -- so the server has to be told,
                // and a reload is how this mod tells it anything about its quest files.
                report(Screen.hasShiftDown() ? "Redo" : "Undo");
                reloadQuests();
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
            if (editor.redo()) {
                report("Redo");
                reloadQuests();
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_N) {
            createQuest(editor);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_D && selectedQuest != null) {
            // Saved on both sides of the change, and the reason is the *manifest*: duplicating writes the
            // copy's file, but the chapter's list of quests is a second file, and it is the list the
            // loader walks. The save after the change is what puts the name in it -- and a save validates
            // and reloads, so the canvas and the disk agree in one step.
            if (!saveEditor()) {
                return true;
            }
            String copy = editor.duplicate(selectedQuest);
            if (copy == null) {
                report("The copy could not be written - see the log");
                return true;
            }
            selectedQuest = copy;
            if (saveEditor()) {
                report("Duplicated as " + copy);
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)
                && selectedQuest != null && editor.quest(selectedQuest) != null) {
            String going = selectedQuest;
            if (!saveEditor()) {
                return true;
            }
            if (editor.delete(going) && saveEditor()) {
                // Cleared only once the chapter is on disk without it: a selection naming a quest whose
                // file is still listed would be a Delete key that claims to have deleted something.
                selectedQuest = null;
                report("Deleted " + going + " (its file is beside it, renamed .deleted)");
            }
            return true;
        }
        return false;
    }

    /**
     * Commits a finished drag.
     *
     * <p>The position is rounded to whole content units, which is the grid a quest file is authored on:
     * a node at x=37.4182 is a number nobody typed on purpose, and the canvas is drawn at a zoom where
     * the difference is invisible — so the file would carry noise that only shows up in a diff.
     */
    private void commitMove(String id, float x, float y) {
        QuestEditor editor = editor();
        if (editor == null) {
            return;
        }
        long revision = ClientQuestCache.treeRevision();
        if (editor.move(id, Math.round(x), Math.round(y))) {
            // Remembered until a reload says the same thing: the canvas draws the server's tree, and the
            // server has not heard about this yet. See `EditorSession`.
            editors.moved(id, Math.round(x), Math.round(y), revision);
        }
    }

    /** Something changed in the editor that the canvas has to redraw. */
    private void movedByEditor() {
        rebuildWidgets();
    }

    /**
     * Writes the chapter, and asks the server to read it again.
     *
     * <p>Validating first is the model's job — {@link QuestEditor#save()} refuses the whole save if any
     * file would not load — so what is left here is telling the author what happened: the count on
     * success, and the loader's own messages on refusal, in the chat because that is where this mod's
     * commands answer and a list of problems does not fit on a panel.
     */
    private boolean saveEditor() {
        QuestEditor editor = editor();
        if (editor == null) {
            return false;
        }
        QuestEditor.SaveResult result = editor.save();
        if (!result.ok()) {
            for (String message : result.messages()) {
                say("\u00a7c" + message);
            }
            report("Not saved: " + result.messages().size() + " problem(s), listed in the chat");
            return false;
        }
        if (result.written() == 0) {
            report("Nothing to save");
            return true;
        }
        report("Saved " + result.written() + " file(s)");
        reloadQuests();
        return true;
    }

    /** Adds a quest where the middle of the view is, which is where the author is looking. */
    private void createQuest(QuestEditor editor) {
        // Saved before *and* after: before so that an error in one of the chapter's other files is
        // reported here rather than overwritten, and after because the manifest -- the list the loader
        // walks -- is a second file that `create` only changes in memory. The save after the change is
        // what writes it, and a save validates and reloads, so there is nothing left for this method to
        // do but say what happened.
        if (!saveEditor()) {
            return;
        }
        float x = viewport().contentX(canvasLeft() + (canvasRight() - canvasLeft()) / 2.0);
        float y = viewport().contentY(canvasTop() + (canvasBottom() - canvasTop()) / 2.0);
        String id = editor.create(Math.round(x), Math.round(y));
        if (id == null) {
            report("A new quest could not be written - see the log");
            return;
        }
        selectedQuest = id;
        if (saveEditor()) {
            report("Added " + id);
        }
    }

    /** Asks the server to read the quest files again, which is what puts an edit on the canvas. */
    private void reloadQuests() {
        runPartyCommand("tasked reload");
    }

    /** One line in the chat, for something the author did. */
    private void report(String message) {
        say("\u00a77" + message);
    }

    private void say(String message) {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(message));
        }
    }

    /** A quest book should not stop the world ticking — you want to read it mid-fight. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static String stateLabel(QuestState state) {
        return switch (state) {
            case COMPLETED -> "Completed";
            case STARTED -> "In progress";
            case UNLOCKED -> "Available";
            case LOCKED -> "Locked";
        };
    }

    private static int stateColour(QuestState state) {
        return switch (state) {
            case COMPLETED -> ArmatureTheme.complete();
            case STARTED -> ArmatureTheme.inProgress();
            case UNLOCKED -> ArmatureTheme.available();
            case LOCKED -> ArmatureTheme.blocked();
        };
    }

    // wrap(String, int) and drawParagraphs(...) used to live here: a hand-rolled word wrap, and the
    // drawing of it, in the same class that needed the height. That is why the scrollbar's range and
    // the text on screen were two computations that had to be kept in step. The wrap rule is TextWrap's
    // now, the composition is OverlayLayout's, and this class draws the lines it is handed.

    // trim(String, int) used to live here: a character-count truncation, which is the wrong kind of
    // quantity for this job -- see trimToWidth above. It had four callers and all four were wrong:
    // chapter titles cut to 16 characters in a 116px button, node labels cut to a number derived from
    // the zoom, strip titles trimmed a character at a time in a loop that re-measured each time, and a
    // summary passed a limit of 999, which is not a truncation at all.

    /**
     * Forgets the view: chapter, selection, zoom, pan.
     *
     * <p>Called on disconnect. Without it, leaving one server and joining another shows the first
     * server's chapter and pan until the new tree arrives — which looks exactly like a sync failure and
     * is not one, so it sends you looking in the wrong place.
     */
    public static void forgetViewState() {
        selectedChapter = null;
        selectedQuest = null;
        VIEW.setScale(1.0F);
        VIEW.setOffset(0, 0);
        pannedChapter = null;
        centred = false;
        // The warning memory goes with them, for the same reason as everything else here: it describes
        // a chapter of a server this client is no longer connected to. Without this, rejoining the same
        // server would stay quiet about a chapter theme that is still misspelled.
        //
        // There is no theme to release any more, and there were two calls here that did it. A chapter's
        // palette lives inside one frame now, so there is nothing that could outlive a disconnect.
        warnedThemeFor = null;

        // The sidebar's outline goes too, and the revision with it. The outline holds which groups the
        // player had collapsed, which is a fact about a questline on a server this client is no longer
        // connected to -- so keeping it would open the next server's book with the last one's collapses,
        // and `-1` means the next tree definitely re-seeds rather than possibly matching a revision it
        // happens to share.
        sidebar = null;
        sidebarRevision = -1;
    }

    @Override
    public void removed() {
        super.removed();
        // Nothing to undo, and that is worth recording because there used to be three lines here.
        //
        // A chapter's theme was a global claim in an earlier round: it was applied when the chapter was
        // opened and had to be released when the book closed, or browsing the gallery and then walking
        // away left that chapter's palette on every other Armature screen until the client restarted.
        // The release was written correctly; the need for it was the design being wrong.
        //
        // A chapter's palette is a *region* now -- a scope around the canvas and the overlay, closed on
        // every exit path by the compiler -- so leaving the book cannot leave anything behind. This
        // comment stays so that the next person does not add the release back.
        warnedThemeFor = null;
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    /**
     * The palette the canvas and the quest overlay are drawn in: the chapter's theme, or the main one.
     *
     * <h2>This is the whole of the chapter-theme rule, and it is deliberately four lines</h2>
     *
     * <p>A chapter's theme used to be an <i>override</i> of the player's own: it won while you were in
     * that chapter, a click declined it, the decline lasted one visit, and there were two flags to
     * remember that. All of it is gone, and not because it was buggy — it worked. It is gone because the
     * two things were never competing: "how do I want this program to look" and "what does this chapter
     * look like" are different questions, answered for different regions of the screen. Two palettes,
     * live at once, with no rule between them.
     *
     * <p>What that buys, concretely: a player can be looking at a violet chapter's canvas inside an
     * otherwise-unchanged book, and there is no state in which a control reports one theme while the
     * screen shows another.
     *
     * <h2>Quest-level themes slot in here, and nowhere else</h2>
     *
     * <p>A quest may override its chapter's palette. That merge is a {@code ThemePatch} composition —
     * main → chapter → quest — and this method is the single place it happens, which is why the chapter's
     * patch will be applied here rather than at the call site. Nothing else in this screen knows how a
     * palette is assembled.
     *
     * <p><b>A name this build has no theme for falls back and says so</b>, once per chapter. Ignoring it
     * silently would make a typo indistinguishable from a deliberately plain chapter; refusing to draw
     * the chapter would let one bad string block a player from their own quests. See
     * {@link #warnAboutThemeOnce}.
     */
    private static Theme viewportTheme() {
        String chapter = effectiveChapter();
        if (chapter == null) {
            return Appearance.main();
        }

        String named = ClientQuestCache.chapterTheme(chapter);
        if (named == null) {
            return Appearance.main();
        }

        Theme found = Themes.any(named);
        if (found == null) {
            warnAboutThemeOnce(chapter, named);
            return Appearance.main();
        }
        return found;
    }

    /**
     * Reports a chapter whose theme name this build cannot resolve, once per chapter.
     *
     * <p>Warned on the client rather than the server, and that boundary is deliberate rather than an
     * omission: the theme catalogue is a client concept, and a dedicated server has no appearance and no
     * themes, so teaching the validator about them would make a quest file depend on which client reads
     * it. {@code Chapter.theme} carries the same argument.
     *
     * <p>Once per chapter because this runs inside a per-frame drawing path: a line of log per frame for
     * one misspelled name would bury every other message the client writes.
     */
    private static void warnAboutThemeOnce(String chapter, String named) {
        if (chapter.equals(warnedThemeFor)) {
            return;
        }
        warnedThemeFor = chapter;
        Constants.LOG.warn("tasked: chapter '{}' asks to be drawn in a theme called '{}', which this"
                + " build does not have, so your own theme is in use. The themes it has are: {}",
                chapter, named, Themes.names());
    }

    // ------------------------------------------------------------------
    // Appearance, and the two controls that used to be here
    // ------------------------------------------------------------------
    //
    // `cycleTheme()` and `toggleMotion()` were the callbacks for the sidebar's two appearance rows, and
    // both have gone with the buttons. Neither did any work: one called `Appearance.cycleTheme()` and
    // the other `Appearance.setMotion(!Appearance.motion())`, and what they added was a `rebuildWidgets()`
    // to refresh a label that no longer exists.
    //
    // That is the right split rather than a deletion that loses something. The operations live in
    // Armature, are tested there, and are what a dev-mode screen will call; the screen only ever wrapped
    // them to redraw a button. A dev-mode control that cycles the theme does not need this class to know
    // about it at all, which is the property worth having -- the alternative is that every screen wanting
    // a theme control reimplements the rebuild.
    //
    // What remains here is `viewportTheme()`, which is not a control: it decides which palette a *chapter*
    // is drawn in, and that is this screen's business because a chapter is this screen's concept. A
    // theme control is not.
}
