package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tasked.client.BookGeometry;

import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The floating colour picker: a saturation/value box, two tracks, a hex field and three channel fields.
 *
 * <h2>An overlay, not a band</h2>
 *
 * <p>It replaces the docked colour footer -- a swatch line, four channel steppers and a permanent hex
 * field, all of which were spent on one colour at a time whether or not anybody was editing one. This
 * opens from the chip on the row the colour belongs to, sits to the left of the drawer (over the canvas,
 * where nothing can be underneath it to steal its presses), and closes when the edit is done.
 *
 * <h2>One commit per gesture, on the same terms as every other control</h2>
 *
 * <p>{@link #preview} is called as the pointer moves -- the canvas follows the drag before anything is
 * written -- and {@link #commit} once per released gesture, and once more on close if the last change was
 * not written yet. Both are the screen's: the popover does not know whether the target is a chapter's
 * patch or the player's own theme, and it must not.
 *
 * <h2>The fields are widgets; the picker is not</h2>
 *
 * <p>The hex box and the three channel fields are {@code ArmatureTextField} and {@code ScrubField}, because
 * typing and scrubbing are what those already do; the screen adds them while the popover is open and
 * removes them when it closes ({@link #widgets()}). The picker's own surface, tracks, swatches and markers
 * are drawn here, and their presses are hit-tested here -- one rectangle per part, derived once.
 */
public final class ColourPopover {

    public static final int WIDTH = 200;
    /** The saturation/value box: the design's 160x120, with the panel's own padding either side. */
    public static final int SV_WIDTH = 160;
    public static final int SV_HEIGHT = 120;
    /** The two tracks: hue, then alpha over a checkerboard. */
    public static final int TRACK_HEIGHT = 12;
    private static final int PAD = 8;
    private static final int GAP = 6;
    private static final int SWATCH = 18;
    private static final int SWATCH_GAP = 4;
    private static final int RESET_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 16;

    /** The colours offered as quick picks. Seeded from the screen's palette, and grown by `+`. */
    private static final List<Integer> PRESETS = new ArrayList<>();

    private boolean open;
    private BookGeometry.Rect panel = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect sv = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect hue = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect alpha = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect swatches = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect plus = BookGeometry.Rect.at(0, 0, 0, 0);
    private BookGeometry.Rect reset = BookGeometry.Rect.at(0, 0, 0, 0);

    private String label = "";
    private int argb = 0xFFFFFFFF;
    /** What was in force when the picker opened: what Reset returns to and what "changed" means. */
    private int openedWith = 0xFFFFFFFF;
    /** The last value handed to the target, so a release that changed nothing writes nothing. */
    private int committed = 0xFFFFFFFF;

    private IntConsumer preview = value -> {
    };
    private IntConsumer commit = value -> {
    };

    /** Which part a press claimed, so the drag and the release go to the same one. */
    private Part dragging = Part.NONE;

    private enum Part {
        NONE,
        SV,
        HUE,
        ALPHA
    }

    private final dev.ellipog.armature.client.ArmatureTextField hex;
    private final ScrubField red;
    private final ScrubField green;
    private final ScrubField blue;

    public ColourPopover() {
        hex = new dev.ellipog.armature.client.ArmatureTextField(0, 0, 0, 0, "#FFFFFF");
        hex.onSubmit(this::typeHex);
        hex.colours(ArmatureTheme.title(), ArmatureTheme.recessed(), ArmatureTheme.panelEdge());
        red = channelField();
        green = channelField();
        blue = channelField();
        // The channel fields write the moment they commit, which is once per gesture -- the same contract
        // the tracks have, so a channel drag and a track drag cannot differ in how many undo steps they cost.
        red.onCommit(value -> set(ColourMath.withChannel(argb, 0, (int) Math.round(value))));
        green.onCommit(value -> set(ColourMath.withChannel(argb, 1, (int) Math.round(value))));
        blue.onCommit(value -> set(ColourMath.withChannel(argb, 2, (int) Math.round(value))));
    }

    private ScrubField channelField() {
        return new ScrubField(0, 0, 255, 1, "");
    }

    /** Whether the picker is showing. */
    public boolean isOpen() {
        return open;
    }

    /** The whole panel, for the caller's own hit test ("was this press outside?"). */
    public BookGeometry.Rect panel() {
        return panel;
    }

    /**
     * The fields the screen has to add to itself while the picker is open.
     *
     * <p>They are ordinary widgets: the screen adds them when it opens the picker and removes them when it
     * closes, exactly as it does with the drawer's own fields. Their positions are set by {@link #place},
     * which runs on open and whenever the panel is sized.
     */
    public List<AbstractWidget> widgets() {
        return List.of(hex, red, green, blue);
    }

    /**
     * Opens the picker beside a chip.
     *
     * @param anchor  the chip's rectangle, in screen coordinates: the panel is placed at its left
     * @param bounds  the canvas, which the panel is kept inside -- so it can never cover the drawer it
     *                belongs to, and its presses are never shadowed by a row's own field
     * @param label   the property's name, drawn at the panel's top
     * @param argb    the colour in force
     * @param presets the quick picks; the caller passes what it wants offered, and `+` appends to this list
     */
    public void open(BookGeometry.Rect anchor, BookGeometry.Rect bounds, String label, int argb,
                     List<Integer> presets, IntConsumer preview, IntConsumer commit) {
        this.open = true;
        this.label = label == null ? "" : label;
        this.argb = argb;
        this.openedWith = argb;
        this.committed = argb;
        this.preview = preview == null ? value -> {
        } : preview;
        this.commit = commit == null ? value -> {
        } : commit;
        for (int preset : presets) {
            if (!PRESETS.contains(preset)) {
                PRESETS.add(preset & 0xFFFFFF);
            }
        }
        place(anchor, bounds);
        syncFields();
    }

    /** Closes, writing anything the last gesture left unwritten -- one op, on the same terms as a release. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        dragging = Part.NONE;
        if (argb != committed) {
            committed = argb;
            commit.accept(argb);
        }
    }

    /** The geometry, from the anchor and the bounds. */
    private void place(BookGeometry.Rect anchor, BookGeometry.Rect bounds) {
        int height = PAD + 10 + GAP
                + SV_HEIGHT + GAP
                + TRACK_HEIGHT + GAP
                + TRACK_HEIGHT + GAP
                + BUTTON_HEIGHT + GAP
                + BUTTON_HEIGHT + GAP
                + SWATCH + PAD;
        int x = anchor.x() - WIDTH - GAP;
        // Beside the chip when there is room to the left, and clamped inside the canvas otherwise: a
        // picker half off the canvas is a picker whose drags leave the window.
        x = Math.max(bounds.x() + 2, Math.min(x, bounds.right() - WIDTH - 2));
        int y = Math.max(bounds.y() + 2, Math.min(anchor.y(), bounds.bottom() - height - 2));
        panel = BookGeometry.Rect.at(x, y, WIDTH, height);

        int cursor = panel.y() + PAD + 10 + GAP;
        int inner = WIDTH - PAD * 2;
        sv = BookGeometry.Rect.at(panel.x() + PAD, cursor, Math.min(SV_WIDTH, inner), SV_HEIGHT);
        cursor = sv.bottom() + GAP;
        hue = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), TRACK_HEIGHT);
        cursor = hue.bottom() + GAP;
        alpha = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), TRACK_HEIGHT);
        cursor = alpha.bottom() + GAP;
        // The hex field keeps the left; Reset the right.
        hex.setX(sv.x());
        hex.setY(cursor);
        hex.setWidth(Math.max(0, sv.width() - RESET_WIDTH - GAP));
        hex.setHeight(BUTTON_HEIGHT);
        reset = BookGeometry.Rect.at(sv.right() - RESET_WIDTH, cursor, RESET_WIDTH, BUTTON_HEIGHT);
        cursor += BUTTON_HEIGHT + GAP;

        // Three channels abreast under the hex, each a ScrubField's own width.
        int channelWidth = Math.max(0, (sv.width() - GAP * 2) / 3);
        placeChannel(red, sv.x(), cursor, channelWidth);
        placeChannel(green, sv.x() + channelWidth + GAP, cursor, channelWidth);
        placeChannel(blue, sv.x() + (channelWidth + GAP) * 2, cursor, channelWidth);
        cursor += BUTTON_HEIGHT + GAP;

        swatches = BookGeometry.Rect.at(sv.x(), cursor, sv.width(), SWATCH);
        int fits = Math.max(1, (swatches.width() + SWATCH_GAP) / (SWATCH + SWATCH_GAP));
        plus = BookGeometry.Rect.at(swatches.right() - SWATCH, cursor, SWATCH, SWATCH);
        // One slot fewer than fits is reserved for the `+`, which is drawn last and always present.
        swatches = BookGeometry.Rect.at(swatches.x(), cursor,
                Math.max(0, (Math.min(fits - 1, Math.max(1, PRESETS.size())) * (SWATCH + SWATCH_GAP))),
                SWATCH);
    }

    private static void placeChannel(ScrubField field, int x, int y, int width) {
        field.setX(x);
        field.setY(y);
        field.setWidth(width);
        field.setHeight(BUTTON_HEIGHT);
        field.value(field.value());
    }

    /** Pushes the current colour into the fields, without touching the target. */
    private void syncFields() {
        hex.setValue(String.format("#%06X", argb & 0xFFFFFF));
        red.value(ColourMath.channel(argb, 0));
        green.value(ColourMath.channel(argb, 1));
        blue.value(ColourMath.channel(argb, 2));
        hsvCache = ColourMath.toHsv(argb);
    }

    /** The hue the tracks are drawn with, kept between frames so a grey's hue survives a drag. */
    private ColourMath.Hsv hsvCache = new ColourMath.Hsv(0F, 0F, 1F);

    // ------------------------------------------------------------------
    // The edit
    // ------------------------------------------------------------------

    /** Sets the colour from a gesture: previews it, and lets the release commit it. */
    private void set(int next) {
        if (next == argb) {
            return;
        }
        argb = next;
        hsvCache = ColourMath.toHsv(argb);
        syncFieldsWithoutRecursion();
        preview.accept(argb);
    }

    /**
     * Re-syncs the hex and channel texts after a track drag.
     *
     * <p>The channel fields must not commit what this writes: setting a widget's value is the caller's
     * state, and a commit here would be a write per frame of a drag -- the thing the whole design avoids.
     * So their model is updated through the same silent setter the screen would use.
     */
    private void syncFieldsWithoutRecursion() {
        hex.setValue(String.format("#%06X", argb & 0xFFFFFF));
        red.value(ColourMath.channel(argb, 0));
        green.value(ColourMath.channel(argb, 1));
        blue.value(ColourMath.channel(argb, 2));
    }

    private void typeHex(String text) {
        Integer parsed = dev.ellipog.tasked.client.dev.HexColour.parse(text, argb);
        if (parsed == null) {
            hex.setValue(String.format("#%06X", argb & 0xFFFFFF));
            return;
        }
        set(parsed);
        commit.accept(argb);
        committed = argb;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        if (button == 0 && reset.contains(mouseX, mouseY)) {
            set(openedWith);
            commit.accept(argb);
            committed = argb;
            return true;
        }
        if (button == 0 && plus.contains(mouseX, mouseY)) {
            if (!PRESETS.contains(argb & 0xFFFFFF)) {
                PRESETS.add(argb & 0xFFFFFF);
            }
            return true;
        }
        int preset = presetAt(mouseX, mouseY);
        if (preset >= 0) {
            set(preset | 0xFF000000);
            commit.accept(argb);
            committed = argb;
            return true;
        }
        if (sv.contains(mouseX, mouseY)) {
            dragging = Part.SV;
            dragTo(mouseX, mouseY);
            return true;
        }
        if (hue.contains(mouseX, mouseY)) {
            dragging = Part.HUE;
            dragTo(mouseX, mouseY);
            return true;
        }
        if (alpha.contains(mouseX, mouseY)) {
            dragging = Part.ALPHA;
            dragTo(mouseX, mouseY);
            return true;
        }
        return panel.contains(mouseX, mouseY);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!open || dragging == Part.NONE || button != 0) {
            return false;
        }
        dragTo(mouseX, mouseY);
        return true;
    }

    /** The release writes once, and only when the gesture changed the colour. */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open || button != 0) {
            return false;
        }
        if (dragging != Part.NONE) {
            dragging = Part.NONE;
            if (argb != committed) {
                committed = argb;
                commit.accept(argb);
            }
            return true;
        }
        return false;
    }

    /** A press on a track goes straight to the value under the pointer, and then drags it. */
    private void dragTo(double mouseX, double mouseY) {
        switch (dragging) {
            case SV -> {
                float saturation = fraction(mouseX, sv.x(), sv.width());
                float value = 1F - fraction(mouseY, sv.y(), sv.height());
                set(ColourMath.fromHsv(hsvCache.hue(), saturation, value,
                        ColourMath.channel(argb, 3)));
            }
            case HUE -> set(ColourMath.fromHsv(fraction(mouseX, hue.x(), hue.width()),
                    hsvCache.saturation(), hsvCache.value(), ColourMath.channel(argb, 3)));
            case ALPHA -> set(ColourMath.fromHsv(hsvCache.hue(), hsvCache.saturation(), hsvCache.value(),
                    Math.round(fraction(mouseX, alpha.x(), alpha.width()) * 255F)));
            default -> {
            }
        }
    }

    private static float fraction(double point, int from, int length) {
        if (length <= 1) {
            return 0F;
        }
        return Math.max(0F, Math.min(1F, (float) ((point - from) / (length - 1))));
    }

    private int presetAt(double mouseX, double mouseY) {
        for (int i = 0; i < PRESETS.size(); i++) {
            BookGeometry.Rect slot = presetRect(i);
            if (slot != null && slot.contains(mouseX, mouseY)) {
                return PRESETS.get(i);
            }
        }
        return -1;
    }

    private BookGeometry.Rect presetRect(int index) {
        int x = swatches.x() + index * (SWATCH + SWATCH_GAP);
        if (x + SWATCH > swatches.right()) {
            return null;
        }
        return BookGeometry.Rect.at(x, swatches.y(), SWATCH, SWATCH);
    }

    /** Escape closes the picker; the screen calls this before its own Escape meanings. */
    public boolean keyPressed(int keyCode) {
        if (!open || keyCode != 256) {
            return false;
        }
        close();
        return true;
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    public void render(GuiRenderer r, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        ArmatureTheme.panel(r, panel.x() - 2, panel.y() - 3, panel.width() + 4, panel.height() + 6,
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());
        r.text(label, panel.x() + PAD, panel.y() + PAD, ArmatureTheme.title());

        drawSv(r);
        drawHue(r);
        drawAlpha(r);
        drawReset(r, mouseX, mouseY);
        drawSwatches(r, mouseX, mouseY);
    }

    /** The saturation/value box: one vertical line per column, then a black veil that deepens downward. */
    private void drawSv(GuiRenderer r) {
        float hue = hsvCache.hue();
        for (int x = 0; x < sv.width(); x++) {
            float saturation = sv.width() <= 1 ? 0F : x / (float) (sv.width() - 1);
            r.fill(sv.x() + x, sv.y(), sv.x() + x + 1, sv.bottom(),
                    ColourMath.fromHsv(hue, saturation, 1F, 0xFF));
        }
        int steps = Math.min(48, sv.height());
        for (int i = 0; i < steps; i++) {
            float value = i / (float) steps;
            int y = sv.y() + (int) (value * sv.height());
            int next = sv.y() + (int) ((i + 1) / (float) steps * sv.height());
            int veil = Math.round(value * 255F) << 24;
            r.fill(sv.x(), y, sv.right(), Math.max(y + 1, next), veil);
        }
        // The marker: a ring around the colour's own spot.
        int markerX = sv.x() + Math.round(hsvCache.saturation() * (sv.width() - 1));
        int markerY = sv.y() + Math.round((1F - hsvCache.value()) * (sv.height() - 1));
        r.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, 0xFF000000);
        r.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, 0xFFFFFFFF);
    }

    private void drawHue(GuiRenderer r) {
        int steps = Math.min(60, Math.max(1, hue.width() / 2));
        for (int i = 0; i < steps; i++) {
            int from = hue.x() + i * hue.width() / steps;
            int to = hue.x() + (i + 1) * hue.width() / steps;
            r.fill(from, hue.y(), Math.max(from + 1, to), hue.bottom(),
                    ColourMath.fromHsv(i / (float) steps, 1F, 1F, 0xFF));
        }
        marker(r, hue, Math.round(hsvCache.hue() * (hue.width() - 1)) + hue.x());
    }

    private void drawAlpha(GuiRenderer r) {
        int steps = Math.min(60, Math.max(1, alpha.width() / 2));
        for (int i = 0; i < steps; i++) {
            int from = alpha.x() + i * alpha.width() / steps;
            int to = alpha.x() + (i + 1) * alpha.width() / steps;
            // A checkerboard under the alpha gradient, so a low alpha is visibly transparent rather than
            // just dark -- the same reason every picker in every program draws one.
            for (int x = from; x < Math.max(from + 1, to); x += 4) {
                for (int y = alpha.y(); y < alpha.bottom(); y += 4) {
                    boolean dark = ((x / 4) + (y / 4)) % 2 == 0;
                    r.fill(x, y, Math.min(x + 4, Math.max(from + 1, to)), Math.min(y + 4, alpha.bottom()),
                            dark ? 0xFF606060 : 0xFF9A9A9A);
                }
            }
            int scale = Math.round(i / (float) steps * 255F);
            r.fill(from, alpha.y(), Math.max(from + 1, to), alpha.bottom(),
                    (scale << 24) | (argb & 0xFFFFFF));
        }
        marker(r, alpha, Math.round(ColourMath.channel(argb, 3) / 255F * (alpha.width() - 1)) + alpha.x());
    }

    private void marker(GuiRenderer r, BookGeometry.Rect track, int x) {
        r.fill(x - 2, track.y() - 2, x + 3, track.bottom() + 2, 0xFF000000);
        r.fill(x - 1, track.y() - 1, x + 2, track.bottom() + 1, 0xFFFFFFFF);
    }

    private void drawReset(GuiRenderer r, int mouseX, int mouseY) {
        boolean hot = reset.contains(mouseX, mouseY);
        r.fill(reset.x(), reset.y(), reset.right(), reset.bottom(),
                hot ? ArmatureTheme.rowHover() : ArmatureTheme.recessed());
        String word = "Reset";
        r.text(word, reset.x() + (reset.width() - r.textWidth(word)) / 2,
                reset.y() + (reset.height() - r.lineHeight()) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.faint());
    }

    private void drawSwatches(GuiRenderer r, int mouseX, int mouseY) {
        for (int i = 0; i < PRESETS.size(); i++) {
            BookGeometry.Rect slot = presetRect(i);
            if (slot == null) {
                break;
            }
            boolean hot = slot.contains(mouseX, mouseY);
            r.fill(slot.x() - 1, slot.y() - 1, slot.right() + 1, slot.bottom() + 1,
                    hot ? ArmatureTheme.title() : ArmatureTheme.panelEdge());
            r.fill(slot.x(), slot.y(), slot.right(), slot.bottom(), 0xFF000000 | PRESETS.get(i));
        }
        boolean hot = plus.contains(mouseX, mouseY);
        r.fill(plus.x() - 1, plus.y() - 1, plus.right() + 1, plus.bottom() + 1,
                hot ? ArmatureTheme.title() : ArmatureTheme.panelEdge());
        r.fill(plus.x(), plus.y(), plus.right(), plus.bottom(), ArmatureTheme.recessed());
        String glyph = "+";
        r.text(glyph, plus.x() + (plus.width() - r.textWidth(glyph)) / 2,
                plus.y() + (plus.height() - r.lineHeight()) / 2,
                hot ? ArmatureTheme.title() : ArmatureTheme.body());
    }

    /** The seed for the quick picks: what a fresh picker offers before anything is pinned. */
    public static void seedPresets(List<Integer> colours) {
        if (!PRESETS.isEmpty()) {
            return;
        }
        for (int colour : colours) {
            PRESETS.add(colour & 0xFFFFFF);
        }
    }
}
