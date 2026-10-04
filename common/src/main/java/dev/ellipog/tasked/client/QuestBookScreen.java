package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ArmatureSwitch;
import dev.ellipog.armature.client.ArmatureTextField;
import dev.ellipog.armature.client.Look;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.Tooltips;
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
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.editor.EditorOp;
import dev.ellipog.tasked.net.EditorReplyPayload;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.client.ClientChapterReplica;
import dev.ellipog.tasked.client.ClientEditReplies;
import dev.ellipog.tasked.client.viewer.QuestBookFocus;
import dev.ellipog.tasked.client.viewer.RecipeLookups;
import dev.ellipog.tasked.client.dev.HexColour;
import dev.ellipog.tasked.client.dev.ChapterNaming;
import dev.ellipog.tasked.client.dev.ChapterPanel;
import dev.ellipog.tasked.client.dev.ChapterPanelLayout;
import dev.ellipog.tasked.client.dev.ChapterTheme;
import dev.ellipog.tasked.client.dev.CanvasReveal;
import dev.ellipog.tasked.client.dev.ClientEditorClipboard;
import dev.ellipog.tasked.client.dev.EntryFormLayout;
import dev.ellipog.tasked.quest.EditorField;
import dev.ellipog.tasked.quest.EditorSpecs;
import dev.ellipog.tasked.client.dev.InlineEdit;
import dev.ellipog.tasked.client.dev.ItemPicker;
import dev.ellipog.tasked.client.dev.ItemPickerLayout;
import dev.ellipog.tasked.client.dev.Alignment;
import dev.ellipog.tasked.client.dev.LineArt;
import dev.ellipog.tasked.client.dev.MenuFlyout;
import dev.ellipog.tasked.client.dev.MenuFlyoutArt;
import dev.ellipog.tasked.client.dev.MenuPlacement;
import dev.ellipog.tasked.client.dev.QuestPanel;
import dev.ellipog.tasked.client.dev.QuestSettingsLayout;
import dev.ellipog.tasked.client.dev.RewardBadge;
import dev.ellipog.tasked.client.dev.QuestSettingsPanel;
import dev.ellipog.tasked.client.dev.SettingsDraft;
import dev.ellipog.tasked.quest.DependencyStyle;
import dev.ellipog.tasked.quest.QuestShape;
import dev.ellipog.tasked.client.dev.QuestPanelLayout;
import dev.ellipog.tasked.client.dev.RowDrag;
import dev.ellipog.tasked.client.dev.SearchCatalogue;
import dev.ellipog.tasked.client.dev.SidebarDrag;
import dev.ellipog.tasked.client.dev.ToolsLayout;
import dev.ellipog.tasked.client.dev.ToolsPanel;
import dev.ellipog.tasked.client.dev.ToastStack;
import dev.ellipog.tasked.editor.EditorSession;
import dev.ellipog.tasked.editor.QuestEditor;
import dev.ellipog.tasked.net.PartySnapshot;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.client.ClientPartyCache;
import dev.ellipog.tasked.net.ClaimRewardPayload;
import dev.ellipog.tasked.net.ClaimAllPayload;
import dev.ellipog.tasked.net.ChoiceRewardPayload;
import dev.ellipog.tasked.net.ClaimChoicePayload;
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
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    private static final float MIN_ZOOM = 0.35F;
    private static final float MAX_ZOOM = 2.2F;

    /** How far the pointer must move before a press counts as a drag rather than a click. */
    private static final double DRAG_THRESHOLD = 4.0;

    /** Which panel is covering the book, if any. */
    private enum Overlay {
        NONE,
        QUEST,
        PARTY,
        /**
         * The item picker on its own, for a field that is not a quest's.
         *
         * <p>It used to be a page of the quest card and nothing else, because every field it served
         * belonged to a quest. The Chapter tab's icon is the first caller outside that card, and a picker
         * that demanded a quest to open would have meant a second picker -- so it became an overlay of
         * its own, drawn with the same card and the same list. See {@link #pickTarget}.
         */
        PICKER,
        /**
         * A choice reward's entries, waiting for the player's answer.
         *
         * <p>A card of its own because the question has no quest card behind it: the offer arrives when
         * the player claims, which may be from the reader, from the canvas, or from the rewards panel --
         * and it outlives any one of them, held in {@code ClientChoiceOffers} until it is answered or
         * dismissed. Dismissing loses nothing: the reward stays outstanding on the server, so pressing
         * Claim again produces the same question.
         */
        CHOICE,
        /**
         * What the server owes this player, and the one press that collects all of it.
         *
         * <p>A list rather than a pile of buttons on the canvas: a finished quest's rewards are the
         * server's to state (see {@code ClientQuestCache.canClaimFor}), and a card is where a player can
         * read them before spending them. Opened from the header, between Party and Close.
         */
        REWARDS,
        /**
         * The naming card: a title and an id for a chapter or a group being made, renamed or duplicated.
         *
         * <p>A card of its own rather than a page of the quest card, because what it names may have no
         * quest and no chapter behind it at all -- a new group, or a chapter about to be created. It is
         * the one overlay opened from the sidebar rather than from a quest, and the one whose fields are
         * read without an editor session: the ops it sends are structural.
         */
        NAMING
    }

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
    /** The dependency line under the pointer, keyed by its two ids: the brightening before a right-click. */
    private final Hover edgeHover = new Hover();

    /**
     * The last line-hover answer, and the pointer/view state it was computed under.
     *
     * <p>The nearest-line walk reads every point of every route, and it used to run every frame in edit
     * mode. A still pointer over a still canvas cannot change the answer, so it is kept until the
     * pointer, the view or the chapter's geometry moves -- see {@link #lineHover}.
     */
    private double lastLineHoverX = Double.NaN;
    private double lastLineHoverY = Double.NaN;
    private float lastLineHoverScale = -1F;
    private int lastLineHoverPanX;
    private int lastLineHoverPanY;
    private int lastLineHoverEdges = -1;
    private long lastLineHoverRevision = -1L;
    private boolean lastLineHoverMoving;
    private String[] lastLineHover;

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
     * The tree revision the selection was last checked against; see {@link #pruneSelection}.
     *
     * <p>Static with the selection it guards, because the selection outlives the screen and so must the
     * memory of which tree it was checked against -- an instance field would re-prune (harmlessly) on
     * every reopen and, worse, would not notice a reload that happened while the book was closed.
     */
    private static long selectionRevision = -1L;

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

    /** What the pick is for, and the rules a click follows: see {@code DependencyPick}. */
    private dev.ellipog.tasked.client.dev.DependencyPick pendingPick;

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
     * The card's back stack: one entry per "Requires" jump, newest last.
     *
     * <p>A prerequisite chain is four deep — Rocket, Fuel Tank, Steel Plate, Blast Furnace — before a
     * reader notices they cannot step back without losing the thread, so every jump out of a card
     * remembers the card it left: which quest, and where its body was scrolled and folded. Cleared
     * when the card closes or a fresh card opens from the canvas; a jump or a step back keeps it.
     */
    private final List<CardVisit> overlayHistory = new ArrayList<>();

    /** One card in the back stack: the quest, and the body state it was left in. */
    private record CardVisit(String questId, int scrollY, Set<String> folded) {
    }

    /**
     * The locate-on-canvas move in flight: which node it is going to, and where it started.
     *
     * <p>Advanced once a frame from the canvas drawing — the only place the move can be seen, since a
     * card hides the canvas — and cancelled by any manual pan or zoom, because a camera that fights the
     * hand is worse than no camera move at all.
     */
    private String glideQuest;
    private long glideStart;
    private int glideFromX;
    private int glideFromY;
    private int glideToX;
    private int glideToY;

    /** The node whose outline is flashing after a locate, and when the flash began. */
    private String flashQuest;
    private long flashStart;

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
    private Layout partyLeftLayout;
    private Layout partyRightLayout;
    private PartyPanelLayout.Face partyLeftFace;
    private PartyPanelLayout.Face partyRightFace;
    private final ScrollView partyLeftView = ScrollView.of(Viewport.fixed());
    private final ScrollView partyRightView = ScrollView.of(Viewport.fixed());

    /**
     * The card the panel was built into: what the widgets were placed inside, and what the drawing and
     * the click-outside test read.
     *
     * <h2>Why this is a field rather than a second computation</h2>
     *
     * <p>Because the card is built once and everything inside it is placed from that one rectangle — the
     * two columns, their viewports, the footer and the clip. A drawing that re-derived it would be the
     * second arithmetic this panel was rewritten twice to remove.
     */
    private BookGeometry.Rect partyCard;

    /**
     * The rows each column drew, kept because a row's <i>label</i> is drawn by the panel rather than by
     * a widget: only the controls are widgets, so the drawing needs the same lines the controls were
     * made from, and a second derivation of them is how a name ends up beside somebody else's button.
     */
    private List<PartyPanelLayout.Line> partyLeftLines = new ArrayList<>();
    private List<PartyPanelLayout.Line> partyRightLines = new ArrayList<>();

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
     * {@code init}, which disarms — so arming and rebuilding would be the same press, and the second
     * press would send. The window is three seconds: a confirmation that never expires is not a
     * confirmation, it is a flag a player sets and forgets.
     */
    private ArmatureButton disbandButton;
    private final ArmedPress disbandPress = new ArmedPress();

    /** Which question the panel is asking, if any: the two flows that need an answer before acting. */
    private enum PartyPhase {
        /** The ordinary panel. */
        NONE,
        /** Hand the party to one member, confirmed. */
        CONFIRM_TRANSFER,
        /** The owner is leaving and must pick who owns it — or disband. */
        PICK_SUCCESSOR
    }

    private PartyPhase partyPhase = PartyPhase.NONE;
    private UUID partyPhaseTarget;

    /** Whether the owner has the inline rename field open, on the left column's name row. */
    private boolean partyRenaming;

    /**
     * What the solo face's two fields hold between rebuilds.
     *
     * <p>Kept because a rebuild recreates the widgets — the roster revision moves on every push, and a
     * rebuild is what redraws the panel — so the text a player was typing has to survive in a field the
     * screen owns, or it would be wiped by somebody else's join.
     */
    private String partyCreateName = "";
    private String partySearch = "";
    private boolean partyCreateFocused;
    private boolean partySearchFocused;
    private ArmatureTextField partyCreateField;
    private ArmatureTextField partySearchField;
    private ArmatureTextField partyNameField;

    /**
     * The member rows' owner controls, by member id.
     *
     * <p>Kept because their <b>visibility</b> is decided at draw time from the row's hover: the widgets
     * exist whenever the viewer may use them, and a row that is not hovered draws neither. See
     * {@code drawPartyMembers} — the alternative, creating them on hover, would be widgets made in a
     * drawing pass.
     */
    private final Map<UUID, ArmatureButton> partyRemoveButtons = new java.util.HashMap<>();
    private final Map<UUID, ArmatureButton> partyTransferButtons = new java.util.HashMap<>();

    /**
     * Every row control the panel built, by control key.
     *
     * <h2>Why these are placed here rather than registered with the scroll view</h2>
     *
     * <p>{@code ScrollView} matches a registered widget to a slot <b>by key</b>, and its slots are the
     * layout's rows. A row with one control registered under the row's own key works -- and that is how
     * the search field is handled. A row with <b>two</b> controls cannot: two widgets would need one
     * row key, and a control key like {@code right:open:switch} is a key no row carries -- so the view
     * hides it as "registered but not in this layout". That is exactly what happened to the settings
     * switches and the rename pencil: built, added, invisible.
     *
     * <p>So the controls are placed from {@link PartyPanelLayout#controlSlots} every frame, in the same
     * pass that reads the layout for everything else -- one derivation for the rectangle, read by the
     * placement, the hover logic and the preview. The scroll views keep what they are good at: the
     * clamp, the bar and the wheel.
     */
    private final Map<String, net.minecraft.client.gui.components.AbstractWidget> partyControls =
            new java.util.HashMap<>();

    /** The measure every party row is built with. Fixed-width, because a row is a fixed height. */
    private static final Measure TEXT_MEASURE = Measure.monospace(6, 9);

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
     * The choice offer's own list, while its card is open: one row per entry the server offered.
     *
     * <p>A view of its own rather than {@link #overlayView}, for the reason that one is the card's: the
     * two are never open at once, and a shared view would carry the quest card's scroll into a question
     * with three answers.
     */
    private final ScrollView choiceView = ScrollView.of(Viewport.fixed());

    /** The choice offer's rows and layout, for the card that is open. */
    private List<InspectRow> choiceRows = List.of();
    private Layout choiceLayout;

    /** The key prefix of a choice offer's rows; the entry's index in the table follows. */
    private static final String CHOICE_PREFIX = "choice:";

    /**
     * The rewards panel's own list: one row per quest whose rewards are waiting.
     *
     * <p>Its own view for the reason {@link #choiceView} is: the two cards are never open at once, and a
     * shared scroll would carry one list's position into the other.
     */
    private final ScrollView rewardView = ScrollView.of(Viewport.fixed());

    /** The rewards panel's rows and layout, for the card that is open. */
    private List<InspectRow> rewardRows = List.of();
    private Layout rewardLayout;

    /** The key prefix of a rewards row; the quest's id follows. */
    private static final String REWARD_PREFIX = "reward:";

    /** The header's way into the panel, kept so its tooltip can say whether anything is waiting. */
    private ArmatureButton rewardsButton;

    /** The progress revision the rewards panel's rows were built at; see {@link #tick}. */
    private long rewardsRevision = -1;

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

    /**
     * Which groups the player has opened or closed, over the groups' own defaults.
     *
     * <p>Kept across sidebar rebuilds, and a sidebar is rebuilt every time the tree arrives — which is
     * after every structural edit. Without this the authored {@code collapsedByDefault} won every time,
     * so a group snapped shut the moment something was dragged into it.
     */
    private static final Map<String, Boolean> sidebarExpansion = new LinkedHashMap<>();

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

    /**
     * Whether the Theme tab is editing the open chapter's palette rather than the player's own theme.
     *
     * <p>Off by default: the panel's day-to-day job is the player's theme, and the chapter target is the
     * author's switch. It writes the chapter file through the same field op every other chapter edit
     * uses, so an undo takes it back like any other change.
     */
    private boolean themeTargetsChapter;

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
     * The entries folded to their badges, by {@code member.index}.
     *
     * <p>The same index-keyed shape {@link #questFolded} uses, and for the same reason: the key names a
     * position in a list the card rebuilds from the tree, and a key that survived a switch of quests would
     * be folding an entry nobody chose.
     */
    private final Set<String> entryFolded = new LinkedHashSet<>();

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
        /** A prerequisite's name: the press opens that quest's card, the reader's own jump. */
        NAVIGATE_DEP,
        /** A prerequisite's locate icon: close the card and take the canvas to that node. */
        LOCATE_DEP,
        /** The row's leading strip: a press that travels becomes a reorder. */
        DRAG_ENTRY,
        /** A stepper's two chips: the number moves by one, or by ten with shift held. */
        STEP_UP,
        STEP_DOWN,
        /** A choice: the press moves to the next option in the field's own ring. */
        CYCLE_CHOICE,
        /** A position's button: the corner and the dimension become where the player stands. */
        USE_POSITION,
        /** A field whose ids are a list: the press opens the picker on the field's own source. */
        SEARCH,
        /** The triangle in an entry's corner: fold it to its badge, or unfold it. */
        TOGGLE_ENTRY,
        /** The row under an entry that opens the condition picker. */
        CONDITION_ADD,
        /** A condition's cross: the condition is removed from the entry's list. */
        CONDITION_REMOVE
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

    /** The book's own transient messages; see {@link ToastStack} for why chat is not enough. */
    private final ToastStack toasts = new ToastStack();

    /** The progress revision the last completion notice was read at; see {@link #announceCompletions}. */
    private long announcedProgress = -1;

    /**
     * The reward counts the badges read, rebuilt only when the progress or the tree moves.
     *
     * <p>One walk over the entries per revision rather than one per node per frame: the maps answer
     * "what is waiting" for the canvas and the sidebar at once, and both are read every frame.
     */
    private record RewardCounts(long progress, long tree, java.util.UUID player,
                                Map<String, Integer> byQuest, Map<String, Integer> byChapter) {
    }

    private RewardCounts rewardCounts;

    /** The badge counts in force, recomputed when the progress, the tree or the viewer changes. */
    private RewardCounts rewardCounts() {
        long progress = ClientQuestCache.progressRevision();
        long tree = ClientQuestCache.treeRevision();
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        RewardCounts current = rewardCounts;
        if (current != null && current.progress() == progress && current.tree() == tree
                && java.util.Objects.equals(current.player(), self)) {
            return current;
        }
        rewardCounts = new RewardCounts(progress, tree, self,
                ClientQuestCache.outstandingByQuest(self), ClientQuestCache.claimableByChapter(self));
        return rewardCounts;
    }

    /**
     * What each quest was in the last progress sync that was read.
     *
     * <p>Kept so a quest that <i>becomes</i> collectable can be told from one that was already: a sync
     * carries a state, not a change. Empty at a join, which is what stops the first sync from announcing
     * the whole finished half of the book.
     */
    private final Map<String, QuestState> lastStates = new LinkedHashMap<>();

    /**
     * Says which quests have just become collectable.
     *
     * <p>Derived rather than sent, because it is the only signal the book has: the server's own line about a
     * completion goes to chat, and chat is behind this screen. A progress sync moves the state, and the
     * comparison with the last sync is the difference between "this quest is finished" and "this quest just
     * finished".
     *
     * <p>Capped at the stack's own size: a claim-all or a party's shared progress can move a dozen quests at
     * once, and twelve notices stacked on the card is a column nobody reads -- the canvas behind shows the
     * rest turning their colour.
     */
    private void announceCompletions() {
        List<String> fresh = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            QuestState state = ClientQuestCache.stateOf(entry.id());
            QuestState was = lastStates.put(entry.id(), state);
            if (was != null && was != QuestState.COMPLETED && state == QuestState.COMPLETED) {
                // A quest whose rewards are handed over silently says nothing: the author asked for the
                // mode, and fifty starter quests announcing themselves is the noise auto-claim exists to
                // remove. See RewardAutoClaim.notifies -- this is the half that made `no_toast` and
                // `invisible` mean something.
                dev.ellipog.tasked.quest.reward.RewardAutoClaim mode = entry.effectiveAutoClaim();
                if (mode.automatic() && !mode.notifies()) {
                    continue;
                }
                fresh.add(titleOf(entry));
            }
        }
        for (int i = 0; i < Math.min(fresh.size(), ToastStack.MAX); i++) {
            toast(Component.translatable("tasked.quest.completed", fresh.get(i)).getString(), false);
        }
    }

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

    /**
     * The explanation of a hovered task or reward row, waiting to be drawn with the tooltips.
     *
     * <p>Collected the same way the pending labels are, and for a sharper version of the same reason:
     * the row is drawn <b>inside the card body's clip</b>, and a tooltip belongs over everything,
     * including the edge it reaches past. A box drawn at the row would be cut off at the body's edge;
     * drawn in the tooltip layer it is not. One entry at most per frame -- a pointer is a single point
     * -- but a list, so the collecting code does not have to know that.
     */
    private final List<RowTooltip> rowTooltips = new ArrayList<>();

    private record RowTooltip(Slot box, List<String> lines) {
    }

    /**
     * A task's or reward's row that can send the player to a recipe viewer, from the last frame's
     * own drawing — the contract {@code editTargets} and the picker rows already use, so a press
     * lands on the row that was drawn and a row scrolled out of the card is not pressable.
     */
    private final List<RowItem> rowItems = new ArrayList<>();

    private record RowItem(Slot box, RecipeLookups.Target target) {
    }

    /**
     * The reader card's prerequisite rows, from the last frame's drawing: where a press navigates or
     * locates.
     *
     * <p>The same lifecycle as {@link #rowItems} — rebuilt by the drawing, read by the click — with one
     * row carrying two targets: the row itself is the jump to that quest's card, and the locate icon at
     * its right edge (or a middle-click or shift-click anywhere on it) is "show me where it is".
     */
    private final List<DependencyTarget> dependencyTargets = new ArrayList<>();

    private record DependencyTarget(Slot row, BookGeometry.Rect locate, String questId) {
    }

    /** The field being edited inline, by path, and the widget editing it. One at a time. */
    private String editingPath;
    private ArmatureTextField inlineField;
    private ArmatureTextArea inlineArea;

    /**
     * The settings page: shape, size, placement and rules, over the whole card body.
     *
     * <p>It was a popover of stepper rows above the Settings button, and the third playtest's report
     * was that it read as a form — a shape you cycle with + and −, a size that does nothing above 48,
     * and a preview nowhere. The page draws the node it is changing, offers the shapes as shapes, and
     * spans the size the format actually allows. The rows are gone with the popover: what is left of
     * the inspector is the dock's own panel and the type picker.
     */
    private boolean settingsOpen;
    private ArmatureButton settingsButton;
    private final dev.ellipog.armature.client.ui.kit.ScrollView settingsView =
            dev.ellipog.armature.client.ui.kit.ScrollView.of(
                    dev.ellipog.armature.client.ui.kit.Viewport.fixed());
    private List<dev.ellipog.tasked.client.dev.QuestSettingsLayout.Row> settingsRows = List.of();
    private Layout settingsLayout;
    private final dev.ellipog.tasked.client.dev.SettingsDraft settingsDraft =
            new dev.ellipog.tasked.client.dev.SettingsDraft();

    /**
     * The card's field values the server has not answered yet — see {@code FieldDraft} for why a
     * stepper cannot wait for the round trip. It is a second draft rather than the settings page's
     * because it expires on a different signal: the settings preview reads the tree, which arrives in
     * milliseconds, while these fields read the replica, which lags by up to its retry window.
     */
    private static final dev.ellipog.tasked.client.dev.FieldDraft fieldDraft =
            new dev.ellipog.tasked.client.dev.FieldDraft();

    /** Which slider the pointer has hold of ("size"/"iconScale"/"rotation"), or null. */
    private String draggingSlider;

    /** The value under the pointer during a drag: the release commits this, not the draft. */
    private double draggingValue;

    /** The settings preview's resolved outline, and the shape and angle it was resolved from. */
    private QuestShape previewShape;
    private int previewRotation = Integer.MIN_VALUE;
    private dev.ellipog.armature.client.ui.shape.Shape previewGeometry;

    /** The chapter panel's rows and layout, for the dock's Chapter tab. */
    private List<InspectRow> chapterRows = List.of();
    private Layout chapterLayout;

    /**
     * The Chapter tab's header: its name, its icon, and the id that icon was resolved from.
     *
     * <p>Recorded when the rows are built, from the same chapter tree, so the band at the top and the
     * fields below cannot describe two different chapters. The stack is kept beside the id because a
     * missing item is a fact worth drawing -- an empty box reads as "no icon set" -- and the id is how
     * the drawing tells the two apart.
     */
    private ChapterPanelLayout.Header chapterHeader = new ChapterPanelLayout.Header("", "");
    private ItemStack chapterIcon = ItemStack.EMPTY;
    private String chapterIconId = "";

    /** Which list the modal's type picker is adding to ("tasks"/"rewards"), or null when it is closed. */
    private String pickingEntryType;

    /**
     * Which entry's conditions list the picker is adding to, as a path ("tasks.3"), or null.
     *
     * <p>Separate from {@link #pickingEntryType} rather than a third member name, because the insert is
     * a different op: a task is a new element of a top-level list ({@code EditorOp.Insert}), and a
     * condition is a rebuilt nested array written with {@code SetField} — nested arrays have no Insert.
     * When this is set the picker lists condition types; when the member is set it lists that member's.
     */
    private String pickingConditionFor;

    /** Which field the item picker is setting, or null when it is closed. */
    private String pickingItemPath;

    /**
     * What the open picker is listing, when it is not items.
     *
     * <p>A search field -- a dimension, a biome, a statistic -- opens the same card, the same list and the
     * same box as the item picker does; what changes is where the rows come from. Null means items, which
     * is what every pick before this one was.
     */
    private EditorField.Source pickerSource;

    /** How many rows a search field shows before anything is typed. */
    private static final int FIRST_ROWS = 10;

    /**
     * Whether the picker was opened from the settings page, and should hand back to it.
     *
     * <p>The page's Icon row opens the same picker the card does, and the picker closes the page while it
     * is up -- a list is what you are reading, and the page behind it is context. What the report caught
     * was the other half: choosing an item closed the picker and left the author in the card's editor,
     * with the page they were working on gone. Coming back is the whole of "Change…" as a button on that
     * page rather than a detour out of it.
     */
    private boolean pickerFromSettings;

    /**
     * Which of the three things an open picker is setting: a quest's field, the chapter's icon, or its
     * group's icon.
     *
     * <p>The picker is one surface with three callers now, and the pick decides three different ops on
     * two different files -- {@code SetField} on a quest, {@code SetChapter} and {@code SetGroup} on the
     * chapter's own and its group's. A single nullable field rather than three booleans, so "which file"
     * has one answer and a pick from the dock can never be written to whatever quest happens to be
     * selected. Cleared with the picker's other state.
     */
    private enum PickTarget { QUEST, CHAPTER, GROUP }

    /** The open picker's target, or null when it is closed. */
    private PickTarget pickTarget;

    /**
     * The icon and the name the picker's own card shows, so the card says what it is about.
     *
     * <p>Set when the picker opens and refreshed whenever its widgets are rebuilt: the row the press came
     * from is behind the card, and "Pick an item" alone would not say which chapter's or group's icon is
     * being set. For a group these are the group's <b>own</b> icon and title -- an empty stack is the
     * honest picture of a group that has none of its own yet.
     */
    private ItemStack pickIcon = ItemStack.EMPTY;
    private String pickName = "";

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
     * The sidebar tree's drag: the row a press landed on, whether it has travelled, and what it means.
     *
     * <p>A second set of fields rather than reuse of the row drag's, because the tree's gesture has one
     * question the flat lists do not: <b>which container</b>. The row keys are the sidebar's own
     * ({@code group:} / {@code chapter:} prefixes), and {@link SidebarDrag} answers with a container key
     * and a position among its children — so what is kept here is the key and the last answer, not an
     * index into a list that the drop itself may move things out of.
     */
    private String sidebarDragKey;
    private boolean sidebarDragGroup;
    private boolean sidebarDragLive;
    private double sidebarDragPointerY;
    private double sidebarDragPressX;
    private double sidebarDragPressY;
    private SidebarDrag.Drop sidebarDrop;

    /** Whether the naming card is open, and what it is naming. */
    private Naming naming;

    /** Which of the three things the naming card does. */
    private enum NamingMode {
        CREATE,
        RENAME,
        DUPLICATE
    }

    /**
     * One naming request: what is being named, what is being done to it, and where a new one goes.
     *
     * <p>One record for all three modes rather than three paths, because create, rename and duplicate
     * differ only in the op they send and the id they start from -- and a second copy of the card for
     * each would be three places the validation rule could drift apart.
     */
    private record Naming(String kind, NamingMode mode, String targetId, String groupId, int index) {
    }

    private ArmatureTextField namingTitle;
    private ArmatureTextField namingId;

    /** The sidebar's right-click menu: its items and where it was opened. Empty when it is shut. */
    private List<MenuItem> menu = List.of();
    private int menuX;
    private int menuY;

    /** The chapter a "Move to" submenu would move, or null when the menu has no such row. */
    private String menuChapter;

    /**
     * The menu row whose submenu is showing, or -1.
     *
     * <p>State rather than a question asked of the pointer, and that is the whole of the fix for a
     * submenu that vanished on the way to it: while the pointer is on the panel — or in the bridge
     * between the two panels — this stays where it was, so travelling to a group does not cross a frame
     * where the row is not hovered and the destinations are gone.
     */
    private int submenuRow = -1;

    /**
     * Whether the menu's Delete is armed: the first press asks, the second deletes.
     *
     * <p>The same two-press rule the quest card's Delete already follows, and for the same reason: it is
     * the one row whose mistake cannot be undone by pressing something else. Disarming is what
     * {@link #closeMenu} does, so every other press, Escape and clicking away all mean "no".
     */
    private boolean menuDeleteArmed;

    /**
     * What a canvas menu's row was about: a quest, or a line between two.
     *
     * <p>A line's subject is the pair (dependency, dependent) — the same key its override is stored
     * under — and the two are kept apart from the sidebar's chapter subject because a menu knows which
     * kind it is and must not have to guess from which field happens to be set.
     */
    private String canvasMenuQuest;
    private String canvasMenuFrom;
    private String canvasMenuTo;

    /** Whether the press being released was a right-press on the canvas, for the click-vs-drag split. */
    private boolean canvasRightPressed;

    /**
     * The curve being bent by hand: which line, whether the drag has started, and the bow it previews.
     *
     * <p>The preview is a field rather than a file write because a drag is one edit: the drawing reads it
     * so the curve follows the hand, and the release is the one op that makes it real.
     */
    private String bendDragFrom;
    private String bendDragTo;
    private boolean bendDragLive;

    /** The line whose handles were last revealed, so a hand reaching for a control point cannot blink. */
    private String[] revealedEdge;
    private Double bendPreview;

    /**
     * How far the pointer was from the handle's own value when it was grabbed.
     *
     * <p>The whole of the "it jumps when I start dragging" fix: the drag is applied as a delta from the
     * grab, so the first live pixel moves the line by one pixel rather than snapping the value to wherever
     * the pointer happened to be pressed.
     */
    private double bendDragGrab;
    private double anchorDragGrab;

    /** Where inside a control point's own value the pointer grabbed it, in the chord's frame. */
    private double handleGrabAlong;
    private double handleGrabAcross;

    /** Which handle of that line is being dragged: "from", "to" or "bend". */
    private String bendDragKind;
    /** The angle an end handle is previewing, or null. */
    private Double anchorPreview;

    /** A split control point being dragged, as `[along, across]`. */
    private java.util.List<Double> handlePreview;

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

    /** Read at the press like the others: Alt+click straightens a node, Alt+drag places it freely. */
    private boolean pressedAlt;

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
     *
     * <p>What the list must <b>not</b> hold is the book's own controls, and it did. The modal branches
     * of {@code init} build the sidebar rows, the header and the view cluster while the overlay is
     * already open -- the book stays drawn behind the card -- and {@link #control} records everything
     * built in that state, so all of them were repainted over the card as well. {@link
     * #beginModalControls} is the boundary that drops them: it is the same line {@code bookButtonCount}
     * draws for {@code setBookControlsActive}, which is the point -- the book is one range whether the
     * question is "make it inert" or "keep it out of this list".
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
        // The author's pair is built only for an operator, and the count has to stop where the controls
        // that are actually drawn begin. `mayEdit` rather than `mayEditNow`: the pair exists as soon as
        // the permission does, whether or not its gear has been pressed.
        return geometry().headerRightLimit(mayEdit());
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
        BookGeometry.Rect rect = geometry().sidebarViewport(mayEditNow());
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
     * <h2>Two lists, and where each comes from</h2>
     *
     * <p>The headings come from {@link ClientQuestCache#groups()}, which the server sends explicitly —
     * so a group with no chapters is still a heading, and the rows say so. The chapters come from
     * {@link ClientQuestCache#chapters()} when the server sent that list, which every server since the
     * tree's version 3 does: it carries every chapter, <b>including one with no quests</b>, which is
     * what makes a newly created chapter visible instead of invisible until somebody writes a quest
     * into it.
     *
     * <p>When that list is empty — an older server — the chapters are derived from the quest entries
     * exactly as they always were, deduped by chapter id while keeping the order of first sighting. So
     * absence is a fallback rather than a special case, and the two sources are merged rather than
     * switched between: a quest naming a chapter the list somehow missed still produces a row, because
     * losing the row would lose the quests under it.
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
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            chapters.putIfAbsent(chapter.id(), new SidebarLayout.ChapterRow(
                    chapter.id(), chapter.title(), chapter.groupId()));
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            chapters.putIfAbsent(entry.chapterId(), new SidebarLayout.ChapterRow(
                    entry.chapterId(), entry.chapterTitle(), entry.chapterGroupId()));
        }

        SidebarLayout layout = SidebarLayout.of(groups, List.copyOf(chapters.values()));
        // The player's own expansions, over the authored defaults: this rebuild is what used to throw
        // them away. And the memory is pruned to the groups that still exist here, so a renamed or
        // deleted group's key does not accumulate for the life of the session.
        layout.applyExpansion(sidebarExpansion);
        sidebarExpansion.keySet().retainAll(layout.groupKeys());
        return layout;
    }

    /**
     * One sidebar row's icon: the stack to draw, and the id it was resolved from.
     *
     * <p>The pair, for the same reason {@link ClientQuestCache.Entry} keeps it: a stack that failed to
     * resolve with an id behind it is a <b>missing item</b>, which the row can say on hover, while an
     * empty id is simply no icon, which it cannot. One is a broken pack worth chasing and the other is
     * a chapter that never declared one.
     */
    private record SidebarIcon(ItemStack stack, String id) {
    }

    /**
     * The icon each sidebar row carries, keyed by the row's own key.
     *
     * <h2>Why a map beside the layout rather than a field on its rows</h2>
     *
     * <p>Because {@link SidebarLayout} is deliberately game-free -- it holds strings and numbers, and
     * its tests build rows without a client -- and an {@code ItemStack} on a row would end that. The key
     * is already the join between a row and everything the drawing needs, so the icons ride the same
     * join as a second map, resolved here where the cache and the registry are both in reach.
     *
     * <p>A group with no authored icon borrows the first chapter under it: the chapters are what the
     * group is made of, and a heading that shows something is easier to find than one that shows
     * nothing. "First" is the entries' declaration order, which is the order the sidebar draws them in.
     */
    private static Map<String, SidebarIcon> sidebarIcons() {
        Map<String, SidebarIcon> icons = new LinkedHashMap<>();
        Map<String, SidebarIcon> firstChapter = new LinkedHashMap<>();
        // The explicit chapter list first, when the server sent one: it has an icon for every chapter,
        // including a chapter with no quests -- which is the one row that cannot borrow one from a quest.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            SidebarIcon icon = new SidebarIcon(chapter.icon(), chapter.iconId());
            if (!chapter.groupId().isEmpty()) {
                firstChapter.putIfAbsent(chapter.groupId(), icon);
            }
            icons.putIfAbsent(SidebarLayout.chapterKey(chapter.id()), icon);
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            SidebarIcon icon = new SidebarIcon(entry.chapterIcon(), entry.chapterIconId());
            if (!entry.chapterGroupId().isEmpty()) {
                firstChapter.putIfAbsent(entry.chapterGroupId(), icon);
            }
            icons.putIfAbsent(SidebarLayout.chapterKey(entry.chapterId()), icon);
        }
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            SidebarIcon authored = new SidebarIcon(group.icon(), group.iconId());
            SidebarIcon shown = authored.id().isEmpty()
                    ? firstChapter.getOrDefault(group.id(), authored) : authored;
            icons.put(SidebarLayout.groupKey(group.id()), shown);
        }
        return icons;
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
                // Remembered, because the next tree -- which any structural edit produces -- rebuilds
                // this outline, and a group that shut itself after every drop is the report this answers.
                sidebarExpansion.put(key, sidebar().isExpanded(key));
                rebuildWidgets();
            }
            return;
        }

        selectChapter(SidebarLayout.idOf(key));
        rebuildWidgets();
    }

    /**
     * Switches to a chapter, closing the selection with it.
     *
     * <p>The selection belongs to the chapter being left -- the primary the panel edits <i>and</i> the
     * multi-selection the canvas gestures act on, which is the half the first version of this forgot.
     * Leaving it open would show a quest that is not on screen, with a Submit button, for a chapter you
     * have walked away from; and the set was worse, because Delete, Ctrl+D, the drag and Copy read
     * {@link #selection()} rather than the canvas, so they acted on quests in the chapter just left.
     *
     * <p>Three paths change chapters -- the sidebar, a row's menu and the naming overlay -- and all
     * three ask here, so the rule cannot drift between them.
     */
    private static void selectChapter(String id) {
        selectedChapter = id;
        selectedQuest = null;
        multiSelection.clear();
        centred = false;
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
        // The explicit list first, then the quests: the same merge, in the same order, as the sidebar's
        // own build. An empty chapter has to be selectable here too, or the book would draw a row for it
        // and then refuse to open on it -- the selection falls back to the first chapter that a *quest*
        // names.
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            chapters.putIfAbsent(chapter.id(), chapter.title());
        }
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

    private List<ClientQuestCache.Entry> questsIn(String chapterId) {
        // An author sees every quest, hidden flags or not. The flags are exactly the ones an author
        // sets and then has to keep working on -- a node that vanished from the canvas the moment its
        // `hideUntilDependenciesVisible` was set could not be placed, wired or clicked, which makes the
        // feature unusable by the person who asked for it. So developer mode shows them all, and
        // `drawNode` marks the ones the reader would not see; the reader's own book hides them, which
        // is the whole point of the flags.
        boolean authoring = mayEditNow();
        return ClientQuestCache.entries().stream()
                .filter(entry -> entry.chapterId().equals(chapterId))
                // Hidden quests are filtered out rather than withheld: every flag travels with the tree,
                // so a quest that becomes visible because it was completed -- or because a prerequisite
                // was -- needs no second sync to appear. The rules themselves are `QuestVisibility`'s,
                // where a test can hold them; this only asks.
                .filter(entry -> authoring || questVisible(entry.id()))
                .toList();
    }

    /**
     * Whether a quest is drawn at all: {@code QuestVisibility}'s rules, read off the client's cache.
     *
     * <p>The three functions the rules take are the client's three answers: the team's state for a
     * quest, whether its prerequisite rule is met (through {@code DependencyProgress}, so
     * {@code minRequired} and the started-based modes are the same here as everywhere else), and how
     * many of its tasks have any progress.
     */
    private static boolean questVisible(String id) {
        return dev.ellipog.tasked.client.dev.QuestVisibility.visible(id, VISIBILITY_LOOKUP,
                ClientQuestCache::stateOf, QuestBookScreen::prerequisiteRuleMet,
                QuestBookScreen::tasksWithProgress);
    }

    /** The cache's answer to the visibility rules' questions, for any quest id -- other chapters too. */
    private static final dev.ellipog.tasked.client.dev.QuestVisibility.Lookup VISIBILITY_LOOKUP =
            new dev.ellipog.tasked.client.dev.QuestVisibility.Lookup() {
                @Override
                public boolean invisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.invisible();
                }

                @Override
                public int invisibleUntilTasks(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? 0 : entry.invisibleUntilTasks();
                }

                @Override
                public boolean hideUntilDependenciesComplete(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesComplete();
                }

                @Override
                public boolean hideUntilDependenciesVisible(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry != null && entry.hideUntilDependenciesVisible();
                }

                @Override
                public List<String> dependencies(String id) {
                    ClientQuestCache.Entry entry = cacheEntryFor(id);
                    return entry == null ? List.of() : dependenciesOf(entry);
                }
            };

    /** Whether a quest's prerequisite rule is satisfied. An unknown quest has none, so it is met. */
    private static boolean prerequisiteRuleMet(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        return entry == null || dependencyProgressOf(entry).met(ClientQuestCache::stateOf);
    }

    /** How many of a quest's tasks have any progress: what `invisibleUntilTasks` counts. */
    private static int tasksWithProgress(String id) {
        ClientQuestCache.Entry entry = cacheEntryFor(id);
        if (entry == null) {
            return 0;
        }
        int touched = 0;
        for (int i = 0; i < entry.tasks().size(); i++) {
            if (ClientQuestCache.taskProgressOf(id, i) > 0) {
                touched++;
            }
        }
        return touched;
    }

    private ClientQuestCache.Entry entryFor(String questId) {
        return cacheEntryFor(questId);
    }

    /** The same lookup, static: the visibility rules are static and have to reach the cache too. */
    private static ClientQuestCache.Entry cacheEntryFor(String questId) {
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
     * A node's drawn size: the quest's own size, times the zoom.
     *
     * <p>This used to clamp the size to 26..48, which made the file's 16..512 range a field that did
     * nothing above 48 — a 96-pixel node drew at 48 and the author had no way to see that their number
     * was being ignored. The clamp is gone: the number in the file is the number on screen. The floor of
     * twelve is not a clamp on the size but the smallest a node can be drawn and still be a target, and
     * it is reached by zooming out, not by a file.
     */
    private int nodeSize(ClientQuestCache.Entry entry) {
        int size = fieldDraft.number(entry.chapterId(), entry.id(), "size", entry.size());
        return Math.max(12, Math.round(size * viewport().scale()));
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

    /**
     * A click that opens a card, without discarding the set the node is already part of.
     *
     * <p>A plain click normally means "this node and nothing else" -- clearing the multi-selection is
     * what says so. The exception is a click on a node that is <b>already selected</b>: the author has
     * collected a set with ctrl-click or the marquee, is clicking one of its members to edit it, and
     * clearing would throw away the very set they are about to say "Add selected" over. So the clicked
     * node becomes the primary and the rest of the set stays.
     *
     * <p>The old behaviour is kept for everything else, including a second click on a lone primary,
     * which still deselects: a set of one is not a set, and "click it again to let go" is a gesture
     * people already have.
     */
    private static void selectForCard(String id) {
        if (multiSelection.contains(id)) {
            multiSelection.remove(id);
            selectedQuest = id;
            return;
        }
        if (id.equals(selectedQuest)) {
            if (multiSelection.isEmpty()) {
                selectedQuest = null;
            }
            return;
        }
        selectedQuest = id;
        multiSelection.clear();
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
     * Drops selected ids the tree no longer holds.
     *
     * <p>The selection is static, and a tree arrives after every edit -- including the edit that deletes
     * a selected quest, an undo of its creation, or a file hand-edited and reloaded. An id the cache no
     * longer knows would otherwise stay in the set and be acted on by the next gesture: the same failure
     * as a selection carried across a chapter switch, arriving by a different road. So the revision that
     * expires the editor's remembered positions expires the selection too -- once per tree, by a guard
     * that lives with the selection it protects, and nothing that still exists is touched.
     */
    private static void pruneSelection(long revision) {
        if (revision == selectionRevision) {
            return;
        }
        selectionRevision = revision;
        Set<String> live = new java.util.HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            live.add(entry.id());
        }
        multiSelection.removeIf(id -> !live.contains(id));
        if (selectedQuest != null && !live.contains(selectedQuest)) {
            selectedQuest = null;
        }
    }

    /**
     * Whether this player may edit at all: edit mode on, and the permission the server will check for itself.
     *
     * <p>This used to also open the chapter's files on the *client*, which went with the client's write path.
     * The files belong to the server now; a client on a dedicated server has none of its own to open, and the
     * gate that matters is the one in the payload handler. What is left here is the courtesy — not offering a
     * control whose only answer would be a refusal.
     *
     * <p>Two ops-2 authors on one server are therefore a supported state, and the optimistic drafts assume
     * one: a structural edit by the other author moves the indices a pending value names. Locally that
     * shift is forgotten at the gesture ({@code FieldDraft.forgetList}); from the other author it is
     * caught only when the copy comes back shorter than the draft's index (the guard in
     * {@code reconcile}), and a same-length reorder is left to the {@code STALE_MILLIS} backstop.
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
        // A zoom is the hand taking the camera: a glide still in flight must not fight it.
        glideQuest = null;
        viewport().zoomAt(mouseX, mouseY, factor);
    }

    /** Zooms about the view port's centre, for the buttons, which have no pointer position. */
    private void zoomCentre(float factor) {
        glideQuest = null;
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
            // Recorded for the redraw pass over the card -- but only the controls built after
            // `beginModalControls` survive there. That boundary is what keeps the book's own rows,
            // header and view cluster -- built above it, while the overlay is already open -- from
            // being repainted on top of the card they belong behind. See `modalRedraws`.
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
    private void buildPartyWidgets() {
        partyLeftView.clear();
        partyRightView.clear();
        partyLeftLines = new ArrayList<>();
        partyRightLines = new ArrayList<>();
        partyNameField = null;
        partyCreateField = null;
        partySearchField = null;
        partyRemoveButtons.clear();
        partyTransferButtons.clear();
        partyControls.clear();

        PartyRoster roster = partyRoster();
        PartySnapshot snapshot = ClientPartyCache.snapshot();

        if (!partiesAvailable()) {
            // Singleplayer with LAN closed. The panel stays reachable and says why, rather than a
            // button greyed out with a tooltip: a disabled control explains nothing about the one
            // action that changes the state, and the notice's second line is that action.
            buildPartyNotice();
            return;
        }
        if (partyPhase != PartyPhase.NONE) {
            buildPartyPhase(roster);
            return;
        }
        if (!roster.isReal()) {
            buildPartySolo(roster, snapshot);
            return;
        }
        buildPartyActive(roster, snapshot);
    }

    /**
     * The two columns' viewports of a card's body, left then right.
     *
     * <p>One function rather than two rectangles computed at each use: the split is
     * {@code PartyPanelLayout}'s share, and the two widths must add up to the body — a caller deriving
     * "the right column is what is left" a second time is the arithmetic that agrees until the gap
     * changes.
     */
    private Viewport partyColumn(boolean left) {
        ScrollView view = left ? partyLeftView : partyRightView;
        if (partyCard == null) {
            return view.viewport().bounds(0, 0, 0, 0);
        }
        BookGeometry.Rect body = BookGeometry.modalBody(partyCard);
        int wide = PartyPanelLayout.leftWidth(body.width());
        int x = left ? body.x() : body.x() + wide + PartyPanelLayout.COLUMN_GAP;
        int width = left ? wide : PartyPanelLayout.rightWidth(body.width());
        return view.viewport().bounds(x, body.y(), width, body.height());
    }

    /**
     * The scroll view a pointer is over: the left column left of the split, the right column after.
     *
     * <p>The two columns scroll independently because they are different lists; a wheel aimed at the
     * roster must not move the settings out from under it. A pointer outside the card still lands on
     * one of them by this rule, which is the same thing the old single view did (clamped).
     */
    private ScrollView partyScrollAt(double mouseX) {
        BookGeometry.Rect body = partyCard == null ? null : BookGeometry.modalBody(partyCard);
        if (body == null) {
            return partyLeftView;
        }
        int split = body.x() + PartyPanelLayout.leftWidth(body.width()) + PartyPanelLayout.COLUMN_GAP / 2;
        return mouseX < split ? partyLeftView : partyRightView;
    }

    /**
     * The notice card: a small content-sized panel with one way out.
     *
     * <h2>Wrapped at the width the card will really have</h2>
     *
     * <p>The card is clamped to the window, so wrapping at the preferred 320 would under-count the
     * lines in a narrow window and the sentences would be cut again. The width is asked of
     * {@code modal()} -- which does not depend on the content -- and the same number sizes the card,
     * wraps the text and builds the rows, so the three cannot disagree.
     */
    private void buildPartyNotice() {
        int cardWidth = Math.min(BookGeometry.PARTY_MODAL_WIDTH, geometry().modal().width());
        int textWidth = Math.max(0, cardWidth - BookGeometry.MODAL_INSET * 2);
        PartyPanelLayout.Face face = PartyPanelLayout.notice(
                partyText("tasked.screen.party.unavailable"),
                partyText("tasked.screen.party.unavailable.why"),
                partyText("tasked.screen.party.unavailable.how"),
                textWidth, TEXT_MEASURE);
        partyCard = geometry().modalFramed(faceHeight(face, cardWidth), cardWidth);
        partyLeftFace = face;
        partyLeftLines = face.lines();
        partyLeftLayout = face.build(partyBodyWidth(), TEXT_MEASURE);
        partyLeftView.apply(partyLeftLayout, partyBodyWidth());
        placePartyControls(partyLeftFace);
        singleFooter("tasked.screen.party.back", this::closeOverlay);
    }

    /**
     * A card for a question the panel asked: the transfer confirmation and the successor picker.
     *
     * <h2>Why these are separate cards rather than rows in the party</h2>
     *
     * <p>Because both interrupt: neither is information about the party, they are questions about what
     * to do with it, and one of them — the successor picker — is reached by pressing Leave, so
     * answering "no" has to put the player back where they were rather than into some changed panel.
     * A phase with its own card is that, and it is also what keeps the main panel's controls from
     * staying live under a question.
     */
    private void buildPartyPhase(PartyRoster roster) {
        List<PartyPanelLayout.Line> lines = new ArrayList<>();
        List<PartyPanelLayout.Control> actions = new ArrayList<>();

        if (partyPhase == PartyPhase.CONFIRM_TRANSFER) {
            String target = nameOfMember(roster, partyPhaseTarget);
            lines.add(PartyPanelLayout.Line.header("confirm:title",
                    Component.translatable("tasked.screen.party.confirm.transfer.title").getString()));
            lines.add(PartyPanelLayout.Line.plain("confirm:body",
                    Component.translatable("tasked.screen.party.confirm.transfer.body", target)
                            .getString()));
            actions.add(PartyPanelLayout.Control.primary("phase:confirm",
                    "/tasked party transfer " + target));
            actions.add(PartyPanelLayout.Control.small("phase:cancel", null));
        }
        else {
            lines.add(PartyPanelLayout.Line.header("confirm:title",
                    Component.translatable("tasked.screen.party.confirm.successor.title").getString()));
            lines.add(PartyPanelLayout.Line.plain("confirm:body",
                    Component.translatable("tasked.screen.party.confirm.successor.body").getString()));
            for (PartyRoster.Member member : roster.members()) {
                if (member.self()) {
                    continue;
                }
                lines.add(PartyPanelLayout.Line.controls("succeed:" + member.id(), member.name(),
                        PartyPanelLayout.Control.primary("succeed-go:" + member.id(),
                                "/tasked party handover " + member.name())));
            }
            actions.add(PartyPanelLayout.Control.small("phase:disband", "/tasked party disband"));
            actions.add(PartyPanelLayout.Control.small("phase:cancel", null));
        }
        lines.add(PartyPanelLayout.Line.controls("confirm:actions", "",
                actions.toArray(new PartyPanelLayout.Control[0])));

        PartyPanelLayout.Face face = PartyPanelLayout.face(lines);
        partyCard = geometry().modalFramed(faceHeight(face, BookGeometry.PARTY_MODAL_WIDTH),
                BookGeometry.PARTY_MODAL_WIDTH);
        partyLeftFace = face;
        partyLeftLines = face.lines();
        partyLeftLayout = face.build(partyBodyWidth(), TEXT_MEASURE);
        partyLeftView.apply(partyLeftLayout, partyBodyWidth());
        placePartyControls(partyLeftFace);
        singleFooter("tasked.screen.party.back", this::cancelPartyPhase);
    }

    /** The solo onboarding: start a party on the left, join one on the right. */
    private void buildPartySolo(PartyRoster roster, PartySnapshot snapshot) {
        if (partyCreateName.isEmpty()) {
            partyCreateName = defaultPartyName();
        }
        partyLeftFace = PartyPanelLayout.soloLeft(roster, snapshot, selfName(), partyCreateName);
        partyRightFace = PartyPanelLayout.soloRight(snapshot);
        buildTwoColumns();
        buildPartyFooter(Footer.doneOnly(), roster);

        partyCreateField = new ArmatureTextField(0, 0, 0, 0, partyCreateName);
        partyCreateField.onSubmit(text -> {
            partyCreateName = text;
            runPartyCommand("/tasked party create " + text.trim());
        });
        modalRedraws.add(partyCreateField::render);
        addRenderableWidget(partyCreateField);
        if (partyCreateFocused) {
            setFocused(partyCreateField);
        }
    }

    /** The active party: the roster on the left, its management on the right. */
    private void buildPartyActive(PartyRoster roster, PartySnapshot snapshot) {
        partyLeftFace = PartyPanelLayout.activeLeft(roster, snapshot);
        partyRightFace = PartyPanelLayout.activeRight(roster, snapshot, selfName(), partySearch);
        buildTwoColumns();
        buildPartyFooter(Footer.of(roster.canDisband(), roster.canLeave() && roster.memberCount() > 1),
                roster);

        // The roster rows are not `Line`s: they are `PartyRoster`'s own composition, nested into the
        // left column's stack in `activeLeft`. Their buttons are registered here against that stack.
        for (PartyRoster.Member member : roster.members()) {
            if (member.canRemove()) {
                ArmatureButton remove = control(0, 0, 0, 0,
                        Component.translatable("tasked.screen.party.remove"),
                        () -> runPartyCommand("/tasked party kick " + member.name()));
                if (remove != null) {
                    remove.tooltip(List.of(
                            Component.literal("Remove " + member.name() + " from the party"),
                            Component.literal("They keep the progress they earned here")));
                    partyRemoveButtons.put(member.id(), remove);
                }
            }
            if (member.canTransfer()) {
                ArmatureButton transfer = control(0, 0, 0, 0,
                        Component.translatable("tasked.screen.party.transfer"),
                        () -> openPartyPhase(PartyPhase.CONFIRM_TRANSFER, member.id()));
                if (transfer != null) {
                    transfer.tooltip(List.of(
                            Component.literal("Make " + member.name() + " the owner"),
                            Component.literal("You stay in the party as a member")));
                    partyTransferButtons.put(member.id(), transfer);
                }
            }
        }

        // The party's name is the rename control: a flat, empty-labelled button laid over its row, so
        // the name itself is what a player clicks. It draws nothing (flat, no label) and the label
        // underneath is the name. A pencil was the first shape and it is gone at the owner's request --
        // and it was also the control the scroll view's key matching hid, so one control fewer to get
        // wrong. Non-owners get no target at all.
        if (roster.canRename() && !partyRenaming) {
            ArmatureButton rename = control(0, 0, 0, 0, Component.empty(), () -> {
                partyRenaming = true;
                rebuildWidgets();
            });
            if (rename != null) {
                rename.flat(true);
                rename.tooltip(List.of(Component.translatable("tasked.screen.party.rename")));
                partyControls.put("left:name:edit", rename);
            }
        }

        // The rename field replaces the name row's label while it is open, so there is one thing in
        // that row rather than a field over a name.
        if (partyRenaming) {
            partyNameField = new ArmatureTextField(0, 0, 0, 0, snapshot.teamName());
            partyNameField.onSubmit(text -> {
                partyRenaming = false;
                runPartyCommand("/tasked party rename " + text.trim());
            });
            modalRedraws.add(partyNameField::render);
            addRenderableWidget(partyNameField);
            setFocused(partyNameField);
        }

        // The search field, on the right column's own row.
        partySearchField = new ArmatureTextField(0, 0, 0, 0, partySearch);
        partySearchField.onSubmit(text -> {
            partySearch = text;
            partySearchFocused = false;
            rebuildWidgets();
        });
        modalRedraws.add(partySearchField::render);
        addRenderableWidget(partySearchField);
        if (partySearchFocused) {
            setFocused(partySearchField);
        }
    }

    /** Builds both columns from the faces and registers every line's controls. */
    private void buildTwoColumns() {
        partyCard = geometry().modal();
        partyLeftLayout = partyLeftFace.build(partyColumn(true).viewWidth(), TEXT_MEASURE);
        partyRightLayout = partyRightFace.build(partyColumn(false).viewWidth(), TEXT_MEASURE);
        partyLeftLines = partyLeftFace.lines();
        partyRightLines = partyRightFace.lines();
        partyLeftView.apply(partyLeftLayout, partyColumn(true).viewWidth());
        partyRightView.apply(partyRightLayout, partyColumn(false).viewWidth());
        placePartyControls(partyLeftFace);
        placePartyControls(partyRightFace);
    }

    /**
     * Registers a face's controls: a button per command, a switch per toggle, a screen action per
     * key.
     *
     * <p>Each control's rectangle is derived from its own row's slot — one derivation for all three
     * kinds, so a switch and a button in the same column cannot be placed by two arithmetics that
     * agree until one row grows a control.
     */
    private void placePartyControls(PartyPanelLayout.Face face) {
        for (PartyPanelLayout.Line line : face.lines()) {
            List<PartyPanelLayout.Control> controls = line.controls();
            for (int i = 0; i < controls.size(); i++) {
                PartyPanelLayout.Control spec = controls.get(i);
                if (spec.isToggle()) {
                    ArmatureSwitch toggle = new ArmatureSwitch(0, 0, toggleState(spec.key()));
                    toggle.onToggle(() -> runPartyCommand(toggleCommand(spec.key(), toggle.selected())));
                    modalRedraws.add(toggle::draw);
                    addRenderableWidget(toggle);
                    partyControls.put(spec.key(), toggle);
                }
                else if (spec.sendsCommand()) {
                    ArmatureButton button = control(0, 0, 0, 0,
                            buttonLabel(spec, line), () -> runPartyCommand(spec.command()));
                    if (button != null) {
                        button.accent(spec.accent());
                        button.tooltip(List.of(Component.literal(partyText(line.label())),
                                Component.literal(spec.command())));
                        partyControls.put(spec.key(), button);
                    }
                }
                else {
                    ArmatureButton cancel = control(0, 0, 0, 0,
                            Component.translatable("tasked.screen.party.cancel"),
                            this::cancelPartyPhase);
                    if (cancel != null) {
                        partyControls.put(spec.key(), cancel);
                    }
                }
            }
        }
    }

    /**
     * The footer, from which of the three controls exist.
     *
     * <h2>Why a record rather than two booleans</h2>
     *
     * <p>Because the three are a set the layout, the buttons and the preview all have to agree about —
     * a sole owner hides Leave and keeps Disband, a member sees Leave and Done, the notice sees Done
     * alone — and passing "hasDisband, hasLeave" around as two loose booleans is two chances to
     * transpose them.
     */
    private record Footer(boolean disband, boolean leave, boolean done) {

        static Footer of(boolean disband, boolean leave) {
            return new Footer(disband, leave, true);
        }

        static Footer doneOnly() {
            return new Footer(false, false, true);
        }
    }

    /** Places the footer's controls for a card, from the same map the preview reads. */
    private void buildPartyFooter(Footer wanted, PartyRoster roster) {
        Map<String, BookGeometry.Rect> footer = geometry().partyControls(
                partyCard, wanted.disband(), wanted.leave(), wanted.done());

        ArmatureButton leave = control(footer.get("leave"),
                Component.translatable("tasked.screen.party.leave"),
                () -> {
                    if (roster.canDisband() && roster.memberCount() > 1) {
                        // The owner may not simply leave: the party would be handed over by the
                        // fallback rule, to whoever the sort happens to name. The spec asks, and this
                        // is the ask.
                        openPartyPhase(PartyPhase.PICK_SUCCESSOR, null);
                    }
                    else {
                        runPartyCommand("/tasked party leave");
                    }
                });
        if (leave != null) {
            leave.tooltip(List.of(Component.literal("Leave the party"),
                    Component.literal("You keep the progress you earned here")));
        }

        ArmatureButton disband = control(footer.get("disband"),
                Component.translatable("tasked.screen.party.disband"),
                this::pressDisband);
        if (disband != null) {
            // Warning amber, not the blocked grey: the button is destructive, and a grey label was
            // read as "this one is unavailable to me". See ArmatureButton.Ink.DANGER.
            disband.ink(ArmatureButton.Ink.DANGER);
            disband.tooltip(List.of(Component.literal("Dissolve the party"),
                    Component.literal("Press again within three seconds to confirm")));
            disbandButton = disband;
        }

        ArmatureButton done = control(footer.get("done"),
                Component.translatable("tasked.screen.party.done"), this::closeOverlay);
        if (done != null) {
            done.accent(true);
        }
    }

    /** A one-button footer for the notice and the phases: Back, at Done's own place. */
    private void singleFooter(String labelKey, Runnable onPress) {
        Map<String, BookGeometry.Rect> footer = geometry().partyControls(partyCard, false, false, true);
        control(footer.get("done"), Component.translatable(labelKey), onPress);
    }

    /** The body width of a content-sized card, for a single-column face. */
    private int partyBodyWidth() {
        return Math.max(0, partyCard.width() - BookGeometry.MODAL_INSET * 2);
    }

    /** A content-sized face's height, to hand {@code modalFramed}. */
    private int faceHeight(PartyPanelLayout.Face face, int width) {
        return face.build(width - BookGeometry.MODAL_INSET * 2, TEXT_MEASURE).height();
    }

    /** Whether parties can be used at all: singleplayer with LAN closed is the one refusal. */
    private boolean partiesAvailable() {
        if (minecraft == null || !minecraft.hasSingleplayerServer()) {
            // A dedicated server, or a multiplayer one: parties are what the panel is for.
            return true;
        }
        var integrated = minecraft.getSingleplayerServer();
        return integrated == null || integrated.isPublished();
    }

    /**
     * A row's words: a translation key when one is given, the text itself otherwise.
     *
     * <p>The layout is game-free and cannot call {@code Component}, so it carries keys for the strings
     * a translator owns and literal text for the ones it composes from data — a member's name, a
     * count, an age. This is the one place the two are told apart, and the marker is the key prefix
     * every such key shares.
     */
    private static String partyText(String label) {
        return label != null && label.startsWith("tasked.") ? Component.translatable(label).getString() : label;
    }

    private String selfName() {
        return minecraft == null || minecraft.player == null ? "" : minecraft.player.getScoreboardName();
    }

    /** The name the create field starts with: the player's own, made safe for a command argument. */
    private String defaultPartyName() {
        String self = selfName();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < self.length(); i++) {
            char c = self.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' ? c : '_');
        }
        return (self.isEmpty() ? "My" : out.toString()) + " party";
    }

    private String nameOfMember(PartyRoster roster, UUID id) {
        for (PartyRoster.Member member : roster.members()) {
            if (member.id().equals(id)) {
                return member.name();
            }
        }
        return "?";
    }

    /** The label a line's button wears, from the control's key. */
    private Component buttonLabel(PartyPanelLayout.Control spec, PartyPanelLayout.Line line) {
        String key = spec.key();
        if (key.startsWith("accept:")) {
            return Component.translatable("tasked.screen.party.accept");
        }
        if (key.startsWith("decline:")) {
            return Component.translatable("tasked.screen.party.decline");
        }
        if (key.startsWith("invite-go:")) {
            return Component.translatable("tasked.screen.party.invite");
        }
        if (key.startsWith("join:")) {
            return Component.translatable("tasked.screen.party.join");
        }
        if (key.startsWith("cancel:")) {
            return Component.translatable("tasked.screen.party.cancel");
        }
        if (key.startsWith("succeed-go:")) {
            return Component.translatable("tasked.screen.party.choose");
        }
        if (key.startsWith("phase:confirm")) {
            return Component.translatable("tasked.screen.party.confirm");
        }
        if (key.startsWith("phase:disband")) {
            return Component.translatable("tasked.screen.party.disband");
        }
        if (key.startsWith("solo:create")) {
            return Component.translatable("tasked.screen.party.create");
        }
        return Component.literal(line.label().isEmpty() ? key : partyText(line.label()));
    }

    /** Which switch a toggle key is: the member-invite policy, or the public one. */
    private boolean toggleState(String key) {
        PartyRoster roster = partyRoster();
        return key.startsWith("right:open") ? roster.openJoin() : roster.membersCanInvite();
    }

    /** The command a flipped switch sends: the policy in force with that one field changed. */
    private String toggleCommand(String key, boolean value) {
        PartyRoster roster = partyRoster();
        boolean open = key.startsWith("right:open") ? value : roster.openJoin();
        boolean memberInvites = key.startsWith("right:open") ? roster.membersCanInvite() : value;
        // Two commands rather than one that takes both, because each switch means one change and a
        // command carrying the other field's current value would be a way for a stale read to undo a
        // change made between the draw and the press.
        return open != roster.openJoin()
                ? "/tasked party open " + (open ? "on" : "off")
                : "/tasked party member-invites " + (memberInvites ? "on" : "off");
    }

    private void openPartyPhase(PartyPhase phase, UUID target) {
        partyPhase = phase;
        partyPhaseTarget = target;
        rebuildWidgets();
    }

    private void cancelPartyPhase() {
        partyPhase = PartyPhase.NONE;
        partyPhaseTarget = null;
        rebuildWidgets();
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
            //
            // Without edit mode there is no copy to show and no request has been made: the tools are the
            // author's, and the tab says so instead of reporting the copy as still coming. See
            // `ChapterPanelLayout.notEditing` for why that state is a plain value row -- the widget loop
            // below then builds nothing at all, so no field or toggle can look live and do nothing.
            boolean editing = mayEditNow();
            JsonObject chapter = editing ? ClientChapterReplica.chapterTree(effectiveChapter())
                    : new JsonObject();
            // The pending chapter values, so a title or a rule shows the press's value before the
            // replica catches up -- every row below is built from this one tree.
            chapter = fieldDraft.overlaid(effectiveChapter(),
                    dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER, chapter);
            readChapterIdentity(chapter);
            ChapterPanelLayout.GroupInfo group = editing ? chapterGroupInfo(effectiveChapter()) : null;
            chapterRows = editing
                    ? ChapterPanelLayout.rows(chapter, group, questFolded,
                            ClientChapterReplica.refusal(effectiveChapter()))
                    : ChapterPanelLayout.notEditing();
            // Stacked, not side by side: the dock is a narrow column, and a 96-pixel control strip
            // leaves too little for labels like "Default Prerequisite Mode" -- see InspectLayout.Mode.
            chapterLayout = InspectLayout.build(chapterRows, toolsFrame.list().width(),
                    Measure.monospace(6, 9), InspectLayout.Mode.STACKED);
            toolsView.clear();
            toolsView.whole(true);
            toolsView.viewport().bounds(toolsFrame.list().x(), toolsFrame.list().y(),
                    toolsFrame.list().width(), toolsFrame.list().height());

            for (InspectRow row : chapterRows) {
                switch (row.kind()) {
                    case FIELD -> {
                        if (ChapterPanelLayout.isChoiceKey(row.key())) {
                            // A cycling row is drawn by ChapterPanel and pressed through the layout's own
                            // arrow boxes, so it holds no widget: the widget pass takes presses before the
                            // screen's own handling, and one over the arrows would swallow every step. The
                            // radius stepper's comment in this file is the same rule, one tab over.
                            continue;
                        }
                        if (ChapterPanelLayout.ICON.equals(row.key())) {
                            // The icon is picked, not typed: a button showing the item and its id, which
                            // opens the same picker the card's icon does. The id stays the value a commit
                            // writes; the button's label is only how it is read.
                            ArmatureButton pick = control(0, 0, 0, 0,
                                    Component.literal(row.value().isEmpty() ? "Pick an item\u2026" : row.value()),
                                    this::openChapterItemPicker);
                            pick.ink(ArmatureButton.Ink.BODY).icon(chapterIcon).alignLeft(true);
                            toolsView.put(row.key(), pick, InspectLayout::controlBand);
                            continue;
                        }
                        if ((ChapterPanelLayout.GROUP_PREFIX + ChapterPanelLayout.ICON).equals(row.key())) {
                            // The group's own icon, and it is deliberately its own button: the label says
                            // when the group has none of its own (and is borrowing the first chapter's),
                            // so setting one here is visibly a different thing from what the sidebar is
                            // drawing in the meantime. See `chapterGroupInfo`.
                            boolean own = group != null && !group.iconId().isEmpty();
                            ArmatureButton pick = control(0, 0, 0, 0,
                                    Component.literal(own ? row.value() : "First chapter's \u2014 pick to set"),
                                    this::openGroupItemPicker);
                            pick.textColour(own ? ArmatureTheme.body() : ArmatureTheme.faint())
                                    .icon(groupIconStack(group)).alignLeft(true);
                            toolsView.put(row.key(), pick, InspectLayout::controlBand);
                            continue;
                        }
                        ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, row.value());
                        String path = row.key();
                        field.onSubmit(text -> commitField(path, text, true));
                        field.colours(ArmatureTheme.title(), ArmatureTheme.recessed(),
                                ArmatureTheme.panelEdge());
                        toolsView.put(row.key(), field, InspectLayout::controlBand);
                        addRenderableWidget(field);
                    }
                    case TOGGLE -> {
                        // The button shows the state and the press changes it, the same rule every
                        // switch in this screen follows; the row's own label already carries the state
                        // too, which is what a row whose label *is* the state means. A group row's state
                        // lives in the tree, not in the chapter's own file.
                        boolean on = row.key().startsWith(ChapterPanelLayout.GROUP_PREFIX)
                                ? group != null && group.collapsedByDefault()
                                : flagOn(chapter, row.key());
                        ArmatureButton button = control(0, 0, 0, 0,
                                Component.literal(on ? "On" : "Off"),
                                () -> pressChapterToggle(row.key()));
                        button.ink(ArmatureButton.Ink.BODY);
                        toolsView.put(row.key(), button, InspectLayout::controlBand);
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
                toolsColoursOpen, themeTargetsChapter,
                // The chapter target writes a file, so it is offered only where there is a chapter and an
                // author: a reader's Theme tab stays about their own theme.
                mayEditNow() && effectiveChapter() != null);
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
                button.ink(ArmatureButton.Ink.BODY);
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
        ArmatureButton save = control(ToolsLayout.save(toolsFrame.actions()),
                Component.translatable("tasked.dev.save"), this::saveTheme);
        if (save != null) {
            // Saving writes a client theme file from the player's own theme, which is not what a chapter
            // palette is: while the chapter target is on, the button stands down rather than writing the
            // wrong thing.
            save.active = !themeTargetsChapter;
            if (themeTargetsChapter) {
                save.tooltip(Component.literal("Saving writes your own themes - switch the chapter"
                        + " palette off to save one"));
            }
        }

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
        else if (key.equals(ToolsLayout.CHAPTER_THEME)) {
            themeTargetsChapter = !themeTargetsChapter;
            // The selection names a token either way; the value under it changes source, and a band still
            // showing the last number would be showing the wrong palette's.
            toolsSelected = null;
            status(themeTargetsChapter
                    ? "Editing this chapter's palette \u2014 changes are saved to the chapter file"
                    : "Editing your own theme", false);
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
        if (chapter == null || target == null) {
            return null;
        }
        // The draft applied at the paths the ops write, and that is the whole of the card's optimism:
        // every read below goes through here, so a stepper shows what it asked for before the replica
        // catches up. It has to be an overlay rather than a check per read -- the draft is keyed by the
        // op's path (`tasks.0.count`) while the drawing reads a field's path relative to its entry
        // (`count`), and the first version's per-read check therefore never matched a task or reward.
        return fieldDraft.overlaid(chapter, target, ClientChapterReplica.quest(chapter, target));
    }

    /**
     * A quest's prerequisites, with a pending dependency edit winning over the tree's list.
     *
     * <p>The canvas reads the *tree*, not the replica, so a dependency edit needs this read to be
     * optimistic: the edge is drawn from the list this returns, which is why adding or removing a
     * prerequisite shows on the press rather than on the tree that follows it. Static, and keyed by the
     * entry's own chapter, because the canvas and the delete notes ask from static helpers.
     */
    private static List<String> dependenciesOf(ClientQuestCache.Entry entry) {
        return fieldDraft.strings(entry.chapterId(), entry.id(), "dependsOn", entry.dependencies());
    }

    /** A quest's title, with a committed rename winning over the tree the sidebar draws. */
    private static String titleOf(ClientQuestCache.Entry entry) {
        return fieldDraft.text(entry.chapterId(), entry.id(), "title", entry.title());
    }

    /** The same, for the subtitle. */
    private static String subtitleOf(ClientQuestCache.Entry entry) {
        return fieldDraft.text(entry.chapterId(), entry.id(), "subtitle", entry.subtitle());
    }

    /** The shape the canvas should draw, with a pending settings-page change winning over the tree. */
    private static QuestShape drawnShape(ClientQuestCache.Entry entry) {
        String name = fieldDraft.text(entry.chapterId(), entry.id(), "shape", "");
        if (!name.isEmpty()) {
            for (QuestShape shape : QuestShape.values()) {
                if (shape.name().equalsIgnoreCase(name)) {
                    return shape;
                }
            }
        }
        return entry.shape();
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
        if (path != null && path.startsWith(ChapterPanelLayout.GROUP_PREFIX)) {
            // A row from the Group section commits to the group's own file, whatever the panel's other
            // rows do -- the prefix is the whole of that distinction, so the two cannot be confused.
            commitGroupField(path.substring(ChapterPanelLayout.GROUP_PREFIX.length()), text);
            return;
        }
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
        // The quest case goes through the draft, so a committed value shows at once; the chapter case
        // is the settings draft's territory and sends directly.
        java.util.function.Consumer<JsonElement> commit = chapter
                ? value -> sendChapterField(path, value)
                : value -> sendField(target, path, value);
        if (typed.isEmpty()) {
            // Empty means absent: the field is removed rather than written as nothing -- the same
            // decision the model's SetField was given a null for.
            commit.accept(null);
            return;
        }
        if (path.equals("aliases")) {
            // One field for the list, because an alias is one word: commas between them, empties gone.
            List<String> aliases = Arrays.stream(typed.split(","))
                    .map(String::trim).filter(alias -> !alias.isEmpty()).toList();
            commit.accept(stringArray(aliases));
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(tree, path);
        InspectField.Result<?> result = field.parse(typed);
        if (!result.ok()) {
            status(result.error(), true);
            rebuildWidgets();
            return;
        }
        commit.accept(jsonOf(result.value()));
    }

    /** A chapter flag's press: the opposite of what the chapter tree says now. */
    private void pressChapterToggle(String path) {
        if (!mayEditNow()) {
            return;
        }
        if (path.startsWith(ChapterPanelLayout.GROUP_PREFIX)) {
            pressGroupToggle(path.substring(ChapterPanelLayout.GROUP_PREFIX.length()));
            return;
        }
        JsonObject chapter = fieldDraft.overlaid(effectiveChapter(),
                dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(effectiveChapter()));
        sendChapterField(path, new JsonPrimitive(!flagOn(chapter, path)));
        // The button's own label is built from this value, so the flip is visible on this press
        // rather than on the tree that follows it.
        rebuildWidgets();
    }

    /**
     * Steps one of the Chapter tab's cycling rows and sends the write it lands on.
     *
     * <p>The current value comes from the chapter's own tree -- the replica, not a local draft --
     * because the cycle has to wrap around the value the file actually holds, and the write goes
     * through {@link ChapterPanelLayout#choiceEdit}, which is where the two shapes of row (a plain
     * field, and an axis inside {@code dependencyStyle}) are told apart.
     */
    private void cycleChapterSetting(ChapterPanelLayout.Choice choice, int step) {
        if (!mayEditNow()) {
            return;
        }
        JsonObject chapter = fieldDraft.overlaid(effectiveChapter(),
                dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(effectiveChapter()));
        String next = ChapterPanelLayout.cycleChoice(choice,
                ChapterPanelLayout.choiceValue(chapter, choice), step);
        ChapterPanelLayout.Edit edit = ChapterPanelLayout.choiceEdit(chapter, choice, next);
        sendChapterField(edit.path(), edit.value());
        // The row is drawn from the rows built on this value, so it steps on this press.
        rebuildWidgets();
        status(choice.label() + ": " + ChapterPanelLayout.choiceLabel(choice, next), false);
    }

    /** One text field of the chapter's group: the group's own file, so its own op. */
    private void commitGroupField(String path, String text) {
        if (!mayEditNow()) {
            return;
        }
        String typed = text == null ? "" : text.trim();
        // Empty means absent, the same rule every field follows: the title a group is not allowed to
        // lose is refused by the validator on apply, not by a second rule here.
        send(new EditorOp.SetGroup(path, typed.isEmpty() ? null : new JsonPrimitive(typed)));
    }

    /** The group's collapsed flag's press: the opposite of what the tree says now. */
    private void pressGroupToggle(String path) {
        ChapterPanelLayout.GroupInfo group = chapterGroupInfo(effectiveChapter());
        if (!mayEditNow() || group == null) {
            return;
        }
        send(new EditorOp.SetGroup(path, new JsonPrimitive(!group.collapsedByDefault())));
    }

    /** The chapter's icon id as its file spells it, or "" when it declares none. */
    private static String chapterIconId(JsonObject chapter) {
        JsonElement item = ChapterPanelLayout.icon(chapter).get("item");
        return item != null && item.isJsonPrimitive() ? item.getAsString() : "";
    }

    /** The chapter's identity, read from its tree: the header's text, the icon, and the id behind it. */
    private void readChapterIdentity(JsonObject chapter) {
        chapterHeader = ChapterPanelLayout.header(chapter);
        chapterIcon = chapterIconStack(chapter);
        chapterIconId = chapterIconId(chapter);
    }

    /**
     * The selected chapter's group, as the Chapter tab's Group section needs it, or null when there is
     * none to show.
     *
     * <p>Read from the client's tree: the entries are what say which group a chapter hangs under -- the
     * heading list alone cannot -- and the headings are what say what that group is called, what icon it
     * has authored, and whether its chapters start collapsed. A chapter whose group the server did not
     * describe (an older server, or a tree with no entry for it) gets no section rather than one whose
     * edits could only be refused.
     */
    private static ChapterPanelLayout.GroupInfo chapterGroupInfo(String chapterId) {
        if (chapterId == null || chapterId.isEmpty()) {
            return null;
        }
        String groupId = "";
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                groupId = entry.chapterGroupId();
                break;
            }
        }
        if (groupId.isEmpty()) {
            return null;
        }
        for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
            if (group.id().equals(groupId)) {
                return new ChapterPanelLayout.GroupInfo(group.id(), group.title(), group.iconId(),
                        group.collapsedByDefault());
            }
        }
        return null;
    }

    /**
     * The group's own icon, resolved: empty when it has none of its own and is borrowing a chapter's.
     *
     * <p>The authored stack alone, never the fallback: the Group section's button says "first chapter's"
     * while this is empty, and setting one is only meaningful as a distinct act if the button was not
     * already showing the borrowed icon as though it were the group's.
     */
    private static ItemStack groupIconStack(ChapterPanelLayout.GroupInfo group) {
        if (group == null) {
            return ItemStack.EMPTY;
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(group.id())) {
                return entry.icon();
            }
        }
        return ItemStack.EMPTY;
    }

    /** The chapter's icon, resolved for drawing; empty when absent or unknown to this client. */
    private static ItemStack chapterIconStack(JsonObject chapter) {
        JsonObject icon = ChapterPanelLayout.icon(chapter);
        return ClientQuestCache.iconOf(chapterIconId(chapter), icon.get("components"));
    }

    /**
     * Opens the item picker on the chapter's icon, from the dock.
     *
     * <p>The picker's own overlay rather than the quest card's: the Chapter tab is not a quest, and the
     * card this is drawn over is the same card every other picker uses -- see {@link Overlay#PICKER}.
     * The current value is read from the replica's chapter tree, which is the same source the rows and
     * the header came from, so the picker's "current" line cannot name an item the panel is not showing.
     */
    private void openChapterItemPicker() {
        if (!mayEditNow()) {
            return;
        }
        JsonObject chapter = fieldDraft.overlaid(effectiveChapter(),
                dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                ClientChapterReplica.chapterTree(effectiveChapter()));
        readChapterIdentity(chapter);
        pickTarget = PickTarget.CHAPTER;
        pickIcon = chapterIcon;
        pickName = chapterHeader.title();
        pickingItemPath = ChapterPanelLayout.ICON;
        pickingItemCurrent = chapterIconId;
        // Clearing removes the whole `icon` object rather than its `item` member, so the path here is
        // the object's: `remove("icon.item")` would leave `"icon": {}` behind, and an item-less icon
        // object is a file the codec refuses. The clear row is drawn from this being non-null.
        pickingItemClearPath = "icon";
        openPickerOverlay();
    }

    /**
     * The same, on the chapter's <b>group's</b> icon.
     *
     * <p>The group's own icon and no fallback: the picker's current line is the authored item, and an
     * empty one is what makes the clear row absent and the card's title honest about a group that has
     * none of its own. A chapter whose group the tree does not describe has nothing to edit, and says so
     * rather than opening a picker that could only be refused.
     */
    private void openGroupItemPicker() {
        if (!mayEditNow()) {
            return;
        }
        ChapterPanelLayout.GroupInfo group = chapterGroupInfo(effectiveChapter());
        if (group == null) {
            status("This chapter's group is not editable from here", true);
            return;
        }
        pickTarget = PickTarget.GROUP;
        pickIcon = groupIconStack(group);
        pickName = group.title();
        pickingItemPath = ChapterPanelLayout.ICON;
        pickingItemCurrent = group.iconId();
        pickingItemClearPath = "icon";
        openPickerOverlay();
    }

    /** The shared last half of the two opens above: the picker's state, then the card. */
    private void openPickerOverlay() {
        pickerFromSettings = false;
        pickingEntryType = null;
        pickerEntries = catalogue();
        pickerInventory = carried();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        pickerSelected = -1;
        pickerScroll = 0;
        overlay = Overlay.PICKER;
        rebuildWidgets();
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
            sendField(target, "dependsOn", stringArray(remaining));
            return;
        }
        sendField(target, key, new JsonPrimitive(!flagOn(quest, key)));
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
            openTypePicker(key.equals(QuestPanelLayout.ADD_TASKS) ? "tasks" : "rewards");
        }
        else if (key.startsWith(QuestPanelLayout.TYPE_PREFIX)) {
            pressTypeRow(key.substring(QuestPanelLayout.TYPE_PREFIX.length()));
        }
    }

    /**
     * Opens the type picker on a member, at the top of its list.
     *
     * <p>One way in, so the two that reach it cannot disagree about what opening means -- and the scroll
     * reset is part of opening: a list has no memory between openings, and the last one's scroll would
     * drop the author into the middle of a list they have not seen yet.
     */
    private void openTypePicker(String member) {
        pickingEntryType = member;
        pickingConditionFor = null;
        overlayView.scrollTo(0);
        rebuildWidgets();
    }

    /**
     * The same picker, adding a condition to one entry's list instead of a task or a reward.
     *
     * <p>{@code pathPrefix} is the entry's own path ("tasks.3"): the picker lists condition types while
     * it is set, and the press rebuilds that entry's {@code conditions} array with the new one appended.
     */
    private void openConditionTypePicker(String pathPrefix) {
        pickingEntryType = null;
        pickingConditionFor = pathPrefix;
        overlayView.scrollTo(0);
        rebuildWidgets();
    }

    /**
     * One row of the condition picker: append that type's default to the entry's conditions list.
     *
     * <p>A rebuilt array through {@code SetField}, not an insert op: {@code EditorOp.Insert} addresses a
     * top-level list, and a nested one has no such op — the same move the dependency list and the chapter
     * order already make. One op, so one undo.
     */
    private void pressConditionTypeRow(String typeId) {
        String prefix = pickingConditionFor;
        if (prefix == null || editTarget() == null) {
            return;
        }
        com.google.gson.JsonObject fresh = net.minecraft.resources.ResourceLocation.tryParse(typeId) == null
                ? null
                : dev.ellipog.tasked.quest.condition.ConditionTypes
                        .defaultTree(net.minecraft.resources.ResourceLocation.tryParse(typeId)).orElse(null);
        if (fresh == null) {
            status("This build cannot add a " + typeId + " here", true);
            return;
        }
        JsonObject quest = replicaQuest();
        if (quest == null) {
            return;
        }
        com.google.gson.JsonArray conditions = new com.google.gson.JsonArray();
        JsonElement existing = QuestPanelLayout.get(quest, prefix + ".conditions");
        if (existing != null && existing.isJsonArray()) {
            for (JsonElement value : existing.getAsJsonArray()) {
                conditions.add(value.deepCopy());
            }
        }
        conditions.add(fresh);
        sendField(editTarget(), prefix + ".conditions", conditions);
        pickingConditionFor = null;
        status("Added a " + typeId + " condition", false);
    }

    /**
     * Removes one condition from its entry, by index: the list is rebuilt without it.
     *
     * <p>{@code path} is the condition's own path ("tasks.3.conditions.1"). Removing the last one sends
     * an empty array rather than nothing, so the field's presence is what the author chose rather than
     * something the write decided.
     */
    private void removeCondition(String path) {
        if (editTarget() == null) {
            return;
        }
        int dot = path.lastIndexOf('.');
        int index;
        try {
            index = Integer.parseInt(path.substring(dot + 1));
        }
        catch (NumberFormatException notAnIndex) {
            return;
        }
        String listPath = path.substring(0, dot);
        JsonObject quest = replicaQuest();
        if (quest == null) {
            return;
        }
        JsonElement existing = QuestPanelLayout.get(quest, listPath);
        if (existing == null || !existing.isJsonArray()) {
            return;
        }
        com.google.gson.JsonArray held = existing.getAsJsonArray();
        if (index < 0 || index >= held.size()) {
            // The array changed under the last frame. Rebuilding without an element would send an array
            // identical to the one stored -- a wasted save and a false "removed".
            return;
        }
        com.google.gson.JsonArray rebuilt = new com.google.gson.JsonArray();
        for (int i = 0; i < held.size(); i++) {
            if (i != index) {
                rebuilt.add(held.get(i).deepCopy());
            }
        }
        // The list's shape changes here, and a pending value indexed inside it would name a different
        // condition afterwards -- the same failure `pressEntry` forgets for, one nesting level down.
        // `rebuilt` was read through the overlay, so it already carries every other condition's pending
        // values; forgetting the leaves loses nothing the array draft that follows does not hold.
        fieldDraft.forgetList(effectiveChapter(), editTarget(), listPath);
        sendField(editTarget(), listPath, rebuilt);
        status("Removed the condition", false);
    }

    /**
     * One row of the type picker: add a fresh entry of that type, and close the picker.
     *
     * <p>The tree is the type's own default, encoded by the type's codec -- so what the picker adds is
     * exactly what the loader reads back, and an addon's type is addable the day it registers. A type
     * whose defaults cannot be encoded is refused out loud rather than inserted half-formed.
     */
    private void pressTypeRow(String typeId) {
        if (pickingConditionFor != null) {
            pressConditionTypeRow(typeId);
            return;
        }
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
        // The list's shape is about to change, so a pending value indexed inside it no longer names
        // the entry the author was editing -- see FieldDraft.forgetList for what that would commit.
        fieldDraft.forgetList(effectiveChapter(), editTarget(), member);
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
        pendingPick = new dev.ellipog.tasked.client.dev.DependencyPick(quest, effectiveChapter(),
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
        dev.ellipog.tasked.client.dev.DependencyPick pick = pendingPick;
        pendingPick = null;
        if (pick == null) {
            return;
        }
        // The three rules live in the pick itself, where a test can hold them: append to the end of the
        // list as it was armed, refuse a self-dependency, refuse one that is already there.
        dev.ellipog.tasked.client.dev.DependencyPick.Result result = pick.with(id);
        if (result.outcome() == dev.ellipog.tasked.client.dev.DependencyPick.Outcome.ITSELF) {
            status("A quest cannot depend on itself", true);
            returnToEditedQuest(pick);
            return;
        }
        if (result.outcome() == dev.ellipog.tasked.client.dev.DependencyPick.Outcome.ALREADY) {
            status(pick.quest() + " already depends on " + id, true);
            returnToEditedQuest(pick);
            return;
        }
        // Sent to the chapter that was being edited, not to the one on screen: the pick may have been
        // taken in another chapter or group entirely — and the draft goes under that chapter too, since
        // that is whose replica will answer for it.
        sendField(pick.chapter(), pick.quest(), "dependsOn", stringArray(result.dependsOn()));
        status(pick.quest() + " now depends on " + id, false);
        returnToEditedQuest(pick);
    }

    /**
     * Back to the quest the pick was armed from: its chapter, then its card.
     *
     * <p>Because a pick is a detour and not an exit. The author was editing a quest, left it to name a
     * prerequisite -- possibly in another chapter, which is why the pick carries its own chapter -- and
     * wants to be back where they were with the list they just changed in front of them, rather than on
     * the canvas reading a status line. Cancelling goes back too, unchanged, which is what makes Escape a
     * way out of a pick rather than out of the book.
     */
    private void returnToEditedQuest(dev.ellipog.tasked.client.dev.DependencyPick pick) {
        if (pick.chapter() != null && !pick.chapter().equals(selectedChapter)) {
            // The chapter the quest lives in, not the one on screen: the pick may have been taken
            // somewhere else entirely, and the card can only show a quest of the chapter it is drawn in.
            selectedChapter = pick.chapter();
        }
        selectedQuest = pick.quest();
        multiSelection.clear();
        openOverlay(pick.quest());
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
        sendField(target, "dependsOn", stringArray(dependencies));
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
        if (themeTargetsChapter) {
            writeThemeRadius(net.minecraft.util.Mth.clamp(editedRadius() + delta, Look.MIN_RADIUS,
                    Look.MAX_RADIUS));
            status("Chapter border radius " + editedRadius()
                    + (editedRadiusChosen() ? "  (theme's own: " + editedBaseRadius() + ")" : ""), false);
            rebuildWidgets();
            return;
        }
        ClientAppearance.LOOK.setRadius(net.minecraft.util.Mth.clamp(ClientAppearance.LOOK.radius() + delta,
                Look.MIN_RADIUS, Look.MAX_RADIUS));
        status("Border radius " + ClientAppearance.LOOK.radius()
                + (ClientAppearance.LOOK.radiusChosen() ? "  (theme's own: " + editedBaseRadius() + ")" : ""),
                false);
        rebuildWidgets();
    }

    /**
     * The palette the Theme tab is editing: the open chapter's composed one, or the player's own.
     *
     * <p>One expression, read by the panel's drawing and by every handler, so a swatch and the number it
     * writes can never come from different palettes.
     */
    private Theme editedTheme() {
        return themeTargetsChapter ? viewportTheme() : ClientAppearance.LOOK.main();
    }

    /** The radius in force for the panel's target. */
    private int editedRadius() {
        return themeTargetsChapter ? editedTheme().cornerRadius() : ClientAppearance.LOOK.radius();
    }

    /** Whether the target itself pins the radius, rather than inheriting the theme's. */
    private boolean editedRadiusChosen() {
        return themeTargetsChapter ? chapterThemePatchOf(effectiveChapter()).has("cornerRadius")
                : ClientAppearance.LOOK.radiusChosen();
    }

    /** The radius the theme underneath asks for: what a Revert goes back to. */
    private int editedBaseRadius() {
        if (!themeTargetsChapter) {
            Theme theme = Themes.any(ClientAppearance.LOOK.currentName());
            return theme == null ? 0 : theme.cornerRadius();
        }
        // The chapter's named theme without its patch: the radius the chapter's own override replaced.
        return ChapterTheme.compose(ClientQuestCache.chapterTheme(effectiveChapter()), null,
                ClientAppearance.LOOK.main()).cornerRadius();
    }

    /**
     * The open chapter's theme patch as the editor has it: the pending write first, then the file's own
     * copy, then what the tree last sent — the same three-layer read {@code dependencyLinesOf} makes, for
     * the same reason: every writer rebuilds the whole object, so a pending sibling edit has to be in the
     * base or the server's whole-object write discards it.
     */
    private JsonObject chapterThemePatchOf(String chapter) {
        if (chapter == null) {
            return new JsonObject();
        }
        JsonElement drafted = fieldDraft.value(chapter, dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                "themePatch");
        if (drafted != null && drafted.isJsonObject()) {
            return drafted.getAsJsonObject().deepCopy();
        }
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(chapter);
        if (copy != null && copy.revision() == ClientQuestCache.treeRevision()) {
            JsonElement stored = QuestPanelLayout.get(copy.chapterTree(), "themePatch");
            if (stored != null && stored.isJsonObject()) {
                return stored.getAsJsonObject().deepCopy();
            }
        }
        JsonObject sent = ClientQuestCache.chapterThemePatch(chapter);
        return sent == null ? new JsonObject() : sent.deepCopy();
    }

    /**
     * Writes one token into whichever palette the panel is editing, or removes it when {@code argb} is
     * null.
     *
     * <p>Two destinations behind one call, because the panel's handlers must not each remember which: the
     * player's theme lives in this client's appearance file, and a chapter's patch is a chapter field
     * written through the same op every other chapter edit uses — so Ctrl+Z takes a chapter colour back
     * like any other change.
     */
    private void writeThemeToken(String token, Integer argb) {
        if (!themeTargetsChapter) {
            if (argb == null) {
                ClientAppearance.LOOK.clearCustom(token);
            }
            else {
                ClientAppearance.LOOK.setCustom(token, argb);
            }
            return;
        }
        JsonObject patch = chapterThemePatchOf(effectiveChapter());
        JsonObject colours = patch.has("colours") && patch.get("colours").isJsonObject()
                ? patch.getAsJsonObject("colours") : new JsonObject();
        if (argb == null) {
            colours.remove(token);
        }
        else {
            colours.addProperty(token, String.format("#%08X", argb));
        }
        if (colours.isEmpty()) {
            patch.remove("colours");
        }
        else {
            patch.add("colours", colours);
        }
        sendChapterThemePatch(patch);
    }

    /** Writes (or clears) the corner radius on the panel's target. */
    private void writeThemeRadius(Integer radius) {
        if (!themeTargetsChapter) {
            if (radius == null) {
                ClientAppearance.LOOK.clearRadius();
            }
            else {
                ClientAppearance.LOOK.setRadius(radius);
            }
            return;
        }
        JsonObject patch = chapterThemePatchOf(effectiveChapter());
        if (radius == null) {
            patch.remove("cornerRadius");
        }
        else {
            patch.addProperty("cornerRadius", radius);
        }
        sendChapterThemePatch(patch);
    }

    /** Sends a chapter's whole patch object; an empty patch is removed rather than written. */
    private void sendChapterThemePatch(JsonObject patch) {
        sendChapterField("themePatch", patch.isEmpty() ? null : patch);
    }

    /** The selected colour's value, as the field should show it — from the palette being edited. */
    private String hexOf(String token) {
        return token == null ? "" : String.format("#%06X", editedTheme().colour(token) & 0xFFFFFF);
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
        Integer argb = HexColour.parse(text, editedTheme().colour(toolsSelected));
        if (argb == null) {
            status("\"" + text + "\" is not a hex colour - try #4A90D9", true);
            // The field goes back to the colour it belongs to, silently. Rebuilding the widgets here is what
            // it used to do, and a rebuild blurs the field being submitted, which submits again: the message
            // above and this line are the whole answer.
            refreshHexField();
            return;
        }
        writeThemeToken(toolsSelected, argb);
        status(labelOfToken(toolsSelected) + " set to " + String.format("#%08X", argb)
                + (themeTargetsChapter ? "  (this chapter)" : ""), false);
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
        int argb = editedTheme().colour(token);
        int shift = switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
        int value = net.minecraft.util.Mth.clamp(((argb >>> shift) & 0xFF) + step, 0, 255);
        writeThemeToken(token, (argb & ~(0xFF << shift)) | (value << shift));
        rebuildWidgets();
    }

    /**
     * Undoes the edits to what the panel is about: the selected colour, or -- with nothing selected --
     * the corner radius, which has no selection of its own now that its arrows live in its row.
     */
    private void revertSelected() {
        boolean chapter = themeTargetsChapter;
        if (toolsSelected == null) {
            writeThemeRadius(null);
            status(chapter ? "Chapter border radius back to the theme's own"
                    : "Border radius back to the theme's own", false);
            rebuildWidgets();
            return;
        }
        if (ToolsLayout.RADIUS.equals(toolsSelected)) {
            writeThemeRadius(null);
            status(chapter ? "Chapter border radius back to the theme's own"
                    : "Border radius back to the theme's own", false);
            rebuildWidgets();
            return;
        }
        writeThemeToken(toolsSelected, null);
        status("Reverted " + labelOfToken(toolsSelected) + (chapter ? " in this chapter" : ""), false);
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
        toast(message, error);
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
     * Disband, which takes two presses inside a three-second window.
     *
     * <p>The label is changed on the control rather than by rebuilding the panel, and that is not a
     * shortcut: a rebuild goes through {@code init}, which disarms — so arming and rebuilding cannot be
     * the same press. The window lives in {@link ArmedPress}, which is where its boundaries are
     * testable; this method only decides what to do about the answer.
     */
    private void pressDisband() {
        if (disbandPress.press(net.minecraft.Util.getMillis())) {
            runPartyCommand("/tasked party disband");
            return;
        }
        if (disbandButton != null) {
            disbandButton.setMessage(Component.translatable("tasked.screen.party.confirm_again"));
            disbandButton.tooltip(List.of(Component.literal("Press again within three seconds"),
                    Component.literal("Everybody keeps the progress they earned")));
        }
    }

    /** Undoes an armed Disband. A rebuild does it, and so does the window lapsing. */
    private void disarmDisband() {
        disbandPress.disarm();
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

    /**
     * Places one of the panel's widgets at a content slot, with the kit's own cull rule.
     *
     * <p>The scroll view is not asked to do this because it matches widgets by row key and a row may
     * hold two controls -- see {@link #partyControls}. The rule it would have applied is kept here
     * instead: map through the viewport, hide a widget the viewport cannot show, and the same
     * half-open test it uses (a row whose bottom edge is exactly at the viewport's top is off screen).
     *
     * <p>A null slot hides the widget rather than leaving it where it was: an unplaced control is one
     * whose row is not in this face at all, and leaving it on screen would be the previous panel's
     * control drawn over this one.
     */
    private void placePartyWidget(net.minecraft.client.gui.components.AbstractWidget widget,
                                  Slot slot, Viewport body) {
        if (widget == null) {
            return;
        }
        if (slot == null) {
            widget.visible = false;
            return;
        }
        Slot onScreen = screenSlot(body, slot);
        widget.setX(onScreen.x());
        widget.setY(onScreen.y());
        widget.setWidth(onScreen.width());
        widget.setHeight(onScreen.height());
        widget.visible = onScreen.y() < body.viewBottom() && onScreen.bottom() > body.originY();
    }

    /** Places a face's row controls, one per control, from the row's own strip. */
    private void placePartyLineControls(PartyPanelLayout.Face face, Layout layout, Viewport body) {
        if (face == null || layout == null) {
            return;
        }
        for (PartyPanelLayout.Line line : face.lines()) {
            if (!line.hasControls()) {
                continue;
            }
            Slot row = layout.slot(line.key());
            List<Slot> slots = row == null ? List.of() : PartyPanelLayout.controlSlots(line, row);
            for (int i = 0; i < line.controls().size(); i++) {
                placePartyWidget(partyControls.get(line.controls().get(i).key()),
                        i < slots.size() ? slots.get(i) : null, body);
            }
        }
    }

    /** Places a text field in this face's row for it, if the face has that row. */
    private void placePartyField(net.minecraft.client.gui.components.AbstractWidget field,
                                 PartyPanelLayout.Face face, Layout layout, String key, Viewport body) {
        if (field == null || face == null || layout == null) {
            return;
        }
        PartyPanelLayout.Line line = face.line(key);
        Slot row = layout.slot(key);
        placePartyWidget(field, line == null || row == null ? null
                : PartyPanelLayout.textSlot(line, row), body);
    }

    /**
     * Opens the party panel at the top of both columns, with no question left over.
     *
     * <p>The phases and the fields are reset here rather than in a rebuild: a panel opened afresh is a
     * new interaction, and a half-typed search from ten minutes ago would make the invite list look
     * like it was missing people. The scroll reset is documented at the {@code scrollTo} call below.
     */
    private void openPartyOverlay() {
        overlay = Overlay.PARTY;
        overlayQuest = null;
        partyPhase = PartyPhase.NONE;
        partyPhaseTarget = null;
        partyRenaming = false;
        partySearch = "";
        partySearchFocused = false;
        partyCreateFocused = false;
        // The name a player typed for a party they no longer have must not greet them as if it were
        // that party: clearing it lets the build re-derive the default from their own name, so the
        // field is a suggestion for a new party rather than a leftover. See the create heading.
        partyCreateName = "";
        // Opened at the top, and reset before the rebuild rather than after: the rebuild's own `apply`
        // clamps against the new content, and an offset left from a previous party would be clamped
        // into range rather than forgotten -- a panel that opened halfway down for no visible reason.
        partyLeftView.scrollTo(0);
        partyRightView.scrollTo(0);
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
        // The add buttons live with the list they add to, and are built wherever it is -- the book's own
        // branch and the three modal branches -- so a card in front of the book cannot leave them absent
        // when it closes. They draw only in edit mode; their rectangles are always reserved, which is
        // what keeps one window geometry for every player.
        buildSidebarToolbar();
        sidebarView.clear();

        sidebarViewport();
        int width = geometry().sidebarViewport().width();

        // Resolved once per rebuild, not per frame: the icons come from the cache, which only moves when
        // a tree arrives -- and a tree arriving rebuilds this list anyway.
        Map<String, SidebarIcon> icons = sidebarIcons();

        for (SidebarLayout.Row row : layout.rows()) {
            boolean heading = row.group();
            boolean isSelected = !heading && row.id().equals(effectiveChapter());

            ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                    () -> pressSidebarRow(row.key()));

            // The row's item, when there is one: a chapter's own icon, or a group's authored icon, or
            // the first chapter under a group that authored none. A missing item leaves the row without
            // a mark rather than with an empty box, and says so on hover -- the id is kept for exactly
            // that, the same "missing is not absent" reading the quest header draws in its corner.
            SidebarIcon icon = icons.get(row.key());
            if (icon != null && !icon.stack().isEmpty()) {
                button.icon(icon.stack());
            }
            else if (icon != null && !icon.id().isEmpty()) {
                button.tooltip(List.of(Component.literal("Missing item: " + icon.id())));
            }

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
        rewardsButton = null;
        // Cleared with them: the rows are drawn from these lists, so a rebuild that left them alone
        // would draw the previous panel's rows over the new one. The card and the layouts go with them,
        // because one pass records them together and a card left behind would draw a box for a panel
        // that no longer exists.
        partyLeftLines = new ArrayList<>();
        partyRightLines = new ArrayList<>();
        partyLeftFace = null;
        partyRightFace = null;
        partyCard = null;
        partyLeftLayout = null;
        partyRightLayout = null;
        // And the scroll views' widgets, for the reason the three above are cleared: they belong to
        // the panel being replaced. `buildPartyWidgets` clears them too, but only on the branch that
        // builds a panel -- a rebuild for a quest would otherwise leave the views holding controls
        // that are no longer on the screen.
        partyLeftView.clear();
        partyRightView.clear();
        // And the armed Disband, because a rebuild is a new panel: the row a player armed may not be
        // there any more, and a control that says "Confirm" for a press it no longer remembers is
        // worse than one that forgot.
        disbandButton = null;
        disbandPress.disarm();
        partyNameField = null;
        partyCreateField = null;
        partySearchField = null;
        partyControls.clear();
        // `partyRenaming` is deliberately NOT reset here, and that is a fix for a report: the name's
        // own press sets the flag and then rebuilds, so a reset in this path cancelled the edit on the
        // same click -- "edit party name doesn't do anything". A panel opened afresh still resets it,
        // in `openPartyOverlay`; a rebuild keeps whatever interaction was in progress.

        // No theme is applied here, and there used to be one call. A chapter's palette is now a scope
        // opened and closed within a single frame -- see `drawCanvas` and `renderWith` -- so there is
        // nothing to establish before the controls are built, and the label below reads from
        // `Appearance`, which no chapter can influence.
        //
        // That is a real simplification rather than a relocation. The sequence here used to matter: the
        // theme had to be applied before the controls were made, because each control reads its colours
        // when it is constructed. With the two palettes separated by region, a control reads the chrome
        // and a canvas reads the chapter, and neither has an order dependency on the other.

        // A recipe viewer that asked for the book opens it on its quest: the request is consumed here,
        // before the branches below build for whatever overlay it names, so the card's own build path
        // is the one that runs -- exactly as if the player had clicked the node. A request for a quest
        // the tree no longer holds -- a viewer's click that outlived a reload -- is dropped rather than
        // opening a card for nothing.
        QuestBookFocus.consume().ifPresent(questId -> {
            ClientQuestCache.Entry entry = cacheEntryFor(questId);
            if (entry != null) {
                selectChapter(entry.chapterId());
                selectedQuest = questId;
                centred = true;
                overlay = Overlay.QUEST;
                overlayQuest = questId;
                // A card opened from outside the book is a fresh session, not a continuation of one:
                // there is no "where I came from" for the arrow to return to.
                overlayHistory.clear();
            }
        });

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
            // Where the book's controls end and this modal's begin -- both numbers that track the
            // boundary. See `beginModalControls`.
            beginModalControls();

            buildOverlayWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.PARTY) {
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            beginModalControls();

            buildPartyWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.PICKER) {
            // The book's own chrome behind the card, exactly as the other two overlays build it -- the
            // card is drawn over a book that is still drawn, and a sidebar left unbuilt would be a hole
            // in the picture behind it. Then the picker's one field, and the book made inert.
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            beginModalControls();

            buildOverlayWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.CHOICE) {
            // The same chrome behind the card as every other overlay, and then the entries. The card is
            // a question a player answers, so nothing here is gated on editing: `mayEditNow` guards the
            // editor's cards, and this one is not the editor's.
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            beginModalControls();

            buildChoiceWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.REWARDS) {
            // The same again for the rewards panel, which is also a player's card rather than an
            // author's.
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            beginModalControls();

            buildRewardWidgets();
            setBookControlsActive(false);
            return;
        }

        if (overlay == Overlay.NAMING) {
            // The book behind the card, exactly as the other overlays build it, then the two fields.
            buildSidebarWidgets();
            buildHeaderChrome();
            buildViewCluster();
            beginModalControls();

            buildNamingWidgets();
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
                .ink(ArmatureButton.Ink.BODY);

        control(controls.get("zoomOut"), Component.literal("\u2212"), () -> zoomCentre(0.8F))
                .tooltip(List.of(Component.literal("Zoom out"),
                        Component.literal("Or scroll down over the canvas")))
                .ink(ArmatureButton.Ink.BODY);

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
                .ink(ArmatureButton.Ink.BODY);
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
            closeButton.ink(ArmatureButton.Ink.BODY);
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
            partyButton.ink(ArmatureButton.Ink.BODY);
        }

        // The rewards button, between Close and Party -- the spot `BookGeometry.partyButton` was anchored
        // for. Built for every player, like Party: claiming is a player's business rather than an author's,
        // so it is not gated the way Edit and its gear are. What changes with state is the tooltip, not the
        // label -- the same rule the party button's own comment states, and for the same reason.
        rewardsButton = control(controls.get("rewards"),
                Component.translatable("tasked.screen.rewards.button"), this::openRewardsOverlay);
        if (rewardsButton != null) {
            rewardsButton.ink(ArmatureButton.Ink.BODY);
            int waiting = claimableQuests().size();
            rewardsButton.tooltip(Component.translatable(waiting == 0
                    ? "tasked.screen.rewards.none" : "tasked.screen.rewards.waiting", waiting));
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
                editButton.ink(ArmatureButton.Ink.BODY)
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
                toolsButton.ink(ArmatureButton.Ink.BODY)
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
        // Leaving edit mode drops the chapter target with it: its switch is not drawn for a reader, and a
        // panel still pointed at a chapter would offer colours the server would refuse to write.
        if (!on) {
            themeTargetsChapter = false;
        }
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

    /**
     * Records where the book's controls end and the modal's begin.
     *
     * <h2>Why one call rather than two assignments</h2>
     *
     * <p>Because two fields track this boundary and a modal's {@code init} has to move both.
     * {@code bookButtonCount} is the range {@link #setBookControlsActive} makes inert; and
     * {@link #modalRedraws} is what the render pass repaints <i>above</i> the card. They disagreed:
     * {@link #control} appends to the redraw list for every control built while an overlay is open, and
     * the modal branches of {@code init} build the book's own sidebar rows, header controls and view
     * cluster first -- deliberately, because the book is drawn behind the card rather than replaced by
     * it -- so the redraw loop painted the sidebar tree, the zoom cluster and the header on top of the
     * card they belong behind. The fix is to clear the list at the same line the count is taken.
     *
     * <p>Everything a modal builds is built after this call -- {@link #buildOverlayWidgets},
     * {@link #buildPartyWidgets} and the text fields that register their own clipped redraws -- so
     * clearing here is exactly the book/modal split the redraw loop's comment already claimed, and it
     * is the same boundary {@code setBookControlsActive} uses: one line, one place to move it.
     */
    private void beginModalControls() {
        bookButtonCount = buttons.size();
        modalRedraws.clear();
    }

    private void buildOverlayWidgets() {
        // The picker's own overlay first: it is the one overlay that is not about a quest, so it must
        // not be asked for an entry -- the test below is what would otherwise close it on the frame it
        // opened, because a chapter icon pick has no quest behind it.
        if (overlay == Overlay.PICKER) {
            // The card's own state is re-read from the tree here, not only when the row was pressed.
            // `tick` rebuilds for this overlay when a replica arrives -- that is the point of the rebuild
            // -- so a header, an icon or a Current row left from the tree as it stood when the row was
            // pressed would be exactly the staleness the rebuild exists to end, and pressing a stale
            // Current row would write the old id back over the new one.
            if (pickTarget == PickTarget.CHAPTER) {
                JsonObject chapter = fieldDraft.overlaid(effectiveChapter(),
                        dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                        ClientChapterReplica.chapterTree(effectiveChapter()));
                readChapterIdentity(chapter);
                pickIcon = chapterIcon;
                pickName = chapterHeader.title();
                pickingItemCurrent = chapterIconId;
            }
            else if (pickTarget == PickTarget.GROUP) {
                ChapterPanelLayout.GroupInfo group = chapterGroupInfo(effectiveChapter());
                if (group != null) {
                    pickIcon = groupIconStack(group);
                    pickName = group.title();
                    pickingItemCurrent = group.iconId();
                }
            }
            buildPickerWidgets();
            // One control, because the card has no other: Escape and a press outside also leave, and a
            // picker whose only way out is a key nobody was told about reads as a trap. The reader's
            // Back rectangle is the one the card's other footers use, so the three cards line up.
            ArmatureButton back = control(geometry().overlayControls(false).get("back"),
                    Component.literal("Back"), this::closePickerOverlay);
            if (back != null) {
                back.ink(ArmatureButton.Ink.BODY)
                        .tooltip(Component.literal("Escape also closes this"));
            }
            setBookControlsActive(false);
            return;
        }

        ClientQuestCache.Entry entry = entryFor(overlayQuest);
        if (entry == null) {
            overlay = Overlay.NONE;
            rebuildWidgets();
            return;
        }

        // The back arrow, in the header's empty right-hand side and above the card like every modal
        // control. A prerequisite chain is four deep before anyone notices they cannot step back
        // without losing the thread, and the footer's Back leaves the whole card — so one press per
        // level, like a browser. Built for the reader and the editor alike: the editor's header marks
        // are drawn on top of this band, so the two must not share a corner.
        BookGeometry.Rect card = geometry().modal();
        ArmatureButton backArrow = control(card.right() - BookGeometry.MODAL_INSET - 20, card.y() + 13,
                20, 20, Component.literal("\u2190"), this::backInCards);
        if (backArrow != null) {
            // Inert while there is nothing to go back to, rather than hidden: a control that appears
            // and disappears with the stack would move under the pointer that is using it.
            backArrow.active = !overlayHistory.isEmpty();
            backArrow.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.literal("Back to the quest you came from"));
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
        // Per player: a teammate having collected their copy must not hide this player's button.
        java.util.UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        boolean claimable = self != null && ClientQuestCache.canClaimFor(self, entry.id());
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
            back.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.literal("Escape also closes this"));
        }
    }

    /**
     * The item picker's search box: the one widget it has, because every row is drawn and hit-tested by
     * the same derivation the list class owns.
     *
     * <h2>One method, two ways in</h2>
     *
     * <p>The picker opens as a page of the quest card and, since the Chapter tab's icon became pickable,
     * as an overlay of its own -- see {@link Overlay#PICKER}. Both need this box built identically, and
     * the alternative to extracting it was a second copy of the placeholder, the clip and the redraw
     * ordering below, each of which is a thing that only looks right when it is the same in both.
     *
     * <p>Rebuilt with the value it already held: a tick rebuild (a replica arriving mid-search) must not
     * clear what is being typed.
     */
    private void buildPickerWidgets() {
        if (pickingItemPath == null) {
            itemSearch = null;
            return;
        }
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
        addRenderableWidget(itemSearch);
        // **Redrawn after the card, because the widget pass runs before it.** This is the third
        // time this exact ordering has cost something: the modal's card is painted after
        // `super.render`, so a field left to that pass is painted over -- every other field in
        // this card carries the same redraw for the same reason. The placeholder goes after the
        // field in the same redraw, because it is drawn over the field's own fill; and only when
        // the box is empty, which is the whole of what a placeholder is.
        modalRedraws.add(r -> {
            // Clipped to the body, for the reason the settings page's fields are: `render` neither
            // checks `visible` nor clips, so a redraw that is not the widget pass can paint outside
            // the card.
            try (GuiRenderer.Scoped clip = r.clip(bodyRect.x(), bodyRect.y(), bodyRect.right(),
                    bodyRect.bottom())) {
                itemSearch.render(r);
                if (itemSearch.value().isEmpty()) {
                    r.text("Search items \u2014 name or id", frame.search().x() + 4,
                            frame.search().y() + (frame.search().height() - 8) / 2,
                            ArmatureTheme.faint());
                }
            }
        });
        setFocused(itemSearch);
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
                // A live role rather than a captured colour: the armed state's red is the theme's
                // blocked ink, and this card is drawn inside the chapter's scope.
                questDeleteButton.ink(confirmingDelete ? ArmatureButton.Ink.BLOCKED
                        : ArmatureButton.Ink.BODY);
            }
            control(duplicate, Component.literal("Duplicate"), this::duplicateQuest)
                    .ink(ArmatureButton.Ink.BODY);
            control(copy, Component.literal("Copy"), this::copyQuest)
                    .ink(ArmatureButton.Ink.BODY);
            settingsButton = control(settings, Component.literal("Settings"), this::toggleSettings);
            if (settingsButton != null) {
                settingsButton.ink(ArmatureButton.Ink.BODY).selected(settingsOpen)
                        .tooltip(Component.literal("Shape, size, placement and rules"));
            }
        }
        ArmatureButton done = control(controls.get("back"), Component.literal("Done"), this::closeOverlay);
        if (done != null) {
            done.ink(ArmatureButton.Ink.BODY).tooltip(Component.literal("Escape also closes this"));
        }

        buildPickerWidgets();

        // The body is the preview itself now -- drawn, not a list of widgets -- so the only rows that
        // still need hosting are the type picker's, when it is open.
        if (pickingEntryType != null || pickingConditionFor != null) {
            questRows = pickerRows();
            Viewport body = overlayBody();
            questLayout = InspectLayout.build(questRows, body.viewWidth(), Measure.monospace(6, 9));
            overlayView.clear();
            overlayView.whole(true);
            overlayView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                    body.viewHeight());
            for (InspectRow row : questRows) {
                if (row.kind() == InspectRow.Kind.ACTION
                        && row.key().startsWith(QuestPanelLayout.TYPE_PREFIX)) {
                    String typeId = row.key().substring(QuestPanelLayout.TYPE_PREFIX.length());
                    ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                            () -> pressQuestAction(row.key()));
                    button.alignLeft(true).flat(true);
                    // The type's registered icon and its hover description. The icon is the one the entry
                    // row will lead with once the type is added, so the picker chooses in the terms the
                    // row reads back in.
                    ItemStack icon = pickerIcon(typeId);
                    if (!icon.isEmpty()) {
                        button.icon(icon);
                    }
                    button.tooltip(pickerTooltip(typeId));
                    overlayView.put(row.key(), button);
                }
            }
            overlayView.apply(questLayout, body.viewWidth());
        }

        // The settings page's widgets, when it is open: one text field per row that takes typing, placed
        // by the page's own layout so a scrolled field sits beside its label. Everything else on the
        // page is drawn and hit-tested from `QuestSettingsLayout`, which is why the page has three
        // widgets rather than twenty.
        settingsRows = List.of();
        settingsLayout = null;
        if (settingsOpen) {
            JsonObject quest = replicaQuest();
            settingsRows = dev.ellipog.tasked.client.dev.QuestSettingsLayout.rows(quest,
                    this::dependencyTitle, selectedDependencyCandidates().size());
            dev.ellipog.tasked.client.dev.QuestSettingsLayout.Frame frame =
                    dev.ellipog.tasked.client.dev.QuestSettingsLayout.Frame.of(overlayBodyRect());
            settingsLayout = dev.ellipog.tasked.client.dev.QuestSettingsLayout.build(settingsRows,
                    frame.controls().width(), Measure.monospace(6, 9));
            settingsView.clear();
            settingsView.whole(true);
            // The column's own rectangle -- not the page's: a widget placed from a viewport that does
            // not match the clip sits beside its label.
            settingsView.viewport().bounds(frame.controls().x(), frame.controls().y(),
                    frame.controls().width(), frame.controls().height());
            for (dev.ellipog.tasked.client.dev.QuestSettingsLayout.Row row : settingsRows) {
                if (row.kind() != dev.ellipog.tasked.client.dev.QuestSettingsLayout.Row.Kind.FIELD) {
                    continue;
                }
                String path = row.key();
                ArmatureTextField field = new ArmatureTextField(0, 0, 0, 0, questValue(quest, path));
                field.onSubmit(text -> commitField(path, text, false));
                settingsView.put(row.key(), field,
                        dev.ellipog.tasked.client.dev.QuestSettingsLayout::strip);
                addRenderableWidget(field);
                // Clipped to the column, and skipped when the scroll view has culled the row -- which
                // `render` does not do for itself, because the widget pass is what normally checks
                // `visible` and this redraw is not the widget pass. Without both, a field scrolled out
                // of the column was painted over the footer and the dimmed world below it: the report
                // was "input fields flow outside sometimes".
                modalRedraws.add(r -> {
                    if (!field.visible) {
                        return;
                    }
                    try (GuiRenderer.Scoped clip = r.clip(frame.controls().x(), frame.controls().y(),
                            frame.controls().right(), frame.controls().bottom())) {
                        field.render(r);
                    }
                });
            }
            settingsView.apply(settingsLayout, frame.controls().width());
        }
    }

    /** The card's body as a rectangle, for the layouts that take one. */
    private BookGeometry.Rect overlayBodyRect() {
        return BookGeometry.Rect.at(overlayBody().originX(), overlayBody().originY(),
                overlayBody().viewWidth(), overlayBody().viewHeight());
    }

    /** A field's value as a box starts it, from the replica — empty when the field is absent. */
    private String questValue(JsonObject quest, String path) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, path);
        if (value == null) {
            return "";
        }
        if (value.isJsonArray()) {
            return String.join(", ", QuestPanelLayout.strings(quest, path));
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    /**
     * The settings page's own state: what the page is asking for, and what is under the pointer.
     *
     * <p>One record rather than six locals, because the drawing, the hit tests and the preview all need
     * the same answer to "what is the page showing" — and the alternative is three places that each
     * remember to ask the draft first.
     */
    private dev.ellipog.tasked.client.dev.QuestSettingsPanel.View settingsViewOf(
            ClientQuestCache.Entry entry, JsonObject quest, double mouseX, double mouseY) {
        // The server's answer with the page's pending values applied over it, resolved here rather than
        // in the panel: the outline a rotation produces has to be *built*, and building it per frame
        // inside the drawing would rebuild a sampled table per frame.
        QuestShape shape = settingsDraft.shape(entry.shape());
        int size = settingsDraft.size(entry.size());
        double iconScale = settingsDraft.iconScale(entry.iconScale());
        int rotation = settingsDraft.rotation(entry.rotation());
        boolean showTitle = flagOn(quest, "showTitle");
        int hoveredCell = -1;
        String hoveredKey = null;
        Slot grid = settingsLayout == null ? null : settingsLayout.slot("shape");
        if (grid != null) {
            Slot onScreen = InspectLayout.onScreen(settingsView.viewport(), grid);
            hoveredCell = dev.ellipog.tasked.client.dev.QuestSettingsLayout.cellAt(onScreen, mouseX,
                    mouseY, QuestShape.values().length);
            if (hoveredCell >= 0) {
                hoveredKey = "shape";
            }
        }
        if (hoveredKey == null && settingsLayout != null) {
            for (dev.ellipog.tasked.client.dev.QuestSettingsLayout.Row row : settingsRows) {
                Slot slot = settingsLayout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = InspectLayout.onScreen(settingsView.viewport(), slot);
                if (onScreen.contains(mouseX, mouseY)) {
                    hoveredKey = row.key();
                    break;
                }
            }
        }
        return new dev.ellipog.tasked.client.dev.QuestSettingsPanel.View(titleOf(entry), entry.icon(),
                shape, previewGeometry(shape, rotation), rotation, size, iconScale, showTitle,
                hoveredCell, hoveredKey,
                entry.chapterDefaultPrerequisiteMode().name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The outline the settings page previews, remembered until the shape or the angle changes.
     *
     * <p>A rotation is applied by sampling the shape again, which is O(size squared) -- cheap once, and
     * not something to do inside a drawing pass. The memo is one entry, because the page previews one
     * node, and a drag rebuilds it once per frame at worst, which is a few thousand point tests.
     */
    private dev.ellipog.armature.client.ui.shape.Shape previewGeometry(QuestShape shape, int rotation) {
        if (previewGeometry == null || previewShape != shape || previewRotation != rotation) {
            previewShape = shape;
            previewRotation = rotation;
            previewGeometry = rotation == 0
                    ? shape.geometry()
                    : dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation);
        }
        return previewGeometry;
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

        // The settings page takes the body when it is open, on the pickers' own terms: it is a page of
        // the card rather than a popover over it, because the thing it is changing -- the node -- needs
        // room to be drawn, and a popover over the prose was the form the third playtest rejected.
        if (settingsOpen) {
            drawSettings(r, entry, mouseX, mouseY);
            return;
        }

        // The item picker takes the body when it is open, on the type picker's own terms: the list is
        // what you are reading, the card is the page you are setting a field on, and the page behind a
        // list is context rather than a second thing to press.
        if (pickingItemPath != null) {
            drawItemPicker(r, body, mouseX, mouseY);
            return;
        }

        // The type picker takes the body when it is open: a list of types is a list, and rows are what
        // lists are made of. The only rows left in this card.
        if (pickingEntryType != null || pickingConditionFor != null) {
            if (questLayout != null) {
                overlayView.apply(questLayout, body.viewWidth());
                QuestPanel.drawRows(r, BookGeometry.Rect.at(body.originX(), body.originY(),
                        body.viewWidth(), body.viewHeight()), overlayView.viewport(), questLayout,
                        questRows, mouseX, mouseY);
            }
            // The list's own bar, drawn here because this page returns before the card's call below:
            // a list that scrolls with nothing on screen saying so is the defect that call records.
            overlayView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
            return;
        }

        JsonObject quest = replicaQuest();
        if (quest == null) {
            r.text("Waiting for the chapter's copy\u2026", body.originX() + 6, body.originY() + 6,
                    ArmatureTheme.faint());
            return;
        }

        List<String> description = shownDescription(entry, quest);
        // Per-entry line counts, not a count: an entry whose controls wrap is taller, and the layout
        // has to place the rows below it against the height the drawing will use.
        List<Integer> taskLines = entryLineCounts("tasks", quest, body.viewWidth());
        List<Integer> rewardLines = entryLineCounts("rewards", quest, body.viewWidth());
        List<String> dependencies = QuestPanelLayout.strings(quest, "dependsOn");
        Layout layout = OverlayLayout.stack(editorProse(r, description, body.viewWidth()),
                        taskLines, rewardLines, dependencies.size(), true)
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
            if (taskLines.isEmpty()) {
                // The row the layout reserves either way ("a row either way, so an empty list and a
                // one-item list take the same space") -- which the editor left *blank*, so an empty
                // section was a heading, a hole, and then the add row. The reader's own words go in it.
                drawEmptyState(r, placed(layout, body, OverlayLayout.NO_TASKS), "Nothing required");
            }
            for (int i = 0; i < taskLines.size(); i++) {
                drawEntryRow(r, placed(layout, body, OverlayLayout.taskKey(i)), memberEntry(quest, "tasks", i),
                        "tasks", i, mouseX, mouseY);
            }
            drawAddRow(r, placed(layout, body, OverlayLayout.TASKS_ADD), "+ Add task",
                    EditAction.ADD_TASK, mouseX, mouseY);

            drawHeading(r, placed(layout, body, OverlayLayout.REWARDS_HEADING), "REWARDS");
            if (rewardLines.isEmpty()) {
                drawEmptyState(r, placed(layout, body, OverlayLayout.NO_REWARDS), "Nothing");
            }
            for (int i = 0; i < rewardLines.size(); i++) {
                drawEntryRow(r, placed(layout, body, OverlayLayout.rewardKey(i)),
                        memberEntry(quest, "rewards", i), "rewards", i, mouseX, mouseY);
            }
            drawAddRow(r, placed(layout, body, OverlayLayout.REWARDS_ADD), "+ Add reward",
                    EditAction.ADD_REWARD, mouseX, mouseY);

            // The insertion line last in the clip, over every row it sits between: a line drawn where
            // the rows are drawn is a line the next row paints over.
            if (dragRowLive) {
                drawRowDragIndicator(r, dragRowSlots.get(dragRowMember), dragRowPointerY, onScreenBand(body));
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
                        Math.max(60, r.textWidth(titleOf(entry)) + 6), 12),
                textX, card.y() + 12, titleOf(entry), null, -1, mouseX, mouseY);

        String where = entry.chapterTitle()
                + (subtitleOf(entry).isEmpty() ? "" : "  \u00b7  " + subtitleOf(entry));
        target(r, EditAction.FIELD, "subtitle", BookGeometry.Rect.at(textX - 2, card.y() + 24,
                        Math.max(80, r.textWidth(where) + 6), 12),
                textX, card.y() + 26, subtitleOf(entry), null, -1, mouseX, mouseY);
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
     * How many lines each of a quest's entries needs, for the card layout's heights.
     *
     * <p>The width is the body's, less the layout's own row indent -- the same arithmetic a {@code Slot}
     * gets at build time, so a row's height and its slot cannot disagree about where the row ends.
     */
    private List<Integer> entryLineCounts(String member, JsonObject quest, int bodyWidth) {
        if (!quest.has(member) || !quest.get(member).isJsonArray()) {
            return List.of();
        }
        int slotWidth = bodyWidth - 2 * OverlayLayout.ROW_INDENT;
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < quest.getAsJsonArray(member).size(); i++) {
            JsonElement element = quest.getAsJsonArray(member).get(i);
            lines.add(element.isJsonObject()
                    ? EntryFormLayout.lines(member, element.getAsJsonObject(), slotWidth,
                            entryFolded.contains(member + "." + i))
                    : 1);
        }
        return lines;
    }

    /**
     * One task or reward row, as its type's own form.
     *
     * <p>The badge names the type and shows its icon at its own size; the fields come from the type's
     * registration -- {@code QuestPanelLayout.editorFor}, which is the registry's form with the settings
     * every task or reward carries appended -- and each is drawn as a label in the label column with its
     * control beside it. An unknown type gets the raw value instead, editable whole, which is the one
     * thing that can honestly be offered for a shape this build cannot read.
     */
    private void drawEntryRow(GuiRenderer r, Slot slot, JsonObject entry, String member, int index,
                              int mouseX, int mouseY) {
        if (slot == null || entry == null) {
            return;
        }
        String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString() : "not stated";
        boolean known = QuestPanelLayout.knownType(type);
        boolean folded = entryFolded.contains(member + "." + index);

        // A rule between entries, so a stack of forms reads as a list of them rather than as one long
        // column of boxes. Inside the entry's own air, so no height changes and nothing moves.
        if (index > 0) {
            r.fill(slot.x(), slot.y() + 1, slot.right(), slot.y() + 2, ArmatureTheme.panelEdge());
        }

        // One derivation for the whole entry: the badge, the fields, the grip and the corner controls.
        // Drawing and the hit test below both read it, so a control cannot be drawn on one line and
        // pressed on another -- the rule every row in this card follows.
        EntryFormLayout.Form form = EntryFormLayout.form(member, entry,
                BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height()), folded);

        drawBadge(r, form, member, type, known, folded, entry, mouseX, mouseY);

        if (known && !folded) {
            for (EntryFormLayout.Cell cell : form.cells()) {
                drawField(r, cell, entry, member, index, mouseX, mouseY);
            }
            for (EntryFormLayout.ConditionRow condition : form.conditions()) {
                drawConditionRow(r, condition, entry, member, index, mouseX, mouseY);
            }
            drawConditionAddRow(r, form.addCondition(), member, index, mouseX, mouseY);
        }
        else if (!known && !folded) {
            drawRawRow(r, form, member, index, mouseX, mouseY);
        }

        // Copy and the cross, on the badge's line at the entry's corner. **Flat, with the frame only on
        // hover**, and that is a fix rather than a taste: drawn like everything else they carried the same
        // faint frame a value box carries, so every entry ended in what read as two more text fields --
        // "Copy" and "×" sitting in boxes the size of the ones you type in.
        String[] labels = {"Copy", "\u00d7"};
        EditAction[] actions = {EditAction.COPY_ENTRY, EditAction.REMOVE_ENTRY};
        BookGeometry.Rect[] boxes = {form.copy(), form.remove()};
        for (int i = 0; i < 2; i++) {
            BookGeometry.Rect box = boxes[i];
            boolean hot = box.contains(mouseX, mouseY);
            if (hot) {
                drawEditAffordance(r, box, true);
            }
            r.text(labels[i], box.x() + (box.width() - r.textWidth(labels[i])) / 2,
                    box.y() + (box.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
            editTargets.add(new EditTarget(actions[i], null, box, box.x(),
                    box.y() + (box.height() - 8) / 2, "", member, index));
        }

        // The fold: the triangle between Copy and the cross, pointing down at the fields while they are
        // shown and up while they are not. Flat like the two beside it, and its word on hover -- a
        // triangle is a guess until it says what it does.
        BookGeometry.Rect fold = form.fold();
        boolean foldHot = fold.contains(mouseX, mouseY);
        if (foldHot) {
            drawEditAffordance(r, fold, true);
        }
        String mark = folded ? "\u25b2" : "\u25bc";
        r.text(mark, fold.x() + (fold.width() - r.textWidth(mark)) / 2,
                fold.y() + (fold.height() - 8) / 2, foldHot ? ArmatureTheme.title() : ArmatureTheme.body());
        if (foldHot) {
            String word = folded ? "Expand" : "Collapse";
            int width = r.textWidth(word) + 8;
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(Math.min(fold.x(), geometry().modal().right() - width - 6),
                            fold.bottom() + 2, width, 12),
                    word));
        }
        editTargets.add(new EditTarget(EditAction.TOGGLE_ENTRY, null, fold, fold.x(),
                fold.y() + (fold.height() - 8) / 2, "", member, index));

        // The row's leading strip is the drag's grip -- the type's icon and the gutter beside it, which no
        // field covers, so registering last wins exactly the space nothing else wanted. The whole row is
        // what the gap is counted against; the grip is only where the press lands.
        //
        // **Marked only while the pointer is on it**, for the same reason as Copy above: the grip used to
        // draw the value-box frame, so every entry wore a tall empty box down its left edge -- a column of
        // what looked like unlabelled fields. A bar on hover says the same thing without pretending there
        // is something there.
        dragRowSlots.computeIfAbsent(member, key -> new ArrayList<>())
                .add(BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height()));
        BookGeometry.Rect grip = form.grip();
        if (grip.contains(mouseX, mouseY)) {
            r.fill(grip.x() + 1, grip.y() + 2, grip.x() + 3, grip.bottom() - 2, ArmatureTheme.hoverRing());
        }
        registerTarget(EditAction.DRAG_ENTRY, null, grip, grip.x() + 4,
                grip.y() + (grip.height() - 8) / 2, "", member, index);
    }

    /**
     * One condition under an entry: its badge, its own fields, and the cross that removes it.
     *
     * <p>A condition is edited exactly as a task is, one level down: the same registered form, the same
     * controls, the same one-op-per-press rule — with the condition's type's form from
     * {@code QuestPanelLayout.conditionEditorFor}. A type this build does not know gets the raw value,
     * the same refusal an unknown entry gets: what cannot be drawn is not faked.
     */
    private void drawConditionRow(GuiRenderer r, EntryFormLayout.ConditionRow row, JsonObject entry,
                                  String member, int index, int mouseX, int mouseY) {
        JsonElement value = entry.getAsJsonArray("conditions").get(row.index());
        if (!value.isJsonObject()) {
            return;
        }
        JsonObject condition = value.getAsJsonObject();
        String pathPrefix = member + "." + index + ".conditions." + row.index();
        String type = condition.has("type") && condition.get("type").isJsonPrimitive()
                ? condition.get("type").getAsString() : "not stated";

        ItemStack icon = conditionIcon(type);
        if (!icon.isEmpty()) {
            r.icon(icon, row.icon().x(), row.icon().y(), EntryFormLayout.ICON_BOX);
        }
        r.text(Measure.truncate(QuestPanelLayout.conditionName(type), row.name().width() - 2,
                        textMeasure(r)),
                row.name().x() + 1, row.name().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                ArmatureTheme.faint());

        BookGeometry.Rect remove = row.remove();
        boolean removeHot = remove.contains(mouseX, mouseY);
        if (removeHot) {
            drawEditAffordance(r, remove, true);
        }
        String cross = "\u00d7";
        r.text(cross, remove.x() + (remove.width() - r.textWidth(cross)) / 2,
                remove.y() + (remove.height() - 8) / 2,
                removeHot ? ArmatureTheme.title() : ArmatureTheme.body());
        editTargets.add(new EditTarget(EditAction.CONDITION_REMOVE, pathPrefix, remove, remove.x(),
                remove.y() + (remove.height() - 8) / 2, "", member, index));

        if (QuestPanelLayout.conditionEditorFor(condition).isEmpty()) {
            BookGeometry.Rect raw = BookGeometry.Rect.at(row.name().x(), row.name().bottom(),
                    Math.max(20, row.name().width()), EntryFormLayout.LINE_HEIGHT);
            drawEditAffordance(r, raw, raw.contains(mouseX, mouseY));
            if (!InlineEdit.replaces(pathPrefix, editingPath)) {
                r.text("edit JSON", raw.x() + 4, raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                        ArmatureTheme.faint());
            }
            editTargets.add(new EditTarget(EditAction.RAW, pathPrefix, raw, raw.x() + 4,
                    raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, "", member, index));
            return;
        }
        for (EntryFormLayout.Cell cell : row.cells()) {
            // The condition owns these fields. `drawField` reads every value and flag state from the
            // object it is handed, and a task beside it has fields with the same names -- item, count,
            // match -- so handing it the entry is what made a condition's row display the task's
            // numbers, and its inline editor pre-fill from them.
            drawField(r, cell, condition, member, index, pathPrefix, mouseX, mouseY);
        }
    }

    /** The row that opens the condition picker: a chip in the entry's own fields' column, and its press. */
    private void drawConditionAddRow(GuiRenderer r, BookGeometry.Rect box, String member, int index,
                                     int mouseX, int mouseY) {
        if (box.width() <= 0 || box.height() <= 0) {
            return;
        }
        BookGeometry.Rect chip = BookGeometry.Rect.at(box.x() + EntryFormLayout.CONDITION_INDENT, box.y(),
                Math.min(84, Math.max(20, box.width() - EntryFormLayout.CONDITION_INDENT)), box.height());
        boolean hot = chip.contains(mouseX, mouseY);
        drawEditAffordance(r, chip, hot);
        String label = "+ Condition";
        r.text(Measure.truncate(label, chip.width() - 6, textMeasure(r)), chip.x() + 3,
                chip.y() + (chip.height() - 8) / 2, hot ? ArmatureTheme.title() : ArmatureTheme.body());
        registerTarget(EditAction.CONDITION_ADD, member + "." + index, chip, chip.x() + 3,
                chip.y() + (chip.height() - 8) / 2, "", member, index);
    }

    /** A condition type's registered icon, or nothing for one this build does not know. */
    private static ItemStack conditionIcon(String typeId) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(typeId);
        return id == null ? ItemStack.EMPTY
                : dev.ellipog.tasked.quest.condition.ConditionTypes.iconOf(id).toStack();
    }

    /**
     * The entry's badge: the type's icon at its own size, and the type's name beside it.
     *
     * <p>The icon used to be drawn sixteen pixels tall into a fourteen-pixel slot, one pixel high, with
     * nothing saying what it was -- a game item standing in for a concept. At its own size, beside the
     * type's name, it reads as what the type's author meant it to be: a badge.
     *
     * <p>Folded, the name is followed by a summary of what the entry says -- see {@link #summary} -- so a
     * folded row is still a row that tells you something rather than a row reduced to a word.
     */
    private void drawBadge(GuiRenderer r, EntryFormLayout.Form form, String member, String type,
                           boolean known, boolean folded, JsonObject entry, int mouseX, int mouseY) {
        ItemStack icon = typeIcon(member, type);
        if (!icon.isEmpty()) {
            r.icon(icon, form.icon().x(),
                    form.icon().y() + (form.icon().height() - EntryFormLayout.ICON_BOX) / 2,
                    EntryFormLayout.ICON_BOX);
        }
        // A type this build cannot read says so where its name would be, and keeps the raw editor below:
        // the alternative -- no name, no form, no explanation -- is the row that looked broken.
        String name = known ? QuestPanelLayout.typeName(member, type)
                : type + " \u2014 not known to this build";
        String line = name;
        if (folded && known) {
            String summary = summary(member, entry);
            if (!summary.isEmpty()) {
                line = name + "   \u00b7   " + summary;
            }
        }
        r.text(Measure.truncate(line, form.name().width(), textMeasure(r)), form.name().x(),
                form.name().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                known ? ArmatureTheme.title() : ArmatureTheme.blocked());

        // The raw id, when the pointer is over the icon: the badge says "Item", and the spelling a file
        // and an error message use is one hover away rather than nowhere.
        if (form.icon().contains(mouseX, mouseY)) {
            int labelWidth = r.textWidth(type) + 8;
            int labelX = Math.min(form.icon().x(), geometry().modal().right() - labelWidth - 6);
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(labelX, form.icon().bottom() + 2, labelWidth, 12), type));
        }
    }

    /**
     * One line saying what an entry asks for, in the words its own fields use.
     *
     * <p>Built from the same rules the controls draw by -- an item's name, an id's friendly label, a
     * choice's option, a switch's own label while it is on, a number with its unit -- so a folded entry
     * reads as the reader's line for it rather than as a second, different summary. Triples are left out:
     * "Corner · Box size" for six numbers says nothing, and the fields are one press away.
     */
    private String summary(String member, JsonObject entry) {
        StringBuilder out = new StringBuilder();
        for (EditorField field : QuestPanelLayout.editorFor(member, entry)) {
            String part = summaryOf(field, entry);
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("   \u00b7   ");
            }
            out.append(part);
        }
        return out.toString();
    }

    /** One field's contribution to a folded entry's line: empty when the field says nothing. */
    private String summaryOf(EditorField field, JsonObject entry) {
        if (field.isTriple()) {
            return "";
        }
        if (field.kind() == EditorField.Kind.FLAG) {
            return flagOn(entry, field.path()) ? field.label() : "";
        }
        String value = rawValue(entry, field.path());
        if (value.isEmpty()) {
            return "";
        }
        return switch (field.kind()) {
            case ITEM -> {
                ItemStack stack = itemStack(value);
                yield stack.isEmpty() ? value : stack.getHoverName().getString();
            }
            case SEARCH, TAG -> friendlyId(value);
            case CHOICE -> EditorSpecs.label(value);
            case NUMBER -> field.unit().isEmpty() ? value : value + " " + field.unit();
            default -> value;
        };
    }

    /**
     * Folds an entry to its badge, or unfolds it.
     *
     * <p>Nothing is rebuilt: the card asks every entry for its line count as it lays out, so the list
     * reflows on the next frame -- and the fold is keyed by the entry's own position, the same index-keyed
     * shape the card's sections fold by.
     */
    private void toggleFold(String member, int index) {
        String key = member + "." + index;
        if (!entryFolded.remove(key)) {
            entryFolded.add(key);
        }
    }

    /**
     * One field of an entry: its label in the label column, and its control in the column beside it.
     *
     * <p>The label is outside the box, which is the whole difference from the row this replaced: a box
     * that reads "Count" when it is empty and "8" when it is not is a box an author has to press to
     * identify, and a form whose labels all sit on one x is one an eye can run down.
     */
    private void drawField(GuiRenderer r, EntryFormLayout.Cell cell, JsonObject entry, String member,
                           int index, int mouseX, int mouseY) {
        drawField(r, cell, entry, member, index, member + "." + index, mouseX, mouseY);
    }

    /**
     * The same, with the path the field commits to supplied by the caller.
     *
     * <p>The one difference a condition makes: its fields live one level deeper, and every press it
     * registers must carry the full path — {@code tasks.3.conditions.1.min} — while the form's own
     * arithmetic is unchanged. The member and index still name the entry, which is what the presses that
     * need more than a path resolve their field through.
     */
    private void drawField(GuiRenderer r, EntryFormLayout.Cell cell, JsonObject entry, String member,
                           int index, String pathPrefix, int mouseX, int mouseY) {
        EditorField field = cell.field();
        String path = pathPrefix + "." + field.path();
        String value = rawValue(entry, field.path());
        boolean on = flagOn(entry, field.path());
        boolean replaced = InlineEdit.replaces(path, editingPath);
        Measure measure = textMeasure(r);

        // The label: lit when its switch is on, so a form of flags reads as a set of states rather than a
        // set of words.
        r.text(Measure.truncate(field.label(), cell.label().width() - 4, measure),
                cell.label().x() + 2, cell.label().y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                field.kind() == EditorField.Kind.FLAG && on ? ArmatureTheme.title() : ArmatureTheme.faint());

        // And what the field is for, on the label's own hover: the labels are one or two words by
        // necessity, and a form of switches reads as a set of guesses without this.
        if (!field.hint().isEmpty() && cell.label().contains(mouseX, mouseY)) {
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(cell.label().x(), cell.label().bottom() + 2,
                            r.textWidth(field.hint()) + 8, 12),
                    field.hint()));
        }

        switch (field.kind()) {
            case FLAG -> {
                // A switch: a chip that lights. The press is the chip and its label together, without the
                // hover frame -- a control that is a chip does not need a box drawn around it as well.
                drawSwitch(r, cell.value(), on);
                BookGeometry.Rect whole = BookGeometry.Rect.at(cell.label().x(), cell.label().y(),
                        Math.max(0, cell.value().right() - cell.label().x()), EntryFormLayout.LINE_HEIGHT);
                registerTarget(EditAction.FLAG, path, whole, whole.x() + 2, whole.y() + 3, value, member,
                        index);
            }
            case NUMBER -> {
                drawChip(r, cell.minus(), "-", cell.minus().contains(mouseX, mouseY));
                drawChip(r, cell.plus(), "+", cell.plus().contains(mouseX, mouseY));
                drawNumber(r, cell.value(), value, field.unit(), "", replaced, mouseX, mouseY);
                // The boxes a number is nudged with, and the box it is typed in. Three presses rather
                // than one: a count is almost always a nudge, and the buttons say which direction.
                registerTarget(EditAction.STEP_DOWN, path, cell.minus(), cell.minus().x() + 3,
                        cell.minus().y() + 3, value, member, index);
                registerTarget(EditAction.STEP_UP, path, cell.plus(), cell.plus().x() + 3,
                        cell.plus().y() + 3, value, member, index);
                target(r, EditAction.FIELD, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
            case CHOICE -> {
                String shown = value.isEmpty() ? "" : EditorSpecs.label(value);
                drawValue(r, cell.value(), shown, "", false, replaced, mouseX, mouseY);
                drawOptionsMark(r, cell.value());
                if (cell.value().contains(mouseX, mouseY)) {
                    // What the press will do, said before it is pressed: a cycle through values is a
                    // control nobody can aim without knowing the ring.
                    pendingLabels.add(new PendingLabel(
                            BookGeometry.Rect.at(cell.value().x(), cell.value().bottom() + 2,
                                    r.textWidth(optionsHint(field)) + 8, 12),
                            optionsHint(field)));
                }
                registerTarget(EditAction.CYCLE_CHOICE, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index);
            }
            case ITEM -> {
                int textX = drawItemValue(r, cell.value(), value, replaced, mouseX, mouseY);
                target(r, EditAction.ITEM, path, cell.value(), textX, cell.value().y() + 3, value, member,
                        index, mouseX, mouseY);
            }            case POSITION, SIZE -> {
                String[] axes = {"X", "Y", "Z"};
                for (int axis = 0; axis < 3; axis++) {
                    BookGeometry.Rect box = cell.axes().get(axis);
                    String element = rawValue(entry, field.path() + "." + axis);
                    drawNumber(r, box, element, "", axes[axis], replaced, mouseX, mouseY);
                    target(r, EditAction.FIELD, pathPrefix + "." + field.axis(axis), box,
                            box.x() + 4, box.y() + 3, element, member, index, mouseX, mouseY);
                }
                if (field.kind() == EditorField.Kind.POSITION) {
                    drawEditAffordance(r, cell.action(), cell.action().contains(mouseX, mouseY));
                    String label = "My position";
                    r.text(Measure.truncate(label, cell.action().width() - 6, measure),
                            cell.action().x() + 3, cell.action().y() + 3, ArmatureTheme.body());
                    registerTarget(EditAction.USE_POSITION, path, cell.action(), cell.action().x() + 3,
                            cell.action().y() + 3, value, member, index);
                }
            }
            default -> {
                // Text, and the ids a search lists. A text box carries its hint when it is empty, which
                // is the difference between a box that explains itself and one that does not.
                String shown = value.isEmpty() ? field.hint() : value;
                drawValue(r, cell.value(), shown, "", value.isEmpty(), replaced, mouseX, mouseY);
                if (cell.value().contains(mouseX, mouseY) && !field.hint().isEmpty() && !value.isEmpty()) {
                    pendingLabels.add(new PendingLabel(
                            BookGeometry.Rect.at(cell.value().x(), cell.value().bottom() + 2,
                                    r.textWidth(field.hint()) + 8, 12),
                            field.hint()));
                }
                target(r, EditAction.FIELD, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
            case SEARCH, TAG -> {
                // An id with a list behind it: the name a person reads while it is set, and the picker on
                // the press. The raw id becomes the hover label, because the form has room for one word
                // and the spelling a file and an error message use is the other one.
                String shown = value.isEmpty() ? "search\u2026" : friendlyId(value);
                drawValue(r, cell.value(), shown, "", value.isEmpty(), replaced, mouseX, mouseY);
                if (cell.value().contains(mouseX, mouseY) && !value.isEmpty()) {
                    pendingLabels.add(new PendingLabel(
                            BookGeometry.Rect.at(cell.value().x(), cell.value().bottom() + 2,
                                    r.textWidth(value) + 8, 12),
                            value));
                }
                target(r, EditAction.SEARCH, path, cell.value(), cell.value().x() + 4,
                        cell.value().y() + 3, value, member, index, mouseX, mouseY);
            }
        }
    }

    /** The unknown-type row: the raw value, editable whole, drawn under the badge. */
    private void drawRawRow(GuiRenderer r, EntryFormLayout.Form form, String member, int index,
                            int mouseX, int mouseY) {
        BookGeometry.Rect row = BookGeometry.Rect.at(form.name().x(), form.name().y() +
                EntryFormLayout.LINE_HEIGHT, Math.max(20, form.name().width()), EntryFormLayout.LINE_HEIGHT);
        BookGeometry.Rect raw = BookGeometry.Rect.at(row.x(), row.y(),
                Math.min(120, Math.max(20, row.width())), EntryFormLayout.LINE_HEIGHT);
        drawEditAffordance(r, raw, raw.contains(mouseX, mouseY));
        // The label is what the field replaces; the field is transparent, so drawing both would print the
        // raw JSON over the words "edit JSON".
        String path = member + "." + index;
        if (!InlineEdit.replaces(path, editingPath)) {
            r.text("edit JSON", raw.x() + 4, raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                    ArmatureTheme.faint());
        }
        editTargets.add(new EditTarget(EditAction.RAW, path, raw, raw.x() + 4,
                raw.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, "", member, index));
    }

    /**
     * A value box: a faint frame, its text, and a right-aligned tail for a unit or a mark.
     *
     * <p>{@code replaced} is the inline editor's contract: the field drawing over this box draws the value,
     * and two drawings of one value is the fault that contract exists to prevent. The frame stays -- an
     * empty box where a value was reads as the row having lost it.
     */
    private void drawValue(GuiRenderer r, BookGeometry.Rect box, String text, String tail, boolean faint,
                           boolean replaced, int mouseX, int mouseY) {
        drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int room = box.width() - 8 - (tail.isEmpty() ? 0 : r.textWidth(tail) + 4);
        if (!text.isEmpty() && !replaced) {
            r.text(Measure.truncate(text, Math.max(0, room), textMeasure(r)), box.x() + 4, baseline,
                    faint ? ArmatureTheme.faint() : ArmatureTheme.body());
        }
        if (!tail.isEmpty()) {
            r.text(tail, box.right() - 4 - r.textWidth(tail), baseline, ArmatureTheme.faint());
        }
    }

    /**
     * A number: its axis letter where it has one, the value, and what it counts beside the value.
     *
     * <p>The unit rides with the number rather than at the far edge of the box -- `8 ×`, not `8        ×`.
     * Right-aligned it looked like a stray glyph in an empty box, and an empty number (which is every
     * number until it is set) made the box read as two thirds air. The axis letter stays on a filled box as
     * well as an empty one: "10" under a label that says "Corner" does not say which axis it is.
     */
    private void drawNumber(GuiRenderer r, BookGeometry.Rect box, String value, String unit,
                            String axisLetter, boolean replaced, int mouseX, int mouseY) {
        drawEditAffordance(r, box, box.contains(mouseX, mouseY));
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int textX = box.x() + 4;
        if (!axisLetter.isEmpty()) {
            r.text(axisLetter, textX, baseline, ArmatureTheme.faint());
            textX += r.textWidth(axisLetter) + 2;
        }
        int tail = unit.isEmpty() ? 0 : r.textWidth(unit) + 4;
        int room = Math.max(0, box.right() - 4 - textX - tail);
        if (!value.isEmpty() && !replaced) {
            String shown = Measure.truncate(value, room, textMeasure(r));
            r.text(shown, textX, baseline, ArmatureTheme.body());
            if (!unit.isEmpty()) {
                r.text(unit, textX + r.textWidth(shown) + 4, baseline, ArmatureTheme.faint());
            }
        }
    }

    /** The chip a stepper's `-` and `+` are drawn as. */
    private void drawChip(GuiRenderer r, BookGeometry.Rect box, String glyph, boolean hot) {
        drawEditAffordance(r, box, hot);
        r.text(glyph, box.x() + (box.width() - r.textWidth(glyph)) / 2,
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    /** The switch a flag is drawn as: filled when on, empty when off. */
    private void drawSwitch(GuiRenderer r, BookGeometry.Rect cell, boolean on) {
        int size = EntryFormLayout.BUTTON;
        BookGeometry.Rect chip = BookGeometry.Rect.at(cell.x(), cell.y() + (EntryFormLayout.LINE_HEIGHT - size) / 2,
                size, size);
        int edge = on ? ArmatureTheme.title() : ArmatureTheme.faint();
        r.fill(chip.x(), chip.y(), chip.right(), chip.y() + 1, edge);
        r.fill(chip.x(), chip.bottom() - 1, chip.right(), chip.bottom(), edge);
        r.fill(chip.x(), chip.y() + 1, chip.x() + 1, chip.bottom() - 1, edge);
        r.fill(chip.right() - 1, chip.y() + 1, chip.right(), chip.bottom() - 1, edge);
        if (on) {
            // A filled middle rather than a tick: the dot matrix has no check mark -- see the glyph list
            // `BookGeometry.TOOLS_BUTTON_WIDTH` records, where `\u2713` is absent.
            r.fill(chip.x() + 3, chip.y() + 3, chip.right() - 3, chip.bottom() - 3, ArmatureTheme.title());
        }
    }

    /** The mark that says a box cycles when pressed. `\u25bc` is a glyph the default font carries. */
    private void drawOptionsMark(GuiRenderer r, BookGeometry.Rect box) {
        String mark = "\u25bc";
        r.text(mark, box.right() - 4 - r.textWidth(mark),
                box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2, ArmatureTheme.faint());
    }

    /** What the press on a choice will do next, for the hover label. */
    private static String optionsHint(EditorField field) {
        StringBuilder out = new StringBuilder();
        for (String option : field.options()) {
            if (!out.isEmpty()) {
                out.append("  \u00b7  ");
            }
            out.append(EditorSpecs.label(option));
        }
        return out.toString();
    }

    /**
     * An item field's box: the icon, the item's own name, and -- on hover -- the id a file spells.
     *
     * <p>The name rather than the id, which is what the fields beside it do: a search field shows "Plains"
     * and a tag shows "Ores", so an item field showing {@code minecraft:cobblestone} made one form speak
     * two vocabularies. The id is a hover away, which is where it belongs -- and it is what a missing item
     * still shows, because there is no name to show for one.
     */
    private int drawItemValue(GuiRenderer r, BookGeometry.Rect box, String value, boolean replaced,
                              int mouseX, int mouseY) {
        int boxSize = Math.max(8, box.height() - 2);
        ItemStack stack = itemStack(value);
        boolean missing = !value.isEmpty() && stack.isEmpty();
        if (missing) {
            drawItemPlaceholder(r, box.x() + 1, box.y() + 1, boxSize);
        }
        else {
            r.icon(stack, box.x() + 1, box.y() + 1, boxSize);
        }
        int baseline = box.y() + (EntryFormLayout.LINE_HEIGHT - 8) / 2;
        int textX = box.x() + box.height() + 2;
        int room = Math.max(0, box.right() - 4 - textX - (missing ? r.textWidth("missing") + 4 : 0));
        if (replaced) {
            // The open field draws the words; the icon stays, because a blank where the item was would
            // read as the row having lost it.
            return textX;
        }
        if (missing) {
            r.text(Measure.truncate(value, room, textMeasure(r)), textX, baseline, ArmatureTheme.blocked());
            r.text("missing", box.right() - 4 - r.textWidth("missing"), baseline, ArmatureTheme.blocked());
            if (box.contains(mouseX, mouseY)) {
                pendingLabels.add(new PendingLabel(
                        BookGeometry.Rect.at(box.x(), box.bottom() + 2,
                                r.textWidth("missing - the mod is not installed") + 8, 12),
                        "missing - the mod is not installed; the id is kept"));
            }
            return textX;
        }
        String shown = stack.isEmpty() ? "pick an item" : stack.getHoverName().getString();
        r.text(Measure.truncate(shown, room, textMeasure(r)), textX, baseline,
                stack.isEmpty() ? ArmatureTheme.faint() : ArmatureTheme.body());
        // The id, on hover: the badge says "Item" and the box says "Cobblestone", and the spelling a file
        // and an error message use is the one thing neither of them says.
        if (!value.isEmpty() && box.contains(mouseX, mouseY)) {
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(box.x(), box.bottom() + 2, r.textWidth(value) + 8, 12), value));
        }
        return textX;
    }

    /** One entry of a quest's array, or null when the path names nothing that is an object. */
    private static JsonObject entryAt(JsonObject quest, String member, int index) {
        JsonElement found = QuestPanelLayout.get(quest, member + "." + index);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    /** A field's value as stored, for a form control: empty when it is absent or not a primitive. */
    private static String rawValue(JsonObject entry, String path) {
        JsonElement found = QuestPanelLayout.get(entry, path);
        return found == null || !found.isJsonPrimitive() ? "" : found.getAsString();
    }

    /** A registry id as a row reads it: the path, prettified, with a tag's leading `#` kept. */
    private static String friendlyId(String id) {
        return QuestPanelLayout.friendlyId(id);
    }

    /**
     * A task row's sentence, with a registry id inside it prettified where the type's table entry says
     * the argument names one.
     *
     * <p>The count-shaped types pass no argument: their key is written for the count ("%s XP"), which
     * {@code text()} supplies, and there is nothing in it to prettify. See {@link #prettyArg} for the
     * kinds that do.
     */
    private static String rowText(String member, ClientQuestCache.TaskEntry task) {
        String arg = task.labelArg();
        return arg.isEmpty()
                ? task.text().getString()
                : task.text(prettyArg(member, task.type(), arg)).getString();
    }

    /** A reward row's sentence; the task row's rule, one member over. */
    private static String rowText(String member, ClientQuestCache.RewardEntry reward) {
        String arg = reward.labelArg();
        return arg.isEmpty()
                ? reward.text().getString()
                : reward.text(prettyArg(member, reward.type(), arg)).getString();
    }

    /**
     * A row's argument, with the registry ids in it prettified when the type's table entry says its
     * argument is one -- "minecraft:plains" reads "Plains", "#minecraft:logs" reads "#Logs".
     *
     * <p>The walk, and the reason it is token by token over full ids only, are
     * {@link QuestPanelLayout#prettiedArgument}. What is here and not there is the one kind the client
     * can name better than any id can spell: an advancement. The client holds the advancement's own
     * title, so the walk is given a name to try first and falls back to the prettified id when the
     * client has not been told about that advancement.
     */
    private static String prettyArg(String member, String typeId, String arg) {
        if (!typeId.endsWith(":advancement")) {
            return QuestPanelLayout.prettiedArgument(member, typeId, arg);
        }
        return QuestPanelLayout.prettiedArgument(member, typeId, arg, QuestBookScreen::advancementTitle);
    }

    /** An id token's advancement title, or null when this client has not been told about it. */
    private static String advancementTitle(String id) {
        net.minecraft.resources.ResourceLocation location =
                net.minecraft.resources.ResourceLocation.tryParse(id);
        if (location == null) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return null;
        }
        for (net.minecraft.advancements.AdvancementNode node
                : minecraft.getConnection().getAdvancements().getTree().nodes()) {
            net.minecraft.advancements.AdvancementHolder holder = node.holder();
            if (holder.id().equals(location)) {
                return holder.value().display()
                        .map(display -> display.getTitle().getString())
                        .orElse(null);
            }
        }
        return null;
    }

    /**
     * The field of an entry's own form that a control's committed path belongs to, or null.
     *
     * <p>How the presses that need more than a path -- a cycle needs the ring, a search needs the source
     * -- find their field: the form is the registry's, and the path names one of its fields.
     */
    private static EditorField fieldAt(JsonObject entry, String member, int index, String path) {
        String prefix = member + "." + index + ".";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String relative = path.substring(prefix.length());
        // A condition's field: the entry's list, the condition, then the field's own path. One level of
        // nesting, and only one -- a condition has no lists of its own. Resolved here rather than by
        // widening EditTarget, so every press that needs its field (a cycle's ring, a search's source)
        // works for a nested field with no change of its own.
        if (relative.startsWith("conditions.")) {
            String tail = relative.substring("conditions.".length());
            int dot = tail.indexOf('.');
            if (dot < 0) {
                return null;
            }
            int conditionIndex;
            try {
                conditionIndex = Integer.parseInt(tail.substring(0, dot));
            }
            catch (NumberFormatException notAnIndex) {
                return null;
            }
            // `entry` is the entry object, and `prefix` already names it: the condition's own path is
            // relative to it, exactly as a field's path is relative to an entry.
            JsonElement found = QuestPanelLayout.get(entry, "conditions." + conditionIndex);
            if (found == null || !found.isJsonObject()) {
                return null;
            }
            String fieldPath = tail.substring(dot + 1);
            for (EditorField field : QuestPanelLayout.conditionEditorFor(found.getAsJsonObject())) {
                if (field.path().equals(fieldPath)) {
                    return field;
                }
            }
            return null;
        }
        for (EditorField field : QuestPanelLayout.editorFor(member, entry)) {
            if (field.path().equals(relative)) {
                return field;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // What the form's controls do
    // ------------------------------------------------------------------

    /**
     * A stepper's press: the number moves by one, or by ten with shift held.
     *
     * <p>One op per press, so Ctrl+Z undoes one nudge -- which is what a nudge is. The base comes from
     * the tree the card draws, which carries the draft, so spamming the button accumulates instead of
     * sending the same value from a stale copy — see {@code FieldDraft}. Clamped at zero, because
     * every number in the format counts something and a negative count is a refusal the server
     * would report as a sentence rather than as an edit.
     */
    private void nudge(EditTarget target, int direction) {
        JsonObject quest = replicaQuest();
        String questId = editTarget();
        if (quest == null || questId == null) {
            return;
        }
        JsonElement found = QuestPanelLayout.get(quest, target.path());
        int current = found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()
                ? found.getAsInt() : 0;
        int step = direction * (Screen.hasShiftDown() ? 10 : 1);
        sendField(questId, target.path(), new JsonPrimitive(Math.max(0, current + step)));
    }

    /**
     * A choice's press: the value becomes the next option in the field's own ring, and round again.
     *
     * <p>One op per press, and a value the ring does not hold -- an addon's, or one this build removed --
     * is not lost by the first press: the cycle starts from wherever the file is up to and moves on.
     */
    private void cycleChoice(EditTarget target) {
        JsonObject quest = replicaQuest();
        if (quest == null || editTarget() == null || target.index() < 0) {
            return;
        }
        JsonObject entry = entryAt(quest, target.member(), target.index());
        if (entry == null) {
            return;
        }
        EditorField field = fieldAt(entry, target.member(), target.index(), target.path());
        if (field == null || field.options().isEmpty()) {
            return;
        }
        int at = field.options().indexOf(target.value());
        String next = field.options().get((at + 1) % field.options().size());
        sendField(editTarget(), target.path(), new JsonPrimitive(next));
    }

    /**
     * A search field's press: the picker opens on the field's own source.
     *
     * <p>A source with nothing to list -- disconnected, or a registry the server has not sent -- still
     * opens, on an empty list with a box that takes a typed id. Refusing to open would leave the author
     * pressing a control that does nothing at all.
     */
    private void openSearchPick(EditTarget target, double mouseX, double mouseY) {
        JsonObject quest = replicaQuest();
        JsonObject entry = quest == null || target.index() < 0
                ? null
                : entryAt(quest, target.member(), target.index());
        EditorField field = entry == null
                ? null
                : fieldAt(entry, target.member(), target.index(), target.path());
        if (field != null && field.source() != null) {
            openSearchPicker(target, field);
            return;
        }
        openInlineEditor(target, mouseX, mouseY);
    }

    /**
     * The position button: the corner becomes where the player is standing, and the dimension becomes the
     * one they are standing in.
     *
     * <p>Both, because a corner with no dimension is a corner in whichever world the reader happens to be
     * in -- filling only the numbers would hand an author a box in the wrong place that looks right.
     *
     * <p>Two ops rather than one: an op is one field, and a composite op would be a new shape on the wire
     * for one button. So Ctrl+Z twice, said here rather than pretended otherwise.
     */
    private void useMyPosition(EditTarget target) {
        JsonObject quest = replicaQuest();
        if (quest == null || editTarget() == null || minecraft.player == null) {
            return;
        }
        net.minecraft.core.BlockPos pos = minecraft.player.blockPosition();
        JsonArray corner = new JsonArray();
        corner.add(pos.getX());
        corner.add(pos.getY());
        corner.add(pos.getZ());
        sendField(editTarget(), target.path(), corner);

        JsonObject entry = entryAt(quest, target.member(), target.index());
        // The object that owns this position's siblings: the entry, or -- for a condition's field -- the
        // condition itself. Read from the same nesting rule `fieldAt` uses, so a nested position resolves
        // its dimension beside itself rather than writing the task's own field.
        String container = containerOf(target.path(), target.member(), target.index());
        JsonObject owner = entryAt(quest, target.member(), target.index());
        JsonElement nested = container.equals(target.member() + "." + target.index())
                ? null : QuestPanelLayout.get(quest, container);
        JsonObject holder = nested != null && nested.isJsonObject() ? nested.getAsJsonObject() : owner;
        boolean hasDimension = holder != null
                && (container.contains(".conditions.")
                        ? QuestPanelLayout.conditionEditorFor(holder)
                        : QuestPanelLayout.editorFor(target.member(), holder)).stream()
                        .anyMatch(field -> field.path().equals("dimension"));
        if (hasDimension && minecraft.level != null) {
            sendField(editTarget(), container + ".dimension",
                    new JsonPrimitive(minecraft.level.dimension().location().toString()));
        }
    }

    /**
     * The object that owns a row's field: the entry, or the condition when the path is nested.
     *
     * <p>Mirrors {@code fieldAt}'s nesting rule exactly -- one level, {@code conditions.<index>} -- so the
     * two cannot come to disagree about where a condition's fields live.
     */
    private static String containerOf(String path, String member, int index) {
        String prefix = member + "." + index + ".";
        if (path.startsWith(prefix + "conditions.")) {
            String tail = path.substring((prefix + "conditions.").length());
            int dot = tail.indexOf('.');
            String conditionIndex = dot < 0 ? tail : tail.substring(0, dot);
            return prefix + "conditions." + conditionIndex;
        }
        return member + "." + index;
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

    /** One prerequisite: its name (a jump to that quest), a locate icon, and a cross to remove it. */
    private void drawDependencyRow(GuiRenderer r, Slot slot, String name, int mouseX, int mouseY) {
        if (slot == null) {
            return;
        }
        int y = slot.y() + 2;
        int height = Math.max(8, slot.height() - 4);
        BookGeometry.Rect locate = BookGeometry.Rect.at(slot.right() - 52, y, 18, height);
        BookGeometry.Rect nameBox = BookGeometry.Rect.at(slot.x(), y,
                Math.max(60, slot.width() - 74), height);
        boolean nameHover = nameBox.contains(mouseX, mouseY);
        if (nameHover) {
            // The name is a jump now, and the wash is the affordance: an edit box would say "type
            // here", which is what the add row underneath says.
            r.fill(nameBox.x() - 2, nameBox.y(), nameBox.right(), nameBox.bottom(),
                    ArmatureTheme.rowHover());
        }
        r.text(Measure.truncate(name, nameBox.width() - 8, textMeasure(r)), nameBox.x() + 2,
                y + (height - 8) / 2, ArmatureTheme.body());
        editTargets.add(new EditTarget(EditAction.NAVIGATE_DEP, name, nameBox, nameBox.x() + 2,
                y + (height - 8) / 2, "", null, -1));

        drawLocateIcon(r, locate.x() + locate.width() / 2, y + height / 2,
                locate.contains(mouseX, mouseY) ? ArmatureTheme.body() : ArmatureTheme.faint());
        editTargets.add(new EditTarget(EditAction.LOCATE_DEP, name, locate, locate.x(), locate.y(),
                "", null, -1));

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

    /**
     * The settings page: the node's shape, size, placement and rules, over the card's body.
     *
     * <p>Everything but the three text fields is drawn and hit-tested by {@code QuestSettingsPanel} and
     * {@code QuestSettingsLayout}; the fields are widgets, placed by the page's own layout through the
     * same viewport the drawing uses, which is what keeps a scrolled field beside its label.
     *
     * <p>The viewport is re-applied every frame with the column's own rectangle, and that is the fix
     * rather than tidiness: placement ran once at build time against whatever the geometry was then,
     * while the drawing runs against the geometry now — so the widgets drift off the page, some of them
     * outside it entirely. One bounds, one apply, every frame, the same rule the body uses.
     */
    private void drawSettings(GuiRenderer r, ClientQuestCache.Entry entry, int mouseX, int mouseY) {
        if (settingsLayout == null || entry == null) {
            return;
        }
        JsonObject quest = replicaQuest();
        dev.ellipog.tasked.client.dev.QuestSettingsLayout.Frame frame =
                dev.ellipog.tasked.client.dev.QuestSettingsLayout.Frame.of(overlayBodyRect());
        settingsView.viewport().bounds(frame.controls().x(), frame.controls().y(),
                frame.controls().width(), frame.controls().height());
        settingsView.apply(settingsLayout, frame.controls().width());
        dev.ellipog.tasked.client.dev.QuestSettingsPanel.draw(r, frame, settingsLayout, settingsRows,
                quest, settingsViewOf(entry, quest, mouseX, mouseY), settingsView.viewport(),
                mouseX, mouseY);
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

    /**
     * The hover description of a type in the picker: what it is for, the fields it will ask for, and the
     * id a file spells.
     *
     * <p>The fields line comes from the registry rather than the table, so a type the table does not name
     * still says what it takes -- and a type whose fields change says so without the table being touched.
     *
     * <p>The <b>author's</b> description, for the picker's buttons: field names and the id are what an
     * author edits, and the picker is a tool only edit mode reaches. A player's row hover is
     * {@link QuestPanelLayout#playerTooltip}, which shares none of these lines.
     */
    private static List<Component> typeTooltip(String member, String typeId) {
        List<Component> lines = new ArrayList<>();
        for (String line : QuestPanelLayout.typeTooltip(member, typeId)) {
            lines.add(Component.literal(line));
        }
        return List.copyOf(lines);
    }

    /** The rows the open type picker shows: the condition types when that is what is open, else the member's. */
    private List<InspectRow> pickerRows() {
        return pickingConditionFor != null
                ? QuestPanelLayout.conditionTypeRows()
                : QuestPanelLayout.typeRows(pickingEntryType);
    }

    /** A picker row's icon: the registered type's, from whichever of the three registries it came. */
    private ItemStack pickerIcon(String typeId) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(typeId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return pickingConditionFor != null
                ? dev.ellipog.tasked.quest.condition.ConditionTypes.iconOf(id).toStack()
                : typeIcon(pickingEntryType, typeId);
    }

    /** A picker row's hover: the author's description, from whichever registry the picker is over. */
    private List<Component> pickerTooltip(String typeId) {
        List<String> lines = pickingConditionFor != null
                ? QuestPanelLayout.conditionTypeTooltip(typeId)
                : QuestPanelLayout.typeTooltip(pickingEntryType, typeId);
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Component.literal(line));
        }
        return List.copyOf(out);
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
                    sendField(editTarget(), target.path(),
                            new JsonPrimitive(!flagOn(quest, target.path())));
                }
            }
            case FIELD, RAW -> openInlineEditor(target, mouseX, mouseY);
            // An item is picked, not typed: the text field is still there (it is the picker's search
            // box, and a whole id in it commits), but it is no longer the whole of how a field is set.
            case ITEM -> openItemPicker(target, false);
            case ADD_TASK -> openTypePicker("tasks");
            case ADD_REWARD -> openTypePicker("rewards");
            case CONDITION_ADD -> openConditionTypePicker(target.path());
            case CONDITION_REMOVE -> removeCondition(target.path());
            // The form's own controls: a nudge, a cycle, and the button that fills a box from where the
            // player stands. Each is one press and one op -- see the methods for what each one sends.
            case STEP_UP -> nudge(target, 1);
            case STEP_DOWN -> nudge(target, -1);
            case CYCLE_CHOICE -> cycleChoice(target);
            case USE_POSITION -> useMyPosition(target);
            case SEARCH -> openSearchPick(target, mouseX, mouseY);
            case TOGGLE_ENTRY -> toggleFold(target.member(), target.index());
            case ADD_DEP -> openInlineEditor(new EditTarget(EditAction.FIELD, InlineEdit.DEPENDENCY_ADD,
                    target.box(), target.textX(), target.textY(), "", null, -1), mouseX, mouseY);
            case PICK_DEP -> armDependencyPick();
            case REMOVE_DEP -> {
                if (quest != null && editTarget() != null) {
                    List<String> remaining = QuestPanelLayout.strings(quest, "dependsOn").stream()
                            .filter(each -> !each.equals(target.path())).toList();
                    sendField(editTarget(), "dependsOn", stringArray(remaining));
                }
            }
            // A prerequisite's name and its locate icon are the card's own navigation: the same two
            // the reader's rows carry, so both modes of the card teach one behaviour.
            case NAVIGATE_DEP -> navigateToQuest(target.path());
            case LOCATE_DEP -> locateOnCanvas(target.path());
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
        if (InlineEdit.inHeader(editingPath)) {
            // A header piece is not in the scroll: the title line does not move when the body scrolls, so
            // there is nothing to follow -- and following it would scroll the body *up* to chase a target
            // that sits above the body's top edge, which is a card that jumps when a title is clicked.
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
     * The region an inline editor lives in: the card's header band for the pieces the header carries,
     * the scrolling body for everything else.
     *
     * <p>Two regions, because the two halves of the card are two. The body scrolls under a fixed header,
     * and a field is kept alive and clipped against the one its target is drawn in. Asking the body about
     * a header target is what made the title and subtitle fields unopenable: their boxes sit above the
     * body's top edge, so from the frame the field opened it was judged off screen, stood down and
     * hidden -- clicking a title mark made the title disappear and nothing editable appear. See
     * {@link InlineEdit#inHeader}.
     */
    private BookGeometry.Rect inlineRegion(String path) {
        if (InlineEdit.inHeader(path)) {
            return BookGeometry.Rect.at(overlayLeft(), overlayTop(), overlayWidth(), BODY_TOP);
        }
        Viewport body = overlayBody();
        return BookGeometry.Rect.at(body.originX(), body.originY(), body.viewWidth(), body.viewHeight());
    }

    /**
     * The open field, drawn above the card and cut off at the edges of the region it belongs to.
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
     * <p><b>It is clipped to the region.</b> The field is drawn outside the content's clip -- it has to be,
     * it is chrome over the card -- so with its box following the prose through a scroll, an unclipped
     * field would draw its text over the header and the footer bar as the prose passes them. Clipping is
     * what lets the box be the prose's real rectangle at every scroll position, which is what keeps the
     * text in the field on the text underneath it: one arithmetic, cut by one edge. And a header field is
     * clipped to the header band rather than to the body -- a title field cut at the body's top edge would
     * be a field nobody could see, which is the same fault the region above is about.
     */
    private void drawOpenEditor(GuiRenderer r) {
        if (editingPath == null) {
            return;
        }
        BookGeometry.Rect region = inlineRegion(editingPath);
        try (GuiRenderer.Scoped clip = r.clip(region.x(), region.y(), region.right(), region.bottom())) {
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
            // On screen is a question about the box and the region the piece belongs to, not about the
            // target existing: the box follows the prose through a scroll now, so the only thing that
            // stands the field down is the whole of it being off its view -- where typing would be
            // typing blind. The region is the header for a header piece and the body for the rest; asking
            // the body about the title is what stood that field down on the frame it opened.
            BookGeometry.Rect region = inlineRegion(editingPath);
            boolean onScreen = box.bottom() > region.y() && box.y() < region.bottom();
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
                sendField(target, "description", null);
                return;
            }
            // Split back into paragraphs and drop the blank ones at the ends: a blank line at the end of
            // the prose is where the caret was, not content. A blank line *between* two paragraphs is a
            // paragraph break and is written as one. See `Prose`.
            List<String> paragraphs = Prose.trimmed(List.of(text.split("\n", -1)));
            sendField(target, "description", stringArray(paragraphs));
            return;
        }
        if (path.matches("(tasks|rewards)\\.\\d+(\\.conditions\\.\\d+)?")) {
            if (text == null || text.trim().isEmpty()) {
                status("An entry cannot be empty", true);
                return;
            }
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                sendField(target, path, parsed);
            }
            catch (RuntimeException malformed) {
                status("That is not JSON \u2014 " + malformed.getMessage(), true);
            }
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            sendField(target, path, null);
            return;
        }
        InspectField<?> field = QuestPanelLayout.fieldFor(replicaQuest(), path);
        InspectField.Result<?> result = field.parse(text.trim());
        if (!result.ok()) {
            status(result.error(), true);
            rebuildWidgets();
            return;
        }
        sendField(target, path, jsonOf(result.value()));
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
    /**
     * Opens the picker on a field whose ids are a list rather than an item stack: a dimension, a biome, a
     * statistic, an advancement.
     *
     * <p>The same card and the same rows as the item picker -- what changes is the catalogue and where the
     * "current" row's idea of known comes from. There is no inventory group and nothing to clear: a
     * dimension has no NBT and no "carried" to offer.
     */
    private void openSearchPicker(EditTarget target, EditorField field) {
        closeInlineEditor();
        pickingEntryType = null;
        settingsOpen = false;
        pickerFromSettings = false;
        pickTarget = PickTarget.QUEST;
        pickIcon = ItemStack.EMPTY;
        pickName = "";
        draggingSlider = null;
        settingsDraft.clear();
        pickingItemPath = target.path();
        JsonObject quest = replicaQuest();
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, target.path());
        pickingItemCurrent = current != null && current.isJsonPrimitive() ? current.getAsString() : "";
        pickingItemClearPath = null;
        pickerSource = field.source();
        // The observation task's own mode, read from the entry: its target field lists blocks for a block
        // observation and entities for an entity one, and the picker asks the same question the engine does.
        JsonObject entry = quest == null || target.index() < 0
                ? null
                : entryAt(quest, target.member(), target.index());
        pickerEntries = SearchCatalogue.list(field.source(),
                entry == null ? "" : rawValue(entry, "observeType"));
        pickerInventory = List.of();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        // A picker opens on an empty box. The box keeps its text across rebuilds -- that is what makes it
        // usable while typing -- but a query from the last field would filter this field's list, and the
        // first rows are what say what this one can hold.
        itemSearch = null;
        pickerSelected = -1;
        pickerScroll = 0;
        rebuildWidgets();
    }

    private void openItemPicker(EditTarget target, boolean returnToSettings) {
        closeInlineEditor();
        pickingEntryType = null;
        settingsOpen = false;
        pickerFromSettings = returnToSettings;
        // The card's picker, named as such: the dock's two icons are the other targets, and a stale one
        // left set would route this pick to a chapter's or a group's file.
        pickTarget = PickTarget.QUEST;
        pickIcon = ItemStack.EMPTY;
        pickName = "";
        draggingSlider = null;
        settingsDraft.clear();
        pickingItemPath = target.path();
        JsonObject quest = replicaQuest();
        JsonElement current = quest == null ? null : QuestPanelLayout.get(quest, target.path());
        pickingItemCurrent = current != null && current.isJsonPrimitive() ? current.getAsString() : "";
        pickingItemClearPath = ItemPicker.clearPath(target.path());
        // The item catalogue, and this pick is items: a search left set from the last one would list
        // biomes for a field that wants an item.
        pickerSource = null;
        pickerEntries = catalogue();
        pickerInventory = carried();
        pickerMatches = List.of();
        pickerRows = List.of();
        pickerFrame = null;
        pickerQuery = "";
        // An empty box, for the same reason the search picker gets one: the last field's query must not
        // filter this field's list.
        itemSearch = null;
        pickerSelected = -1;
        pickerScroll = 0;
        rebuildWidgets();
    }

    /** Closes it without committing, and forgets its list: the next open gathers a fresh one. */
    private void closeItemPicker() {
        // Back to the page it was opened from, before anything else looks at the state: the picker is
        // closed either way, and an author who pressed Change… on the settings page belongs there
        // afterwards rather than in the card's editor.
        if (pickerFromSettings) {
            pickerFromSettings = false;
            settingsOpen = true;
        }
        // The dock's picker has no card behind it to return to: its overlay *is* the picker, so closing
        // it puts the book back. Read before the target is cleared, because only a dock pick owns the
        // overlay -- a pick from the card closes back into the card.
        boolean ownOverlay = pickTarget != null && pickTarget != PickTarget.QUEST && overlay == Overlay.PICKER;
        pickTarget = null;
        pickIcon = ItemStack.EMPTY;
        pickName = "";
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
        if (ownOverlay) {
            overlay = Overlay.NONE;
        }
    }

    /** Leaves the dock's picker: the list closes and the book it was opened over comes back. */
    private void closePickerOverlay() {
        closeItemPicker();
        rebuildWidgets();
    }

    /** A press on a picker row: an item sets the field, the clear row removes it, a heading nothing. */
    private void pressPickerRow(int index) {
        if (index < 0 || index >= pickerRows.size()) {
            return;
        }
        ItemPickerLayout.Row row = pickerRows.get(index);
        if (!ItemPickerLayout.pickable(row)) {
            return;
        }
        if (row.kind() == ItemPickerLayout.Kind.CLEAR) {
            commitPicker(null);
            return;
        }
        if (row.kind() == ItemPickerLayout.Kind.MISSING && row.id().equals(pickingItemCurrent)) {
            // The field's own missing value, pressed: it is already what the field says, so this is
            // "keep it" -- the picker closes and no edit is spent saying nothing.
            closeItemPicker();
            rebuildWidgets();
            return;
        }
        commitPicker(row.id());
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
        if (row != null && (row.kind() == ItemPickerLayout.Kind.ITEM
                || row.kind() == ItemPickerLayout.Kind.MISSING)) {
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
        if (pickTarget != null && pickTarget != PickTarget.QUEST) {
            commitDockPicker(id);
            return;
        }
        String quest = editTarget();
        String path = pickingItemPath;
        String clearPath = pickingItemClearPath;
        String data = id == null ? "" : pickedDataOf(id);
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
            sendField(quest, clearPath, null);
            status("Cleared", false);
            rebuildWidgets();
            return;
        }
        // A pick is id **and** custom data, written as one edit of the object that holds both: a
        // task's or a reward's entry, or the quest's icon object. Two ops would be two saves, and a
        // save between them is a field that briefly holds the new item with the old data.
        JsonElement components = data.isEmpty()
                ? null : com.google.gson.JsonParser.parseString(data);
        if (("tasks".equals(memberOf(path)) || "rewards".equals(memberOf(path)))
                && path.endsWith(".item")) {
            // The object that holds item and components together: the entry itself, or -- for a
            // condition's item field -- the condition, which is the same shape one level down. Written
            // as one op either way, for the reason above: two ops would be two saves, and a save between
            // them is a field holding the new item with the old data.
            String objectPath = path.contains(".conditions.")
                    ? path.substring(0, path.length() - ".item".length())
                    : memberOf(path) + "." + indexOf(path);
            JsonElement found = QuestPanelLayout.get(replicaQuest(), objectPath);
            JsonObject rebuilt = found != null && found.isJsonObject()
                    ? found.getAsJsonObject().deepCopy() : new JsonObject();
            rebuilt.addProperty("item", id);
            if (components == null) {
                rebuilt.remove("components");
            }
            else {
                rebuilt.add("components", components);
            }
            sendField(quest, objectPath, rebuilt);
        }
        else if ("icon.item".equals(path)) {
            JsonObject icon = new JsonObject();
            JsonElement existing = QuestPanelLayout.get(replicaQuest(), "icon");
            if (existing != null && existing.isJsonObject()) {
                icon = existing.getAsJsonObject().deepCopy();
            }
            icon.addProperty("item", id);
            if (components == null) {
                icon.remove("components");
            }
            else {
                icon.add("components", components);
            }
            sendField(quest, "icon", icon);
        }
        else {
            sendField(quest, path, new JsonPrimitive(id));
        }
        status(data.isEmpty() ? "Set to " + id : "Set to " + id + " with its data", false);
        rebuildWidgets();
    }

    /**
     * The Chapter tab's pick: the chapter's icon becomes this id, or the icon object is removed.
     *
     * <p>The same shape as the quest icon's branch above -- the whole {@code icon} object is written in
     * one op, because item and components are one thing and two ops would leave a save between them
     * holding the new item with the old data -- but the op is {@code SetChapter} or {@code SetGroup},
     * and the object is built fresh rather than merged with what is there. Fresh is the honest shape for
     * an icon: the picker's choice is the item and its data, and carrying anything over from the old one
     * would be an edit that quietly kept half of a value the author just replaced.
     *
     * <p>The clear case sends null, which the model reads as "remove the field": an icon that is absent
     * is the default -- paper for a chapter, the first chapter's for a group -- not an empty item.
     */
    private void commitDockPicker(String id) {
        boolean group = pickTarget == PickTarget.GROUP;
        String data = id == null ? "" : pickedDataOf(id);
        closeItemPicker();
        if (!mayEditNow()) {
            rebuildWidgets();
            return;
        }
        if (id == null) {
            // The whole object, not its `item` member: both codecs read `icon` as an item reference
            // whose item is required, so an emptied member would be a file that will not load.
            if (group) {
                send(new EditorOp.SetGroup("icon", null));
            }
            else {
                sendChapterField("icon", null);
            }
            status("Cleared", false);
            rebuildWidgets();
            return;
        }
        JsonElement components = data.isEmpty()
                ? null : com.google.gson.JsonParser.parseString(data);
        JsonObject icon = new JsonObject();
        icon.addProperty("item", id);
        if (components != null) {
            icon.add("components", components);
        }
        if (group) {
            send(new EditorOp.SetGroup("icon", icon));
        }
        else {
            sendChapterField("icon", icon);
        }
        status(data.isEmpty() ? "Set to " + id : "Set to " + id + " with its data", false);
        rebuildWidgets();
    }

    /** The member a dotted field path starts with, and the index it names -- for entry rebuilds. */
    private static String memberOf(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static int indexOf(String path) {
        String[] parts = path.split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException notAnEntry) {
            return -1;
        }
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
        pickerMatches = pickerSource != null && query.isBlank()
                // A search field opens on the first ten of its list rather than on nothing: the list is
                // what says what the field can hold, and a picker that shows an empty box until you type
                // reads as one that found nothing. Typing ranks exactly as it does for items.
                ? pickerEntries.stream().limit(FIRST_ROWS).toList()
                : ItemPicker.rank(pickerEntries, query, ItemPicker.LIMIT);
        String typedCandidate = ItemPicker.missingCandidate(query, pickerMatches);
        // What "the current value is real" means depends on what is being listed: an item is known when
        // the build has it, and a registry id is known when it parses -- there is no stack to look up.
        boolean currentKnown = pickingItemCurrent.isEmpty()
                || (pickerSource != null
                        ? net.minecraft.resources.ResourceLocation.tryParse(pickingItemCurrent) != null
                        : !itemStack(pickingItemCurrent).isEmpty());
        pickerRows = ItemPickerLayout.compose(pickerInventory, pickerMatches,
                new ItemPickerLayout.Current(pickingItemCurrent, currentKnown,
                        pickingItemClearPath != null),
                typedCandidate, query,
                // The item picker's own rule for its results (null), and the search field's own words for
                // its list -- see ItemPickerLayout#compose.
                pickerSource == null ? null : EditorSpecs.label(pickerSource.name()));
        pickerScroll = Math.max(0,
                Math.min(pickerScroll, ItemPickerLayout.maxScroll(pickerRows, pickerFrame)));
        // `-1` is "nothing chosen yet", and it stays that way until the author chooses. A selection
        // invented here would be Enter's answer on a picker nobody has touched -- and the first pickable
        // row is often Clear, so that answer would quietly empty the field. `clamp` re-lands a real
        // selection when the list changes size, and answers -1 when nothing is pickable, which is the
        // same honest nothing. See `commitFromPicker` for what Enter does with it.
        if (pickerSelected >= 0) {
            pickerSelected = ItemPickerLayout.clamp(pickerRows, pickerSelected);
        }

        // **Clipped to the list, not to the body.** A row half scrolled under the search box was
        // painting into it -- and an item icon draws at its own depth, so it showed *through* the
        // box's fill. A row's edge belongs at the box's edge, which is where the list starts.
        try (GuiRenderer.Scoped clip = r.clip(pickerFrame.list().x(), pickerFrame.list().y(),
                pickerFrame.list().right(), pickerFrame.list().bottom())) {
            if (pickerRows.isEmpty()) {
                r.text(pickerSource == null
                                ? "Type to search every item, or pick something you carry."
                                : "Type to search what this build has, or an id it does not -- "
                                        + "pressing the row that appears keeps it.",
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
                boolean missing = row.kind() == ItemPickerLayout.Kind.MISSING;
                if (row.kind() == ItemPickerLayout.Kind.ITEM) {
                    r.icon(itemStack(row.id()), rect.x() + 1, rect.y() + 1,
                            Math.max(8, rect.height() - 2));
                    textX = rect.x() + 20;
                }
                else if (missing) {
                    // No item behind the id: a placeholder where the icon would be, and the note that
                    // says so. The id itself is the label, so it is kept and visible either way.
                    drawItemPlaceholder(r, rect.x() + 1, rect.y() + 1, Math.max(8, rect.height() - 2));
                    textX = rect.x() + 20;
                }
                boolean blocked = missing || row.kind() == ItemPickerLayout.Kind.CLEAR;
                r.text(Measure.truncate(row.label(), rect.width() - (textX - rect.x()) - 30, measure),
                        textX, rect.y() + (rect.height() - 8) / 2,
                        blocked ? ArmatureTheme.blocked() : ArmatureTheme.body());
                if (!row.secondary().isEmpty()) {
                    r.text(row.secondary(), rect.right() - 4 - r.textWidth(row.secondary()),
                            rect.y() + (rect.height() - 8) / 2,
                            blocked ? ArmatureTheme.blocked() : ArmatureTheme.faint());
                }
            }
        }
    }

    /**
     * A stand-in for an item this build cannot draw: a recessed box and a question mark.
     *
     * <p>For an id with no item behind it -- a mod that is not installed, an item that was removed.
     * The id is kept and drawn beside this, so the placeholder is what stops "cannot draw it" from
     * reading as "there is nothing here".
     */
    private static void drawItemPlaceholder(GuiRenderer r, int x, int y, int box) {
        r.fill(x + 1, y + 1, x + box - 1, y + box - 1, ArmatureTheme.recessed());
        r.text("?", x + (box - r.textWidth("?")) / 2, y + (box - 8) / 2, ArmatureTheme.blocked());
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
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                    .toString();
            counts.merge(id, stack.getCount(), Integer::sum);
            labels.putIfAbsent(id, stack.getHoverName().getString());
            // The first stack's custom data wins a dedupe: two stacks of one item are one row, and the
            // row's data is the thing a pick would copy -- showing one and copying another would make
            // the list lie about what pressing it does.
            data.putIfAbsent(id, componentsText(stack));
        }
        List<ItemPicker.Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> each : counts.entrySet()) {
            out.add(new ItemPicker.Entry(each.getKey(), labels.get(each.getKey()), each.getValue(),
                    data.get(each.getKey())));
        }
        return List.copyOf(out);
    }

    /**
     * A stack's component patch as the JSON text an entry carries, or empty when there is nothing
     * custom about it. The same codec and the same spelling the quest file uses.
     */
    private static String componentsText(ItemStack stack) {
        var patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return "";
        }
        return net.minecraft.core.component.DataComponentPatch.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, patch)
                .result().map(Object::toString).orElse("");
    }

    /** The picked entry's custom data, from what the player carries. Unknown ids have none. */
    private String pickedDataOf(String id) {
        for (ItemPicker.Entry entry : pickerInventory) {
            if (entry.id().equals(id)) {
                return entry.data();
            }
        }
        return "";
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
            // The pending order, not the replica's: two reorders inside the replica window would
            // otherwise compute the second from the first's stale order and drop it.
            JsonObject chapter = fieldDraft.overlaid(effectiveChapter(),
                    dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                    ClientChapterReplica.chapterTree(effectiveChapter()));
            List<String> names = new ArrayList<>(QuestPanelLayout.strings(chapter, "quests"));
            if (from >= names.size() || to >= names.size() || to < 0) {
                return;
            }
            names.add(to, names.remove(from));
            sendChapterField("quests", stringArray(names));
        }
        else {
            if (quest == null) {
                return;
            }
            // The entries are about to shift, so a pending value indexed inside the list no longer
            // names the entry the author was editing.
            fieldDraft.forgetList(effectiveChapter(), quest, member);
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
    // ------------------------------------------------------------------
    // The sidebar tree drag
    // ------------------------------------------------------------------

    /**
     * Reads a press that landed on a sidebar row, without consuming it.
     *
     * <p>Before the widget pass, because a row is a button and a button takes the press it is given: the
     * screen would never see it again, and a drag that cannot start is a click. Not consuming it is the
     * other half: if the pointer never travels the row is still clicked, and the button's own release is
     * what does that, exactly as before this gesture existed.
     */
    private void rememberSidebarPress(double mouseX, double mouseY, int button) {
        if (button != 0 || overlay != Overlay.NONE || !mayEditNow()
                || !sidebarViewport().containsScreen(mouseX, mouseY)) {
            return;
        }
        for (SidebarLayout.Row row : sidebar().rows()) {
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect != null && rect.contains(mouseX, mouseY)) {
                sidebarDragKey = row.key();
                sidebarDragGroup = row.group();
                sidebarDragLive = false;
                sidebarDragPointerY = mouseY;
                sidebarDragPressX = mouseX;
                sidebarDragPressY = mouseY;
                sidebarDrop = null;
                return;
            }
        }
    }

    /** The visible rows as the drop arithmetic wants them: key, kind, container and where they are. */
    private List<SidebarDrag.Row> sidebarRows() {
        List<SidebarDrag.Row> rows = new ArrayList<>();
        String heading = "";
        for (SidebarLayout.Row row : sidebar().rows()) {
            if (row.group()) {
                heading = row.key();
            }
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect == null) {
                continue;
            }
            // The true container, from the layout rather than from a walk back to the nearest heading:
            // a chapter at depth 0 is a root chapter wherever it is drawn, and every group is drawn
            // before the root chapters -- so the nearest heading above one is not its parent, which is
            // what made a drop on the last group read as a no-op and send nothing.
            String container = row.group() || row.depth() == 0 ? "" : heading;
            rows.add(new SidebarDrag.Row(row.key(), row.group(), container, rect));
        }
        return rows;
    }

    /**
     * Where a row is on screen, from the view that placed it.
     *
     * <p>From the {@code ScrollView} rather than from the layout, because the rows scroll: the slot the
     * view holds is the one the widget was moved to this frame, which is the only rectangle a pointer
     * can be tested against. Null for a row scrolled out of view, which is a row nothing can be dropped
     * on — and it is absent from the list rather than clamped, so a drop at an edge lands where the
     * rows actually are.
     */
    private BookGeometry.Rect placedRect(String key) {
        var slot = sidebarView.placedSlot(key);
        return slot == null ? null
                : BookGeometry.Rect.at(slot.x(), slot.y(), slot.width(), slot.height());
    }

    /**
     * Sends the op a released tree drag asked for.
     *
     * <p>Everything that could make the drop meaningless is decided here, once: a drop that changes
     * nothing sends nothing (the server would rewrite a manifest to say the same thing and broadcast
     * the tree for it), and a group only ever moves at the root — {@link SidebarDrag} refuses the
     * nesting, and this refuses to send one.
     */
    private void commitSidebarDrop(String key, boolean group, SidebarDrag.Drop drop) {
        if (!mayEditNow() || drop == null || drop instanceof SidebarDrag.Drop.Nowhere) {
            return;
        }
        List<SidebarDrag.Row> rows = sidebarRows();
        if (SidebarDrag.isNoOp(rows, key, drop)) {
            return;
        }
        String id = SidebarLayout.idOf(key);
        if (group) {
            if (drop instanceof SidebarDrag.Drop.Insert insert) {
                send(new EditorOp.MoveGroup(id, insert.index()));
            }
            return;
        }
        switch (drop) {
            case SidebarDrag.Drop.Insert insert -> send(new EditorOp.MoveChapter(id,
                    SidebarLayout.isGroupKey(insert.containerKey())
                            ? SidebarLayout.idOf(insert.containerKey()) : "",
                    insert.index()));
            case SidebarDrag.Drop.Into into -> send(new EditorOp.MoveChapter(id,
                    SidebarLayout.idOf(into.groupKey()),
                    // Into a group takes the end of its list, which is what the highlight promises: the
                    // heading has no seam to aim at, and "somewhere in this group" is the answer.
                    SidebarDrag.childCount(rows, into.groupKey())));
            case SidebarDrag.Drop.Nowhere ignored -> {
            }
        }
    }

    /**
     * Draws what the drag is about to do: a seam line at an insert, a ring around a group it is over.
     *
     * <p>Inside the list's clip, after the widget pass, so it is above the rows it describes and cannot
     * reach the header. The two marks are the two answers, and they are different shapes on purpose: a
     * line says "between these", a ring says "inside this", and drawing both the same way would leave
     * the reader to guess which of the two places the row is going.
     */
    private void drawSidebarDrag(GuiRenderer r) {
        if (!sidebarDragLive || sidebarDrop == null) {
            return;
        }
        List<SidebarDrag.Row> rows = sidebarRows();
        switch (sidebarDrop) {
            case SidebarDrag.Drop.Insert insert -> {
                int y = SidebarDrag.indicatorY(rows, insert);
                if (y < 0 || rows.isEmpty()) {
                    return;
                }
                BookGeometry.Rect first = rows.get(0).rect();
                r.fill(first.x() - 2, y - 1, first.right() + 2, y + 2, ArmatureTheme.hoverRing());
            }
            case SidebarDrag.Drop.Into into -> {
                for (SidebarDrag.Row row : rows) {
                    if (row.key().equals(into.groupKey())) {
                        BookGeometry.Rect rect = row.rect();
                        r.fill(rect.x() - 1, rect.y() - 1, rect.right() + 1, rect.bottom() + 1,
                                ArmatureTheme.selectedRing());
                        return;
                    }
                }
            }
            case SidebarDrag.Drop.Nowhere ignored -> {
            }
        }
    }

    /**
     * The chapter rows' reward counts, right-aligned in each row: "(3)" where three quests have
     * something waiting.
     *
     * <p>Right-aligned rather than appended to the row's label, and that is a correctness point rather
     * than taste: the label is truncated from its end, so on a long chapter name the count would be the
     * first thing cut — and it is the one part that must never be lost.
     *
     * <p>Only chapters with something waiting, and never groups: a group is a heading, and "(0)" is a
     * number nobody needs.
     */
    private void drawSidebarRewardCounts(GuiRenderer r, int mouseX, int mouseY) {
        Map<String, Integer> counts = rewardCounts().byChapter();
        SidebarLayout layout = sidebar();
        if (counts.isEmpty() || layout == null) {
            return;
        }
        for (SidebarLayout.Row row : layout.rows()) {
            Integer count = counts.get(row.id());
            if (row.group() || count == null) {
                continue;
            }
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect == null) {
                continue;
            }
            String text = "(" + count + ")";
            // The chapter's own accent — the same token its node badges wear — rather than the row's
            // chrome ink. The count is the chapter's mark, so it follows the chapter's palette even
            // though the row it sits in is the book's own: a themed chapter's "(3)" is the colour of
            // the coins on its canvas, and the two read as one feature. The row's states still speak:
            // hover and selection brighten it rather than recolouring it, which is what a mark with a
            // colour of its own can do.
            int accent = themeFor(row.id()).inProgress();
            boolean selected = row.id().equals(effectiveChapter());
            int colour = selected ? Colour.shade(accent, 0.35F)
                    : rect.contains(mouseX, mouseY) ? Colour.shade(accent, 0.15F) : accent;
            r.text(text, rect.right() - 4 - r.textWidth(text),
                    rect.y() + (rect.height() - 8) / 2, colour);
        }
    }

    /**
     * The reward badges: a small mark pinned to each node's outline while that quest has rewards this
     * player has not collected.
     *
     * <p>Drawn on the canvas and therefore inside the chapter's theme scope, so the badge is already
     * the chapter's own colour: a themed chapter's badges take its {@code inProgress}, not the
     * player's. The count comes from one walk per revision (see {@link #rewardCounts}), and the
     * badge's own art and anchoring are {@link RewardBadge}'s.
     */
    private void drawRewardBadges(GuiRenderer r, List<ClientQuestCache.Entry> visible) {
        Map<String, Integer> waiting = rewardCounts().byQuest();
        if (waiting.isEmpty()) {
            return;
        }
        for (ClientQuestCache.Entry quest : visible) {
            Integer count = waiting.get(quest.id());
            if (count == null) {
                continue;
            }
            // The badge anchors itself to this node's own outline and shrinks with it; the ring is a
            // darker shade of the badge's own colour rather than a theme edge, because a grey outline
            // around a gold disc reads as two unrelated things.
            int fill = ArmatureTheme.inProgress();
            RewardBadge.draw(r, quest.geometry(), nodeScreenX(quest), nodeScreenY(quest), nodeSize(quest),
                    count, fill, Colour.shade(fill, -0.45F), ArmatureTheme.canvas());
        }
    }

    /**
     * Scrolls the sidebar while a drag is held near one of its edges.
     *
     * <p>One row's pitch a tick, which is the same step a wheel notch takes — the list moves the way it
     * moves under the wheel, so a reader who has scrolled this list before can predict this. The
     * margin is small: the top and bottom of the viewport are where the pointer goes when the row it is
     * carrying has somewhere further to be.
     */
    private void autoScrollSidebar() {
        Viewport view = sidebarViewport();
        int margin = 12;
        int step = SidebarLayout.pitch();
        if (sidebarDragPointerY < view.originY() + margin) {
            sidebarView.scrollBy(-step);
        }
        else if (sidebarDragPointerY > view.viewBottom() - margin) {
            sidebarView.scrollBy(step);
        }
    }

    // ------------------------------------------------------------------
    // The naming card, the toolbar, and the sidebar's menu
    // ------------------------------------------------------------------

    private void openNaming(String kind, NamingMode mode, String targetId, String groupId, int index) {
        closeMenu();
        naming = new Naming(kind, mode, targetId, groupId, index);
        overlay = Overlay.NAMING;
        rebuildWidgets();
    }

    private void closeNaming() {
        naming = null;
        closeOverlay();
    }

    /** The two fields' rectangles: one derivation for the widgets and for the drawing. */
    private BookGeometry.Rect[] namingFieldRects() {
        BookGeometry.Rect card = geometry().modal();
        int left = card.x() + 14;
        int width = Math.max(60, card.width() - 28);
        int top = card.y() + 44;
        return new BookGeometry.Rect[] {
                BookGeometry.Rect.at(left, top, width, 20),
                BookGeometry.Rect.at(left, top + 30, width, 20) };
    }

    private String namingKindWord() {
        return naming != null && naming.kind().equals("group") ? "group" : "chapter";
    }

    /** The title the thing being named already has, for the card to open on. */
    private String namingExistingTitle() {
        if (naming == null || naming.targetId() == null) {
            return "";
        }
        if (naming.kind().equals("group")) {
            for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
                if (group.id().equals(naming.targetId())) {
                    return group.title();
                }
            }
            return "";
        }
        return chapters().getOrDefault(naming.targetId(), "");
    }

    /** Every id of the kind being named: what the collision check is against. */
    private List<String> namingTakenIds() {
        List<String> taken = new ArrayList<>();
        if (naming == null) {
            return taken;
        }
        if (naming.kind().equals("group")) {
            for (ClientQuestCache.GroupEntry group : ClientQuestCache.groups()) {
                taken.add(group.id());
            }
        }
        else {
            taken.addAll(chapters().keySet());
        }
        return taken;
    }

    /** Where the thing being named will live, as a path an author can find. */
    private String namingFolderPath(String id) {
        if (naming == null || id.isBlank()) {
            return "";
        }
        if (naming.kind().equals("group")) {
            return id + "/group.json";
        }
        String group = naming.mode() == NamingMode.CREATE
                ? naming.groupId() : groupOfChapter(naming.targetId());
        return group == null || group.isEmpty()
                ? id + "/chapter.json" : group + "/" + id + "/chapter.json";
    }

    /** The group a chapter is in, from the cache, or empty. */
    private static String groupOfChapter(String chapterId) {
        if (chapterId == null) {
            return "";
        }
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.id().equals(chapterId)) {
                return chapter.groupId();
            }
        }
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterGroupId();
            }
        }
        return "";
    }

    private void buildNamingWidgets() {
        if (naming == null) {
            namingTitle = null;
            namingId = null;
            return;
        }
        BookGeometry.Rect[] fields = namingFieldRects();
        String title = naming.mode() == NamingMode.CREATE ? "" : namingExistingTitle();
        String id = switch (naming.mode()) {
            case CREATE -> ChapterNaming.suggested(
                    naming.kind().equals("group") ? "new_group" : "new_chapter", "", namingTakenIds());
            case RENAME -> naming.targetId();
            case DUPLICATE -> ChapterNaming.suggested(naming.targetId(), "_copy", namingTakenIds());
        };
        namingTitle = new ArmatureTextField(fields[0].x(), fields[0].y(), fields[0].width(),
                fields[0].height(), title);
        namingId = new ArmatureTextField(fields[1].x(), fields[1].y(), fields[1].width(),
                fields[1].height(), id);
        // Enter and Escape are the screen's while this card is open -- see `keyPressed` -- so a blur
        // must commit nothing.
        namingTitle.onSubmit(text -> { });
        namingId.onSubmit(text -> { });
        addRenderableWidget(namingTitle);
        addRenderableWidget(namingId);
        BookGeometry.Rect card = geometry().modal();
        for (ArmatureTextField field : List.of(namingTitle, namingId)) {
            // Redrawn after the card and clipped to it: the widget pass runs before the card is painted,
            // so a field left to that pass would be painted over -- the ordering every field in this book
            // carries the same redraw for.
            modalRedraws.add(r -> {
                try (GuiRenderer.Scoped clip = r.clip(card.x(), card.y(), card.right(), card.bottom())) {
                    field.render(r);
                }
            });
        }
        Map<String, BookGeometry.Rect> controls = geometry().overlayControls(true);
        BookGeometry.Rect back = controls.get("back");
        if (back != null) {
            control(back, Component.literal("Cancel"), this::closeNaming)
                    .ink(ArmatureButton.Ink.BODY);
        }
        BookGeometry.Rect primary = controls.get("submit");
        if (primary != null) {
            control(primary, Component.literal(switch (naming.mode()) {
                case CREATE -> "Create";
                case RENAME -> "Rename";
                case DUPLICATE -> "Duplicate";
            }), this::submitNaming).accent(true);
        }
        setFocused(namingId);
    }

    /** Sends the op the card is for, or says why it cannot. */
    private void submitNaming() {
        if (naming == null || namingId == null) {
            return;
        }
        String id = namingId.value().trim();
        String title = namingTitle == null ? "" : namingTitle.value().trim();
        String problem = ChapterNaming.problemWith(id, namingTakenIds());
        if (problem != null) {
            status(problem, true);
            return;
        }
        String label = title.isEmpty() ? null : title;
        switch (naming.mode()) {
            case CREATE -> {
                if (naming.kind().equals("group")) {
                    send(new EditorOp.CreateGroup(id, label));
                }
                else {
                    send(new EditorOp.CreateChapter(naming.groupId(), naming.index(), id, label));
                    selectChapter(id);
                }
            }
            case RENAME -> {
                if (naming.kind().equals("group")) {
                    send(new EditorOp.RenameGroup(naming.targetId(), id, label));
                }
                else {
                    send(new EditorOp.RenameChapter(naming.targetId(), id, label));
                    selectChapter(id);
                }
            }
            case DUPLICATE -> {
                if (naming.kind().equals("group")) {
                    send(new EditorOp.DuplicateGroup(naming.targetId(), id, label));
                }
                else {
                    send(new EditorOp.DuplicateChapter(naming.targetId(), id, label));
                    selectChapter(id);
                }
            }
        }
        closeNaming();
    }

    /** The card itself: heading, field labels, and the live verdict on the id. */
    private void drawNamingOverlay(GuiRenderer r) {
        BookGeometry.Rect card = geometry().modal();
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        String heading = switch (naming.mode()) {
            case CREATE -> "New " + namingKindWord();
            case RENAME -> "Rename " + namingKindWord();
            case DUPLICATE -> "Duplicate " + namingKindWord();
        };
        r.text(heading, card.x() + 14, card.y() + 12, ArmatureTheme.title());
        BookGeometry.Rect[] fields = namingFieldRects();
        r.text("Title", fields[0].x(), fields[0].y() - 10, ArmatureTheme.body());
        r.text("Id - becomes the folder name", fields[1].x(), fields[1].y() - 10, ArmatureTheme.body());
        String id = namingId == null ? "" : namingId.value().trim();
        String problem = ChapterNaming.problemWith(id, namingTakenIds());
        r.text(problem == null ? namingFolderPath(id) : problem,
                fields[1].x(), fields[1].bottom() + 8,
                problem == null ? ArmatureTheme.faint() : ArmatureTheme.blocked());
    }

    /** The two add buttons above the list. Built wherever the sidebar is, drawn only for an author. */
    private void buildSidebarToolbar() {
        Map<String, BookGeometry.Rect> toolbar = geometry().sidebarToolbar();
        BookGeometry.Rect addChapter = toolbar.get("addChapter");
        if (addChapter != null) {
            ArmatureButton button = control(addChapter, Component.literal("+ Chapter"),
                    this::newChapterFromToolbar);
            button.ink(ArmatureButton.Ink.BODY).flat(true).tooltip(
                    Component.literal("Add a chapter to the selected group"));
            button.visible = mayEditNow();
        }
        BookGeometry.Rect addGroup = toolbar.get("addGroup");
        if (addGroup != null) {
            ArmatureButton button = control(addGroup, Component.literal("+ Group"),
                    this::newGroupFromToolbar);
            button.ink(ArmatureButton.Ink.BODY).flat(true).tooltip(
                    Component.literal("Add a group at the end of the list"));
            button.visible = mayEditNow();
        }
    }

    private void newChapterFromToolbar() {
        if (!mayEditNow()) {
            return;
        }
        // Ungrouped, always: this button and its shortcut make "a chapter", and the group it should live
        // in is what the group's own menu is for. Choosing the selected chapter's group was a guess that
        // put new chapters somewhere the author did not ask for.
        openNaming("chapter", NamingMode.CREATE, "", "", Integer.MAX_VALUE);
    }

    private void newGroupFromToolbar() {
        if (!mayEditNow()) {
            return;
        }
        openNaming("group", NamingMode.CREATE, "", "", 0);
    }

    /**
     * One line of a right-click menu.
     *
     * @param action  what a press runs, or null for a row that is not pressable (a flyout parent, or
     *                the "…" row a capped submenu ends with)
     * @param submenu whether hovering this row opens the flyout beside it
     */
    private record MenuItem(String label, Runnable action, boolean danger, List<MenuFlyout.Row> children) {

        static MenuItem of(String label, Runnable action) {
            return new MenuItem(label, action, false, List.of());
        }

        static MenuItem destructive(String label, Runnable action) {
            return new MenuItem(label, action, true, List.of());
        }

        /** A row that opens the flyout beside the menu. Its rows are built with it, not looked up. */
        static MenuItem parent(String label, List<MenuFlyout.Row> children) {
            return new MenuItem(label, null, false, List.copyOf(children));
        }

        boolean submenu() {
            return !children.isEmpty();
        }
    }

    /** The rows of a menu row's flyout, for a caller holding only the row's index. */
    private List<MenuFlyout.Row> childrenOf(int row) {
        return row >= 0 && row < menu.size() ? menu.get(row).children() : List.of();
    }

    private static final int MENU_ROW_HEIGHT = 16;
    private static final int MENU_WIDTH = 150;

    /** Opens the menu for whatever the pointer is on: a chapter, a group, or the empty list. */
    private boolean openSidebarMenuAt(double mouseX, double mouseY) {
        if (!sidebarViewport().containsScreen(mouseX, mouseY)) {
            return false;
        }
        for (SidebarLayout.Row row : sidebar().rows()) {
            BookGeometry.Rect rect = placedRect(row.key());
            if (rect == null || !rect.contains(mouseX, mouseY)) {
                continue;
            }
            menu = row.group() ? groupMenu(row.id()) : chapterMenu(row.id());
            // Only a chapter menu has a "Move to" submenu, so only then is there a chapter to move.
            menuChapter = row.group() ? null : row.id();
            menuX = (int) mouseX;
            menuY = (int) mouseY;
            return true;
        }
        menu = List.of(
                MenuItem.of("New chapter...", () -> openNaming("chapter", NamingMode.CREATE,
                        "", "", Integer.MAX_VALUE)),
                MenuItem.of("New group...",
                        () -> openNaming("group", NamingMode.CREATE, "", "", 0)));
        menuChapter = null;
        menuX = (int) mouseX;
        menuY = (int) mouseY;
        return true;
    }

    private List<MenuItem> chapterMenu(String id) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("Open", () -> {
            selectChapter(id);
            rebuildWidgets();
        }));
        items.add(MenuItem.of("Rename...",
                () -> openNaming("chapter", NamingMode.RENAME, id, "", 0)));
        items.add(MenuItem.of("Duplicate...",
                () -> openNaming("chapter", NamingMode.DUPLICATE, id, "", 0)));
        // One row, and the groups appear beside it on hover: a flat list of every destination is what
        // made this menu taller than the window, and a menu whose last entries are clipped away is a
        // destination that cannot be reached at all.
        items.add(MenuItem.parent("Move to \u203a", moveToItems(id)));
        // The label and the action are chosen together, from the arming state this menu was built in.
        // The first version tested the flag inside the action instead -- and the press that runs an
        // action closes the menu first, which clears the flag, so "Really delete?" re-armed forever and
        // never deleted. A menu built armed has one job; a menu built unarmed has the other.
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + chapterDeleteNote(id) : "Delete",
                armed
                        ? () -> send(new EditorOp.DeleteChapter(id))
                        : () -> armDelete(() -> menu = chapterMenu(id), id)));
        return items;
    }

    private List<MenuItem> groupMenu(String id) {
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("New chapter here",
                () -> openNaming("chapter", NamingMode.CREATE, "", id, Integer.MAX_VALUE)));
        items.add(MenuItem.of("Rename...",
                () -> openNaming("group", NamingMode.RENAME, id, "", 0)));
        items.add(MenuItem.of("Duplicate...",
                () -> openNaming("group", NamingMode.DUPLICATE, id, "", 0)));
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + groupDeleteNote(id) : "Delete",
                armed
                        ? () -> send(new EditorOp.DeleteGroup(id))
                        : () -> armDelete(() -> menu = groupMenu(id), null)));
        return items;
    }

    /**
     * Arms the menu's Delete and re-labels it in place.
     *
     * <p>The menu is closed by the press before its action runs — that is how every other row behaves —
     * so arming puts a rebuilt menu back where the old one was, anchored at the same point. The chapter
     * is re-established too, or the "Move to" submenu would come back empty on a rebuilt chapter menu.
     */
    private void armDelete(Runnable rebuild, String chapterId) {
        menuDeleteArmed = true;
        menuChapter = chapterId;
        rebuild.run();
    }

    /** How many quests the chapter holds, and how many elsewhere depend on them. */
    private static String chapterDeleteNote(String chapterId) {
        Set<String> ids = new HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterId().equals(chapterId)) {
                ids.add(entry.id());
            }
        }
        int dependents = 0;
        if (!ids.isEmpty()) {
            for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
                if (entry.chapterId().equals(chapterId)) {
                    continue;
                }
                for (String dependency : dependenciesOf(entry)) {
                    if (ids.contains(dependency)) {
                        dependents++;
                        break;
                    }
                }
            }
        }
        return note(ids.size(), "quest", dependents, "dependent");
    }

    /** How many chapters a group holds, and how many quests they hold between them. */
    private static String groupDeleteNote(String groupId) {
        Set<String> chapters = new HashSet<>();
        for (ClientQuestCache.ChapterEntry chapter : ClientQuestCache.chapters()) {
            if (chapter.groupId().equals(groupId)) {
                chapters.add(chapter.id());
            }
        }
        int quests = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (entry.chapterGroupId().equals(groupId)) {
                quests++;
                chapters.add(entry.chapterId());
            }
        }
        return note(chapters.size(), "chapter", quests, "quest");
    }

    /** "(3 quests, 1 dependent)", or empty when there is nothing to warn about. */
    private static String note(int first, String firstWord, int second, String secondWord) {
        if (first == 0 && second == 0) {
            return "";
        }
        StringBuilder note = new StringBuilder(" (");
        if (first > 0) {
            note.append(first).append(' ').append(firstWord).append(first == 1 ? "" : "s");
        }
        if (second > 0) {
            if (first > 0) {
                note.append(", ");
            }
            note.append(second).append(' ').append(secondWord).append(second == 1 ? "" : "s");
        }
        return note.append(')').toString();
    }

    /**
     * The destinations for one chapter: no group, then every other group.
     *
     * <p>Longer than fits is handled here rather than at the drawing: the list is cut to the rows the
     * panel can show and a final unpressable "…" says the rest exist, so what is drawn is always exactly
     * what can be pressed and the panel never runs past the bottom of the book.
     */
    private List<MenuFlyout.Row> moveToItems(String id) {
        List<MenuFlyout.Row> items = new ArrayList<>();
        String current = groupOfChapter(id);
        if (!current.isEmpty()) {
            items.add(MenuFlyout.text("No group",
                    () -> send(new EditorOp.MoveChapter(id, "", Integer.MAX_VALUE))));
        }
        for (ClientQuestCache.GroupEntry entry : ClientQuestCache.groups()) {
            if (entry.id().equals(current)) {
                continue;
            }
            items.add(MenuFlyout.text("Move to " + entry.title(),
                    () -> send(new EditorOp.MoveChapter(id, entry.id(), Integer.MAX_VALUE))));
        }
        int room = Math.max(0, (panelRect().height() - 16) / MENU_ROW_HEIGHT - 2);
        if (items.size() > room && room >= 2) {
            List<MenuFlyout.Row> cut = new ArrayList<>(items.subList(0, room - 1));
            cut.add(MenuFlyout.text("\u2026", null));
            return cut;
        }
        return items;
    }

    private void closeMenu() {
        menu = List.of();
        menuChapter = null;
        submenuRow = -1;
        menuDeleteArmed = false;
        canvasMenuQuest = null;
        canvasMenuFrom = null;
        canvasMenuTo = null;
    }

    /** Every item's rectangle, from the one derivation the drawing and the press both read. */
    private List<BookGeometry.Rect> menuRects() {
        List<BookGeometry.Rect> rects = new ArrayList<>();
        int x = menuLeft();
        int y = menuTop();
        for (int i = 0; i < menu.size(); i++) {
            rects.add(BookGeometry.Rect.at(x, y + i * MENU_ROW_HEIGHT, MENU_WIDTH, MENU_ROW_HEIGHT));
        }
        return rects;
    }

    /**
     * The open flyout, placed: one derivation the drawing, the presses and the hover's bridge all read.
     *
     * <p>Beside the menu when there is room and on its other side when there is not, and its top pulled
     * up until it fits above the book's bottom edge. The top is also held below the chapter list's top,
     * because the flyout is drawn inside that clip band and the preview rows are taller than any text
     * submenu was — a tall panel anchored near the header is the one thing that could be eaten by it.
     */
    private MenuFlyout.Placed submenuPlacement(int parentIndex) {
        List<MenuFlyout.Row> rows = childrenOf(parentIndex);
        if (parentIndex < 0 || parentIndex >= menu.size() || rows.isEmpty()) {
            return MenuFlyout.place(0, 0, List.of());
        }
        BookGeometry.Rect main = menuRects().get(parentIndex);
        int width = MenuFlyout.width(rows);
        int height = MenuFlyout.height(rows);
        int x = MenuPlacement.submenuX(menuPlacement(), width, 4);
        int top = Math.max(panelRect().y() + 4, geometry().chapterListTop() + 4);
        int y = Math.max(top, Math.min(main.y(), panelRect().bottom() - 4 - height));
        return MenuFlyout.place(x, y, rows);
    }

    /**
     * Where the menu goes, and which side its flyout opens on.
     *
     * <p>The pointer decides, and the window bounds it — one rule for the sidebar's menus and the
     * canvas's, in {@link MenuPlacement}, because the first version measured x from the sidebar column's
     * right edge and so put every canvas menu beside the sidebar whatever the click was near.
     */
    private MenuPlacement.Placed menuPlacement() {
        BookGeometry.Rect window = panelRect();
        int flyoutWidth = submenuRow >= 0 ? MenuFlyout.width(childrenOf(submenuRow)) : MENU_WIDTH;
        return MenuPlacement.place(menuX, menuY, MENU_WIDTH, menu.size() * MENU_ROW_HEIGHT + 6,
                flyoutWidth, window.x() + 4, window.y() + 4, window.right() - 4, window.bottom() - 4, 8);
    }

    private int menuLeft() {
        return menuPlacement().x();
    }

    private int menuTop() {
        return menuPlacement().y();
    }

    /** Which menu row the pointer is on, or -1. */
    private int menuRowAt(double mouseX, double mouseY) {
        List<BookGeometry.Rect> rects = menuRects();
        for (int i = 0; i < rects.size(); i++) {
            if (rects.get(i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A label cut to the panel's width.
     *
     * <p>The menu is a fixed-width panel and a group title is an author's string, so a long one used to
     * be drawn straight past the panel and over the canvas — which reads as a rendering fault rather
     * than as a long name. Same rule as a control's label, on the same terms.
     */
    private static String fitLabel(GuiRenderer r, String label) {
        int room = MENU_WIDTH - 8;
        if (r.textWidth(label) <= room) {
            return label;
        }
        String cut = label;
        while (cut.length() > 1 && r.textWidth(cut + "\u2026") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "\u2026";
    }

    /** Whether a press is the menu's: a row runs, a flyout tunes or chooses, anything else just closes. */
    private boolean pressMenu(double mouseX, double mouseY) {
        if (menu.isEmpty()) {
            return false;
        }
        int parent = submenuRow;
        if (parent >= 0) {
            MenuFlyout.Placed placed = submenuPlacement(parent);
            MenuFlyout.Hit hit = MenuFlyout.hit(placed, mouseX, mouseY);
            if (hit instanceof MenuFlyout.Hit.Choose choose) {
                closeMenu();
                choose.run().run();
                return true;
            }
            if (hit instanceof MenuFlyout.Hit.Tune tune) {
                tune.run().run();
                // The rows are rebuilt in place, with the flyout still open: the selection, the reset
                // chips and the rows another axis switches off all read the style the press just wrote,
                // and rebuilding is what keeps the panel showing the line as it now is. The pointer is
                // still on the same parent row, so `submenuRow` survives -- `closeMenu` is not called.
                if (canvasMenuFrom != null && canvasMenuTo != null) {
                    menu = lineMenuItems(canvasMenuFrom, canvasMenuTo);
                }
                return true;
            }
            if (hit != null) {
                return true;  // on the flyout, on nothing pressable
            }
        }
        int row = menuRowAt(mouseX, mouseY);
        if (row >= 0) {
            MenuItem item = menu.get(row);
            if (item.submenu()) {
                // The flyout is hover-driven and already open under this pointer; a press on its parent
                // chooses nothing, and closing here would make the row impossible to use at all.
                return true;
            }
            closeMenu();
            item.action().run();
            return true;
        }
        closeMenu();
        return true;
    }

    /** The menu, drawn after the widget pass so it can overhang the list it describes. */
    private void drawMenu(GuiRenderer r, int mouseX, int mouseY) {
        if (menu.isEmpty()) {
            return;
        }
        List<BookGeometry.Rect> rects = menuRects();
        BookGeometry.Rect first = rects.get(0);
        int bottom = rects.get(rects.size() - 1).bottom();
        ArmatureTheme.panel(r, first.x() - 2, first.y() - 3, MENU_WIDTH + 4,
                bottom - first.y() + 6, ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        for (int i = 0; i < rects.size(); i++) {
            drawMenuItem(r, rects.get(i), menu.get(i), mouseX, mouseY);
        }

        int parent = submenuRow;
        if (parent < 0) {
            return;
        }
        MenuFlyout.Placed placed = submenuPlacement(parent);
        if (placed.rows().isEmpty()) {
            return;
        }
        ArmatureTheme.panel(r, placed.panel().x(), placed.panel().y(), placed.panel().width(),
                placed.panel().height(), ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        MenuFlyoutArt.draw(r, placed, mouseX, mouseY);
    }

    /** One row of either panel: its hover wash, and its label in the ink its meaning asks for. */
    private void drawMenuItem(GuiRenderer r, BookGeometry.Rect rect, MenuItem item,
                              int mouseX, int mouseY) {
        boolean pressable = item.action() != null || item.submenu();
        if (pressable && rect.contains(mouseX, mouseY)) {
            r.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), ArmatureTheme.rowHover());
        }
        // A submenu parent is a live row, not a disabled one: it opened the panel beside it. Only a row
        // that can do nothing at all -- the "…" that says the rest did not fit -- is drawn faint.
        int colour = item.submenu() ? ArmatureTheme.body()
                : item.action() == null ? ArmatureTheme.faint()
                : item.danger() ? ArmatureTheme.blocked() : ArmatureTheme.body();
        r.text(fitLabel(r, item.label()), rect.x() + 4, rect.y() + (MENU_ROW_HEIGHT - 8) / 2, colour);
    }

    // ------------------------------------------------------------------
    // The canvas's menus
    // ------------------------------------------------------------------

    /**
     * Opens the canvas's menu at the pointer: for a quest, for a line, or for the empty canvas.
     *
     * <p>Node first, then line, then empty — the same priority as the press, so what a click opens is
     * what it looked like it was on. Edit mode only: every entry is an edit, and a reader has none.
     */
    private boolean openCanvasMenuAt(double mouseX, double mouseY) {
        String chapter = effectiveChapter();
        if (chapter == null || !mayEditNow()) {
            return false;
        }
        List<ClientQuestCache.Entry> quests = questsIn(chapter);
        ClientQuestCache.Entry node = nodeAt(mouseX, mouseY, quests);
        canvasMenuQuest = null;
        canvasMenuFrom = null;
        canvasMenuTo = null;
        if (node != null) {
            // A right-click on a node outside the selection makes it the subject: acting on a set the
            // pointer is not on would be the menu editing something the author was not pointing at.
            if (!isSelected(node.id())) {
                selectedQuest = node.id();
                multiSelection.clear();
            }
            canvasMenuQuest = node.id();
            menu = nodeMenuItems(node.id());
        }
        else {
            List<String> edge = edgeAt(mouseX, mouseY, quests);
            if (edge != null) {
                canvasMenuFrom = edge.get(0);
                canvasMenuTo = edge.get(1);
                menu = lineMenuItems(edge.get(0), edge.get(1));
            }
            else {
                menu = emptyCanvasItems(mouseX, mouseY);
            }
        }
        menuX = (int) mouseX;
        menuY = (int) mouseY;
        submenuRow = -1;
        menuDeleteArmed = false;
        return true;
    }

    /** What a quest node offers. A multi-selection is acted on as one, and the labels say so. */
    private List<MenuItem> nodeMenuItems(String id) {
        List<String> targets = selection().contains(id) ? selection() : List.of(id);
        String many = targets.size() > 1 ? " " + targets.size() + " quests" : "";
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("Open", () -> openOverlay(id)));
        items.add(MenuItem.of("Duplicate" + many, () -> {
            for (String each : targets) {
                send(new EditorOp.Duplicate(each));
            }
        }));
        items.add(MenuItem.of("Copy" + many, this::copySelection));
        if (targets.size() == 1) {
            items.add(MenuItem.of("Add dependency\u2026", () -> {
                selectedQuest = id;
                multiSelection.clear();
                armDependencyPick();
            }));
        }
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + questDeleteNote(targets) : "Delete" + many,
                armed
                        ? () -> {
                            for (String each : targets) {
                                send(new EditorOp.Delete(each));
                            }
                            selectedQuest = null;
                            multiSelection.clear();
                        }
                        : () -> {
                            menuDeleteArmed = true;
                            canvasMenuQuest = id;
                            menu = nodeMenuItems(id);
                        }));
        return items;
    }

    /** "(3 quests, 1 dependent)", counted the same way the sidebar's delete note is. */
    private static String questDeleteNote(List<String> targets) {
        Set<String> ids = new HashSet<>(targets);
        int dependents = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (ids.contains(entry.id())) {
                continue;
            }
            for (String dependency : dependenciesOf(entry)) {
                if (ids.contains(dependency)) {
                    dependents++;
                    break;
                }
            }
        }
        return note(targets.size(), "quest", dependents, "dependent");
    }

    /** What the empty canvas offers: make one here, paste here, take the chapter. */
    private List<MenuItem> emptyCanvasItems(double mouseX, double mouseY) {
        double x = BookGeometry.snap(viewport().contentX(mouseX), BookGeometry.SNAP_GRID, snappingNow());
        double y = BookGeometry.snap(viewport().contentY(mouseY), BookGeometry.SNAP_GRID, snappingNow());
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.of("New quest here",
                () -> send(new EditorOp.Create(Math.round(x), Math.round(y)))));
        if (!ClientEditorClipboard.isEmpty()) {
            items.add(MenuItem.of("Paste here", () -> pasteAt(x, y)));
        }
        items.add(MenuItem.of("Select all in chapter", this::selectAllInChapter));
        return items;
    }

    /**
     * What a dependency line offers: its form, its arrows, its pattern, its weight, and its removal.
     *
     * <p>Each flyout is one panel of previews: a route is recognised by seeing it, and the three arrow
     * axes share one flyout — one level of flyout, never two — so tuning a combination is a single visit
     * rather than a hover-chain that closes when the hand strays a pixel. A cell press keeps the panel
     * open and rebuilds it in place; the way out is the pointer leaving, Escape, or a choice like
     * "Join handles" that closes with the click.
     */
    private List<MenuItem> lineMenuItems(String from, String to) {
        ClientQuestCache.Entry dependent = entryFor(to);
        DependencyStyle now = dependent == null ? DependencyStyle.UNSET : lineStyle(dependent, from);
        String suffix = " \u2192 " + to;
        List<MenuItem> items = new ArrayList<>();
        items.add(MenuItem.parent("Form \u203a", formRows(from, to, now)));
        items.add(MenuItem.parent("Arrows \u203a", arrowRows(from, to, now)));
        items.add(MenuItem.parent("Line \u203a", patternRows(from, to, now)));
        items.add(MenuItem.parent("Weight \u203a", weightRows(from, to, now)));
        boolean armed = menuDeleteArmed;
        items.add(MenuItem.destructive(
                armed ? "Really delete?" + suffix : "Delete dependency" + suffix,
                armed
                        ? () -> removeDependency(to, from)
                        : () -> {
                            menuDeleteArmed = true;
                            canvasMenuFrom = from;
                            canvasMenuTo = to;
                            menu = lineMenuItems(from, to);
                        }));
        return items;
    }

    /** The Form flyout: a preview per route, the handle toggle, and the axis reset. */
    private List<MenuFlyout.Row> formRows(String from, String to, DependencyStyle now) {
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            cells.add(new MenuFlyout.Cell(formLabel(form),
                    () -> setLineAxis(from, to, "form", form.wire()),
                    now.formOr(DependencyStyle.Form.ORTHOGONAL) == form,
                    new MenuFlyout.Preview.Form(form)));
        }
        List<MenuFlyout.Row> rows = new ArrayList<>();
        rows.add(new MenuFlyout.Row.Cells("Form", cells, reset(from, to, "form"), true));
        if (isSplit(now)) {
            rows.add(MenuFlyout.text("Join handles", () -> joinHandles(from, to)));
        }
        else if (now.formOr(DependencyStyle.Form.ORTHOGONAL) == DependencyStyle.Form.CURVED) {
            // Only a curve: its two control points reproduce the bow exactly, where a radial arc's cubic
            // stand-in would jump — so the offer is made only where it keeps the shape.
            rows.add(MenuFlyout.text("Split handles", () -> splitHandles(from, to, now)));
        }
        return rows;
    }

    /**
     * The Arrows flyout: one row per arrow axis, in one panel.
     *
     * <p>The old menu kept the glyph and its placement in one axis and offered a text list; the split
     * axes live together here rather than in nested flyouts. A row whose meaning another axis has
     * switched off — placement and density under a blunt end, density when the heads are not a stream —
     * is drawn faint rather than hidden, so the panel does not change shape under the pointer.
     */
    private List<MenuFlyout.Row> arrowRows(String from, String to, DependencyStyle now) {
        DependencyStyle.ArrowHead head = now.headOr(DependencyStyle.ArrowHead.CHEVRON);
        DependencyStyle.ArrowPlace place = now.placeOr(DependencyStyle.ArrowPlace.TARGET);
        List<MenuFlyout.Cell> heads = new ArrayList<>();
        for (DependencyStyle.ArrowHead value : DependencyStyle.ArrowHead.values()) {
            heads.add(new MenuFlyout.Cell(headLabel(value),
                    () -> setLineAxis(from, to, "arrowHead", value.wire()), head == value,
                    new MenuFlyout.Preview.Head(value)));
        }
        List<MenuFlyout.Cell> places = new ArrayList<>();
        for (DependencyStyle.ArrowPlace value : DependencyStyle.ArrowPlace.values()) {
            places.add(new MenuFlyout.Cell(placeLabel(value),
                    () -> setLineAxis(from, to, "arrowPlace", value.wire()), place == value,
                    new MenuFlyout.Preview.Place(value)));
        }
        List<MenuFlyout.Cell> densities = new ArrayList<>();
        for (DependencyStyle.ArrowDensity value : DependencyStyle.ArrowDensity.values()) {
            densities.add(new MenuFlyout.Cell(densityLabel(value),
                    () -> setLineAxis(from, to, "arrowDensity", value.wire()),
                    now.densityOr(DependencyStyle.ArrowDensity.MEDIUM) == value,
                    new MenuFlyout.Preview.Density(value)));
        }
        List<MenuFlyout.Row> rows = new ArrayList<>();
        rows.add(new MenuFlyout.Row.Cells("Head", heads, reset(from, to, "arrowHead"), true));
        rows.add(new MenuFlyout.Row.Cells("Place", places, reset(from, to, "arrowPlace"),
                head != DependencyStyle.ArrowHead.NONE));
        rows.add(new MenuFlyout.Row.Cells("Density", densities, reset(from, to, "arrowDensity"),
                head != DependencyStyle.ArrowHead.NONE && place == DependencyStyle.ArrowPlace.STREAM));
        return rows;
    }

    /** The Line flyout: a preview per pattern. */
    private List<MenuFlyout.Row> patternRows(String from, String to, DependencyStyle now) {
        DependencyStyle.Dash current = now.dashOr(DependencyStyle.Dash.SOLID);
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Dash pattern : DependencyStyle.Dash.values()) {
            cells.add(new MenuFlyout.Cell(patternLabel(pattern),
                    () -> setLineAxis(from, to, "dash", pattern.wire()), current == pattern,
                    new MenuFlyout.Preview.Pattern(pattern)));
        }
        return List.of(new MenuFlyout.Row.Cells("Line", cells, reset(from, to, "dash"), true));
    }

    /** The Weight flyout: a preview per thickness, faint while the pattern ignores the weight axis. */
    private List<MenuFlyout.Row> weightRows(String from, String to, DependencyStyle now) {
        DependencyStyle.Weight current = now.weightOr(DependencyStyle.Weight.THIN);
        List<MenuFlyout.Cell> cells = new ArrayList<>();
        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            cells.add(new MenuFlyout.Cell(weightLabel(weight),
                    () -> setLineAxis(from, to, "weight", weight.wire()), current == weight,
                    new MenuFlyout.Preview.Weight(weight)));
        }
        return List.of(new MenuFlyout.Row.Cells("Weight", cells, reset(from, to, "weight"),
                now.dashOr(DependencyStyle.Dash.SOLID) != DependencyStyle.Dash.DOUBLE));
    }

    /** The reset chip's action, or null when the line already stands on the chapter's default. */
    private Runnable reset(String from, String to, String axis) {
        return overrides(from, to, axis) ? () -> clearLineAxis(from, to, axis) : null;
    }

    /**
     * Whether this line's own override names the axis.
     *
     * <p>The legacy arrows axis stands in for all three current arrow axes: a line written before the
     * split genuinely overrides its arrows, and a reset offered on it must clear the old spelling too,
     * or clearing one new axis would leave the old value in force.
     */
    private boolean overrides(String from, String to, String axis) {
        JsonObject entry = lineEntry(from, to);
        return entry.has(axis)
                || (DependencyStyle.isArrowAxis(axis)
                && entry.has(DependencyStyle.LEGACY_ARROWS_FIELD));
    }

    /** One line's override object, or an empty one when it has none. */
    private JsonObject lineEntry(String from, String to) {
        ClientQuestCache.Entry dependent = entryFor(to);
        if (dependent == null) {
            return new JsonObject();
        }
        JsonObject lines = dependencyLinesOf(dependent);
        return lines.has(from) && lines.get(from).isJsonObject()
                ? lines.getAsJsonObject(from) : new JsonObject();
    }

    // ------------------------------------------------------------------
    // The cells' captions: short, because a preview is about forty pixels wide
    // ------------------------------------------------------------------

    private static String formLabel(DependencyStyle.Form form) {
        return switch (form) {
            case ORTHOGONAL -> "Ortho";
            case CHAMFERED -> "Circuit";
            case STRAIGHT -> "Straight";
            case STEPPED -> "Stepped";
            case CURVED -> "Curved";
            case RADIAL -> "Arc";
        };
    }

    private static String headLabel(DependencyStyle.ArrowHead head) {
        return switch (head) {
            case CHEVRON -> "Chevron";
            case TRIANGLE -> "Tri";
            case DOT -> "Dot";
            case DIAMOND -> "Diamond";
            case NONE -> "None";
        };
    }

    private static String placeLabel(DependencyStyle.ArrowPlace place) {
        return switch (place) {
            case TARGET -> "Target";
            case BOTH -> "Both";
            case MID -> "Mid";
            case STREAM -> "Stream";
        };
    }

    private static String densityLabel(DependencyStyle.ArrowDensity density) {
        return switch (density) {
            case LOW -> "Low";
            case MEDIUM -> "Med";
            case HIGH -> "High";
        };
    }

    private static String patternLabel(DependencyStyle.Dash pattern) {
        return switch (pattern) {
            case SOLID -> "Solid";
            case DASHED -> "Dash";
            case DOTTED -> "Dot";
            case DASH_DOT -> "D.Dot";
            case DOUBLE -> "Double";
            case HAZARD -> "Hazard";
        };
    }

    private static String weightLabel(DependencyStyle.Weight weight) {
        return switch (weight) {
            case THIN -> "Hair";
            case THICK -> "Std";
            case BOLD -> "Bold";
            case CONDUIT -> "Pipe";
        };
    }

    /**
     * Turns a single bow into two control points, without moving the line.
     *
     * <p>The pair written is the one that passes through the quadratic's own midpoint, so the shape the
     * author was looking at is the shape they get; from there the two diamonds pull it into an S.
     */
    private void splitHandles(String from, String to, DependencyStyle style) {
        JsonObject lines = lineOverride(from, to);
        JsonObject entry = lines.getAsJsonObject(from);
        double bend = style.bendOr(DEFAULT_BEND);
        entry.add("fromHandle", handleArray(LineArt.equivalentFromHandle(bend)));
        entry.add("toHandle", handleArray(LineArt.equivalentToHandle(bend)));
        sendField(to, "dependencyLines", lines);
        status("Line " + from + " -> " + to + ": split into two handles", false);
    }

    /** Puts the single bow back: the two control points are removed and `bend` has it again. */
    private void joinHandles(String from, String to) {
        JsonObject lines = lineOverride(from, to);
        JsonObject entry = lines.getAsJsonObject(from);
        entry.remove("fromHandle");
        entry.remove("toHandle");
        // A join can leave the entry empty -- when the handles were the only thing this line
        // overrode -- and an empty entry is not an override: it is a key that pins nothing.
        if (entry.isEmpty()) {
            lines.remove(from);
        }
        sendField(to, "dependencyLines", lines.isEmpty() ? null : lines);
        status("Line " + from + " -> " + to + ": handles joined", false);
    }

    /**
     * The override for one line, read from what the server last sent.
     *
     * <p>From the synced map rather than from the editor's replica: the map is the server's own answer
     * about this quest, and rebuilding the object from it means the write cannot lose an axis the file
     * holds but this client's copy does not.
     */
    private JsonObject lineOverride(String from, String to) {
        ClientQuestCache.Entry dependent = entryFor(to);
        JsonObject lines = dependent == null ? new JsonObject() : dependencyLinesOf(dependent);
        JsonObject style = lines.has(from) && lines.get(from).isJsonObject()
                ? lines.getAsJsonObject(from).deepCopy() : new JsonObject();
        lines.add(from, style);
        return lines;
    }

    /**
     * A quest's dependency-line overrides as the editor has them: the pending write first, then the
     * file's own copy, then the synced tree's parsed styles.
     *
     * <p>Every writer rebuilds the whole map for one axis's change, so the base has to include a
     * pending sibling edit — reading the synced tree alone dropped an axis the author had just set,
     * and the server's whole-object write then discarded it. The replica is the file's own answer, so
     * it carries axes the synced tree never shows; the parsed tree is the last resort.
     *
     * <p>The replica is only the file's own answer for the tree on screen: a broadcast that moved the
     * revision past it is the newer truth — another author's edit, with no draft to cover it — and
     * rebuilding from the older copy would drop that edit when the whole object is written back. So
     * the copy is read while its revision is the tree's, and the synced tree otherwise. The fallback
     * re-serializes the parsed tree, so an axis this build does not know — written by a newer one — is
     * not carried while the copy is stale; the replica's raw JSON is the only place it survives, which
     * is why the copy is preferred the moment it is current.
     */
    private static JsonObject dependencyLinesOf(ClientQuestCache.Entry entry) {
        JsonElement drafted = fieldDraft.value(entry.chapterId(), entry.id(), "dependencyLines");
        if (drafted != null && drafted.isJsonObject()) {
            return drafted.getAsJsonObject().deepCopy();
        }
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(entry.chapterId());
        if (copy != null && copy.revision() == ClientQuestCache.treeRevision()) {
            JsonObject quest = copy.quests().get(entry.id());
            JsonElement stored = quest == null ? null : QuestPanelLayout.get(quest, "dependencyLines");
            if (stored != null && stored.isJsonObject()) {
                return stored.getAsJsonObject().deepCopy();
            }
        }
        JsonObject lines = new JsonObject();
        entry.dependencyLines().forEach((dependency, style) -> lines.add(dependency, style.asJson()));
        return lines;
    }

    /** Writes one axis of one line's override, keeping every other axis and every other line. */
    private void setLineAxis(String from, String to, String axis, String value) {
        JsonObject lines = lineOverride(from, to);
        JsonObject style = lines.getAsJsonObject(from);
        style.addProperty(axis, value);
        if (DependencyStyle.isArrowAxis(axis)) {
            // The legacy axis said all three of the current ones at once: an edit that speaks the new
            // vocabulary retires it, or clearing one new axis later would resurrect the old value.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        sendField(to, "dependencyLines", lines);
        status("Line " + from + " \u2192 " + to + ": " + axis + " " + value, false);
    }

    /** Removes one axis of one line's override, dropping the override entirely when it empties. */
    private void clearLineAxis(String from, String to, String axis) {
        JsonObject lines = lineOverride(from, to);
        JsonObject style = lines.getAsJsonObject(from);
        style.remove(axis);
        if (DependencyStyle.isArrowAxis(axis)) {
            // The reset means "the chapter's default again", and while the legacy spelling is still in
            // the object that is not what it would get.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        if (style.isEmpty()) {
            lines.remove(from);
        }
        sendField(to, "dependencyLines", lines.isEmpty() ? null : lines);
        status("Line " + from + " \u2192 " + to + ": chapter default", false);
    }

    /** Removes one dependency, by writing the rest of the list — the card's own remove path. */
    private void removeDependency(String dependentId, String dependencyId) {
        ClientQuestCache.Entry dependent = entryFor(dependentId);
        if (dependent == null) {
            return;
        }
        List<String> remaining = dependenciesOf(dependent).stream()
                .filter(each -> !each.equals(dependencyId)).toList();
        sendField(dependentId, "dependsOn", stringArray(remaining));
        status("Removed " + dependencyId + " \u2192 " + dependentId, false);
    }

    /** How close to the ink a right-click must land, in pixels: looser than an exact hit. */
    private static final int LINE_HIT = LineArt.TOLERANCE + 2;

    /**
     * How far a hovered line is lightened at full hover, as a fraction towards white.
     *
     * <p>Lightened rather than recoloured: {@code Colour.shade} keeps the line's own hue and its
     * alpha, so a completed line still reads as completed while it brightens, and the cue cannot be
     * mistaken for a selection.
     */
    private static final float LINE_HOVER_BRIGHTEN = 0.5F;

    /**
     * The dependency line under the pointer, as {dependency, dependent}, or null.
     *
     * <p>The candidates are {@link #edgeCandidates}, which is the drawing's own walk -- so a line that
     * is drawn is a line that answers, and one function rather than three copies of the loop. The
     * caller has already established that no node is under the pointer, because the node wins the
     * press and so it wins the cue: see {@code drawCanvasContents}.
     */
    private List<String> edgeAt(double mouseX, double mouseY, List<ClientQuestCache.Entry> quests) {
        String[] pair = LineArt.nearest(edgeCandidates(quests), mouseX, mouseY, LINE_HIT);
        return pair == null ? null : List.of(pair[0], pair[1]);
    }

    /** The route one line takes, from the style that line is owed. The drawing's own derivation. */
    private List<LineArt.Point> linePath(ClientQuestCache.Entry dependent, String dependencyId,
                                         ClientQuestCache.Entry from, ClientQuestCache.Entry to) {
        DependencyStyle style = styleFor(dependent, dependencyId);
        LineArt.Point[] ends = lineEnds(from, to, style);
        return stylePath(style, ends[0], ends[1]);
    }

    private int nodeCentreX(ClientQuestCache.Entry entry) {
        return nodeScreenX(entry) + nodeSize(entry) / 2;
    }

    private int nodeCentreY(ClientQuestCache.Entry entry) {
        return nodeScreenY(entry) + nodeSize(entry) / 2;
    }

    /**
     * The handle of the curved line under the pointer, as {dependency, dependent}, or null.
     *
     * <p>Only a curved line has one — a bow means nothing on a step or a straight edge — and the handle
     * sits on the curve's own midpoint, which is off the route the hit test measures, so the two tests are
     * separate questions rather than one.
     */
    /** The three kinds of handle a line can show. */
    private static final String HANDLE_FROM = "from";
    private static final String HANDLE_TO = "to";
    private static final String HANDLE_BEND = "bend";
    private static final String HANDLE_FROM_HANDLE = "fromHandle";
    private static final String HANDLE_TO_HANDLE = "toHandle";

    /**
     * The handle under the pointer, as {dependency, dependent, kind}, or null.
     *
     * <p>The rim dots come before the bend dot in this order, and both before any node: the dots sit on a
     * node's own edge, where a node hit test is true, so a press that reached the node first could never
     * grab one.
     */
    private String[] handleAt(double mouseX, double mouseY) {
        if (!mayEditNow() || overlay != Overlay.NONE) {
            return null;
        }
        String chapter = effectiveChapter();
        if (chapter == null) {
            return null;
        }
        if (draggedNode != null || marqueeActive) {
            // A node or a marquee owns the pointer; a handle is not what this press is for.
            return null;
        }
        List<ClientQuestCache.Entry> quests = questsIn(chapter);
        String[] found = null;
        double best = HANDLE_GRAB;
        for (LineArt.Candidate<String[]> candidate : edgeCandidates(quests)) {
            String[] edge = candidate.id();
            ClientQuestCache.Entry dependent = entryFor(edge[1]);
            ClientQuestCache.Entry dependency = entryFor(edge[0]);
            if (dependent == null || dependency == null) {
                continue;
            }
            DependencyStyle style = styleFor(dependent, edge[0]);
            if (!showsHandles(edge, handleArms(edge, candidate.path(), style), mouseX, mouseY)) {
                continue;
            }
            LineArt.Point fromDot = handleDot(candidate.path(), true);
            LineArt.Point toDot = handleDot(candidate.path(), false);
            double fromDistance = Math.hypot(fromDot.x() - mouseX, fromDot.y() - mouseY);
            if (fromDistance <= best) {
                best = fromDistance;
                found = new String[] { edge[0], edge[1], HANDLE_FROM };
            }
            double toDistance = Math.hypot(toDot.x() - mouseX, toDot.y() - mouseY);
            if (toDistance <= best) {
                best = toDistance;
                found = new String[] { edge[0], edge[1], HANDLE_TO };
            }
            if (isSplit(style) || bows(style)) {
                if (isSplit(style)) {
                    LineArt.Point fromControl = splitPointOf(edge, style, true);
                    LineArt.Point toControl = splitPointOf(edge, style, false);
                    double fromHandleDistance = Math.hypot(fromControl.x() - mouseX,
                            fromControl.y() - mouseY);
                    if (fromHandleDistance <= best) {
                        best = fromHandleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_FROM_HANDLE };
                    }
                    double toHandleDistance = Math.hypot(toControl.x() - mouseX,
                            toControl.y() - mouseY);
                    if (toHandleDistance <= best) {
                        best = toHandleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_TO_HANDLE };
                    }
                }
                else {
                    LineArt.Point middle = LineArt.pointAt(candidate.path(),
                            LineArt.length(candidate.path()) / 2);
                    double middleDistance = Math.hypot(middle.x() - mouseX, middle.y() - mouseY);
                    if (middleDistance <= best) {
                        best = middleDistance;
                        found = new String[] { edge[0], edge[1], HANDLE_BEND };
                    }
                }
            }
        }
        return found;
    }

    /** How close to a handle a press must be, in pixels. */
    private static final int HANDLE_GRAB = 8;

    /**
     * How far the hover reaches around a line and its handles, in pixels.
     *
     * <p>Wider than the grab on purpose: the reveal is what invites the hand, and a line that answers
     * only on its own 1px of ink is one whose control points cannot be reached at all.
     */
    private static final int HANDLE_REACH = HANDLE_GRAB + 4;

    /**
     * How much further the already-revealed line keeps its handles, in pixels.
     *
     * <p>What stops a diamond winking out as the hand crosses to it diagonally: nothing new reveals
     * outside {@link #HANDLE_REACH}, but the line that is already lit stays lit a little longer.
     */
    private static final int HANDLE_STICKY = 6;

    /**
     * The handle layer: a circle at each end of the line under the pointer, and a diamond at the middle
     * of a curved or radial one.
     *
     * <p><b>Called after the nodes are drawn</b>, so a dot that overlaps a node — an anchor dragged round
     * to its far side — is on top of it. The first version ran before `drawNode`, and the dot you were
     * holding vanished under the node it was next to.
     *
     * <p><b>One shape per meaning</b>: ends are circles, the bend is a diamond, so the outline says which
     * one detaches and which one bends. Grab radius is the same for all three; the diamond is checked last
     * and wins ties, so a pointer between an end and the control bends rather than detaching.
     *
     * <p><b>And a drag owns the pointer:</b> while a handle is being dragged, or a node, or a marquee,
     * no other line may light up — dragging across a neighbour used to sprout its handles mid-gesture.
     */
    private void drawEdgeHandles(GuiRenderer r, double mouseX, double mouseY, List<FrameEdge> edges) {
        if (!mayEditNow() || overlay != Overlay.NONE) {
            return;
        }
        // A gesture owns the pointer: only the dragged line shows anything, and a node or marquee drag
        // shows nothing at all until the release.
        boolean gesture = bendDragFrom != null || draggedNode != null || marqueeActive;
        // The frame's own edges: the paths the handles are grabbed against are the ones that were drawn,
        // and they are already built -- this loop used to rebuild every route a second time per frame.
        for (FrameEdge frameEdge : edges) {
            String[] edge = frameEdge.key();
            ClientQuestCache.Entry dependent = frameEdge.to();
            ClientQuestCache.Entry dependency = frameEdge.from();
            boolean dragging = edge[0].equals(bendDragFrom) && edge[1].equals(bendDragTo);
            if (gesture && !dragging) {
                continue;
            }
            DependencyStyle style = frameEdge.style();
            List<List<LineArt.Point>> arms = handleArms(edge, frameEdge.path(), style);
            if (!dragging && !showsHandles(edge, arms, mouseX, mouseY)) {
                continue;
            }
            boolean fromDragged = dragging && HANDLE_FROM.equals(bendDragKind);
            boolean toDragged = dragging && HANDLE_TO.equals(bendDragKind);
            // Drawn where they are grabbed, from the same path the ink came from: circles at the ends,
            // the diamond at the middle of a curve.
            circleDot(r, handleDot(frameEdge.path(), true), fromDragged);
            circleDot(r, handleDot(frameEdge.path(), false), toDragged);
            if (isSplit(style)) {
                // Two diamonds at the control points, each with a leash to the end of the curve it
                // steers -- the outline that says "bend here" rather than "another end". Both come from
                // `arms`, the same list the hover reaches along, so the two cannot drift apart again.
                if (arms.size() == 3) {
                    leash(r, arms.get(1));
                    leash(r, arms.get(2));
                    diamondDot(r, arms.get(1).get(arms.get(1).size() - 1),
                            dragging && HANDLE_FROM_HANDLE.equals(bendDragKind));
                    diamondDot(r, arms.get(2).get(arms.get(2).size() - 1),
                            dragging && HANDLE_TO_HANDLE.equals(bendDragKind));
                }
            }
            else if (bows(style)) {
                LineArt.Point middle = LineArt.pointAt(frameEdge.path(),
                        LineArt.length(frameEdge.path()) / 2);
                diamondDot(r, middle, dragging && HANDLE_BEND.equals(bendDragKind));
            }
        }
    }

    /** How far outside the rim an end's dot sits, so it is clear of the node it belongs to. */
    private static final int DOT_OUT = 7;

    /**
     * Where an end's dot is drawn and grabbed: a point **of the drawn line**, a little in from its end.
     *
     * <p>The first version pushed the rim point outward along the direction from the node's centre — the
     * chord's direction — which is not the direction a curve leaves its rim in, so on every curved line
     * the dots sat beside the ink. Taking the point from the path cannot disagree with the drawing, for
     * any form and any anchor, because it is a point of it.
     */
    private static LineArt.Point handleDot(List<LineArt.Point> path, boolean atStart) {
        double total = LineArt.length(path);
        if (total < 1) {
            return path.isEmpty() ? new LineArt.Point(0, 0) : path.get(0);
        }
        // A third of the path rather than half: on a short line both dots would otherwise clamp onto
        // the same pixel and become one grab target.
        double in = Math.min(DOT_OUT, total / 3.0);
        return atStart ? LineArt.pointAt(path, in) : LineArt.pointAt(path, total - in);
    }

    /**
     * How wide a handle dot is, at its middle.
     *
     * <p>Its own number, not `HANDLE_GRAB - 2`: how big a target looks and how close a press must land
     * are two decisions, and tying them together made the dots chunky the day the grab got generous.
     */
    private static final int DOT_RADIUS = 4;

    /**
     * A terminal anchor's dot: a **circle**.
     *
     * <p>The interactive inks rather than the faint panel edge, which is close to invisible on the light
     * themes where the line itself is dark — a handle that cannot be seen is a handle that cannot be used.
     */
    private static void circleDot(GuiRenderer r, LineArt.Point centre, boolean dragged) {
        int colour = dragged ? ArmatureTheme.selectedRing() : ArmatureTheme.hoverRing();
        for (int y = -DOT_RADIUS; y <= DOT_RADIUS; y++) {
            int half = (int) Math.floor(Math.sqrt((double) DOT_RADIUS * DOT_RADIUS - y * y));
            r.fill(centre.x() - half, centre.y() + y, centre.x() + half + 1, centre.y() + y + 1, colour);
        }
    }

    /** A 1px leash along one arm, from the end of the curve out to its own control point. */
    private static void leash(GuiRenderer r, List<LineArt.Point> arm) {
        for (LineArt.Point point : arm) {
            r.fill(point.x(), point.y(), point.x() + 1, point.y() + 1, ArmatureTheme.line());
        }
    }

    /** The bend control's dot: a **diamond**, so it cannot be mistaken for an end. */
    private static void diamondDot(GuiRenderer r, LineArt.Point centre, boolean dragged) {
        int colour = dragged ? ArmatureTheme.selectedRing() : ArmatureTheme.hoverRing();
        for (int y = -DOT_RADIUS; y <= DOT_RADIUS; y++) {
            int half = DOT_RADIUS - Math.abs(y);
            r.fill(centre.x() - half, centre.y() + y, centre.x() + half + 1, centre.y() + y + 1, colour);
        }
    }

    /**
     * The arms the hover reaches along for one line: its ink, then one leash per split control point.
     *
     * <p><b>One source of truth for the furniture.</b> The drawing takes its leashes and its diamonds
     * from this list too, so a handle that is drawn is a handle the hover can reach. The split control
     * points were the case that proved it: they sit off the ink by design, and a reveal that knew only
     * about the ink made them vanish exactly as the hand arrived.
     */
    private List<List<LineArt.Point>> handleArms(String[] edge, List<LineArt.Point> path,
                                                 DependencyStyle style) {
        List<List<LineArt.Point>> arms = new ArrayList<>();
        arms.add(path);
        if (isSplit(style)) {
            LineArt.Point fromControl = splitPointOf(edge, style, true);
            LineArt.Point toControl = splitPointOf(edge, style, false);
            if (fromControl != null && toControl != null) {
                arms.add(LineArt.steps(handleDot(path, true), fromControl));
                arms.add(LineArt.steps(handleDot(path, false), toControl));
            }
        }
        return arms;
    }

    /**
     * Whether this line should show its handles: the pointer is within {@link #HANDLE_REACH} of the ink,
     * a leash or a control point — or within {@link #HANDLE_STICKY} more of the line last revealed.
     *
     * <p>The reach is the union of the arms, connected by construction — ink, then the leash out to each
     * control — so travelling to a control point never crosses a dead band. The extra stickiness is for
     * the approach that does not follow the leash: aiming diagonally at a diamond used to wink the whole
     * line out a few pixels short of the target.
     */
    private boolean showsHandles(String[] edge, List<List<LineArt.Point>> arms,
                                 double mouseX, double mouseY) {
        double reach = LineArt.distanceToAny(arms, mouseX, mouseY);
        if (reach <= HANDLE_REACH) {
            revealedEdge = edge;
            return true;
        }
        return revealedEdge != null && revealedEdge[0].equals(edge[0]) && revealedEdge[1].equals(edge[1])
                && reach <= HANDLE_REACH + HANDLE_STICKY;
    }

    /**
     * Every line the chapter draws, as candidates for a hover or a hit test.
     *
     * <p>The same rules the drawing uses — dependencies, plus a linear chapter's road — so a line that is
     * drawn is a line that answers, and one function rather than three copies of the loop.
     */
    private List<LineArt.Candidate<String[]>> edgeCandidates(List<ClientQuestCache.Entry> quests) {
        List<LineArt.Candidate<String[]>> candidates = new ArrayList<>();
        for (ClientQuestCache.Entry quest : quests) {
            for (String dependencyId : dependenciesOf(quest)) {
                ClientQuestCache.Entry dependency = entryFor(dependencyId);
                if (dependency == null || !quests.contains(dependency)) {
                    continue;
                }
                candidates.add(new LineArt.Candidate<>(new String[] { dependencyId, quest.id() },
                        linePath(quest, dependencyId, dependency, quest)));
            }
        }
        if (!quests.isEmpty() && quests.get(0).chapterLinear()) {
            List<ClientQuestCache.Entry> ordered = quests.stream()
                    .sorted(java.util.Comparator.comparingInt(ClientQuestCache.Entry::orderInChapter))
                    .toList();
            for (int i = 1; i < ordered.size(); i++) {
                ClientQuestCache.Entry previous = ordered.get(i - 1);
                candidates.add(new LineArt.Candidate<>(new String[] { previous.id(), ordered.get(i).id() },
                        linePath(ordered.get(i), previous.id(), previous, ordered.get(i))));
            }
        }
        return candidates;
    }

    /** The chord's frame for one line: the two ends, and the unit direction and length between them. */
    private double[] chordOf(ClientQuestCache.Entry from, ClientQuestCache.Entry to) {
        double ax = nodeCentreX(from);
        double ay = nodeCentreY(from);
        double bx = nodeCentreX(to);
        double by = nodeCentreY(to);
        double length = Math.hypot(bx - ax, by - ay);
        return new double[] { ax, ay, length < 1 ? 0 : (bx - ax) / length, length < 1 ? 0 : (by - ay) / length, length };
    }

    /** A split control point as a screen point, for its diamond and the leash to its end. */
    private LineArt.Point splitPoint(double[] chord, List<Double> handle) {
        return LineArt.handlePoint(new LineArt.Point((int) chord[0], (int) chord[1]), chord[2], chord[3],
                chord[4], handle);
    }

    /** One of the two split control points of a line, or null when it is not split. */
    private LineArt.Point splitPointOf(String[] edge, DependencyStyle style, boolean fromEnd) {
        if (!isSplit(style)) {
            return null;
        }
        ClientQuestCache.Entry dependency = entryFor(edge[0]);
        ClientQuestCache.Entry dependent = entryFor(edge[1]);
        if (dependency == null || dependent == null) {
            return null;
        }
        return splitPoint(chordOf(dependency, dependent),
                fromEnd ? style.fromHandle().get() : style.toHandle().get());
    }

    /** Writes one split control point: one op, so Ctrl+Z undoes a split drag like any other edit. */
    private void setLineHandle(String from, String to, String kind, List<Double> handle) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).add(HANDLE_FROM_HANDLE.equals(kind) ? "fromHandle" : "toHandle",
                handleArray(handle));
        sendField(to, "dependencyLines", lines);
        status("Line " + from + " -> " + to + ": control point moved", false);
    }

    /** A pair as the JSON a file holds. */
    private static JsonArray handleArray(List<Double> handle) {
        JsonArray array = new JsonArray();
        array.add(handle.get(0));
        array.add(handle.get(1));
        return array;
    }

    /** Writes one end's anchor angle: one op, so Ctrl+Z undoes an anchor like any other edit. */
    private void setLineAnchor(String from, String to, String kind, double degrees) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).addProperty(HANDLE_FROM.equals(kind) ? "fromAnchor" : "toAnchor",
                degrees);
        sendField(to, "dependencyLines", lines);
        status("Line " + from + " -> " + to + ": anchor " + Math.round(degrees) + "°", false);
    }

    /**
     * Alt+click: centres a node between the two **linked** quests nearest it.
     *
     * <p>Links in either direction — the quests it depends on and the quests that depend on it — because
     * a chain runs through a node from both sides, and the two nearest of those are what it should sit
     * between. Fewer than two is said out loud rather than moving the node somewhere invented.
     */
    private void straightenNode(String id) {
        ClientQuestCache.Entry node = entryFor(id);
        if (node == null) {
            return;
        }
        LineArt.Point here = new LineArt.Point((int) nodeX(node), (int) nodeY(node));
        List<LineArt.Point> linked = new ArrayList<>();
        for (String dependency : dependenciesOf(node)) {
            ClientQuestCache.Entry parent = entryFor(dependency);
            if (parent != null && !parent.id().equals(id)) {
                linked.add(new LineArt.Point((int) nodeX(parent), (int) nodeY(parent)));
            }
        }
        for (ClientQuestCache.Entry other : questsIn(effectiveChapter())) {
            if (!other.id().equals(id) && dependenciesOf(other).contains(id)) {
                linked.add(new LineArt.Point((int) nodeX(other), (int) nodeY(other)));
            }
        }
        java.util.Optional<LineArt.Point> middle = Alignment.midpointOfTwoNearest(here, linked);
        if (middle.isEmpty()) {
            status("Nothing to straighten: " + id + " has fewer than two linked quests", true);
            return;
        }
        // Exact rather than snapped: Alt is already the free-placement key, so it means the same thing
        // here -- put it where it was asked for.
        send(new EditorOp.Move(id, middle.get().x(), middle.get().y()));
    }

    /** Writes a curve's new bow: one op, so Ctrl+Z undoes a bend like any other edit. */
    private void setLineBend(String from, String to, double bend) {
        JsonObject lines = lineOverride(from, to);
        lines.getAsJsonObject(from).addProperty("bend", bend);
        sendField(to, "dependencyLines", lines);
        status("Line " + from + " -> " + to + ": bend " + Math.round(bend * 100) + "%", false);
    }

    /**
     * The on-screen band of a viewport: the rectangle a list's rows are visible in, scrolled or not.
     *
     * <p>The band a drag indicator is tested against. It has to be this and not the list's unscrolled
     * frame: the rows and the pointer are on-screen coordinates, and a clip in the frame's own would be
     * offset by the scroll -- which is how the chapter list's line came to vanish once the list moved.
     */
    private static BookGeometry.Rect onScreenBand(Viewport view) {
        return BookGeometry.Rect.at(view.originX(), view.originY(), view.viewWidth(), view.viewHeight());
    }

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
     * A press on the settings page: a swatch, a slider, a switch, an arrow, or the icon's button.
     *
     * <p>Every control is drawn from a rectangle {@code QuestSettingsLayout} computed, so this reads the
     * same rectangles rather than a second set: a swatch is chosen through {@code cellAt}, an arrow
     * through {@code stepperStepAt}, a slider through its track. The step is the field's own — a
     * coordinate moves by the grid (8), a size by one pixel, the icon scale by a twentieth — and a
     * slider's drag commits once, on release, rather than an operation per frame.
     */
    private void pressSettingsPage(double mouseX, double mouseY) {
        JsonObject quest = replicaQuest();
        if (settingsLayout == null || quest == null || editTarget() == null) {
            return;
        }
        for (dev.ellipog.tasked.client.dev.QuestSettingsLayout.Row row : settingsRows) {
            Slot slot = settingsLayout.slot(row.key());
            if (slot == null) {
                continue;
            }
            Slot onScreen = InspectLayout.onScreen(settingsView.viewport(), slot);
            if (!onScreen.contains(mouseX, mouseY)) {
                continue;
            }
            Slot strip = InspectLayout.onScreen(settingsView.viewport(),
                    dev.ellipog.tasked.client.dev.QuestSettingsLayout.strip(slot));
            switch (row.kind()) {
                case SHAPE_GRID -> {
                    int cell = dev.ellipog.tasked.client.dev.QuestSettingsLayout.cellAt(onScreen,
                            mouseX, mouseY, QuestShape.values().length);
                    if (cell >= 0) {
                        chooseShape(QuestShape.values()[cell]);
                    }
                }
                case SLIDER -> {
                    Integer step = dev.ellipog.tasked.client.dev.QuestSettingsLayout.stepperStepAt(
                            strip, mouseX, mouseY);
                    if (step != null) {
                        stepField(row.key(), step, quest);
                    }
                    else {
                        // The track: the knob jumps to the pointer and follows it until the release,
                        // which is one commit rather than one per frame.
                        draggingSlider = row.key();
                        dragSliderTo(mouseX);
                    }
                }
                case STEPPER -> {
                    Integer step = dev.ellipog.tasked.client.dev.QuestSettingsLayout.stepperStepAt(
                            strip, mouseX, mouseY);
                    if (step != null) {
                        stepField(row.key(), step, quest);
                    }
                }
                case SWITCH -> {
                    BookGeometry.Rect track = dev.ellipog.tasked.client.dev.QuestSettingsLayout
                            .switchTrack(strip);
                    if (track.contains(mouseX, mouseY)) {
                        pressQuestToggle(row.key());
                    }
                }
                case ICON -> {
                    BookGeometry.Rect button = dev.ellipog.tasked.client.dev.QuestSettingsPanel
                            .iconButton(strip);
                    if (button.contains(mouseX, mouseY)) {
                        // The picker opens on the icon's own field, so the item is chosen with the same
                        // list the card uses rather than a second one here -- and it hands back to this
                        // page when it closes, because that is where the author was.
                        openItemPicker(new EditTarget(EditAction.ITEM, "icon.item", button,
                                button.x(), button.y(), "", null, -1), true);
                    }
                }
                case CHOICE -> {
                    Integer step = dev.ellipog.tasked.client.dev.QuestSettingsLayout.stepperStepAt(
                            strip, mouseX, mouseY);
                    if (step != null) {
                        JsonElement value = QuestPanelLayout.get(quest, row.key());
                        String current = value != null && value.isJsonPrimitive()
                                ? value.getAsString() : "";
                        String next = "autoClaim".equals(row.key())
                                ? dev.ellipog.tasked.client.dev.QuestSettingsLayout
                                        .cycleAutoClaim(current, step)
                                : dev.ellipog.tasked.client.dev.QuestSettingsLayout
                                        .cycleRequirement(current, step);
                        // The chapter's default is the *absence* of the field, not a string that spells
                        // it out: a quest that says "all_completed" keeps saying it when the chapter's
                        // default changes, which is the whole difference between the two states.
                        sendField(editTarget(), row.key(),
                                next.isEmpty() ? null : new JsonPrimitive(next));
                    }
                }
                case DEPENDENCY -> {
                    if (dev.ellipog.tasked.client.dev.QuestSettingsLayout.removeAt(strip, mouseX, mouseY)) {
                        String id = row.key().substring(
                                dev.ellipog.tasked.client.dev.QuestSettingsLayout.DEPENDENCY_PREFIX
                                        .length());
                        List<String> left = new ArrayList<>(
                                QuestPanelLayout.strings(quest, "dependsOn"));
                        left.remove(id);
                        sendField(editTarget(), "dependsOn", stringArray(left));
                        status("No longer depends on " + id, false);
                    }
                }
                case ACTION -> {
                    if (!dev.ellipog.tasked.client.dev.QuestSettingsLayout.actionAt(strip, mouseX, mouseY)) {
                        break;
                    }
                    if (row.key().equals(QuestPanelLayout.DEPENDENCY_PICK)) {
                        armDependencyPick();
                    }
                    else if (row.key().equals(
                            dev.ellipog.tasked.client.dev.QuestSettingsLayout.DEPENDENCY_SELECTED)) {
                        addSelectedDependencies();
                    }
                }
                default -> {
                }
            }
            return;
        }
    }

    /**
     * What a prerequisite is called: the cache's title, or the id when the cache has never heard of it.
     *
     * <p>The id is the honest fallback and not a placeholder -- it is what the file holds, and it is what
     * an author would have to type into "Add by id" to get the same dependency back.
     */
    private String dependencyTitle(String id) {
        ClientQuestCache.Entry entry = entryFor(id);
        return entry == null ? id : titleOf(entry);
    }

    /**
     * The canvas selection, as prerequisites this quest does not have yet.
     *
     * <p>Filtered rather than handed over whole, because "Add selected" means <i>add</i>: the quest
     * itself is never a prerequisite of itself, and re-adding one that is already in the list would be a
     * no-op the server would have to refuse. The count in the row's label is this list's size, so the
     * label says exactly what the press would do.
     */
    private List<String> selectedDependencyCandidates() {
        String target = editTarget();
        JsonObject quest = replicaQuest();
        if (target == null || quest == null) {
            return List.of();
        }
        List<String> have = QuestPanelLayout.strings(quest, "dependsOn");
        List<String> add = new ArrayList<>();
        for (String id : selection()) {
            if (!id.equals(target) && !have.contains(id) && !add.contains(id)) {
                add.add(id);
            }
        }
        return add;
    }

    /** The action row: every selected node becomes a prerequisite, in one write. */
    private void addSelectedDependencies() {
        String target = editTarget();
        JsonObject quest = replicaQuest();
        if (!mayEditNow() || target == null || quest == null) {
            return;
        }
        List<String> add = selectedDependencyCandidates();
        if (add.isEmpty()) {
            status("Select the quests to depend on first \u2014 Ctrl-click or shift-click on the canvas",
                    true);
            return;
        }
        List<String> dependencies = new ArrayList<>(QuestPanelLayout.strings(quest, "dependsOn"));
        dependencies.addAll(add);
        sendField(target, "dependsOn", stringArray(dependencies));
        status(target + " now depends on " + add.size() + (add.size() == 1 ? " quest" : " quests"), false);
    }

    /** A swatch's press: the shape, remembered for the preview and sent to the server. */
    private void chooseShape(QuestShape shape) {
        settingsDraft.shape(shape, revision());
        sendField(editTarget(), "shape",
                new JsonPrimitive(shape.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /** An arrow's press: the field stepped by its own amount, remembered and sent. */
    private void stepField(String key, int step, JsonObject quest) {
        if (key.equals("rotation")) {
            // Fifteen degrees an arrow: fine enough to phase a gear off the grid, coarse enough to
            // reach an angle without a drag. The track is there for anything finer.
            int current = settingsDraft.rotation(intField(quest, key, 0));
            int next = Math.floorMod(current + step * 15, 360);
            settingsDraft.rotation(next, revision());
            sendField(editTarget(), key, new JsonPrimitive((long) next));
            return;
        }
        if (key.equals("iconScale")) {
            double current = settingsDraft.iconScale(doubleField(quest, key, 0.75));
            double next = Math.max(QuestShape.MIN_ICON_SCALE,
                    Math.min(QuestShape.MAX_ICON_SCALE, Math.round((current + step * 0.05) * 100) / 100.0));
            settingsDraft.iconScale(next, revision());
            sendField(editTarget(), key, new JsonPrimitive(next));
            return;
        }
        int current = (int) settingsDraft.size(key.equals("size") ? intField(quest, key, 48)
                : intField(quest, key, 0));
        int delta = key.equals("x") || key.equals("y") ? 8 : 1;
        int next = current + step * delta;
        if (key.equals("size")) {
            next = Math.max(dev.ellipog.tasked.client.dev.QuestSettingsLayout.MIN_SIZE,
                    Math.min(dev.ellipog.tasked.client.dev.QuestSettingsLayout.MAX_SIZE, next));
            settingsDraft.size(next, revision());
        }
        if (key.equals("minRequired")) {
            // Bounded by the list above it: "3 of 2" is not a rule, and a validator that refuses it is
            // a validator the arrow should not have been able to reach.
            next = Math.max(0, Math.min(QuestPanelLayout.strings(quest, "dependsOn").size(), next));
        }
        else if (key.equals("maxCompletableDependents") || key.equals("invisibleUntilTasks")) {
            next = Math.max(0, Math.min(dev.ellipog.tasked.quest.QuestRules.MAX_COUNT, next));
        }
        sendField(editTarget(), key, new JsonPrimitive((long) next));
    }

    /** A slider drag: the value under the pointer, remembered for the preview and not yet sent. */
    private void dragSliderTo(double mouseX) {
        if (draggingSlider == null || settingsLayout == null) {
            return;
        }
        Slot slot = settingsLayout.slot(draggingSlider);
        if (slot == null) {
            return;
        }
        // Through the viewport, because the pointer is in screen coordinates and the slot is not. This
        // read the layout's own slot until a report came back that a slider "drags to max and stays
        // there": every press in the column was far right of the track it thought it was on, so the
        // value clamped to the maximum on the first pixel. The drawing had been mapped; this had not.
        dev.ellipog.armature.client.ui.kit.Viewport column = settingsView.viewport();
        if (draggingSlider.equals("size")) {
            draggingValue = dev.ellipog.tasked.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, dev.ellipog.tasked.client.dev.QuestSettingsLayout.MIN_SIZE,
                    dev.ellipog.tasked.client.dev.QuestSettingsLayout.MAX_SIZE, true);
        }
        else if (draggingSlider.equals("rotation")) {
            draggingValue = dev.ellipog.tasked.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, dev.ellipog.tasked.client.dev.QuestSettingsLayout.MIN_ROTATION,
                    dev.ellipog.tasked.client.dev.QuestSettingsLayout.MAX_ROTATION, false);
        }
        else {
            draggingValue = dev.ellipog.tasked.client.dev.QuestSettingsLayout.valueAtScreen(slot,
                    column, mouseX, 25, 100, false) / 100.0;
        }
        stampDragging(revision());
    }

    /**
     * Stamps the value under the pointer into the preview draft, at a revision.
     *
     * <p>Called on every pointer move and again from the frame path, because a drag outlives tree
     * revisions: the settings draft expires on a revision move, and without the re-stamp the preview
     * would snap back to the server's value mid-gesture — and the release, which commits the dragged
     * value, would otherwise look like it committed a value the author never saw.
     */
    private void stampDragging(long revision) {
        if (draggingSlider == null) {
            return;
        }
        if (draggingSlider.equals("size")) {
            settingsDraft.size((int) Math.round(draggingValue), revision);
        }
        else if (draggingSlider.equals("rotation")) {
            settingsDraft.rotation((int) Math.round(draggingValue), revision);
        }
        else {
            settingsDraft.iconScale(draggingValue, revision);
        }
    }

    /**
     * A slider's release: the one commit for the whole drag, from the value the pointer was on.
     *
     * <p>The dragged value rather than the draft, and that is the fix for a real fault: the settings
     * draft expires on any tree revision, and a revision landing between the last pointer move and
     * the release would have committed the value the drag <i>started</i> from — the knob snapped back
     * and the old number went to the file. See {@link #stampDragging}.
     */
    private void releaseSlider() {
        if (draggingSlider == null || editTarget() == null) {
            draggingSlider = null;
            return;
        }
        String key = draggingSlider;
        double value = draggingValue;
        draggingSlider = null;
        if (key.equals("rotation") || key.equals("size")) {
            sendField(editTarget(), key, new JsonPrimitive((long) Math.round(value)));
        }
        else {
            sendField(editTarget(), key, new JsonPrimitive(value));
        }
    }

    /** The revision the client's tree is at, for the draft's expiry. */
    private long revision() {
        return ClientQuestCache.treeRevision();
    }

    /**
     * A settings-page number, read from the tree the page is drawn from — which already carries the
     * draft, so an arrow or a slider shows what it asked for before the replica catches up.
     */
    private int intField(JsonObject quest, String key, int fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : fallback;
    }

    /** The same, for a fractional field: see {@link #intField}. */
    private double doubleField(JsonObject quest, String key, double fallback) {
        JsonElement value = quest == null ? null : QuestPanelLayout.get(quest, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : fallback;
    }

    /**
     * Opens or closes the settings page.
     *
     * <p>Opening it closes the pickers, because the page takes the body and a picker behind it would be
     * a second thing claiming presses that are the page's. Closing it forgets the draft: a value the
     * page was asking for is the page's own state, and leaving it behind would make the canvas draw a
     * number the file does not have.
     */
    private void toggleSettings() {
        settingsOpen = !settingsOpen;
        draggingSlider = null;
        if (settingsOpen) {
            pickingEntryType = null;
            pickingConditionFor = null;
            pickingItemPath = null;
        }
        else {
            settingsDraft.clear();
        }
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
        // hover carried over would light up a row nobody is pointing at for a fifth of a second. The
        // folds go with them: an entry folded here is not the entry at that index in the next quest.
        rowHover.clear();
        entryFolded.clear();
        rebuildWidgets();
    }

    private void closeOverlay() {
        overlay = Overlay.NONE;
        overlayQuest = null;
        overlayView.scrollTo(0);
        rowHover.clear();
        // The chain of jumps ends with the card: a stale stack would replay a trail from a card the
        // reader closed, and the next card they open is a fresh session with its own history.
        overlayHistory.clear();
        // The folds are the card's too: the next card has its own list, and an index folded here names
        // nothing there.
        entryFolded.clear();
        // The editor's transient state goes with the card: a picker left armed would greet the next
        // quest with a list of types, and a Delete left confirmed would delete on one press.
        pickingEntryType = null;
        // And the condition picker's, which names an entry of the quest being closed for the same reason.
        pickingConditionFor = null;
        // And the item picker's, for the same reason: its path names a field of the quest being closed.
        closeItemPicker();
        confirmingDelete = false;
        settingsOpen = false;
        draggingSlider = null;
        settingsDraft.clear();
        // The widgets go with the clear; the references and the path must not outlive them.
        inlineField = null;
        inlineArea = null;
        editingPath = null;
        rebuildWidgets();
    }

    /**
     * Opens a quest's card from a "Requires" jump, putting the card being left on the back stack.
     *
     * <p>Navigation rather than selection: the reader is following a chain, and the chain is the
     * context — so the card being left keeps its scroll and its folds, and the header's arrow is one
     * press away. The canvas selection follows the jump as well, because the card is a detour: closing
     * it should leave the canvas where the reader ended up, not where they started.
     *
     * <p>A quest in another chapter is reached by switching chapters first, exactly as the sidebar
     * would: a card cannot be shown for a chapter the book is not on, and in edit mode every commit
     * would otherwise go to the wrong file.
     */
    private void navigateToQuest(String questId) {
        ClientQuestCache.Entry target = entryFor(questId);
        if (target == null || questId.equals(overlayQuest)) {
            // An id the tree does not know cannot be opened, and a jump to the card already showing is
            // not a jump. Both are silent: there is nothing here for a player to fix.
            return;
        }
        if (overlay == Overlay.QUEST && overlayQuest != null) {
            overlayHistory.add(new CardVisit(overlayQuest, overlayView.viewport().scrollY(),
                    Set.copyOf(entryFolded)));
        }
        if (!target.chapterId().equals(effectiveChapter())) {
            selectChapter(target.chapterId());
        }
        selectedQuest = questId;
        multiSelection.clear();
        openOverlay(questId);
    }

    /** The header's arrow: back to the card this one was reached from, as it was left. */
    private void backInCards() {
        if (overlayHistory.isEmpty()) {
            return;
        }
        CardVisit visit = overlayHistory.remove(overlayHistory.size() - 1);
        ClientQuestCache.Entry target = entryFor(visit.questId());
        if (target == null) {
            // The quest was deleted while its card sat on the stack: a blank card is worse than a
            // dropped visit, so the visit goes and the button rebuilds without it.
            rebuildWidgets();
            return;
        }
        if (!target.chapterId().equals(effectiveChapter())) {
            selectChapter(target.chapterId());
        }
        selectedQuest = visit.questId();
        multiSelection.clear();
        openOverlay(visit.questId());
        // `openOverlay` resets the body to the top and drops the folds; the visit remembers how this
        // card was left, and a step back that lost the reader's place would not be a step back.
        overlayView.scrollTo(visit.scrollY());
        entryFolded.clear();
        entryFolded.addAll(visit.folded());
    }

    /**
     * Takes the canvas to a quest: closes the card, switches chapter when the quest lives elsewhere,
     * glides the camera until the node is centred, and flashes its outline.
     *
     * <p>What the prerequisite row's locate icon and the shift/middle-click gesture both call. The
     * flash is the half that answers "which of these forty nodes is it": a camera that arrives at an
     * unremarkable square has moved the question rather than answered it.
     */
    private void locateOnCanvas(String questId) {
        ClientQuestCache.Entry target = entryFor(questId);
        if (target == null) {
            status("No quest " + questId + " to show", true);
            return;
        }
        closeOverlay();
        if (!target.chapterId().equals(effectiveChapter())) {
            selectChapter(target.chapterId());
        }
        selectedQuest = questId;
        multiSelection.clear();
        // The view is the caller's now, not the chapter auto-centre's: `centreCanvas` re-centres a
        // chapter once, and a glide it kept overriding would never arrive.
        pannedChapter = effectiveChapter();
        centred = true;
        startGlide(target);
        flashQuest = questId;
        flashStart = Util.getMillis();
    }

    /** Starts the camera's glide to a node's centre, from where the view stands now. */
    private void startGlide(ClientQuestCache.Entry entry) {
        Viewport view = viewport();
        glideQuest = entry.id();
        glideStart = Util.getMillis();
        glideFromX = view.offsetX();
        glideFromY = view.offsetY();
        // Content coordinates for the node's middle, through the drawing's own helpers — so the target
        // is the node as it is drawn, draft size included, rather than a second opinion about where it
        // is. The zoom does not change: "show me where it is" is not "zoom in on it".
        glideToX = CanvasReveal.offsetX(view.viewWidth(), view.contentX(nodeCentreX(entry)), view.scale());
        glideToY = CanvasReveal.offsetY(view.viewHeight(), view.contentY(nodeCentreY(entry)), view.scale());
    }

    /**
     * Advances the camera's glide, if one is in flight.
     *
     * <p>Called once a frame from the canvas drawing, which is the only place the move can be seen: the
     * canvas is not drawn while a card is up, so a glide cannot run on behind one.
     */
    private void advanceGlide(long now) {
        if (glideQuest == null) {
            return;
        }
        ClientQuestCache.Entry target = entryFor(glideQuest);
        if (target == null || !target.chapterId().equals(effectiveChapter())) {
            // The chapter changed under the glide (a sidebar press mid-flight): the view belongs to the
            // chapter now, and dragging it to a node that is not on screen is the wrong kind of help.
            glideQuest = null;
            return;
        }
        long elapsed = now - glideStart;
        Viewport view = viewport();
        if (elapsed >= CanvasReveal.GLIDE_MILLIS) {
            view.setOffset(glideToX, glideToY);
            glideQuest = null;
            return;
        }
        view.setOffset(CanvasReveal.glide(glideFromX, glideToX, elapsed, CanvasReveal.GLIDE_MILLIS),
                CanvasReveal.glide(glideFromY, glideToY, elapsed, CanvasReveal.GLIDE_MILLIS));
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
            // A locked task has no button: the press would be refused, and a button that refuses is
            // worse than no button at all. The row says locked and its hover says what is missing.
            if (task.manual() && ClientQuestCache.taskLockOf(quest.id(), i).isEmpty()
                    && ClientQuestCache.taskProgressOf(quest.id(), i) < task.count()) {
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
        GuiRenderer renderer = DevMode.on()
                ? new dev.ellipog.tasked.client.dev.CountingRenderer(new GuiGraphicsRenderer(graphics))
                : new GuiGraphicsRenderer(graphics);

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
        // depth rather than by order, which is what "this is the top layer" actually means. It is not
        // the *top* layer any more -- the modal's card and the tooltips have bands of their own above
        // it, because a chrome control can carry an icon of its own; see `MODAL_Z`.
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
                // The chapter rows' reward counts, over the labels they belong to and inside the same
                // clip: a count drawn outside the list would be a count over the header.
                drawSidebarRewardCounts(renderer, mouseX, mouseY);
                // The drag's own marks, above the rows it is about and inside the clip for the same
                // reason the rows are: a seam line that escaped the list would draw into the header.
                drawSidebarDrag(renderer);
                // And the menu, which is outside the rows' clip only in the sense that it overhangs
                // them -- it belongs to the list, and it is drawn after it so nothing paints over it.
                drawMenu(renderer, mouseX, mouseY);
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
            if (rewardsButton != null) {
                // And the one this list forgot. The rewards button is built for every player and added as
                // a widget, so it was clickable from the day it landed -- and invisible, because the
                // widget pass is clipped to the band below the header and nothing here drew it. A control
                // that exists, takes clicks and cannot be seen is the report this block exists to prevent;
                // it happened because the draw list is hand-written and the build site is somewhere else.
                rewardsButton.hoverTold(rewardsButton.isMouseOver(mouseX, mouseY)).draw(renderer);
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

                // The card and its controls are drawn one band above the rest of the chrome, and that
                // is not tidiness: the chrome is not flat either. A control in it can carry an item
                // icon, and item rendering writes depth 150 above the pose it inherits -- the sidebar's
                // rows do exactly that -- so the chrome's own layer can leave depth `CHROME_Z + 150`
                // behind. A card at `CHROME_Z` would fail the depth test at every one of those pixels,
                // and the sidebar's icons would stand in the middle of an open dialog. `MODAL_Z` clears
                // them; see its javadoc for the arithmetic.
                pose.pushPose();
                pose.translate(0F, 0F, MODAL_Z - CHROME_Z);
                try {
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
                    // And the list holds exactly the modal's own controls: `beginModalControls` cleared it
                    // after the book's were built, the same boundary `setBookControlsActive` uses. The
                    // book stays behind the card, which is where its own comment says it belongs.
                    //
                    // The controls draw inside the chapter's palette when the modal belongs to the
                    // chapter -- the card's buttons, the picker's search box, a rename field. Party,
                    // choice and rewards are player cards and keep the main theme, by position rather
                    // than by a special case: no scope is opened for them. See `chapterBoundOverlay`.
                    if (chapterBoundOverlay()) {
                        try (ArmatureTheme.Scope theme = ArmatureTheme.scope(viewportTheme())) {
                            for (java.util.function.Consumer<GuiRenderer> redraw : modalRedraws) {
                                redraw.accept(renderer);
                            }
                            drawOpenEditor(renderer);
                        }
                    }
                    else {
                        for (java.util.function.Consumer<GuiRenderer> redraw : modalRedraws) {
                            redraw.accept(renderer);
                        }
                        drawOpenEditor(renderer);
                    }
                }
                finally {
                    pose.popPose();
                }
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
            // The notices first, so a tooltip -- which is what the pointer is asking for -- stays on top.
            // They keep the main theme: a toast is the book talking, not the chapter.
            drawToasts(renderer, Util.getMillis());
            // The tooltips describe what is under the pointer, so they wear that surface's palette: a
            // caption over a chapter's canvas is that chapter's, and one over the sidebar is the book's
            // own. The toasts above are outside this scope on purpose.
            try (ArmatureTheme.Scope theme = ArmatureTheme.scope(tooltipTheme(mouseX, mouseY))) {
                drawPendingLabels(renderer);
                drawRowTooltips(renderer, mouseX, mouseY);
                drawLinkTarget(renderer, mouseX, mouseY);
                drawTooltips(renderer, mouseX, mouseY);
            }
            pose.popPose();

            // Consumed, because they are drawn here rather than where they are collected: the card fills
            // the list while it draws, and a frame that draws no card (the overlay closed, a chapter
            // switch) must not leave last frame's label standing over the canvas.
            pendingLabels.clear();
            rowTooltips.clear();
        }
        finally {
            pose.popPose();
        }
        // The frame is over: the counting renderer (dev mode only) logs its totals at most once a second.
        // Here rather than anywhere earlier because the tooltips above are the last thing a frame draws.
        dev.ellipog.tasked.client.dev.CountingRenderer.endFrame(renderer);
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
     * <p>400 is above the item layer, and the layers that sit above <i>it</i> are named below: the
     * modal's card at {@code MODAL_Z} and the tooltips at {@code TOOLTIP_Z}. Vanilla's own tooltips sit
     * around the same figure, which is a coincidence rather than a dependency; what matters is only that
     * it is greater than 150, and the value is a named constant so that the two figures can be compared
     * by reading rather than by recalling.
     *
     * <p>It would be better if this were not needed, and the honest alternative is worse: clip the
     * canvas around the cluster, which means drawing the canvas twice, splitting any node that straddles
     * the boundary, and re-rendering every item icon for the privilege. Raising the chrome's Z costs a
     * pose push.
     */
    static final float CHROME_Z = 400F;

    /**
     * How far above the chrome the modal's card and its own controls are drawn.
     *
     * <h2>Why the card is not at {@code CHROME_Z}</h2>
     *
     * <p>Because the chrome is not flat: a control drawn in it can carry an item icon -- the sidebar's
     * rows do -- and item rendering writes depth <b>150 above the pose it inherits</b>, the same fact
     * that made the chrome a band of its own. So the chrome's layer can leave depth {@code CHROME_Z +
     * 150} sitting in the buffer, and the card, which is meant to cover the book, has to be above that
     * rather than beside it. At the chrome's own Z the card's fills would fail the depth test at every
     * icon pixel and the sidebar's icons would stand in the middle of an open dialog.
     *
     * <p>Two hundred rather than 151, for the same reason as {@code TOOLTIP_Z}: the step only has to
     * clear the icon layer, and a round number puts all three bands on one line to be read together.
     */
    static final float MODAL_Z = CHROME_Z + 200F;

    /**
     * How far above the modal the two things that belong over <b>everything</b> are drawn: a control's
     * tooltip, and the type label beside a hovered icon.
     *
     * <p>A step above {@code MODAL_Z} rather than the same Z, because the card holds item icons too:
     * item rendering writes depth, so "drawn later" is not "on top" at equal Z. That is the same
     * arithmetic {@code MODAL_Z} explains, and the tooltip has to clear the modal's icons exactly as the
     * card had to clear the chrome's.
     */
    static final float TOOLTIP_Z = MODAL_Z + 200F;

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
     * The party panel, whichever face it is showing.
     *
     * <h2>Two columns, two viewports, one rectangle each</h2>
     *
     * <p>Every position below comes from a layout the build pass recorded, through the viewport that
     * owns the scroll \u2014 the same rule the panel always had, now per column. The clip is the
     * column's, so a row scrolled past its edge is cut off rather than drawn over the other column.
     */
    private void drawPartyOverlay(GuiRenderer r, int mouseX, int mouseY, long now) {
        BookGeometry.Rect card = partyCard;
        if (card == null || partyLeftFace == null) {
            return;
        }

        // The armed Disband lapses on its own: a control still saying "press again" a minute after the
        // first press is a control that lies about what it will do next.
        if (disbandPress.lapsed(now)) {
            disarmDisband();
        }

        // No dim: `renderWith` fills one before dispatching to an overlay, and a second put two
        // translucent blacks over a world that is not otherwise drawn.
        ArmatureTheme.panel(r, card.x(), card.y(), card.width(), card.height(),
                ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        drawPartyFace(r, partyLeftFace, partyLeftLayout, partyLeftView, partyColumn(true), now);
        if (partyRightFace != null) {
            drawPartyFace(r, partyRightFace, partyRightLayout, partyRightView, partyColumn(false), now);
        }

        // The roster rows are the left column's own composition rather than `Line`s -- see
        // `buildPartyActive` -- so they are drawn here, against the same layout their buttons were
        // registered against.
        PartyRoster roster = partyRoster();
        if (roster.isReal() && partyPhase == PartyPhase.NONE && partiesAvailable()
                && partyRightFace != null && partyLeftLayout != null) {
            drawPartyMembers(r, partyLeftLayout, roster, partyColumn(true), now);
        }
    }

    /**
     * One column: its rows' text, and the scrollbar when there is more than fits.
     *
     * <p>Re-applied every frame rather than only at build time, like the quest overlay's body: a
     * scroll that happened since the last frame is already in the widget positions, and a resize
     * cannot leave the clamp measuring the previous window.
     */
    private void drawPartyFace(GuiRenderer r, PartyPanelLayout.Face face, Layout layout,
                               ScrollView view, Viewport body, long now) {
        if (layout == null) {
            return;
        }
        view.apply(layout, body.viewWidth());
        // Placement first, then the drawing: every widget this column owns gets its rectangle from the
        // same layout the rows are drawn from, so a switch cannot sit beside the wrong label.
        placePartyLineControls(face, layout, body);
        placePartyField(partyCreateField, face, layout, "solo:create", body);
        placePartyField(partyNameField, face, layout, "left:name", body);
        placePartyField(partySearchField, face, layout, "right:search", body);
        // The click target over the party's name, when this face has that row and the viewer may rename.
        placePartyWidget(partyControls.get("left:name:edit"), layout.slot("left:name"), body);
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            for (PartyPanelLayout.Line line : face.lines()) {
                Slot slot = layout.slot(line.key());
                if (slot == null || line.label().isEmpty()) {
                    continue;
                }
                Slot onScreen = screenSlot(body, slot);
                int colour = line.header()
                        ? ArmatureTheme.heading()
                        : ArmatureTheme.body();
                String label = partyText(line.label());
                // The label stops short of the room the row reserved for its controls, so it cannot run
                // under a switch or a button.
                r.text(Measure.truncate(label,
                                Math.max(0, onScreen.width() - line.controlRoom() - 8), textMeasure(r)),
                        onScreen.x() + 4, onScreen.y() + (onScreen.height() - r.lineHeight()) / 2,
                        colour);
                if (line.detail() != null && !line.detail().isEmpty()) {
                    String detail = partyText(line.detail());
                    int detailX = onScreen.right() - line.controlRoom() - 4 - r.textWidth(detail);
                    if (detailX > onScreen.x() + 4) {
                        r.text(detail, detailX,
                                onScreen.y() + (onScreen.height() - r.lineHeight()) / 2,
                                ArmatureTheme.faint());
                    }
                }
            }
        }
        view.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /**
     * The roster's member rows: portrait, presence, name, and the role chip.
     *
     * <h2>The chip and the hover controls share one strip</h2>
     *
     * <p>Both belong at the row's right edge and only one can matter at a time: the chip says who
     * somebody is until the pointer is on their row, and then the owner's Transfer and Remove replace
     * it \u2014 which is how two controls fit in a column that also has to hold a name. The wash, the
     * chip and the buttons all read the same {@link Hover}, so the row cannot be hovered for one of
     * them and not the others.
     */
    private void drawPartyMembers(GuiRenderer r, Layout layout, PartyRoster roster, Viewport body,
                                  long now) {
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
            // The name may use the whole slot: the slot is already narrowed by the action strip, so
            // subtracting the strip again here would cost the name its room twice -- which it did,
            // truncating every name to three characters beside three owner controls that are only
            // *sometimes* drawn.
            r.text(Measure.truncate(member.label(),
                            Math.max(0, onScreen.width() - (nameX - onScreen.x()) - 4),
                            textMeasure(r)),
                    nameX, textY, member.self() ? ArmatureTheme.title() : ArmatureTheme.body());

            // The chip, in the same strip the hover controls occupy. Drawn only while the row is at
            // rest, so a hover reads as "these are the actions" rather than as a third state.
            if (hover <= 0.05F) {
                drawRoleChip(r, member, onScreen, textY);
            }

            // And the controls' visibility follows the same hover. Placed first -- the rectangle comes
            // from the row's own strip, like every other control here -- and then hidden unless the row
            // is hovered, so a scrolled-out button is neither drawn nor clickable.
            Slot rowSlot = layout.slot(member.key());
            ArmatureButton remove = partyRemoveButtons.get(member.id());
            if (remove != null) {
                placePartyWidget(remove, rowSlot == null ? null
                        : PartyRoster.removeSlot(member, rowSlot), body);
                remove.visible = remove.visible && (hover > 0.05F || remove.isHoveredOrFocused());
            }
            ArmatureButton transfer = partyTransferButtons.get(member.id());
            if (transfer != null) {
                placePartyWidget(transfer, rowSlot == null ? null
                        : PartyRoster.transferSlot(member, rowSlot), body);
                transfer.visible = transfer.visible && (hover > 0.05F || transfer.isHoveredOrFocused());
            }
        }
    }

    /**
     * The role chip: a small bordered badge with the role's word.
     *
     * <h2>Why the colours are tokens rather than the spec's gold and slate</h2>
     *
     * <p>Armature's themes carry no gold, and a colour written here would be the one value in this
     * screen a theme could not reach \u2014 the mistake every other drawing in this file avoids. So the
     * owner wears the brightest ink and everybody else the faintest, which reads as the same
     * hierarchy on every palette instead of on one.
     */
    private void drawRoleChip(GuiRenderer r, PartyRoster.Member member, Slot onScreen, int textY) {
        String role = member.roleLabel().toUpperCase(java.util.Locale.ROOT);
        int width = r.textWidth(role) + PartyPanelLayout.CHIP_PAD * 2;
        // Right-aligned in the strip the row reserved, where the hover buttons also go: the chip and
        // the controls share one place because only one of them is ever the answer.
        int x = onScreen.right() + PartyRoster.actionStrip() - PartyPanelLayout.CONTROL_INSET - width;
        if (x <= onScreen.right() + 1) {
            return;
        }
        int chipY = onScreen.y() + (onScreen.height() - PartyPanelLayout.CHIP_HEIGHT) / 2;
        int ink = member.owner() ? ArmatureTheme.title() : ArmatureTheme.faint();
        ArmatureTheme.panel(r, x, chipY, width, PartyPanelLayout.CHIP_HEIGHT,
                ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        r.text(role, x + PartyPanelLayout.CHIP_PAD,
                chipY + (PartyPanelLayout.CHIP_HEIGHT - r.lineHeight()) / 2, ink);
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
            // The one line the not-editing tab has to add, in the band the panel reserves for status:
            // full width, above the list, and it does not scroll away with the row that names the state.
            // The band is the theme tab's feedback line's, drawn by `ToolsPanel` there; this tab has no
            // status of its own, so it is empty except for this.
            if (!mayEditNow()) {
                r.text(Measure.truncate(ChapterPanelLayout.EDIT_MODE_HINT, toolsFrame.feedback().width(),
                                Measure.of(r::textWidth, r.lineHeight())),
                        toolsFrame.feedback().x(), toolsFrame.feedback().y(), ArmatureTheme.faint());
            }
            // The chapter's identity first, on the band the frame kept fixed above the list: it is what
            // says whose fields these are, and it is where the icon is seen as an item rather than as
            // the id the row's button carries.
            ChapterPanel.drawHeader(r, toolsFrame.preview(),
                    ToolsLayout.chapterHeader(toolsFrame.preview()), chapterHeader, chapterIcon,
                    chapterIconId);
            QuestPanel.drawRows(r, toolsFrame.list(), toolsView.viewport(), chapterLayout, chapterRows,
                    mouseX, mouseY, InspectLayout.Mode.STACKED);
            // The cycling rows' controls, drawn here because no widget may cover their arrows -- the
            // label band above each one is the panel's, the arrows and the value are ChapterPanel's, and
            // the press is the screen's from the layout's own boxes. See `ChapterPanel.drawChoice`.
            //
            // **Clipped to the list**, which is what `drawRows` does for the rows it draws and what this
            // loop was missing: a choice band is placed by the scroll viewport, so once a row scrolled
            // past the list's edge its arrows and value were drawn over the panel above it -- the report
            // was "when scrolled they don't go underneath and clip like they should". The clip is the
            // same rectangle the rows are clipped to, so a control and its label leave together.
            try (GuiRenderer.Scoped clip = r.clip(toolsFrame.list().x(), toolsFrame.list().y(),
                    toolsFrame.list().right(), toolsFrame.list().bottom())) {
                for (InspectRow row : chapterRows) {
                    ChapterPanelLayout.Choice choice = row.kind() == InspectRow.Kind.FIELD
                            ? ChapterPanelLayout.choiceForKey(row.key()) : null;
                    if (choice == null) {
                        continue;
                    }
                    Slot slot = chapterLayout.slot(row.key());
                    if (slot == null) {
                        continue;
                    }
                    Slot band = InspectLayout.onScreen(toolsView.viewport(),
                            InspectLayout.controlBand(slot));
                    ChapterPanel.drawChoice(r, band, ChapterPanelLayout.choiceLabel(choice, row.value()),
                            row.value().isEmpty(), mouseX, mouseY);
                }
                // The rows the drag reorders, in the order they are drawn, and the line over them. Only
                // the quest rows take part -- the identity fields above them are not a list.
                dragRowSlots.put("chapter", chapterQuestRowRects());
                if (dragRowLive && "chapter".equals(dragRowMember)) {
                    drawRowDragIndicator(r, dragRowSlots.get("chapter"), dragRowPointerY,
                            onScreenBand(toolsView.viewport()));
                }
            }
        }
        else {
            if (toolsLayout == null) {
                return;
            }
            ToolsPanel.draw(r, toolsFrame, toolsView.viewport(), toolsLayout, toolsRows,
                    new ToolsPanel.State(toolsSelected, toolsFeedback, toolsFeedbackIsError,
                            toolsSelected != null, editedTheme(), editedRadius(), editedRadiusChosen()),
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
        // The settings page's pending values expire on the same signal: the tree arriving is what makes
        // the file's own answer the current one, and the draft is only there to cover the round trip.
        settingsDraft.onRevision(revision);
        // A drag outlives revisions: what the pointer is showing is the author's current ask, so the
        // expiry must not snap it back mid-gesture — the release commits the dragged value.
        stampDragging(revision);
        // The card fields' drafts expire on the copy instead, because these values are drawn from the
        // replica and the replica lags: a draft is dropped when the copy holds what was asked for, and
        // a copy that disagrees past the backstop wins. See FieldDraft for why the two rules differ.
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(effectiveChapter());
        fieldDraft.reconcile(effectiveChapter(),
                copy == null ? null : copy.revision(),
                (owner, path) -> {
                    JsonObject tree = copy == null ? null
                            : dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER.equals(owner)
                                    ? copy.chapterTree()
                                    : copy.quests().get(owner);
                    return tree == null ? null : QuestPanelLayout.get(tree, path);
                },
                Util.getMillis());
        // And the selection, because an id this tree does not hold is a phantom the next gesture would
        // act on -- see `pruneSelection` for why the revision is the right moment and the only one.
        pruneSelection(revision);
        // And the same revision decides whether a panel's copy of the chapter is current. `claim` is what
        // keeps this from being one request per frame: it says yes only while no usable copy exists, and
        // no faster than its retry window -- so a refusal or an empty answer is retried rather than
        // leaving the panel stuck, without turning a broken chapter into a request flood.
        if (mayEditNow() && ClientChapterReplica.claim(effectiveChapter(), revision, Util.getMillis())) {
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
     * was exactly that, *"items render over tooltips"*. The card's contents are all at {@code MODAL_Z};
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
            // The tooltip tokens, the same three the toolkit's box uses: a caption anchored to an icon
            // and a tooltip anchored to the pointer are the same kind of surface, and a theme that set
            // one and not the other would look half-done.
            r.fill(box.x() - 1, box.y() - 1, box.right() + 1, box.bottom() + 1,
                    ArmatureTheme.tooltipEdge());
            r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.tooltipFill());
            r.text(label.text(), box.x() + 4, box.y() + (box.height() - 8) / 2,
                    ArmatureTheme.tooltipText());
        }
    }

    /**
     * The type's own explanation, under a hovered task or reward row.
     *
     * <h2>What the row cannot say, and what the sentence therefore leaves out</h2>
     *
     * <p>A row is one sentence: "Kill Zombie", "Grant the stage my_pack:inducted". That is the right
     * length for a list, and it is not enough to <i>act</i> on -- the reader who has never met the type
     * cannot tell a stage from a statistic from a command, and the row has no room to say. The tooltip
     * is where that answer goes: the type's name, what it is for, the fields it takes, and the id a
     * file spells.
     *
     * <p>Collected while the card draws and painted here, with the tooltips -- see
     * {@code rowTooltips} for why the card cannot draw it where the row is.
     */
    private void drawRowTooltips(GuiRenderer r, int mouseX, int mouseY) {
        for (RowTooltip tooltip : rowTooltips) {
            if (tooltip.box().contains(mouseX, mouseY)) {
                drawTooltip(r, tooltip.lines(), mouseX, mouseY);
                return;
            }
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
    /**
     * The box near the pointer, from the toolkit: the theme's tooltip tokens and the flip geometry
     * every screen shares. The wrapper stays because the call sites read better with the screen's own
     * size folded in.
     */
    private void drawTooltip(GuiRenderer r, List<String> lines, int mouseX, int mouseY) {
        Tooltips.draw(r, lines, mouseX, mouseY, width, height);
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
            // Everything on the canvas in **one batch**, and the placement is the whole correctness
            // argument: the clip is open, the batch flushes before the clip closes, so every queued fill
            // is submitted under the canvas's own scissor. See GuiRenderer#batched.
            //
            // Without it this is one GPU batch submission per fill -- the context a screen draws into is
            // unmanaged, so every fill ends in its own flush -- and a chapter of curved lines is one fill
            // per screen pixel of ink. The count is what the player feels as zoom lag: the same chapter
            // issues tens of thousands of submissions at 2.2x and a handful with this wrapper.
            hovered = r.batched(() -> drawCanvasContents(r, mouseX, mouseY, quests, now));
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
        // The locate glide first, before anything reads a screen position: a frame is the unit the
        // camera moves in, and this is the only drawing the move can be seen in -- the canvas is not
        // drawn while a card is up.
        advanceGlide(now);
        r.fill(canvasLeft(), canvasTop(), canvasRight(), canvasBottom(), ArmatureTheme.canvas());

        // The node under the pointer first, because a node owns the right-click and so owns the hover
        // cue too -- a line brightening under a node would promise a menu the press will not open. The
        // line test mirrors `edgeAt` exactly (the same candidates and the same reach), so the ink lights
        // precisely where the menu opens; and it is edit-gated with the menu, so it never offers an
        // action this client cannot take.
        ClientQuestCache.Entry hovered = nodeAt(mouseX, mouseY, quests);

        // Every line the chapter draws, this frame: endpoints, style and route computed **once**. The
        // drawing, the hover and the handle layer all read this list -- they used to each rebuild every
        // path, which is up to three full walks of every curve per frame, the CPU half of the zoom cost.
        List<FrameEdge> edges = frameEdges(quests);

        // The line hover, recomputed only when the pointer or the view moved: a still pointer over a
        // still canvas cannot change which line is nearest, and the walk it would redo is every pixel
        // of every route.
        String[] hoveredEdge = mayEditNow() && hovered == null ? lineHover(edges, mouseX, mouseY) : null;
        // A value-equal key -- a List, not the candidate's own array -- because Hover compares by
        // equals, and a fresh array every frame would restart the fade every frame and never arrive.
        edgeHover.update(hoveredEdge == null ? null : List.of(hoveredEdge[0], hoveredEdge[1]), now);

        // Dependency lines first, so nodes draw over them.
        for (FrameEdge edge : edges) {
            drawStyledPath(r, edge.path(), edge.style(),
                    lineInk(edge.baseColour(), edge.fromId(), edge.toId(), now));
        }

        // Told which node the pointer is over, once, before any node is drawn. Then every node asks how
        // hovered it is -- which is what makes the ring ease in as the pointer arrives and ease out as
        // it leaves, and what makes a fast sweep across a chapter look like following the pointer
        // rather than like flicker. See Hover for why both halves have to ease.
        nodeHover.update(hovered == null ? null : hovered.id(), now);

        // Only the nodes the canvas can show. The scissor already hides the rest, but a clipped fill is
        // still a fill that was built, transformed and submitted -- and at high zoom most of a chapter is
        // off-canvas, which is why this is the node half of the zoom fix.
        List<ClientQuestCache.Entry> visible = quests.stream().filter(this::nodeVisible).toList();
        for (ClientQuestCache.Entry quest : visible) {
            float flash = quest.id().equals(flashQuest)
                    ? CanvasReveal.flash(now - flashStart, CanvasReveal.FLASH_MILLIS) : 0F;
            drawNode(r, quest, nodeHover.amount(quest.id(), now), flash);
        }
        // Titles in their own pass, after every node, so a label can see the other nodes -- see the
        // comment on drawLabels for what happened when it could not. Fed the visible list, because the
        // overlap it tests for is a collision with a node that was *drawn*.
        drawLabels(r, visible);
        // The reward badges last of the node furniture: a title's backdrop is opaque and reaches the
        // corner on a long name, so a badge drawn with the nodes would vanish exactly when the chapter
        // is busiest.
        drawRewardBadges(r, visible);

        // The handle layer **after the nodes**, deliberately: a dot that overlaps a node -- an anchor
        // dragged round to its far side -- has to be on top of it, or the thing in your hand disappears.
        // The frame's own edge list, so the paths it grabs against are the ones that were drawn.
        drawEdgeHandles(r, mouseX, mouseY, edges);

        // The editor's in-progress gestures, drawn over everything they touch: the marquee is what the
        // author is reaching for, and the rubber line is the edge they are about to make.
        if (marqueeActive) {
            drawMarquee(r);
        }
        if (edgeDragLive && edgeDragFrom != null) {
            ClientQuestCache.Entry from = entryFor(edgeDragFrom);
            if (from != null) {
                // The chapter's default, because the landing quest -- whose style the new edge will wear --
                // is not known until the pointer lets go. It is the same style the line gets unless that
                // quest overrides it, which is the closest the drag can honestly promise.
                drawStyled(r, from.chapterDependencyStyle(),
                        new LineArt.Point(nodeScreenX(from) + nodeSize(from) / 2,
                                nodeScreenY(from) + nodeSize(from) / 2),
                        new LineArt.Point((int) edgeDragX, (int) edgeDragY),
                        // The pointer end has no node, so its half is zero and the head rides at the
                        // pointer exactly; the source's own half keeps its end clear of its rim.
                        nodeSize(from) / 2, 0, ArmatureTheme.hoverRing());
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
    private void drawNodeCaption(GuiRenderer r, ClientQuestCache.Entry entry) {
        QuestNodeArt.caption(r, nodeScreenX(entry), nodeScreenY(entry), nodeSize(entry),
                titleOf(entry), canvasLeft(), canvasRight(), canvasBottom());
    }

    // drawIcon(GuiGraphics, ...) used to be here, delegating to ArmatureTheme's copy. Both are gone:
    // the operation is `GuiRenderer.icon` now, so the screen calls it on the renderer it was handed
    // rather than on a helper, and there is no static method on either side to pass the wrong thing to.
    //
    // Worth keeping as a note because of what the indirection cost. This method existed only to forward
    // to another class's static helper, and that helper existed only because a colour table had been
    // asked to solve a rendering problem. Two layers of forwarding around one pose-stack manipulation,
    // and the manipulation is the only part that had anything to say.

    private void drawNode(GuiRenderer r, ClientQuestCache.Entry entry, float hover, float flash) {
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

        // The hover ring's alpha is scaled by the eased hover, so it fades in and out rather than
        // appearing. `translucent` rather than `alphaOf`: HOVER_RING is already 0x80 alpha, and
        // `alphaOf` would discard that and make a fully-hovered ring twice as bright as it has always
        // been. Selection is not animated at all -- the row you are on is a state, not a transition.
        //
        // The locate flash outranks both while it lasts: it is the answer to "where did that quest
        // go", and the one moment the node must not blend in with its neighbours.
        int ring = 0;
        if (flash > 0F) {
            ring = Colour.translucent(ArmatureTheme.selectedRing(), flash);
        }
        else if (hover > 0F || isSelected) {
            ring = isSelected
                    ? ArmatureTheme.selectedRing()
                    : Colour.translucent(ArmatureTheme.hoverRing(), hover);
        }

        // The wash FOLLOWS THE SHAPE, and that is the whole point of drawing it here rather than with a
        // `fill` rectangle over the icon's box -- see `QuestNodeArt` for the drawing and for why it is
        // shared with the settings preview rather than written twice.
        int wash = switch (state) {
            case LOCKED -> ArmatureTheme.nodeDim();
            case COMPLETED -> ArmatureTheme.nodeDoneWash();
            case STARTED, UNLOCKED -> 0;
        };

        // `entry.geometry()` rather than `entry.shape()`: the outline with its rotation applied, built
        // once when the tree arrived rather than per node per frame. A pending shape or rotation — a
        // settings-page change the tree has not carried yet — is drawn through the same cached outline
        // table, so the canvas follows the click; icon scale is read through the draft the same way.
        QuestShape drawnShape = drawnShape(entry);
        int drawnRotation = fieldDraft.number(entry.chapterId(), entry.id(), "rotation", entry.rotation());
        boolean draftedLook = drawnShape != entry.shape() || drawnRotation != entry.rotation();
        QuestNodeArt.draw(r, x, y, new QuestNodeArt.Look(size, drawnShape,
                draftedLook ? ClientQuestCache.geometry(drawnShape, drawnRotation) : entry.geometry(),
                entry.icon(),
                fieldDraft.decimal(entry.chapterId(), entry.id(), "iconScale", entry.iconScale()),
                edge, ring, wash));

        if (mayEditNow() && !questVisible(entry.id())) {
            // Marked, because the author is looking at a node the reader's book does not draw -- and
            // without a mark the only way to find out would be to open the book as a player and notice
            // something missing. A dashed square, deliberately unlike every other ring on this canvas:
            // those mean selection or hover, and this means "you are seeing this and nobody else is".
            drawHiddenMark(r, x, y, size);
        }
    }

    /** A dashed one-pixel square around a node that is hidden from players. */
    private static void drawHiddenMark(GuiRenderer r, int x, int y, int size) {
        int colour = ArmatureTheme.faint();
        for (int i = 0; i < size; i += 4) {
            int length = Math.min(2, size - i);
            r.fill(x + i, y, x + i + length, y + 1, colour);
            r.fill(x + i, y + size - 1, x + i + length, y + size, colour);
            r.fill(x, y + i, x + 1, y + i + length, colour);
            r.fill(x + size - 1, y + i, x + size, y + i + length, colour);
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
                .filter(e -> fieldDraft.flag(e.chapterId(), e.id(), "showTitle", e.showTitle()))
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

            String shown = Measure.truncate(titleOf(entry), room, textMeasure(r));
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
     * The style in force for one line: its own override, else the chapter's, else the built-ins.
     *
     * <p>One expression, and the same one the hover and the hit test use — a line drawn under one style
     * and hit-tested under another is a line whose pixels do not answer the pointer that can see them.
     *
     * <p>A pending write for this line's map wins over the tree, for the same reason the menu reads
     * through {@link #dependencyLinesOf}: with a handle write still in flight the tree is the past, and
     * the menu would offer "Split handles" on a line the author just joined — and seed a split from the
     * stale bend. The synced tree stays the base otherwise: the canvas draws the tree, and the replica
     * can be older than it.
     */
    private static DependencyStyle lineStyle(ClientQuestCache.Entry entry, String dependencyId) {
        JsonElement drafted = fieldDraft.value(entry.chapterId(), entry.id(), "dependencyLines");
        DependencyStyle own = drafted != null && drafted.isJsonObject()
                ? DependencyStyle.from(drafted.getAsJsonObject().get(dependencyId))
                : entry.dependencyLines().getOrDefault(dependencyId, DependencyStyle.UNSET);
        return own.over(entry.chapterDependencyStyle()).resolved();
    }

    /**
     * One line the chapter draws this frame: the two quests it joins, the style it is owed, and its route
     * in screen space — computed once, then read by the drawing, the hover and the handle layer.
     */
    private record FrameEdge(String fromId, String toId, ClientQuestCache.Entry from, ClientQuestCache.Entry to,
                             DependencyStyle style, List<LineArt.Point> path, int baseColour) {

        /** The key the hover and the hit test use: the dependency first, the dependent second. */
        String[] key() {
            return new String[] { fromId, toId };
        }
    }

    /**
     * Every line the chapter draws, this frame, in draw order — dependencies plus a linear chapter's road.
     *
     * <p>One list rather than one loop per caller: the routes used to be rebuilt by the drawing, again by
     * the hover and again by the handle layer, which is up to three full walks of every curve per frame.
     * Building them once is the CPU half of the zoom fix; the batch wrapper in {@code drawCanvas} is the
     * GPU half.
     *
     * <p>A line that cannot reach the canvas is left out entirely — see {@link #mayReachCanvas} — because
     * a clipped fill is still a fill that was built and submitted, and at high zoom most of a chapter is
     * off-canvas.
     */
    private List<FrameEdge> frameEdges(List<ClientQuestCache.Entry> quests) {
        // An id-keyed map for the lookups, so the loops below are linear rather than a scan of every
        // quest per dependency -- the same O(E*N) scan the drawing used to do per frame.
        java.util.Map<String, ClientQuestCache.Entry> byId = new java.util.HashMap<>();
        for (ClientQuestCache.Entry quest : quests) {
            byId.put(quest.id(), quest);
        }

        List<FrameEdge> edges = new ArrayList<>();
        for (ClientQuestCache.Entry quest : quests) {
            if (!dev.ellipog.tasked.client.dev.QuestVisibility
                    .drawsDependencyLines(quest.hideDependencyLines())) {
                // The author asked for the hub without the spokes: the quest is drawn and the lines
                // arriving at it are not. Only the incoming side, so a quest that hides its own lines
                // still appears as a prerequisite of everything that depends on it.
                continue;
            }
            for (String dependencyId : dependenciesOf(quest)) {
                ClientQuestCache.Entry dependency = byId.get(dependencyId);
                if (dependency == null) {
                    // A dependency in another chapter, or one filtered out. Not drawn: a line to
                    // nowhere reads as a rendering fault, and the overlay names the dependency.
                    continue;
                }
                // Coloured by the *dependent's* rule rather than by completion: under the two
                // started-based modes a prerequisite with any task progress already satisfies, and a
                // line drawn dark for it was the canvas telling a player they were stuck when they
                // were not.
                boolean done = dependencyProgressOf(quest)
                        .satisfies(dependencyId, ClientQuestCache::stateOf);
                FrameEdge edge = frameEdge(dependency, quest, dependencyId,
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
                if (edge != null) {
                    edges.add(edge);
                }
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
                ClientQuestCache.Entry dependent = ordered.get(i);
                if (!dev.ellipog.tasked.client.dev.QuestVisibility
                        .drawsDependencyLines(dependent.hideDependencyLines())) {
                    continue;
                }
                boolean done = ClientQuestCache.stateOf(previous.id()) == QuestState.COMPLETED;
                FrameEdge edge = frameEdge(previous, dependent, previous.id(),
                        done ? ArmatureTheme.lineDone() : ArmatureTheme.line());
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    /**
     * One line, ready to draw — or null when it cannot reach the canvas.
     *
     * <p>{@code dependencyId} is the quest the dependent waits on — the key its override is stored
     * under — and the pairing matters: the style belongs to the edge, and the edge is owned by the
     * quest that depends, not by the quest that is depended upon.
     */
    private FrameEdge frameEdge(ClientQuestCache.Entry from, ClientQuestCache.Entry to, String dependencyId,
                                int baseColour) {
        DependencyStyle style = styleFor(to, dependencyId);
        LineArt.Point[] ends = lineEnds(from, to, style);
        if (!mayReachCanvas(ends, from, to)) {
            return null;
        }
        // The halves are zero because the path already ends on each node's rim: the arrowhead only needs
        // its gap, and the ink is not hidden under a node any more.
        return new FrameEdge(from.id(), to.id(), from, to, style, stylePath(style, ends[0], ends[1]), baseColour);
    }

    /**
     * Whether a line between these ends could put any pixel on the canvas.
     *
     * <p>Conservative by construction: the route stays inside the convex hull of the ends and the two
     * control points, which are at most a chord away, so the box is the ends' bounding box grown by the
     * chord, both node sizes and a margin. A line this box cannot hold would not have been visible; one
     * it can hold is drawn even if most of it is off-canvas, which is the honest direction for a cull.
     */
    private boolean mayReachCanvas(LineArt.Point[] ends, ClientQuestCache.Entry from,
                                   ClientQuestCache.Entry to) {
        double chord = Math.hypot(ends[1].x() - ends[0].x(), ends[1].y() - ends[0].y());
        double slack = chord + nodeSize(from) + nodeSize(to) + 32;
        double left = Math.min(ends[0].x(), ends[1].x()) - slack;
        double top = Math.min(ends[0].y(), ends[1].y()) - slack;
        double right = Math.max(ends[0].x(), ends[1].x()) + slack;
        double bottom = Math.max(ends[0].y(), ends[1].y()) + slack;
        return left < canvasRight() && right > canvasLeft() && top < canvasBottom() && bottom > canvasTop();
    }

    /** Whether a node's screen square overlaps the canvas at all. */
    private boolean nodeVisible(ClientQuestCache.Entry entry) {
        int size = nodeSize(entry);
        int x = nodeScreenX(entry);
        int y = nodeScreenY(entry);
        return x < canvasRight() && x + size > canvasLeft() && y < canvasBottom() && y + size > canvasTop();
    }

    /** The frame's edges as hover candidates — the paths already computed, so a walk costs only distance. */
    private static List<LineArt.Candidate<String[]>> frameCandidates(List<FrameEdge> edges) {
        List<LineArt.Candidate<String[]>> candidates = new ArrayList<>(edges.size());
        for (FrameEdge edge : edges) {
            candidates.add(new LineArt.Candidate<>(edge.key(), edge.path()));
        }
        return candidates;
    }

    /** One styled line along a path that is already computed, with the arrowhead gaps the caller wants. */
    private static void drawStyledPath(GuiRenderer r, List<LineArt.Point> path, DependencyStyle style,
                                       int fromHalf, int toHalf, int colour) {
        for (LineArt.Fill fill : LineArt.fills(path, style.weightOr(DependencyStyle.Weight.THIN),
                style.dashOr(DependencyStyle.Dash.SOLID))) {
            r.fill(fill.x1(), fill.y1(), fill.x2(), fill.y2(), MenuFlyoutArt.ink(fill.tone(), colour));
        }
        for (LineArt.Fill fill : LineArt.arrows(path, style.headOr(DependencyStyle.ArrowHead.CHEVRON),
                style.placeOr(DependencyStyle.ArrowPlace.TARGET), style.arrowSpacing(),
                fromHalf, toHalf)) {
            r.fill(fill.x1(), fill.y1(), fill.x2(), fill.y2(), colour);
        }
    }

    /** The same, for a drawn line whose path is already computed and whose ends sit on the rims. */
    private static void drawStyledPath(GuiRenderer r, List<LineArt.Point> path, DependencyStyle style,
                                       int colour) {
        drawStyledPath(r, path, style, 0, 0, colour);
    }

    /**
     * Where a line starts and ends: each node's rim in the other's direction, or this line's own anchor.
     *
     * <p>Automatic by default and exact for the shape, because {@link LineArt#rimPoint} asks the same
     * containment test the clicks and the hover use. An anchor is the fussy override: a line can name an
     * angle for either end, and the other end stays automatic.
     */
    private LineArt.Point[] lineEnds(ClientQuestCache.Entry from, ClientQuestCache.Entry to,
                                     DependencyStyle style) {
        LineArt.Point fromCentre = new LineArt.Point(nodeCentreX(from), nodeCentreY(from));
        LineArt.Point toCentre = new LineArt.Point(nodeCentreX(to), nodeCentreY(to));
        return new LineArt.Point[] {
                endPoint(from, fromCentre, toCentre, style.fromAnchorOrNull()),
                endPoint(to, toCentre, fromCentre, style.toAnchorOrNull()) };
    }

    /** One end: the anchor angle when the line names one, else the node's own rim. */
    private LineArt.Point endPoint(ClientQuestCache.Entry entry, LineArt.Point centre,
                                   LineArt.Point towards, Double anchor) {
        int size = nodeSize(entry);
        int screenX = nodeScreenX(entry);
        int screenY = nodeScreenY(entry);
        // A point along the anchor's direction, then the shape's real rim along it: an anchor on a square
        // or a diamond used to land at half the size, which is inside the shape for any off-axis angle.
        LineArt.Point aim = anchor == null ? towards : LineArt.anchorPoint(centre, size * 2, anchor);
        return LineArt.rimPoint(centre, aim, size,
                (x, y) -> entry.geometry().contains(x, y, screenX, screenY, size));
    }

    /**
     * One styled line between two screen points — the shapes the dependency lines and the edge gesture's
     * rubber line both draw, because a rubber line that bent by different rules than the real ones would
     * promise a route the real line would not take.
     *
     * <p>The path comes from {@link LineArt}, which is the same function the hover and the right-click
     * hit test call: that is what makes a line you can see a line you can click, whatever its form.
     */
    private static void drawStyled(GuiRenderer r, DependencyStyle style, LineArt.Point from,
                                   LineArt.Point to, int fromHalf, int toHalf, int colour) {
        drawStyledPath(r, stylePath(style, from, to), style, fromHalf, toHalf, colour);
    }

    /**
     * A line's ink with the hover's brightening on it: the cue that a right-click lands here.
     *
     * <p>Eased by the same {@link Hover} the node ring uses, so the line being left dims as the one
     * being arrived at lights; and {@code shade} lightens towards white without picking a hue, so a
     * completed line stays recognisably completed underneath.
     */
    private int lineInk(int base, String from, String to, long now) {
        float amount = edgeHover.amount(List.of(from, to), now);
        return amount <= 0F ? base : Colour.shade(base, LINE_HOVER_BRIGHTEN * amount);
    }

    /**
     * The line nearest the pointer — recomputed only when the pointer, the view or the geometry moved.
     *
     * <p>{@link LineArt#nearest} reads every point of every candidate route, and in edit mode it ran on
     * every frame whether anything had changed or not. A still pointer over a still canvas has the same
     * nearest line as it did a frame ago, so the answer is kept until one of the things that can change
     * it does: the pointer, the pan, the zoom, the tree revision (a reload or a chapter switch) or a live
     * gesture (a node or handle being dragged moves the geometry without moving the view).
     *
     * <p>The cached value is the edge key only. The hover's brightening is applied where the ink is
     * chosen, so the fade still animates while the answer stands.
     */
    private String[] lineHover(List<FrameEdge> edges, double mouseX, double mouseY) {
        boolean moving = draggedNode != null || bendDragFrom != null || edgeDragLive;
        float scale = viewport().scale();
        int panX = viewport().offsetX();
        int panY = viewport().offsetY();
        long revision = ClientQuestCache.treeRevision();
        boolean same = !moving && !lastLineHoverMoving
                && mouseX == lastLineHoverX && mouseY == lastLineHoverY
                && scale == lastLineHoverScale && panX == lastLineHoverPanX && panY == lastLineHoverPanY
                && edges.size() == lastLineHoverEdges && revision == lastLineHoverRevision;
        if (same) {
            return lastLineHover;
        }
        lastLineHoverX = mouseX;
        lastLineHoverY = mouseY;
        lastLineHoverScale = scale;
        lastLineHoverPanX = panX;
        lastLineHoverPanY = panY;
        lastLineHoverEdges = edges.size();
        lastLineHoverRevision = revision;
        lastLineHoverMoving = moving;
        lastLineHover = LineArt.nearest(frameCandidates(edges), mouseX, mouseY, LINE_HIT);
        return lastLineHover;
    }

    /**
     * The route a style asks for: a cubic when the line is split, else the form's own path.
     *
     * <p>One function, so the drawing, the hover and the hit test cannot disagree about which shape a
     * split line is — the same rule the rest of {@link LineArt} exists to keep.
     */
    private static List<LineArt.Point> stylePath(DependencyStyle style, LineArt.Point from,
                                                 LineArt.Point to) {
        if (style.fromHandle().isPresent() && style.toHandle().isPresent()) {
            return LineArt.cubic(from, to, style.fromHandle().get(), style.toHandle().get());
        }
        return LineArt.path(style.formOr(DependencyStyle.Form.ORTHOGONAL), from, to,
                style.bendOr(DEFAULT_BEND));
    }

    /** Whether this line has been split into two control points. While it has, they are its shape. */
    private static boolean isSplit(DependencyStyle style) {
        return style.fromHandle().isPresent() && style.toHandle().isPresent();
    }

    /**
     * Whether this line's shape is a bow the bend handle steers: a curve or a radial arc.
     *
     * <p>Both put their middle where {@link LineArt#bendAt} reads it — the quadratic control offset and
     * the arc's sagitta are the same fraction of the chord — so one diamond steers either, and switching
     * between the two forms does not move the handle off the line.
     */
    private static boolean bows(DependencyStyle style) {
        DependencyStyle.Form form = style.formOr(DependencyStyle.Form.ORTHOGONAL);
        return form == DependencyStyle.Form.CURVED || form == DependencyStyle.Form.RADIAL;
    }

    /** The bow a curve gets when nothing, anywhere, says otherwise. */
    private static final double DEFAULT_BEND = 0.2;

    /**
     * The style a line is drawn with right now: its own, with the bend being dragged laid over it.
     *
     * <p>The preview is a screen fact, not a file one — nothing is written until the hand lets go — so it
     * belongs here rather than in {@code lineStyle}, which answers from what the server sent.
     */
    private DependencyStyle styleFor(ClientQuestCache.Entry dependent, String dependencyId) {
        DependencyStyle style = lineStyle(dependent, dependencyId);
        if (bendDragFrom == null || !bendDragFrom.equals(dependencyId)
                || !bendDragTo.equals(dependent.id())) {
            return style;
        }
        java.util.Optional<Double> from = java.util.Optional.ofNullable(
                HANDLE_FROM.equals(bendDragKind) ? anchorPreview : style.fromAnchor().orElse(null));
        java.util.Optional<Double> to = java.util.Optional.ofNullable(
                HANDLE_TO.equals(bendDragKind) ? anchorPreview : style.toAnchor().orElse(null));
        java.util.Optional<java.util.List<Double>> fromHandle = style.fromHandle();
        java.util.Optional<java.util.List<Double>> toHandle = style.toHandle();
        if (handlePreview != null) {
            if (HANDLE_FROM_HANDLE.equals(bendDragKind)) {
                fromHandle = java.util.Optional.of(handlePreview);
            }
            else if (HANDLE_TO_HANDLE.equals(bendDragKind)) {
                toHandle = java.util.Optional.of(handlePreview);
            }
        }
        return new DependencyStyle(style.form(), style.arrows(), style.dash(), style.weight(),
                java.util.Optional.ofNullable(bendPreview == null ? style.bend().orElse(null) : bendPreview),
                from, to, fromHandle, toHandle,
                style.arrowHead(), style.arrowPlace(), style.arrowDensity());
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
            // The rotated geometry, so a click lands on the node as it is drawn: the same table the
            // renderer walked, which is the whole of "a click lands on exactly the pixels that were
            // drawn".
            if (entry.geometry().contains(mouseX, mouseY, nodeScreenX(entry), nodeScreenY(entry), size)) {
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
        else if (overlay == Overlay.PICKER) {
            // A list of items is not a chapter's content, so this card is chrome, like the party
            // panel's: it is about the item registry rather than about the chapter it was opened from.
            drawPickerOverlay(r, mouseX, mouseY);
        }
        else if (overlay == Overlay.CHOICE) {
            // A question about a reward, not a chapter's content: the card is chrome, like the picker's.
            drawChoiceOverlay(r, mouseX, mouseY);
        }
        else if (overlay == Overlay.REWARDS) {
            // The same: a list of what the server owes is about the player, not about the chapter behind it.
            drawRewardsOverlay(r, mouseX, mouseY);
        }
        else if (overlay == Overlay.NAMING) {
            drawNamingOverlay(r);
        }
    }

    // ------------------------------------------------------------------
    // The choice offer
    // ------------------------------------------------------------------

    /**
     * Opens the choice card on the offer the server is holding, at the top of its list.
     *
     * <p>Called from {@link #tick} when an offer is waiting, and from nowhere else: the offer is not
     * this screen's state, and a second way in would be a second answer to "is a question waiting".
     */
    private void openChoiceOffer() {
        // Whatever card was open is closed first, through the one method every other close uses: an
        // armed type picker or a half-typed field left standing behind the question would greet the
        // next card as if it still belonged to the one it was opened from. That rebuilds, and this
        // rebuilds again after it, because the overlay it left is `NONE` and this one is not.
        closeOverlay();
        overlay = Overlay.CHOICE;
        choiceView.scrollTo(0);
        rebuildWidgets();
    }

    /**
     * Leaves the choice card: answered, or dismissed unanswered.
     *
     * <p>Both are one call, because both do the one thing: forget the offer and close. Dismissing is
     * safe by design -- {@link ChoiceRewardPayload} is only an offer, and the reward is not marked
     * claimed until an answer arrives -- so the same question comes back the next time Claim is
     * pressed. That is what makes Escape a way out rather than a way to lose the reward.
     */
    private void closeChoice() {
        ClientChoiceOffers.clear();
        overlay = Overlay.NONE;
        rebuildWidgets();
    }

    /** The card's controls: one row per entry, and the footer's Keep-it-for-later. */
    private void buildChoiceWidgets() {
        choiceRows = List.of();
        choiceLayout = null;
        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        if (offer == null) {
            return;
        }
        List<InspectRow> rows = new ArrayList<>();
        for (int i = 0; i < offer.entries().size(); i++) {
            rows.add(InspectRow.action(CHOICE_PREFIX + i, choiceLabel(offer.entries().get(i))));
        }
        choiceRows = List.copyOf(rows);

        Viewport body = overlayBody();
        choiceLayout = InspectLayout.build(choiceRows, body.viewWidth(), Measure.monospace(6, 9));
        choiceView.clear();
        choiceView.whole(true);
        choiceView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                body.viewHeight());
        for (int i = 0; i < choiceRows.size(); i++) {
            InspectRow row = choiceRows.get(i);
            ChoiceRewardPayload.Entry entry = offer.entries().get(i);
            ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                    () -> pressChoiceRow(row.key()));
            button.alignLeft(true).flat(true);
            ItemStack icon = itemStack(entry.item());
            if (!icon.isEmpty()) {
                button.icon(icon);
            }
            // The id on hover, because a table entry is otherwise only as identifiable as its label --
            // and a label an author wrote can be as vague as "a surprise".
            if (!entry.item().isEmpty()) {
                button.tooltip(Component.literal(entry.item()));
            }
            choiceView.put(row.key(), button);
        }
        choiceView.apply(choiceLayout, body.viewWidth());

        // The footer's one control, on the rectangle every card's footer uses. Dismissing is not a
        // refusal -- see `closeChoice` -- and saying so is the whole point of labelling it rather than
        // leaving Escape as the only way out.
        ArmatureButton later = control(geometry().overlayControls(false).get("back"),
                Component.literal("Keep for later"), this::closeChoice);
        if (later != null) {
            later.ink(ArmatureButton.Ink.BODY)
                    .tooltip(Component.literal("The reward stays yours to collect, and Claim asks again"));
        }
    }

    /** A press on an entry: the answer goes to the server, and the question closes. */
    private void pressChoiceRow(String key) {
        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        int index = choiceIndex(key);
        if (offer == null || index < 0 || index >= offer.entries().size()) {
            return;
        }
        // The index, not the reward: the server re-resolves it against its own files, so the worst a
        // modified client can do is pick entry 2 instead of entry 1 of a table it was offered.
        ArmatureNetwork.sendToServer(
                new ClaimChoicePayload(offer.questId(), offer.rewardIndex(), index));
        closeChoice();
    }

    /** The entry index a row's key names, or -1 for a key that names nothing. */
    private static int choiceIndex(String key) {
        if (key == null || !key.startsWith(CHOICE_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(CHOICE_PREFIX.length()));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * An entry's label, resolved the way every other reward label is: the item when there is one, the
     * author's key with the count when there is not, and the fallback text after that.
     *
     * <p>The same rule {@code ClientQuestCache.RewardEntry.text} states and the item picker's rows
     * follow, including the count in front -- the three lists show the same prizes, and a prize that
     * read differently in each would be a list an author has to learn twice.
     */
    private static String choiceLabel(ChoiceRewardPayload.Entry entry) {
        ItemStack item = itemStack(entry.item());
        if (!item.isEmpty()) {
            String name = item.getHoverName().getString();
            return entry.count() > 1 ? "x" + entry.count() + " " + name : name;
        }
        if (!entry.labelFallback().isEmpty() && !entry.label().isEmpty()) {
            return Component.translatableWithFallback(entry.label(), entry.labelFallback(), entry.count())
                    .getString();
        }
        return entry.label().isEmpty() ? "?" : entry.label();
    }

    /**
     * The choice card: the question, and one row per entry the server offered.
     *
     * <p>Drawn like the picker's card -- the panel, the header strip, the rule under it -- with the
     * quest's own title for a header, because the question is about a quest and a card that did not name
     * it would ask the player to remember which reward they were collecting.
     */
    private void drawChoiceOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = overlayLeft();
        int top = overlayTop();
        int w = overlayWidth();
        int h = overlayHeight();

        ArmatureTheme.panel(r, left, top, w, h, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        ArmatureTheme.fillSurface(r, left + 1, top + 1, w - 2, 45, ArmatureTheme.raised(),
                Math.max(0, ArmatureTheme.current().cornerRadius() - 1), ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.panelEdge());

        ClientChoiceOffers.Offer offer = ClientChoiceOffers.current();
        ClientQuestCache.Entry quest = offer == null ? null : ClientQuestCache.entry(offer.questId());
        String heading = Component.translatable("tasked.quest.choose").getString();
        int iconBox = HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        if (quest != null && !quest.icon().isEmpty()) {
            r.icon(quest.icon(), iconX, iconY, iconBox);
        }
        int textX = iconX + iconBox + 6;
        Measure measure = textMeasure(r);
        r.text(Measure.truncate(quest == null ? heading : quest.title(), w - (textX - left) - 8, measure),
                textX, top + 12, ArmatureTheme.title());
        if (quest != null) {
            r.text(heading, textX, top + 26, ArmatureTheme.faint());
        }

        if (choiceLayout == null) {
            return;
        }
        Viewport body = overlayBody();
        choiceView.apply(choiceLayout, body.viewWidth());
        QuestPanel.drawRows(r, BookGeometry.Rect.at(body.originX(), body.originY(),
                body.viewWidth(), body.viewHeight()), choiceView.viewport(), choiceLayout, choiceRows,
                mouseX, mouseY);
        choiceView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    // ------------------------------------------------------------------
    // The rewards panel
    // ------------------------------------------------------------------

    /**
     * Opens the rewards panel: what the server owes this player, and the two presses that collect it.
     *
     * <p>Whatever card was open is closed first, the same way the choice card opens -- and unlike the
     * choice card there is nothing to remember while it is up: the list is read from the progress the
     * client already holds, so closing leaves no trace either.
     */
    private void openRewardsOverlay() {
        closeOverlay();
        overlay = Overlay.REWARDS;
        rewardsRevision = ClientQuestCache.progressRevision();
        rewardView.scrollTo(0);
        rebuildWidgets();
    }

    /** The card's controls: one row per waiting quest, Claim all, and Back. */
    private void buildRewardWidgets() {
        rewardRows = List.of();
        rewardLayout = null;
        List<ClientQuestCache.Entry> waiting = claimableQuests();
        List<InspectRow> rows = new ArrayList<>();
        for (ClientQuestCache.Entry entry : waiting) {
            rows.add(InspectRow.action(REWARD_PREFIX + entry.id(), titleOf(entry)));
        }
        rewardRows = List.copyOf(rows);

        Viewport body = overlayBody();
        rewardLayout = InspectLayout.build(rewardRows, body.viewWidth(), Measure.monospace(6, 9));
        rewardView.clear();
        rewardView.whole(true);
        rewardView.viewport().bounds(body.originX(), body.originY(), body.viewWidth(),
                body.viewHeight());
        for (int i = 0; i < rewardRows.size(); i++) {
            InspectRow row = rewardRows.get(i);
            final String questId = waiting.get(i).id();
            ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                    () -> claim(questId));
            button.alignLeft(true).flat(true);
            ItemStack icon = waiting.get(i).icon();
            if (!icon.isEmpty()) {
                button.icon(icon);
            }
            // The id on hover, the same as the choice card's rows: a title can be renamed, and the id is
            // what an author and a bug report both name.
            button.tooltip(List.of(Component.translatable("tasked.screen.rewards.claim"),
                    Component.literal(questId)));
            rewardView.put(row.key(), button);
        }
        rewardView.apply(rewardLayout, body.viewWidth());

        // The footer: Claim all where the reader's Submit sits, Back where every card's does. `hasSubmit`
        // is true because this card has a left-hand control, which is the flag's whole meaning -- the two
        // rectangles come from one arithmetic and stack rather than collide on a narrow window.
        Map<String, BookGeometry.Rect> controls = geometry().overlayControls(true);
        ArmatureButton all = control(controls.get("submit"),
                Component.translatable("tasked.screen.rewards.claim_all"), QuestBookScreen::claimAllRewards);
        if (all != null) {
            all.accent(true).tooltip(Component.literal("Collects everything the server is holding"));
        }
        ArmatureButton back = control(controls.get("back"),
                Component.translatable("tasked.screen.rewards.back"), this::closeOverlay);
        if (back != null) {
            back.ink(ArmatureButton.Ink.BODY);
        }
    }

    /**
     * The quests whose rewards this player can collect right now, in the tree's order.
     *
     * <p>Asked of {@code ClientQuestCache.canClaimFor} rather than derived from a state: that is the same
     * question the reader's Claim button asks, so the panel and the button cannot disagree about what is
     * waiting -- and it is per player, so a teammate having collected their copy does not hide this one's.
     */
    private List<ClientQuestCache.Entry> claimableQuests() {
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        if (self == null) {
            return List.of();
        }
        List<ClientQuestCache.Entry> out = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            if (ClientQuestCache.canClaimFor(self, entry.id())) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /** Claim all: one press, and the server walks the book with the same test the single claim uses. */
    private static void claimAllRewards() {
        ArmatureNetwork.sendToServer(new ClaimAllPayload());
    }

    /**
     * The rewards card: what is waiting, and the controls that collect it.
     *
     * <p>The count is the header's second line rather than part of its title, so the title reads the same
     * whether nothing is waiting or twenty things are.
     */
    private void drawRewardsOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = overlayLeft();
        int top = overlayTop();
        int w = overlayWidth();
        int h = overlayHeight();

        ArmatureTheme.panel(r, left, top, w, h, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        ArmatureTheme.fillSurface(r, left + 1, top + 1, w - 2, 45, ArmatureTheme.raised(),
                Math.max(0, ArmatureTheme.current().cornerRadius() - 1), ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.panelEdge());

        int waiting = claimableQuests().size();
        int textX = left + 14;
        r.text(Component.translatable("tasked.screen.rewards.title").getString(), textX, top + 12,
                ArmatureTheme.title());
        r.text(Component.translatable(waiting == 0
                        ? "tasked.screen.rewards.none" : "tasked.screen.rewards.waiting", waiting).getString(),
                textX, top + 26, ArmatureTheme.faint());

        if (rewardLayout == null) {
            return;
        }
        Viewport body = overlayBody();
        rewardView.apply(rewardLayout, body.viewWidth());
        if (rewardRows.isEmpty()) {
            // Nothing waiting is a sentence rather than an empty card, and it says what puts something
            // here: an empty list with no explanation reads as a list that failed to load.
            r.text(Component.translatable("tasked.screen.rewards.none_hint").getString(),
                    body.originX() + 4, body.originY() + 4, ArmatureTheme.faint());
        }
        else {
            QuestPanel.drawRows(r, BookGeometry.Rect.at(body.originX(), body.originY(),
                    body.viewWidth(), body.viewHeight()), rewardView.viewport(), rewardLayout, rewardRows,
                    mouseX, mouseY);
        }
        rewardView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /**
     * The picker's own card, for a pick that is not a quest's.
     *
     * <p>The same card the quest editor draws its picker into -- the panel, the header strip, the rule
     * under it -- with a heading of its own instead of a quest's name, because there is no quest behind
     * it. The list and the search box are the picker's own code, unchanged: one picker, one list, two
     * ways in.
     */
    private void drawPickerOverlay(GuiRenderer r, int mouseX, int mouseY) {
        int left = overlayLeft();
        int top = overlayTop();
        int w = overlayWidth();
        int h = overlayHeight();

        ArmatureTheme.panel(r, left, top, w, h, ArmatureTheme.panel(), ArmatureTheme.panelEdge());
        ArmatureTheme.fillSurface(r, left + 1, top + 1, w - 2, 45, ArmatureTheme.raised(),
                Math.max(0, ArmatureTheme.current().cornerRadius() - 1), ArmatureTheme.CORNERS_TOP);
        r.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.panelEdge());

        // The subject's icon and name, so the card says what it is about: the row the press came from is
        // behind it, and "Pick an item" alone would not say which chapter's or group's icon is being set.
        int iconBox = HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        if (!pickIcon.isEmpty()) {
            r.icon(pickIcon, iconX, iconY, iconBox);
        }
        int textX = iconX + iconBox + 6;
        r.text(pickTarget == PickTarget.GROUP ? "Choose the group's icon"
                        : "Choose the chapter's icon",
                textX, top + 12, ArmatureTheme.title());
        if (!pickName.isEmpty()) {
            r.text(pickName, textX, top + 26, ArmatureTheme.faint());
        }

        drawItemPicker(r, overlayBody(), mouseX, mouseY);
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
        if (entry.icon().isEmpty() && !entry.iconId().isEmpty()) {
            // An icon id the build cannot resolve: the placeholder where the item would be, a small
            // mark beside it, and the sentence on hover -- the header has no room for a word, but the
            // fact must not be silent. The editor's icon row and the picker both say it in full.
            drawItemPlaceholder(r, iconX, iconY, iconBox);
            r.text("!", iconX + iconBox - 2, iconY - 2, ArmatureTheme.blocked());
            pendingLabels.add(new PendingLabel(
                    BookGeometry.Rect.at(iconX, iconY + iconBox + 2, 170, 12),
                    "missing item - the id is kept, so the mod can come back"));
        }
        else {
            r.icon(entry.icon(), iconX, iconY, iconBox);
        }

        int textX = iconX + iconBox + 6;
        // The title and the subtitle are drawn unless their own field is open and drawing them -- see
        // `InlineEdit.replaces`. The state tag stays either way: it is not part of the title, and its x
        // is derived from the title's width, so it does not move when the title's drawing changes hands.
        if (!InlineEdit.replaces("title", editingPath)) {
            r.text(titleOf(entry), textX, top + 12, InlineEdit.ink("title"));
        }
        r.text(stateLabel(state), textX + r.textWidth(titleOf(entry)) + 10, top + 12,
                stateColour(state));

        String where = entry.chapterTitle() + (subtitleOf(entry).isEmpty() ? "" : "  \u00b7  " + subtitleOf(entry));
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
        //
        // The clickable rows are rebuilt from this pass, and cleared here rather than with the tooltip
        // lists at the end of the frame: a press arrives *between* frames, so the list it reads has to
        // be the last frame's drawing — the same lifecycle `editTargets` has, and the reason a list
        // cleared after drawing would always be empty by the time a click asks.
        rowItems.clear();
        dependencyTargets.clear();
        try (GuiRenderer.Scoped clip = r.clip(body)) {
            drawProse(r, layout, body, mouseX, mouseY);
            drawTasks(r, entry, layout, body, mouseX, mouseY, now);
            drawRewards(r, entry, layout, body, mouseX, mouseY, now);
            drawDependencies(r, entry, layout, body, mouseX, mouseY, now);
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
        // What the reader may see, from the same rules the canvas hides quests with. The editor never
        // comes through here -- it builds its own stack with everything in it -- because an author
        // looking at a card must see every field they can edit.
        QuestState state = ClientQuestCache.stateOf(entry.id());
        boolean text = dev.ellipog.tasked.client.dev.QuestVisibility
                .showsText(entry.hideTextUntilComplete(), state);
        boolean details = dev.ellipog.tasked.client.dev.QuestVisibility
                .showsDetails(entry.hideDetailsUntilStartable(), state);
        return OverlayLayout.stack(
                        readerProse(r, text ? entry.description() : List.of(), body.viewWidth()),
                        entry.tasks().size(), entry.rewards().size(), dependenciesOf(entry).size(), false,
                        new OverlayLayout.Reveal(text, details))
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
                           int mouseX, int mouseY, long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.TASKS_HEADING), "TASKS");

        if (entry.tasks().isEmpty()) {
            drawEmptyState(r, placed(layout, body, OverlayLayout.NO_TASKS), "Nothing required");
            return;
        }
        for (int i = 0; i < entry.tasks().size(); i++) {
            String key = OverlayLayout.taskKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                drawTaskRow(r, entry, i, slot, rowHover.amount(key, now), mouseX, mouseY);
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
        // The prerequisites are rows of the body too: they carry a hover wash and a press, and both
        // read the row the pointer is over from this one list.
        for (int i = 0; i < dependenciesOf(entry).size(); i++) {
            keys.add(OverlayLayout.dependencyKey(i));
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
     * A press on a row that has something to look up: the chosen viewer opens, and the press is
     * consumed.
     *
     * <p>Consumed even when no viewer is installed: a press inside the reader's card did nothing
     * before this feature, and it still does nothing — the difference is only that the row answers
     * when there is a viewer to answer with.
     */
    private boolean pressRowItem(double mouseX, double mouseY) {
        for (RowItem item : rowItems) {
            if (item.box().contains(mouseX, mouseY)) {
                RecipeLookups.open(item.target());
                return true;
            }
        }
        return false;
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
                             int mouseX, int mouseY, long now) {
        drawHeading(r, placed(layout, body, OverlayLayout.REWARDS_HEADING), "REWARDS");

        if (entry.rewards().isEmpty()) {
            drawEmptyState(r, placed(layout, body, OverlayLayout.NO_REWARDS), "Nothing");
            return;
        }
        for (int i = 0; i < entry.rewards().size(); i++) {
            String key = OverlayLayout.rewardKey(i);
            Slot slot = placed(layout, body, key);
            if (slot != null) {
                drawRewardRow(r, entry, i, slot, rowHover.amount(key, now), mouseX, mouseY);
            }
        }
    }

    private void drawDependencies(GuiRenderer r, ClientQuestCache.Entry entry, Layout layout,
                                  Viewport body, int mouseX, int mouseY, long now) {
        if (dependenciesOf(entry).isEmpty()) {
            // No REQUIRES section at all, rather than one saying nothing. The layout omits it for the
            // same reason, so there is no heading to draw and no room reserved for one.
            return;
        }
        // The heading carries the count when the rule is more interesting than "all of them": "2 of 3
        // met" is the whole of what a player needs to know about a quest that is not unlocking, and it
        // is a question the old heading could not answer at all.
        dev.ellipog.tasked.client.dev.DependencyProgress progress = dependencyProgressOf(entry);
        int satisfied = progress.satisfied(ClientQuestCache::stateOf);
        String heading = satisfied >= progress.required()
                ? "REQUIRES"
                : "REQUIRES · " + satisfied + " of " + progress.required() + " met";
        drawHeading(r, placed(layout, body, OverlayLayout.REQUIRES_HEADING), heading);

        for (int i = 0; i < dependenciesOf(entry).size(); i++) {
            Slot slot = placed(layout, body, OverlayLayout.dependencyKey(i));
            if (slot == null) {
                continue;
            }
            String key = OverlayLayout.dependencyKey(i);
            String dependency = dependenciesOf(entry).get(i);
            ClientQuestCache.Entry other = entryFor(dependency);
            // The *rule's* bar, not completion: under `all_started` and `one_started` a prerequisite
            // with any task progress has done its job, and a cross beside it said otherwise.
            boolean met = progress.satisfies(dependency, ClientQuestCache::stateOf);
            String label = other != null ? other.title() : dependency;

            // The row's own box, and the wash the whole row is a target under: the row jumps to that
            // quest's card, so the wash is the affordance that says so before the press.
            Slot row = rowBox(slot);
            float hover = rowHover.amount(key, now);
            if (hover > 0F) {
                r.fill(row.x() - 3, row.y() - 1, Math.round(body.visibleRight()), row.bottom() + 1,
                        Colour.translucent(ArmatureTheme.rowHover(), hover));
            }
            BookGeometry.Rect locate = locateBox(slot);
            // A tick and a cross. The cross is U+00D7 rather than the heavier U+2716, which is one of the
            // few symbols this font does not carry -- see `BookGeometry.TOOLS_BUTTON_WIDTH`. Cut to the
            // room the locate icon leaves, so a long title cannot run under it, and centred on the row:
            // the label used to be drawn at the slot's top edge while the icon was centred, which read
            // as text sitting high in the wash.
            String line = (met ? "\u2714" : "\u00d7") + "  " + label;
            r.text(Measure.truncate(line, Math.max(24, locate.x() - slot.x() - 8), textMeasure(r)),
                    slot.x(), slot.y() + (slot.height() - 8) / 2,
                    met ? ArmatureTheme.complete() : ArmatureTheme.blocked());
            drawLocateIcon(r, locate.x() + locate.width() / 2, locate.y() + locate.height() / 2,
                    locate.contains(mouseX, mouseY) ? ArmatureTheme.body() : ArmatureTheme.faint());
            dependencyTargets.add(new DependencyTarget(row, locate, dependency));
        }
    }

    /** The locate icon's box: at the row's right edge, the size of the row it sits in. */
    private static BookGeometry.Rect locateBox(Slot slot) {
        int size = Math.max(8, Math.min(12, slot.height()));
        return BookGeometry.Rect.at(slot.right() - size, slot.y() + (slot.height() - size) / 2, size,
                size);
    }

    /**
     * The locate icon: a small diamond ring with a dot in it — "show me where this is".
     *
     * <p>Drawn from pixels rather than written as a character, for the same reason the tick and the
     * cross are chosen carefully: the font carries no crosshair, and a missing glyph is a box.
     */
    private static void drawLocateIcon(GuiRenderer r, int cx, int cy, int colour) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int distance = Math.abs(dx) + Math.abs(dy);
                if (distance == 2 || distance == 0) {
                    r.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, colour);
                }
            }
        }
    }

    /**
     * A press on a prerequisite row: the row itself jumps to that quest's card, while the locate icon
     * — or a middle-click or shift-click anywhere on the row — closes the card and takes the canvas to
     * the node instead.
     *
     * <p>From the last frame's own drawing, the same contract every other row in the card follows.
     */
    private boolean pressDependencyRow(double mouseX, double mouseY, int button) {
        for (DependencyTarget target : dependencyTargets) {
            if (!target.row().contains(mouseX, mouseY)) {
                continue;
            }
            boolean locate = button == 2 || hasShiftDown() || target.locate().contains(mouseX, mouseY);
            if (locate) {
                locateOnCanvas(target.questId());
            }
            else if (button == 0) {
                navigateToQuest(target.questId());
            }
            return true;
        }
        return false;
    }

    /**
     * The rule a quest's prerequisites are judged by, from the replica's own fields.
     *
     * <p>One helper for the canvas and the card, because two expressions for "is this prerequisite
     * satisfied" is how the two would come to disagree -- and they did: both asked about completion,
     * which is the wrong question for half the modes.
     */
    private static dev.ellipog.tasked.client.dev.DependencyProgress dependencyProgressOf(
            ClientQuestCache.Entry entry) {
        return new dev.ellipog.tasked.client.dev.DependencyProgress(entry.effectivePrerequisiteMode(),
                entry.minRequired(), dependenciesOf(entry));
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
                             float hover, int mouseX, int mouseY) {
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

        // An id with no item behind it is still the row's subject: the id is drawn where the item's
        // name would be, in the ink that says something is wrong. The placeholder icon beside it says
        // the same thing without words, and between them the row never reads as an ordinary task.
        boolean missingItem = !task.hasItem() && !task.itemId().isEmpty();
        String text = missingItem ? task.itemId() : rowText("tasks", task);
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
        // A condition this player does not meet. Locked wins the one tag slot: "hand in" would be a lie
        // about a button that is not drawn, and the explanation is the whole point of the tag.
        boolean locked = !ClientQuestCache.taskLockOf(entry.id(), index).isEmpty();
        String tag = locked ? "locked" : (manual ? "hand in" : (optional ? "optional" : null));
        int tagX = tag == null ? 0 : x + availableWidth - r.textWidth(tag)
                - (manual && optional && !locked ? r.textWidth("optional") + 6 : 0);

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
        // The same box answers the hover and registers the row's explanation, so the row that lights up
        // is exactly the row that explains itself.
        Slot row = rowBox(slot);
        // The row's target, from the rule the tests hold (BookRowTargets): an item task, or a tag
        // task, and nothing for a row with neither. Collected from the drawing pass so the press and
        // the hover come from one description of where a row is -- the rowTooltips contract.
        RecipeLookups.Target target = BookRowTargets.ofTask(task);
        if (target != null) {
            rowItems.add(new RowItem(row, target));
        }
        if (row.contains(mouseX, mouseY)) {
            // The player's explanation, not the author's: this hover is read by someone who has never
            // heard of a task type. The second line is about pressing Submit, so it is decided by the
            // same predicate the button is: a locked task has no button, and telling the player to press
            // one that is not there is the one way this hover can lie.
            List<String> lines = new ArrayList<>(QuestPanelLayout.playerTooltip("tasks", task.type(),
                    task.manual() && !locked));
            if (!task.tagId().isEmpty()) {
                // The raw id, kept for the hover: the row's label is the humanized tag now, and the
                // one thing "Any Iron Ores" cannot tell a player is which id to hand in.
                lines.add(1, "#" + task.tagId());
            }
            appendConditionLines(lines, task.conditions(), ClientQuestCache.taskLockOf(entry.id(), index));
            if (RecipeLookups.canOpen(target)) {
                lines.add("Click for recipes");
            }
            rowTooltips.add(new RowTooltip(row, lines));
        }
        rowWash(r, row, contentRight, hover);

        // --- drawn ---

        int textX = x;
        ItemStack toDraw = task.hasItem() ? task.item() : task.icon();
        if (missingItem) {
            drawItemPlaceholder(r, x, y, ROW_ICON);
            textX = x + ROW_ICON + 5;
        }
        else if (r.icon(toDraw, x, y, ROW_ICON)) {
            // Only indent the text when something was actually drawn, so a task whose item the client
            // cannot resolve is not left with a gap where an icon should be.
            textX = x + ROW_ICON + 5;
        }

        int colour = missingItem ? ArmatureTheme.blocked()
                : satisfied ? ArmatureTheme.complete()
                : locked || ClientQuestCache.stateOf(entry.id()) == QuestState.LOCKED
                        ? ArmatureTheme.blocked()
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

        // The optional tag is the one case where two tags share the row's far edge, and it is drawn by
        // its own branch below -- so a locked row draws "locked" there instead, not both on top of
        // each other.
        if (optional && !locked) {
            r.text("optional", x + availableWidth - r.textWidth("optional"), textY, ArmatureTheme.faint());
        }
        if (manual && !locked) {
            r.text("hand in", tagX, textY, ArmatureTheme.available());
        }
        if (locked) {
            r.text("locked", tagX, textY, ArmatureTheme.blocked());
        }
    }

    /**
     * Appends a locked row's explanation: the gates it is missing, by the server's own naming.
     *
     * <p>Only the unmet ones. The server sends which condition indices failed, and the tree carried what
     * those conditions say, so a row with three gates and one unmet names the one — and the list is the
     * same order the author wrote, because the mask indexes into it.
     */
    private static void appendConditionLines(List<String> lines,
                                             List<ClientQuestCache.ConditionEntry> conditions,
                                             List<Integer> unmet) {
        if (unmet.isEmpty() || conditions.isEmpty()) {
            return;
        }
        lines.add("Locked - needs:");
        for (int index : unmet) {
            if (index >= 0 && index < conditions.size()) {
                lines.add("  " + conditions.get(index).line());
            }
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
    private void drawRewardRow(GuiRenderer r, ClientQuestCache.Entry entry, int index, Slot slot,
                               float hover, int mouseX, int mouseY) {
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        int x = slot.x();
        int y = slot.y();
        int textY = y + (ROW_ICON - 8) / 2;

        // The task row's rule, one row over: a reward whose item is gone shows the id it names.
        boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
        String text = missingItem ? reward.itemId() : rowText("rewards", reward);
        String count = reward.hasItem() && reward.count() > 1 ? "x" + reward.count() : null;
        // A condition this player does not meet on this reward. The row is drawn shut and the tag says
        // so; the claim button is not this row's -- see BookGeometry's claimable -- and the server
        // refuses the payout anyway, with a message, if the press gets there.
        boolean locked = !ClientQuestCache.rewardLockOf(entry.id(), index).isEmpty();

        int contentRight = x + ROW_ICON + 5 + r.textWidth(text);
        if (count != null) {
            contentRight += 5 + r.textWidth(count);
        }
        if (locked) {
            // The tag is right-aligned, so the wash has to reach the row's own edge -- the task row's
            // rule, where the tag's x plus its width is exactly that. Measuring from the text's end
            // instead left the tag floating outside its own highlight.
            contentRight = Math.max(contentRight, x + slot.width());
        }

        // The row's own box rather than its slot: see `rowBox` for the six pixels of gap between them.
        // Registered exactly as the task row's is, one member over: the same box answers the hover and
        // carries the explanation.
        Slot row = rowBox(slot);
        // The row's target, from the same rule the task row uses: an item reward, and nothing for a
        // reward with no item. Collected from the drawing pass, like the rowTooltips below.
        RecipeLookups.Target target = BookRowTargets.ofReward(reward);
        if (target != null) {
            rowItems.add(new RowItem(row, target));
        }
        if (row.contains(mouseX, mouseY)) {
            // The task row's rule, one member over: the player's explanation, never the author's.
            // A reward is collected rather than handed in, so there is no second line to pick.
            List<String> lines = new ArrayList<>(
                    QuestPanelLayout.playerTooltip("rewards", reward.type(), false));
            appendConditionLines(lines, reward.conditions(),
                    ClientQuestCache.rewardLockOf(entry.id(), index));
            if (RecipeLookups.canOpen(target)) {
                lines.add("Click for recipes");
            }
            rowTooltips.add(new RowTooltip(row, lines));
        }
        rowWash(r, row, contentRight, hover);

        int textX = x;
        ItemStack toDraw = reward.hasItem() ? reward.item() : reward.icon();
        if (missingItem) {
            drawItemPlaceholder(r, x, y, ROW_ICON);
            textX = x + ROW_ICON + 5;
        }
        else if (r.icon(toDraw, x, y, ROW_ICON)) {
            textX = x + ROW_ICON + 5;
        }

        r.text(text, textX, textY,
                missingItem || locked ? ArmatureTheme.blocked() : ArmatureTheme.body());
        if (count != null) {
            r.text(count, textX + r.textWidth(text) + 5, textY, ArmatureTheme.faint());
        }
        if (locked) {
            r.text("locked", x + slot.width() - r.textWidth("locked"), textY, ArmatureTheme.blocked());
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
            if (overlay == Overlay.PARTY && partyScrollAt(mouseX).scrollbarHit(mouseX, mouseY)) {
                partyScrollAt(mouseX).beginThumbDrag(mouseY);
                partyScrollAt(mouseX).dragThumbTo(mouseY);
            }
            else if (overlay == Overlay.CHOICE && button == 0) {
                // The bar and the outside; nothing else. An entry is a widget and answers on release,
                // so the press has no rows to hit-test -- and an outside press dismisses the way every
                // other card's does, which loses nothing: see `closeChoice`.
                if (choiceView.scrollbarHit(mouseX, mouseY)) {
                    choiceView.beginThumbDrag(mouseY);
                    choiceView.dragThumbTo(mouseY);
                    return true;
                }
                if (clickedOutsideCard(mouseX, mouseY)) {
                    closeChoice();
                }
                return true;
            }
            else if (overlay == Overlay.REWARDS && button == 0) {
                // The bar, then the outside -- the same shape the choice card's press has, and for the
                // same reason: the rows are widgets and answer on release.
                if (rewardView.scrollbarHit(mouseX, mouseY)) {
                    rewardView.beginThumbDrag(mouseY);
                    rewardView.dragThumbTo(mouseY);
                    return true;
                }
                if (clickedOutsideCard(mouseX, mouseY)) {
                    closeOverlay();
                }
                return true;
            }
            else if ((overlay == Overlay.QUEST || overlay == Overlay.PICKER) && mayEditNow() && button == 0) {
                // The type picker's bar before its rows, on the same contract as every other list: a
                // press that starts on the bar stays on the bar, so this runs before anything that
                // would take the press as a row's. `beginThumbDrag` first -- `dragThumbTo` moves a drag
                // that has begun and does nothing to one that has not.
                //
                // The card's own page keeps wheel-only scrolling: its right edge is where a row's Copy
                // and cross sit, and a band that swallowed those presses would be a worse trade than a
                // bar that answers the wheel alone.
                if ((pickingEntryType != null || pickingConditionFor != null)
                        && overlayView.scrollbarHit(mouseX, mouseY)) {
                    overlayView.beginThumbDrag(mouseY);
                    overlayView.dragThumbTo(mouseY);
                    return true;
                }
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
                // The settings page owns the body while it is open: a press on it is the page's, and
                // there is nothing behind it to fall through to -- it is a page of the card, not a
                // popover over one. Escape and the Settings button are the ways back.
                if (settingsOpen) {
                    pressSettingsPage(mouseX, mouseY);
                    return true;
                }
                // The card's own marked pieces, from the last frame's drawing -- one derivation for
                // the mark and the press.
                for (EditTarget target : editTargets) {
                    if (target.box().contains(mouseX, mouseY)) {
                        // Shift on a prerequisite's name is the locate gesture, the same as the
                        // reader's rows: "do not open it, show me where it is". Scoped to this one
                        // target, so the steppers' shift meaning (ten at a time) is untouched.
                        if (target.action() == EditAction.NAVIGATE_DEP && hasShiftDown()) {
                            locateOnCanvas(target.path());
                            return true;
                        }
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
            else if (overlay == Overlay.QUEST && !mayEditNow() && (button == 0 || button == 2)) {
                // The reader's card: a press on a task's or reward's row opens the chosen viewer on
                // that row's item (or tag) -- "how is this made", the direction the viewer pages do
                // not cover. From the last frame's own list, so the row that lights up is the row
                // that answers; a press that hits no row keeps the old behaviour: outside closes,
                // inside is swallowed.
                //
                // A prerequisite row is the card's own navigation: a plain press opens that quest's
                // card, and its locate icon -- or a middle-click or shift-click anywhere on the row --
                // closes the card and takes the canvas to the node instead.
                if (pressDependencyRow(mouseX, mouseY, button)) {
                    return true;
                }
                // The recipe-viewer rows answer the left press alone; a middle press that hit no
                // prerequisite falls through to the outside test, exactly as it did before.
                if (button == 0 && pressRowItem(mouseX, mouseY)) {
                    return true;
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

        // The sidebar's menu, before anything else on the screen: an open menu swallows the press that
        // dismisses it, and the press that chooses an item, and neither is a click on what is underneath.
        if (pressMenu(mouseX, mouseY)) {
            return true;
        }
        // And the right-click that opens it. On a row or on the empty list, in edit mode only: the menu
        // is all structural edits, and a reader has none of them.
        if (button == 1 && mayEditNow() && openSidebarMenuAt(mouseX, mouseY)) {
            return true;
        }

        // The sidebar's own press, read before the widget pass rather than after it, because the rows
        // are widgets and a widget consumes the press it is given: a row that is about to be dragged
        // must be remembered here, or the press is a click and there is nothing left to drag. It is not
        // consumed -- a press that never travels is still the row's click, and `super` below is what
        // makes it one.
        rememberSidebarPress(mouseX, mouseY, button);

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
            //
            // Only on the Theme tab, and that guard is the fix rather than a formality: the Chapter tab's
            // header now occupies the same band, and an unguarded hotspot test would read a press on the
            // chapter's icon as "select the canvas colour" -- the sample is not on screen to point at.
            if (toolsTab == ToolsLayout.Tab.THEME) {
                ToolsLayout.Hotspot part = ToolsLayout.hotspotAt(toolsFrame.preview(), mouseX, mouseY);
                if (part != null) {
                    selectColour(part.token());
                    return true;
                }
            }
            // The radius row's two arrows before the scrollbar band, because a control beats chrome and the
            // arrows sit at the row's right edge where that band is. They are drawn controls rather than
            // widgets, and the hit test comes from the same two calls the panel draws them with -- so what
            // is pressed is what is seen, scrolled or not. Theme tab only: `toolsLayout` belongs to that
            // tab and is left standing while the Chapter tab is open, so an unguarded test would read a
            // press on the chapter's own rows as a radius step.
            if (toolsTab == ToolsLayout.Tab.THEME) {
                Integer step = ToolsLayout.radiusStepAt(toolsView.viewport(),
                        toolsLayout == null ? null : toolsLayout.slot(ToolsLayout.RADIUS), mouseX, mouseY);
                if (step != null) {
                    stepRadius(step);
                    return true;
                }
            }
            // The Chapter tab's cycling rows, in the same shape: drawn by ChapterPanel, pressed through
            // the layout's own boxes. Before the list drag below, because an arrow is a control and the
            // row band under it is not the thing being grabbed.
            if (toolsTab == ToolsLayout.Tab.CHAPTER && mayEditNow() && chapterLayout != null) {
                for (ChapterPanelLayout.Choice choice : ChapterPanelLayout.CHOICES) {
                    Integer step = ChapterPanelLayout.choiceStepAt(toolsView.viewport(),
                            chapterLayout.slot(choice.key()), mouseX, mouseY);
                    if (step != null) {
                        cycleChapterSetting(choice, step);
                        return true;
                    }
                }
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

        if (overlay == Overlay.NONE && button == 0 && sidebarView.scrollbarHit(mouseX, mouseY)) {
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
            pressedAlt = Screen.hasAltDown();
            panContentX = viewport().contentX(mouseX);
            panContentY = viewport().contentY(mouseY);
            // A pan is the hand taking the camera: a glide still in flight must not fight it.
            glideQuest = null;

            String chapter = effectiveChapter();
            ClientQuestCache.Entry under = chapter == null ? null
                    : nodeAt(mouseX, mouseY, questsIn(chapter));
            pressedNode = under == null ? null : under.id();

            // A left press on a curve's handle is the handle's: claimed before the node and the pan, so
            // bending a line cannot also pick a node up or start a marquee.
            if (button == 0 && mayEditNow()) {
                String[] bend = handleAt(mouseX, mouseY);
                if (bend != null) {
                    bendDragFrom = bend[0];
                    bendDragTo = bend[1];
                    bendDragKind = bend[2];
                    bendDragLive = false;
                    bendPreview = null;
                    anchorPreview = null;
                    handlePreview = null;
                    ClientQuestCache.Entry grabFrom = entryFor(bend[0]);
                    ClientQuestCache.Entry grabTo = entryFor(bend[1]);
                    if (grabFrom != null && grabTo != null) {
                        LineArt.Point fromCentre = new LineArt.Point(nodeCentreX(grabFrom),
                                nodeCentreY(grabFrom));
                        LineArt.Point toCentre = new LineArt.Point(nodeCentreX(grabTo),
                                nodeCentreY(grabTo));
                        DependencyStyle style = styleFor(grabTo, bend[0]);
                        if (HANDLE_BEND.equals(bend[2])) {
                            bendDragGrab = LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                                    - style.bendOr(DEFAULT_BEND);
                        }
                        else if (HANDLE_FROM_HANDLE.equals(bend[2]) || HANDLE_TO_HANDLE.equals(bend[2])) {
                            // Free 2D: the delta between the pointer and the control point, in the chord's
                            // own frame, so the drag lands where the hand is.
                            double[] chord = chordOf(grabFrom, grabTo);
                            LineArt.Point control = splitPointOf(bend, style,
                                    HANDLE_FROM_HANDLE.equals(bend[2]));
                            java.util.List<Double> pointer =
                                    LineArt.chordFraction(new LineArt.Point((int) chord[0], (int) chord[1]),
                                            new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                                                    (int) (chord[1] + chord[3] * chord[4])), mouseX, mouseY);
                            java.util.List<Double> at = LineArt.chordFraction(
                                    new LineArt.Point((int) chord[0], (int) chord[1]),
                                    new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                                            (int) (chord[1] + chord[3] * chord[4])),
                                    control.x(), control.y());
                            handleGrabAlong = pointer.get(0) - at.get(0);
                            handleGrabAcross = pointer.get(1) - at.get(1);
                        }
                        else {
                            LineArt.Point centre = HANDLE_FROM.equals(bend[2]) ? fromCentre : toCentre;
                            LineArt.Point[] ends = lineEnds(grabFrom, grabTo, style);
                            LineArt.Point end = HANDLE_FROM.equals(bend[2]) ? ends[0] : ends[1];
                            anchorDragGrab = LineArt.anchorAngle(centre, mouseX, mouseY)
                                    - LineArt.anchorAngle(centre, end.x(), end.y());
                        }
                    }
                    return true;
                }
            }

            // Middle-drag is a pan and never a click, so forget the node immediately. Otherwise a
            // middle-click that happens not to move would select whatever it landed on.
            if (button == 2) {
                pressedNode = null;
            }

            // Right claims the press for the edge gesture, on a node with an editor open. Anywhere else
            // it is remembered for the release, which decides between the canvas's menus: a right-press
            // that travels is an edge (or nothing, on empty canvas), and one that does not is a menu.
            if (button == 1) {
                canvasRightPressed = true;
                if (under != null && mayEditNow()) {
                    edgeDragFrom = under.id();
                    edgeDragLive = false;
                }
                return true;
            }
            canvasRightPressed = false;

            // A left press *on* a node, with an editor open, picks the node up rather than panning. The
            // two cannot share the button, and this is the split every graph editor makes: a press on a
            // node is about that node, and a press on the canvas is about the view. The middle button
            // still pans from anywhere, which is what a trackpad-less mouse reaches for.
            //
            // The press is only the first of the four phases -- press, threshold, follow, release. The
            // node is claimed here but does not follow yet: that starts when the pointer has moved
            // further than a click's jitter, and until then this could still be a click.
            if (button == 0 && under != null && mayEditNow()) {
                if (pickingDependency) {
                    // **While a pick is armed a press on a node is about the pair, not the node.** It
                    // claims nothing: no drag starts, the selection does not move, and the release lands
                    // the dependency -- which it could not do while the node was claimed, because the
                    // release answers a claimed node by opening it. That was the whole bug: the pick's
                    // landing code was correct and unreachable for the one gesture it exists for.
                    return true;
                }
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

    /**
     * The pointer's movement, which is what keeps a submenu open.
     *
     * <p>The first override of this method in the book, and it exists for one thing: `render` is told
     * where the pointer is every frame, but the pointer's *travel* is only announced here, and a submenu
     * has to survive the trip from its parent row to the panel beside it.
     */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        updateSubmenuHover(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * Which submenu should be showing for a pointer at this position.
     *
     * <p>Over a menu row: that row's, if it has one. Anywhere inside the open submenu, in the bridge to
     * it, or on its parent row: unchanged. Anywhere else: shut. The middle case is the one the first
     * version lacked — it asked only "is the pointer on the parent row", so the panel disappeared the
     * moment the pointer left it, which made the destinations unreachable.
     */
    private void updateSubmenuHover(double mouseX, double mouseY) {
        if (menu.isEmpty()) {
            submenuRow = -1;
            return;
        }
        int row = menuRowAt(mouseX, mouseY);
        if (row >= 0) {
            submenuRow = menu.get(row).submenu() ? row : -1;
            return;
        }
        if (submenuRow >= 0 && insideOpenSubmenu(mouseX, mouseY)) {
            return;
        }
        submenuRow = -1;
    }

    /** Whether the pointer is on the open flyout, or on the way to it. */
    private boolean insideOpenSubmenu(double mouseX, double mouseY) {
        if (submenuRow < 0) {
            return false;
        }
        MenuFlyout.Placed placed = submenuPlacement(submenuRow);
        if (placed.rows().isEmpty()) {
            return false;
        }
        if (placed.contains(mouseX, mouseY)) {
            return true;
        }
        BookGeometry.Rect bridge = submenuBridge(placed);
        return bridge != null && bridge.contains(mouseX, mouseY);
    }

    /**
     * The rectangle between the parent row and its panel.
     *
     * <p>Without it a pointer travelling diagonally crosses a sliver that is neither the row nor the
     * panel, and the submenu shuts under the hand — which is the same fault in a smaller place.
     */
    private BookGeometry.Rect submenuBridge(MenuFlyout.Placed placed) {
        if (placed.rows().isEmpty() || submenuRow < 0) {
            return null;
        }
        List<BookGeometry.Rect> main = menuRects();
        if (submenuRow >= main.size()) {
            return null;
        }
        List<BookGeometry.Rect> rects = placed.rows();
        BookGeometry.Rect parent = main.get(submenuRow);
        BookGeometry.Rect last = rects.get(rects.size() - 1);
        int left = Math.min(parent.x(), rects.get(0).x() - 2);
        int right = Math.max(parent.right(), last.right() + 2);
        int top = Math.min(parent.y(), rects.get(0).y() - 3);
        int bottom = Math.max(parent.bottom(), last.bottom() + 3);
        return BookGeometry.Rect.at(left, top, right - left, bottom - top);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // The curve handle's follow. The bow is recomputed from the pointer rather than accumulated, so
        // letting the hand drift back over the chord straightens the line again.
        if (bendDragFrom != null) {
            if (!bendDragLive) {
                if (Math.abs(mouseX - pressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - pressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                bendDragLive = true;
                pressMoved = true;
            }
            ClientQuestCache.Entry bendFrom = entryFor(bendDragFrom);
            ClientQuestCache.Entry bendTo = entryFor(bendDragTo);
            if (bendFrom != null && bendTo != null) {
                LineArt.Point fromCentre = new LineArt.Point(nodeCentreX(bendFrom), nodeCentreY(bendFrom));
                LineArt.Point toCentre = new LineArt.Point(nodeCentreX(bendTo), nodeCentreY(bendTo));
                if (HANDLE_BEND.equals(bendDragKind)) {
                    bendPreview = LineArt.limitBend(LineArt.bendAt(fromCentre, toCentre, mouseX, mouseY)
                            - bendDragGrab);
                }
                else if (HANDLE_FROM_HANDLE.equals(bendDragKind) || HANDLE_TO_HANDLE.equals(bendDragKind)) {
                    // Free 2D, grab-relative, clamped so a control point cannot be flung off the graph.
                    double[] chord = chordOf(bendFrom, bendTo);
                    LineArt.Point origin = new LineArt.Point((int) chord[0], (int) chord[1]);
                    LineArt.Point other = new LineArt.Point((int) (chord[0] + chord[2] * chord[4]),
                            (int) (chord[1] + chord[3] * chord[4]));
                    java.util.List<Double> at = LineArt.chordFraction(origin, other, mouseX, mouseY);
                    handlePreview = java.util.List.of(
                            Math.max(-0.5, Math.min(1.5, at.get(0) - handleGrabAlong)),
                            Math.max(-0.9, Math.min(0.9, at.get(1) - handleGrabAcross)));
                }
                else {
                    // Free angle, around this end's own node, applied as the delta from the grab.
                    LineArt.Point centre = HANDLE_FROM.equals(bendDragKind) ? fromCentre : toCentre;
                    anchorPreview = LineArt.anchorAngle(centre, mouseX, mouseY) - anchorDragGrab;
                }
            }
            return true;
        }

        // A right-press that travels is a drag wherever it started: on a node it is the dependency edge,
        // which tracks its own liveness, and on empty canvas it is a gesture with no meaning — but either
        // way the release is not a click, and no menu may open for it. Recorded here because the pan
        // branch below only runs for the left and middle buttons, so a right-drag would otherwise reach
        // its release looking stationary.
        if (canvasRightPressed
                && (Math.abs(mouseX - pressX) > DRAG_THRESHOLD || Math.abs(mouseY - pressY) > DRAG_THRESHOLD)) {
            pressMoved = true;
        }

        // A slider's drag, before anything else: the press claimed the track, so the pan and the node
        // drag must not also answer it. The value follows the pointer and the preview with it; the
        // commit waits for the release, which is what keeps a drag to one operation rather than sixty.
        if (draggingSlider != null) {
            dragSliderTo(mouseX);
            return true;
        }

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

        // The sidebar tree's drag, before the flat row drag because the two cannot both be live: a press
        // claims one list or the other, and the sidebar's rows are widgets while these lists are drawn.
        // The threshold separates a click from a drag exactly as it does everywhere else; the follow is
        // the pointer plus the drop it currently means, recomputed each frame so the mark the reader
        // sees is the drop they will get.
        if (sidebarDragKey != null) {
            if (!sidebarDragLive) {
                if (Math.abs(mouseX - sidebarDragPressX) <= DRAG_THRESHOLD
                        && Math.abs(mouseY - sidebarDragPressY) <= DRAG_THRESHOLD) {
                    return true;
                }
                sidebarDragLive = true;
                pressMoved = true;
            }
            sidebarDragPointerY = mouseY;
            sidebarDrop = SidebarDrag.target(sidebarRows(), mouseY, sidebarDragKey, sidebarDragGroup);
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
        // The choice card's list, likewise.
        if (choiceView.draggingThumb()) {
            choiceView.dragThumbTo(mouseY);
            return true;
        }

        // And the rewards panel's.
        if (rewardView.draggingThumb()) {
            rewardView.dragThumbTo(mouseY);
            return true;
        }

        // The type picker's list, on the same contract as the rest of the bars.
        if (overlayView.draggingThumb()) {
            overlayView.dragThumbTo(mouseY);
            return true;
        }

        if (toolsView.draggingThumb()) {
            toolsView.dragThumbTo(mouseY);
            return true;
        }

        if (sidebarView.draggingThumb() || partyLeftView.draggingThumb()
                || partyRightView.draggingThumb()) {
            if (sidebarView.draggingThumb()) {
                sidebarView.dragThumbTo(mouseY);
            }
            else {
                partyScrollAt(mouseX).dragThumbTo(mouseY);
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
        // A slider's release: the one commit for the whole drag. Read and cleared before anything else
        // looks at the gesture, because the drag is over the moment the button is up.
        if (draggingSlider != null) {
            releaseSlider();
            return true;
        }

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

        // The sidebar tree's release. Unlike the flat rows above, a press that never travelled has to
        // fall through to the widget pass: the row is a button, and a button's click is its release.
        // Only a live drag is swallowed here -- and swallowing it is what stops the same release firing
        // the row's press after it has just been dropped somewhere else.
        if (sidebarDragKey != null) {
            String key = sidebarDragKey;
            boolean group = sidebarDragGroup;
            boolean live = sidebarDragLive;
            SidebarDrag.Drop drop = sidebarDrop;
            sidebarDragKey = null;
            sidebarDragGroup = false;
            sidebarDragLive = false;
            sidebarDrop = null;
            if (live) {
                commitSidebarDrop(key, group, drop);
                return true;
            }
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
        if (choiceView.endThumbDrag() || rewardView.endThumbDrag() || overlayView.endThumbDrag()
                || sidebarView.endThumbDrag() || partyLeftView.endThumbDrag()
                || partyRightView.endThumbDrag()
                || toolsView.endThumbDrag()) {
            return true;
        }

        // The curve handle's release: one op, or nothing at all if the press never travelled.
        if (bendDragFrom != null) {
            String bendFrom = bendDragFrom;
            String bendTo = bendDragTo;
            boolean bendLive = bendDragLive;
            Double bend = bendPreview;
            Double anchor = anchorPreview;
            java.util.List<Double> handle = handlePreview;
            String kind = bendDragKind;
            bendDragFrom = null;
            bendDragTo = null;
            bendDragKind = null;
            bendDragLive = false;
            bendPreview = null;
            anchorPreview = null;
            handlePreview = null;
            dragging = false;
            if (bendLive && kind != null) {
                if (HANDLE_BEND.equals(kind) && bend != null) {
                    setLineBend(bendFrom, bendTo, bend);
                }
                else if (handle != null) {
                    setLineHandle(bendFrom, bendTo, kind, handle);
                }
                else if (anchor != null) {
                    setLineAnchor(bendFrom, bendTo, kind, anchor);
                }
            }
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
                        // Built on the draft, not the replica: an edge drag that lands while another
                        // prerequisite is still pending must keep it rather than overwrite it.
                        List<String> dependencies = new ArrayList<>(fieldDraft.strings(chapter,
                                landing.id(), "dependsOn", QuestPanelLayout.strings(quest, "dependsOn")));
                        if (!dependencies.contains(from)) {
                            dependencies.add(from);
                            sendField(chapter, landing.id(), "dependsOn",
                                    stringArray(dependencies));
                        }
                        else {
                            status(landing.id() + " already depends on " + from, true);
                        }
                    }
                }
            }
            // A right-press that never travelled is a click, and the canvas's menus are what a
            // right-click is for: on the node it was over, or on the line, or on the empty canvas.
            if (!live && !pressMoved) {
                openCanvasMenuAt(mouseX, mouseY);
            }
            return true;
        }

        // A right-press on empty canvas never arms an edge, so its release arrives here: a menu for the
        // canvas if the pointer stayed put, and nothing at all if it travelled — a right-drag on empty
        // canvas is not a pan, and it is not a menu either.
        if (button == 1 && canvasRightPressed) {
            canvasRightPressed = false;
            if (!pressMoved) {
                openCanvasMenuAt(mouseX, mouseY);
            }
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
                if (pressedAlt) {
                    // Alt+click: centre the node between its two nearest links. Not a card, and not a
                    // selection statement -- the gesture is about where the node sits.
                    straightenNode(id);
                    return true;
                }
                selectForCard(id);
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
        // The wheel closes an open menu, wherever it is. The canvas zooms and the lists scroll under the
        // pointer, so a menu left open would hang over a node or a row that has moved out from under it.
        if (!menu.isEmpty()) {
            closeMenu();
        }
        if (overlay == Overlay.PICKER) {
            // The wheel is the list's, and nothing behind the card answers it. First, before the dock's
            // branch below: the panel is still built behind the card, so without this the wheel scrolled
            // the dock underneath -- the same hole the party panel's note describes.
            if (pickerFrame != null) {
                pickerScroll = Math.max(0, Math.min(pickerScroll - (int) (scrollY * 30),
                        ItemPickerLayout.maxScroll(pickerRows, pickerFrame)));
            }
            return true;
        }

        if (overlay == Overlay.CHOICE) {
            // The card is a list, so the wheel is the list's -- and absorbed whether or not there is
            // anywhere to go, the same as every other card's.
            choiceView.scrollBy(-(int) (scrollY * 30));
            return true;
        }

        if (overlay == Overlay.REWARDS) {
            rewardView.scrollBy(-(int) (scrollY * 30));
            return true;
        }

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
            partyScrollAt(mouseX).scrollBy(-(int) (scrollY * 30));
            return true;
        }

        if (overlay == Overlay.QUEST) {
            // The settings page owns the wheel while it is open, before anything else looks at it: it is
            // a page of the card rather than a list in it, so the card's own scroll behind it is not
            // what the pointer is over. Without this branch the wheel fell through to the body -- which
            // scrolled the invisible editor behind the page and left the column still, and the report
            // was simply "i cant scroll".
            if (settingsOpen) {
                settingsView.scrollBy(-(int) (scrollY * 30));
                return true;
            }
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
    /**
     * The party panel's two live fields.
     *
     * <h2>Why the search rebuilds on every character</h2>
     *
     * <p>Because the invite list is drawn from the layout, and the layout is built once per rebuild —
     * filtering at draw time would leave rows registered for players whose names no longer match, so a
     * click could land on an invisible button. A rebuild re-creates the field with the text preserved
     * in {@link #partySearch} and re-focuses it, which is why the screen keeps that string at all: the
     * widget is not the storage.
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        boolean handled = super.charTyped(codePoint, modifiers);
        if (!handled) {
            return false;
        }
        if (getFocused() == partySearchField && partySearchField != null) {
            partySearch = partySearchField.value();
            partySearchFocused = true;
            rebuildWidgets();
            return true;
        }
        if (getFocused() == partyCreateField && partyCreateField != null) {
            // No rebuild: the create field is the only thing its text affects, so there is nothing to
            // redraw until it is submitted.
            partyCreateName = partyCreateField.value();
            partyCreateFocused = true;
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The naming card's own keys, read before anything else: Enter is the button the card is for,
        // and Escape abandons it without touching a file. Handing Enter to the field would blur it,
        // which is not what a form's Enter means.
        if (overlay == Overlay.NAMING) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeNaming();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitNaming();
                return true;
            }
        }
        // And the sidebar menu's, before the overlay Escape below: a menu is the innermost thing on
        // screen while it is open, so Escape closes it and not what it was opened over.
        if (!menu.isEmpty() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeMenu();
            return true;
        }
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
        // A focused editor's Escape: commit and blur, not close the card. **Forwarded to the widget by
        // name**, because "declining" here is not forwarding: this method's own `return false` leaves
        // without ever reaching `super.keyPressed`, which is the only thing that routes a key to the
        // focused child -- so the old shape left Escape answered by nobody. The widget's own Escape is
        // the edit ending, and this is the screen saying so out loud.
        //
        // **Unless the field is not on screen**: one scrolled out of the body is stood down and takes no
        // keys (see `repositionInlineEditor`), so Escape means what it means for the card -- leave it,
        // committing on the way out -- rather than being answered by nobody. The `visible` test below is
        // that case, and it is why this runs before everything else: a field with the keyboard is the one
        // thing that outranks the key's other meanings.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE
                && getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && focused.visible) {
            boolean handled = focused.keyPressed(keyCode, scanCode, modifiers);
            if (!focused.isFocused()) {
                // The widget blurred itself, and the screen's own focus pointer would otherwise stay on
                // it: a widget that is not focused refuses `charTyped`, so the field read as dead until
                // it was clicked again. See the same cleanup after `super.keyPressed` below.
                setFocused(null);
            }
            if (handled) {
                return true;
            }
            // The widget had no Escape of its own -- a button, say, which answers Enter and Space -- so
            // the key keeps its screen meanings rather than being swallowed by whatever happens to hold
            // the focus. Falling through, not returning: the branches below are those meanings (leave
            // the picker, close the card, close the book). A focused button used to eat Escape whole,
            // because the old shape returned here without forwarding it anywhere at all.
        }

        // A pick is armed on the canvas with the card closed, so its Escape is answered here rather than
        // inside the overlay branch below: the author who armed it has no overlay to close, and before
        // this was outside the guard Escape fell through to the screen's own handler and closed the whole
        // book. The field comment said "Escape cancels" and the code could not reach the branch that did.
        if (pickingDependency && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            pickingDependency = false;
            dev.ellipog.tasked.client.dev.DependencyPick pick = pendingPick;
            pendingPick = null;
            status("Add cancelled", false);
            if (pick != null) {
                returnToEditedQuest(pick);
            }
            return true;
        }

        if (overlay != Overlay.NONE && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (settingsOpen) {
                // Back to the card, and the page's pending values go with it: a draft is the page's own
                // state, and one left behind would make the canvas draw a number the file does not have.
                settingsOpen = false;
                draggingSlider = null;
                settingsDraft.clear();
                rebuildWidgets();
                return true;
            }
            if (pickingEntryType != null) {
                pickingEntryType = null;
                rebuildWidgets();
                return true;
            }
            if (overlay == Overlay.CHOICE) {
                // Escape leaves the question unanswered, which loses nothing -- see `closeChoice`. This
                // branch rather than `closeOverlay`, which does not clear the offer: the next tick would
                // open the same card straight back up.
                closeChoice();
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

        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        // A widget that ended its own edit (Enter in a text field blurs it and commits) leaves the
        // screen's focus pointer on it -- `AbstractWidget` and the container's focus are two flags, and
        // only the container's is what input is routed by. Clearing it here is what makes the *next*
        // keystroke reach the screen again instead of a field that refuses characters because it is no
        // longer focused.
        if (getFocused() instanceof net.minecraft.client.gui.components.AbstractWidget focused
                && !focused.isFocused()) {
            setFocused(null);
        }
        return handled;
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
        // The shifted pair, read before the plain keys below: `Ctrl+N` is a new *quest*, and on the same
        // key the shift is the difference between "a node in this chapter" and "a chapter in this book".
        if (ctrl && Screen.hasShiftDown() && keyCode == GLFW.GLFW_KEY_N) {
            newChapterFromToolbar();
            return true;
        }
        if (ctrl && Screen.hasShiftDown() && keyCode == GLFW.GLFW_KEY_G) {
            newGroupFromToolbar();
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
        String chapter = effectiveChapter();
        // Empty rather than null when the book has no chapters yet. The payload's chapter is the
        // session a structural edit is recorded on, and "no session" is the empty string the server
        // reads the same way -- which is what lets an empty pack make its first chapter.
        TaskedNetworking.sendEditorOp(chapter == null ? "" : chapter, op);
    }

    /**
     * A card field's commit: the value is remembered locally, then sent.
     *
     * <p>The draft is what makes a stepper spammable — see {@code FieldDraft} for the whole story —
     * and one op per press stays, so Ctrl+Z still undoes one nudge. The chapter case is deliberately
     * not here: a chapter or group field is drawn from the tree, and its draft is the settings one.
     */
    private void sendField(String quest, String path, JsonElement value) {
        sendField(effectiveChapter(), quest, path, value);
    }

    /**
     * The same, for an edit aimed at another chapter: a dependency pick taken in one chapter can be
     * for a quest in the chapter it was opened from, and the draft has to live under the chapter whose
     * replica will answer for it.
     */
    private void sendField(String chapter, String quest, String path, JsonElement value) {
        if (quest == null) {
            return;
        }
        fieldDraft.set(chapter, quest, path, value,
                ClientQuestCache.treeRevision(), Util.getMillis());
        TaskedNetworking.sendEditorOp(chapter == null ? "" : chapter, new EditorOp.SetField(quest, path, value));
    }

    /**
     * A chapter field's commit: the same draft, under the chapter's own owner.
     *
     * <p>The chapter's file is in the replica, so the same convergence rule applies — the value holds
     * until the copy holds it, and a refusal clears it. The Chapter tab reads it through
     * {@code FieldDraft.overlaid}, because it builds all of its rows from the tree at once.
     */
    private void sendChapterField(String path, JsonElement value) {
        fieldDraft.set(effectiveChapter(), dev.ellipog.tasked.client.dev.FieldDraft.CHAPTER_OWNER,
                path, value, ClientQuestCache.treeRevision(), Util.getMillis());
        send(new EditorOp.SetChapter(path, value));
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
                // What the author sees is what is copied: a pending edit is part of the selection's
                // value, and the clipboard deep-copies, so a later overlay cannot reach into it.
                trees.add(fieldDraft.overlaid(chapter, id, tree));
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
        pasteAt(viewport().contentX(canvasLeft() + (canvasRight() - canvasLeft()) / 2.0),
                viewport().contentY(canvasTop() + (canvasBottom() - canvasTop()) / 2.0));
    }

    /**
     * Pastes the clipboard at a content position, snapped like every other placement.
     *
     * <p>Two callers, one body: Ctrl+V drops at the middle of what you are looking at, and the canvas
     * menu's "Paste here" drops where you right-clicked. A second copy would be a second thing to keep
     * in step about the 48-pixel stagger and the snap.
     */
    private void pasteAt(double x, double y) {
        List<JsonObject> trees = ClientEditorClipboard.quests();
        if (trees.isEmpty()) {
            return;
        }
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
        toast(message, false);
        say("\u00a77" + message);
    }

    /**
     * The book's own messages, drawn over the card and under the tooltips.
     *
     * <p>Over the card, because a notice the card covers is not a notice; under the tooltips, because a
     * tooltip is what the pointer is asking for and a toast is what just happened. Anchored to the card's
     * bottom corner and growing upward, so the newest sentence sits where the eye already is and the ones
     * being read move up rather than being pushed away.
     */
    private void drawToasts(GuiRenderer r, long now) {
        List<ToastStack.Toast> visible = toasts.visible(now);
        if (visible.isEmpty()) {
            return;
        }
        int line = 14;
        int gap = 3;
        int width = Math.min(Math.max(90, geometry().modal().width() / 4), 240);
        BookGeometry.Rect card = geometry().modal();
        for (int i = 0; i < visible.size(); i++) {
            ToastStack.Toast toast = visible.get(i);
            int fromBottom = visible.size() - 1 - i;
            int y = card.bottom() - 10 - line - fromBottom * (line + gap);
            BookGeometry.Rect box = BookGeometry.Rect.at(card.right() - 10 - width, y, width, line);
            float alpha = toast.alpha(now);
            int colour = toast.error() ? ArmatureTheme.blocked() : ArmatureTheme.body();
            ArmatureTheme.panel(r, box.x(), box.y(), box.width(), box.height(),
                    Colour.alphaOf(ArmatureTheme.raised(), alpha),
                    Colour.alphaOf(toast.error() ? ArmatureTheme.blocked() : ArmatureTheme.panelEdge(), alpha));
            r.text(Measure.truncate(toast.text(), box.width() - 8, textMeasure(r)), box.x() + 4,
                    box.y() + (line - 8) / 2, Colour.alphaOf(colour, alpha));
        }
    }

    /**
     * A sentence for the reader of this book, said over the book.
     *
     * <p>Alongside `say`, not instead of it: chat keeps the history for a player who is not looking at the
     * book, and the toast is the same sentence shown where the book is -- which is the one place chat
     * cannot be read, because the HUD is not drawn behind a screen. See {@link ToastStack}.
     */
    private void toast(String message, boolean error) {
        toasts.add(message, error, Util.getMillis());
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

        // A choice the server is holding comes to the front. It is a question the player asked by
        // claiming, so it is not left waiting behind a card they opened since -- and it is read here
        // rather than in the drawing, because opening a card re-places every widget.
        if (ClientChoiceOffers.current() != null && overlay != Overlay.CHOICE) {
            openChoiceOffer();
        }

        // The book's own notices: what has faded goes, and a quest that has just become collectable says so.
        // Read here rather than in the drawing, because expiring is bookkeeping and the drawing is not the
        // place for it.
        long millis = Util.getMillis();
        toasts.expire(millis);
        // The locate flash's own expiry, for the same reason: the drawing asks `CanvasReveal.flash`
        // every frame and draws nothing once it is over, and the state that named the node goes when
        // the flash does rather than outliving it.
        if (flashQuest != null && millis - flashStart >= CanvasReveal.FLASH_MILLIS) {
            flashQuest = null;
        }
        long synced = ClientQuestCache.progressRevision();
        if (synced != announcedProgress) {
            announcedProgress = synced;
            announceCompletions();
        }

        // The rewards panel is a list of what the server owes, so a progress sync changes it: a claim from
        // anywhere -- a row, Claim all, the command, a teammate's team reward -- lands here as a row that
        // should not be there any more. Rebuilt on the revision, the same way the dock's panel follows the
        // tree, and only while the card is open because that is the only time the rows are read.
        if (overlay == Overlay.REWARDS) {
            long progress = ClientQuestCache.progressRevision();
            if (progress != rewardsRevision) {
                rewardsRevision = progress;
                rebuildWidgets();
            }
        }

        long revision = ClientQuestCache.treeRevision();
        ClientChapterReplica.Copy copy = ClientChapterReplica.of(effectiveChapter());
        long replicaRevision = copy == null ? -1 : copy.revision();
        if (revision != questPanelRevision || replicaRevision != questReplicaRevision) {
            questPanelRevision = revision;
            questReplicaRevision = replicaRevision;
            boolean dockPanel = toolsOpen && toolsTab == ToolsLayout.Tab.CHAPTER
                    && overlay == Overlay.NONE;
            // The dock's picker counts as an editor for this purpose: a replica arriving while it is
            // open changes the chapter under the list, and the card's title and the "current" row are
            // read from that chapter. Rebuilding keeps the typed query -- `buildPickerWidgets` carries
            // the box's value across a rebuild -- so this cannot eat what is being searched for.
            boolean modalEditor = (overlay == Overlay.QUEST || overlay == Overlay.PICKER) && mayEditNow();
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

        // And the sidebar scrolls itself while a row is held near an edge. The flat lists deliberately
        // do not auto-scroll -- they are short enough to wheel mid-drag -- but the tree is the one list
        // that routinely does not fit, and "drag it to the group at the bottom" has to be possible
        // without letting go. In tick rather than in the drag's follow, because a pointer held still at
        // the edge stops producing mouse events and the list has to keep moving.
        if (sidebarDragLive) {
            autoScrollSidebar();
        }

        // Every answer nobody has read yet, oldest first. A burst of quick edits -- spamming a stepper
        // -- puts several between two ticks, and the store used to keep only the last, so a refusal in
        // the middle was lost. A refusal also drops that chapter's pending values: the edit did not
        // stick, and the copy's own answer is the truth.
        for (EditorReplyPayload reply : ClientEditReplies.drain()) {
            if (!reply.ok()) {
                fieldDraft.forgetChapter(reply.chapter());
                // The settings page's pending values are the same kind of ask and end the same way. A
                // refusal does not move the tree, so `onRevision` would never drop them and the preview
                // would keep drawing — and the next arrow press would accumulate from — the value the
                // server just said no to. The page belongs to the effective chapter, so only that
                // chapter's refusal clears it; a drag re-stamps itself on the next frame either way.
                if (reply.chapter().equals(effectiveChapter())) {
                    settingsDraft.clear();
                }
            }
            if (!reply.chapter().equals(effectiveChapter())) {
                // The answer is about a chapter the author has navigated away from. It is still news about
                // that chapter's copy -- a refusal is exactly why its panel would keep saying "has not
                // arrived yet" -- so it is recorded and logged rather than dropped in silence, which is what
                // made the placeholder's lie impossible to diagnose.
                if (!reply.ok()) {
                    String said = String.join(" ", reply.lines());
                    ClientChapterReplica.refuse(reply.chapter(), said);
                    Constants.LOG.info("tasked: replica for \"{}\" was refused while another chapter was open: {}",
                            reply.chapter(), said);
                }
                continue;
            }
            for (String line : reply.lines()) {
                if (reply.ok()) {
                    report(line);
                }
                else {
                    // Recorded, so the Chapter tab's placeholder can say what the server said instead of
                    // claiming a copy is still on its way.
                    ClientChapterReplica.refuse(reply.chapter(), line);
                    Constants.LOG.info("tasked: replica for \"{}\" was refused: {}", reply.chapter(), line);
                    toast(line, true);
                    say("\u00a7c" + line);
                }
            }
            if (reply.ok() && !reply.questId().isEmpty()) {
                // A quest the server made: created, or duplicated. Selecting it here rather than when the op was
                // sent, because the id is the server's to choose and this is the first moment the author has it.
                selectedQuest = reply.questId();
                report("Now editing " + reply.questId());
            }
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

    /**
     * The state word a card and a viewer page both show.
     *
     * <p>Public since the viewer seam: an EMI or JEI row and the book's own card must not be able to
     * disagree about what "started" is called, and the alternative -- a second switch in the content
     * class -- is exactly how they would.
     */
    public static String stateLabel(QuestState state) {
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
    /**
     * Opens the book on one quest, with its card up -- the entry point a recipe viewer calls.
     *
     * <p>Static and id-taking because the screen opener Armature offers carries an id and nothing
     * else, and the book is constructed by Minecraft's screen machinery a moment later: the request is
     * parked in {@link QuestBookFocus} and consumed by {@code init}. A quest this client does not hold
     * -- a stale click, or a click while the tree is still arriving -- opens nothing; there is no book
     * state for a quest that is not there.
     */
    public static void openOn(String questId) {
        if (questId == null || questId.isEmpty() || cacheEntryFor(questId) == null) {
            return;
        }
        QuestBookFocus.request(questId);
        ArmatureClient.openScreen(Tasked.QUEST_BOOK_SCREEN);
    }

    public static void forgetViewState() {
        selectedChapter = null;
        selectedQuest = null;
        multiSelection.clear();
        // The viewer request goes with the selection: it names a quest of the server being left, and a
        // book opened on the next server would look for a quest that is not there.
        QuestBookFocus.clear();
        // The clipboard goes too: another server's quests are not this one's to paste, and a tree
        // carried across a disconnect is an op the new server would dutifully apply to its own files.
        ClientEditorClipboard.clear();
        // And the server's dimension list, for the same reason: a picker still offering the last
        // server's worlds would write an id the new one has never heard of.
        ClientDimensions.clear();
        // And the stages: a gate answered from the last server's flags would open a quest this one
        // has not unlocked.
        ClientStages.clear();
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
        // And the player's own expansions, for the reason the outline goes: they belong to a questline
        // on a server this client has left.
        sidebarExpansion.clear();
    }

    @Override
    public void removed() {
        super.removed();
        // The pending values die with the screen: reopening reads the server's copy, and a draft from
        // a session that is over must not answer for it.
        fieldDraft.clear();
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
        return themeFor(effectiveChapter());
    }

    /**
     * The palette one chapter asks for, by id: its named theme with its token patch over it, or the
     * player's own when it names neither.
     *
     * <p>Takes the chapter rather than reading the effective one, because the sidebar's counts are
     * drawn for every chapter with something waiting — each row's count is that chapter's own mark,
     * and it has to resolve the palette of the chapter it counts, not of the one on screen.
     */
    private static Theme themeFor(String chapter) {
        if (chapter == null) {
            return ClientAppearance.LOOK.main();
        }
        String named = ClientQuestCache.chapterTheme(chapter);
        if (named != null && !named.isBlank() && !ChapterTheme.known(named)) {
            warnAboutThemeOnce(chapter, named);
        }
        // The chapter's token patch composes over whatever the name resolved to — including over the
        // player's own theme, for a chapter that set colours without naming one. See ChapterTheme.
        return ChapterTheme.compose(named, ClientQuestCache.chapterThemePatch(chapter),
                ClientAppearance.LOOK.main());
    }

    /**
     * Whether the open overlay belongs to the chapter rather than to the player.
     *
     * <p>The quest card, the item picker and the naming card are all about the chapter on screen — the
     * picker is choosing that chapter's or its quest's icon, the naming card is renaming that chapter.
     * The party, choice and rewards cards are the player's own, wherever they are standing, so they keep
     * the main theme. This is the one list, so a modal added later is themed by a decision rather than
     * by whether somebody remembered to open a scope.
     */
    private boolean chapterBoundOverlay() {
        return overlay == Overlay.QUEST || overlay == Overlay.PICKER || overlay == Overlay.NAMING;
    }

    /**
     * The palette a tooltip belongs to: the surface under the pointer, or the book's own.
     *
     * <p>A tooltip describes what it is next to, so it takes that surface's theme — the caption over a
     * chapter's canvas wears the chapter's colours, and one over the sidebar wears the book's. Chrome
     * answers with {@link ArmatureTheme#chrome()} rather than the main theme, so a tooltip drawn while
     * a chapter scope happens to be open cannot inherit it by accident.
     */
    private Theme tooltipTheme(int mouseX, int mouseY) {
        if (chapterBoundOverlay() || (overlay == Overlay.NONE && inCanvas(mouseX, mouseY))) {
            return viewportTheme();
        }
        return ArmatureTheme.chrome();
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
