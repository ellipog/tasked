package dev.ellipog.tenet.client.hud;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureSwitch;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
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
 * <h2>Two ways to move, and why both exist</h2>
 *
 * <p>Grab the element and drag it, or press {@code Move} in its row and it comes to the pointer: it follows
 * the cursor until the next press, which puts it down where it is. The second is not a convenience: an
 * element can be left lying over the editor's own rows, and coming to the pointer is how one is picked up
 * without hunting for the part of it that nothing is covering. Escape before the drop puts it back.
 *
 * <h2>What is drawn over what, and which of the two answers wins</h2>
 *
 * <p>The rows' panel and labels are the bottom layer, drawn by this screen; the elements are widgets on top
 * of them; and the rows' controls -- a switch, <i>Move</i>, <i>Reset</i>, <i>Done</i> -- are widgets added
 * <b>before</b> the elements. Both halves of that are deliberate, and they are the opposite of each other,
 * because a press is offered to children in the order they were added while drawing goes the other way:
 *
 * <ul>
 *   <li>the controls are asked first, so an element left lying across one cannot make it unreachable. An
 *       unreachable control is the one arrangement that traps a player in this screen;</li>
 *   <li>and so an element left lying across a control is drawn <i>over</i> it. It looks covered and still
 *       answers, which is the better of the two faults -- the row's {@code Move} picks the element up, and
 *       putting it down anywhere the rows are not is a press away.</li>
 * </ul>
 */
public final class HudEditScreen extends ArmatureScreen {

    /** Whether the screen that opened this one wants to be reopened when it closes. */
    private static boolean pendingReturn = false;

    private final Map<HudElement, HudElementPreview> previews = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureSwitch> switches = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> moves = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> resets = new EnumMap<>(HudElement.class);
    private ArmatureButton done;

    /** The element a row armed, which follows the pointer until a press puts it down. */
    private HudElement armed;

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

            ArmatureButton move = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                    Component.translatable("tenet.hud.move"), () -> arm(element));
            ArmatureButton reset = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                    Component.translatable("tenet.hud.reset"), () -> resetElement(element));
            addRenderableWidget(move);
            addRenderableWidget(reset);
            moves.put(element, move);
            resets.put(element, reset);
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
        Measure measure = Measure.of(renderer::textWidth, renderer.lineHeight());

        for (HudElement element : HudElement.values()) {
            follow(element, mouseX, mouseY);
        }
        drawChrome(renderer, chrome(), measure);
    }

    /**
     * Where each element draws: where it is stored, or the pointer while a row has armed it.
     *
     * <p>Every frame, against the window as it is <i>now</i> -- which is what makes this work at any size.
     * The dimensions are asked for rather than remembered, so a window that has just been resized has its
     * elements pulled inside it on the next frame instead of leaving one off the edge until something else
     * happens to move it.
     *
     * <p>Skipped for an element the widget pass is currently carrying: that one is where the pointer put it,
     * and putting it back to the stored position every frame would fight the drag that has not finished.
     */
    private void follow(HudElement element, int mouseX, int mouseY) {
        HudElementPreview preview = previews.get(element);
        if (preview == null || preview.isHeld()) {
            return;
        }
        if (element == armed) {
            // Centred under the pointer: a thing being carried is carried by its middle, and an offset grab
            // would drift further from the cursor with every frame.
            preview.at(HudLayout.placed(mouseX - preview.getWidth() / 2, width - preview.getWidth()),
                    HudLayout.placed(mouseY - preview.getHeight() / 2, height - preview.getHeight()));
            return;
        }
        BookGeometry.Rect box = HudLayout.boxAt(element, width, height,
                HudSettings.x(element), HudSettings.y(element));
        preview.at(box.x(), box.y());
    }

    private void drawChrome(GuiRenderer renderer, BookGeometry.Rect chrome, Measure measure) {
        ArmatureTheme.panel(renderer, chrome.x(), chrome.y(), chrome.width(), chrome.height(),
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());

        int index = 0;
        for (HudElement element : HudElement.values()) {
            BookGeometry.Rect label = HudLayout.label(index, chrome);
            // The element that was last pressed reads bright, so a row and the thing it names are visibly
            // the same thing -- the rows are words and the elements are icons, and that is the join.
            int ink = element == selected ? ArmatureTheme.title() : ArmatureTheme.body();
            renderer.text(Measure.truncate(Component.translatable(element.labelKey()).getString(),
                            label.width(), measure),
                    label.x(), label.y() + 2, ink);

            place(switches.get(element), HudLayout.toggle(index, chrome));
            place(moves.get(element), HudLayout.move(index, chrome));
            place(resets.get(element), HudLayout.reset(index, chrome));
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

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    // There is no `mouseClicked` override here, and that is the whole point of arming an element by moving
    // it rather than by a mode: while it is armed it is centred on the pointer, so the press that puts it
    // down lands on the element's own widget and is handled by the widget path -- see `select`. A branch
    // here that dropped it on a press anywhere would be unreachable, and a drop path nothing can reach is
    // worse than no path: it reads as supported.

    /**
     * Escape puts a carried element back rather than closing the screen.
     *
     * <p>It is the gesture's own undo: {@code Move} arms something, and the way out of having armed it is the
     * key that cancels things. A second Escape closes, which is what the key does on every other screen.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE && armed != null) {
            armed = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    /** The element a row armed: it follows the pointer until a press puts it down. */
    private void arm(HudElement element) {
        if (!HudSettings.on(element)) {
            // It is drawn here but not out there, and somebody who has just asked to move it is about to
            // look for it. Switching it on is the honest answer, and the switch beside the row follows.
            HudSettings.setOn(element, true);
            ArmatureSwitch toggle = switches.get(element);
            if (toggle != null) {
                toggle.setSelected(true);
            }
        }
        armed = element;
    }

    private void resetElement(HudElement element) {
        HudSettings.resetElement(element);
        ArmatureSwitch toggle = switches.get(element);
        if (toggle != null) {
            // Reset puts the switch back too: an element switched off and then reset is an element the
            // player asked to have back.
            toggle.setSelected(HudSettings.on(element));
        }
    }

    /**
     * Writes where an element has been put.
     *
     * <p>The position, pulled onto the window as it is now -- so a drop near an edge stores where the element
     * visibly is rather than the coordinate the cursor reached, and a window that changed size since the drag
     * began cannot have a position written that this frame would refuse to draw.
     */
    void drop(HudElement element) {
        HudElementPreview preview = previews.get(element);
        if (preview == null) {
            return;
        }
        BookGeometry.Rect box = HudLayout.boxAt(element, width, height, preview.getX(), preview.getY());
        HudSettings.setPosition(element, box.x(), box.y());
        armed = null;
    }

    /**
     * A press on an element itself.
     *
     * <p>Two cases, and the first is why this is not only about the ring. An armed element follows the
     * pointer, so the press that puts it down lands <b>on the element's own widget</b> rather than on bare
     * canvas -- the release is what gets here, by way of the widget's own press-and-release. Persisting from
     * here is what makes {@code Move} followed by a click do what it looks like it does; without it the
     * element would be selected, un-armed, and snapped back to where it was, which reads as the button
     * having done nothing at all.
     */
    void select(HudElement element) {
        selected = element;
        if (armed == element) {
            drop(element);
            return;
        }
        // Grabbing one thing puts any other one back where it was: one element is ever in hand, and an
        // un-dropped one has no position to keep. Escape does the same thing on purpose.
        armed = null;
    }

    /** Whether this element is the one being moved or the one last touched, for the ring it draws. */
    boolean isHighlighted(HudElement element) {
        HudElementPreview preview = previews.get(element);
        return element == armed || element == selected || (preview != null && preview.isHeld());
    }

    // ------------------------------------------------------------------

    private BookGeometry.Rect chrome() {
        return HudLayout.chrome(width, height, HudElement.values().length);
    }
}
