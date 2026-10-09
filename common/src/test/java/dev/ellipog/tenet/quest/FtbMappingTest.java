package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.reward.CommandReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.AdvancementTask;
import dev.ellipog.tenet.quest.task.ComponentMatch;
import dev.ellipog.tenet.quest.task.ItemTask;
import dev.ellipog.tenet.quest.task.KillTask;
import dev.ellipog.tenet.quest.task.LocationTask;
import dev.ellipog.tenet.quest.task.ObservationTask;
import dev.ellipog.tenet.quest.task.StatTask;
import dev.ellipog.tenet.quest.task.TaskTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FTB Quests rows that need no Tenet change, pinned as tests.
 *
 * <p>Batch 0 of the migration work verified that every row here already has a Tenet home, so the
 * migration tool may emit the middle column today: the FTB snake_case becomes Tenet's camelCase,
 * and an absent FTB field keeps Tenet's default. Each test decodes the JSON the tool would write
 * through the real codec, which is what makes this page fail first if a field quietly becomes
 * required or changes meaning. The human-readable contract is {@code docs/authoring/ftb-mapping.md};
 * this class is the same contract in a form the build checks.
 *
 * <p>What is deliberately <b>not</b> here: every row that still needs Tenet work (quest
 * {@code optional}, flexible progress, titles and icons, images, links, click actions, text
 * tokens, energy, toast and currency rewards, team stages, file settings, presets). Those belong
 * to later batches, and adding them here early would pin JSON nothing reads.
 */
@DisplayName("FTB Quests mappings that need no Tenet change")
class FtbMappingTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static <T> T decoded(DataResult<T> result, String what) {
        return result.result().orElseThrow(() -> new AssertionError(what + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static QuestTask task(String text) {
        return decoded(TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static QuestReward reward(String text) {
        return decoded(RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static Quest quest(String text) {
        return decoded(Quest.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static Chapter chapter(String text) {
        return decoded(Chapter.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static QuestSettings settings(String text) {
        return decoded(QuestSettings.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("advancement with a criterion decodes to that criterion")
    void advancementWithCriterion() {
        var parsed = assertInstanceOf(AdvancementTask.class,
                task("{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_stone\","
                        + " \"criterion\": \"get_stone\"}"));
        assertEquals("minecraft:story/mine_stone", parsed.advancement().toString());
        assertEquals(Optional.of("get_stone"), parsed.criterion());
    }

    @Test
    @DisplayName("advancement without a criterion watches the whole advancement")
    void advancementWithoutCriterion() {
        // The tool turns FTB's empty-string criterion into an absent field rather than an empty
        // one: absent is "the whole advancement" and an empty string would name a criterion that
        // does not exist.
        var parsed = assertInstanceOf(AdvancementTask.class,
                task("{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_stone\"}"));
        assertEquals(Optional.empty(), parsed.criterion());
    }

    @Test
    @DisplayName("observation carries its target, timer and observe type")
    void observationCarriesTargetTimerAndType() {
        var parsed = assertInstanceOf(ObservationTask.class,
                task("{\"type\": \"tenet:observation\", \"observeType\": \"block\","
                        + " \"toObserve\": \"minecraft:beacon\", \"timer\": 40}"));
        assertEquals(ObservationTask.ObserveType.BLOCK, parsed.observeType());
        assertEquals("minecraft:beacon", parsed.toObserve());
        assertEquals(40, parsed.timer());
    }

    @Test
    @DisplayName("observation defaults to watching a block for a second")
    void observationDefaults() {
        var parsed = assertInstanceOf(ObservationTask.class,
                task("{\"type\": \"tenet:observation\", \"toObserve\": \"minecraft:beacon\"}"));
        assertEquals(ObservationTask.ObserveType.BLOCK, parsed.observeType());
        assertEquals(20, parsed.timer());
    }

    @Test
    @DisplayName("stat carries its id and value")
    void statCarriesIdAndValue() {
        var parsed = assertInstanceOf(StatTask.class,
                task("{\"type\": \"tenet:stat\", \"stat\": \"minecraft:walk_one_cm\", \"value\": 1000}"));
        assertEquals("minecraft:walk_one_cm", parsed.stat().toString());
        assertEquals(1000, parsed.value());
    }

    @Test
    @DisplayName("kill carries tag, count, name and NBT filter")
    void killCarriesAllFilters() {
        var parsed = assertInstanceOf(KillTask.class,
                task("{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\","
                        + " \"entityTypeTag\": \"minecraft:undead\", \"value\": 5,"
                        + " \"customName\": \"Bob\", \"nbtFilter\": \"{Color: 4b}\"}"));
        assertEquals(Optional.of("minecraft:zombie"), parsed.entity().map(Object::toString));
        assertEquals(Optional.of("minecraft:undead"), parsed.entityTypeTag().map(Object::toString));
        assertEquals(5, parsed.value());
        assertEquals(Optional.of("Bob"), parsed.customName());
        assertEquals(Optional.of("{Color: 4b}"), parsed.nbtFilter());
    }

    @Test
    @DisplayName("item match speaks FTB's three words")
    void itemMatchSpeaksThreeWords() {
        // FTB's match_components vocabulary, identical on both sides; strict stays the default.
        for (var row : List.of(
                new String[]{"none", "NONE"}, new String[]{"fuzzy", "FUZZY"}, new String[]{"strict", "STRICT"})) {
            var parsed = assertInstanceOf(ItemTask.class,
                    task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"match\": \""
                            + row[0] + "\"}"));
            assertEquals(ComponentMatch.valueOf(row[1]), parsed.match(), "match " + row[0]);
        }
        assertEquals(ComponentMatch.STRICT,
                assertInstanceOf(ItemTask.class,
                        task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}")).match());
    }

    @Test
    @DisplayName("item onlyFromCrafting and consumeItems decode")
    void itemCraftingAndConsumeFlags() {
        var parsed = assertInstanceOf(ItemTask.class,
                task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\","
                        + " \"onlyFromCrafting\": true, \"consumeItems\": true}"));
        assertTrue(parsed.onlyFromCrafting());
        assertEquals(Optional.of(true), parsed.consumeItems());
        // Absent defers to the chapter default rather than pinning false: the Optional must stay
        // empty so the chapter's answer shows through.
        assertEquals(Optional.empty(),
                assertInstanceOf(ItemTask.class,
                        task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}"))
                        .consumeItems());
    }

    @Test
    @DisplayName("location carries its ignore-dimension flag")
    void locationIgnoreDimension() {
        var parsed = assertInstanceOf(LocationTask.class,
                task("{\"type\": \"tenet:location\", \"position\": [0, 64, 0], \"ignoreDimension\": true}"));
        assertTrue(parsed.ignoreDimension());
    }

    // ------------------------------------------------------------------
    // Rewards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reward auto speaks FTB's five words")
    void rewardAutoSpeaksFiveWords() {
        for (var row : List.of(
                new String[]{"default", "DEFAULT"}, new String[]{"disabled", "DISABLED"},
                new String[]{"enabled", "ENABLED"}, new String[]{"no_toast", "NO_TOAST"},
                new String[]{"invisible", "INVISIBLE"})) {
            var parsed = assertInstanceOf(ItemReward.class,
                    reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"auto\": \""
                            + row[0] + "\"}"));
            assertEquals(RewardAutoClaim.valueOf(row[1]), parsed.common().auto(), "auto " + row[0]);
        }
    }

    @Test
    @DisplayName("reward team is a tristate that defers when absent")
    void rewardTeamTristate() {
        assertEquals(Optional.of(true),
                assertInstanceOf(ItemReward.class, reward(
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"team\": true}"))
                        .common().team());
        assertEquals(Optional.of(false),
                assertInstanceOf(ItemReward.class, reward(
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"team\": false}"))
                        .common().team());
        assertEquals(Optional.empty(),
                assertInstanceOf(ItemReward.class,
                        reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"))
                        .common().team());
    }

    @Test
    @DisplayName("reward claim-all and reward-blocking flags decode")
    void rewardClaimAndBlockingFlags() {
        var parsed = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"excludeFromClaimAll\": true, \"ignoreRewardBlocking\": true}"));
        assertTrue(parsed.common().excludeFromClaimAll());
        assertTrue(parsed.common().ignoreRewardBlocking());
        var absent = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"));
        assertFalse(absent.common().excludeFromClaimAll());
        assertFalse(absent.common().ignoreRewardBlocking());
    }

    @Test
    @DisplayName("command silent decodes")
    void commandSilent() {
        var parsed = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\", \"silent\": true}"));
        assertTrue(parsed.silent());
        assertEquals(2, parsed.permissionLevel());
    }

    @Test
    @DisplayName("item onlyOne and randomBonus decode")
    void itemOnlyOneAndBonus() {
        var parsed = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"onlyOne\": true, \"randomBonus\": 3}"));
        assertTrue(parsed.onlyOne());
        assertEquals(3, parsed.randomBonus());
    }

    // ------------------------------------------------------------------
    // Quest, chapter and file rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quest sequentialTasks decodes")
    void questSequentialTasks() {
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"sequentialTasks\": true}").sequentialTasks());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").sequentialTasks());
    }

    @Test
    @DisplayName("quest hide flags keep their tristate")
    void questHideTristate() {
        // Absent is "no opinion" and the chapter default decides; false forces visible. Collapsing
        // absent into false would pin the chapter default the moment a quest is written.
        assertEquals(Optional.empty(),
                quest("{\"id\": \"a\", \"title\": \"A\"}").rules().hideUntilDependenciesComplete());
        assertEquals(Optional.of(true),
                quest("{\"id\": \"a\", \"title\": \"A\","
                        + " \"hideUntilDependenciesComplete\": true}").rules()
                        .hideUntilDependenciesComplete());
        assertEquals(Optional.of(false),
                quest("{\"id\": \"a\", \"title\": \"A\","
                        + " \"hideUntilDependenciesComplete\": false}").rules()
                        .hideUntilDependenciesComplete());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").rules().hideDependencyLines());
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\","
                + " \"hideDependencyLines\": true, \"hideTextUntilComplete\": true,"
                + " \"hideDetailsUntilStartable\": true, \"invisible\": true,"
                + " \"invisibleUntilTasks\": 2}").rules().hideTextUntilComplete());
    }

    @Test
    @DisplayName("chapter carries consume and hide defaults")
    void chapterDefaults() {
        var parsed = chapter("{\"id\": \"c\", \"title\": \"C\", \"defaultConsumeItems\": true,"
                + " \"defaultHideUntilDependenciesComplete\": true,"
                + " \"defaultHideUntilDependenciesVisible\": true}");
        assertTrue(parsed.defaultConsumeItems());
        assertTrue(parsed.rules().defaultHideUntilDependenciesComplete());
        assertTrue(parsed.rules().defaultHideUntilDependenciesVisible());
    }

    @Test
    @DisplayName("file settings carry auto-claim and team defaults")
    void fileSettings() {
        var parsed = settings("{\"defaultAutoClaim\": \"no_toast\", \"defaultTeamReward\": true,"
                + " \"detectionDelay\": 60}");
        assertEquals(RewardAutoClaim.NO_TOAST, parsed.defaultAutoClaim());
        assertTrue(parsed.defaultTeamReward());
        assertEquals(60, parsed.detectionDelay());
    }
}
