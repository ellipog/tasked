package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.Appearance;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.Theme;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.Themes;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.ScrollView;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.client.DevMode;

import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The developer screen: the mode's own switch, the appearance controls that were taken off the quest
 * book, and the theme editor.
 *
 * <h2>What lives here, and why it is not on the book</h2>
 *
 * <p>A book is content; a picker is a tool. The theme picker and the motion switch were two rows at the
 * foot of the book's sidebar, and they were removed in the theme round for exactly that reason -- a pack
 * author needs a mode, not a permanent set of controls under a chapter list. This is the mode, and the
 * theme editor is the first tool in it; the quest editor (the next stage) lands here too.
 *
 * <h2>Why the editor is thin</h2>
 *
 * <p>Because everything behind it already exists in Armature and is asserted there:
 * {@code Appearance.beginEditing} materialises the resolved theme so the list opens showing what is on
 * screen, {@code setCustom} edits one token, {@code clearAllCustom} reverts, and {@code saveAsTheme}
 * writes {@code config/armature/themes/&lt;name&gt;.json}, reloads the catalogue and selects the new
 * theme. This class decides where the rows go and what a press means, and nothing else -- which is the
 * whole reason the picker could be moved here without a rewrite.
 *
 * <h2>The editor is opened in a state, not merely drawn</h2>
 *
 * <p>{@link #init} calls {@code beginEditing} once per opening, before the widgets are built. That is not
 * a convenience: edits apply <i>over</i> a theme, so an editor that only recorded the tokens a player
 * touched would write a file that depends on the theme it was written against -- five colours saved on
 * top of {@code amethyst} and then read against {@code paper} is a paper theme with five amethyst colours
 * in it, which no UI can explain. Starting from every colour stated is what makes "what the editor shows"
 * and "what the file says" the same thing.
 *
 * <h2>Where the drawing is</h2>
 *
 * <p>{@code render} wraps the graphics and hands a {@link GuiRenderer} to the rest, so every draw call
 * goes through the version seam -- the same arrangement the quest book uses, and for the same reason: the
 * widget pass needs the raw context, and nothing else here does.
 */
public final class DevScreen extends ArmatureScreen {

    /** How tall a colour's swatch is, and the gap before the hex value beside it. */
    private static final int SWATCH = 12;
    private static final int SWATCH_GAP = 6;

    /** The list, as a scroll view: the rows are widgets, so they have to move when the list does. */
    private final ScrollView listView = ScrollView.of(Viewport.fixed());

    /** What was built, so the drawing reads the layout the widgets were placed from. */
    private Layout listLayout;
    private DevLayout.Frame frame;
    private List<DevLayout.Action> rows = List.of();

    /** The token the channel buttons edit, or null before a colour has been picked. */
    private String selected;

    /** Whether an editing session has been opened for this visit. See the class note. */
    private boolean editingStarted;

    public DevScreen() {
        super(Component.translatable(DevLayout.TITLE));
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        if (!editingStarted) {
            // Once per opening, not once per rebuild: a rebuild happens on every press, and re-opening
            // the session under the player's edits would be a save-and-reload per button.
            Appearance.beginEditing();
            editingStarted = true;
        }

        frame = DevLayout.frame(width, height);
        rows = switches();
        List<DevLayout.Action> all = allRows();
        listLayout = DevLayout.build(all, frame.body().width(), Measure.monospace(6, 9));

        listView.clear();
        listView.viewport().bounds(frame.body().x(), frame.body().y(),
                frame.body().width(), frame.body().height());

        for (DevLayout.Action row : all) {
            if (row.hasButton()) {
                // Created at 0,0 with no size: `apply` sets the rectangle from the slot, and the shape
                // says which part of the row the button takes. A button created at its final size would
                // be a second description of where a row goes.
                ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.buttonLabel()),
                        () -> press(row.key()));
                listView.put(row.key(), button, DevLayout::strip);
            }
            else if (row.isControl()) {
                // The whole row is the control. It is **flat** -- no fill, no border -- because the row's
                // appearance is its name, the swatch of the colour it holds and a wash when it is the one
                // being edited. A button that painted its own fill covered exactly the colour this screen
                // exists to show, which is what the first version did: forty-one rows of grey, and not one
                // swatch visible.
                //
                // The name is still the widget's, drawn left-aligned -- a column of centred names has a
                // ragged left edge and nothing to line up with. The swatch, the hex and the washes are
                // this screen's, drawn where the colour is.
                ArmatureButton button = control(0, 0, 0, 0, Component.literal(row.label()),
                        () -> select(row.key()));
                button.alignLeft(true).flat(true);
                listView.put(row.key(), button);
            }
        }
        listView.apply(listLayout, frame.body().width());

        footerControls();
    }

    /** The three switches at the top of the list, labelled with the state they are in. */
    private List<DevLayout.Action> switches() {
        Theme theme = Appearance.main();
        return List.of(
                DevLayout.Action.button(DevLayout.DEV,
                        "Developer mode: " + (DevMode.on() ? "on" : "off"),
                        DevMode.on() ? "Turn off" : "Turn on"),
                DevLayout.Action.button(DevLayout.THEME,
                        "Theme: " + theme.displayName(),
                        "Change"),
                DevLayout.Action.button(DevLayout.MOTION,
                        "Motion: " + (Appearance.motion() ? "on" : "off"),
                        Appearance.motion() ? "Turn off" : "Turn on"));
    }

    /** The whole list: the three switches, then every colour in the registry's own order. */
    private List<DevLayout.Action> allRows() {
        List<DevLayout.Action> all = new ArrayList<>(rows);
        all.addAll(DevLayout.colourRows());
        return List.copyOf(all);
    }

    /** The fixed controls under the list: the eight channel buttons, then Reset, Save and Close. */
    private void footerControls() {
        for (int i = 0; i < DevLayout.CHANNELS.size(); i++) {
            DevLayout.Channel channel = DevLayout.CHANNELS.get(i);
            ArmatureButton button = control(DevLayout.channel(i, frame.channels()),
                    Component.literal(channel.channel() + (channel.step() < 0 ? "\u2212" : "+")),
                    () -> nudge(channel));
            button.tooltip(List.of(
                    Component.literal("The selected colour's " + channel.channel() + " channel"),
                    Component.literal(channel.step() < 0 ? "Eight steps down" : "Eight steps up")));
        }

        control(frame.footer().get(DevLayout.RESET),
                Component.translatable("tasked.dev.reset"), this::resetAll)
                .tooltip(List.of(Component.literal("Undo every colour changed here"),
                        Component.literal("The theme underneath is untouched")));

        ArmatureButton save = control(frame.footer().get(DevLayout.SAVE),
                Component.translatable("tasked.dev.save"), this::save);
        save.tooltip(List.of(Component.literal("Writes config/armature/themes/"),
                Component.literal(Themes.derivedName(Appearance.currentName(), "edited")
                        + ".json, and switches to it")));

        control(frame.footer().get(DevLayout.CLOSE),
                Component.translatable("tasked.dev.close"), this::onClose);
    }

    /**
     * A control, placed at its own rectangle.
     *
     * <p>One method rather than {@code addRenderableWidget} at each site, because forgetting the
     * remembering half is silent, and because the list's own buttons go through {@code listView.put}
     * instead -- see {@link #init} -- so the two paths are visibly different.
     */
    private ArmatureButton control(int x, int y, int w, int h, Component label, Runnable onPress) {
        return addRenderableWidget(new ArmatureButton(x, y, w, h, label, onPress));
    }

    private ArmatureButton control(dev.ellipog.tasked.client.BookGeometry.Rect rect, Component label,
                                  Runnable onPress) {
        return control(rect.x(), rect.y(), rect.width(), rect.height(), label, onPress);
    }

    // ------------------------------------------------------------------
    // Presses
    // ------------------------------------------------------------------

    /** What one of the three switches does. */
    private void press(String key) {
        switch (key) {
            case DevLayout.DEV -> DevMode.toggle();
            case DevLayout.THEME -> Appearance.cycleTheme();
            case DevLayout.MOTION -> Appearance.setMotion(!Appearance.motion());
            default -> {
                return;
            }
        }
        rebuildWidgets();
    }

    /** Picks the colour the channel buttons edit. Pressing the selected row again lets go of it. */
    private void select(String key) {
        String id = DevLayout.tokenId(key);
        selected = id != null && id.equals(selected) ? null : id;
        rebuildWidgets();
    }

    /** One channel button: eight steps of one channel of the selected colour. */
    private void nudge(DevLayout.Channel channel) {
        String id = selected;
        if (id == null) {
            return;
        }
        int shift = channelShift(channel.channel());
        int argb = Appearance.main().colour(id);
        int value = Mth.clamp(((argb >>> shift) & 0xFF) + channel.step() * 8, 0, 255);
        Appearance.setCustom(id, (argb & ~(0xFF << shift)) | (value << shift));
        rebuildWidgets();
    }

    /** Which bits of the packed colour a channel's letter names. */
    private static int channelShift(String channel) {
        return switch (channel) {
            case "R" -> 16;
            case "G" -> 8;
            case "B" -> 0;
            default -> 24;
        };
    }

    private void resetAll() {
        Appearance.clearAllCustom();
        rebuildWidgets();
    }

    /**
     * Saves the edits as a theme and switches to it.
     *
     * <p>No name is passed: {@code saveAsTheme} derives one from the theme being edited, which is why a
     * save never has to be refused for want of a text field -- the kit has a text model but the widget
     * around it is the quest editor's work, not this screen's. The call selects the new theme and clears
     * the edits, so the session that was open has been written and forgotten: {@code editingStarted} goes
     * back to false and the next rebuild opens a fresh one over the theme just saved.
     */
    private void save() {
        if (Appearance.saveAsTheme(null) != null) {
            editingStarted = false;
        }
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * The content, through the seam.
     *
     * <p>This screen never names the game's graphics type: {@link ArmatureScreen#renderContent} is the
     * one place the wrapping happens, so the seam check's list of files allowed near the context does
     * not grow by one per screen. A screen that had to interleave drawing with the widget pass would
     * have to override {@code render} instead -- the quest book does, for its raised-Z cluster -- and
     * that is the exception rather than the shape.
     */
    @Override
    protected void renderContent(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        renderer.fill(0, 0, width, height, ArmatureTheme.dim());
        draw(renderer, mouseX, mouseY);
    }

    private void draw(GuiRenderer r, int mouseX, int mouseY) {
        if (frame == null || listLayout == null) {
            return;
        }
        ArmatureTheme.panel(r, frame.card().x(), frame.card().y(), frame.card().width(),
                frame.card().height(), ArmatureTheme.panel(), ArmatureTheme.panelEdge());

        Slot title = new Slot(null, frame.title().x(), frame.title().y(),
                frame.title().width(), frame.title().height());
        r.text(Component.translatable(DevLayout.TITLE).getString(), title.x(),
                title.y() + (title.height() - r.lineHeight()) / 2, ArmatureTheme.title());

        Measure measure = textMeasure(r);

        // The list, clipped to its own body: a scrolled row is cut at the edge rather than drawn over
        // the bands below it, which is the same clip the party panel's body has.
        try (GuiRenderer.Scoped clip = r.clip(frame.body().x(), frame.body().y(),
                frame.body().width(), frame.body().height())) {
            for (DevLayout.Action row : allRows()) {
                Slot slot = listLayout.slot(row.key());
                if (slot == null) {
                    continue;
                }
                Slot onScreen = onScreen(slot);
                if (row.heading()) {
                    r.text(Measure.truncate(row.label(), slot.width(), measure),
                            onScreen.x(), textY(slot, onScreen, r), ArmatureTheme.faint());
                }
                else if (row.isControl()) {
                    drawColourRow(r, row, slot, onScreen, measure, mouseX, mouseY);
                }
                else {
                    // A switch row's label stops short of the strip its button was placed in, so a long
                    // theme name cannot run under its own Change button.
                    r.text(Measure.truncate(row.label(),
                                    Math.max(0, slot.width() - DevLayout.STRIP_WIDTH - 8), measure),
                            onScreen.x() + 4, textY(slot, onScreen, r), ArmatureTheme.body());
                }
            }
        }

        drawEditing(r, measure);
        listView.drawScrollbar(r, ArmatureTheme.scrollTrack(), ArmatureTheme.scrollThumb());
    }

    /**
     * One colour row: a swatch of the colour it holds now, and that colour as hex.
     *
     * <p>The row's <i>name</i> is not here. It is the widget's label, because the whole row is a control
     * -- and what is here is the colour, which no widget can draw, since a swatch is a filled rectangle
     * and not a string. The two washes say which row the channel buttons will edit and which row the
     * pointer is over.
     */
    private void drawColourRow(GuiRenderer r, DevLayout.Action row, Slot slot, Slot onScreen,
                               Measure measure, int mouseX, int mouseY) {
        String id = DevLayout.tokenId(row.key());
        int argb = Appearance.main().colour(id);
        boolean isSelected = id != null && id.equals(selected);
        // `onScreen`, not `slot`: the slot is in the list's own content coordinates and the pointer is
        // in screen coordinates, so asking the slot would light up the wrong row by exactly the
        // viewport's origin -- and by the scroll on top of it.
        boolean hovered = !isSelected && onScreen.contains(mouseX, mouseY);

        if (isSelected || hovered) {
            // A wash rather than a border, and the pointer's is fainter than the selection's: one is a
            // state of the list and the other is where the mouse happens to be.
            r.fill(onScreen.x(), onScreen.y(), onScreen.right(), onScreen.bottom(),
                    Colour.translucent(ArmatureTheme.rowHover(), isSelected ? 0.5F : 0.22F));
        }

        String hex = String.format("#%06X", argb & 0xFFFFFF);
        int hexWidth = r.textWidth(hex);
        int swatchX = onScreen.right() - 6 - hexWidth - SWATCH_GAP - SWATCH;
        int swatchY = onScreen.y() + (slot.height() - SWATCH) / 2;
        // The border is the larger rectangle underneath rather than four fills, which the renderer's own
        // `outline` would be: one call, and a swatch of the card's own colour is still visible.
        r.fill(swatchX - 1, swatchY - 1, swatchX + SWATCH + 1, swatchY + SWATCH + 1,
                ArmatureTheme.panelEdge());
        r.fill(swatchX, swatchY, swatchX + SWATCH, swatchY + SWATCH, argb);
        r.text(hex, onScreen.right() - 6 - hexWidth, textY(slot, onScreen, r),
                isSelected ? ArmatureTheme.title() : ArmatureTheme.faint());
    }

    /** The line under the list: what the channel buttons will edit, or what to do to pick something. */
    private void drawEditing(GuiRenderer r, Measure measure) {
        String text;
        if (selected == null) {
            text = Component.translatable("tasked.dev.pick").getString();
        }
        else {
            ThemeToken token = ThemeToken.byId(selected);
            int argb = Appearance.main().colour(selected);
            text = (token == null ? selected : token.label()) + "  " + String.format("#%08X", argb);
        }
        Slot band = new Slot(null, frame.editing().x(), frame.editing().y(),
                frame.editing().width(), frame.editing().height());
        r.text(Measure.truncate(text, band.width(), measure), band.x() + 4,
                band.y() + (band.height() - r.lineHeight()) / 2,
                selected == null ? ArmatureTheme.faint() : ArmatureTheme.body());
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private Slot onScreen(Slot slot) {
        Viewport body = listView.viewport();
        return new Slot(slot.key(), body.screenX(slot.x()), body.screenY(slot.y()),
                slot.width(), slot.height());
    }

    private static int textY(Slot slot, Slot onScreen, GuiRenderer r) {
        return onScreen.y() + (slot.height() - r.lineHeight()) / 2;
    }

    private static Measure textMeasure(GuiRenderer r) {
        return Measure.of(r::textWidth, r.lineHeight());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (listView.accepts(mouseX, mouseY)) {
            listView.scrollBy(-(int) (scrollY * 30));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
