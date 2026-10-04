package dev.ellipog.tasked.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leaving a party: what the leaver's own record keeps.
 *
 * <h2>Why this is a pure test and not a playthrough</h2>
 *
 * <p>Because every rule here is a merge over records, and the cases that matter are combinations —
 * a solo record further along than the party, a party further along than the solo record, both sides
 * claiming the same reward, a round already over on one side only. A server test can act out one
 * combination per order; this enumerates them, and the playthrough then asserts the wiring (that a
 * leave <i>calls</i> this) rather than re-deriving the table.
 *
 * <p>The direction of every field is asserted in both directions on purpose. A merge that took the
 * party's value unconditionally, or the player's, would pass a one-sided test and silently erase
 * something for half the players who ever leave a party.
 */
class ProgressMergeTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static QuestProgress quest(QuestState state, int taskZero, int times, long last) {
        return new QuestProgress(state, Map.of(0, taskZero), QuestClaims.NONE, false, false, times, last);
    }

    @Test
    @DisplayName("a party further along carries the player's own record forward")
    void partyFurtherAlongWins() {
        TeamProgress own = TeamProgress.empty()
                .withQuest("punch", quest(QuestState.STARTED, 2, 0, 0L));
        TeamProgress party = TeamProgress.empty()
                .withQuest("punch", quest(QuestState.COMPLETED, 8, 1, 420L));

        QuestProgress merged = ProgressMerge.merge(own, party, ME).progressOfId("punch");

        assertEquals(QuestState.COMPLETED, merged.state(),
                "the party finished the chain, so the leaver keeps it -- this is the soft-lock fix");
        assertEquals(8, merged.progressOf(0), "and the counts, which dependents' gates read");
        assertEquals(1, merged.timesCompleted());
        assertEquals(420L, merged.lastCompletedAt());
    }

    @Test
    @DisplayName("a personal record further along is not erased by the party's")
    void personalRecordFurtherAlongIsKept() {
        TeamProgress own = TeamProgress.empty()
                .withQuest("mine", quest(QuestState.COMPLETED, 5, 0, 100L))
                .withQuest("shared", quest(QuestState.STARTED, 1, 0, 0L));
        TeamProgress party = TeamProgress.empty()
                .withQuest("shared", quest(QuestState.UNLOCKED, 0, 0, 0L))
                .withQuest("theirs", quest(QuestState.COMPLETED, 3, 0, 50L));

        TeamProgress merged = ProgressMerge.merge(own, party, ME);

        assertEquals(QuestState.COMPLETED, merged.progressOfId("mine").state(),
                "a quest only the player had stays");
        assertEquals(QuestState.COMPLETED, merged.progressOfId("theirs").state(),
                "a quest only the party had arrives");
        assertEquals(QuestState.STARTED, merged.progressOfId("shared").state(),
                "and on a quest both had, the further one wins -- not the party's, which is behind");
        assertEquals(1, merged.progressOfId("shared").progressOf(0));
    }

    @Nested
    @DisplayName("Claims")
    class Claims {

        @Test
        @DisplayName("the leaver's claims and the team's travel, and nobody else's do")
        void claimsTravel() {
            QuestClaims ownClaims = QuestClaims.NONE.withPlayerClaim(ME, 0);
            QuestClaims partyClaims = QuestClaims.NONE
                    .withTeamClaim(1)
                    .withPlayerClaim(ME, 2)
                    .withPlayerClaim(FRIEND, 3);
            QuestProgress own = new QuestProgress(QuestState.COMPLETED, Map.of(), ownClaims,
                    false, false, 0, 0L);
            QuestProgress party = new QuestProgress(QuestState.COMPLETED, Map.of(), partyClaims,
                    false, false, 0, 0L);

            QuestClaims merged = ProgressMerge.mergeQuest(own, party, ME).claims();

            assertTrue(merged.claimed(ME, 0, false), "a reward the player collected stays collected");
            assertTrue(merged.claimed(ME, 1, true),
                    "a team reward was one payout the player partook in -- keeping it marked is what "
                            + "stops a second copy being handed out after they leave");
            assertTrue(merged.claimed(ME, 2, false), "and one collected in the party travels too");
            assertFalse(merged.claimed(FRIEND, 3, false),
                    "another member's payout is not the leaver's to carry");
        }

        @Test
        @DisplayName("an unclaimed per-player reward stays claimable from the player's own record")
        void unclaimedRewardsStayUnclaimed() {
            // The other half of the rule: leaving must not confiscate a reward the player was owed.
            // Nothing marks it, so the state is COMPLETED and their own record can still pay it.
            QuestProgress party = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    false, false, 0, 0L);

            QuestClaims merged = ProgressMerge.mergeQuest(QuestProgress.NONE, party, ME).claims();

            assertFalse(merged.claimed(ME, 0, false),
                    "an unclaimed reward is still claimable -- per-player rewards belong to the player, "
                            + "and leaving is not confiscation");
            assertTrue(merged.isEmpty(), "nothing was invented to mark it otherwise");
        }
    }

    @Nested
    @DisplayName("Round state, at its furthest")
    class RoundState {

        @Test
        @DisplayName("timesCompleted and lastCompletedAt take the maximum, either side")
        void countersTakeTheMax() {
            QuestProgress own = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    false, false, 3, 900L);
            QuestProgress party = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    false, false, 5, 100L);

            QuestProgress merged = ProgressMerge.mergeQuest(own, party, ME);

            assertEquals(5, merged.timesCompleted());
            assertEquals(900L, merged.lastCompletedAt(),
                    "each takes its own maximum: a cooldown that was running keeps running, and a "
                            + "count is not reset by a record that happened to be younger");
        }

        @Test
        @DisplayName("rewardsClaimed and legacySettled are set if either side says so")
        void settledFlagsAreOr() {
            QuestProgress claimed = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    true, false, 0, 0L);
            QuestProgress legacy = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    false, true, 0, 0L);
            QuestProgress fresh = new QuestProgress(QuestState.COMPLETED, Map.of(), QuestClaims.NONE,
                    false, false, 0, 0L);

            assertTrue(ProgressMerge.mergeQuest(fresh, claimed, ME).rewardsClaimed(),
                    "a round that was over on either record is over -- this is what stops a repeatable "
                            + "being paid twice across the merge");
            assertTrue(ProgressMerge.mergeQuest(legacy, fresh, ME).legacySettled(),
                    "and a pre-per-player 'already paid' stays paid, or an old save pays out again");
            assertFalse(ProgressMerge.mergeQuest(fresh, fresh, ME).rewardsClaimed());
        }
    }

    @Test
    @DisplayName("the team-level rewards block is the player's own, not the party's")
    void rewardsBlockedIsPersonal() {
        TeamProgress blockedMine = TeamProgress.empty().withRewardsBlocked(true);
        TeamProgress blockedParty = TeamProgress.empty().withRewardsBlocked(true);
        TeamProgress plain = TeamProgress.empty();

        assertTrue(ProgressMerge.merge(blockedMine, plain, ME).rewardsBlocked(),
                "a block on the player's own record stays");
        assertFalse(ProgressMerge.merge(plain, blockedParty, ME).rewardsBlocked(),
                "and a temporary block on the party does not follow them home -- it is an operator's "
                        + "statement about that team, not about the player");
    }

    @Test
    @DisplayName("an empty party record merges to the player's record unchanged")
    void emptyPartyChangesNothing() {
        TeamProgress own = TeamProgress.empty()
                .withQuest("mine", quest(QuestState.STARTED, 2, 1, 50L));
        TeamProgress merged = ProgressMerge.merge(own, TeamProgress.empty(), ME);

        assertEquals(Set.of("mine"), merged.storedIds());
        assertEquals(own.progressOfId("mine"), merged.progressOfId("mine"),
                "one quest, byte for byte -- the case a party that never progressed takes");
    }

    @Test
    @DisplayName("a store round trip keeps the merged record's shape")
    void mergedRecordsAreStorable() {
        // The merge produces the same records the store already writes, and the assertion is that
        // nothing about the merged shape defeats the format -- a claim map keyed by a non-member, for
        // instance, would be loaded and then quietly ignored. Asserted through the real save/load so a
        // field added to QuestProgress later cannot be dropped here in silence.
        TeamProgress own = TeamProgress.empty()
                .withQuest("mine", quest(QuestState.STARTED, 1, 0, 0L));
        TeamProgress party = TeamProgress.empty()
                .withQuest("mine", quest(QuestState.COMPLETED, 8, 2, 300L)
                        .withClaims(QuestClaims.NONE.withPlayerClaim(ME, 1)));

        TeamProgress merged = ProgressMerge.merge(own, party, ME);
        TeamProgress back = TeamProgress.fromTag(merged.toTag());

        assertEquals(merged.progressOfId("mine"), back.progressOfId("mine"),
                "the merged quest survives a save and a load unchanged");
    }
}
