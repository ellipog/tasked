package dev.ellipog.tenet.client;

import dev.ellipog.tenet.net.ChoiceRewardPayload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The choice offers the server has sent, waiting to be shown — a queue, not a slot.
 *
 * <h2>Why a queue</h2>
 *
 * <p>One press can produce several offers: Claim all across a book, or a quest holding two choice
 * rewards. A single slot kept the last and silently dropped the rest — the rewards stayed outstanding,
 * so nothing was lost, but the player was never asked about them and had no way to know. The queue is
 * the whole fix: the picker walks them one at a time.
 *
 * <p>Two ways out, and the difference between them is the point:
 *
 * <ul>
 *   <li>{@link #pop()} — answered, or kept for later. Either way the offer leaves the walk: a kept
 *       reward stays outstanding in the inbox with its Choose button and does <b>not</b> come back
 *       around, because "keep it for later" means "do not ask me again during this session".</li>
 *   <li>{@link #clear()} — Escape, or the outside of the card. The whole walk ends.</li>
 * </ul>
 *
 * <p>Written by the payload handler and read by the screen, both on the client thread — the thread the
 * screen is read and written on; see {@code FabricClientNetworking} for where that was established.
 */
public final class ClientChoiceOffers {

    private ClientChoiceOffers() {
    }

    /** One offer: which quest and reward, and what may be chosen. */
    public record Offer(String questId, int rewardIndex, List<ChoiceRewardPayload.Entry> entries) {

        public Offer {
            entries = List.copyOf(entries);
        }
    }

    private static final Deque<Offer> pending = new ArrayDeque<>();

    /** Takes an offer from the wire. Offers queue; none replaces another. */
    public static void accept(String questId, int rewardIndex, List<ChoiceRewardPayload.Entry> entries) {
        pending.addLast(new Offer(questId, rewardIndex, entries));
    }

    /** The offer waiting to be shown, or null. */
    public static Offer current() {
        return pending.peekFirst();
    }

    /** The current offer leaves the walk: answered, or kept for later. */
    public static void pop() {
        pending.pollFirst();
    }

    /** Forgets every offer: dismissed, or the connection ended. */
    public static void clear() {
        pending.clear();
    }

    /** How many offers are waiting. For the tests and for nothing else. */
    public static int size() {
        return pending.size();
    }
}
