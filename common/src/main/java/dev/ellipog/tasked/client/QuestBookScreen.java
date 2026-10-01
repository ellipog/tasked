package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.ArmatureLive;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.RichText;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.TextWrap;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tasked.QuestAuthority;
import dev.ellipog.tasked.editor.EditorOp;
import dev.ellipog.tasked.net.EditorReplyPayload;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.client.ClientChapterReplica;
import dev.ellipog.tasked.client.ClientEditReplies;
import dev.ellipog.tasked.client.dev.HexColour;
import dev.ellipog.tasked.client.dev.ChapterPanelLayout;
import dev.ellipog.tasked.client.dev.ClientEditorClipboard;
import dev.ellipog.tasked.client.dev.InlineEdit;
import dev.ellipog.tasked.client.dev.ItemPicker;
import dev.ellipog.tasked.client.dev.ItemPickerLayout;
import dev.ellipog.tasked.client.dev.QuestPanel;
import dev.ellipog.tasked.client.dev.QuestPanelLayout;
import dev.ellipog.tasked.client.dev.RowDrag;
import dev.ellipog.tasked.client.dev.ToolsLayout;
import dev.ellipog.tasked.client.dev.ToolsPanel;
import dev.ellipog.tasked.editor.EditorSession;
import dev.ellipog.tasked.editor.QuestEditor;
import dev.ellipog.tasked.net.PartySnapshot;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.client.ClientPartyCache;
import dev.ellipog.tasked.net.ClaimRewardPayload;
import dev.ellipog.tasked.net.SubmitTaskPayload;
import dev.ellipog.tasked.progress.QuestState;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h2>The editor's drag, in four phases</h2>
 *
 * <p>In developer mode a left press on a node picks it up instead of panning, and the same gesture
 * runs <b>press, threshold, follow, release</b>: the press reads the node's position through the one
 * reader ({@code nodeX}/{@code nodeY}) before claiming it; the threshold (four screen pixels, the pan's
 * own) separates a click from a drag, so a click never twitches the node; the follow puts the grabbed
 * point under the pointer and lands it on {@link BookGeometry#SNAP_GRID} while the Snap switch is on —
 * Alt held asks for free placement, per event; the release commits exactly the position the canvas was
 * last drawn showing. One edit per drag, one write per drag.
 *
 * <p><b>The modifiers are the selection's, and every button has one meaning.</b> Left is select,
 * drag-move and pan; right is the dependency edge; middle pans. Shift-click adds a node to the
 * selection (and the press still picks it up, so shift-drag moves the whole selection); Ctrl-click
 * toggles one in or out; a click on the empty canvas lets go of everything; a shift-drag on the empty
 * canvas stretches the additive marquee. Modifiers are read at press and remembered, so letting go of
 * a key a moment before the mouse comes up cannot turn one gesture into another.
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
     * The quests selected besides the primary one, which is {@link #selectedQuest}.
     *
     * <p>Static with the selection it extends, for the same reason: a marquee that survives reopening
     * the book is the selection an author put down and walked away from. The panel edits the primary
     * alone -- a property panel over six quests at once is six panels pretending to be one -- so the
     * multi-selection exists for the canvas's whole-hand gestures: drag them together, delete them
     * together, copy them together.
     */
    private static final Set<String> multiSelection = new LinkedHashSet<>();

    /**
     * The marquee: where the rectangle started, and where the pointer is now, in screen coordinates.
     *
     * <p>Screen coordinates because the rectangle is drawn as it is dragged and a zoom mid-drag should
     * not move what the author is holding; the selection test at release maps each node into the same
     * space, so the rectangle and the test it feeds agree about what "inside" means.
     */
    private boolean marqueeActive;
    private int marqueeX0;
    private int marqueeY0;
    private int marqueeX1;
    private int marqueeY1;

    /**
     * The quest an edge drag is carrying from, once the drag is live.
     *
     * <p>Right is the edge's button: a right-press on a node that never moves does nothing, and the
     * same threshold that tells a click from a node drag tells this one from that. A live edge drag
     * draws the line to the pointer and lands on whatever node is under the release -- the arrow
     * points the way the drag went: from the prerequisite to the quest that depends on it.
     */
    private String edgeDragFrom;
    private boolean edgeDragLive;
    private double edgeDragX;
    private double edgeDragY;

    /** Whether the next canvas click adds a dependency instead of selecting. Escape cancels. */
    private boolean pickingDependency;

    /**
     * What the dependency pick is for, captured when it is armed.
     *
     * <p>Captured rather than read at the click, because arming closes the card: the edited quest's id and
     * its replica are both gone by the time the click lands, and the author may switch to another chapter
     * or group to find the quest they mean -- which is the whole reason a pick exists instead of a text
     * field. So the quest, the chapter it lives in, and its current prerequisites travel with the pick, and
     * the operation is sent to the chapter that was being edited however far the sidebar has moved since.
     */
    private record DependencyPick(String quest, String chapter, List<String> dependsOn) {
    }

    private DependencyPick pendingPick;

    /**
     * Whether the drag in progress began on the open field's own frame, so it extends the field's mark
     * rather than panning the canvas or moving a node. Set by the press the field took -- the same test
     * that places the caret -- and cleared when it is let go.
     */
    private boolean fieldDrag;

    /** One block of the reader's description: what it is, and the lines it was wrapped to. */
    private record ProseBlock(RichText.Paragraph paragraph, List<RichText.Line> lines) {
    }

    /** A link as it was drawn: its rectangle on screen, and where it goes. */
    private record LinkRect(BookGeometry.Rect box, String url) {
    }

    /**
     * The reader's description, parsed and wrapped for this frame.
     *
     * <p>One parse serves three things -- the layout takes the lines and widths, the drawing takes the runs,
     * and the link rectangles are cut from what the drawing did -- so they cannot disagree about what the
     * prose says or where it is. Rebuilt where the reader's card is drawn, which is once a frame and the
     * same cost class as the wrap it replaces.
     */
    private final List<ProseBlock> readerProse = new ArrayList<>();

    /** What the reader's card drew this frame: the targets a hover and a click are matched against. */
    private final List<LinkRect> linkRects = new ArrayList<>();

    /** The link the press landed on, opened when it is released on the same rectangle. */
    private LinkRect pressedLink;

    /**
     * What the caret follow last saw: the field it was watching, its text, and where in it the caret was.
     * Three strings and an int, and they are the whole difference between following the caret and fighting
     * the wheel -- see {@link #followCaret}.
     */
    private String followedPath;
    private String followedValue;
    private int followedCaret = -1;

    /**
     * Where every node of a dragged selection started, in content coordinates.
     *
     * <p>Captured when the drag goes live, because a drag moves the selection <i>together</i> and a
     * delta can only be taken against where things were. The pressed node's own start is
     * {@link #dragStartX}; this carries the rest.
     */
    private final Map<String, float[]> dragStarts = new LinkedHashMap<>();
    private float dragStartX;
    private float dragStartY;

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
    /** Positions asked for and not yet answered. Built once: it holds no chapter, so nothing to reset. */
    private final EditorSession editors = new EditorSession();

    /** Whether the tools panel is open. See {@link #buildToolsWidgets} and {@link #drawTools}. */
    private boolean toolsOpen;

    /**
     * Which panel the dock is showing: the tools this panel started as, or the quest the canvas has
     * selected. The strip that replaced the title row switches it.
     *
     * <p>The theme panel's own state — the selected colour, the scroll, the fold — lives in fields the
     * tab switch never touches, so going to the Quest tab and back is exactly where you left it. That
     * permanence is the reason the dock grew tabs rather than swapping its contents.
     */
    private ToolsLayout.Tab toolsTab = ToolsLayout.Tab.THEME;

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

    /** The quest panel's rows and layout, for the tab it is shown on. */
    private List<InspectRow> questRows = List.of();
    private Layout questLayout;

    /** The quest panel's folded sections, by heading key. Selection is not one of them: that is shared. */
    private final Set<String> questFolded = new LinkedHashSet<>();

    /**
     * The tree and replica revisions the quest panel's rows were built at.
     *
     * <p>Two, because the panel reads the <b>replica</b> and the canvas reads the tree, and they move at
     * different times: an op's answer moves the tree first, and the replica it asked for arrives a round
     * trip later. Rebuilding on the tree alone would build rows from a copy that is about to be replaced,
     * and nothing would ever rebuild again -- the panel would show the edit it just committed as it was
     * before it. Either number moving rebuilds the rows.
     */
    private long questPanelRevision = -1;
    private long questReplicaRevision = -1;

    /** What a click in the card's editor can land on. */
    private enum EditAction {
        FIELD, FLAG, ITEM, RAW, ADD_TASK, ADD_REWARD, ADD_DEP, PICK_DEP, REMOVE_DEP, COPY_ENTRY,
        REMOVE_ENTRY,
        /** The row's leading strip: a press that travels becomes a reorder. */
        DRAG_ENTRY
    }

    /**
     * One pressable piece of the card's editor: what it does, where it is, and what it is about.
     *
     * <p>Rebuilt every frame by the drawing and read by the click -- the radius stepper's arrangement,
     * one derivation for both halves. {@code box} is in screen coordinates, which is what a click is.
     */
    private record EditTarget(EditAction action, String path, BookGeometry.Rect box, int textX,
                              int textY, String value, String member, int index) {
    }

    /** The editor's pressable pieces, from the last frame's drawing. */
    private final List<EditTarget> editTargets = new ArrayList<>();

    /**
     * Labels waiting to be drawn, after everything else.
     *
     * <p>A tooltip drawn where it is discovered is a tooltip the next row can paint over -- the row
     * below draws later and wins. Collected and drawn last, the same rule the screen's own tooltip
     * pass follows.
     */
    private final List<PendingLabel> pendingLabels = new ArrayList<>();

    private record PendingLabel(BookGeometry.Rect box, String text) {
    }

    /** The field being edited inline, by path, and the widget editing it. One at a time. */
    private String editingPath;
    private ArmatureTextField inlineField;
    private ArmatureTextArea inlineArea;

    /** The settings popover: the fields the reader's card never shows. */
    private boolean settingsOpen;
    private ArmatureButton settingsButton;
    private final dev.ellipog.armature.client.ui.kit.ScrollView settingsView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());
    private List<InspectRow> settingsRows = List.of();
    private Layout settingsLayout;

    /** The chapter panel's rows and layout, for the dock's Chapter tab. */
    private List<InspectRow> chapterRows = List.of();
    private Layout chapterLayout;

    /** Which list the modal's type picker is adding to ("tasks"/"rewards"), or null when it is closed. */
    private String pickingEntryType;

    /** Which field the item picker is setting, or null when it is closed. */
    private String pickingItemPath;

    /** The value that field holds now, for the clear row; empty when there is none. */
    private String pickingItemCurrent = "";

    /**
     * The path a clear removes, when clearing is legal at all; null leaves the clear row off.
     *
     * <p>From {@link ItemPicker#clearPath}: the icon, and only the icon. A clear row on a task used
     * to delete a required field, save, and take the quest out of the tree.
     */
    private String pickingItemClearPath;

    /** The picker's search box, while it is open. Read for its text and cleared with the picker. */
    private ArmatureTextField itemSearch;

    /** The registry's entries, and the player's carried stacks -- gathered once, when the picker opens. */
    private List<ItemPicker.Entry> pickerEntries = List.of();
    private List<ItemPicker.Entry> pickerInventory = List.of();

    /**
     * The picker's last drawing: the rows, the frame and the scroll the press has to agree with.
     *
     * <p>The click reads these rather than recomputing, for the reason every drawn control here is
     * hit-tested from the drawing's own derivation: two computations of the same list, taken a frame
     * apart, are two chances to land on different rows.
     */
    private List<ItemPickerLayout.Row> pickerRows = List.of();
    private ItemPickerLayout.Frame pickerFrame;
    private List<ItemPicker.Entry> pickerMatches = List.of();
    private String pickerQuery = "";
    private int pickerSelected = -1;
    private int pickerScroll;

    /**
     * The row drag: which list, which row, and the y the line is drawn at.
     *
     * <p>One state for three lists -- a quest's tasks, its rewards, and the dock Chapter tab's quest
     * list -- because the gesture is one gesture. What differs is the op the release sends, and that is
     * read off {@link #dragRowMember}. Null/&#8209;1 between drags.
     */
    private String dragRowMember;
    private String dragRowQuest;
    private int dragRowFrom = -1;
    private boolean dragRowLive;
    private double dragRowPointerY;

    /**
     * The rows each list is drawn at, this frame, in drawing order -- the seam the gap is counted
     * against. Rebuilt by the drawing and read by the release, one derivation for both.
     */
    private final Map<String, List<BookGeometry.Rect>> dragRowSlots = new LinkedHashMap<>();

    /** Whether the modal's Delete has been pressed once and is waiting for the confirming second. */
    private boolean confirmingDelete;

    /** The modal's Delete, so a press anywhere else can disarm it. The party's Disband set the shape. */
    private ArmatureButton questDeleteButton;

    /**
     * The band's hex field, while there is one.
     *
     * <p>Held because {@code applyHex} has to write back to it, and because writing back is not rebuilding:
     * the first version answered a hex edit with {@code rebuildWidgets()}, which cleared this field, which
     * blurred it, which submitted it — and a submit is what asked for the rebuild. The game crashed with a
     * StackOverflowError five hundred frames deep, on the first hex anyone typed.
     */
    private dev.ellipog.armature.client.ArmatureTextField hexField;

    /** The panel's own controls, so the chrome layer can draw them: the header's pair. */
    private ArmatureButton editButton;
    private ArmatureButton toolsButton;

    /**
     * The node the drag is carrying, once the drag is one: past the threshold, following the pointer.
     *
     * <p>Null between a press and the threshold, so a click never looks like a wiggle; the node under
     * the pointer at press is {@link #pressedNode} until then.
     */
    private String draggedNode;

    /**
     * Where that node is now, in content coordinates, and where inside it the pointer grabbed it.
     *
     * <p><b>Not named {@code dragX} and {@code dragY}.</b> Vanilla's {@code mouseDragged} declares its
     * own parameters under exactly those two names, and a field under the same name is shadowed by
     * them for the whole method body: the follow step's {@code dragX = ...} wrote the <i>parameter</i>,
     * the field never moved, and the drag — which reads the field to draw the node and to commit it —
     * did nothing at all while looking exactly like code that worked. Every commit was 0,0 until the
     * press read the position first, after which every commit was the press position: a drag that
     * cannot move the thing it is dragging. The names are the fix; the state machine around them is
     * the rewrite.
     */
    private float dragNodeX;
    private float dragNodeY;
    private float dragGrabX;
    private float dragGrabY;

    /**
     * Whether the drag has passed the threshold and is now carrying the node.
     *
     * <p>Press, then threshold, then follow: a pointer that has moved a pixel or two since the press
     * has not decided to drag anything yet, and a node that twitched after every click would read as a
     * jitter the author did not ask for. Between the press and this flag the press is still a possible
     * click; once it is set the node follows and release commits.
     */
    private boolean nodeDragLive;

    /**
     * Which modifiers the canvas press was made under.
     *
     * <p>Remembered at press and not re-asked at release, because letting go of shift a moment before
     * the mouse comes up must not turn a shift-click into a plain one -- that would select and open
     * the node the press was adding, and clear the rest of the selection doing it.
     */
    private boolean pressedShift;
    private boolean pressedCtrl;

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
     * Every widget the open modal built, redrawn after the card.
     *
     * <h2>Why a list, and why it is not {@link #buttons}</h2>
     *
     * <p>The card is painted in the chrome layer, <i>after</i> the widget pass -- see the ordering note
     * at the redraw loop -- so every modal control has to be drawn a second time on top of it. That
     * loop used to walk {@code buttons} from {@code bookButtonCount}, which is every control created
     * through {@link #control}; a text field is not one of those. It was placed, took clicks and was
     * invisible: the card covered it, and nothing drew it again. The fourth playtest's screenshot is
     * exactly that -- labels drawn by the panel, every field blank. This list is the general answer:
     * whatever the modal builds records its redraw here, and the loop runs them.
     */
    private final List<java.util.function.Consumer<GuiRenderer>> modalRedraws = new ArrayList<>();

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
     *
     * <p>And the <b>press</b> reads it too, and that order is load-bearing: a press picks a node up by
     * asking where it is <i>before</i> claiming it, because claiming is what makes this method answer
     * with {@link #dragNodeX} — which, before the follow has run, is whatever the last drag left there.
     * The first version of the drag read after claiming and every commit landed at 0,0.
     */
    private float nodeX(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragNodeX;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedX(entry.id()) : entry.x();
    }

    /** The same, for y. See {@link #nodeX}. */
    private float nodeY(ClientQuestCache.Entry entry) {
        if (entry.id().equals(draggedNode)) {
            return dragNodeY;
        }
        return editors != null && editors.hasMoved(entry.id())
                ? (float) editors.movedY(entry.id()) : entry.y();
    }

    /** Whether a node is selected at all: the primary the panel edits, or one of the multi-selection. */
    private static boolean isSelected(String id) {
        return id.equals(selectedQuest) || multiSelection.contains(id);
    }

    /** The whole selection: the primary first, then the rest in the order they were added. */
    private static List<String> selection() {
        List<String> all = new ArrayList<>();
        if (selectedQuest != null) {
            all.add(selectedQuest);
        }
        for (String id : multiSelection) {
            if (!id.equals(selectedQuest)) {
                all.add(id);
            }
        }
        return all;
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
    /**
     * Whether this player may edit at all: edit mode on, and the permission the server will check for itself.
     *
     * <p>This used to also open the chapter's files on the *client*, which went with the client's write path.
     * The files belong to the server now; a client on a dedicated server has none of its own to open, and the
     * gate that matters is the one in the payload handler. What is left here is the courtesy — not offering a
     * control whose only answer would be a refusal.
     */
    private boolean mayEditNow() {
        return DevMode.on() && mayEdit();
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
        if (overlay != Overlay.NONE) {
            // Built for the modal, so it must be redrawn above the card. See `modalRedraws`.
            modalRedraws.add(button::draw);
        }
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
        toolsFrame = ToolsLayout.frame(geometry().canvas(), toolsTab);

        // The tab strip, in the band the title used to occupy. Two buttons, the active one selected --
        // the same appearance rule as every other pressed-look control in this screen. The panel's own
        // name is gone: two tabs name the two panels the dock holds, and neither of them is "Tools".
        control(ToolsLayout.tabTheme(toolsFrame.tabs()), Component.literal(ToolsLayout.Tab.THEME.label()),
                () -> setTab(ToolsLayout.Tab.THEME)).selected(toolsTab == ToolsLayout.Tab.THEME);
        control(ToolsLayout.tabQuest(toolsFrame.tabs()), Component.literal(ToolsLayout.Tab.CHAPTER.label()),
                () -> setTab(ToolsLayout.Tab.CHAPTER)).selected(toolsTab == ToolsLayout.Tab.CHAPTER);

        if (toolsTab == ToolsLayout.Tab.CHAPTER) {
            // The chapter's own file, from the replica, as the rows' source. The quests are edited in
            // their own card -- the modal -- and this tab is the chapter: title, icon, rules, and the
            // authored order of the quests.
            JsonObject chapter = ClientChapterReplica.chapterTree(effectiveChapter());
            chapterRows = ChapterPanelLayout.rows(chapter, questFolded);
            chapterLayout = InspectLayout.build(chapterRows, toolsFrame.list().width(),
                    Measure.monospace(6, 9));
            toolsView.clear();
            toolsView.whole(true);
            toolsView.viewport().bounds(toolsFrame.list().x(), toolsFrame.list().y(),
                    toolsFrame.list().width(), toolsFrame.list().height());

            for (InspectRow row : chapterRows) {
                switch (row.kind()) {
                    case FIELD -> {
                        ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, row.value());
                        String path = row.key();
                        field.onSubmit(text -> commitField(path, text, true));
                        field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                                ArmatureTheme.panelEdge());
                        toolsView.put(row.key(), field, InspectLayout::strip);
                        addRenderableWidget(field);
                    }
                    case TOGGLE -> {
                        ArmatureButton button = control(0, 0, 0, 0,
                                Component.literal(flagOn(chapter, row.key()) ? "Off" : "On"),
                                () -> pressChapterToggle(row.key()));
                        button.textColour(ArmatureTheme.body());
                        toolsView.put(row.key(), button, InspectLayout::strip);
                    }
                    case HEADING -> {
                        ArmatureButton button = control(0, 0, 0, 0, Component.literal(""),
                                () -> foldQuestSection(row.key()));
                        button.flat(true);
                        toolsView.put(row.key(), button);
                    }
                    default -> {
                        // VALUE rows are drawn by the panel and hold no widget.
                    }
                }
            }

            toolsView.apply(chapterLayout, toolsFrame.list().width());
            return;
        }

        toolsRows = ToolsLayout.rows(DevMode.on(), ClientAppearance.LOOK.motion(), DevMode.snap(),
                toolsColoursOpen);
        toolsLayout = ToolsLayout.build(toolsRows, toolsFrame.list().width(), Measure.monospace(6, 9));

        toolsView.clear();
        // Wholly-visible rows only: the panel's widgets are drawn by the book's widget pass, which is
        // clipped to the book -- not to this list -- so a row half scrolled out of the list would be
        // painted over the sample above it. That is a report, and this is the flag for it.
        toolsView.whole(true);
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

        // The shape's stepper has no widgets at all, and that is the design rather than an omission. Its
        // arrows are drawn by the panel from `ToolsLayout.stepper` and pressed through
        // `ToolsLayout.radiusStepAt`, which reads the same rectangles; its *label* is drawn by the panel too,
        // because a widget for it would cover the arrows and the widget pass takes presses before this
        // screen's own click handling does. Building the arrows as widgets was the first version, at the
        // slot's own coordinates -- which are the *list's*, not the screen's -- so they were placed outside
        // the panel and the row read as a number with nothing to press.
        //
        // The kind is STEPPER and the panel dispatches on it; see `ToolsPanel.drawStepper`, which is the one
        // place this row is drawn since the fall-through that used to draw only its label was removed.

        // And the hex code, editable where it is shown: a field over the swatch's line, holding the
        // selected colour's value. Creating it here rather than drawing it is the whole point of having a
        // text widget -- the number is typed, not watched.
        BookGeometry.Rect hex = ToolsLayout.hexField(toolsFrame.swatch(), toolsSelected != null);
        hexField = null;
        if (hex != null) {
            hexField = new dev.ellipog.armature.client.ArmatureTextField(
                    hex.x(), hex.y(), hex.width(), hex.height(), hexOf(toolsSelected));
            hexField.onSubmit(this::applyHex);
            hexField.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
            addRenderableWidget(hexField);
        }

        control(ToolsLayout.revert(toolsFrame.actions()),
                Component.translatable("tasked.dev.reset"), this::revertSelected);
        control(ToolsLayout.save(toolsFrame.actions()),
                Component.translatable("tasked.dev.save"), this::saveTheme);

        toolsView.apply(toolsLayout, toolsFrame.list().width());
    }

    /** Switches the dock's tab. The other panel's state is not touched: see {@link #toolsTab}. */
    private void setTab(ToolsLayout.Tab tab) {
        if (toolsTab != tab) {
            toolsTab = tab;
            rebuildWidgets();
        }
    }

    /** One of the three switches. */
    private void pressToolsSwitch(String key) {
        if (key.equals(ToolsLayout.EDIT)) {
            setEditing(!DevMode.on());
        }
        else if (key.equals(ToolsLayout.MOTION)) {
            ClientAppearance.LOOK.setMotion(!ClientAppearance.LOOK.motion());
            status(ClientAppearance.LOOK.motion() ? "Motion on" : "Motion off", false);
            rebuildWidgets();
        }
        else if (key.equals(ToolsLayout.SNAP)) {
            DevMode.setSnap(!DevMode.snap());
            status(DevMode.snap() ? "Snap on \u2014 Alt places freely" : "Snap off", false);
            rebuildWidgets();
        }
    }

    // ------------------------------------------------------------------
    // The quest panel's edits
    // ------------------------------------------------------------------

    /**
     * The selected quest's own tree, from the replica, or null.
     *
     * <p>The one read every quest-panel handler makes, and null is the answer for all three ways there
     * can be nothing: no chapter on screen, nothing selected, or the copy not arrived yet. Every caller
     * treats it as "nothing to edit here" rather than as an error.
     */
    private JsonObject replicaQuest() {
        String chapter = effectiveChapter();
        String target = editTarget();
        return chapter == null || target == null
                ? null : ClientChapterReplica.quest(chapter, target);
    }

    /**
     * The quest the editor's commits are about: the modal's quest while its editor is open, else the
     * dock's selection.
     *
     * <p>One method, so the dock panel's handlers and the modal's cannot disagree about who a field
     * edit is for -- the two surfaces share every commit path below.
     */
    private String editTarget() {
        return overlay == Overlay.QUEST && overlayQuest != null ? overlayQuest : selectedQuest;
    }

    private static boolean flagOn(JsonObject quest, String path) {
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, path);
        return current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isBoolean() && current.getAsBoolean();
    }

    /**
     * Commits one field, as the text the player typed and the path it belongs to.
     *
     * <p>This is the whole of "per field, on commit": the field's submit brings the text here, the type
     * is re-asked of the tree as it stands now, and a refusal costs a status line and a reset -- the
     * field goes back to what the tree says, because a red line of text beside a field still holding the
     * bad value is two disagreements where one will do.
     *
     * <p>An <b>empty</b> commit removes the field rather than writing an empty string into it, which is
     * the decision {@code EditorOp.SetField} was given a null for. Required fields refuse on the server
     * with the validator's own message, which is the honest answer for clearing a title.
     */
    private void commitQuestField(String path, String text) {
        commitField(path, text, false);
    }

    /**
     * Commits one text field, as the op the surface sends: {@code SetField} for a quest, {@code
     * SetChapter} for the chapter.
     *
     * <p>One method, because the rules are the same on both files -- parse by the declared or inferred
     * kind, an empty field means <i>remove</i>, aliases are a comma list -- and only the target
     * differs. A second copy of those rules is a second place for them to drift.
     */
    private void commitField(String path, String text, boolean chapter) {
        if (!mayEditNow()) {
            return;
        }
        String target = chapter ? null : editTarget();
        if (!chapter && target == null) {
            return;
        }
        String typed = text == null ? "" : text.trim();
        if (!chapter && path.equals(QuestPanelLayout.DEPENDENCY_ADD)) {
            addDependency(typed);
            return;
        }
        JsonObject tree = chapter
                ? ClientChapterReplica.chapterTree(effectiveChapter()) : replicaQuest();
        java.util.function.Function<JsonElement, EditorOp> op = chapter
                ? value -> new EditorOp.SetChapter(path, value)
                : value -> new EditorOp.SetField(target, path, value);
        if (typed.isEmpty()) {
            // Empty means absent: the field is removed rather than written as nothing -- the same
            // decision the model's SetField was given a null for.
            send(op.apply(null));
            return;
        }
        if (path.equals("aliases")) {
            // One field for the list, because an alias is one word: commas between them, empties gone.
            List<String> aliases = Arrays.stream(typed.split(","))
                    .map(String::trim).filter(alias -> !alias.isEmpty()).toList();
            send(op.apply(stringArray(aliases)));
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(tree, path);
        InspectField.Result<?> result = field.parse(typed);
        if (!result.ok()) {
            status(result.error(), true);
            rebuildWidgets();
            return;
        }
        send(op.apply(jsonOf(result.value())));
    }

    /** A chapter flag's press: the opposite of what the chapter tree says now. */
    private void pressChapterToggle(String path) {
        if (!mayEditNow()) {
            return;
        }
        JsonObject chapter = ClientChapterReplica.chapterTree(effectiveChapter());
        send(new EditorOp.SetChapter(path, new JsonPrimitive(!flagOn(chapter, path))));
    }

    /**
     * A toggle's press: the opposite of what the tree says now, which is the flag's own truth and not
     * the label's -- the label was decided when the rows were built, the tree is what a commit answers.
     *
     * <p>A dependency row's press is a remove, and it is one op with the whole remaining list: there is
     * no "remove one" op, and computing the list here from the replica is the same move adding makes.
     */
    private void pressQuestToggle(String key) {
        JsonObject quest = replicaQuest();
        String target = editTarget();
        if (!mayEditNow() || target == null || quest == null) {
            return;
        }
        if (key.startsWith(QuestPanelLayout.DEPENDENCY_PREFIX)) {
            String dependency = key.substring(QuestPanelLayout.DEPENDENCY_PREFIX.length());
            List<String> remaining = QuestPanelLayout.strings(quest, "dependsOn").stream()
                    .filter(each -> !each.equals(dependency)).toList();
            send(new EditorOp.SetField(target, "dependsOn", stringArray(remaining)));
            return;
        }
        send(new EditorOp.SetField(target, key, new JsonPrimitive(!flagOn(quest, key))));
    }

    /** The panel's action rows: adding a dependency, a task, a reward; and the type picker's rows. */
    private void pressQuestAction(String key) {
        if (key.equals(QuestPanelLayout.DEPENDENCY_ADD)) {
            addDependency("");
        }
        else if (key.equals(QuestPanelLayout.DEPENDENCY_PICK)) {
            armDependencyPick();
        }
        else if (key.equals(QuestPanelLayout.ADD_TASKS) || key.equals(QuestPanelLayout.ADD_REWARDS)) {
            // The type picker: the same body, listing what can be added. One state, so Escape and the
            // rows' own presses have one place to look.
            pickingEntryType = key.equals(QuestPanelLayout.ADD_TASKS) ? "tasks" : "rewards";
            rebuildWidgets();
        }
        else if (key.startsWith(QuestPanelLayout.TYPE_PREFIX)) {
            pressTypeRow(key.substring(QuestPanelLayout.TYPE_PREFIX.length()));
        }
    }

    /**
     * One row of the type picker: add a fresh entry of that type, and close the picker.
     *
     * <p>The tree is the type's own default, encoded by the type's codec -- so what the picker adds is
     * exactly what the loader reads back, and an addon's type is addable the day it registers. A type
     * whose defaults cannot be encoded is refused out loud rather than inserted half-formed.
     */
    private void pressTypeRow(String typeId) {
        String member = pickingEntryType;
        if (member == null || editTarget() == null) {
            return;
        }
        JsonObject entry = QuestPanelLayout.defaultEntry(member, typeId);
        if (entry == null) {
            status("This build cannot add a " + typeId + " here", true);
            return;
        }
        JsonObject quest = replicaQuest();
        int at = quest == null ? 0 : QuestPanelLayout.strings(quest, member).size();
        if (quest != null && quest.has(member) && quest.get(member).isJsonArray()) {
            at = quest.getAsJsonArray(member).size();
        }
        send(new EditorOp.Insert(editTarget(), member, at, entry));
        pickingEntryType = null;
        status("Added " + typeId, false);
    }

    /**
     * An entry row's two controls: Copy duplicates the entry beside itself, the cross removes it.
     *
     * <p>Both are one op each, so undo is one step each -- and the copy is the entry's own tree, which
     * is what keeps a task's unknown-to-the-panel fields when it is duplicated.
     */
    private void pressEntry(String key, boolean copy) {
        JsonObject quest = replicaQuest();
        if (quest == null || editTarget() == null) {
            return;
        }
        String rest = key.startsWith("h:") ? key.substring(2) : key;
        int dot = rest.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String member = rest.substring(0, dot);
        int index;
        try {
            index = Integer.parseInt(rest.substring(dot + 1));
        }
        catch (NumberFormatException notAnEntry) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        if (found == null || !found.isJsonObject()) {
            return;
        }
        if (copy) {
            send(new EditorOp.Insert(editTarget(), member, index + 1, found.getAsJsonObject().deepCopy()));
            status("Copied " + member.substring(0, member.length() - 1) + " " + (index + 1), false);
        }
        else {
            send(new EditorOp.Remove(editTarget(), member, index));
            status("Removed " + member.substring(0, member.length() - 1) + " " + (index + 1), false);
        }
    }

    /**
     * The edit bar's Delete: the first press arms it, the second deletes.
     *
     * <p>A quest is a file, and the model's delete renames rather than destroys -- but "recoverable"
     * is not the same as "obvious", and one keystroke away from a mis-click is exactly where the
     * confirmation belongs. Any other press disarms, so the label on screen always matches what the
     * next press will do.
     */
    private void pressDeleteQuest() {
        if (editTarget() == null) {
            return;
        }
        if (!confirmingDelete) {
            confirmingDelete = true;
            rebuildWidgets();
            return;
        }
        send(new EditorOp.Delete(editTarget()));
        confirmingDelete = false;
        closeOverlay();
    }

    /** The edit bar's Duplicate: a copy of the quest, beside it, under a fresh id. */
    private void duplicateQuest() {
        if (editTarget() == null) {
            return;
        }
        send(new EditorOp.Duplicate(editTarget()));
        closeOverlay();
    }

    /** The edit bar's Copy: the quest's own tree onto the session clipboard, as Ctrl+C would. */
    private void copyQuest() {
        JsonObject quest = replicaQuest();
        if (quest == null) {
            return;
        }
        ClientEditorClipboard.copy(List.of(quest));
        status("Copied " + editTarget(), false);
    }

    /**
     * Which of an entry's two drawn controls a point is on: "copy", "remove", or null.
     *
     * <p>Drawn, not hosted: a list row carries one widget and an entry has two controls, so the pair
     * is drawn by the panel and hit-tested here -- from the same {@code stripHalves} derivation the
     * drawing uses, which is the radius stepper's arrangement and exists for the same reason.
     */
    private String entryActionAt(double mouseX, double mouseY) {
        if (questLayout == null) {
            return null;
        }
        Viewport view = overlayView.viewport();
        for (InspectRow row : questRows) {
            if (!row.isEntry()) {
                continue;
            }
            Slot slot = questLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            List<Slot> halves = InspectLayout.stripHalves(slot);
            if (InspectLayout.onScreen(view, halves.get(0)).contains(mouseX, mouseY)) {
                return "copy";
            }
            if (InspectLayout.onScreen(view, halves.get(1)).contains(mouseX, mouseY)) {
                return "remove";
            }
        }
        return null;
    }

    /** The key of the entry row whose strip holds a point, or null. The pair to {@link #entryActionAt}. */
    private String entryUnder(double mouseX, double mouseY) {
        if (questLayout == null) {
            return null;
        }
        Viewport view = overlayView.viewport();
        for (InspectRow row : questRows) {
            if (!row.isEntry()) {
                continue;
            }
            Slot slot = questLayout.slot(row.key());
            if (slot != null && InspectLayout.onScreen(view, slot).contains(mouseX, mouseY)) {
                return row.key();
            }
        }
        return null;
    }

    /**
     * Adds one dependency, by the id typed into the Add row.
     *
     * <p>The two refusals the server would make anyway are made here first, because a refusal that
     * arrives after a round trip reads as a delay rather than as an answer: a duplicate is already in
     * the list, and a quest cannot depend on itself. Everything else -- a mistyped id, a cycle, a quest
     * in another chapter -- is the loader's validator, and its message is the one the reply carries.
     */
    /**
     * Opens a description's link in the platform's browser -- <b>http and https only</b>.
     *
     * <p>A description is content somebody else may have written -- a published questline -- so the scheme
     * is checked here rather than trusted: a {@code file:} target or a made-up scheme is refused with a word
     * in the status line, never opened and never silently ignored.
     */
    private void openLink(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        }
        catch (IllegalArgumentException malformed) {
            status("That link is not a valid address", true);
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            status("Only http and https links open", true);
            return;
        }
        net.minecraft.Util.getPlatform().openUri(uri);
    }

    /**
     * Arms the dependency pick: the next canvas click names the quest to depend on, Escape cancels.
     *
     * <p>The card closes with it, because the pick needs the canvas the card covers -- the one gesture the
     * modal cannot host -- so <b>what the pick is for is captured here</b>: the edited quest, the chapter
     * it lives in, and the prerequisites it already has. Reading those at the click would read them after
     * the card that knew them is gone.
     *
     * <p>The author may switch chapter or group between arming and picking, and that is the point rather
     * than a tolerated side effect: a prerequisite may live anywhere in the tree, ids resolve across the
     * whole file, and the operation is sent to the chapter that was being *edited* however far the sidebar
     * has moved since. See {@code addPickedDependency}.
     */
    private void armDependencyPick() {
        String quest = editTarget();
        if (!mayEditNow() || quest == null) {
            status("Open a quest to add a prerequisite to", true);
            return;
        }
        pendingPick = new DependencyPick(quest, effectiveChapter(),
                QuestPanelLayout.strings(replicaQuest(), "dependsOn"));
        pickingDependency = true;
        closeOverlay();
        status("Click the quest to depend on \u2014 any chapter or group \u2014 Escape cancels", false);
    }

    /**
     * Lands the pick: the quest that was clicked becomes a prerequisite of the quest the pick was armed
     * for.
     *
     * <p>The clicked quest may be in any chapter or group -- a dependency is an id, and the loader resolves
     * ids across the whole tree -- so what is checked is the id, not where it was clicked. The refusals are
     * said here, in the status line, rather than landing as an operation the server then refuses: a pick
     * that silently did nothing was the report.
     */
    private void addPickedDependency(String id) {
        DependencyPick pick = pendingPick;
        pendingPick = null;
        if (pick == null) {
            return;
        }
        if (id.equals(pick.quest())) {
            status("A quest cannot depend on itself", true);
            return;
        }
        if (pick.dependsOn().contains(id)) {
            status(pick.quest() + " already depends on " + id, true);
            return;
        }
        List<String> next = new ArrayList<>(pick.dependsOn());
        next.add(id);
        // Sent to the chapter that was being edited, not to the one on screen: the pick may have been
        // taken in another chapter entirely.
        TaskedNetworking.sendEditorOp(pick.chapter(),
                new EditorOp.SetField(pick.quest(), "dependsOn", stringArray(next)));
        status(pick.quest() + " now depends on " + id, false);
    }

    private void addDependency(String typed) {
        JsonObject quest = replicaQuest();
        String target = editTarget();
        if (!mayEditNow() || target == null || quest == null) {
            return;
        }
        if (typed.isEmpty()) {
            status("Type the id of the quest to depend on, then Enter", true);
            rebuildWidgets();
            return;
        }
        if (typed.equals(target)) {
            status("A quest cannot depend on itself", true);
            rebuildWidgets();
            return;
        }
        List<String> dependencies = new ArrayList<>(QuestPanelLayout.strings(quest, "dependsOn"));
        if (dependencies.contains(typed)) {
            status(target + " already depends on " + typed, true);
            rebuildWidgets();
            return;
        }
        dependencies.add(typed);
        send(new EditorOp.SetField(target, "dependsOn", stringArray(dependencies)));
    }

    /** Folds or unfolds one of the quest panel's sections. */
    private void foldQuestSection(String key) {
        if (!questFolded.remove(key)) {
            questFolded.add(key);
        }
        rebuildWidgets();
    }

    /** A typed value as the wire carries it: the shape the op's reader takes back apart. */
    private static JsonElement jsonOf(Object value) {
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static JsonElement stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
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

    /**
     * Picks a colour by name: selected, colours unfolded, and the list scrolled to its row.
     *
     * <p>What pressing a part of the sample does — *"allow me to click the things in the preview thing at
     * the top to instantly kinda get me to it in the colours menu"*. The scroll is the point of it, and it
     * happens <b>after</b> the rebuild rather than before: the row it is looking for is a slot in the layout
     * that rebuild produced.
     *
     * <p>No toggling, unlike a row in the list: a part of the sample always means "this colour" and never
     * "off", because there is nothing about pressing a button in a picture that could mean "not that one".
     */
    private void selectColour(String token) {
        // The full name rather than an import: this file names two things in Armature and the shorter alias
        // has been tried. See the same spelling in `labelOfToken`.
        if (dev.ellipog.armature.client.ui.ThemeToken.byId(token) == null) {
            return;
        }
        toolsSelected = token;
        toolsColoursOpen = true;
        rebuildWidgets();
        var row = toolsLayout == null ? null : toolsLayout.slot(ToolsLayout.tokenKey(token));
        if (row != null) {
            toolsView.scrollTo(row.y() - toolsFrame.list().height() / 3);
        }
        status(labelOfToken(token) + " - " + hexOf(token), false);
    }

    /** The shape row's arrows: one step of corner radius each. */
    private void stepRadius(int delta) {
        ClientAppearance.LOOK.setRadius(net.minecraft.util.Mth.clamp(ClientAppearance.LOOK.radius() + delta,
                Look.MIN_RADIUS, Look.MAX_RADIUS));
        status("Border radius " + ClientAppearance.LOOK.radius()
                + (ClientAppearance.LOOK.radiusChosen() ? "  (theme's own: " + themeRadius() + ")" : ""), false);
        rebuildWidgets();
    }

    /** The theme's own radius, for the message: what a Revert would go back to. */
    private static int themeRadius() {
        var theme = Themes.any(ClientAppearance.LOOK.currentName());
        return theme == null ? 0 : theme.cornerRadius();
    }

    /** The selected colour's value, as the field should show it. */
    private String hexOf(String token) {
        return token == null ? "" : String.format("#%06X", ClientAppearance.LOOK.main().colour(token) & 0xFFFFFF);
    }

    /**
     * A hex code typed into the band's field.
     *
     * <p>Accepts what a person types: `#RRGGBB`, `RRGGBB`, `#RGB`, or `#AARRGGBB` when the alpha matters.
     * Only RGB keeps the colour's own alpha, because setting a colour to translucent by mistake is not
     * recoverable by looking at it -- and three of the forty-one tokens are translucent on purpose. The
     * reading itself is {@code HexColour}'s, where the cases are asserted; what is here is the two things
     * this screen does with the answer.
     *
     * <p>A value it cannot read is reported and changes nothing: the field goes back to the colour it
     * belongs to, so a typo costs a message rather than a broken theme.
     */
    private void applyHex(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty() || toolsSelected == null) {
            return;
        }
        Integer argb = HexColour.parse(text, ClientAppearance.LOOK.main().colour(toolsSelected));
        if (argb == null) {
            status("\"" + text + "\" is not a hex colour - try #4A90D9", true);
            // The field goes back to the colour it belongs to, silently. Rebuilding the widgets here is what
            // it used to do, and a rebuild blurs the field being submitted, which submits again: the message
            // above and this line are the whole answer.
            refreshHexField();
            return;
        }
        ClientAppearance.LOOK.setCustom(toolsSelected, argb);
        status(labelOfToken(toolsSelected) + " set to " + String.format("#%08X", argb), false);
        refreshHexField();
    }

    /**
     * Puts the field back to the selected colour's code, without the field calling back.
     *
     * <p>{@code setValue} is the model's silent setter — no handler, no focus change — which is the whole
     * reason it exists rather than a call to {@code submit()}: a field told what to say has nothing to
     * report. The panel's colours, swatches and number are drawn from the theme every frame, so a colour
     * change needs no rebuilding at all.
     */
    private void refreshHexField() {
        if (hexField != null) {
            hexField.setValue(hexOf(toolsSelected));
        }
    }

    /** One channel stepper: eight steps of one channel of the selected colour. */
    private void nudgeChannel(String channel, int step) {
        String token = toolsSelected;
        if (token == null) {
            return;
        }
        int argb = ClientAppearance.LOOK.main().colour(token);
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        int value = net.minecraft.util.Mth.clamp(((argb >>> shift) & 0xFF) + step, 0, 255);
        ClientAppearance.LOOK.setCustom(token, (argb & ~(0xFF << shift)) | (value << shift));
        rebuildWidgets();
    }

    /**
     * Undoes the edits to what the panel is about: the selected colour, or -- with nothing selected --
     * the corner radius, which has no selection of its own now that its arrows live in its row.
     */
    private void revertSelected() {
        if (toolsSelected == null) {
            ClientAppearance.LOOK.clearRadius();
            status("Border radius back to the theme's own", false);
            rebuildWidgets();
            return;
        }
        if (ToolsLayout.RADIUS.equals(toolsSelected)) {
            ClientAppearance.LOOK.clearRadius();
            status("Border radius back to the theme's own", false);
            rebuildWidgets();
            return;
        }
        ClientAppearance.LOOK.clearCustom(toolsSelected);
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
        String saved = ClientAppearance.LOOK.saveAsTheme(null);
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
        modalRedraws.clear();
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
        // the control back out. The tooltip carries the word. The glyph is a house -- "back to where the
        // view started" -- which is one of the few symbols this font has; the fisheye it used to be was
        // not, and drew as the missing-glyph box. See `BookGeometry.TOOLS_BUTTON_WIDTH`.
        control(controls.get("centre"), Component.literal("\u2302"), () -> {
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
        closeButton = control(controls.get("close"), Component.literal("\u00d7"), () -> {
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
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(QuestAuthority.EDIT_LEVEL);
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

        // An author opening a quest gets the editor, not the reader: the same card, with the fields
        // editable in place and the gameplay buttons gone. The reader's view is untouched for everyone
        // who may not edit.
        if (mayEditNow()) {
            buildQuestEditorWidgets(entry);
            return;
        }
        questDeleteButton = null;

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
     * The quest editor's widgets: the edit bar in the footer, and one control per inspector row.
     *
     * <p>Built exactly like the dock's lists -- register a widget under the row's key, let the
     * ScrollView place it from the same layout the drawing reads -- because that is what keeps a
     * scrolled control beside the row it belongs to. The rows themselves come from
     * {@code QuestPanelLayout}, so the modal and the dock cannot drift about what a quest has.
     */
    private void buildQuestEditorWidgets(ClientQuestCache.Entry entry) {
        // The edit bar, in the footer the reader uses for Submit and Claim -- hidden while editing,
        // because an author's own progress is noise on the page they are writing. Delete asks once:
        // the first press arms it and says so, the second deletes, and anything else disarms it.
        Map<String, BookGeometry.Rect> controls = geometry().overlayControls(true);
        BookGeometry.Rect slot = controls.get("submit");
        if (slot != null) {
            // The footer's real width -- from the card's inset to the Done button -- not the reader's
            // Submit slot: four buttons in that slot truncated every label ("Del...", "Dup..."), which
            // is a bar that cannot say what its buttons do.
            BookGeometry.Rect back = controls.get("back");
            int gap = 2;
            int left = slot.x();
            int right = back == null ? slot.right() + 200 : back.x() - 8;
            int quarter = Math.max(0, (right - left - gap * 3) / 4);
            BookGeometry.Rect delete = BookGeometry.Rect.at(left, slot.y(), quarter, slot.height());
            BookGeometry.Rect duplicate = BookGeometry.Rect.at(delete.right() + gap, slot.y(),
                    quarter, slot.height());
            BookGeometry.Rect copy = BookGeometry.Rect.at(duplicate.right() + gap, slot.y(),
                    quarter, slot.height());
            BookGeometry.Rect settings = BookGeometry.Rect.at(copy.right() + gap, slot.y(),
                    Math.max(0, right - copy.right() - gap), slot.height());
            questDeleteButton = control(delete,
                    Component.literal(confirmingDelete ? "Really delete?" : "Delete"),
                    this::pressDeleteQuest);
            if (questDeleteButton != null) {
                questDeleteButton.textColour(
                        confirmingDelete ? ArmatureTheme.blocked() : ArmatureTheme.body());
            }
            control(duplicate, Component.literal("Duplicate"), this::duplicateQuest)
                    .textColour(ArmatureTheme.body());
            control(copy, Component.literal("Copy"), this::copyQuest)
                    .textColour(ArmatureTheme.body());
            settingsButton = control(settings, Component.literal("Settings"), this::toggleSettings);
            if (settingsButton != null) {
                settingsButton.textColour(ArmatureTheme.body()).selected(settingsOpen)
                        .tooltip(Component.literal("Placement, rules and aliases"));
            }
        }
        ArmatureButton done = control(controls.get("back"), Component.literal("Done"), this::closeOverlay);
        if (done != null) {
            done.textColour(ArmatureTheme.body()).tooltip(Component.literal("Escape also closes this"));
        }

        // The item picker's search box: the one widget it has, because every row is drawn and hit-tested
        // by the same derivation the list class owns. Rebuilt with the value it already held -- a tick
        // rebuild (a replica arriving mid-search) must not clear what is being typed.
        if (pickingItemPath != null) {
            BookGeometry.Rect bodyRect = BookGeometry.Rect.at(overlayBody().originX(),
                    overlayBody().originY(), overlayBody().viewWidth(), overlayBody().viewHeight());
            ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(bodyRect);
            String kept = itemSearch == null ? "" : itemSearch.value();
            itemSearch = new ArmatureTextField(frame.search().x(), frame.search().y(),
                    frame.search().width(), frame.search().height(), kept);
            // Enter and Escape are the screen's while the picker is open -- see `keyPressed` -- so a blur
            // must commit nothing: a rebuild that blurred the box would otherwise set the field to the
            // text it happened to be holding.
            itemSearch.onSubmit(text -> { });
            itemSearch.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
            addRenderableWidget(itemSearch);
            // **Redrawn after the card, because the widget pass runs before it.** This is the third
            // time this exact ordering has cost something: the modal's card is painted after
            // `super.render`, so a field left to that pass is painted over -- every other field in
            // this card carries the same redraw for the same reason. The placeholder goes after the
            // field in the same redraw, because it is drawn over the field's own fill; and only when
            // the box is empty, which is the whole of what a placeholder is.
            modalRedraws.add(r -> {
                itemSearch.render(r);
                if (itemSearch.value().isEmpty()) {
                    r.text("Search items \u2014 name or id", frame.search().x() + 4,
                            frame.search().y() + (frame.search().height() - 8) / 2,
                            ArmatureTheme.faint());
                }
            });
            setFocused(itemSearch);
        }
        else {
            itemSearch = null;
        }

        // The body is the preview itself now -- drawn, not a list of widgets -- so the only rows that
        // still need hosting are the type picker's, when it is open.
        if (pickingEntryType != null) {
            questRows = QuestPanelLayout.typeRows(pickingEntryType);
            Viewport body = overlayBody();
            questLayout = InspectLayout.build(questRows, body.viewWidth(), Measure.monospace(6, 9));
            overlayView.clear();
            overlayView.whole(true);
            overlayView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                    body.viewHeight());
            for (InspectRow row : questRows) {
                if (row.kind() == InspectRow.Kind.ACTION) {
                    ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                            () -> pressQuestAction(row.key()));
                    button.alignLeft(true).flat(true);
                    overlayView.put(row.key(), button);
                }
            }
            overlayView.apply(questLayout, body.viewWidth());
        }

        // The settings popover's rows, when it is open. Rows are the right shape here -- it is a
        // popover of secondary fields, which is what the fourth playtest said rows are for.
        settingsRows = List.of();
        settingsLayout = null;
        if (settingsOpen) {
            settingsRows = QuestPanelLayout.settingsRows(replicaQuest(), questFolded);
            BookGeometry.Rect popover = settingsPopover();
            settingsLayout = InspectLayout.build(settingsRows, popover.width() - 12,
                    Measure.monospace(6, 9));
            settingsView.clear();
            settingsView.whole(true);
            // The rows' own rectangle -- below the title, inset like the drawing -- not the panel's:
            // a widget placed from a viewport that does not match the clip sits beside its label.
            settingsView.viewport().bounds(popover.x() + 6, popover.y() + 20,
                    popover.width() - 12, popover.height() - 26);
            for (InspectRow row : settingsRows) {
                switch (row.kind()) {
                    case FIELD -> {
                        ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, row.value());
                        String path = row.key();
                        field.onSubmit(text -> commitField(path, text, false));
                        field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                                ArmatureTheme.panelEdge());
                        settingsView.put(row.key(), field, InspectLayout::strip);
                        addRenderableWidget(field);
                        modalRedraws.add(field::render);
                    }
                    case TOGGLE -> {
                        ArmatureButton button = control(0, 0, 0, 0,
                                Component.literal(flagOn(replicaQuest(), row.key()) ? "Off" : "On"),
                                () -> pressQuestToggle(row.key()));
                        button.textColour(ArmatureTheme.body());
                        settingsView.put(row.key(), button, InspectLayout::strip);
                    }
                    case HEADING -> {
                        ArmatureButton button = control(0, 0, 0, 0, Component.literal(""),
                                () -> foldQuestSection(row.key()));
                        button.flat(true);
                        settingsView.put(row.key(), button);
                    }
                    default -> {
                    }
                }
            }
            settingsView.apply(settingsLayout, popover.width() - 12);
        }
    }

    /**
     * Where the settings popover sits: above the button that opens it, kept inside the card.
     *
     * <p>Above, because the button is in the footer and there is no room below it; anchored to the
     * button rather than centred, because a popover that appears somewhere else is a popover the
     * pointer has to go looking for.
     */
    private BookGeometry.Rect settingsPopover() {
        BookGeometry.Rect card = geometry().modal();
        int width = Math.min(360, card.width() - 40);
        int height = Math.min(400, card.height() - 110);
        int x = settingsButton == null ? card.x() + 8
                : Math.max(card.x() + 8, Math.min(settingsButton.getX(), card.right() - 8 - width));
        int y = settingsButton == null ? card.y() + 60
                : Math.max(card.y() + 56, settingsButton.getY() - 8 - height);
        return BookGeometry.Rect.at(x, y, width, height);
    }

    /**
     * The editor's body: <b>the preview itself</b>, with the things that can be edited marked.
     *
     * <p>The fourth playtest's design and the row inspector it rejected: the card an author edits is
     * the card a player reads. A title is a title until the pointer is over it, prose is prose, task
     * rows are the row's own parts -- icon, count, name, consume -- and the editors appear in place
     * when a piece is pressed. The reader's layout is the editor's layout, with add rows in it, so the
     * two cannot disagree about where anything sits; {@code editTargets} is rebuilt here every frame
     * and read by the click, one derivation for the drawing and the press.
     */
    private void drawQuestEditor(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY,
                                 long now) {
        BookGeometry.Rect card = geometry().modal();
        Viewport body = overlayBody();
        editTargets.clear();
        pendingLabels.clear();
        // The row positions are this frame's, rebuilt with the rows themselves: a release must aim at
        // the list the pointer saw, not at last frame's.
        dragRowSlots.clear();
        // The reader's rectangles are stale while the editor draws: a press on the raw description must open
        // the field, not follow a link that is no longer on screen.
        readerProse.clear();
        linkRects.clear();
        // The header is the reader's, already drawn above this call -- text, state tag, chapter line
        // and all. This adds only the marks and the targets: drawing the title again here is how the
        // subtitle line came out garbled, two texts on top of each other.
        drawEditHeaderMarks(r, entry, mouseX, mouseY);

        // The item picker takes the body when it is open, on the type picker's own terms: the list is
        // what you are reading, the card is the page you are setting a field on, and the page behind a
        // list is context rather than a second thing to press.
        if (pickingItemPath != null) {
            drawItemPicker(r, body, mouseX, mouseY);
            drawSettings(r, mouseX, mouseY);
            return;
        }

        // The type picker takes the body when it is open: a list of types is a list, and rows are what
        // lists are made of. The only rows left in this card.
        if (pickingEntryType != null) {
            if (questLayout != null) {
                overlayView.apply(questLayout, body.viewWidth());
                QuestPanel.drawRows(r, BookGeometry.Rect.at(body.originX(), body.originY(),
                        body.viewWidth(), body.viewHeight()), overlayView.viewport(), questLayout,
                        questRows, mouseX, mouseY);
            }
            drawSettings(r, mouseX, mouseY);
            return;
        }

        JsonObject quest = replicaQuest();
        if (quest == null) {
            r.text("Waiting for the chapter's copy\u2026", body.originX() + 6, body.originY() + 6,
                    ArmatureTheme.faint());
            drawSettings(r, mouseX, mouseY);
            return;
        }

        List<String> description = shownDescription(entry, quest);
        int tasks = arraySize(quest, "tasks");
        int rewards = arraySize(quest, "rewards");
        List<String> dependencies = QuestPanelLayout.strings(quest, "dependsOn");
        Layout layout = OverlayLayout.stack(editorProse(r, description, body.viewWidth()), tasks, rewards,
                        dependencies.size(), true)
                .build(body.viewWidth(), textMeasure(r));
        overlayView.apply(layout, body.viewWidth());

        try (GuiRenderer.Scoped clip = r.clip(body)) {
            Measure measure = textMeasure(r);
            // One drawing of the prose, and while the description is being edited the editor's is the
            // one: the field is transparent so the text does not move, so a second copy underneath is
            // not hidden by it -- it is printed on top of it, a line apart wherever the two models
            // disagree. See `InlineEdit.replaces`.
            if (!InlineEdit.replaces("description", editingPath)) {
                drawDescription(r, description, layout, body, measure);
            }
            // The description's box, registered with **no mark drawn around it**: the prose is the mark,
            // and a border around prose is one more thing in the author's way (the report, after three
            // rounds of making that border behave: *"just remove the outline around the description box
            // completely, its easier"*). The box is still the press's answer to "where is the
            // description" and the field's answer to where it goes -- it is only never painted.
            BookGeometry.Rect prose = descriptionBox(description.size(), layout, body);
            registerTarget(EditAction.FIELD, "description", prose,
                    prose.x() + OverlayLayout.PROSE_PAD, prose.y() + OverlayLayout.PROSE_PAD,
                    join(description), null, -1);

            drawHeading(r, placed(layout, body, OverlayLayout.TASKS_HEADING), "TASKS");
            if (tasks == 0) {
                // The row the layout reserves either way ("a row either way, so an empty list and a
                // one-item list take the same space") -- which the editor left *blank*, so an empty
                // section was a heading, a hole, and then the add row. The reader's own words go in it.
                drawEmptyState(r, placed(layout, body, OverlayLayout.NO_TASKS), "Nothing required");
            }
            for (int i = 0; i < tasks; i++) {
                drawEntryRow(r, placed(layout, body, OverlayLayout.taskKey(i)), memberEntry(quest, "tasks", i),
                        "tasks", i, mouseX, mouseY);
            }
            drawAddRow(r, placed(layout, body, OverlayLayout.TASKS_ADD), "+ Add task",
                    EditAction.ADD_TASK, mouseX, mouseY);

            drawHeading(r, placed(layout, body, OverlayLayout.REWARDS_HEADING), "REWARDS");
            if (rewards == 0) {
                drawEmptyState(r, placed(layout, body, OverlayLayout.NO_REWARDS), "Nothing");
            }
            for (int i = 0; i < rewards; i++) {
                drawEntryRow(r, placed(layout, body, OverlayLayout.rewardKey(i)),
                        memberEntry(quest, "rewards", i), "rewards", i, mouseX, mouseY);
            }
            drawAddRow(r, placed(layout, body, OverlayLayout.REWARDS_ADD), "+ Add reward",
                    EditAction.ADD_REWARD, mouseX, mouseY);

            // The insertion line last in the clip, over every row it sits between: a line drawn where
            // the rows are drawn is a line the next row paints over.
            if (dragRowLive) {
                drawRowDragIndicator(r, dragRowSlots.get(dragRowMember), dragRowPointerY,
                        BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(),
                                body.viewHeight()));
            }

            drawHeading(r, placed(layout, body, OverlayLayout.REQUIRES_HEADING), "REQUIRES");
            for (int i = 0; i < dependencies.size(); i++) {
                drawDependencyRow(r, placed(layout, body, OverlayLayout.dependencyKey(i)),
                        dependencies.get(i), mouseX, mouseY);
            }
            drawDependencyAddRow(r, placed(layout, body, OverlayLayout.REQUIRES_ADD), mouseX, mouseY);
        }
        overlayView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());

        // Where the open editor goes, every frame. The card scrolls under it -- the wheel is answered by
        // the body even while a field has the keyboard -- and the description's height has to track the
        // text being written as the card grows. Both are the same question -- where is the target now --
        // so both are answered here, from the frame's own target list, rather than a second time for the
        // area alone. See `inlineBox` for the geometry.
        repositionInlineEditor();

        drawSettings(r, mouseX, mouseY);

        // The waiting labels are drawn after the clip, with the tooltips -- see `drawPendingLabels` for
        // why "last in the card" was not enough.

    }

    /**
     * The marks on the reader's own header -- icon, title and the chapter/subtitle line.
     *
     * <p>Marks only. The text, the state tag and the chapter line are drawn by the read path, which
     * runs before this; drawing them a second time here is what put two subtitles on top of each other.
     */
    private void drawEditHeaderMarks(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY) {
        BookGeometry.Rect card = geometry().modal();
        int iconX = card.x() + 14;
        int iconY = card.y() + (46 - HEADER_ICON) / 2;

        target(r, EditAction.ITEM, "icon.item",
                BookGeometry.Rect.at(iconX - 1, iconY - 1, HEADER_ICON + 2, HEADER_ICON + 2),
                iconX + 1, iconY + (HEADER_ICON - 8) / 2, "", null, -1, mouseX, mouseY);

        int textX = iconX + HEADER_ICON + 6;
        target(r, EditAction.FIELD, "title", BookGeometry.Rect.at(textX - 2, card.y() + 10,
                        Math.max(60, r.textWidth(entry.title()) + 6), 12),
                textX, card.y() + 12, entry.title(), null, -1, mouseX, mouseY);

        String where = entry.chapterTitle()
                + (entry.subtitle().isEmpty() ? "" : "  \u00b7  " + entry.subtitle());
        target(r, EditAction.FIELD, "subtitle", BookGeometry.Rect.at(textX - 2, card.y() + 24,
                        Math.max(80, r.textWidth(where) + 6), 12),
                textX, card.y() + 26, entry.subtitle(), null, -1, mouseX, mouseY);
    }

    /**
     * Registers a target and draws its affordance, so the mark and the press cannot disagree.
     *
     * <p>{@code textX} and {@code textY} are where the read path draws the value's text -- the box is
     * the affordance, which hugs the whole piece and is padded, while the text inside it is inset
     * (four pixels for a number, past the icon for an item, two for the title, four inside the
     * description's frame). The editor's field is placed from <b>those</b> rather than from the box,
     * which is what stops a click moving the value: the field draws its text at the text's own origin,
     * because that is where the text was -- and it stays there when the frame around it is padded.
     */
    private void target(GuiRenderer r, EditAction action, String path, BookGeometry.Rect box, int textX,
                        int textY, String value, String member, int index, int mouseX, int mouseY) {
        if (registerTarget(action, path, box, textX, textY, value, member, index)) {
            drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        }
    }

    /**
     * The same, without the mark: for a piece whose mark is drawn somewhere the press is not -- the
     * description's frame, which is drawn outside the content's clip. Returns whether anything was
     * registered, so a caller drawing the mark can skip a box with no size.
     */
    private boolean registerTarget(EditAction action, String path, BookGeometry.Rect box, int textX,
                                   int textY, String value, String member, int index) {
        if (box.width() <= 0 || box.height() <= 0) {
            return false;
        }
        editTargets.add(new EditTarget(action, path, box, textX, textY, value, member, index));
        return true;
    }

    /**
     * The mark on something editable: a faint box at rest, brighter under the pointer.
     *
     * <p>The playtest's answer for how an author finds the editable parts: discoverable without
     * hovering, quiet enough that the card still reads as the preview it is. Four one-pixel lines, so
     * the text inside is never dimmed by its own affordance.
     */
    private static void drawEditAffordance(GuiRenderer r, BookGeometry.Rect box, boolean hot) {
        int colour = hot
                ? Colour.alphaOf(ArmatureTheme.title(), 0.55F)
                : Colour.alphaOf(ArmatureTheme.faint(), 0.30F);
        r.fill(box.x(), box.y(), box.right(), box.y() + 1, colour);
        r.fill(box.x(), box.bottom() - 1, box.right(), box.bottom(), colour);
        r.fill(box.x(), box.y() + 1, box.x() + 1, box.bottom() - 1, colour);
        r.fill(box.right() - 1, box.y() + 1, box.right(), box.bottom() - 1, colour);
    }

    /**
     * One task or reward row, in the editor's language: its own parts, then Copy and the cross.
     *
     * <p>A known type's parts come from {@code QuestPanelLayout.entryParts} -- the item, the count, the
     * consume flag -- drawn as the row the reader sees, with each piece pressable. An unknown type
     * keeps the reader's warning row and gains one box: the raw JSON, editable whole.
     */
    private void drawEntryRow(GuiRenderer r, Slot slot, JsonObject entry, String member, int index,
                              int mouseX, int mouseY) {
        if (slot == null || entry == null) {
            return;
        }
        String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString() : "not stated";
        boolean known = QuestPanelLayout.knownType(type);
        // The slot's own x: the layout already insets these rows, and adding a second indent on top
        // of it is what pushed every row sixty pixels right of the heading it belongs to.
        int x = slot.x();
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);

        // The type's own icon first, the same one the reader's row leads with: without it a task and
        // a reward are two anonymous boxes of text.
        BookGeometry.Rect iconBox = BookGeometry.Rect.at(x, y, 16, height);
        r.icon(typeIcon(member, type), x, y + (height - 16) / 2, 16);
        x += 20;

        if (known) {
            for (QuestPanelLayout.Part part : QuestPanelLayout.entryParts(member, entry)) {
                JsonElement found = QuestPanelLayout.get(entry, part.path());
                String value = found == null || !found.isJsonPrimitive() ? "" : found.getAsString();
                int width = switch (part.kind()) {
                    // Measured against what they show: "minecraft:deepslate_bricks" is twenty-five
                    // characters, and a box that truncates the id it exists to edit is a box that
                    // cannot do its job.
                    case ITEM -> 170;
                    // Wide enough for the unit the value is read with: the row says "5 levels" and
                    // "12 XP", not "5" and "12" -- and at 44 the reader's own text was truncated to
                    // "5 lev..." in play, which is the cut-off report. The editor shows the bare number;
                    // the *box* has to hold the widest thing the row ever says in it.
                    case INTEGER -> 64;
                    case FLAG -> 84;
                    case TEXT -> 150;
                };
                BookGeometry.Rect box = BookGeometry.Rect.at(x, y, width, height);
                String levels = member.equals("rewards")
                        ? String.valueOf(entry.has("levels") && entry.get("levels").getAsBoolean())
                        : "false";
                String path = member + "." + index + "." + part.path();
                // The part the open field is editing is drawn by that field alone. This is also what
                // keeps the read side's own spelling of the value out of the way: a count reads "x8"
                // and an amount "5 XP", while the field shows the bare number you are actually editing.
                int textX = drawPart(r, box, part, value, levels,
                        InlineEdit.replaces(path, editingPath), mouseX, mouseY);

                EditAction action = switch (part.kind()) {
                    case FLAG -> EditAction.FLAG;
                    case ITEM -> EditAction.ITEM;
                    case TEXT, INTEGER -> EditAction.FIELD;
                };
                target(r, action, path, box, textX, box.y() + (box.height() - 8) / 2,
                        value, member, index, mouseX, mouseY);
                x += width + 4;
            }
        }
        else {
            r.text(type + " \u2014 not known to this build", x, y + (height - 8) / 2,
                    ArmatureTheme.blocked());
            BookGeometry.Rect raw = BookGeometry.Rect.at(slot.right() - 200, y, 92, height);
            drawEditAffordance(r, raw, raw.contains(mouseX, mouseY));
            // The label is what the field replaces; the field is transparent, so drawing both would
            // print the raw JSON over the words "edit JSON".
            if (!InlineEdit.replaces(member + "." + index, editingPath)) {
                r.text("edit JSON", raw.x() + 4, y + (height - 8) / 2,
                        ArmatureTheme.faint());
            }
            editTargets.add(new EditTarget(EditAction.RAW, member + "." + index, raw, raw.x() + 4,
                    y + (height - 8) / 2, "", member, index));
        }

        // The type's name, when the pointer is over its icon: collected, not drawn here, and placed
        // after the parts -- beside the icon is where the item box starts, and the row's empty middle
        // is the one place a label hides nothing.
        if (iconBox.contains(mouseX, mouseY)) {
            // Directly below the icon, as asked -- and safe to sit over the next row because the
            // labels are drawn after every row, not in row order.
            int labelWidth = r.textWidth(type) + 8;
            int labelX = Math.min(iconBox.x(), geometry().modal().right() - labelWidth - 6);
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(labelX, iconBox.bottom() + 2, labelWidth, 12), type));
        }

        // Copy and the cross, at the row's right edge -- the entry's own controls, drawn and
        // hit-tested from one derivation because a list row hosts one widget and an entry has two.
        List<Slot> halves = InspectLayout.stripHalves(
                new Slot("entry", slot.right() - 96, y, 96, height));
        String[] labels = {"Copy", "\u00d7"};
        EditAction[] actions = {EditAction.COPY_ENTRY, EditAction.REMOVE_ENTRY};
        for (int i = 0; i < 2; i++) {
            BookGeometry.Rect box = BookGeometry.Rect.at(halves.get(i).x(), y,
                    halves.get(i).width(), height);
            drawEditAffordance(r, box, box.contains(mouseX, mouseY));
            r.text(labels[i], box.x() + (box.width() - r.textWidth(labels[i])) / 2,
                    y + (height - 8) / 2, ArmatureTheme.body());
            editTargets.add(new EditTarget(actions[i], null, box, box.x(),
                    box.y() + (box.height() - 8) / 2, "", member, index));
        }

        // The row's leading strip is the drag's grip -- the type's icon and the gutter beside it, which
        // no part and no control covers, so registering last wins exactly the space nothing else wanted.
        // The whole row is what the gap is counted against; the grip is only where the press lands.
        dragRowSlots.computeIfAbsent(member, key -> new ArrayList<>())
                .add(BookGeometry.Rect.at(slot.x(), y, slot.width(), height));
        BookGeometry.Rect grip = BookGeometry.Rect.at(slot.x(), y, 18, height);
        target(r, EditAction.DRAG_ENTRY, null, grip, grip.x() + 4, y + (height - 8) / 2,
                "", member, index, mouseX, mouseY);
    }

    /**
     * One part of an entry: an icon and its id, a number, a word, or a flag's state.
     *
     * <p>Returns the x the part's text was drawn at, which is not the box's: the box hugs the piece and
     * the text is inset inside it. The caller hands that x to {@code target}, so a field opened over the
     * part draws its text in the same place rather than four pixels to the left of it -- see
     * {@code inlineBox}.
     *
     * <p>When {@code replaced}, the text is not drawn at all: the open field is drawing it, and two
     * drawings of one value is the fault the whole inline-editor contract exists to prevent. The icon is
     * still drawn -- an item part's field starts after it, and a blank where the icon was would read as
     * the row having lost its item.
     */
    private int drawPart(GuiRenderer r, BookGeometry.Rect box, QuestPanelLayout.Part part,
                         String value, String flagsOfAmount, boolean replaced, int mouseX, int mouseY) {
        if (part.kind() == QuestPanelLayout.Part.Kind.ITEM) {
            r.icon(itemStack(value), box.x() + 1, box.y() + 1, Math.max(8, box.height() - 2));
            String shown = value.isEmpty() ? "item" : value;
            if (!replaced) {
                r.text(Measure.truncate(shown, box.width() - box.height() - 4, textMeasure(r)),
                        box.x() + box.height() + 2, box.y() + (box.height() - 8) / 2,
                        value.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.body());
            }
            return box.x() + box.height() + 2;
        }
        if (part.kind() == QuestPanelLayout.Part.Kind.FLAG) {
            boolean on = "true".equals(value);
            if (!replaced) {
                r.text(part.label() + (on ? " on" : " off"), box.x() + 4,
                        box.y() + (box.height() - 8) / 2,
                        on ? ArmatureTheme.title() : ArmatureTheme.faint());
            }
            return box.x() + 4;
        }
        // A bare number is a number with no unit: the reader's row says "8 x Oak Log" and "5
        // levels", and an editor whose boxes say "8" and "5" makes the author read the neighbours
        // to know what they are editing.
        String shown = value;
        if (!value.isEmpty() && part.path().equals("count")) {
            shown = "x" + value;
        }
        else if (!value.isEmpty() && part.path().equals("amount")) {
            // The unit is the flag: five points or five levels are different rewards, and the
            // reader says which -- an editor that made you read the chip beside it did not.
            shown = value + ("true".equals(flagsOfAmount) ? " levels" : " XP");
        }
        if (!replaced) {
            r.text(Measure.truncate(shown.isEmpty() ? part.label() : shown, box.width() - 8,
                    textMeasure(r)), box.x() + 4, box.y() + (box.height() - 8) / 2,
                    value.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.body());
        }
        return box.x() + 4;
    }

    /** An add row: a faint box saying what pressing it does. */
    private void drawAddRow(GuiRenderer r, Slot slot, String label, EditAction action,
                            int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        BookGeometry.Rect box = BookGeometry.Rect.at(slot.x(), slot.y() + 2,
                Math.max(120, r.textWidth(label) + 24), Math.max(8, slot.height() - 4));
        drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        r.text(label, box.x() + 6, box.y() + (box.height() - 8) / 2,
                ArmatureTheme.faint());
        editTargets.add(new EditTarget(action, null, box, box.x() + 6,
                box.y() + (box.height() - 8) / 2, "", null, -1));
    }

    /** One prerequisite: its name, and a cross to remove it. */
    private void drawDependencyRow(GuiRenderer r, Slot slot, String name, int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);
        BookGeometry.Rect nameBox = BookGeometry.Rect.at(slot.x(), y,
                Math.max(60, slot.width() - 40), height);
        r.text(Measure.truncate(name, nameBox.width() - 8, textMeasure(r)), nameBox.x() + 2,
                y + (height - 8) / 2, ArmatureTheme.body());

        BookGeometry.Rect remove = BookGeometry.Rect.at(slot.right() - 30, y, 26, height);
        drawEditAffordance(r, remove, remove.contains(mouseX, mouseY));
        r.text("\u00d7", remove.x() + (remove.width() - r.textWidth("\u00d7")) / 2,
                y + (height - 8) / 2, ArmatureTheme.body());
        editTargets.add(new EditTarget(EditAction.REMOVE_DEP, name, remove, remove.x(),
                y + (height - 8) / 2, "", null, -1));
    }

    /** The requires section's add row: type an id, or pick the quest on the canvas. */
    private void drawDependencyAddRow(GuiRenderer r, Slot slot, int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);
        int x = slot.x();
        BookGeometry.Rect type = BookGeometry.Rect.at(x, y,
                Math.max(80, slot.width() - 70), height);
        drawEditAffordance(r, type, type.contains(mouseX, mouseY));
        // The prompt is a placeholder for an empty field, and the field's text would print over it --
        // so while that field is open the prompt stands down like any other value being edited.
        if (!InlineEdit.replaces(InlineEdit.DEPENDENCY_ADD, editingPath)) {
            r.text("+ Add by id", type.x() + 6, type.y() + (type.height() - 8) / 2,
                    ArmatureTheme.faint());
        }
        editTargets.add(new EditTarget(EditAction.ADD_DEP, null, type, type.x() + 6,
                type.y() + (type.height() - 8) / 2, "", null, -1));

        BookGeometry.Rect pick = BookGeometry.Rect.at(type.right() + 4, y, 60, height);
        drawEditAffordance(r, pick, pick.contains(mouseX, mouseY));
        r.text("Pick", pick.x() + 6, pick.y() + (pick.height() - 8) / 2,
                ArmatureTheme.faint());
        editTargets.add(new EditTarget(EditAction.PICK_DEP, null, pick, pick.x() + 6,
                pick.y() + (pick.height() - 8) / 2, "", null, -1));
    }

    /** The settings popover, drawn over the card: the fields the reader never sees. */
    private void drawSettings(GuiRenderer r, int mouseX, int mouseY) {
        if (!settingsOpen || settingsLayout == null) {
            return;
        }
        BookGeometry.Rect popover = settingsPopover();
        // Re-applied every frame with the panel's own rectangle, and that is the fix rather than
        // tidiness: placement ran once at build time against whatever the geometry was then, while
        // the drawing ran against the geometry now -- so the widgets drifted off the panel, some of
        // them outside it entirely. One bounds, one apply, every frame, the same rule the body uses.
        settingsView.viewport().bounds(popover.x() + 6, popover.y() + 20,
                popover.width() - 12, popover.height() - 26);
        settingsView.apply(settingsLayout, popover.width() - 12);
        ArmatureTheme.panel(r, popover.x(), popover.y(), popover.width(), popover.height(),
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        r.text("Quest settings", popover.x() + 6, popover.y() + 5, ArmatureTheme.title());
        QuestPanel.drawRows(r, BookGeometry.Rect.at(popover.x() + 6, popover.y() + 20,
                popover.width() - 12, popover.height() - 26), settingsView.viewport(),
                settingsLayout, settingsRows, mouseX, mouseY);
        settingsView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /** The description the card lays out: the tree's lines, or the open editor's own text. */
    private List<String> shownDescription(ClientQuestCache.Entry entry, JsonObject quest) {
        if (inlineArea != null && "description".equals(editingPath)) {
            // The editor's own text, exactly as typed -- including the blank line the caret is on at the
            // end. It is the commit that trims (see `commitInlineText`), so a press of Enter at the end
            // shows while it is being typed and is gone once the edit finishes.
            return List.of(inlineArea.value().split("\n", -1));
        }
        // The ends of the prose are not content; see `Prose`. The entry's own description is trimmed
        // where it is parsed (`ClientQuestCache`), so both readings of this file agree about the ends.
        List<String> lines = Prose.trimmed(QuestPanelLayout.strings(quest, "description"));
        if (!lines.isEmpty()) {
            return lines;
        }
        return entry.description();
    }

    /**
     * A section's empty row, said in the words the reader uses.
     *
     * <p>One method for both surfaces because they show the same row: the layout reserves it either way
     * (see {@code OverlayLayout.stack}), so the reader draws it and the editor used to leave it blank --
     * a heading, a hole, an add row. That hole is the spacing in the report.
     */
    private static void drawEmptyState(GuiRenderer r, Slot slot, String text) {
        if (slot != null) {
            // Centred in the row, like the text of a real row: the row is a row's height either way, so
            // text sitting on its top edge left the rest of it looking like a gap between the words and
            // whatever comes next -- the "random space" the empty quest was reported for.
            r.text(text, slot.x(), slot.y() + (slot.height() - 8) / 2, ArmatureTheme.faint());
        }
    }

    /** The prose's rectangle, for the editor's box: the paragraphs the layout placed, counted. */
    private BookGeometry.Rect descriptionBox(int paragraphs, Layout layout, Viewport body) {
        // The frame is the text area's own box: the column opened out by the area's padding, and never
        // narrower than the widest paragraph the layout placed. See `OverlayLayout.proseFrame` for both
        // halves and the play reports behind them. The slots are `scrolled`, which does not cull: the frame
        // exists at every scroll position, because the field is placed on it and the field's *drawing* is
        // what gets cut off at the body's edge -- a frame that disappeared mid-scroll took the open
        // description with it.
        if (paragraphs == 0) {
            Slot slot = scrolled(layout, body, OverlayLayout.NO_DESCRIPTION);
            return slot == null ? BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), 0)
                    : rect(OverlayLayout.proseFrame(slot, slot.bottom(), slot.right(), body.viewWidth()));
        }
        Slot first = scrolled(layout, body, OverlayLayout.proseKey(0));
        Slot last = scrolled(layout, body, OverlayLayout.proseKey(paragraphs - 1));
        if (first == null || last == null) {
            return BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), 0);
        }
        return rect(OverlayLayout.proseFrame(first, last.bottom(), widestRight(paragraphs, layout, body),
                body.viewWidth()));
    }

    /**
     * A slot where it is on screen <b>whether or not any of it is</b>: {@code placed} without the cull.
     *
     * <p>For the description's frame, which has to exist at every scroll position. {@code placed} culls a
     * slot that is off the view, which is right for drawing a row that is not there and wrong for the box
     * the open field is placed on: a description taller than the body lost its frame the moment the card
     * scrolled, and while a field is open the card draws no prose -- so the text did not move, it vanished,
     * until the scroll was put back exactly or the field was clicked out of. The box follows the prose now,
     * and the *drawing* is cut by the body's clip instead, so an off-edge field is cut exactly where the
     * text under it is.
     */
    private static Slot scrolled(Layout layout, Viewport body, Object key) {
        Slot slot = layout.slot(key);
        if (slot == null) {
            return null;
        }
        return slot.moved(body.screenX(slot.x()) - slot.x(), body.screenY(slot.y()) - slot.y());
    }

    /**
     * The right edge of the widest paragraph the layout placed, so the frame can contain the prose even
     * when the column it was told about and the width the layout wrapped at disagree. Both are the body's
     * width, and the one drawn around must hold the one drawn inside.
     */
    private int widestRight(int paragraphs, Layout layout, Viewport body) {
        int right = 0;
        for (int i = 0; i < paragraphs; i++) {
            Slot slot = placed(layout, body, OverlayLayout.proseKey(i));
            if (slot != null) {
                right = Math.max(right, slot.right());
            }
        }
        return right;
    }

    private static BookGeometry.Rect rect(Slot slot) {
        return BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height());
    }

    private static int arraySize(JsonObject quest, String member) {
        return quest.has(member) && quest.get(member).isJsonArray()
                ? quest.getAsJsonArray(member).size() : 0;
    }

    private static JsonObject memberEntry(JsonObject quest, String member, int index) {
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    /** A task or reward type's registered icon, or paper for one this build does not know. */
    private static ItemStack typeIcon(String member, String type) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(type);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        dev.ellipog.tasked.quest.ItemRef icon = "rewards".equals(member)
                ? dev.ellipog.tasked.quest.reward.RewardTypes.iconOf(id)
                : dev.ellipog.tasked.quest.task.TaskTypes.iconOf(id);
        return icon.toStack();
    }

    private static ItemStack itemStack(String id) {
        net.minecraft.resources.ResourceLocation location =
                net.minecraft.resources.ResourceLocation.tryParse(id);
        return location == null ? ItemStack.EMPTY : new dev.ellipog.tasked.quest.ItemRef(location, 1).toStack();
    }

    // ------------------------------------------------------------------
    // The editor's presses
    // ------------------------------------------------------------------

    /**
     * What a press on a marked piece does.
     *
     * <p>The pointer comes along because a press that opens an inline editor is also the press that says
     * <i>where</i> in the value it landed; the field places its caret from it. A second click to say where
     * you meant is not the gesture anybody makes.
     */
    private void pressEditTarget(EditTarget target, double mouseX, double mouseY) {
        JsonObject quest = replicaQuest();
        switch (target.action()) {
            case FLAG -> {
                if (quest != null && editTarget() != null) {
                    send(new EditorOp.SetField(editTarget(), target.path(),
                            new JsonPrimitive(!flagOn(quest, target.path()))));
                }
            }
            case FIELD, RAW -> openInlineEditor(target, mouseX, mouseY);
            // An item is picked, not typed: the text field is still there (it is the picker's search
            // box, and a whole id in it commits), but it is no longer the whole of how a field is set.
            case ITEM -> openItemPicker(target);
            case ADD_TASK -> {
                pickingEntryType = "tasks";
                rebuildWidgets();
            }
            case ADD_REWARD -> {
                pickingEntryType = "rewards";
                rebuildWidgets();
            }
            case ADD_DEP -> openInlineEditor(new EditTarget(EditAction.FIELD, InlineEdit.DEPENDENCY_ADD,
                    target.box(), target.textX(), target.textY(), "", null, -1), mouseX, mouseY);
            case PICK_DEP -> armDependencyPick();
            case REMOVE_DEP -> {
                if (quest != null && editTarget() != null) {
                    List<String> remaining = QuestPanelLayout.strings(quest, "dependsOn").stream()
                            .filter(each -> !each.equals(target.path())).toList();
                    send(new EditorOp.SetField(editTarget(), "dependsOn", stringArray(remaining)));
                }
            }
            case COPY_ENTRY -> pressEntry("h:" + target.member() + "." + target.index(), true);
            case REMOVE_ENTRY -> pressEntry("h:" + target.member() + "." + target.index(), false);
            case DRAG_ENTRY -> {
                // The press claims the row; whether it becomes a drag is the threshold's, further on.
                // The press's own coordinates are recorded because the threshold compares against them,
                // and this press never reached the canvas branch that usually sets them.
                dragRowMember = target.member();
                dragRowQuest = editTarget();
                dragRowFrom = target.index();
                dragRowLive = false;
                dragRowPointerY = mouseY;
                pressX = mouseX;
                pressY = mouseY;
            }
        }
    }

    /**
     * Opens the one inline editor: a field for a value, a text area for prose or raw JSON.
     *
     * <p>{@code mouseX}/{@code mouseY} are the press that asked for it, and they are replayed into the
     * widget it just made -- see the end of this method.
     */
    private void openInlineEditor(EditTarget target, double mouseX, double mouseY) {
        closeInlineEditor();
        editingPath = target.path();
        boolean area = "description".equals(target.path()) || target.action() == EditAction.RAW;
        BookGeometry.Rect box = inlineBox(target, area);
        if (area) {
            String initial = target.action() == EditAction.RAW
                    ? rawText(target.path()) : target.value();
            inlineArea = new ArmatureTextArea(box.x(), box.y(), box.width(), box.height(), initial);
            inlineArea.onSubmit(text -> commitInlineText(target.path(), text));
            // No box: the ink and the pitch of the prose this replaces, so clicking in changes nothing
            // but the caret. The pitch is the overlay's own -- the layout below was built with
            // `OverlayLayout.LINE_HEIGHT` a line and `PARAGRAPH_GAP` between paragraphs, and the
            // drawing here has to land on the same lines or the paragraph moves on every click.
            inlineArea.colours(InlineEdit.ink(target.path()), InlineEdit.NO_BOX, InlineEdit.NO_BOX);
            inlineArea.advance(OverlayLayout.LINE_HEIGHT, OverlayLayout.PARAGRAPH_GAP);
            // A widget, not a renderable widget: it is drawn by `drawOpenEditor`, clipped to the body, and
            // drawn *once*. The renderable list is painted before the card and before the blur, clipped to
            // the book band rather than to the card -- so with the field's box following the prose past the
            // body's edge, that copy showed through as a blurred ghost over the world: *"when it flows out or
            // i scroll too far it goes into the background, yes blurred, but still there"*. As a child it
            // still takes the keyboard and the pointer; as a renderable it was a second copy nothing could
            // clip, which is also where the frozen slab two rounds ago came from.
            addWidget(inlineArea);
            setFocused(inlineArea);
        }
        else {
            inlineField = new ArmatureTextField(box.x(), box.y(), box.width(), box.height(),
                    target.value());
            inlineField.onSubmit(text -> commitInlineText(target.path(), text));
            // The same rule as the area, one line high: the reader's ink for this value, and no box.
            inlineField.colours(InlineEdit.ink(target.path()), InlineEdit.NO_BOX, InlineEdit.NO_BOX);
            // A child, not a renderable widget: see the note on the area above.
            addWidget(inlineField);
            setFocused(inlineField);
        }

        // **The click that opened the field is the click that places the caret.** Without this the value
        // was focused with the caret at its end, so the first thing the author saw after clicking a word
        // was the text scrolled to its other end -- and the click they had already made did not count.
        //
        // Replayed through the widget's own click handling rather than a second placement rule here: a
        // click on a character puts the caret at it, a click in the frame's padding goes to the nearest
        // end of the line, and a second click in the same place still marks the word under it -- three
        // behaviours, the widget's, from the press it was always meant to be. The target is inside the
        // widget's box in every case: the description's box *is* its frame, and a row's field is its
        // mark opened out by its padding.
        if (inlineArea != null) {
            inlineArea.mouseClicked(mouseX, mouseY, 0);
        }
        else if (inlineField != null) {
            inlineField.mouseClicked(mouseX, mouseY, 0);
        }
    }

    /**
     * The rectangle the open inline editor's widget occupies.
     *
     * <p><b>The area is its target's box, exactly.</b> The description's frame is built as the text
     * area's own rectangle -- the column opened out by {@link OverlayLayout#PROSE_PAD}, which is the text
     * area's own padding, and never narrower than the widest line the layout placed -- so the widget is
     * put at that rectangle and nothing else. That is what makes it impossible for the frame to be
     * narrower than the text inside it: there is one rectangle, and the border is drawn around the same
     * one the text is drawn in. The padding, the wrap width and the frame all come from that single
     * number ({@link ArmatureTextArea#PAD}); a second reading of any of them is what the two reports
     * behind this were.
     *
     * <p>The one-line widget is placed from the target's <b>text origin</b> instead: its box hugs the
     * whole piece and the value is inset inside it (four pixels into a row's number, past the icon for an
     * item, two for the title), so a field sized to the box drew its value to the left of where the value
     * was and every click nudged the text sideways. The widget draws its text at its own left edge plus
     * {@link ArmatureTextField#PAD}, and centres it in its own height, so the box that puts the text back
     * on the value's own origin is derived from that origin rather than from the box around it.
     *
     * <p>One function for the opening and for every frame after it, because the card scrolls under an
     * open editor and the description's box grows with the text: the widget has to be put back on its
     * target each frame, and a second copy of this arithmetic is how a field ends up pixels off the value
     * it is editing.
     */
    private BookGeometry.Rect inlineBox(EditTarget target, boolean area) {
        if (area) {
            return target.box();
        }
        int height = Math.max(12, target.box().height() + 4);
        return BookGeometry.Rect.at(target.textX() - ArmatureTextField.PAD,
                target.textY() - (height - 8) / 2,
                target.box().width() + ArmatureTextField.PAD * 2,
                height);
    }

    /**
     * Scrolls the card so the open field's caret is in view -- <b>and only when the caret moved since the
     * last check</b>. That gate is the whole mechanism, not a detail: a tick with no caret change (a wheel
     * notch, a redraw, a blink) scrolls nothing, so the wheel keeps whatever position the author put it at,
     * while typing, an arrow, a click and opening a field all move the caret and are followed.
     *
     * <p>It follows the <b>caret</b>, not the field's box. The description's box is as tall as the whole
     * text, so a box-follow has nothing to aim at; the caret's own line is both smaller and what the author
     * is looking at. And a field that has been stood down -- scrolled out of the body -- is not followed at
     * all: {@code repositionInlineEditor} froze its rectangle when it hid it, and chasing that stale
     * rectangle was the other half of the scroll fight.
     */
    private void followCaret() {
        net.minecraft.client.gui.components.AbstractWidget editor =
                inlineArea != null ? inlineArea : inlineField;
        if (editor == null || !editor.visible) {
            followedPath = null;
            return;
        }
        String value = inlineArea != null ? inlineArea.value() : inlineField.value();
        int caret = inlineArea != null ? inlineArea.caret() : inlineField.caret();
        if (editingPath != null && editingPath.equals(followedPath)
                && caret == followedCaret && value.equals(followedValue)) {
            return;
        }
        followedPath = editingPath;
        followedValue = value;
        followedCaret = caret;

        Viewport body = overlayBody();
        int margin = 8;
        int top = editor.getY() + (inlineArea != null ? inlineArea.caretTop() : inlineField.caretTop());
        if (top < body.originY() + margin) {
            overlayView.scrollBy(top - body.originY() - margin);
        }
        else if (top + OverlayLayout.LINE_HEIGHT > body.viewBottom() - margin) {
            overlayView.scrollBy(top + OverlayLayout.LINE_HEIGHT - body.viewBottom() + margin);
        }
    }

    /**
     * The open editor's frame on screen, or null: the target it was opened over, which is the rectangle
     * the field is drawn in and so the rectangle a press has to land in to count as being on the field.
     *
     * <p>The frame is the field's own box for the description, and the value's mark for a one-line field
     * (whose widget is that mark opened out by its padding) -- so a press anywhere the author can see the
     * field is a press on it.
     */
    private BookGeometry.Rect openEditorFrame() {
        if (editingPath == null) {
            return null;
        }
        for (EditTarget target : editTargets) {
            if (editingPath.equals(target.path())) {
                return target.box();
            }
        }
        return null;
    }

    /** Whether a press is on the field that is already open, rather than away from it. */
    private boolean onOpenEditor(double mouseX, double mouseY) {
        BookGeometry.Rect frame = openEditorFrame();
        return frame != null && frame.contains(mouseX, mouseY);
    }

    /**
     * A press on the open field: the caret goes where the press landed, and nothing else happens. The
     * widget owns where that is -- a character boundary, the nearest end of the line, a word on the
     * second press of a double click -- so this hands the press to it rather than placing the caret from
     * a second rule here.
     */
    private boolean moveOpenCaret(double mouseX, double mouseY) {
        if (inlineArea != null) {
            inlineArea.mouseClicked(mouseX, mouseY, 0);
            return true;
        }
        if (inlineField != null) {
            inlineField.mouseClicked(mouseX, mouseY, 0);
            return true;
        }
        return false;
    }

    /**
     * The open field, drawn above the card and cut off at the body's edges.
     *
     * <h2>The two things this does that a bare {@code inlineArea::render} did not</h2>
     *
     * <p><b>It respects being stood down.</b> {@code repositionInlineEditor} hides the field when its
     * target has been scrolled out of the body, and the widget pass honours that -- but this list is drawn
     * by hand, and a direct call to {@code render} ignores {@code visible} entirely. So a field the editor
     * had stood down was still painted, at the last rectangle it was ever given: select some text, scroll,
     * and the prose sat frozen in place while the card moved under it. That was the report (*"the text just
     * stays while the other stuff moves under it"*), and it is a bug that could only appear once a
     * description grew taller than the body -- that is when the target starts being culled mid-scroll.
     *
     * <p><b>It is clipped to the body.</b> The field is drawn outside the content's clip -- it has to be,
     * it is chrome over the card -- so with its box following the prose through a scroll, an unclipped
     * field would draw its text over the header and the footer bar as the prose passes them. Clipping is
     * what lets the box be the prose's real rectangle at every scroll position, which is what keeps the
     * text in the field on the text underneath it: one arithmetic, cut by one edge.
     */
    private void drawOpenEditor(GuiRenderer r) {
        if (editingPath == null) {
            return;
        }
        Viewport body = overlayBody();
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            if (inlineArea != null && inlineArea.visible) {
                inlineArea.render(r);
            }
            if (inlineField != null && inlineField.visible) {
                inlineField.render(r);
            }
        }
    }

    /**
     * Moves the open editor back onto the target it replaces, and stands it down while that target is
     * not on screen at all.
     *
     * <p>The frame's own target list is the one description of where a piece is -- the same list the
     * press is matched against -- so a field that followed anything else would be a field drawn somewhere
     * a click would not land. A target that is not in the list was scrolled out of the body (or its box
     * had no size), and the widget is <b>hidden and deactivated</b> rather than left floating: it is
     * redrawn outside the body's clip, so an unclipped field is text over the header, and a focused field
     * that is not on screen must not be what the next keystroke lands on. It comes back -- value and
     * caret intact -- when its target scrolls into view.
     */
    private void repositionInlineEditor() {
        if (editingPath == null) {
            return;
        }
        for (EditTarget target : editTargets) {
            if (!editingPath.equals(target.path())) {
                continue;
            }
            boolean area = inlineArea != null;
            BookGeometry.Rect box = inlineBox(target, area);
            // On screen is a question about the box and the body, not about the target existing: the box
            // follows the prose through a scroll now, so the only thing that stands the field down is the
            // whole of it being off the view -- where typing would be typing blind.
            Viewport body = overlayBody();
            boolean onScreen = box.bottom() > body.originY() && box.y() < body.viewBottom();
            if (area) {
                inlineArea.setX(box.x());
                inlineArea.setY(box.y());
                inlineArea.setWidth(box.width());
                inlineArea.setHeight(box.height());
                inlineArea.visible = onScreen;
                inlineArea.active = onScreen;
            }
            else {
                inlineField.setX(box.x());
                inlineField.setY(box.y());
                inlineField.setWidth(box.width());
                inlineField.setHeight(box.height());
                inlineField.visible = onScreen;
                inlineField.active = onScreen;
            }
            return;
        }
        if (inlineArea != null) {
            inlineArea.visible = false;
            inlineArea.active = false;
        }
        if (inlineField != null) {
            inlineField.visible = false;
            inlineField.active = false;
        }
    }

    private void closeInlineEditor() {
        // References first: removing a focused widget blurs it, and a blur submits -- the field's own
        // re-entrancy guard catches that, but the state should not outlive the widget for a moment
        // longer than it has to either.
        ArmatureTextField field = inlineField;
        ArmatureTextArea area = inlineArea;
        inlineField = null;
        inlineArea = null;
        editingPath = null;
        if (field != null) {
            removeWidget(field);
        }
        if (area != null) {
            removeWidget(area);
        }
    }

    /** The compact JSON of one entry, for the raw editor. */
    private String rawText(String path) {
        JsonObject quest = replicaQuest();
        JsonElement found = quest == null ? null : QuestPanelLayout.get(quest, path);
        return found == null ? "{}" : found.toString();
    }

    /**
     * Commits one inline edit, by the path the piece is about.
     *
     * <p>Three shapes share the door, because they share the rule that a field commits as a whole:
     * prose is split back into the line array the format stores, a whole entry's raw text is parsed as
     * JSON (and refused out loud when it is not), and everything else goes through the typed field the
     * tree declares. An empty value removes the field, as everywhere else.
     */
    private void commitInlineText(String path, String text) {
        String target = editTarget();
        closeInlineEditor();
        if (!mayEditNow() || target == null) {
            return;
        }
        if (InlineEdit.DEPENDENCY_ADD.equals(path)) {
            addDependency(text == null ? "" : text.trim());
            return;
        }
        if ("description".equals(path)) {
            if (text == null || text.trim().isEmpty()) {
                send(new EditorOp.SetField(target, "description", null));
                return;
            }
            // Split back into paragraphs and drop the blank ones at the ends: a blank line at the end of
            // the prose is where the caret was, not content. A blank line *between* two paragraphs is a
            // paragraph break and is written as one. See `Prose`.
            List<String> paragraphs = Prose.trimmed(List.of(text.split("\n", -1)));
            send(new EditorOp.SetField(target, "description", stringArray(paragraphs)));
            return;
        }
        if (path.matches("tasks\\.\\d+|rewards\\.\\d+")) {
            if (text == null || text.trim().isEmpty()) {
                status("An entry cannot be empty", true);
                return;
            }
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                send(new EditorOp.SetField(target, path, parsed));
            }
            catch (RuntimeException malformed) {
                status("That is not JSON \u2014 " + malformed.getMessage(), true);
            }
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            send(new EditorOp.SetField(target, path, null));
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(replicaQuest(), path);
        InspectField.Result<?> result = field.parse(text.trim());
        if (!result.ok()) {
            status(result.error(), true);
            rebuildWidgets();
            return;
        }
        send(new EditorOp.SetField(target, path, jsonOf(result.value())));
    }

    // ------------------------------------------------------------------
    // The item picker
    // ------------------------------------------------------------------

    /**
     * Opens the picker on one field: the registry's items and what the player carries, under a search
     * box.
     *
     * <p>The current value is read from the replica rather than from the press's {@code EditTarget},
     * because the header icon's target carries no value (it opens from a mark with nothing under it) --
     * and the clear row's whole job is to say what there is to clear.
     */
    private void openItemPicker(EditTarget target) {
        closeInlineEditor();
        pickingEntryType = null;
        settingsOpen = false;
        pickingItemPath = target.path();
        JsonObject quest = replicaQuest();
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, target.path());
        pickingItemCurrent = current != null && current.isJsonPrimitive() ? current.getAsString() : "";
        pickingItemClearPath = ItemPicker.clearPath(target.path());
        pickerEntries = catalogue();
        pickerInventory = carried();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        pickerSelected = -1;
        pickerScroll = 0;
        rebuildWidgets();
    }

    /** Closes it without committing, and forgets its list: the next open gathers a fresh one. */
    private void closeItemPicker() {
        pickingItemPath = null;
        pickingItemCurrent = "";
        pickingItemClearPath = null;
        pickerEntries = List.of();
        pickerInventory = List.of();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        pickerSelected = -1;
        pickerScroll = 0;
        itemSearch = null;
    }

    /** A press on a picker row: an item sets the field, the clear row removes it, a heading nothing. */
    private void pressPickerRow(int index) {
        if (index < 0 || index >= pickerRows.size()) {
            return;
        }
        ItemPickerLayout.Row row = pickerRows.get(index);
        if (ItemPickerLayout.pickable(row)) {
            commitPicker(row.kind() == ItemPickerLayout.Kind.CLEAR ? null : row.id());
        }
    }

    /**
     * Enter, which is two rules in one key.
     *
     * <p>A whole id typed into the box is the answer outright -- that is what makes the box a field and
     * not only a filter. Otherwise the selected row commits, and when there is no row to commit the
     * screen says so rather than setting the field to whatever happened to sort first.
     */
    private void commitFromPicker() {
        String query = itemSearch == null ? "" : itemSearch.value();
        String exact = ItemPicker.exactId(query, pickerMatches);
        if (exact != null) {
            commitPicker(exact);
            return;
        }
        ItemPickerLayout.Row row = pickerSelected >= 0 && pickerSelected < pickerRows.size()
                ? pickerRows.get(pickerSelected) : null;
        if (row != null && row.kind() == ItemPickerLayout.Kind.ITEM) {
            commitPicker(row.id());
            return;
        }
        if (row != null && row.kind() == ItemPickerLayout.Kind.CLEAR) {
            commitPicker(null);
            return;
        }
        status(query.isBlank() ? "Type to search, or pick something you carry"
                : "No item matches \"" + query + "\"", true);
    }

    /**
     * One edit: the field becomes this id, or the clear path is removed when there is one.
     *
     * <p>A clear with nothing legal to clear is refused before it reaches the wire -- the row is not
     * drawn for such a field, and this is the second gate in case anything ever draws one anyway.
     */
    private void commitPicker(String id) {
        String quest = editTarget();
        String path = pickingItemPath;
        String clearPath = pickingItemClearPath;
        closeItemPicker();
        if (!mayEditNow() || quest == null || path == null) {
            rebuildWidgets();
            return;
        }
        if (id == null) {
            if (clearPath == null) {
                status("That field cannot be cleared", true);
                rebuildWidgets();
                return;
            }
            send(new EditorOp.SetField(quest, clearPath, null));
            status("Cleared", false);
            rebuildWidgets();
            return;
        }
        send(new EditorOp.SetField(quest, path, new JsonPrimitive(id)));
        status("Set to " + id, false);
        rebuildWidgets();
    }

    /**
     * The picker's body: the search box above (a widget, placed by {@code buildQuestEditorWidgets}),
     * then the rows.
     *
     * <p>Every row is drawn and hit-tested from {@link ItemPickerLayout}'s own rectangles, and the
     * list the press walks is the list this drew -- stored, not recomputed, so a press cannot land on
     * a row one revision away from the one under the pointer.
     */
    private void drawItemPicker(GuiRenderer r, Viewport body, int mouseX, int mouseY) {
        BookGeometry.Rect bodyRect = BookGeometry.Rect.at(body.originX(), body.originY(),
                body.viewWidth(), body.viewHeight());
        pickerFrame = ItemPickerLayout.Frame.of(bodyRect);
        String query = itemSearch == null ? "" : itemSearch.value();
        if (!query.equals(pickerQuery)) {
            // A keystroke is a new list: the selection goes back to its first row and the scroll to the
            // top, because an index into the old list does not name anything in this one.
            pickerQuery = query;
            pickerSelected = -1;
            pickerScroll = 0;
        }
        pickerMatches = ItemPicker.rank(pickerEntries, query, ItemPicker.LIMIT);
        pickerRows = ItemPickerLayout.compose(pickerInventory, pickerMatches,
                pickingItemClearPath != null && !pickingItemCurrent.isEmpty(), query);
        pickerScroll = Math.max(0,
                Math.min(pickerScroll, ItemPickerLayout.maxScroll(pickerRows, pickerFrame)));
        int selected = pickerSelected < 0
                ? ItemPickerLayout.firstPickable(pickerRows)
                : ItemPickerLayout.clamp(pickerRows, pickerSelected);
        pickerSelected = selected;

        try (GuiRenderer.Scoped clip = r.clip(body)) {
            if (pickerRows.isEmpty()) {
                r.text("Type to search every item, or pick something you carry.",
                        pickerFrame.list().x() + 4, pickerFrame.list().y() + 4, ArmatureTheme.faint());
                return;
            }
            Measure measure = textMeasure(r);
            for (int i = 0; i < pickerRows.size(); i++) {
                BookGeometry.Rect rect =
                        ItemPickerLayout.rowRect(pickerRows, pickerFrame, pickerScroll, i);
                // Off the list is not drawn -- the clip would cut a row the keyboard can still land on
                // and the wheel can bring back, and a half row at the edge is exactly what the clip is
                // for, so only the wholly-out ones are skipped.
                if (rect.bottom() <= pickerFrame.list().y()
                        || rect.y() >= pickerFrame.list().bottom()) {
                    continue;
                }
                ItemPickerLayout.Row row = pickerRows.get(i);
                if (row.kind() == ItemPickerLayout.Kind.HEADING) {
                    r.text(row.label(), rect.x() + 2, rect.y() + (rect.height() - 8) / 2,
                            ArmatureTheme.faint());
                    continue;
                }
                if (i == pickerSelected) {
                    r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.recessed());
                }
                else if (rect.contains(mouseX, mouseY)) {
                    drawEditAffordance(r, rect, true);
                }
                int textX = rect.x() + 4;
                if (row.kind() == ItemPickerLayout.Kind.ITEM) {
                    r.icon(itemStack(row.id()), rect.x() + 1, rect.y() + 1,
                            Math.max(8, rect.height() - 2));
                    textX = rect.x() + 20;
                }
                boolean clear = row.kind() == ItemPickerLayout.Kind.CLEAR;
                r.text(Measure.truncate(row.label(), rect.width() - (textX - rect.x()) - 30, measure),
                        textX, rect.y() + (rect.height() - 8) / 2,
                        clear ? ArmatureTheme.blocked() : ArmatureTheme.body());
                if (!row.secondary().isEmpty()) {
                    r.text(row.secondary(), rect.right() - 4 - r.textWidth(row.secondary()),
                            rect.y() + (rect.height() - 8) / 2, ArmatureTheme.faint());
                }
            }
        }
    }

    /** Every item the registry holds, in registry order -- the ranking decides what a query shows. */
    private static List<ItemPicker.Entry> catalogue() {
        List<ItemPicker.Entry> entries = new ArrayList<>();
        var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        for (net.minecraft.resources.ResourceLocation id : registry.keySet()) {
            net.minecraft.world.item.Item item = registry.get(id);
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            entries.add(new ItemPicker.Entry(id.toString(), new ItemStack(item).getHoverName().getString(),
                    0));
        }
        return List.copyOf(entries);
    }

    /**
     * What the player carries, one entry per item with the counts added up.
     *
     * <p>Summed rather than first-stack-wins: two stacks of sixteen is thirty-two carried, and a picker
     * that said sixteen would be answering a question about a slot. Armour and the offhand are included
     * -- they are carried, and an author picking "what am I wearing" is a real thing to do.
     */
    private static List<ItemPicker.Entry> carried() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return List.of();
        }
        var inventory = player.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .toString();
            counts.merge(id, stack.getCount(), Integer::sum);
            labels.putIfAbsent(id, stack.getHoverName().getString());
        }
        List<ItemPicker.Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> each : counts.entrySet()) {
            out.add(new ItemPicker.Entry(each.getKey(), labels.get(each.getKey()), each.getValue()));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // The row drag
    // ------------------------------------------------------------------

    /**
     * Lets a dragged row go: one op, aimed at the gap the line was last drawn on.
     *
     * <p>Three lists, two ops. A task or a reward moves by {@link EditorOp.MoveEntry}, which the model
     * has carried and refused-bounds since before any of this was draggable. The chapter's quest list
     * is not any quest's file -- it is the chapter manifest's own {@code "quests"} array, which only
     * {@link EditorOp.SetChapter} reaches -- so the drop says the whole reordered list rather than an
     * index move, which is a bigger message and the same one edit.
     *
     * <p>A drop onto the row's own place sends nothing: "that edit would change nothing" as a refusal
     * is the server's way of saying it, and there is no reason to spend a round trip on it.
     */
    private void commitRowDrop(String member, String quest, int from, double pointerY) {
        if (!mayEditNow() || member == null || from < 0) {
            return;
        }
        List<BookGeometry.Rect> rows = dragRowSlots.get(member);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int gap = RowDrag.gapAt(rows, pointerY);
        if (gap < 0) {
            return;
        }
        int to = RowDrag.finalIndex(from, gap);
        if (to == from) {
            return;
        }
        if ("chapter".equals(member)) {
            JsonObject chapter = ClientChapterReplica.chapterTree(effectiveChapter());
            List<String> names = new ArrayList<>(QuestPanelLayout.strings(chapter, "quests"));
            if (from >= names.size() || to >= names.size() || to < 0) {
                return;
            }
            names.add(to, names.remove(from));
            send(new EditorOp.SetChapter("quests", stringArray(names)));
        }
        else {
            if (quest == null) {
                return;
            }
            send(new EditorOp.MoveEntry(quest, member, from, to));
        }
        status("Moved to position " + (to + 1), false);
    }

    /**
     * The chapter-tab row a pointer is over, as an index into the manifest's quest order, or -1.
     *
     * <p>The rows are drawn by {@code QuestPanel.drawRows} from {@code chapterLayout}; this is the same
     * mapping the drawing uses, so what is pressed is what is seen, scrolled or not.
     */
    private int chapterQuestRowAt(double mouseX, double mouseY) {
        if (chapterLayout == null) {
            return -1;
        }
        Viewport view = toolsView.viewport();
        int index = 0;
        for (InspectRow row : chapterRows) {
            if (!row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:")) {
                continue;
            }
            Slot slot = chapterLayout.slot(row.key());
            if (slot != null && InspectLayout.onScreen(view, slot).contains(mouseX, mouseY)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** The same rows as rectangles, in order -- the seams the drag's gap arithmetic counts. */
    private List<BookGeometry.Rect> chapterQuestRowRects() {
        List<BookGeometry.Rect> out = new ArrayList<>();
        if (chapterLayout == null) {
            return List.copyOf(out);
        }
        Viewport view = toolsView.viewport();
        for (InspectRow row : chapterRows) {
            if (!row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:")) {
                continue;
            }
            Slot slot = chapterLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = InspectLayout.onScreen(view, slot);
            out.add(BookGeometry.Rect.at(onScreen.x(), onScreen.y(), onScreen.width(),
                    onScreen.height()));
        }
        return List.copyOf(out);
    }

    /**
     * The insertion line: two pixels at the gap the pointer names, spanning the rows' own width.
     *
     * <p>Drawn inside the caller's clip and skipped when the gap is off it, so a drag past the list's
     * edge does not paint a line over a header or into the canvas.
     */
    private void drawRowDragIndicator(GuiRenderer r, List<BookGeometry.Rect> rows, double pointerY,
                                      BookGeometry.Rect clip) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int gap = RowDrag.gapAt(rows, pointerY);
        if (gap < 0) {
            return;
        }
        int y = RowDrag.indicatorY(rows, gap);
        if (y < clip.y() - 2 || y > clip.bottom() + 2) {
            return;
        }
        BookGeometry.Rect first = rows.get(0);
        r.fill(first.x() - 2, y - 1, first.right() + 2, y + 2, ArmatureTheme.hoverRing());
    }

    /**
     * A press on a settings stepper: the arrows are drawn, so the screen is what reads them.
     *
     * <p>The step is the field's own: a coordinate moves by the grid (8), everything else by one, and
     * the shape cycles through {@link dev.ellipog.tasked.quest.QuestShape} -- because a shape is a
     * choice, and a text box for a choice is a form.
     */
    private void pressSettingsStepper(double mouseX, double mouseY) {
        if (settingsLayout == null || replicaQuest() == null || editTarget() == null) {
            return;
        }
        for (InspectRow row : settingsRows) {
            if (row.kind() != InspectRow.Kind.STEPPER) {
                continue;
            }
            Slot slot = settingsLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Integer step = dev.ellipog.tasked.client.dev.QuestPanel.stepperStepAt(
                    settingsView.viewport(), slot, mouseX, mouseY);
            if (step == null) {
                continue;
            }
            if (row.key().equals("shape")) {
                dev.ellipog.tasked.quest.QuestShape[] shapes =
                        dev.ellipog.tasked.quest.QuestShape.values();
                dev.ellipog.tasked.quest.QuestShape current =
                        dev.ellipog.tasked.quest.QuestShape.byName(row.value(),
                                dev.ellipog.tasked.quest.QuestShape.ROUNDED);
                int next = Math.floorMod(current.ordinal() + step, shapes.length);
                send(new EditorOp.SetField(editTarget(), "shape",
                        new JsonPrimitive(shapes[next].name().toLowerCase(java.util.Locale.ROOT))));
                return;
            }
            double current = QuestPanelLayout.get(replicaQuest(), row.key()) instanceof JsonElement value
                    && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsDouble() : 0;
            double delta = row.key().equals("x") || row.key().equals("y") ? 8 : 1;
            send(new EditorOp.SetField(editTarget(), row.key(),
                    new JsonPrimitive((long) (current + step * delta))));
            return;
        }
    }

    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        rebuildWidgets();
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
        // The editor's transient state goes with the card: a picker left armed would greet the next
        // quest with a list of types, and a Delete left confirmed would delete on one press.
        pickingEntryType = null;
        // And the item picker's, for the same reason: its path names a field of the quest being closed.
        closeItemPicker();
        confirmingDelete = false;
        settingsOpen = false;
        // The widgets go with the clear; the references and the path must not outlive them.
        inlineField = null;
        inlineArea = null;
        editingPath = null;
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
                for (java.util.function.Consumer<GuiRenderer> redraw : modalRedraws) {
                    redraw.accept(renderer);
                }
                drawOpenEditor(renderer);
            }

            // Inside the raised Z as well, and one level above *it* -- not tidiness, and it took a report
            // to learn the size of the step. A tooltip is a panel and some text; an item's own render
            // writes depth, so a box at the same Z as the card's contents can lose to the icon it is
            // covering, however much later it is drawn. That is "items render over tooltips", and the same
            // is true of the type label beside an icon. Both are drawn here, at TOOLTIP_Z.
            //
            // Outside the clip, deliberately: a tooltip belongs over everything, including the edge it
            // happens to reach past.
            pose.pushPose();
            pose.translate(0F, 0F, TOOLTIP_Z - CHROME_Z);
            drawPendingLabels(renderer);
            drawLinkTarget(renderer, mouseX, mouseY);
            drawTooltips(renderer, mouseX, mouseY);
            pose.popPose();

            // Consumed, because they are drawn here rather than where they are collected: the card fills
            // the list while it draws, and a frame that draws no card (the overlay closed, a chapter
            // switch) must not leave last frame's label standing over the canvas.
            pendingLabels.clear();
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

    /**
     * How far above the chrome the two things that belong over <b>everything</b> are drawn: a control's
     * tooltip, and the type label beside a hovered icon.
     *
     * <p>A step above {@code CHROME_Z} rather than the same Z, because the card holds item icons: item
     * rendering writes depth, so "drawn later" is not "on top" at equal Z. A hundred is arbitrary and
     * large on purpose -- the value only has to be more than the content's, and every consumer of these
     * two is at the same level.
     */
    private static final float TOOLTIP_Z = CHROME_Z + 100F;

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
        // **Tasked's look, scoped to Tasked's drawing.** The theme is an instance this mod owns now, and
        // this is where it reaches the toolkit: a scope opened around the whole of the book's own drawing,
        // closed however this returns. Another mod's screens open their own and see their own -- which is
        // the property the library had made impossible by holding one global theme for everybody.
        try (ArmatureTheme.Scope look = ArmatureTheme.scope(ClientAppearance.LOOK.main())) {
            renderBook(renderer, mouseX, mouseY, partialTick);
        }
    }

    /** The book's own drawing, inside the look's scope. See {@link #renderWith}. */
    private void renderBook(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
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
        if (!toolsOpen || overlay != Overlay.NONE || toolsFrame == null) {
            return;
        }
        if (toolsTab == ToolsLayout.Tab.CHAPTER) {
            if (chapterLayout == null) {
                return;
            }
            ArmatureTheme.panel(r, toolsFrame.panel().x(), toolsFrame.panel().y(),
                    toolsFrame.panel().width(), toolsFrame.panel().height(), ArmatureTheme.panel(),
                    ArmatureTheme.panelEdge());
            QuestPanel.drawRows(r, toolsFrame.list(), toolsView.viewport(), chapterLayout, chapterRows,
                    mouseX, mouseY);
            // The rows the drag reorders, in the order they are drawn, and the line over them. Only the
            // quest rows take part -- the identity fields above them are not a list.
            dragRowSlots.put("chapter", chapterQuestRowRects());
            if (dragRowLive && "chapter".equals(dragRowMember)) {
                drawRowDragIndicator(r, dragRowSlots.get("chapter"), dragRowPointerY, toolsFrame.list());
            }
        }
        else {
            if (toolsLayout == null) {
                return;
            }
            ToolsPanel.draw(r, toolsFrame, toolsView.viewport(), toolsLayout, toolsRows,
                    new ToolsPanel.State(toolsSelected, toolsFeedback, toolsFeedbackIsError,
                            toolsSelected != null),
                    mouseX, mouseY);
        }
        // The bar, from the kit's own rectangles and drawn only when there is more than fits -- the same
        // call the sidebar and the party panel make. It was missing entirely, which left a list that
        // scrolled with nothing on screen saying so.
        toolsView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    private void drawBook(GuiRenderer r, int mouseX, int mouseY, long now) {
        // The server's own answer for a moved node arrives with a tree, and that is the moment the editor's
        // remembered position is no longer needed. Noticed here rather than in a handler because this is a
        // comparison of two numbers on the frame path that already reads them.
        long revision = ClientQuestCache.treeRevision();
        editors.onRevision(revision);
        // And the same revision decides whether a panel's copy of the chapter is current. `claim` is what
        // keeps this from being one request per frame: it says yes once per revision and remembers asking.
        if (mayEditNow() && ClientChapterReplica.claim(effectiveChapter(), revision)) {
            TaskedNetworking.requestReplica(effectiveChapter());
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
    /**
     * A hovered link's target, in the same box a control's tooltip uses.
     *
     * <p>Because a link's whole affordance is an underline and a change of ink: without the address, the
     * reader knows something is clickable and not where it goes, and the address is the one thing a reader
     * of a link wants before pressing it.
     */
    private void drawLinkTarget(GuiRenderer r, int mouseX, int mouseY) {
        for (LinkRect link : linkRects) {
            if (link.box().contains(mouseX, mouseY)) {
                drawTooltip(r, List.of(link.url()), mouseX, mouseY);
                return;
            }
        }
    }

    /**
     * The type name beside a hovered icon, drawn with the tooltips rather than with the card.
     *
     * <h2>Why "last in the card" was not last enough</h2>
     *
     * <p>These were the last thing the card drew, and that was not enough: an <b>item's</b> own render
     * writes depth, so a box drawn *after* an icon at the *same* Z can still lose to it -- and the report
     * was exactly that, *"items render over tooltips"*. The card's contents are all at {@code CHROME_Z};
     * what belongs over everything is drawn one level above it, which is where the tooltips already were
     * and where this now goes (see the call site in {@code render}).
     *
     * <p>The boxes are in screen coordinates and are worked out while the card is drawn, so drawing them
     * here is a translation in Z only: the label is where the icon it names is, whenever the frame gets
     * around to painting it.
     */
    private void drawPendingLabels(GuiRenderer r) {
        for (PendingLabel label : pendingLabels) {
            BookGeometry.Rect box = label.box();
            r.fill(box.x() - 1, box.y() - 1, box.right() + 1, box.bottom() + 1,
                    ArmatureTheme.panelEdge());
            r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.tooltipFill());
            r.text(label.text(), box.x() + 4, box.y() + (box.height() - 8) / 2, ArmatureTheme.title());
        }
    }

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

        // The editor's in-progress gestures, drawn over everything they touch: the marquee is what the
        // author is reaching for, and the rubber line is the edge they are about to make.
        if (marqueeActive) {
            drawMarquee(r);
        }
        if (edgeDragLive && edgeDragFrom != null) {
            ClientQuestCache.Entry from = entryFor(edgeDragFrom);
            if (from != null) {
                drawEdge(r, nodeScreenX(from) + nodeSize(from) / 2,
                        nodeScreenY(from) + nodeSize(from) / 2, (int) edgeDragX, (int) edgeDragY,
                        ArmatureTheme.hoverRing());
            }
        }
        if (pickingDependency) {
            // Named, because the pick survives a chapter switch: the author may be looking at another
            // chapter's nodes and needs to know which quest is gaining the prerequisite.
            String hint = pendingPick == null
                    ? "click the quest to depend on \u00b7 escape cancels"
                    : "click the quest to depend on \u00b7 for " + pendingPick.quest()
                            + " \u00b7 escape cancels";
            int hintX = canvasLeft() + 10;
            int hintY = canvasTop() + 10;
            r.fill(hintX - 3, hintY - 2, hintX + r.textWidth(hint) + 3, hintY + 10,
                    ArmatureTheme.labelBackdrop());
            r.text(hint, hintX, hintY, ArmatureTheme.body());
        }

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

    /**
     * The marquee, as a one-pixel ring in the selection's own colour: the rectangle the release will
     * select is the rectangle being drawn, which is the whole honesty a rubber band has.
     */
    private void drawMarquee(GuiRenderer r) {
        int left = Math.min(marqueeX0, marqueeX1);
        int right = Math.max(marqueeX0, marqueeX1);
        int top = Math.min(marqueeY0, marqueeY1);
        int bottom = Math.max(marqueeY0, marqueeY1);
        r.fill(left, top, right + 1, top + 1, ArmatureTheme.selectedRing());
        r.fill(left, bottom, right + 1, bottom + 1, ArmatureTheme.selectedRing());
        r.fill(left, top, left + 1, bottom + 1, ArmatureTheme.selectedRing());
        r.fill(right, top, right + 1, bottom + 1, ArmatureTheme.selectedRing());
    }

    /** The hovered node's title, under the pointer. */
    private void drawNodeCaption(GuiRenderer r, ClientQuestCache.Entry entry) {        int size = nodeSize(entry);
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

        boolean isSelected = isSelected(entry.id());
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
                default -> isSelected(entry.id()) ? ArmatureTheme.title() : ArmatureTheme.body();
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
        drawEdge(r, nodeScreenX(from) + nodeSize(from) / 2, nodeScreenY(from) + nodeSize(from) / 2,
                nodeScreenX(to) + nodeSize(to) / 2, nodeScreenY(to) + nodeSize(to) / 2, colour);
    }

    /**
     * One connector, between two screen points -- the shapes the dependency lines and the edge gesture's
     * rubber line both draw, because a rubber line that bent by different rules than the real ones would
     * promise a route the real line would not take.
     */
    private static void drawEdge(GuiRenderer r, int ax, int ay, int bx, int by, int colour) {
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
        // The title and the subtitle are drawn unless their own field is open and drawing them -- see
        // `InlineEdit.replaces`. The state tag stays either way: it is not part of the title, and its x
        // is derived from the title's width, so it does not move when the title's drawing changes hands.
        if (!InlineEdit.replaces("title", editingPath)) {
            r.text(entry.title(), textX, top + 12, InlineEdit.ink("title"));
        }
        r.text(stateLabel(state), textX + r.textWidth(entry.title()) + 10, top + 12,
                stateColour(state));

        String where = entry.chapterTitle() + (entry.subtitle().isEmpty() ? "" : "  \u00b7  " + entry.subtitle());
        // The read line carries the chapter name as well; the field edits the subtitle alone, so
        // drawing both would print the chapter title through the field's text.
        if (!InlineEdit.replaces("subtitle", editingPath)) {
            r.text(where, textX, top + 26, InlineEdit.ink("subtitle"));
        }

        // In edit mode the body is the editor: the same card, the same header, and the fields below
        // instead of the prose. The reader's body is skipped whole -- drawing both would draw the
        // description through the fields.
        if (mayEditNow()) {
            drawQuestEditor(r, entry, mouseX, mouseY, now);
            return;
        }

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
            drawProse(r, layout, body, mouseX, mouseY);
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
        return OverlayLayout.stack(readerProse(r, entry.description(), body.viewWidth()),
                entry.tasks().size(), entry.rewards().size(), entry.dependencies().size())
                .build(body.viewWidth(), textMeasure(r));
    }

    /**
     * The reader's description as markdown: every element parsed into blocks, every block wrapped through
     * the styles, and both kept for the frame -- the layout gets the lines and widths, the drawing gets the
     * runs, and {@code readerProse} carries what the second needs.
     *
     * <p>Wrapped here rather than by the layout because a bold word is wider than the same word plain: only
     * a caller holding a font can say where a marked-up line breaks. The editor's own path wraps the raw
     * text with the plain measure instead -- same shape, other producer, and the difference between them is
     * the one the author sees when a marked-up description re-flows as they click out of the field.
     */
    private List<OverlayLayout.Prose> readerProse(GuiRenderer r, List<String> description, int column) {
        readerProse.clear();
        linkRects.clear();
        // Measured at the font's own size: a block's scale is the kit's to apply (RichText.scale), so the
        // wrapped widths and the drawn runs come from one rule rather than two.
        RichText.StyledWidth styled = (text, bold, italic) -> r.styledWidth(text, bold, italic, 1F);
        List<OverlayLayout.Prose> prose = new ArrayList<>();
        for (String element : description) {
            for (RichText.Paragraph block : RichText.parse(element)) {
                List<RichText.Line> lines = RichText.wrap(block, column, styled);
                List<String> texts = new ArrayList<>(lines.size());
                int widest = 0;
                for (RichText.Line line : lines) {
                    texts.add(block.text().substring(line.start(), line.end()));
                    widest = Math.max(widest, RichText.styledWidth(block, line.start(), line.end(), styled));
                }
                readerProse.add(new ProseBlock(block, lines));
                // The block's own pitch, from the same rule the drawing advances by: a heading's lines are
                // taller, and the space reserved for them has to be the space they take.
                prose.add(new OverlayLayout.Prose(texts, widest,
                        Math.round(OverlayLayout.LINE_HEIGHT * RichText.scale(block))));
            }
        }
        return List.copyOf(prose);
    }

    /**
     * The editor's prose: the raw paragraphs, wrapped by the plain measure, markdown markers and all.
     *
     * <p>Because the author is editing the markdown *itself*: a field that hid the markers would have to
     * map every caret position through characters it does not draw, and this is the trade that was chosen
     * deliberately. It is also why a marked-up description can wrap at different words here and after the
     * edit is committed.
     */
    private List<OverlayLayout.Prose> editorProse(GuiRenderer r, List<String> description, int column) {
        Measure measure = textMeasure(r);
        List<OverlayLayout.Prose> prose = new ArrayList<>(description.size());
        for (String element : description) {
            prose.add(OverlayLayout.Prose.of(element, column, measure));
        }
        return prose;
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
    private void drawProse(GuiRenderer r, Layout layout, Viewport body, int mouseX, int mouseY) {
        if (readerProse.isEmpty()) {
            Slot slot = placed(layout, body, OverlayLayout.NO_DESCRIPTION);
            if (slot != null) {
                r.text("No description.", slot.x(), slot.y(), ArmatureTheme.faint());
            }
            return;
        }
        linkRects.clear();
        for (int i = 0; i < readerProse.size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.proseKey(i));
            if (slot == null) {
                continue;
            }
            ProseBlock block = readerProse.get(i);
            // A heading takes the ink the card's own section headings take, so a markdown heading reads as
            // a heading rather than as louder prose.
            int ink = block.paragraph().kind() == RichText.Kind.HEADING
                    ? ArmatureTheme.heading() : ArmatureTheme.body();
            int advance = Math.round(OverlayLayout.LINE_HEIGHT * RichText.scale(block.paragraph()));
            int lineY = slot.y();
            for (RichText.Line line : block.lines()) {
                int x = slot.x();
                for (RichText.Piece piece : RichText.pieces(block.paragraph(), line)) {
                    int width = r.styledWidth(piece.text(), piece.bold(), piece.italic(), piece.scale());
                    BookGeometry.Rect box = piece.link() == null ? null
                            : BookGeometry.Rect.at(x, lineY, width, advance);
                    boolean hot = box != null && box.contains(mouseX, mouseY);
                    int colour = hot ? ArmatureTheme.title()
                            : piece.code() ? ArmatureTheme.faint() : ink;
                    // One call per piece: a styled run carries no colour, so a line's colours are the
                    // caller's to move between -- and the same loop that draws a link is the one that
                    // records where it was drawn, so a click can never land on a rectangle that is not what
                    // the reader saw.
                    r.styledText(List.of(new GuiRenderer.StyledRun(piece.text(), piece.bold(), piece.italic(),
                            box != null, piece.scale())), x, lineY, colour);
                    if (box != null) {
                        linkRects.add(new LinkRect(box, piece.link()));
                    }
                    x += width;
                }
                lineY += advance;
            }
        }
    }

    /**
     * The prose, at the slots the layout placed it in, for a caller that has the lines already.
     *
     * <p>The editor draws the description it is <i>editing</i> rather than the one on file, because the
     * layout was built from those lines -- a paragraph drawn from the file under a layout built from
     * the editor's text is two descriptions of one block, which is the fault this file keeps naming.
     */
    private void drawDescription(GuiRenderer r, List<String> description, Layout layout,
                                 Viewport body, Measure measure) {
        if (description.isEmpty()) {
            Slot slot = placed(layout, body, OverlayLayout.NO_DESCRIPTION);
            if (slot != null) {
                r.text("No description.", slot.x(), slot.y(), ArmatureTheme.faint());
            }
            return;
        }

        for (int i = 0; i < description.size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.proseKey(i));
            if (slot == null) {
                continue;
            }
            int lineY = slot.y();
            for (String line : TextWrap.wrap(description.get(i), body.viewWidth(), measure)) {
                r.text(line, slot.x(), lineY, ArmatureTheme.body());
                lineY += measure.lineHeight();
            }
        }
    }

    private void drawTasks(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout, Viewport body,
                           long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.TASKS_HEADING), "TASKS");

        if (entry.tasks().isEmpty()) {
            drawEmptyState(r, placed(layout, body, OverlayLayout.NO_TASKS), "Nothing required");
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
            drawEmptyState(r, placed(layout, body, OverlayLayout.NO_REWARDS), "Nothing");
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
            // A tick and a cross. The cross is U+00D7 rather than the heavier U+2716, which is one of
            // the few symbols this font does not carry -- see `BookGeometry.TOOLS_BUTTON_WIDTH`.
            r.text((met ? "\u2714" : "\u00d7") + "  " + label, slot.x(), slot.y(),
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
        fieldDrag = false;
        pressedLink = null;
        // A new press ends a row drag that never got its release -- one gesture at a time, and the row
        // rectangles a stale drag would aim at belong to the frame it started in.
        dragRowFrom = -1;
        dragRowMember = null;
        dragRowQuest = null;
        dragRowLive = false;

        // Clicking off a text field finishes with it: the blur is the commit, and a field that keeps
        // the keyboard after the pointer has moved on is a field that eats the next key typed. Before
        // anything else, so the press that closes one editor can also open the next.
        //
        // "Off it" is asked of the field's **frame** as well as of the widget's own box: they are the
        // same rectangle for the description (its frame *is* its box), and asking the target is what
        // keeps a press on the border or a pixel out of the widget's own hit test from being read as a
        // press away from the field.
        if (button == 0 && getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isMouseOver(mouseX, mouseY) && !onOpenEditor(mouseX, mouseY)) {
            setFocused(null);
        }
        // And a press **on** the field that is already open moves the caret into it -- one gesture, one
        // path, and nothing else. Not a press "away" (the check above), and not a press that opens the
        // field again (the marked pieces further down, which would close it and make a new one).
        //
        // The report this is the answer to: *"when i click on the description box then click on it again
        // it unselects instead of just instantly moving my cursor"* -- the second click was being read as
        // a click off the field, so it committed and closed instead of putting the cursor in the word
        // that was clicked.
        if (button == 0 && onOpenEditor(mouseX, mouseY) && moveOpenCaret(mouseX, mouseY)) {
            // And the drag that may follow this press belongs to the field: the same press, continued.
            fieldDrag = true;
            return true;
        }
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
            // A description's link, before anything else the card does with a press: a link inside a card
            // must not be read as a press on the card. Opened on release, so a press that turns into a drag
            // is not a click.
            if (button == 0 && overlay == Overlay.QUEST && !mayEditNow()) {
                for (LinkRect link : linkRects) {
                    if (link.box().contains(mouseX, mouseY)) {
                        pressedLink = link;
                        return true;
                    }
                }
            }
            // Whether this press is the armed Disband's, asked before the press is handled: a press on
            // anything else is a change of mind, and the label a player is looking at has to match what
            // the next press on Disband will do.
            boolean onDisband = disbandButton != null && disbandButton.isMouseOver(mouseX, mouseY);
            boolean onQuestDelete = questDeleteButton != null
                    && questDeleteButton.isMouseOver(mouseX, mouseY);
            if (super.mouseClicked(mouseX, mouseY, button)) {
                // One of the modal's own controls took it. Everything behind stays untouched.
                if (!onDisband) {
                    disarmDisband();
                }
                // The armed Delete works like the armed Disband: any other press is a change of mind,
                // and the label on screen has to match what the next press on Delete will do.
                if (!onQuestDelete && confirmingDelete) {
                    confirmingDelete = false;
                    rebuildWidgets();
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
            else if (overlay == Overlay.QUEST && mayEditNow() && button == 0) {
                // The item picker's rows first, from the last frame's own drawing. A press inside the
                // card that is not a row does nothing -- the list is what is on screen, and the page
                // behind it is not a second thing to press while a field is being set.
                if (pickingItemPath != null) {
                    int row = pickerFrame == null ? -1
                            : ItemPickerLayout.rowAt(pickerRows, pickerFrame, pickerScroll, mouseY);
                    if (row >= 0) {
                        pressPickerRow(row);
                    }
                    else if (clickedOutsideCard(mouseX, mouseY)) {
                        closeOverlay();
                    }
                    return true;
                }
                // The settings popover first, because it floats over the card: a press inside it that
                // no widget took is a press on the popover, and one outside it closes it -- the card
                // stays open, which is what "a popover over a page" means.
                if (settingsOpen) {
                    if (settingsPopover().contains(mouseX, mouseY)) {
                        pressSettingsStepper(mouseX, mouseY);
                        return true;
                    }
                    settingsOpen = false;
                    rebuildWidgets();
                    return true;
                }
                // The card's own marked pieces, from the last frame's drawing -- one derivation for
                // the mark and the press.
                for (EditTarget target : editTargets) {
                    if (target.box().contains(mouseX, mouseY)) {
                        pressEditTarget(target, mouseX, mouseY);
                        // **The press that opens a field owns the drag that follows it.** Press at one end
                        // of the prose and drag to the other is how a description is selected -- and the
                        // description is not open before that press, so without this the opening press was
                        // the only one the field never got and the drag went to the canvas instead. A
                        // one-line value is nearly always dragged *after* being opened, which is why the
                        // same gesture already worked everywhere else.
                        fieldDrag = editingPath != null;
                        return true;
                    }
                }
                if (clickedOutsideCard(mouseX, mouseY)) {
                    closeOverlay();
                }
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
            // The sample first: it is a map of the theme, so pointing at a part is how an author says "that
            // colour" without hunting through the list below. The test is the panel's own hover ring's, so
            // what is outlined under the pointer is what the press chooses.
            ToolsLayout.Hotspot part = ToolsLayout.hotspotAt(toolsFrame.preview(), mouseX, mouseY);
            if (part != null) {
                selectColour(part.token());
                return true;
            }
            // The radius row's two arrows before the scrollbar band, because a control beats chrome and the
            // arrows sit at the row's right edge where that band is. They are drawn controls rather than
            // widgets, and the hit test comes from the same two calls the panel draws them with -- so what
            // is pressed is what is seen, scrolled or not.
            Integer step = ToolsLayout.radiusStepAt(toolsView.viewport(),
                    toolsLayout == null ? null : toolsLayout.slot(ToolsLayout.RADIUS), mouseX, mouseY);
            if (step != null) {
                stepRadius(step);
                return true;
            }
            // A quest row in the Chapter tab is a draggable thing: the press claims it, and the drag
            // that may follow reorders the chapter's own list. `pressX`/`pressY` are recorded here
            // because the threshold compares against them and this press never reaches the canvas
            // branch that usually sets them.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                int row = chapterQuestRowAt(mouseX, mouseY);
                if (row >= 0) {
                    dragRowMember = "chapter";
                    dragRowQuest = null;
                    dragRowFrom = row;
                    dragRowLive = false;
                    dragRowPointerY = mouseY;
                    pressX = mouseX;
                    pressY = mouseY;
                    return true;
                }
            }
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

        // Left or middle on the canvas: begin a pan, or -- in developer mode -- pick a node up, or
        // stretch a marquee. Whether it becomes a pan or a click is decided by whether the pointer
        // moves, which is why nothing is selected yet -- and why a pan that happens to start on a node
        // does not change the selection.
        // Left, right or middle on the canvas. Left is the selection's and the drag's; middle pans;
        // right is the edge's. The modifiers are read here, at the press, and remembered -- one rule
        // for every gesture, so letting go of a key a moment early cannot change what a press meant.
        if ((button == 0 || button == 1 || button == 2) && inCanvas(mouseX, mouseY)) {
            dragging = button != 1;
            pressMoved = false;
            pressX = mouseX;
            pressY = mouseY;
            pressedShift = Screen.hasShiftDown();
            pressedCtrl = Screen.hasControlDown();
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

            // Right claims the press for the edge gesture, on a node with an editor open. Anywhere
            // else it does nothing: the canvas has no context menu, and a right-drag that panned
            // would fight the edge the button exists for.
            if (button == 1) {
                if (under != null && mayEditNow()) {
                    edgeDragFrom = under.id();
                    edgeDragLive = false;
                }
                return true;
            }

            // A left press *on* a node, with an editor open, picks the node up rather than panning. The
            // two cannot share the button, and this is the split every graph editor makes: a press on a
            // node is about that node, and a press on the canvas is about the view. The middle button
            // still pans from anywhere, which is what a trackpad-less mouse reaches for.
            //
            // The press is only the first of the four phases -- press, threshold, follow, release. The
            // node is claimed here but does not follow yet: that starts when the pointer has moved
            // further than a click's jitter, and until then this could still be a click.
            if (button == 0 && under != null && mayEditNow()) {
                if (pressedCtrl) {
                    // Ctrl-click toggles one node in or out of the selection -- the standard gesture,
                    // and the reason the edge drag lives on the right button. It is a statement about
                    // the selection and nothing else: no pick-up, no open, no drag.
                    toggleSelection(under.id());
                    return true;
                }
                if (pressedShift) {
                    // Shift adds to the selection as the press lands -- additive, so shift-clicking
                    // around a chapter collects nodes. The press still picks the node up, which is
                    // what makes shift-drag "add this one and move everything with it"; and a
                    // shift-click opens nothing, because collecting and opening are different jobs.
                    addToSelection(under.id());
                }
                // **Read before claiming.** `nodeX` answers with `dragNodeX` for the node the drag is
                // carrying, so claiming first would have read the *last* drag's leftover position --
                // zero at startup, which is where every early commit landed. The override exists for
                // the drawing during a drag; the press needs the truth.
                dragNodeX = nodeX(under);
                dragNodeY = nodeY(under);
                draggedNode = under.id();
                nodeDragLive = false;
                // Where inside the node the pointer took hold, so the node does not jump to put its
                // corner under the pointer.
                dragGrabX = viewport().contentX(mouseX) - dragNodeX;
                dragGrabY = viewport().contentY(mouseY) - dragNodeY;
                return true;
            }

            // A shift-drag on the empty canvas stretches the additive marquee. Shift because the plain
            // left-drag on the canvas is the pan -- in both modes, which is the rule this round bought:
            // navigation does not change with a mode. Ctrl+A and shift-click already collect, so the
            // marquee is the third way to say "add these", and it says it with the same modifier.
            if (button == 0 && under == null && mayEditNow() && pressedShift) {
                marqueeActive = true;
                marqueeX0 = (int) mouseX;
                marqueeY0 = (int) mouseY;
                marqueeX1 = marqueeX0;
                marqueeY1 = marqueeY0;
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // A drag that began on the open field extends its selection, and is consumed here: the press was
        // the field's (see `mouseClicked`), so the pan and the node drag must not also answer it. The
        // gesture came from play -- *"allow selecting as well, like dragging the cursor in text fields"*.
        if (fieldDrag) {
            if (inlineArea != null) {
                inlineArea.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            }
            else if (inlineField != null) {
                inlineField.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            }
            return true;
        }

        // The row drag, on the node drag's own phases as far as they apply: the press claimed the row,
        // the threshold separates a click from a drag, and the follow is just the pointer's y -- there
        // is no ghost row to carry, only the line the drop would land on. Consumed so the pan and the
        // node drag do not also answer a gesture that started on a row.
        if (dragRowFrom >= 0) {
            if (!dragRowLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                dragRowLive = true;
                pressMoved = true;
            }
            dragRowPointerY = mouseY;
            return true;
        }

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

        if (edgeDragFrom != null) {
            // The edge gesture's own threshold, the same one the other drags use: a shift-click that
            // jitters is still a selection toggle, and only a travelled press is an edge.
            if (!edgeDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                edgeDragLive = true;
                pressMoved = true;
            }
            edgeDragX = mouseX;
            edgeDragY = mouseY;
            return true;
        }

        if (draggedNode != null) {
            // Press, threshold, follow. Until the pointer has travelled further than a click's jitter,
            // the press is still a possible click — and a node that twitched under every click would
            // read as a jitter nobody asked for. The same threshold the pan uses, so a click means the
            // same amount of movement whichever surface it landed on.
            if (!nodeDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                nodeDragLive = true;
                pressMoved = true;
                // A selected node carries its selection with it. Where they all started is captured
                // now, once, because a delta can only be taken against where things were.
                dragStarts.clear();
                dragStartX = dragNodeX;
                dragStartY = dragNodeY;
                if (isSelected(draggedNode)) {
                    for (String id : selection()) {
                        ClientQuestCache.Entry entry = entryFor(id);
                        if (entry != null && !id.equals(draggedNode)) {
                            dragStarts.put(id, new float[] {nodeX(entry), nodeY(entry)});
                        }
                    }
                }
            }
            // A picked-up node follows the pointer, in content coordinates so the zoom does not
            // matter, and lands on the grid when the switch says so and Alt is not held — Alt is the
            // free placement, read per event so it can be pressed mid-drag. Committed on release
            // rather than here: a drag is one edit, and a file write per mouse move would be a file
            // write per mouse move. What is committed is what was last drawn — release takes these
            // fields — so the file never disagrees with the screen about where the node was dropped.
            dragNodeX = (float) BookGeometry.snap(viewport().contentX(mouseX) - dragGrabX,
                    BookGeometry.SNAP_GRID, snappingNow());
            dragNodeY = (float) BookGeometry.snap(viewport().contentY(mouseY) - dragGrabY,
                    BookGeometry.SNAP_GRID, snappingNow());
            // The rest of the selection moves by the same content delta, through the same pending
            // positions the canvas already reads -- so the lines and the labels move with them, and
            // release commits exactly what was drawn.
            float dx = dragNodeX - dragStartX;
            float dy = dragNodeY - dragStartY;
            if (!dragStarts.isEmpty()) {
                long revision = ClientQuestCache.treeRevision();
                for (Map.Entry<String, float[]> started : dragStarts.entrySet()) {
                    editors.moved(started.getKey(), started.getValue()[0] + dx,
                            started.getValue()[1] + dy, revision);
                }
            }
            return true;
        }

        if (marqueeActive) {
            pressMoved = true;
            marqueeX1 = (int) mouseX;
            marqueeY1 = (int) mouseY;
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
        // Let go: the mark the drag made stays (the model keeps it until the next press or keystroke), and
        // the *gesture* ends -- a later drag belongs to whatever it starts on.
        fieldDrag = false;

        // The row drag's release, read and cleared before anything else looks at the gesture: the drop
        // is committed exactly where the line was last drawn, and a press that never travelled is a
        // click -- which, on a grip, is nothing at all.
        if (dragRowFrom >= 0) {
            int from = dragRowFrom;
            String member = dragRowMember;
            String quest = dragRowQuest;
            boolean live = dragRowLive;
            double pointerY = dragRowPointerY;
            dragRowFrom = -1;
            dragRowMember = null;
            dragRowQuest = null;
            dragRowLive = false;
            dragging = false;
            pressedNode = null;
            if (live) {
                commitRowDrop(member, quest, from, pointerY);
            }
            return true;
        }

        // A link the press landed on, let go on the same rectangle: that is the click it was waiting for.
        LinkRect link = pressedLink;
        pressedLink = null;
        if (button == 0 && link != null) {
            if (link.box().contains(mouseX, mouseY)) {
                openLink(link.url());
            }
            return true;
        }

        // Consumed unconditionally once a drag is in progress, and deliberately not "only if the
        // pointer is still over the bar". Letting go outside the bar is how a drag ends in every
        // program ever written, and releasing on the last position the bar saw is what makes the end
        // of a drag land where the pointer was when it was let go.
        if (sidebarView.endThumbDrag() || partyView.endThumbDrag() || toolsView.endThumbDrag()) {
            return true;
        }

        if (edgeDragFrom != null) {
            String from = edgeDragFrom;
            boolean live = edgeDragLive;
            edgeDragFrom = null;
            edgeDragLive = false;
            dragging = false;
            pressedNode = null;

            if (live) {
                // The edge lands on whatever node is under the release, and the arrow points the way
                // the drag went: from the prerequisite to the quest that now depends on it. Landing on
                // empty canvas cancels -- a gesture that half-lands is a gesture that did not happen.
                String chapter = effectiveChapter();
                ClientQuestCache.Entry landing = chapter == null ? null
                        : nodeAt(mouseX, mouseY, questsIn(chapter));
                if (landing != null && !landing.id().equals(from)) {
                    JsonObject quest = ClientChapterReplica.quest(chapter, landing.id());
                    if (quest != null) {
                        List<String> dependencies =
                                new ArrayList<>(QuestPanelLayout.strings(quest, "dependsOn"));
                        if (!dependencies.contains(from)) {
                            dependencies.add(from);
                            send(new EditorOp.SetField(landing.id(), "dependsOn",
                                    stringArray(dependencies)));
                        }
                        else {
                            status(landing.id() + " already depends on " + from, true);
                        }
                    }
                }
            }
            // A right-press that never travelled does nothing: the button is the edge's, and a
            // right-click on a node is not a selection statement -- Ctrl-click is.
            return true;
        }

        if (draggedNode != null) {
            String id = draggedNode;
            float x = dragNodeX;
            float y = dragNodeY;
            boolean live = nodeDragLive;
            boolean shift = pressedShift;
            draggedNode = null;
            nodeDragLive = false;
            dragging = false;
            pressedNode = null;

            if (live) {
                // What the canvas showed when the pointer let go is what is committed: these are the
                // drawn, snapped coordinates, not a re-derivation that could disagree with them. The
                // selection that moved with it commits through the same pending positions -- one op
                // each, because a move is one quest's edit.
                commitMove(id, x, y);
                long revision = ClientQuestCache.treeRevision();
                for (String moved : dragStarts.keySet()) {
                    commitMove(moved, (float) editors.movedX(moved), (float) editors.movedY(moved),
                            revision);
                }
                dragStarts.clear();
            }
            else {
                // A press that never became a drag is still a click: it selects and opens, exactly as
                // it did before there was a drag to tell it apart from -- unless it was a shift-press,
                // which already added the node at press and opens nothing. The gesture is one gesture,
                // and developer mode only changed what "moved" means.
                if (shift) {
                    return true;
                }
                selectedQuest = id.equals(selectedQuest) ? null : id;
                multiSelection.clear();
                openOverlay(id);
            }
            return true;
        }

        if (marqueeActive) {
            marqueeActive = false;
            boolean moved = pressMoved;
            dragging = false;
            pressedNode = null;
            if (moved) {
                selectInsideMarquee();
            }
            // A shift-click on the empty canvas adds nothing and takes nothing away: the marquee's
            // modifier means "add", and an empty marquee adds an empty set. Clearing is the plain
            // click's job, below.
            return true;
        }

        if (dragging) {
            dragging = false;

            // A press that never moved is a click. Selecting on release rather than on press is what
            // makes "hold to pan" and "click to select" one gesture.
            if (!pressMoved && pressedNode != null && button == 0) {
                // The dependency pick: armed by the panel's Add-from-canvas action, the next click
                // names the quest to depend on instead of opening one. Disarmed by Escape, and by
                // landing -- a pick that stayed armed would turn every later click into a dependency.
                if (pickingDependency) {
                    pickingDependency = false;
                    addPickedDependency(pressedNode);
                    pressedNode = null;
                    return true;
                }

                selectedQuest = pressedNode.equals(selectedQuest) ? null : pressedNode;
                multiSelection.clear();

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
            if (!pressMoved && button == 0) {
                // A click on the empty canvas unselects everything -- the primary too, not only the
                // multi-selection: "press blank space to let go" is one gesture and not two.
                selectedQuest = null;
                multiSelection.clear();
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
            // While a picker is open the wheel is the list's, not the card's: the card is not what is
            // on screen. Clamped where it lands, because a flick past the bottom should stop at the
            // bottom -- the same rule as the body's below, for the same reason.
            if (pickingItemPath != null) {
                if (pickerFrame != null) {
                    pickerScroll = Math.max(0,
                            Math.min(pickerScroll - (int) (scrollY * 30),
                                    ItemPickerLayout.maxScroll(pickerRows, pickerFrame)));
                }
                return true;
            }
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
        // The item picker's keys, and they are read here rather than left to the search box: a field's
        // Enter and Escape *submit*, and while a picker is open neither means that -- Enter commits the
        // rule above, and Escape leaves the field as it was. Handing them to the box would set the
        // field to the text someone pressed Escape to abandon.
        if (pickingItemPath != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeItemPicker();
                rebuildWidgets();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitFromPicker();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                pickerSelected = ItemPickerLayout.step(pickerRows, pickerSelected, -1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                pickerSelected = ItemPickerLayout.step(pickerRows, pickerSelected, 1);
                return true;
            }
        }

        // Any overlay, not just the quest one. This tested `overlay == Overlay.QUEST`, which was the
        // whole of the truth while there was one overlay and stopped being true the moment a second
        // existed: the party panel could then be left by clicking outside or pressing Back, and not by
        // the key every player reaches for first. A key that closes a dialog closes the dialog.
        if (overlay != Overlay.NONE && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // A focused editor's Escape: commit and blur, not close the card. The widget handles it in
            // its own keyPressed once this method declines. **Unless the field is not on screen**: one
            // scrolled out of the body is stood down and takes no keys (see `repositionInlineEditor`),
            // so Escape means what it means for the card -- leave it, committing on the way out -- rather
            // than being answered by nobody.
            if (getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                    && focused.visible) {
                return false;
            }
            if (settingsOpen) {
                settingsOpen = false;
                rebuildWidgets();
                return true;
            }
            if (pickingEntryType != null) {
                pickingEntryType = null;
                rebuildWidgets();
                return true;
            }
            if (pickingDependency) {
                pickingDependency = false;
                pendingPick = null;
                status("Add cancelled", false);
                return true;
            }
            closeOverlay();
            return true;
        }

        // The editor's keys, and they are all modified or unclaimed: Ctrl+S is a save in every program
        // ever written, Ctrl+Z and Ctrl+Y are undo and redo, Ctrl+D duplicates, Ctrl+N is new. Delete is
        // the one bare key, and it only does anything when a node is selected — which is a state the
        // author put the screen in by clicking one.
        //
        // **Not while a text field has the keyboard.** A focused field's Backspace is a character and
        // its Ctrl+A is a selection; a screen that answered those with "delete the selected quest" and
        // "select every quest" would make typing in the editor a trap. Escape still reaches the card
        // above, and closing the card submits the focused field on the way out.
        if (mayEditNow() && getFocused() == null && keysForEditor(keyCode)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** @return whether the key was one of the editor's, and was handled */
    private boolean keysForEditor(int keyCode) {
        boolean ctrl = Screen.hasControlDown();

        if (ctrl && keyCode == GLFW.GLFW_KEY_S) {
            // Nothing to save, and that is the design rather than an omission: every edit is an operation the
            // server validates and writes as it is made, so there is no unsaved state for this key to reach.
            // Said out loud rather than ignored, because Ctrl+S is the key a person presses when a panel full
            // of edits makes them nervous, and a key that does nothing reads as a key that failed.
            report("Every edit is saved as it is made");
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
            send(new EditorOp.Undo());
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
            send(new EditorOp.Redo());
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_N) {
            createQuest();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_A && mayEditNow()) {
            selectAllInChapter();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            copySelection();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V && !ClientEditorClipboard.isEmpty()) {
            pasteClipboard();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_D && selectedQuest != null) {
            // The whole selection duplicates, one op each: a copy of six quests is six quests, and the
            // server names each one.
            for (String id : selection()) {
                send(new EditorOp.Duplicate(id));
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)
                && selectedQuest != null) {
            // The selection clears here rather than when the answer arrives: the node is on its way out, and a
            // canvas highlighting a quest the server has already been asked to remove is a highlight of
            // something that is about to not be there.
            for (String id : selection()) {
                send(new EditorOp.Delete(id));
            }
            selectedQuest = null;
            multiSelection.clear();
            return true;
        }
        return false;
    }

    /**
     * Adds a quest where the middle of the view is, which is where the author is looking.
     *
     * <p>The id is the server's to choose — it is the one that knows which names are taken — so this asks and
     * forgets: the reply carries the id, and {@code tick} selects it. Nothing is written here, and there is
     * nothing to save afterwards.
     */
    private void createQuest() {
        float x = viewport().contentX(canvasLeft() + (canvasRight() - canvasLeft()) / 2.0);
        float y = viewport().contentY(canvasTop() + (canvasBottom() - canvasTop()) / 2.0);
        // A new quest lands on the grid like a moved one: a position an author would have typed, by
        // the same rule, from the same switch — and free under Alt, like everywhere else.
        boolean on = snappingNow();
        send(new EditorOp.Create(Math.round(BookGeometry.snap(x, BookGeometry.SNAP_GRID, on)),
                Math.round(BookGeometry.snap(y, BookGeometry.SNAP_GRID, on))));
    }

    /**
     * Whether a position lands on the grid right now: the switch says so, and Alt is not held.
     *
     * <p>Read per event rather than remembered at press, because Alt is the bypass an author reaches
     * for mid-drag — hold it the moment the node is where they want it, and the follow stops rounding.
     * {@code Screen.hasAltDown()} is the keyboard's own answer; {@link BookGeometry#snap} stays a pure
     * function by taking this one as its {@code on}.
     */
    private static boolean snappingNow() {
        return DevMode.snap() && !Screen.hasAltDown();
    }

    /** Asks the server for one edit. The client writes nothing: see {@code TaskedNetworking.sendEditorOp}. */
    private void send(EditorOp op) {
        TaskedNetworking.sendEditorOp(effectiveChapter(), op);
    }

    /**
     * Commits a finished drag.
     *
     * <p>The position is rounded to whole content units, which is the grid a quest file is authored on:
     * a node at x=37.4182 is a number nobody typed on purpose, and the canvas is drawn at a zoom where
     * the difference is invisible — so the file would carry noise that only shows up in a diff.
     */
    private void commitMove(String id, float x, float y) {
        commitMove(id, x, y, ClientQuestCache.treeRevision());
    }

    /** The same, at a revision the caller already read: a selection's nodes commit against one tree. */
    private void commitMove(String id, float x, float y, long revision) {
        if (!mayEditNow()) {
            return;
        }
        // Remembered until the server's tree says the same thing: the canvas draws the server's answer, and the
        // server has not been asked yet. One exception with an expiry — see `EditorSession` — and the operation
        // that will end it.
        editors.moved(id, Math.round(x), Math.round(y), revision);
        send(new EditorOp.Move(id, Math.round(x), Math.round(y)));
    }

    // ------------------------------------------------------------------
    // The multi-selection's whole-hand gestures
    // ------------------------------------------------------------------

    /**
     * Adds one quest to the selection, which is what a shift-press on a node means.
     *
     * <p>Additive and idempotent: shift-clicking around a chapter collects nodes, and shift-clicking
     * one that is already selected changes nothing -- taking something out is Ctrl-click's job. The
     * first quest ever added becomes the primary, which is the one the property panel edits.
     */
    private static void addToSelection(String id) {
        if (selectedQuest == null) {
            selectedQuest = id;
        }
        else if (!isSelected(id)) {
            multiSelection.add(id);
        }
    }

    /**
     * Turns one quest's selection on or off, which is what a ctrl-click on a node means.
     *
     * <p>The one gesture that subtracts a single node, and the reason the edge drag moved to the right
     * button. A primary that is toggled off hands its slot to an extra rather than leaving the panel
     * with no target, so the selection and the panel cannot disagree about what is being edited.
     */
    private static void toggleSelection(String id) {
        if (multiSelection.remove(id)) {
            return;
        }
        if (id.equals(selectedQuest)) {
            selectedQuest = multiSelection.isEmpty() ? null : multiSelection.iterator().next();
            multiSelection.remove(selectedQuest);
            return;
        }
        if (selectedQuest == null) {
            selectedQuest = id;
        }
        else {
            multiSelection.add(id);
        }
    }

    /**
     * Adds every node the marquee rectangle covers to the selection.
     *
     * <p>The rectangle is in screen coordinates and so are the nodes' hit rects, which is what makes
     * "inside" one question: the same space on both sides, and a zoom that happened during the drag
     * already accounted for. Shape-exact is the wrong strictness here -- a marquee is about regions, and
     * a node half-covered is a node the rectangle was reaching for -- so this is the bounding box, not
     * the shape's own span test.
     *
     * <p>Additive, and by definition of the gesture: the marquee only starts under shift, and the
     * modifier is read at the press rather than at the release, so a marquee that ends with shift let
     * go still adds rather than replaces. To replace, clear first -- a plain click on the canvas.
     * {@code addToSelection} gives the first covered node the primary slot when nothing was selected,
     * so a marquee onto an empty selection behaves like a marquee onto nothing at all.
     */
    private void selectInsideMarquee() {
        int left = Math.min(marqueeX0, marqueeX1);
        int right = Math.max(marqueeX0, marqueeX1);
        int top = Math.min(marqueeY0, marqueeY1);
        int bottom = Math.max(marqueeY0, marqueeY1);
        // A click's worth of rectangle selects nothing: that press was a mis-aimed click, not a
        // marquee, and wiping the selection for it would be a gesture that punishes.
        if (right - left < DRAG_THRESHOLD && bottom - top < DRAG_THRESHOLD) {
            return;
        }
        for (ClientQuestCache.Entry entry : questsIn(effectiveChapter())) {
            int x = nodeScreenX(entry);
            int y = nodeScreenY(entry);
            int size = nodeSize(entry);
            if (x + size > left && x < right && y + size > top && y < bottom) {
                addToSelection(entry.id());
            }
        }
    }

    /** Copies the selection's own trees to the session clipboard. */
    private void copySelection() {
        List<String> ids = selection();
        if (ids.isEmpty()) {
            return;
        }
        String chapter = effectiveChapter();
        List<JsonObject> trees = new ArrayList<>();
        for (String id : ids) {
            JsonObject tree = ClientChapterReplica.quest(chapter, id);
            if (tree != null) {
                trees.add(tree);
            }
        }
        if (trees.isEmpty()) {
            status("Nothing to copy yet -- the chapter's copy has not arrived", true);
            return;
        }
        ClientEditorClipboard.copy(trees);
        status("Copied " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
    }

    /**
     * Pastes the clipboard, where the author is looking.
     *
     * <p>One op per quest, staggered a node apart so a multi-paste is visibly several quests rather
     * than one drawn over another -- the same step a duplicate takes. The ids are the server's to
     * choose, which is what makes pasting into the same chapter twice come out with different names
     * instead of one quest written over another.
     */
    private void pasteClipboard() {
        List<JsonObject> trees = ClientEditorClipboard.quests();
        if (trees.isEmpty()) {
            return;
        }
        float x = viewport().contentX(canvasLeft() + (canvasRight() - canvasLeft()) / 2.0);
        float y = viewport().contentY(canvasTop() + (canvasBottom() - canvasTop()) / 2.0);
        boolean on = snappingNow();
        for (int i = 0; i < trees.size(); i++) {
            send(new EditorOp.Paste(trees.get(i),
                    BookGeometry.snap(x + i * 48, BookGeometry.SNAP_GRID, on),
                    BookGeometry.snap(y, BookGeometry.SNAP_GRID, on)));
        }
        status("Pasting " + trees.size() + (trees.size() == 1 ? " quest" : " quests"), false);
    }

    /** Selects every quest in the chapter: the primary first, the rest in the chapter's own order. */
    private void selectAllInChapter() {
        List<ClientQuestCache.Entry> quests = questsIn(effectiveChapter());
        if (quests == null || quests.isEmpty()) {
            return;
        }
        multiSelection.clear();
        selectedQuest = quests.get(0).id();
        for (int i = 1; i < quests.size(); i++) {
            multiSelection.add(quests.get(i).id());
        }
        status("Selected " + quests.size() + (quests.size() == 1 ? " quest" : " quests"), false);
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

    /** One line in the chat, for something the author did. */
    private void report(String message) {
        say("\u00a77" + message);
    }

    private void say(String message) {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(message));
        }
    }

    /**
     * Reads whatever the server said since the last frame, and rebuilds the quest panel when its sources
     * have moved under it.
     *
     * <p>The only place an author is told about an edit, because the screen is the only thing that can tell
     * them: the payload handler keeps the answer where this finds it, since the reply arrives on the game
     * thread with whatever is open — which may be nothing. See {@code ClientEditReplies}.
     *
     * <p>The panel's rows are built from the replica at rebuild time, so a tree or a copy arriving after
     * they were built leaves the panel showing the past. The rebuild is here rather than on the frame
     * path, because a rebuild inside a render destroys the very field being typed into -- and tick runs
     * between frames, where a widget can be replaced safely.
     */
    @Override
    public void tick() {
        super.tick();

        long revision = ClientQuestCache.treeRevision();
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(effectiveChapter());
        long replicaRevision = copy == null ? -1 : copy.revision();
        if (revision != questPanelRevision || replicaRevision != questReplicaRevision) {
            questPanelRevision = revision;
            questReplicaRevision = replicaRevision;
            boolean dockPanel = toolsOpen && toolsTab == ToolsLayout.Tab.CHAPTER
                    && overlay == Overlay.NONE;
            boolean modalEditor = overlay == Overlay.QUEST && mayEditNow();
            if (dockPanel || modalEditor) {
                rebuildWidgets();
            }
        }

        // The card follows the **caret**, and only when the caret has moved: an editor below the fold is
        // an editor typed into blind, but a view that re-scrolls to its own box every tick is a view that
        // fights the wheel -- and with a description taller than the body it did not even settle, because
        // the "bottom is below the fold" and "top is above it" branches took turns. That was the report
        // (*"scrolling while having the cursor somewhere in the text works super gimmicky"*), and it asked
        // for exactly this split: the wheel wins, the caret follows when the caret is what moved.
        //
        // In tick rather than in the drawing, because scrolling re-places widgets and that is not a thing
        // to do inside a render.
        if (overlay == Overlay.QUEST && mayEditNow()) {
            followCaret();
        }

        EditorReplyPayload reply = ClientEditReplies.take();
        if (reply == null || !reply.chapter().equals(effectiveChapter())) {
            return;
        }
        for (String line : reply.lines()) {
            if (reply.ok()) {
                report(line);
            }
            else {
                say("§c" + line);
            }
        }
        if (reply.ok() && !reply.questId().isEmpty()) {
            // A quest the server made: created, or duplicated. Selecting it here rather than when the op was
            // sent, because the id is the server's to choose and this is the first moment the author has it.
            selectedQuest = reply.questId();
            report("Now editing " + reply.questId());
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
        multiSelection.clear();
        // The clipboard goes too: another server's quests are not this one's to paste, and a tree
        // carried across a disconnect is an op the new server would dutifully apply to its own files.
        ClientEditorClipboard.clear();
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
            return ClientAppearance.LOOK.main();
        }

        String named = ClientQuestCache.chapterTheme(chapter);
        if (named == null) {
            return ClientAppearance.LOOK.main();
        }

        Theme found = Themes.any(named);
        if (found == null) {
            warnAboutThemeOnce(chapter, named);
            return ClientAppearance.LOOK.main();
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
    // both have gone with the buttons. Neither did any work: one called `ClientAppearance.LOOK.cycleTheme()` and
    // the other `ClientAppearance.LOOK.setMotion(!ClientAppearance.LOOK.motion())`, and what they added was a `rebuildWidgets()`
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
