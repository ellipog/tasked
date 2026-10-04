package dev.ellipog.tasked.progress;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tasked.quest.QuestRules;
import dev.ellipog.tasked.quest.QuestSettings;
import dev.ellipog.tasked.quest.reward.RewardAutoClaim;
import dev.ellipog.tasked.quest.reward.RewardCommon;
import dev.ellipog.tasked.quest.reward.TableReward;
import dev.ellipog.tasked.quest.reward.XpReward;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reward-claiming foundations: per-player provenance, the event-progress add, and the tree's
 * settings.
 *
 * <p>All game-free. The server-side flow those foundations feed — two players each collecting their
 * own copy, team rewards settling once, auto-claim to every member — is exercised by the playthrough
 * tests, which have real players to pay.
 */
@DisplayName("the reward claim foundations")
class QuestClaimTest {

    // ------------------------------------------------------------------
    // Per-player provenance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one player's claim is not another's, and neither is a team claim")
    void playerClaimsAreIndependent() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        QuestClaims claims = QuestClaims.NONE.withPlayerClaim(alice, 0);
        assertTrue(claims.claimed(alice, 0, false), "a player collects their own copy");
        assertFalse(claims.claimed(bob, 0, false), "and a teammate's claim is not theirs");
        assertFalse(claims.claimed(alice, 0, true),
                "a personal claim must not settle a team-mode reward");

        QuestClaims both = claims.withPlayerClaim(bob, 0);
        assertTrue(both.claimed(alice, 0, false));
        assertTrue(both.claimed(bob, 0, false));
    }

    @Test
    @DisplayName("a team claim settles the reward for everyone, and is not a personal claim")
    void teamClaimsSettleForEveryone() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        QuestClaims claims = QuestClaims.NONE.withTeamClaim(1);
        assertTrue(claims.claimed(alice, 1, true));
        assertTrue(claims.claimed(bob, 1, true), "one claim settles it for the team");
        assertFalse(claims.claimed(alice, 1, false),
                "and a team claim is not a personal one -- the maps do not bleed");
    }

    // ------------------------------------------------------------------
    // The auto-claim ladder: reward > quest > chapter > pack > off
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reward's own mode wins, and `default` asks the level below")
    void aRewardsOwnModeWins() {
        assertEquals(RewardAutoClaim.ENABLED, RewardAutoClaim.ENABLED.resolved(RewardAutoClaim.DISABLED),
                "an explicit reward mode is not overruled by anything below it");
        assertEquals(RewardAutoClaim.DISABLED, RewardAutoClaim.DEFAULT.resolved(RewardAutoClaim.DISABLED),
                "and `default` is exactly the deferral");
        assertEquals(RewardAutoClaim.DEFAULT,
                RewardCommon.DEFAULT.autoClaim(RewardAutoClaim.DEFAULT),
                "a reward that says nothing arrives as `default`, which is the state the ladder needs");
    }

    @Test
    @DisplayName("the quest's mode overrides the chapter's, and unset falls through to it")
    void theQuestOverridesTheChapter() {
        QuestRules stated = QuestRules.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"autoClaim\": \"no_toast\"}")).getOrThrow();
        assertEquals(RewardAutoClaim.NO_TOAST, stated.autoClaim(RewardAutoClaim.ENABLED),
                "the quest's own mode wins over the chapter's");

        QuestRules silent = QuestRules.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{}")).getOrThrow();
        assertEquals(RewardAutoClaim.ENABLED, silent.autoClaim(RewardAutoClaim.ENABLED),
                "a quest that says nothing takes the chapter's mode");

        // And the chapter's own default defers to the pack setting, which is what makes the middle rung
        // of the ladder a real state rather than a pinned value.
        assertEquals(RewardAutoClaim.INVISIBLE,
                RewardAutoClaim.DEFAULT.resolved(RewardAutoClaim.INVISIBLE));
    }

    @Test
    @DisplayName("a choice reward cannot be auto-granted; every other table mode can")
    void aChoiceIsNeverAutoGranted() {
        // The bug this pins: an automatic mode used to select a choice reward, mark it collected and
        // then grant nothing -- the offer never happened, so the reward was silently lost. The engine
        // now skips these and leaves them outstanding for the claim flow.
        TableReward choice = new TableReward(RewardCommon.DEFAULT, TableReward.Mode.CHOICE,
                Optional.empty(), Optional.empty());
        assertFalse(choice.autoGrantable(), "a choice's payout is the player's pick");

        for (TableReward.Mode mode : TableReward.Mode.values()) {
            if (mode == TableReward.Mode.CHOICE) {
                continue;
            }
            assertTrue(new TableReward(RewardCommon.DEFAULT, mode, Optional.empty(), Optional.empty())
                            .autoGrantable(),
                    mode + " rolls or lists its entries and pays immediately");
        }
        assertTrue(new XpReward(RewardCommon.DEFAULT, 10, false).autoGrantable(),
                "and an ordinary reward is automatic by default");
    }

    @Test
    @DisplayName("a repeatable round clears the claims with the task progress")
    void aNewRoundStartsUnclaimed() {
        UUID alice = UUID.randomUUID();
        QuestProgress progress = QuestProgress.NONE
                .recordTask(0, 4)
                .withClaims(QuestClaims.NONE.withPlayerClaim(alice, 0))
                .withRewardsClaimed(true)
                .resetTasks();

        assertFalse(progress.rewardsClaimed());
        assertTrue(progress.claims().isEmpty());
        assertTrue(progress.taskProgress().isEmpty());
        assertEquals(1, progress.timesCompleted());
    }

    @Test
    @DisplayName("event progress adds upwards and never lowers what a poll recorded")
    void addTaskOnlyRaises() {
        QuestProgress progress = QuestProgress.NONE.recordTask(0, 5);

        assertEquals(6, progress.addTask(0, 1).progressOf(0));
        assertEquals(5, progress.addTask(0, -3).progressOf(0), "a negative delta is not a way down");
        assertEquals(5, progress.addTask(0, 0).progressOf(0));
        assertEquals(2, progress.addTask(1, 2).progressOf(1), "a fresh index starts from zero");
    }

    // ------------------------------------------------------------------
    // RewardCommon
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reward that says nothing defers its team and auto axes, and defaults the flags")
    void rewardCommonDefaults() {
        RewardCommon parsed = RewardCommon.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{}"))
                .getOrThrow();

        assertEquals(RewardCommon.DEFAULT, parsed);
        assertTrue(parsed.team().isEmpty(), "silence about the team is what the file default fills");
        assertEquals(RewardAutoClaim.DEFAULT, parsed.auto());
        assertFalse(parsed.excludeFromClaimAll());
        assertFalse(parsed.ignoreRewardBlocking());
    }

    @Test
    @DisplayName("the auto mode resolves to the file default, and an explicit value beats it")
    void autoModesResolve() {
        RewardCommon explicit = RewardCommon.MAP_CODEC.codec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"auto\":\"no_toast\",\"team\":true}"))
                .getOrThrow();

        assertEquals(RewardAutoClaim.NO_TOAST, explicit.autoClaim(RewardAutoClaim.DISABLED));
        assertTrue(explicit.teamReward(false), "an explicit team flag beats the file default");
        assertEquals(RewardAutoClaim.DISABLED,
                RewardCommon.DEFAULT.autoClaim(RewardAutoClaim.DISABLED));
        assertEquals(RewardAutoClaim.ENABLED,
                RewardCommon.DEFAULT.autoClaim(RewardAutoClaim.ENABLED),
                "the default mode defers, it does not mean disabled");
        assertTrue(RewardAutoClaim.INVISIBLE.automatic());
        assertFalse(RewardAutoClaim.DISABLED.automatic());
        assertTrue(RewardAutoClaim.ENABLED.notifies());
        assertFalse(RewardAutoClaim.NO_TOAST.notifies());
    }

    // ------------------------------------------------------------------
    // The tree's settings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tree with no index, or no settings block, gets the defaults")
    void settingsFallBack(@TempDir Path root) throws Exception {
        assertEquals(QuestSettings.DEFAULTS, QuestSettings.load(root));

        Files.writeString(root.resolve("index.json"), "{\"entries\":[]}");
        assertEquals(QuestSettings.DEFAULTS, QuestSettings.load(root));
    }

    @Test
    @DisplayName("the settings block is read, and an unknown value falls back rather than failing")
    void settingsAreRead(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("index.json"), """
                {
                  "entries": [],
                  "settings": {
                    "defaultAutoClaim": "enabled",
                    "defaultTeamReward": true,
                    "suppressAllAutoclaiming": true,
                    "detectionDelay": 40
                  }
                }
                """);

        QuestSettings read = QuestSettings.load(root);
        assertEquals(RewardAutoClaim.ENABLED, read.defaultAutoClaim());
        assertTrue(read.defaultTeamReward());
        assertTrue(read.suppressAllAutoclaiming());
        assertEquals(40, read.detectionDelay());

        Files.writeString(root.resolve("index.json"), """
                {"entries": [], "settings": {"defaultAutoClaim": "sometimes"}}
                """);
        assertEquals(QuestSettings.DEFAULTS, QuestSettings.load(root),
                "a value this build does not know leaves the defaults, and the tree still loads");
    }

    // ------------------------------------------------------------------
    // Persistence of the claims
    // ------------------------------------------------------------------

    @Test
    @DisplayName("per-player and team claims, and the blocked flag, survive a save and load")
    void claimsRoundTrip() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        QuestClaims claims = QuestClaims.NONE
                .withTeamClaim(2)
                .withPlayerClaim(alice, 0)
                .withPlayerClaim(bob, 1);

        TeamProgress saved = TeamProgress.empty()
                .withRewardsBlocked(true)
                .withQuest("some_quest", QuestProgress.NONE.recordTask(2, 7).withClaims(claims));

        TeamProgress loaded = TeamProgress.fromTag(saved.toTag());
        assertTrue(loaded.rewardsBlocked());
        QuestProgress progress = loaded.progressOfId("some_quest");
        assertEquals(Set.of(2), progress.claims().team());
        assertEquals(Set.of(0), progress.claims().players().get(alice));
        assertEquals(Set.of(1), progress.claims().players().get(bob));
        assertEquals(7, progress.progressOf(2));
    }

    @Test
    @DisplayName("a version-1 file that was settled reads as settled for everyone")
    void versionOneStillReads() {
        // The shape a version-1 save has: no claims at all, one boolean. Written by hand because the
        // old writer is gone, and the point is that the reader still understands it -- and that the
        // one thing a migration must never do, pay an old quest again, cannot happen.
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("quest", "old_quest");
        entry.putString("state", "COMPLETED");
        entry.putBoolean("rewardsClaimed", true);
        entry.putInt("timesCompleted", 1);
        entry.put("tasks", new ListTag());
        list.add(entry);
        root.put("quests", list);

        TeamProgress loaded = TeamProgress.fromTag(root);
        QuestProgress progress = loaded.progressOfId("old_quest");
        assertTrue(progress.rewardsClaimed());
        assertTrue(progress.legacySettled(), "old settled means settled for everyone");
        assertTrue(progress.claims().isEmpty());
        assertFalse(loaded.rewardsBlocked(), "an absent flag is not blocked");
    }

    @Test
    @DisplayName("a version-2 file's flat claimed array reads as team claims")
    void versionTwoStillReads() {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 2);
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("quest", "v2_quest");
        entry.putString("state", "COMPLETED");
        entry.putBoolean("rewardsClaimed", true);
        entry.putIntArray("claimedRewards", new int[] {1});
        entry.put("tasks", new ListTag());
        list.add(entry);
        root.put("quests", list);

        TeamProgress loaded = TeamProgress.fromTag(root);
        QuestProgress progress = loaded.progressOfId("v2_quest");
        assertEquals(Set.of(1), progress.claims().team());
        assertTrue(progress.legacySettled());
    }

    @Test
    @DisplayName("a version-3 file's settled flag is the round flag, not a legacy lockdown")
    void versionThreeSettledIsNotLegacy() {
        UUID alice = UUID.randomUUID();
        TeamProgress saved = TeamProgress.empty().withQuest("q",
                QuestProgress.NONE.withClaims(QuestClaims.NONE.withPlayerClaim(alice, 0))
                        .withRewardsClaimed(true));

        QuestProgress loaded = TeamProgress.fromTag(saved.toTag()).progressOfId("q");
        assertTrue(loaded.rewardsClaimed(), "the round flag survives");
        assertFalse(loaded.legacySettled(), "and a new file's flag is not read as the old one");
    }
}
