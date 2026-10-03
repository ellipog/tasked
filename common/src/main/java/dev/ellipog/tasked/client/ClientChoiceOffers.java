package dev.ellipog.tasked.client;

import dev.ellipog.tasked.net.ChoiceRewardPayload;

import java.util.List;

/**
 * The choice offer the server last sent, waiting for the picker to be shown.
 *
 * <p>One at a time, because a claim is one press: a second offer replaces the first, which is also
 * what a re-press produces. The screen reads this when it opens and clears it when the pick is sent
 * or the offer is dismissed.
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

    private static volatile Offer current;

    /** Takes an offer from the wire. */
    public static void accept(String questId, int rewardIndex, List<ChoiceRewardPayload.Entry> entries) {
        current = new Offer(questId, rewardIndex, entries);
    }

    /** The offer waiting to be shown, or null. */
    public static Offer current() {
        return current;
    }

    /** Forgets the offer: answered, dismissed, or the connection ended. */
    public static void clear() {
        current = null;
    }
}
