package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.net.SubmitTaskPayload;
import dev.ellipog.tasked.progress.QuestState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * text — which is why the text was soft as well as the world. Overriding {@link #renderBackground} to
 * do nothing is the whole fix, and it is one method.
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
 * {@link #stripButtonX()}, {@link #stripButtonY()}, {@link #chapterRows()}. One expression, used twice,
 * rather than two expressions that happen to agree until someone changes one.
 *
 * <h2>Every colour is eight digits</h2>
 *
 * <p>{@code 0xAARRGGBB}, alpha included, and all of them live in {@link ArmatureTheme}. On 1.21.1 a
 * colour written without alpha happens to come out opaque; from 1.21.6 it does not.
 */
public final class QuestBookScreen extends Screen {

    // ------------------------------------------------------------------
    // Layout constants. See the class comment for why these are shared.
    // ------------------------------------------------------------------

    // Every one of these is an alias, not a number. The number lives in BookGeometry, and this class
    // reads it from there -- because a second copy of "the sidebar is 132 wide" is a copy that can
    // disagree with the one the overlap test checks. That disagreement is precisely how two buttons
    // ended up drawn on top of each other: "near the bottom right" written twice.
    private static final int SIDEBAR_WIDTH = BookGeometry.SIDEBAR_WIDTH;
    private static final int HEADER_HEIGHT = BookGeometry.HEADER_HEIGHT;
    private static final int STRIP_HEIGHT = BookGeometry.STRIP_HEIGHT;
    private static final int OVERLAY_MARGIN = BookGeometry.OVERLAY_MARGIN;

    /** The sidebar's footer, as two rows of its own. Four controls do not fit across one. */
    private static final int ROW_HEIGHT = BookGeometry.ROW_HEIGHT;
    private static final int ROW_GAP = BookGeometry.ROW_GAP;
    private static final int EDGE = BookGeometry.EDGE;

    /** The strip's Open button. Also what the strip's text must stop short of. */
    private static final int OPEN_WIDTH = BookGeometry.OPEN_WIDTH;

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
            geometry = new BookGeometry(width, height);
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
     * <p>So one number per box, and everything around it derived: {@link #ROW_ADVANCE} is
     * {@link #ROW_ICON} plus a gap, the text offset is {@link #ROW_ICON} plus a gap, and
     * {@link #measureOverlay} multiplies by {@code ROW_ADVANCE} — the same number the drawing advances
     * by. An icon is then the size of its box by construction rather than by agreement.
     */
    private static final int NODE_INSET = 3;

    private static final int ROW_ICON = 18;

    /** The row pitch: the icon box plus the gap under it. Used to draw *and* to measure. */
    private static final int ROW_ADVANCE = ROW_ICON + 6;

    private static final int HEADER_ICON = 20;

    /** The pitch for a text-only row — a dependency, which has a tick but no icon. */
    private static final int DEP_ADVANCE = 14;

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
    private enum Overlay { NONE, QUEST }

    // --- view state, kept between openings -----------------------------------

    private static String selectedChapter;
    private static String selectedQuest;
    private static float zoom = 1.0F;
    private static int panX;
    private static int panY;
    private static String pannedChapter;
    private static boolean centred;

    // --- per-open state ------------------------------------------------------

    private Overlay overlay = Overlay.NONE;

    /** The quest whose overlay is open, by id. */
    private String overlayQuest;

    /** Scroll offset inside the overlay, in pixels. */
    private int overlayScroll;

    private boolean dragging;
    private boolean pressMoved;
    private double pressX;
    private double pressY;
    private int panAtPressX;
    private int panAtPressY;

    /** The node under the pointer when the press began, if any. */
    private String pressedNode;

    /**
     * This screen's own controls, in the order they were created.
     *
     * <p>Kept because {@code Screen.renderables} is private — established by the compiler after being
     * assumed otherwise. Keeping the list is the better arrangement anyway: a screen that knows which
     * widgets are its own can ask them questions the vanilla base class has no concept of, which is
     * what {@link #drawTooltips} does.
     */
    private final List<ArmatureButton> buttons = new ArrayList<>();

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

    private int canvasRight() {
        return geometry().canvas().right();
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

    /** The y of the sidebar's lower footer row, which holds Done. */
    private int footerRow2Y() {
        return geometry().footerRow2Y();
    }

    /** The y of the sidebar's upper footer row, which holds the zoom controls. */
    private int footerRow1Y() {
        return geometry().footerRow1Y();
    }

    /** Where a chapter row starts, and so the top of the list. */
    private int chapterListTop() {
        return geometry().chapterListTop();
    }

    /**
     * How many chapter rows fit above the footer.
     *
     * <p>This replaced {@code (footerRow1Y() - 6 - chapterListTop()) / 22} — the same arithmetic with
     * the 6 and the 22 written in by hand twice, once here and once where the chapter rows were drawn.
     * {@link BookGeometry} derives the row count from its own {@code CHAPTER_ROW_PITCH}, so a row can
     * no longer be drawn underneath a footer button by changing one number and not the other.
     */
    private int chapterRows() {
        return geometry().chapterRows();
    }

    private int stripButtonX() {
        return geometry().stripButtonX();
    }

    private int stripButtonY() {
        return geometry().stripButtonY();
    }

    /** Where the strip's text has to stop, so a long title does not run under the Open button. */
    private int stripTextLimit() {
        return geometry().stripTextLimit();
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

    /** The quest the bottom strip describes: the selected one, if it is in the chapter on screen. */
    private ClientQuestCache.Entry stripped() {
        String chapter = effectiveChapter();
        if (chapter == null || selectedQuest == null) {
            return null;
        }
        return questsIn(chapter).stream()
                .filter(entry -> entry.id().equals(selectedQuest))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Canvas coordinates
    // ------------------------------------------------------------------

    private int nodeSize(ClientQuestCache.Entry entry) {
        int base = Mth.clamp(entry.size(), 26, 48);
        return Math.max(12, Math.round(base * zoom));
    }

    private int nodeScreenX(ClientQuestCache.Entry entry) {
        return canvasLeft() + panX + Math.round(entry.x() * zoom);
    }

    private int nodeScreenY(ClientQuestCache.Entry entry) {
        return canvasTop() + panY + Math.round(entry.y() * zoom);
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

        int canvasW = canvasRight() - canvasLeft();
        int canvasH = canvasBottom() - canvasTop();
        panX = Math.round(canvasW / 2F - (minX + maxX) / 2F * zoom);
        panY = Math.round(canvasH / 2F - (minY + maxY) / 2F * zoom);
        pannedChapter = chapter;
        centred = true;
    }

    /**
     * Applies a new zoom while keeping the world point under {@code (mouseX, mouseY)} fixed.
     *
     * <p>The whole calculation is one line of algebra: convert the pointer to a world coordinate,
     * zoom, and solve for the pan that puts that same world coordinate back under the pointer. Getting
     * it wrong is what makes a zoom appear to run away from the cursor, which is the single most
     * common complaint about a graph UI.
     */
    private void zoomAt(double mouseX, double mouseY, float factor) {
        float next = Mth.clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (next == zoom) {
            return;
        }
        float worldX = (float) ((mouseX - canvasLeft() - panX) / zoom);
        float worldY = (float) ((mouseY - canvasTop() - panY) / zoom);
        panX = Math.round((float) (mouseX - canvasLeft()) - worldX * next);
        panY = Math.round((float) (mouseY - canvasTop()) - worldY * next);
        zoom = next;
    }

    /** Zooms about the canvas centre, for the buttons, which have no pointer position. */
    private void zoomCentre(float factor) {
        zoomAt(canvasLeft() + (canvasRight() - canvasLeft()) / 2.0,
                canvasTop() + (canvasBottom() - canvasTop()) / 2.0, factor);
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

    @Override
    protected void init() {
        clearWidgets();
        buttons.clear();

        if (overlay == Overlay.QUEST) {
            buildOverlayWidgets();
            return;
        }

        // Every rectangle below comes from BookGeometry's control map, which is what BookGeometryTest
        // asserts on. That sharing is the whole point: the test cannot see the screen, so the screen
        // has to build itself from the thing the test can see. The previous version created controls
        // from numbers written here by hand, and a test asserting on a *parallel* description would
        // have passed while the screen still overlapped -- which is worse than no test at all, because
        // it would have been believed.
        boolean hasOpen = stripped() != null;
        Map<String, BookGeometry.Rect> controls = geometry().controls(chapters().size(), hasOpen);

        // Chapters, down the left. A flat control for the selected one, so which is showing reads at a
        // glance without a separate highlight rectangle.
        int index = 0;
        for (Map.Entry<String, String> chapter : chapters().entrySet()) {
            BookGeometry.Rect row = controls.get("chapter" + index);
            if (row == null) {
                // More chapters than fit. BookGeometry decides how many that is, so the loop simply
                // stops when it stops offering rectangles.
                break;
            }
            index++;

            final String chapterId = chapter.getKey();
            boolean isSelected = chapterId.equals(effectiveChapter());

            // The full chapter title, not a truncated one: ArmatureButton truncates its own label to
            // the width it actually has, and does it by measuring the font. Passing a pre-trimmed
            // string here was trimming by character count to a number chosen by eye, which cut
            // "Getting Started" to "Getting Starte…" in a 116px-wide button with room to spare.
            control(row, Component.literal(chapter.getValue()),
                    () -> {
                        selectedChapter = chapterId;
                        // The selection belongs to the chapter being left, so it closes. Leaving it
                        // open would show a quest that is not on screen, with a Submit button, for a
                        // chapter you have walked away from.
                        selectedQuest = null;
                        centred = false;
                        rebuildWidgets();
                    })
                    .flat(isSelected)
                    .textColour(isSelected ? ArmatureTheme.TITLE : ArmatureTheme.BODY)
                    .tooltip(Component.literal(chapter.getValue()));
        }

        // The sidebar's footer. Two rows, because four controls do not fit across 116 pixels — which
        // is why the footer has two rows at all, and the rectangles come from the same map the overlap
        // test walks.
        control(controls.get("zoomIn"), Component.literal("+"), () -> zoomCentre(1.25F))
                .tooltip(List.of(Component.literal("Zoom in"),
                        Component.literal("Or scroll up over the canvas")))
                .textColour(ArmatureTheme.BODY);

        control(controls.get("zoomOut"), Component.literal("\u2212"), () -> zoomCentre(0.8F))
                .tooltip(List.of(Component.literal("Zoom out"),
                        Component.literal("Or scroll down over the canvas")))
                .textColour(ArmatureTheme.BODY);

        control(controls.get("centre"), Component.literal("Centre"), () -> {
            centred = false;
            centreCanvas();
        })
                .tooltip(List.of(Component.literal("Re-centre the canvas"),
                        Component.literal("Drag with left or middle to pan")))
                .textColour(ArmatureTheme.BODY);

        control(controls.get("done"), Component.translatable("gui.done"), this::onClose)
                .textColour(ArmatureTheme.BODY)
                .tooltip(Component.literal("Escape also closes the book"));

        // The strip's own control. It sits on the strip beside the canvas, while Done sits in the
        // sidebar's footer — different surfaces either side of the divider. They are 10px apart in x
        // and 15px in y, which is why the old hand-written versions collided.
        ClientQuestCache.Entry stripped = stripped();
        BookGeometry.Rect open = controls.get("open");
        if (stripped != null && open != null) {
            control(open, Component.literal("Open"), () -> openOverlay(stripped.id()))
                    .accent(true)
                    .tooltip(List.of(Component.literal("Open this quest full screen"),
                            Component.literal("The whole description, every task and reward")));
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
        int taskIndex = firstManualTask(entry);
        Map<String, BookGeometry.Rect> controls = geometry().overlayControls(taskIndex >= 0);

        // Submit, for the first task a player hands over by hand. Bottom-left, where it is the last
        // thing read after the tasks and rewards.
        if (taskIndex >= 0) {
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
            back.textColour(ArmatureTheme.BODY)
                    .tooltip(Component.literal("Escape also closes this"));
        }
    }

    private void openOverlay(String questId) {
        overlay = Overlay.QUEST;
        overlayQuest = questId;
        overlayScroll = 0;
        rebuildWidgets();
    }

    private void closeOverlay() {
        overlay = Overlay.NONE;
        overlayQuest = null;
        overlayScroll = 0;
        rebuildWidgets();
    }

    /** The first task a player hands over by hand, or -1. */
    private static int firstManualTask(ClientQuestCache.Entry quest) {
        for (int i = 0; i < quest.tasks().size(); i++) {
            if (quest.tasks().get(i).manual()) {
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

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /**
     * Deliberately empty.
     *
     * <p>The base implementation blurs the framebuffer and draws vanilla's menu texture over it, and
     * {@code super.render()} calls it whether a subclass asks or not. Overriding it here is what stops
     * this screen — and its text — being blurred. See the class comment for the mechanism.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur, no panorama, no menu texture. This screen draws its own background.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        centreCanvas();
        graphics.fill(0, 0, width, height, ArmatureTheme.DIM);

        if (overlay == Overlay.QUEST) {
            drawOverlay(graphics);
        }
        else {
            drawBook(graphics, mouseX, mouseY);
        }

        // Widgets over the screen's own drawing, then tooltips over the widgets. renderBackground is a
        // no-op above, so super.render() draws the controls and nothing else.
        super.render(graphics, mouseX, mouseY, partialTick);
        drawTooltips(graphics, mouseX, mouseY);
    }

    private void drawBook(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft();
        int top = panelTop();
        int panelW = panelWidth();
        int panelH = panelHeight();

        ArmatureTheme.panel(graphics, left, top, panelW, panelH, ArmatureTheme.PANEL, ArmatureTheme.PANEL_EDGE);
        graphics.fill(left + 1, top + 1, left + SIDEBAR_WIDTH, top + panelH - 1, ArmatureTheme.RECESSED);
        graphics.fill(left + SIDEBAR_WIDTH, top + 1, left + panelW - 1, top + HEADER_HEIGHT - 1,
                ArmatureTheme.RAISED);
        graphics.fill(left + 1, top + HEADER_HEIGHT - 1, left + panelW - 1, top + HEADER_HEIGHT,
                ArmatureTheme.PANEL_EDGE);
        // A divider between the sidebar and everything else, so the two read as separate surfaces
        // rather than as one dark field with things floating in it.
        graphics.fill(left + SIDEBAR_WIDTH, top + 1, left + SIDEBAR_WIDTH + 1, top + panelH - 1,
                ArmatureTheme.PANEL_EDGE);

        graphics.drawString(font, title, left + 10, top + 9, ArmatureTheme.TITLE, false);
        if (ClientQuestCache.hasData()) {
            String summary = ClientQuestCache.questCount() + " quests  \u00b7  " + Math.round(zoom * 100) + "%";
            graphics.drawString(font, summary, left + panelW - 12 - font.width(summary), top + 9,
                    ArmatureTheme.FAINT, false);
        }

        if (!ClientQuestCache.hasData()) {
            // Two empty states. Saying "waiting" when it is really "nothing loaded" sends someone
            // hunting a sync bug that does not exist.
            Component message = Component.translatable(ClientQuestCache.hasTree()
                    ? "tasked.screen.quest_book.no_quests"
                    : "tasked.screen.quest_book.waiting");
            graphics.drawCenteredString(font, message, left + SIDEBAR_WIDTH + (panelW - SIDEBAR_WIDTH) / 2,
                    top + panelH / 2, ArmatureTheme.BODY);
            return;
        }

        String chapter = effectiveChapter();
        if (chapter != null) {
            drawCanvas(graphics, mouseX, mouseY, questsIn(chapter));
        }
        drawStrip(graphics, stripped());
    }

    /**
     * Draws the hovered control's tooltip, last of all.
     *
     * <p>Not vanilla's tooltip: that would draw in vanilla's style, which is the thing this UI avoids.
     * Drawn here, after the widgets, so it is over everything and not clipped by the canvas scissor.
     */
    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        for (ArmatureButton button : buttons) {
            if (button.tooltip() != null && button.isMouseOver(mouseX, mouseY)) {
                drawTooltip(graphics, button.tooltip(), mouseX, mouseY);
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
     */
    private void drawTooltip(GuiGraphics graphics, List<FormattedCharSequence> lines, int mouseX, int mouseY) {
        int textWidth = 0;
        for (FormattedCharSequence line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }

        int boxWidth = textWidth + 8;
        int boxHeight = lines.size() * 10 + 6;
        int x = mouseX + 10;
        int y = mouseY - 11;
        if (x + boxWidth > width) {
            x = mouseX - boxWidth - 4;
        }
        if (y + boxHeight > height) {
            y = height - boxHeight - 2;
        }
        y = Math.max(2, y);

        ArmatureTheme.panel(graphics, x, y, boxWidth, boxHeight, ArmatureTheme.PANEL,
                ArmatureTheme.CONTROL_EDGE_BRIGHT);
        int lineY = y + 4;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x + 4, lineY, ArmatureTheme.BODY, false);
            lineY += 10;
        }
    }

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

    private void drawCanvas(GuiGraphics graphics, int mouseX, int mouseY, List<ClientQuestCache.Entry> quests) {
        graphics.enableScissor(canvasLeft(), canvasTop(), canvasRight(), canvasBottom());
        graphics.fill(canvasLeft(), canvasTop(), canvasRight(), canvasBottom(), ArmatureTheme.CANVAS);

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
                drawConnector(graphics, dependency, quest,
                        done ? ArmatureTheme.LINE_DONE : ArmatureTheme.LINE);
            }
        }

        ClientQuestCache.Entry hovered = nodeAt(mouseX, mouseY, quests);
        for (ClientQuestCache.Entry quest : quests) {
            drawNode(graphics, quest, quest == hovered);
        }
        // Titles in their own pass, after every node, so a label can see the other nodes -- see the
        // comment on drawLabels for what happened when it could not.
        drawLabels(graphics, quests);

        if (quests.isEmpty()) {
            graphics.drawString(font, "No quests in this chapter", canvasLeft() + 10, canvasTop() + 10,
                    ArmatureTheme.FAINT, false);
        }

        // A hint, only until the player has zoomed. Then it would be clutter on a canvas they
        // demonstrably already know how to drive.
        if (Math.abs(zoom - 1.0F) < 0.001F) {
            graphics.drawString(font, "scroll to zoom  \u00b7  drag to pan  \u00b7  click a quest",
                    canvasLeft() + 6, canvasBottom() - 11, ArmatureTheme.FAINT, false);
        }

        graphics.disableScissor();

        // The hovered quest's name, drawn outside the scissor so it is never clipped by the canvas
        // edge. A node is an icon and nothing else, so without this the canvas is a wall of unlabelled
        // squares until you click one.
        if (hovered != null) {
            drawNodeCaption(graphics, hovered);
        }
    }

    /** The hovered node's title, under the pointer. */
    private void drawNodeCaption(GuiGraphics graphics, ClientQuestCache.Entry entry) {
        int size = nodeSize(entry);
        int x = nodeScreenX(entry);
        int y = nodeScreenY(entry);

        Component title = Component.literal(entry.title());
        int boxWidth = font.width(title) + 10;
        int boxX = Mth.clamp(x + size / 2 - boxWidth / 2, canvasLeft() + 2, canvasRight() - boxWidth - 2);
        int boxY = y + size + 4;
        if (boxY + 14 > canvasBottom()) {
            boxY = y - 18;
        }

        ArmatureTheme.panel(graphics, boxX, boxY, boxWidth, 14, ArmatureTheme.PANEL,
                ArmatureTheme.CONTROL_EDGE_BRIGHT);
        graphics.drawString(font, title, boxX + 5, boxY + 3, ArmatureTheme.TITLE, false);
    }

    /**
     * Draws an item so that it exactly fills a box of {@code box} pixels.
     *
     * <p>Delegates to {@link ArmatureTheme#drawIcon}, where the mechanism is explained. It lives there
     * rather than here because {@link ArmatureButton} needs the same thing, and two copies of "how do
     * you draw an item at a size other than 16px" is two chances to get it wrong.
     */
    private static boolean drawIcon(GuiGraphics graphics, ItemStack stack, int boxX, int boxY, int box) {
        return ArmatureTheme.drawIcon(graphics, stack, boxX, boxY, box);
    }

    private void drawNode(GuiGraphics graphics, ClientQuestCache.Entry entry, boolean hovered) {
        QuestState state = ClientQuestCache.stateOf(entry.id());
        int size = nodeSize(entry);
        int x = nodeScreenX(entry);
        int y = nodeScreenY(entry);

        int edge = switch (state) {
            case COMPLETED -> ArmatureTheme.COMPLETE;
            case STARTED -> ArmatureTheme.IN_PROGRESS;
            case UNLOCKED -> ArmatureTheme.AVAILABLE;
            case LOCKED -> ArmatureTheme.NODE_EDGE_BLOCKED;
        };

        // The node, in its own shape. A shape is a row-to-span lookup and nothing else, so the fill,
        // the border and the hit test all come from one place -- which is why a click lands on exactly
        // the pixels that were drawn and not on a bounding box around them.
        //
        // This draws a square for ROUNDED and a circle, hexagon or book for the others. Before, every
        // node was a square whatever the file said, because the shape never crossed the wire.
        ArmatureTheme.shapePanel(graphics, x, y, size, ArmatureTheme.NODE_FILL, edge,
                entry.shape()::span);

        boolean isSelected = entry.id().equals(selectedQuest);
        if (hovered || isSelected) {
            // The ring is a rectangle around whatever shape is inside it. Deliberately: a ring that
            // followed the outline would sit one pixel from the border and read as a thicker border,
            // where a rectangle reads as a selection box -- and it is the same for all four shapes,
            // which is what makes "this node is selected" legible at a glance.
            ArmatureTheme.outline(graphics, x - 1, y - 1, size + 2, size + 2,
                    isSelected ? ArmatureTheme.SELECTED_RING : ArmatureTheme.HOVER_RING);
        }

        // The icon fills the node, less the inset the shape needs. NOT a constant: the corner of a
        // square is outside a circle of the same size, so one inset either spills the icon outside the
        // outline on a circle or wastes a fifth of the area on a rounded rectangle. At 48 pixels the
        // four shapes want 4, 7, 6 and 5 -- and the circle's 7 is the inscribed square, size/sqrt(2).
        //
        // Derived from the same span table, so the icon can never be drawn outside the shape that
        // contains it -- which is what a hardcoded 3 did as soon as a node was drawn as a circle.
        int itemBox = size - entry.shape().iconInset(size) * 2;
        if (itemBox >= MIN_ITEM_BOX) {
            if (drawIcon(graphics, entry.icon(), x + NODE_INSET, y + NODE_INSET, itemBox)) {
                // The state, as a wash over the icon. It used to be a chip with a ✖ in the node's
                // bottom-right corner, and at node scale that chip was a black square pasted over the
                // artwork -- the worst thing in the screenshot. Dimming what is already there says "not
                // yet" without hiding what the quest is, which is the only reason the icon is here.
                //
                // Drawn after the item, which is safe: every fill in GuiGraphics ends by flushing the
                // buffer (fill -> flushIfUnmanaged -> flush -> bufferSource.endBatch), so the item is
                // submitted first and the wash quad lands on top of it.
                int wash = switch (state) {
                    case LOCKED -> ArmatureTheme.NODE_DIM;
                    case COMPLETED -> ArmatureTheme.NODE_DONE_WASH;
                    case STARTED, UNLOCKED -> 0;
                };
                if (wash != 0) {
                    graphics.fill(x + NODE_INSET, y + NODE_INSET, x + size - NODE_INSET,
                            y + size - NODE_INSET, wash);
                }
            }
            else {
                // No icon, or one the client cannot resolve. A solid square in the state colour still
                // reads as a node in a graph, where an empty one reads as a bug.
                graphics.fill(x + NODE_INSET, y + NODE_INSET, x + size - NODE_INSET, y + size - NODE_INSET,
                        edge);
            }
        }
        else {
            graphics.fill(x + size / 3, y + size / 3, x + size - size / 3, y + size - size / 3,
                    (edge & 0x00FFFFFF) | 0xB0000000);
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
    private void drawLabels(GuiGraphics graphics, List<ClientQuestCache.Entry> quests) {
        int room = labelRoom(quests);
        if (room < MIN_LABEL_WIDTH) {
            // Not enough room for a readable label anywhere in this chapter, so none are drawn and the
            // hover caption carries the name. Drawing them anyway is what "Punch a SomewherStone To…"
            // was: three titles interleaved, which reads as a corrupt string rather than as crowding.
            return;
        }

        for (ClientQuestCache.Entry entry : quests) {
            int size = nodeSize(entry);
            int x = nodeScreenX(entry);
            int y = nodeScreenY(entry);

            String shown = trimToWidth(font, entry.title(), room);
            int width = font.width(shown);
            // Clamped inward so a label on the edge node is not half off the canvas, but never so far
            // that it slides away from the node it belongs to.
            int textX = Mth.clamp(x + size / 2 - width / 2, canvasLeft() + 2, canvasRight() - width - 2);
            int textY = y + size + LABEL_GAP;

            if (textY + 9 > canvasBottom() || overlapsAnotherNode(quests, entry, textX, textY, width)) {
                // A label drawn over the node below it, or out of the canvas, is worse than no label.
                continue;
            }

            QuestState state = ClientQuestCache.stateOf(entry.id());
            int textColour = switch (state) {
                case LOCKED -> ArmatureTheme.BLOCKED;
                case COMPLETED -> ArmatureTheme.COMPLETE;
                default -> entry.id().equals(selectedQuest) ? ArmatureTheme.TITLE : ArmatureTheme.BODY;
            };

            // A backdrop, so a label sitting over a connector line is still readable. Opaque rather
            // than shadowed: a shadow does not help against a line of similar brightness.
            graphics.fill(textX - 2, textY - 1, textX + width + 2, textY + 9, ArmatureTheme.LABEL_BACKDROP);
            graphics.drawString(font, shown, textX, textY, textColour, false);
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

    /**
     * Truncates to a pixel width, with an ellipsis only when something was actually removed.
     *
     * <p>Uses {@code Font.plainSubstrByWidth}, which measures in the font's own metric. The version
     * this replaces divided the node size by the zoom and used the result as a <b>character</b> count,
     * which has nothing to do with how wide the text is: it is not that the answer was imprecise, it is
     * that the quantity was the wrong kind of thing.
     */
    private static String trimToWidth(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return maxWidth <= 0 ? "" : text;
        }
        String ellipsis = "\u2026";
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }

    /**
     * A connector from one node to another.
     *
     * <p>A step function rather than a diagonal. {@code GuiGraphics} has no line drawing, so a diagonal
     * has to be approximated by many single-pixel fills — and at node scale a staircase reads as a
     * mistake rather than as a line. Axis-aligned fills look deliberate and cost three calls.
     */
    private void drawConnector(GuiGraphics graphics, ClientQuestCache.Entry from,
                               ClientQuestCache.Entry to, int colour) {
        int ax = nodeScreenX(from) + nodeSize(from) / 2;
        int ay = nodeScreenY(from) + nodeSize(from) / 2;
        int bx = nodeScreenX(to) + nodeSize(to) / 2;
        int by = nodeScreenY(to) + nodeSize(to) / 2;

        if (ax == bx) {
            graphics.fill(ax, Math.min(ay, by), ax + 1, Math.max(ay, by), colour);
            return;
        }
        if (ay == by) {
            graphics.fill(Math.min(ax, bx), ay, Math.max(ax, bx), ay + 1, colour);
            return;
        }

        // Vertical out of the source, across, then vertical into the target. Vertical-first because a
        // quest chain runs left to right: a short vertical stub reads as a branch, where a long
        // horizontal run would pass through a neighbouring node's space.
        int midY = ay + (by - ay) / 2;
        graphics.fill(ax, Math.min(ay, midY), ax + 1, Math.max(ay, midY), colour);
        graphics.fill(Math.min(ax, bx), midY, Math.max(ax, bx), midY + 1, colour);
        graphics.fill(bx, Math.min(midY, by), bx + 1, Math.max(midY, by), colour);
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
    // The bottom strip
    // ------------------------------------------------------------------

    private void drawStrip(GuiGraphics graphics, ClientQuestCache.Entry entry) {
        int left = canvasLeft();
        int top = canvasBottom();
        int right = canvasRight();

        graphics.fill(left, top, right, top + STRIP_HEIGHT, ArmatureTheme.RAISED);
        graphics.fill(left, top, right, top + 1, ArmatureTheme.PANEL_EDGE);

        int textX = left + 10;
        int limit = stripTextLimit();

        if (entry == null) {
            graphics.drawString(font, "Click a quest to see what it wants", textX, top + 8,
                    ArmatureTheme.FAINT, false);
            graphics.drawString(font, "Scroll to zoom, drag to pan", textX, top + 22,
                    ArmatureTheme.FAINT, false);
            return;
        }

        QuestState state = ClientQuestCache.stateOf(entry.id());
        String stateText = stateLabel(state);
        // The title is truncated to leave room for the state, rather than the state being pushed off
        // the end — which is what happens if the title is drawn first at full length. By width, in one
        // step: the loop this replaces trimmed a character at a time and re-measured, which is the same
        // answer for a great deal more work, and it started from a character count that was a guess.
        String titleText = trimToWidth(font, entry.title(),
                Math.max(0, limit - font.width(stateText) - 8));

        graphics.drawString(font, titleText, textX, top + 7, ArmatureTheme.TITLE, false);
        graphics.drawString(font, stateText, textX + font.width(titleText) + 8, top + 7,
                stateColour(state), false);

        int y = top + 21;
        long done = 0;
        for (int i = 0; i < entry.tasks().size(); i++) {
            if (ClientQuestCache.taskProgressOf(entry.id(), i) >= entry.tasks().get(i).count()) {
                done++;
            }
        }
        String summary = entry.tasks().size() + " task" + (entry.tasks().size() == 1 ? "" : "s")
                + "  \u00b7  " + done + " done"
                + (entry.rewards().isEmpty() ? "" : "  \u00b7  " + entry.rewards().size() + " reward"
                        + (entry.rewards().size() == 1 ? "" : "s"))
                + (entry.dependencies().isEmpty() ? "" : "  \u00b7  needs " + entry.dependencies().size());
        // Truncated to the strip's own limit, by width. The previous version passed 999 as the limit,
        // which is not a truncation at all -- the summary simply ran under the Open button.
        graphics.drawString(font, trimToWidth(font, summary, limit), textX, y, ArmatureTheme.FAINT, false);
    }

    // ------------------------------------------------------------------
    // The overlay
    // ------------------------------------------------------------------

    /**
     * The full-screen quest view.
     *
     * <p>Laid out as a fixed header, a scrolling body and a fixed footer, because the body can be
     * arbitrarily long and the controls that close it must not scroll away. The body's clipping rect is
     * what makes that work: {@code enableScissor} cuts a long description off at the footer rather than
     * letting it draw over the controls.
     */
    private void drawOverlay(GuiGraphics graphics) {
        ClientQuestCache.Entry entry = entryFor(overlayQuest);
        if (entry == null) {
            return;
        }

        int left = overlayLeft();
        int top = overlayTop();
        int w = overlayWidth();
        int h = overlayHeight();

        ArmatureTheme.panel(graphics, left, top, w, h, ArmatureTheme.PANEL, ArmatureTheme.PANEL_EDGE);
        graphics.fill(left + 1, top + 1, left + w - 1, top + 46, ArmatureTheme.RAISED);
        graphics.fill(left + 1, top + 46, left + w - 1, top + 47, ArmatureTheme.PANEL_EDGE);
        graphics.fill(left + 1, top + h - 38, left + w - 1, top + h - 37, ArmatureTheme.PANEL_EDGE);

        // --- header ---

        QuestState state = ClientQuestCache.stateOf(entry.id());
        // The header icon is drawn to its own box, and the text starts after that box, so the two are
        // the same layout decision. The title used to start at a hardcoded left+38 with a 16px icon at
        // left+14, which is 8px of gap -- close enough to look intentional and not derived from
        // anything, so moving the icon would have moved the text by accident.
        int iconBox = HEADER_ICON;
        int iconX = left + 14;
        int iconY = top + (46 - iconBox) / 2;
        drawIcon(graphics, entry.icon(), iconX, iconY, iconBox);

        int textX = iconX + iconBox + 6;
        graphics.drawString(font, entry.title(), textX, top + 12, ArmatureTheme.TITLE, false);
        graphics.drawString(font, stateLabel(state), textX + font.width(entry.title()) + 10, top + 12,
                stateColour(state), false);

        String where = entry.chapterTitle() + (entry.subtitle().isEmpty() ? "" : "  \u00b7  " + entry.subtitle());
        graphics.drawString(font, where, textX, top + 26, ArmatureTheme.FAINT, false);

        // --- body, scrolled and clipped ---

        int bodyLeft = left + 18;
        int bodyRight = left + w - 18;
        int bodyTop = top + 54;
        int bodyBottom = top + h - 46;
        int bodyWidth = bodyRight - bodyLeft;

        int contentHeight = measureOverlay(entry, bodyWidth);
        int maxScroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
        overlayScroll = Mth.clamp(overlayScroll, 0, maxScroll);

        graphics.enableScissor(bodyLeft, bodyTop, bodyRight, bodyBottom);
        int y = bodyTop - overlayScroll;

        y = drawParagraphs(graphics, entry.description(), bodyLeft, y, bodyWidth, ArmatureTheme.BODY);
        if (entry.description().isEmpty()) {
            graphics.drawString(font, "No description.", bodyLeft, y, ArmatureTheme.FAINT, false);
            y += 12;
        }

        y += 8;
        y = heading(graphics, "TASKS", bodyLeft, y);
        if (entry.tasks().isEmpty()) {
            graphics.drawString(font, "Nothing required", bodyLeft + 8, y, ArmatureTheme.FAINT, false);
            // ROW_ADVANCE, not a number: measureOverlay reserves Math.max(1, size) * ROW_ADVANCE for an
            // empty list too, and the scrollbar is only right if the two agree exactly.
            y += ROW_ADVANCE;
        }
        for (int i = 0; i < entry.tasks().size(); i++) {
            y = drawTaskRow(graphics, entry, i, bodyLeft + 8, y, bodyWidth - 16);
        }

        y += 10;
        y = heading(graphics, "REWARDS", bodyLeft, y);
        if (entry.rewards().isEmpty()) {
            graphics.drawString(font, "Nothing", bodyLeft + 8, y, ArmatureTheme.FAINT, false);
            y += ROW_ADVANCE;
        }
        for (ClientQuestCache.RewardEntry reward : entry.rewards()) {
            y = drawRewardRow(graphics, reward, bodyLeft + 8, y);
        }

        if (!entry.dependencies().isEmpty()) {
            y += 10;
            y = heading(graphics, "REQUIRES", bodyLeft, y);
            for (String dependency : entry.dependencies()) {
                ClientQuestCache.Entry other = entryFor(dependency);
                QuestState otherState = ClientQuestCache.stateOf(dependency);
                boolean met = otherState == QuestState.COMPLETED;
                String label = other != null ? other.title() : dependency;
                graphics.drawString(font, (met ? "\u2714" : "\u2716") + "  " + label, bodyLeft + 8, y,
                        met ? ArmatureTheme.COMPLETE : ArmatureTheme.BLOCKED, false);
                y += DEP_ADVANCE;
            }
        }

        graphics.disableScissor();

        // --- a scrollbar, only when there is something to scroll ---

        if (maxScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int thumbHeight = Math.max(20, trackHeight * trackHeight / contentHeight);
            int thumbTop = bodyTop + (trackHeight - thumbHeight) * overlayScroll / maxScroll;
            graphics.fill(bodyRight + 4, bodyTop, bodyRight + 7, bodyBottom, ArmatureTheme.RECESSED);
            graphics.fill(bodyRight + 4, thumbTop, bodyRight + 7, thumbTop + thumbHeight,
                    ArmatureTheme.CONTROL_EDGE);
        }
    }

    private int heading(GuiGraphics graphics, String text, int x, int y) {
        graphics.drawString(font, text, x, y, ArmatureTheme.HEADING, false);
        graphics.fill(x, y + 10, x + font.width(text), y + 11, ArmatureTheme.PANEL_EDGE);
        return y + 16;
    }

    /**
     * How tall the overlay's body will be, so the scrollbar knows its range before anything is drawn.
     *
     * <p>Every number here is the number the drawing actually advances by, and that is the only reason
     * this method can be trusted: a scrollbar computed from a second, independent estimate drifts, and
     * the symptom is a thumb that stops short of the end or runs past it. In particular
     * {@link #ROW_ADVANCE} is shared with {@link #drawTaskRow} and {@link #drawRewardRow} rather than
     * written out again — the previous version had a 13px pitch here against a 16px icon, so rows
     * overlapped each other and the measurement was wrong in the same direction.
     */
    private int measureOverlay(ClientQuestCache.Entry entry, int textWidth) {
        int height = 0;
        if (entry.description().isEmpty()) {
            height += 12;
        }
        for (String paragraph : entry.description()) {
            height += wrap(paragraph, textWidth).size() * 10 + 5;
        }
        height += 8 + 16;                                          // TASKS heading
        height += Math.max(1, entry.tasks().size()) * ROW_ADVANCE;
        height += 10 + 16;                                         // REWARDS heading
        height += Math.max(1, entry.rewards().size()) * ROW_ADVANCE;
        if (!entry.dependencies().isEmpty()) {
            height += 10 + 16 + entry.dependencies().size() * DEP_ADVANCE;
        }
        return height + 16;
    }

    private int drawTaskRow(GuiGraphics graphics, ClientQuestCache.Entry entry, int index, int x, int y,
                            int availableWidth) {
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        int progress = ClientQuestCache.taskProgressOf(entry.id(), index);
        boolean satisfied = progress >= task.count();

        // The text sits on the centre line of the icon's box, and the box is ROW_ICON square. Before,
        // the text was on the row's top edge while the icon was drawn 4px above it at 16px tall, so
        // neither lined up with the other and the icon bled into the row above.
        int textY = y + (ROW_ICON - 8) / 2;

        int textX = x;
        ItemStack toDraw = task.hasItem() ? task.item() : task.icon();
        if (drawIcon(graphics, toDraw, x, y, ROW_ICON)) {
            // Only indent the text when something was actually drawn, so a task whose item the client
            // cannot resolve is not left with a gap where an icon should be.
            textX = x + ROW_ICON + 5;
        }

        Component text = task.text();
        int colour = satisfied ? ArmatureTheme.COMPLETE
                : ClientQuestCache.stateOf(entry.id()) == QuestState.LOCKED ? ArmatureTheme.BLOCKED
                : ArmatureTheme.BODY;
        graphics.drawString(font, text, textX, textY, colour, false);

        int after = textX + font.width(text) + 8;

        // The progress, and a bar for it. The bar is what makes "5 / 8" readable at a glance rather
        // than something you have to stop and parse.
        if (task.count() > 1) {
            String count = Math.min(progress, task.count()) + " / " + task.count();
            graphics.drawString(font, count, after, textY,
                    satisfied ? ArmatureTheme.COMPLETE : ArmatureTheme.FAINT, false);
            after += font.width(count) + 8;

            int barWidth = Mth.clamp(availableWidth - (after - x) - 70, 24, 120);
            int filled = Math.round(barWidth * Math.min(1F, progress / (float) task.count()));
            int barY = textY + 1;
            graphics.fill(after, barY, after + barWidth, barY + 6, ArmatureTheme.RECESSED);
            ArmatureTheme.outline(graphics, after, barY, barWidth, 6, ArmatureTheme.PANEL_EDGE);
            if (filled > 0) {
                graphics.fill(after + 1, barY + 1, after + Math.max(2, filled), barY + 5,
                        satisfied ? ArmatureTheme.COMPLETE : ArmatureTheme.AVAILABLE);
            }
        }

        if (task.optional()) {
            String tag = "optional";
            graphics.drawString(font, tag, x + availableWidth - font.width(tag), textY,
                    ArmatureTheme.FAINT, false);
        }
        if (task.manual()) {
            String tag = "hand in";
            int tagX = x + availableWidth - font.width(tag) - (task.optional() ? font.width("optional") + 6 : 0);
            graphics.drawString(font, tag, tagX, textY, ArmatureTheme.AVAILABLE, false);
        }

        return y + ROW_ADVANCE;
    }

    private int drawRewardRow(GuiGraphics graphics, ClientQuestCache.RewardEntry reward, int x, int y) {
        int textY = y + (ROW_ICON - 8) / 2;

        int textX = x;
        ItemStack toDraw = reward.hasItem() ? reward.item() : reward.icon();
        if (drawIcon(graphics, toDraw, x, y, ROW_ICON)) {
            textX = x + ROW_ICON + 5;
        }

        Component text = reward.text();
        graphics.drawString(font, text, textX, textY, ArmatureTheme.BODY, false);
        if (reward.hasItem() && reward.count() > 1) {
            graphics.drawString(font, "x" + reward.count(), textX + font.width(text) + 5, textY,
                    ArmatureTheme.FAINT, false);
        }
        return y + ROW_ADVANCE;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Widgets first. A control that was clicked must keep the event.
        if (super.mouseClicked(mouseX, mouseY, button)) {
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

        // Left or middle on the canvas: begin a pan. Whether it becomes a pan or a click is decided by
        // whether the pointer moves, which is why nothing is selected yet — and why a pan that happens
        // to start on a node does not change the selection.
        if ((button == 0 || button == 2) && inCanvas(mouseX, mouseY)) {
            dragging = true;
            pressMoved = false;
            pressX = mouseX;
            pressY = mouseY;
            panAtPressX = panX;
            panAtPressY = panY;

            String chapter = effectiveChapter();
            ClientQuestCache.Entry under = chapter == null ? null
                    : nodeAt(mouseX, mouseY, questsIn(chapter));
            pressedNode = under == null ? null : under.id();

            // Middle-drag is a pan and never a click, so forget the node immediately. Otherwise a
            // middle-click that happens not to move would select whatever it landed on.
            if (button == 2) {
                pressedNode = null;
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            if (Math.abs(mouseX - pressX) > DRAG_THRESHOLD || Math.abs(mouseY - pressY) > DRAG_THRESHOLD) {
                pressMoved = true;
            }
            panX = panAtPressX + (int) (mouseX - pressX);
            panY = panAtPressY + (int) (mouseY - pressY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;

            // A press that never moved is a click. Selecting on release rather than on press is what
            // makes "hold to pan" and "click to select" one gesture.
            if (!pressMoved && pressedNode != null && button == 0) {
                selectedQuest = pressedNode.equals(selectedQuest) ? null : pressedNode;
                rebuildWidgets();
            }
            pressedNode = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overlay == Overlay.QUEST) {
            // Inside the overlay the wheel scrolls the text, which is what a long description wants.
            // Zooming here would be wrong: there is no canvas to zoom.
            overlayScroll = Math.max(0, overlayScroll - (int) (scrollY * 30));
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
        if (overlay == Overlay.QUEST && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
            case COMPLETED -> ArmatureTheme.COMPLETE;
            case STARTED -> ArmatureTheme.IN_PROGRESS;
            case UNLOCKED -> ArmatureTheme.AVAILABLE;
            case LOCKED -> ArmatureTheme.BLOCKED;
        };
    }

    private List<FormattedCharSequence> wrap(String text, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        if (width <= 8) {
            out.add(Component.literal(text).getVisualOrderText());
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > width && !line.isEmpty()) {
                out.add(Component.literal(line.toString()).getVisualOrderText());
                line = new StringBuilder(word);
            }
            else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            out.add(Component.literal(line.toString()).getVisualOrderText());
        }
        return out;
    }

    /** Draws wrapped paragraphs, returning the y after the last line. */
    private int drawParagraphs(GuiGraphics graphics, List<String> paragraphs, int x, int y, int width,
                               int colour) {
        for (String paragraph : paragraphs) {
            for (FormattedCharSequence line : wrap(paragraph, width)) {
                graphics.drawString(font, line, x, y, colour, false);
                y += 10;
            }
            y += 5;
        }
        return y;
    }

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
        zoom = 1.0F;
        panX = 0;
        panY = 0;
        pannedChapter = null;
        centred = false;
    }
}
