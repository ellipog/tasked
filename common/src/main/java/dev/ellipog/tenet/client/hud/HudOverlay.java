package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.ClientAppearance;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.ToastArt;
import dev.ellipog.tenet.client.ToastStack;
import dev.ellipog.tenet.progress.QuestState;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two HUD elements' one drawing, and the notice stack that feeds one of them.
 *
 * <h2>Why one class rather than a painter per element</h2>
 *
 * <p>Because the two questions a layout has to answer -- how big is this, and paint it -- have to be
 * answered by the same description, or the editor grabs a box the game does not draw. Both are
 * <b>exhaustive switches over {@link HudElement}</b>, so a fifth element fails to compile until it has an
 * answer to each; that is the shape {@code HudElement}'s own note said it was waiting for.
 *
 * <h2>Where this is called from, and what it is not allowed to name</h2>
 *
 * <p>The loaders' HUD seams -- Fabric's {@code HudRenderCallback}, NeoForge's {@code RenderGuiEvent.Post} --
 * each wrap the frame's drawing context in the toolkit's renderer and call {@link #render}. So this class
 * names no {@code net.minecraft.client} type and is safe to load on a server, where it is simply never
 * called. Nothing here is drawn on a server, and nothing here decides whether the HUD is drawn at all: the
 * seam is not called while the player has hidden the GUI, which is the answer F1 gets for free.
 *
 * <h2>The element's size is measured every frame, and nothing is cached</h2>
 *
 * <p>A pin list and a notice stack are as big as what they hold, so their size is asked for rather than
 * remembered. {@code size} and {@code paint} each assemble the content again rather than sharing one
 * assembly, and that is deliberate: the tree and the progress can both move between the two calls, so a
 * cached panel would be a frame of somebody else's pins -- and the walk is a handful of quests either way.
 *
 * <h2>The notice stack</h2>
 *
 * <p>One {@link ToastStack}, exactly as the book keeps one: same lifetime, same cap, same fade, and the
 * drawing is the same {@code ToastArt} the book uses. This class owns it because the HUD element is what
 * shows it, and {@link #notice} is the only way in -- {@code QuestNotifier} is the only caller, which is what
 * keeps "what happened" in one place and "where it is said" in another.
 */
public final class HudOverlay {

    /** Which surface is asking: the live HUD, or the HUD editor drawing the same thing. */
    public enum Face {

        /**
         * The world's HUD.
         *
         * <p>Nothing pinned and no live notice means nothing is drawn -- not an empty frame -- so an element
         * that is switched on and has nothing to say is invisible, which is what a player expects of it.
         */
        LIVE,

        /**
         * The editor's own canvas.
         *
         * <p>The same drawing with a stand-in sentence where there is no content, because a zero-sized
         * element cannot be seen <i>or</i> grabbed, and an element a player cannot pick up is the one
         * arrangement that traps them in that screen.
         */
        EDITOR
    }

    /** A measured element's size, in the window's own pixels. */
    public record Size(int width, int height) {

        /** Whether there is nothing to draw, and so nothing to place. */
        public boolean empty() {
            return width <= 0 || height <= 0;
        }
    }

    /** The notice stack: the book's own class, one per surface that shows notices. */
    private static ToastStack notices = new ToastStack();

    private HudOverlay() {
    }

    /**
     * Draws every HUD element that is switched on, where the player put it.
     *
     * @param screenWidth  the window, in GUI pixels -- the same space a stored position means
     * @param screenHeight the same, vertically
     */
    public static void render(GuiRenderer renderer, int screenWidth, int screenHeight) {
        render(renderer, screenWidth, screenHeight, Util.getMillis());
    }

    /**
     * The same, at a time the caller supplies.
     *
     * <p>Exists because the clock is the only thing here a test cannot fake: a notice that has expired and
     * one that has not are the same call with a different number, and reading it inside would make "where is
     * this drawn" and "is it still there" two different tests. The loaders use the form above, which reads
     * the frame's own time.
     */
    public static void render(GuiRenderer renderer, int screenWidth, int screenHeight, long now) {
        Measure measure = Measure.of(renderer::textWidth, renderer.lineHeight());
        for (HudElement element : HudElement.values()) {
            // A control is somebody else's screen's business -- the inventory screen holds the real widget
            // and places it itself -- so only a drawn element is this class's to draw.
            if (element.kind() != HudElement.Kind.HUD || !HudSettings.on(element)) {
                continue;
            }
            Size size = size(element, measure, Face.LIVE, now);
            if (size.empty()) {
                continue;
            }
            BookGeometry.Rect box = HudLayout.boxAt(element, screenWidth, screenHeight,
                    HudSettings.x(element), HudSettings.y(element), size.width(), size.height());
            paint(element, renderer, box, measure, Face.LIVE, now);
        }
    }

    /**
     * How big one element is right now.
     *
     * <p>Exhaustive over the table, so a new element cannot be drawn without a size -- and a control answers
     * with its own box, which is the only element whose size is a constant.
     */
    public static Size size(HudElement element, Measure measure, Face face, long now) {
        return switch (element) {
            case INVENTORY_BUTTON -> new Size(element.width(), element.height());
            case PINNED_QUESTS -> {
                PinnedPanelLayout.Column column = PinnedPanelLayout.column(pins().pins(), words(), measure,
                        face == Face.EDITOR);
                yield new Size(column.width(), column.height());
            }
            case NOTIFICATIONS -> {
                List<ToastStack.Toast> rows = noticeRows(face, now);
                yield rows.isEmpty()
                        ? new Size(0, 0)
                        : new Size(ToastArt.width(rows, measure), ToastArt.height(rows.size()));
            }
        };
    }

    /** Draws one element in the box it was given. The same switch, one question over. */
    public static void paint(HudElement element, GuiRenderer renderer, BookGeometry.Rect box, Measure measure,
                             Face face, long now) {
        switch (element) {
            case INVENTORY_BUTTON -> {
                // Nothing: the control draws itself, through the toolkit, on the screen that owns it. A
                // branch here would be a second description of a button this class has never seen.
            }
            case PINNED_QUESTS -> paintPins(renderer, box, measure, face, now);
            case NOTIFICATIONS -> paintNotices(renderer, box, measure, face, now);
        }
    }

    // ------------------------------------------------------------------
    // The pinned boxes
    // ------------------------------------------------------------------

    /**
     * One bar's eased fill: what the last frame drew, gliding toward the live value.
     *
     * <p>The server pushes progress when it changes, which lands as a jump -- 3 one frame, 4 the next --
     * and a bar that jumps reads as lagging behind the hand that just mined the log. Easing starts the
     * motion on the very frame new data lands, so the bar feels faster than the sync it follows while never
     * outrunning it: the target is always the cache's own number, and the glide only decides how quickly
     * the drawing catches up.
     *
     * <p>Exponential approach, frame-rate independent: the same wall-clock interval moves the same share at
     * 30 fps and at 240. {@link #BAR_RATE} is a full sweep in about half a second -- brisk enough to read
     * as live, slow enough to read as motion rather than a flicker.
     */
    private record BarKey(String quest, int task) {
    }

    /** How fast a bar chases new progress, per second. */
    private static final double BAR_RATE = 8.0;

    /** Close enough to converged: the crawl past this would be sub-pixel on any real bar. */
    private static final double BAR_SNAP = 0.001;

    private static final Map<BarKey, Double> BAR_SHOWN = new HashMap<>();
    private static long lastBarNow = -1;
    private static double barFrameSeconds = 0.0;

    /** Opens one painted frame: how much wall clock the easing may spend. Called once per paint. */
    private static void beginBarFrame(long now) {
        barFrameSeconds = lastBarNow < 0 ? 0.0 : Math.max(0, now - lastBarNow) / 1000.0;
        lastBarNow = now;
    }

    /**
     * The fraction this frame draws for one task.
     *
     * <p>A task seen for the first time starts at its target: a newly pinned quest sweeping up from zero
     * would be motion about nothing, and the bar is news about progress, not about pinning. Everything
     * else glides from what the last frame drew.
     */
    private static double easedFraction(String quest, int task, double target) {
        BarKey key = new BarKey(quest, task);
        Double drawn = BAR_SHOWN.get(key);
        if (drawn == null) {
            BAR_SHOWN.put(key, target);
            return target;
        }
        double next = target - (target - drawn) * Math.exp(-BAR_RATE * barFrameSeconds);
        if (Math.abs(next - target) < BAR_SNAP) {
            next = target;
        }
        BAR_SHOWN.put(key, next);
        return next;
    }

    /**
     * One outlined box per pinned quest, each dimmed behind its own borders.
     *
     * <p>The dim is the element's own setting, not the theme's wash: the theme's dim is one opacity for a
     * whole screen, and a slider that could not reach below it would be a control that lies about its
     * bottom half. No fill at all when the strength is 0, which reproduces the transparent look exactly --
     * and a zero-alpha fill would be a submission that draws nothing.
     */
    private static void paintPins(GuiRenderer r, BookGeometry.Rect box, Measure measure, Face face,
                                  long now) {
        beginBarFrame(now);
        Content content = pins();
        PinnedPanelLayout.Column column = PinnedPanelLayout.column(content.pins(), words(), measure,
                face == Face.EDITOR);
        if (column.empty()) {
            return;
        }
        double dim = HudSettings.dim(HudElement.PINNED_QUESTS);
        int wash = Colour.alphaOf(ArmatureTheme.raised(), (float) dim);
        for (PinnedPanelLayout.PlacedBox placed : column.boxes()) {
            PinnedPanelLayout.Box quad = placed.box();
            int edge = box.x();
            int top = box.y() + placed.y();
            if (dim > 0) {
                r.fill(edge, top, edge + quad.width(), top + quad.height(), wash);
            }
            edge(r, edge, top, quad.width(), quad.height());
            int left = edge + PinnedPanelLayout.PAD;
            for (PinnedPanelLayout.Row row : quad.rows()) {
                int rowTop = top + row.y();
                if (row.kind() == PinnedPanelLayout.Kind.TASK) {
                    paintTask(r, measure, content, row, left, rowTop);
                    continue;
                }
                if (row.kind() == PinnedPanelLayout.Kind.TITLE) {
                    paintTitle(r, measure, row, left, rowTop);
                    continue;
                }
                // Everything else is one line of text in the ink its kind names. The switch is an expression
                // so that a sixth kind fails to compile rather than being drawn as nothing.
                r.shadowedText(row.text(), left, rowTop, wordInk(row.kind()), 1F);
            }
        }
        // Drop whatever is no longer drawn: an unpinned quest's glide must not linger to greet its return.
        Set<BarKey> live = new HashSet<>();
        for (PinnedPanelLayout.PlacedBox placed : column.boxes()) {
            for (PinnedPanelLayout.Row row : placed.box().rows()) {
                if (row.kind() == PinnedPanelLayout.Kind.TASK && PinnedPanelLayout.hasCount(row)) {
                    live.add(new BarKey(content.questId(row.pinIndex()), row.taskIndex()));
                }
            }
        }
        BAR_SHOWN.keySet().retainAll(live);
    }

    /** A 1px outline in the panel's edge ink: the box a transparent box still needs to read as one. */
    private static void edge(GuiRenderer r, int left, int top, int width, int height) {
        int ink = ArmatureTheme.panelEdge();
        r.fill(left, top, left + width, top + 1, ink);
        r.fill(left, top + height - 1, left + width, top + height, ink);
        r.fill(left, top, left + 1, top + height, ink);
        r.fill(left + width - 1, top, left + width, top + height, ink);
    }

    /** A word row's ink: the editor's sample reads as a name, and everything else stays quiet. */
    private static int wordInk(PinnedPanelLayout.Kind kind) {
        return switch (kind) {
            case SAMPLE -> ArmatureTheme.title();
            case MORE, COMPLETE -> ArmatureTheme.faint();
            // Asked for by neither caller -- both are drawn by their own branch above -- and answered so that
            // the switch is exhaustive, which is the point of writing it this way.
            case TASK, TITLE -> ArmatureTheme.body();
        };
    }

    /**
     * One pinned quest's name, with its chapter fading after it.
     *
     * <p>The chapter starts where the <i>drawn</i> title ends, which is what the layout cut it against: a
     * shortened title and a note placed from the authored one would overlap, which is the fault that rule
     * exists for. Shadowed, like every glyph on this element, because there is no backdrop behind it --
     * which is what {@code GuiRenderer.shadowedText} is for.
     */
    private static void paintTitle(GuiRenderer r, Measure measure, PinnedPanelLayout.Row row, int left,
                                   int top) {
        int textY = top + (row.height() - 8) / 2;
        r.shadowedText(row.text(), left, textY, ArmatureTheme.title(), 1F);
        if (!row.note().isEmpty()) {
            int x = left + measure.width(row.text()) + measure.width(PinnedPanelLayout.NOTE_SEPARATOR);
            r.shadowedText(row.note(), x, textY, ArmatureTheme.faint(), 1F);
        }
    }

    /**
     * One task: its icon, its sentence, its count, its tick, and its bar, top to bottom.
     *
     * <p>Every step advances by measuring the string it just drew, which is the same measurement the layout
     * reserved the room with -- so there is one answer to "how wide is this row" rather than two. The icon's
     * box is reserved whether or not the stack resolves: a missing item is still a task, and a row that
     * shifted when its icon failed to draw would move under the pointer reading it.
     *
     * <p>The bar is a hairline fill and nothing else: no track, no outline. A groove along every untouched
     * task would be decoration on rows that have nothing to say yet, and an outline around two pixels is
     * border thicker than content. The ink follows the row -- complete when done, available while going --
     * so a finished task's bar and its tick cannot disagree about what happened. The width glides rather
     * than jumps: the number stays the cache's own, and only the drawing eases toward it.
     */
    private static void paintTask(GuiRenderer r, Measure measure, Content content,
                                  PinnedPanelLayout.Row row, int left, int top) {
        ItemStack icon = content.icon(row.pinIndex(), row.taskIndex());
        if (!icon.isEmpty()) {
            r.icon(icon, left, top + (row.height() - PinnedPanelLayout.GAP - PinnedPanelLayout.ICON) / 2,
                    PinnedPanelLayout.ICON);
        }
        int x = left + PinnedPanelLayout.ICON + PinnedPanelLayout.ICON_GAP;
        int textY = top + PinnedPanelLayout.TEXT_TOP;
        int ink = row.done() ? ArmatureTheme.complete() : ArmatureTheme.body();
        r.shadowedText(row.text(), x, textY, ink, 1F);
        x += measure.width(row.text());
        if (PinnedPanelLayout.hasCount(row)) {
            String count = PinnedPanelLayout.countText(row);
            x += PinnedPanelLayout.COLUMN_GAP;
            r.shadowedText(count, x, textY, ArmatureTheme.faint(), 1F);
            x += measure.width(count);

            int barX = left + PinnedPanelLayout.ICON + PinnedPanelLayout.ICON_GAP;
            int barY = top + row.barY();
            int barWidth = Math.max(0, left + row.width() - barX);
            double target = Math.min(1.0, row.progress() / (double) row.count());
            int filled = (int) Math.round(barWidth
                    * easedFraction(content.questId(row.pinIndex()), row.taskIndex(), target));
            if (row.progress() > 0 && filled > 0) {
                r.fill(barX, barY, barX + Math.max(1, filled),
                        barY + PinnedPanelLayout.BAR_HEIGHT,
                        row.done() ? ArmatureTheme.complete() : ArmatureTheme.available());
            }
        }
        if (row.done()) {
            r.shadowedText(PinnedPanelLayout.TICK, x + PinnedPanelLayout.COLUMN_GAP, textY,
                    ArmatureTheme.complete(), 1F);
        }
    }

    // ------------------------------------------------------------------
    // The notices
    // ------------------------------------------------------------------

    private static void paintNotices(GuiRenderer r, BookGeometry.Rect box, Measure measure, Face face,
                                     long now) {
        List<ToastStack.Toast> rows = noticeRows(face, now);
        if (rows.isEmpty()) {
            return;
        }
        ToastArt.draw(r, measure, rows, box.x(), box.y(), box.width(), ClientAppearance.LOOK.motion(), now,
                HudSettings.dim(HudElement.NOTIFICATIONS));
    }

    /**
     * The notices to draw: what is live, or the editor's stand-in when nothing is.
     *
     * <p>The stand-in is born at {@code now} rather than at a fixed time, so it never fades while somebody is
     * looking at it in the editor -- a preview that vanished every four seconds would read as a fault in the
     * element rather than as the lifetime it is showing.
     */
    private static List<ToastStack.Toast> noticeRows(Face face, long now) {
        List<ToastStack.Toast> visible = notices.visible(now);
        if (!visible.isEmpty() || face == Face.LIVE) {
            return visible;
        }
        return List.of(new ToastStack.Toast(
                Component.translatable("tenet.hud.notifications_sample").getString(), false, now));
    }

    /**
     * Says something on the HUD's own stack, at a time the caller supplies.
     *
     * <p>The notifier's only way in, and its only caller. The clock is the caller's for {@link #render}'s
     * reason: the age of a sentence is what decides whether it is still on screen, and a test that could not
     * choose the time could not ask that question.
     */
    public static void notice(String text, boolean error, long now) {
        notices.add(text, error, now);
    }

    /** Drops what has faded. Called once per client tick, beside the notifier's own. */
    public static void tick() {
        notices.expire(Util.getMillis());
    }

    /** Forgets every notice and every eased bar. A disconnect, beside {@code QuestNotifier.reset()}. */
    public static void clear() {
        notices = new ToastStack();
        BAR_SHOWN.clear();
        lastBarNow = -1;
    }

    /** How many notices are held, faded or not. For the tests and for nothing else. */
    public static int held() {
        return notices.held();
    }

    // ------------------------------------------------------------------
    // What the panel is made of
    // ------------------------------------------------------------------

    /** The stack's content: the pins that resolve, and each one's task icons in task order. */
    private record Content(List<PinnedPanelLayout.Pin> pins, List<List<ItemStack>> icons,
                           List<String> questIds) {

        /**
         * The icon one task row draws, or an empty stack for a row that names none.
         *
         * <p>Indexed by quest first because every quest is drawn the same way now: the head is not special,
         * so a single list of "the focus's icons" would be the focus model kept alive one layer down.
         */
        ItemStack icon(int pinIndex, int taskIndex) {
            if (pinIndex < 0 || pinIndex >= icons.size()) {
                return ItemStack.EMPTY;
            }
            List<ItemStack> tasks = icons.get(pinIndex);
            return taskIndex >= 0 && taskIndex < tasks.size() ? tasks.get(taskIndex) : ItemStack.EMPTY;
        }

        /**
         * The quest id behind one pin slot, or nothing when the slot resolved to no quest.
         *
         * <p>The bar animator's key: the layout deliberately never holds an id, so the lookup lives here
         * beside the icons, which are the same shape of answer for the same reason.
         */
        String questId(int pinIndex) {
            return pinIndex >= 0 && pinIndex < questIds.size() ? questIds.get(pinIndex) : "";
        }
    }

    /**
     * The pins, resolved against the tree.
     *
     * <p>A pin the tree does not hold is skipped <b>and spends no drawn slot</b>: the file is per client and
     * not per world, so a pin can name a quest this server has never heard of, and a list that counted it
     * would show five of a player's six pins on that world.
     */
    private static Content pins() {
        List<PinnedPanelLayout.Pin> out = new ArrayList<>();
        List<List<ItemStack>> icons = new ArrayList<>();
        List<String> questIds = new ArrayList<>();
        for (String id : PinnedQuests.pinned()) {
            ClientQuestCache.Entry entry = ClientQuestCache.entry(id);
            if (entry == null) {
                continue;
            }
            List<PinnedPanelLayout.Task> tasks = new ArrayList<>(entry.tasks().size());
            List<ItemStack> stacks = new ArrayList<>(entry.tasks().size());
            for (int i = 0; i < entry.tasks().size(); i++) {
                ClientQuestCache.TaskEntry task = entry.tasks().get(i);
                tasks.add(new PinnedPanelLayout.Task(task.text().getString(),
                        ClientQuestCache.taskProgressOf(id, i), task.count(),
                        ClientQuestCache.taskDone(id, i)));
                // The actual item, not the type icon: the same rule the book's own rows use, so a
                // stack of oak logs draws as oak logs here too rather than as a generic task glyph.
                stacks.add(task.hasItem() ? task.item() : task.icon());
            }
            out.add(new PinnedPanelLayout.Pin(entry.titleText(), entry.chapterTitleText(),
                    ClientQuestCache.stateOf(id) == QuestState.COMPLETED, tasks));
            icons.add(List.copyOf(stacks));
            questIds.add(id);
        }
        return new Content(List.copyOf(out), List.copyOf(icons), List.copyOf(questIds));
    }

    /** The boxes' sentences: the element's own label is gone, because a box per quest needs no panel name. */
    private static PinnedPanelLayout.Words words() {
        return new PinnedPanelLayout.Words(
                Component.translatable("tenet.hud.pinned_complete").getString(),
                Component.translatable("tenet.hud.pinned_sample").getString(),
                count -> Component.translatable("tenet.hud.pinned_more_tasks", count).getString());
    }
}
