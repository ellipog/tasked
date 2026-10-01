package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A renderer that keeps what it was told to draw.
 *
 * <h2>Why the panel needs one</h2>
 *
 * <p>Because every fault this panel has had was a <b>drawing</b> fault that no arithmetic could see: the
 * radius row's arrows built as widgets at the list's coordinates and placed outside the panel; then drawn by
 * nobody at all; then a row that fell through the panel's dispatch into the switch-label branch and drew as a
 * bare label with no controls, while the hit test answered presses perfectly. Three reports from play, and the
 * question behind each of them is one line: <i>was anything drawn here?</i> A recorded call answers it. A
 * screen cannot be instantiated by a test; the panel can, and this is the part of {@link GuiRenderer} it needs
 * to be instantiated with.
 *
 * <p>No window, no font, no items. Text width is a stand-in — six pixels a character, which is what
 * Minecraft's own font is within a few tenths, and the arithmetic under test is position rather than
 * typesetting. The three methods that need a client answer "no" rather than throwing, so a test may draw a
 * panel that happens to contain an item or a player head.
 */
final class RecordingRenderer implements GuiRenderer {

    /** One filled rectangle, as it was drawn. */
    record Fill(int left, int top, int right, int bottom, int argb) {
    }

    /** One line of text, with the left edge it was drawn from. */
    record Drawn(String text, int x, int y, int argb) {
    }

    private final List<Fill> fills = new ArrayList<>();
    private final List<Drawn> texts = new ArrayList<>();

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        fills.add(new Fill(left, top, right, bottom, argb));
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        texts.add(new Drawn(text, x, y, argb));
    }

    @Override
    public void flush() {
    }

    @Override
    public int textWidth(String text) {
        return text.length() * 6;
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        // The panels this recorder exists for draw no styled text -- the description's own recorder is
        // Armature's, where the runs are kept. Recorded as a plain line so a caller that does draw one still
        // appears in the log, which is what a recorder is for.
        StringBuilder whole = new StringBuilder();
        for (StyledRun run : runs) {
            whole.append(run.text());
        }
        texts.add(new Drawn(whole.toString(), x, y, argb));
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        return Math.round(textWidth(text) * scale);
    }

    @Override
    public int lineHeight() {
        return 9;
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        return false;
    }

    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        return false;
    }

    @Override
    public boolean blur(float partialTick) {
        return false;
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        return () -> {
        };
    }

    // ------------------------------------------------------------------
    // What a test asks
    // ------------------------------------------------------------------

    /**
     * Whether some filled rectangle covers this one.
     *
     * <p>Covered rather than exactly equal, because a surface with a radius is drawn as spans rather than as
     * one rectangle: a test that demanded one exact fill would pass at radius zero and fail at six, which is
     * a test that would rather be about the theme than about the control.
     */
    boolean covered(int left, int top, int right, int bottom) {
        return fills.stream().anyMatch(fill -> fill.left() <= left && fill.top() <= top
                && fill.right() >= right && fill.bottom() >= bottom);
    }

    /** Whether a line of text was drawn with its left edge and baseline inside this rectangle. */
    boolean wroteWithin(String text, int left, int top, int right, int bottom) {
        return texts.stream().anyMatch(drawn -> drawn.text().equals(text) && drawn.x() >= left
                && drawn.x() <= right && drawn.y() >= top - 8 && drawn.y() <= bottom);
    }

    boolean wrote(String text) {
        return texts.stream().anyMatch(drawn -> drawn.text().equals(text));
    }

    List<Fill> fills() {
        return List.copyOf(fills);
    }

    List<Drawn> texts() {
        return List.copyOf(texts);
    }

    /** Everything drawn, for an assertion's message: a missing control is not visible in a false. */
    String describe() {
        return "fills=" + fills + " texts=" + texts;
    }
}
