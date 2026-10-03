package dev.ellipog.tasked.client.dev;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tasked.Constants;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * A renderer that counts what it forwards — the instrument the canvas's cost is read from.
 *
 * <h2>Why a counter rather than an argument</h2>
 *
 * <p>The canvas's slowness was one fact: every {@code fill} is its own GPU batch submission, and a
 * chapter of curved lines issues one fill per screen pixel of ink. That is a claim about a <b>number</b>,
 * and the honest way to check a claim about a number is to read the number — not to argue it from the
 * code and then ask a player whether the game feels better.
 *
 * <p>So this wraps the real renderer when the dev mode is on, counts the frame, and logs a line a
 * second: fills, labels, icons and batched regions. Before the batching work a chapter of curves reads
 * in the tens of thousands of fills; with it, the same fills arrive in one batched region per frame,
 * which is the number that matters — the fills still exist, the <i>submissions</i> do not.
 *
 * <p>It is a decorator rather than a second implementation, and it counts in the same call that draws,
 * so nothing can be drawn without being counted. Off, it costs one branch at the call site that builds
 * the renderer.
 */
public final class CountingRenderer implements GuiRenderer {

    private static long lastReportNanos;

    private final GuiRenderer delegate;
    private int fills;
    private int texts;
    private int icons;
    private int batches;

    public CountingRenderer(GuiRenderer delegate) {
        this.delegate = delegate;
    }

    /**
     * The frame is over: log this frame's totals, at most once a second.
     *
     * <p>Called by the screen after its last draw rather than from a constructor, because a frame has an
     * end and only the screen knows where it is — a renderer built at the top of the next frame would be
     * reporting a frame it never saw finish.
     */
    public static void endFrame(GuiRenderer renderer) {
        if (renderer instanceof CountingRenderer counting) {
            counting.report();
        }
    }

    private void report() {
        long now = System.nanoTime();
        if (now - lastReportNanos < 1_000_000_000L) {
            return;
        }
        lastReportNanos = now;
        Constants.LOG.info("tasked: book frame -- {} fill(s), {} text(s), {} icon(s), {} batched region(s)",
                fills, texts, icons, batches);
    }

    @Override
    public void fill(int left, int top, int right, int bottom, int argb) {
        fills++;
        delegate.fill(left, top, right, bottom, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        texts++;
        delegate.text(text, x, y, argb);
    }

    @Override
    public void styledText(java.util.List<StyledRun> runs, int x, int y, int argb) {
        texts++;
        delegate.styledText(runs, x, y, argb);
    }

    @Override
    public int styledWidth(String text, boolean bold, boolean italic, float scale) {
        return delegate.styledWidth(text, bold, italic, scale);
    }

    @Override
    public int textWidth(String text) {
        return delegate.textWidth(text);
    }

    @Override
    public int lineHeight() {
        return delegate.lineHeight();
    }

    @Override
    public boolean icon(ItemStack stack, int boxX, int boxY, int box) {
        icons++;
        return delegate.icon(stack, boxX, boxY, box);
    }

    @Override
    public boolean face(UUID player, int boxX, int boxY, int box) {
        icons++;
        return delegate.face(player, boxX, boxY, box);
    }

    @Override
    public boolean blur(float partialTick) {
        return delegate.blur(partialTick);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public <T> T batched(java.util.function.Supplier<T> draw) {
        batches++;
        return delegate.batched(draw);
    }

    @Override
    public Scoped clip(int left, int top, int right, int bottom) {
        return delegate.clip(left, top, right, bottom);
    }
}
