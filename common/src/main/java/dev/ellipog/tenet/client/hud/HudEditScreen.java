package dev.ellipog.tenet.client.hud;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.armature.client.ArmatureSwitch;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The HUD editor: the list of movable things, and the things themselves, in one screen over the live world.
 *
 * <h2>Why it draws no background</h2>
 *
 * <p>Because the point is to move something you can see. A screen draws over the world <i>and</i> over the
 * HUD -- {@code GameRenderer} renders the HUD on every frame in a world, screens or not -- so every
 * {@code Screen} that fills its background hides exactly what this one is for. {@link ArmatureScreen}
 * already suppresses that (no blur, no panorama, no menu texture) for its own reasons, and this is the first
 * screen that needs the world behind it rather than merely tolerating it.
 *
 * <h2>Two ways in, one place things are stored</h2>
 *
 * <p>Opened by its own key, or from the book's settings card -- with the book waiting to come back, because
 * a player who went through the book to get here did not ask to be dropped into the world when they were
 * finished. Every change writes straight to {@link HudSettings}, there is no Apply, and the
 * inventory-screen button reads the same numbers, so the editor and the thing it edits cannot disagree
 * about where something is.
 *
 * <h2>Nothing stands in for anything</h2>
 *
 * <p>The elements are drawn where they really are, at their real size, in the window's own pixels -- the same
 * space the HUD is measured in and the same space a stored position means. An earlier version drew a ghost
 * of the inventory panel so that a button anchored to that panel's corner had a corner to be anchored to;
 * the ghost's corner was not the real panel's corner, so a number written from one was read from the other
 * and the control moved between the editor and the game. There is one coordinate space now, and no stand-in.
 *
 * <h2>Moving things: drag, and the arrows for the last pixel</h2>
 *
 * <p>Grab the element and drag it; the drop writes where the widget's corner is, clamped onto the window.
 * A press on an element selects it, and the arrows then nudge it a pixel -- ten with Shift -- through the
 * same clamp, for placing something exactly where a drag overshoots by one. There used to be a {@code Move}
 * button that carried the element on the pointer, and it went away because everything here is already
 * grabbable: a second way to move is a second thing to learn, and the arrows cover the precision case the
 * button was really for.
 *
 * <h2>What is drawn over what, and which of the two answers wins</h2>
 *
 * <p>The rows' panel and labels are the bottom layer, drawn by this screen; the elements are widgets on top
 * of them; and the rows' controls -- a switch, <i>Reset</i>, <i>Done</i> -- are widgets added
 * <b>before</b> the elements. Both halves of that are deliberate, and they are the opposite of each other,
 * because a press is offered to children in the order they were added while drawing goes the other way:
 *
 * <ul>
 *   <li>the controls are asked first, so an element left lying across one cannot make it unreachable. An
 *       unreachable control is the one arrangement that traps a player in this screen;</li>
 *   <li>and so an element left lying across a control is drawn <i>over</i> it. It looks covered and still
 *       answers, which is the better of the two faults -- dragging it by any visible part moves it, and
 *       putting it down anywhere the rows are not is a release away.</li>
 * </ul>
 */
public final class HudEditScreen extends ArmatureScreen {

    /** Whether the screen that opened this one wants to be reopened when it closes. */
    private static boolean pendingReturn = false;

    private final Map<HudElement, HudElementPreview> previews = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureSwitch> switches = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> resets = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureSlider> dims = new EnumMap<>(HudElement.class);
    private ArmatureButton done;

    /** The element last pressed, so the thing touched and the row that names it are visibly the same one. */
    private HudElement selected;

    private final boolean returnToBook;

    public HudEditScreen() {
        super(Component.translatable("tenet.screen.hud_edit.title"));
        // Taken here rather than read later: the flag belongs to one visit to this screen, so a screen
        // somebody else opened in between does not send the next visitor back to the book.
        this.returnToBook = pendingReturn;
        pendingReturn = false;
    }

    /** Opens the editor, and reopens the book when it closes. */
    public static void openFromBook() {
        pendingReturn = true;
        ArmatureClient.openScreen(Tenet.HUD_EDIT_SCREEN);
    }

    /**
     * The controls, built once.
     *
     * <p><b>The rows are added before the elements</b>, and the order is a decision rather than a habit: a
     * press is offered to children in the order they were added, so the rows' controls keep their presses
     * even with an element lying across them. See the class note for what that costs in drawing.
     */
    @Override
    protected void init() {
        for (HudElement element : HudElement.values()) {
            ArmatureSwitch toggle = new ArmatureSwitch(0, 0, HudSettings.on(element));
            toggle.onToggle(() -> HudSettings.setOn(element, toggle.selected()));
            addRenderableWidget(toggle);
            switches.put(element, toggle);

            ArmatureButton reset = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                    Component.translatable("tenet.hud.reset"), () -> resetElement(element));
            addRenderableWidget(reset);
            resets.put(element, reset);

            // A drawn element's background strength, on its own line under its controls: a slider shares
            // nothing with the switch beside it the way Move and Reset share a line, and only a drawn
            // element has a background to strengthen. A control draws itself, so it gets no slider.
            if (element.kind() == HudElement.Kind.HUD) {
                ArmatureSlider dim = new ArmatureSlider(0, 0, HudLayout.CHROME_WIDTH, 0.0, 1.0, 0.05,
                        HudSettings.dim(element));
                dim.onChange(() -> HudSettings.setDim(element, dim.value()));
                addRenderableWidget(dim);
                dims.put(element, dim);
            }
        }

        done = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                Component.translatable("tenet.screen.hud_edit.done"), this::onClose);
        addRenderableWidget(done);

        for (HudElement element : HudElement.values()) {
            HudElementPreview preview = new HudElementPreview(element, this);
            previews.put(element, preview);
            addRenderableWidget(preview);
        }
    }

    @Override
    protected void renderContent(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        // Kept for the widget pass, which runs after this one: a preview of a *drawn* element draws itself
        // through `HudOverlay`, and that needs the frame's measure. Read from the renderer in force rather
        // than remembered, for the same reason every position here is asked for rather than stored.
        frameMeasure = Measure.of(renderer::textWidth, renderer.lineHeight());
        long now = Util.getMillis();

        for (HudElement element : HudElement.values()) {
            follow(element, mouseX, mouseY, frameMeasure, now);
        }
        drawChrome(renderer, chrome(), frameMeasure);
    }

    /** The measure this frame is drawing with; see {@link #renderContent}. */
    Measure frameMeasure() {
        return frameMeasure;
    }

    private Measure frameMeasure;

    /**
     * Where each element draws: where it is stored.
     *
     * <p>Every frame, against the window as it is <i>now</i> -- which is what makes this work at any size.
     * The dimensions are asked for rather than remembered, so a window that has just been resized has its
     * elements pulled inside it on the next frame instead of leaving one off the edge until something else
     * happens to move it.
     *
     * <p><b>The size is refreshed here too, and only for a drawn element.</b> A later round made two of the
     * four as big as what they hold, so a preview left at the size its table entry ships would be a box the
     * game never draws -- grabbed in one place and drawn in another, which is the class of fault this
     * editor was rebuilt to remove. {@code HudOverlay} is the only thing that measures them, so it is asked,
     * with the editor's own face: an element with nothing pinned still gets the sample's box, which is what
     * keeps it reachable. A control's size is a constant and is left as it is.
     *
     * <p>Skipped for an element the widget pass is currently carrying: that one is where the pointer put it,
     * and putting it back to the stored position every frame would fight the drag that has not finished.
     */
    private void follow(HudElement element, int mouseX, int mouseY, Measure measure, long now) {
        HudElementPreview preview = previews.get(element);
        if (preview == null || preview.isHeld()) {
            return;
        }
        if (element.kind() == HudElement.Kind.HUD) {
            HudOverlay.Size size = HudOverlay.size(element, measure, HudOverlay.Face.EDITOR, now);
            preview.resize(size.width(), size.height());
        }
        BookGeometry.Rect box = HudLayout.boxAt(element, width, height,
                HudSettings.x(element), HudSettings.y(element), preview.getWidth(), preview.getHeight());
        preview.at(box.x(), box.y());
    }

    private void drawChrome(GuiRenderer renderer, BookGeometry.Rect chrome, Measure measure) {
        ArmatureTheme.panel(renderer, chrome.x(), chrome.y(), chrome.width(), chrome.height(),
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());

        int index = 0;
        List<HudElement> order = List.of(HudElement.values());
        for (HudElement element : order) {
            BookGeometry.Rect label = HudLayout.label(index, chrome, order);
            // The element that was last pressed reads bright, so a row and the thing it names are visibly
            // the same thing -- the rows are words and the elements are icons, and that is the join.
            int ink = element == selected ? ArmatureTheme.title() : ArmatureTheme.body();
            renderer.text(Measure.truncate(Component.translatable(element.labelKey()).getString(),
                            label.width(), measure),
                    label.x(), label.y() + 2, ink);

            place(switches.get(element), HudLayout.toggle(index, chrome, order));
            place(resets.get(element), HudLayout.reset(index, chrome, order));
            ArmatureSlider dim = dims.get(element);
            if (dim != null) {
                placeSlider(dim, HudLayout.slider(index, chrome, order));
            }
            index++;
        }

        BookGeometry.Rect foot = HudLayout.done(chrome);
        place(done, foot);
        renderer.text(Measure.truncate(getTitle().getString(),
                        Math.max(0, foot.x() - chrome.x() - HudLayout.INSET * 2), measure),
                chrome.x() + HudLayout.INSET, foot.y() + 5, ArmatureTheme.body());
    }

    private static void place(AbstractWidget widget, BookGeometry.Rect box) {
        if (widget != null) {
            widget.setX(box.x());
            widget.setY(box.y());
        }
    }

    /**
     * Places a background slider, with its width as well as its corner.
     *
     * <p>The one control on this screen sized per frame rather than built to size: its width <i>is</i> the
     * row's content, so a slider built to the chrome's full width would overhang the panel on a narrow
     * window, and one built to the minimum would leave dead track on a wide one. Buttons keep fixed sizes
     * because a fixed size is what they are; a slider's width is what it is for.
     */
    private static void placeSlider(ArmatureSlider slider, BookGeometry.Rect box) {
        if (slider != null) {
            slider.setX(box.x());
            slider.setY(box.y());
            slider.setWidth(Math.max(ArmatureSlider.MIN_TRACK, box.width()));
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    // There is no `mouseClicked` override here, and that is the whole point of dragging an element
    // directly: the widget path carries press, drag and release, so there is no second gesture to keep in
    // step with the first.

    /**
     * Arrows nudge the selected element a pixel -- ten with Shift -- through the same clamp-and-convert
     * as a drop, and write straight through it.
     *
     * <p>Not into a focused slider: the slider spends arrows on its own value, and stealing them would make
     * a strength unreachable from the keyboard that reaches everything else.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selected != null && !(getFocused() instanceof ArmatureSlider)
                && (keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN
                        || keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT)) {
            nudgeSelected(keyCode == InputConstants.KEY_LEFT ? -1 : keyCode == InputConstants.KEY_RIGHT ? 1 : 0,
                    keyCode == InputConstants.KEY_UP ? -1 : keyCode == InputConstants.KEY_DOWN ? 1 : 0,
                    Screen.hasShiftDown() ? 10 : 1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Moves the selected element by exact pixels, and writes where it landed. */
    private void nudgeSelected(int dx, int dy, int step) {
        HudElementPreview preview = previews.get(selected);
        if (preview == null) {
            return;
        }
        int left = HudLayout.placed(HudSettings.x(selected) + dx * step, width - preview.getWidth());
        int top = HudLayout.placed(HudLayout.placedTop(selected, height, HudSettings.y(selected),
                preview.getHeight()) + dy * step, height - preview.getHeight());
        HudSettings.setPosition(selected, left, HudLayout.storedY(selected, height, top, preview.getHeight()));
    }

    /**
     * Replaces this screen, which is how it closes.
     *
     * <p>Not {@code super.onClose()} on the way back to the book: setting a screen <i>is</i> closing this
     * one -- the game calls {@code removed()} on the way out -- and doing both would close a screen twice.
     */
    @Override
    public void onClose() {
        if (returnToBook) {
            ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
            return;
        }
        super.onClose();
    }

    // ------------------------------------------------------------------
    // What the rows and the elements do
    // ------------------------------------------------------------------

    private void resetElement(HudElement element) {
        HudSettings.resetElement(element);
        ArmatureSwitch toggle = switches.get(element);
        if (toggle != null) {
            // Reset puts the switch back too: an element switched off and then reset is an element the
            // player asked to have back.
            toggle.setSelected(HudSettings.on(element));
        }
        ArmatureSlider dim = dims.get(element);
        if (dim != null) {
            // And the slider, for the same reason: a dim left where it was after everything else went
            // home would be a reset that did not.
            dim.setValue(HudSettings.dim(element));
        }
    }

    /**
     * Writes where an element has been put.
     *
     * <p>The widget's corner, clamped onto the window as it is now -- so a drop near an edge stores where
     * the element visibly is rather than where the cursor went, and a window that changed size since the
     * drag began cannot have a position written that this frame would refuse to draw. Converted to what
     * the file holds exactly once, through the element's anchor: routing the corner through
     * {@code boxAt} instead would read it as a centre-offset a second time, and the box would jump by
     * half its height on release -- which is the fault this shape exists to prevent.
     */
    void drop(HudElement element) {
        HudElementPreview preview = previews.get(element);
        if (preview == null) {
            return;
        }
        int left = HudLayout.placed(preview.getX(), width - preview.getWidth());
        int top = HudLayout.placed(preview.getY(), height - preview.getHeight());
        HudSettings.setPosition(element, left,
                HudLayout.storedY(element, height, top, preview.getHeight()));
    }

    /** A press on an element itself: it becomes the selected one, for the ring and the arrows. */
    void select(HudElement element) {
        selected = element;
    }

    /** Whether this element is the one last touched or being dragged, for the ring it draws. */
    boolean isHighlighted(HudElement element) {
        HudElementPreview preview = previews.get(element);
        return element == selected || (preview != null && preview.isHeld());
    }

    // ------------------------------------------------------------------

    private BookGeometry.Rect chrome() {
        return HudLayout.chrome(width, height, List.of(HudElement.values()));
    }
}
