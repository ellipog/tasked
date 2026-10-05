package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.dev.ToastStack;
import dev.ellipog.tasked.quest.reward.RewardAutoClaim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one detector of completions and claims, and the client half that says so.
 *
 * <h2>Why there is exactly one</h2>
 *
 * <p>The book used to diff the cache itself, in its own {@code tick} — so a completion while the
 * book was closed was never noticed, and a notifier added beside it would have announced the same
 * completion twice whenever it was open. This class owns the diff ({@link QuestNotifications}) and
 * the book is a <b>sink</b>: it is asked to show the sentence, it does not decide that there is one.
 *
 * <h2>The two sinks, and why the routing is here</h2>
 *
 * <p>The book's own stack is the right notice while the book is on screen — chat is unreadable
 * behind a screen, and the stack is themed and tested. A player who is not looking at the book gets
 * a real toast ({@link QuestToast}), which the game draws over whatever they are doing. Both carry
 * the same sentence, so the two can never disagree about what happened.
 *
 * <h2>Lifecycle, which is where the interesting bugs are</h2>
 *
 * <p>The diff is reset on a disconnect (both loaders call {@link #reset()}) and whenever the team
 * changes or the cache empties — because a static notifier outlives a screen, and one that kept its
 * map across a world change would read the next server's tree against the previous server's states
 * and announce a completion that never happened. See {@link QuestNotifications#sample}.
 */
public final class QuestNotifier {

    /** The diff, one per client. */
    private static final QuestNotifications DIFF = new QuestNotifications();

    /**
     * How many notices one sample may announce.
     *
     * <p>Capped because a claim-all or a party's shared progress can move a dozen quests at once,
     * and the toast queue is not a place to find out: vanilla holds five slots and queues the rest,
     * so an uncapped batch is a parade. The same number the book's own stack keeps, so the two
     * sinks bound a burst identically.
     */
    private static final int MAX_PER_SAMPLE = ToastStack.MAX;

    private static long lastRevision = -1L;
    private static UUID lastTeam;

    private QuestNotifier() {
    }

    /**
     * Called once per client tick, from each loader's client entry point.
     *
     * <p>Cheap when nothing moved: a revision comparison, which is what the book used to do while it
     * was open and what this now does whether or not it is.
     */
    public static void tick() {
        long revision = ClientQuestCache.progressRevision();
        UUID team = ClientQuestCache.teamId().orElse(null);
        if (revision == lastRevision && Objects.equals(team, lastTeam)) {
            return;
        }
        lastRevision = revision;
        if (!Objects.equals(team, lastTeam)) {
            // A different team is different progress: the previous map says nothing about this one.
            lastTeam = team;
            DIFF.reset();
        }

        List<QuestNotifications.Notice> notices = DIFF.sample(snapshots());
        if (notices.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (int i = 0; i < Math.min(notices.size(), MAX_PER_SAMPLE); i++) {
            announce(minecraft, notices.get(i));
        }
    }

    /** The cache, as the diff reads it: identity, state, what is waiting, and the author's silence. */
    private static List<QuestNotifications.Snapshot> snapshots() {
        Minecraft minecraft = Minecraft.getInstance();
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        List<QuestNotifications.Snapshot> snapshots = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            RewardAutoClaim mode = entry.effectiveAutoClaim();
            snapshots.add(new QuestNotifications.Snapshot(
                    entry.id(),
                    ClientQuestCache.stateOf(entry.id()),
                    self != null && ClientQuestCache.canClaimFor(self, entry.id()),
                    mode.automatic() && !mode.notifies()));
        }
        return snapshots;
    }

    /** Says one notice the way its moment asks for. */
    private static void announce(Minecraft minecraft, QuestNotifications.Notice notice) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(notice.questId());
        if (entry == null) {
            return;   // the tree moved between the sample and here; nothing to name
        }

        if (notice.kind() == QuestNotifications.Kind.CLAIMED) {
            // The server confirmed it: what was owed is not any more. Played here rather than on the
            // press, because a refusal is not a claim and a press that is refused must stay silent.
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0F, 1.0F));
            return;
        }

        Component message = Component.translatable("tasked.quest.completed", entry.title());
        if (minecraft.screen instanceof QuestBookScreen book) {
            book.notifyQuestCompleted(message);
        } else if (minecraft.getToasts().getToast(QuestToast.class, QuestToast.tokenFor(entry.id()))
                == null) {
            // Not already being announced: a repeatable quest finished twice in quick succession is
            // told once, and the token is what makes the two notices the same notice.
            ItemStack icon = entry.icon();
            minecraft.getToasts().addToast(
                    new QuestToast(entry.id(), icon, Component.literal(entry.title())));
        }
        // A soft chime rather than the advancement fanfare this first shipped with: a pack with a
        // hundred quests plays this a hundred times, and the challenge sting is a celebration-sized
        // sound for a fifty-times-a-session event. The orb pickup at reduced volume reads as "noted",
        // which is what a completion notice is.
        minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 0.6F));
    }

    /**
     * A grant overflowed: items are on the floor.
     *
     * <p>The server plays the click and shows the action-bar line, and that is where a player outside
     * the book reads it — so this does nothing there rather than adding a third copy. Inside the book
     * the HUD is not drawn at all, which is the one case this exists for: the sentence goes to the
     * book's own stack, the same routing, for the same reason, as a completion notice.
     *
     * <p>Called from the payload handler, which both loaders run on the client thread — the thread the
     * screen is read and written on; see {@code FabricClientNetworking} for where that was established.
     */
    public static void rewardOverflow(int stacks) {
        if (stacks <= 0) {
            return;
        }
        if (Minecraft.getInstance().screen instanceof QuestBookScreen book) {
            book.notifyRewardOverflow(stacks);
        }
    }

    /**
     * How a sweep ended: claimed N of M, and whether it stopped for want of room.
     *
     * <p>Routed like the overflow notice, and for the same reason — the action bar the server also
     * sends is part of the HUD, which is not drawn behind the book. A sweep that stopped and one that
     * finished both make rows disappear, so the sentence is what tells them apart.
     */
    public static void claimSummary(int claimed, int total, boolean halted) {
        if (Minecraft.getInstance().screen instanceof QuestBookScreen book) {
            book.notifyClaimSummary(claimed, total, halted);
        }
    }

    /**
     * The server's verdict on a choice pick, routed to the card that asked.
     *
     * <p>Handed to the screen rather than acted on here: only the screen knows whether the offer the
     * verdict names is still the one on display. Escape during the round trip is a legitimate way out,
     * and a verdict for an offer nobody is showing must be dropped rather than matched to whatever
     * slid into its place.
     */
    public static void choiceResult(String questId, int rewardIndex,
                                    dev.ellipog.tasked.net.ClaimChoiceResultPayload.Result result) {
        if (Minecraft.getInstance().screen instanceof QuestBookScreen book) {
            book.notifyChoiceResult(questId, rewardIndex, result);
        }
    }

    /**
     * Forgets everything.
     *
     * <p>Called from each loader's disconnect hook, beside {@code ClientQuestCache.clear()}: the
     * cache empties itself, and this must not outlive it.
     */
    public static void reset() {
        DIFF.reset();
        lastRevision = -1L;
        lastTeam = null;
        // Offers describe a connection's questions, so they go with it: a queue kept across a
        // disconnect would greet the next server with a picker for a quest it has never heard of.
        ClientChoiceOffers.clear();
    }
}
