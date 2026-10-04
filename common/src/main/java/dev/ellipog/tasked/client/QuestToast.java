package dev.ellipog.tasked.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tasked.client.dev.ToastStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The completion notice that reaches a player who is not looking at the book.
 *
 * <h2>Why not a SystemToast</h2>
 *
 * <p>Vanilla's is text on vanilla's own background, which reads as a system message; a quest has an
 * icon, a name and a palette, and the notice is the one place the mod speaks outside its own screen.
 * So this is a real {@link Toast}, drawn through the toolkit's renderer seam — a panel in Tasked's
 * theme, the quest's own icon, the label and the title — which costs one entry on the seam list
 * (see {@code check_seam.py}) and no new network message: everything it shows already reached the
 * client on the tree and progress payloads.
 *
 * <h2>It does not animate, deliberately</h2>
 *
 * <p>Vanilla toasts do not fade either, and a notice whose whole job is to be readable by someone
 * mid-task is the last place to add movement — so there is nothing here for the Motion switch to
 * disable, which is the switch honoured by construction.
 *
 * <h2>The token</h2>
 *
 * <p>{@code tasked:quest/<id>}, so the notifier can ask whether this quest is already being
 * announced ({@code ToastComponent.getToast}) and a repeatable quest finished twice in quick
 * succession is told once rather than queued twice.
 */
public final class QuestToast implements Toast {

    /** The icon's box, in pixels. Fits the 32-pixel toast with four either side. */
    private static final int ICON_BOX = 20;

    /** The label's own line, above the title. */
    private static final Component LABEL = Component.translatable("tasked.toast.completed");

    private final String token;
    private final ItemStack icon;
    private final Component title;
    private long bornAt = -1L;

    public QuestToast(String questId, ItemStack icon, Component title) {
        this.token = tokenFor(questId);
        this.icon = icon;
        this.title = title;
    }

    /** The identity of this quest's notice, for {@code getToast}'s dedupe and for this class's own. */
    public static String tokenFor(String questId) {
        return "tasked:quest/" + questId;
    }

    @Override
    public Object getToken() {
        return token;
    }

    @Override
    public Visibility render(GuiGraphics graphics, ToastComponent toasts, long now) {
        if (bornAt < 0L) {
            // Born when it first draws, not when it was queued: a toast behind two others must not
            // spend its life waiting in the queue.
            bornAt = now;
        }
        long life = (long) (ToastStack.LIFETIME_MILLIS * toasts.getNotificationDisplayTimeMultiplier());

        GuiGraphicsRenderer r = new GuiGraphicsRenderer(graphics);
        try (ArmatureTheme.Scope ignored = ArmatureTheme.scope(ClientAppearance.LOOK.main())) {
            ArmatureTheme.panel(r, 0, 0, width(), height());
            r.icon(icon, 5, (height() - ICON_BOX) / 2, ICON_BOX);

            int textX = 5 + ICON_BOX + 6;
            int room = width() - textX - 6;
            Measure measure = Measure.of(r::textWidth, r.lineHeight());
            r.text(Measure.truncate(LABEL.getString(), room, measure), textX, 6,
                    ArmatureTheme.faint());
            r.text(Measure.truncate(title.getString(), room, measure), textX, 6 + r.lineHeight() + 1,
                    ArmatureTheme.title());
        }
        return now - bornAt >= life ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public int width() {
        return 160;
    }

    @Override
    public int height() {
        return 32;
    }
}
