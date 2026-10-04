package dev.ellipog.tasked.progress;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Merging a party's record into the record of a player who leaves it.
 *
 * <h2>Why leaving copies, and why it copies the best of both</h2>
 *
 * <p>Progress belongs to a team, so a player in a party reads the party's record and their own waits,
 * untouched, for the day they leave. That is deliberate and stays. What this class adds is the other
 * half of the promised departure: the quest nodes <b>earned while in the party</b> are kept by the
 * player who leaves, so a progression-gated pack cannot soft-lock somebody who did a chain with
 * friends and then went solo.
 *
 * <p>Kept by merging rather than by replacing, and the direction of the merge is the point: every
 * field takes whichever side is further along. A player who had finished three quests alone before
 * joining does not lose them to a party that had finished one, and a party that finished a chain
 * does not lose it to a player's older solo record. Progress here is monotonic everywhere else --
 * {@link QuestProgress#recordTask} never lowers a count -- and a merge that could lower anything
 * would be the one place it went backwards.
 *
 * <h2>What travels, and what deliberately does not</h2>
 *
 * <ul>
 *   <li><b>Completion and task counts</b> travel at their best. This is what unlock gating reads.</li>
 *   <li><b>The player's own claims</b> travel, so a reward collected in the party cannot be collected
 *       again from their own record.</li>
 *   <li><b>Team claims</b> travel, because a {@code team: true} reward was one payout for the whole
 *       party: it was consumed while the player was there, and reading it as unclaimed afterwards
 *       would hand out a second copy.</li>
 *   <li><b>Other members' claims do not</b> travel. They are other players' payouts; the merged
 *       record keeps the leaver's own row and the team's, and drops the rest.</li>
 *   <li><b>Unclaimed rewards stay unclaimed</b>, so a member who finished a quest and never got to
 *       press Claim can still press it from their own record afterwards. Per-player rewards are the
 *       player's; leaving the party does not confiscate one they were owed.</li>
 *   <li><b>Repeatable-round state</b> ({@code timesCompleted}, {@code lastCompletedAt},
 *       {@code rewardsClaimed}, {@code legacySettled}) travels at its furthest, so a cooldown that
 *       was running keeps running and an old save's "already paid" stays paid.</li>
 * </ul>
 *
 * <h2>The one direction that is not merged</h2>
 *
 * <p>The team-level {@code rewardsBlocked} flag is the player's own record's, not the party's. It is
 * an operator's statement about <i>that team</i>; carrying it into a player's solo record would let a
 * temporary block on a party follow somebody home.
 *
 * <h2>Game-free, so the cases can be enumerated</h2>
 *
 * <p>Everything here is records and maps, so {@code ProgressMergeTest} can assert the awkward
 * combinations -- a party further along, a solo record further along, both claiming the same reward --
 * without a world. That is the same argument the tinier {@code PartyMode.combine} makes, and this is
 * the merge where a mistake is silently permanent: there is no second pass over a record that has
 * already been written.
 */
public final class ProgressMerge {

    private ProgressMerge() {
    }

    /**
     * The player's own record, with everything the party's record has, kept at its best.
     *
     * @param own    the player's own record. Untouched by a party, which is why a merge is needed
     * @param party  the record of the party they are leaving
     * @param player whose claims are theirs to keep
     */
    public static TeamProgress merge(TeamProgress own, TeamProgress party, UUID player) {
        TeamProgress out = TeamProgress.empty().withRewardsBlocked(own.rewardsBlocked());
        Set<String> ids = new LinkedHashSet<>(own.storedIds());
        ids.addAll(party.storedIds());
        for (String id : ids) {
            out = out.withQuest(id, mergeQuest(own.progressOfId(id), party.progressOfId(id), player));
        }
        return out;
    }

    /**
     * One quest, merged. The unit {@link #merge} is a loop over.
     *
     * <p>Public because it is where the decisions are, and a test that had to build a whole
     * {@code TeamProgress} to check one claim rule would be testing the loop instead.
     */
    public static QuestProgress mergeQuest(QuestProgress own, QuestProgress party, UUID player) {
        QuestState state = own.state().isAtLeast(party.state()) ? own.state() : party.state();
        return new QuestProgress(
                state,
                maxTasks(own.taskProgress(), party.taskProgress()),
                mergeClaims(own.claims(), party.claims(), player),
                own.rewardsClaimed() || party.rewardsClaimed(),
                own.legacySettled() || party.legacySettled(),
                Math.max(own.timesCompleted(), party.timesCompleted()),
                Math.max(own.lastCompletedAt(), party.lastCompletedAt()));
    }

    /** The higher count for every task either side has. See the class note on the direction. */
    private static Map<Integer, Integer> maxTasks(Map<Integer, Integer> own, Map<Integer, Integer> party) {
        Map<Integer, Integer> out = new LinkedHashMap<>(own);
        party.forEach((index, amount) -> out.merge(index, amount, Math::max));
        return out;
    }

    /** The leaver's claims and the team's, and nobody else's. See the class note. */
    private static QuestClaims mergeClaims(QuestClaims own, QuestClaims party, UUID player) {
        Set<Integer> team = new LinkedHashSet<>(own.team());
        team.addAll(party.team());

        Map<UUID, Set<Integer>> players = new LinkedHashMap<>(own.players());
        Set<Integer> mine = new LinkedHashSet<>(own.players().getOrDefault(player, Set.of()));
        mine.addAll(party.players().getOrDefault(player, Set.of()));
        if (!mine.isEmpty()) {
            players.put(player, Set.copyOf(mine));
        }
        return new QuestClaims(team, players);
    }
}
