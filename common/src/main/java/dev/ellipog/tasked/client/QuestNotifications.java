package dev.ellipog.tasked.client;

import dev.ellipog.tasked.progress.QuestState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The diff behind the completion and claim notices: what the cache held, against what it holds now.
 *
 * <h2>Why the client derives this rather than being told</h2>
 *
 * <p>A progress sync carries a state, not a change — the same shape arrives whether a quest finished
 * a moment ago or was already finished when the player joined. The server's own line about a
 * completion goes to chat, and chat is not readable while a screen is open. So the difference
 * between "this quest is finished" and "this quest <i>just</i> finished" can only be the client
 * comparing two samples, and this is that comparison, kept game-free so it can be asserted without
 * a client.
 *
 * <h2>The three silences, and each is a rule rather than an accident</h2>
 *
 * <ul>
 *   <li><b>The first sample seeds and says nothing.</b> It is a join, and a join into a world whose
 *       book is half finished must not announce the whole finished half.</li>
 *   <li><b>An empty sample is a reset, not a hundred removals.</b> A disconnect clears the cache and
 *       bumps its revision; a notifier holding the last map across that would read the next server's
 *       tree against the previous server's states — and a quest that was locked there and is
 *       complete here would be announced as a completion that never happened in front of this
 *       player. Empty means "no news", so the map is cleared and the next sample seeds again.</li>
 *   <li><b>A silent auto-claim mode suppresses the completion.</b> An author who turned auto-claim
 *       on for fifty starter quests asked for exactly this; see {@code RewardAutoClaim.notifies}.
 *       The flag is computed by the caller, which is where the enum lives.</li>
 * </ul>
 *
 * <h2>A claim is a different transition from a completion</h2>
 *
 * <p>A claim is the moment the server stops owing: the quest was COMPLETED at both samples, what was
 * claimable is not any more. Requiring the state at both ends is what keeps a reset (COMPLETED →
 * LOCKED) or a fresh completion (UNLOCKED → COMPLETED) from reading as a claim, and it means the
 * sound fires when the server has confirmed the claim rather than when the button was pressed —
 * a refusal is not a claim.
 */
public final class QuestNotifications {

    /** What a notice is about. */
    public enum Kind {

        /** A quest that was not finished and now is. */
        COMPLETED,

        /** Rewards that were waiting and now are not: the server confirmed a claim. */
        CLAIMED
    }

    /**
     * One quest as the diff needs it.
     *
     * @param questId   the quest's id
     * @param state     the state the server last reported
     * @param claimable whether this player has rewards waiting on it
     * @param silent    whether the author asked for a completion to go unannounced
     */
    public record Snapshot(String questId, QuestState state, boolean claimable, boolean silent) {
    }

    /** One thing that just happened. The caller resolves the title it shows; this carries the id. */
    public record Notice(Kind kind, String questId) {
    }

    /** What one quest was at the last sample. */
    private record Sample(QuestState state, boolean claimable) {
    }

    private final Map<String, Sample> last = new LinkedHashMap<>();

    /**
     * The notices since the last sample, oldest first.
     *
     * <p>Also the seeding: the first sample after a reset returns nothing and remembers everything,
     * which is what makes a join quiet.
     */
    public List<Notice> sample(List<Snapshot> now) {
        if (now.isEmpty()) {
            // See the class note: a cleared or not-yet-arrived cache is not "everything was just
            // removed". Forgetting is the honest reading, and the next sample seeds silently.
            last.clear();
            return List.of();
        }

        List<Notice> notices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Snapshot snapshot : now) {
            seen.add(snapshot.questId());
            Sample was = last.put(snapshot.questId(),
                    new Sample(snapshot.state(), snapshot.claimable()));
            if (was == null) {
                continue;   // a join, or a quest the tree just gained: nothing to compare against
            }
            if (was.state() != QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED
                    && !snapshot.silent()) {
                notices.add(new Notice(Kind.COMPLETED, snapshot.questId()));
            }
            if (was.state() == QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED
                    && was.claimable() && !snapshot.claimable()) {
                notices.add(new Notice(Kind.CLAIMED, snapshot.questId()));
            }
        }
        // A quest the tree no longer carries is forgotten rather than kept: a reload that removes one
        // must not leave its id behind to announce on a later reappearance, and an unbounded map of
        // every id a long session has ever seen is a leak with no reader.
        last.keySet().retainAll(seen);
        return List.copyOf(notices);
    }

    /** Forgets everything: a disconnect, or a change of team. */
    public void reset() {
        last.clear();
    }
}
