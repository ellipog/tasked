package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.XpReward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fields each type declares, asserted against the classes that declare them.
 *
 * <h2>Why this exists rather than trusting the generator's own table</h2>
 *
 * <p>{@code tools/stress_quests.py} carries a hand-written table of "what each task type accepts", because
 * the shipped JSON schemas validate the *envelope* and cannot know that {@code tenet:xp} wants {@code value}
 * while {@code tenet:item} wants {@code count}. That table was wrong the first time it was written — it said
 * {@code amount}, and {@code tenet:statistic} — and a 1034-file pack loaded 98 of them, with
 * {@code these fields do not form a tenet:xp} as the only clue.
 *
 * <p>A hand-copied table drifts. So this asserts the mod's own {@code FIELDS} sets against the names the
 * generator writes, and a change to either side fails here rather than in a pack that quietly does not load.
 * The generator's copy is read out of the Python source, which is the only way a JUnit test can see it.
 */
@DisplayName("the task and reward field names a generated pack may use")
class GeneratedPackFieldNamesTest {

    /**
     * One `NAME = { ... }` table from the generator, by brace matching.
     *
     * <p>Brace matching and not `indexOf("}")`, which is the bug this had: the tables hold a `{...}` per
     * type, so the first `}` closes a *field set* rather than the table, and the parse silently produced an
     * empty table for every entry. A guard that reads nothing and passes would be worse than no guard, so the
     * parse is asserted non-empty by its callers.
     */
    private static String generatorTable(String name) {
        String text = generatorSource();
        int open = text.indexOf(name + " = {");
        assertTrue(open >= 0, "the generator no longer declares " + name + "; this guard is stale");
        open = text.indexOf('{', open);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    /** The types a table declares, with the fields each names. */
    private static java.util.Map<String, Set<String>> tableEntries(String name) {
        String table = generatorTable(name);
        java.util.Map<String, Set<String>> parsed = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher each = java.util.regex.Pattern
                .compile("\"([a-z_]+:[a-z_]+)\":\\s*\\{([^}]*)\\}").matcher(table);
        while (each.find()) {
            Set<String> fields = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher field = java.util.regex.Pattern
                    .compile("\"([a-zA-Z]+)\"").matcher(each.group(2));
            while (field.find()) {
                fields.add(field.group(1));
            }
            parsed.put(each.group(1), fields);
        }
        assertTrue(!parsed.isEmpty(), "parsed no entries out of " + name + "; the guard would pass vacuously");
        return parsed;
    }

    /** The generator's field set for one type in one table. */
    private static Set<String> generatorFields(String table, String type) {
        Set<String> fields = tableEntries(table).get(type);
        assertTrue(fields != null, "the generator declares no " + type + " in " + table);
        return fields;
    }

    @Test
    @DisplayName("every type the generator writes is one the mod actually registers")
    void everyTypeTheGeneratorWritesIsRegistered() {
        // The list the generator carries, against the constants the classes declare. A type renamed on
        // either side fails here.
        Set<String> tasks = tableEntries("TASK_FIELDS").keySet();
        Set<String> rewards = tableEntries("REWARD_FIELDS").keySet();
        assertTrue(tasks.contains(XpTask.TYPE.toString()), "the generator must know task " + XpTask.TYPE);
        assertTrue(tasks.contains(StatTask.TYPE.toString()), "the generator must know task " + StatTask.TYPE);
        assertTrue(tasks.contains(KillTask.TYPE.toString()), "the generator must know task " + KillTask.TYPE);
        assertTrue(tasks.contains(ItemTask.TYPE.toString()), "the generator must know task " + ItemTask.TYPE);
        assertTrue(rewards.contains(XpReward.TYPE.toString()), "the generator must know reward " + XpReward.TYPE);
        assertTrue(rewards.contains(ItemReward.TYPE.toString()),
                "the generator must know reward " + ItemReward.TYPE);
    }

    private static String generatorSource() {
        // **Found by walking up, not by a fixed relative path.** Gradle runs a module's tests with that
        // module as the working directory, so `tools/...` is right from `tenet/` and wrong from
        // `tenet/common/` -- and a guard that cannot find the file it guards is worse than none, because
        // it fails for a reason that has nothing to do with what it checks.
        java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path script = null;
        for (java.nio.file.Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            java.nio.file.Path found = candidate.resolve("tools").resolve("stress_quests.py");
            if (java.nio.file.Files.exists(found)) {
                script = found;
                break;
            }
        }
        if (script == null) {
            throw new AssertionError("cannot find tools/stress_quests.py above " + here);
        }
        try {
            return java.nio.file.Files.readString(script);
        }
        catch (java.io.IOException missing) {
            throw new AssertionError("cannot read the generator at " + script, missing);
        }
    }

    @Test
    @DisplayName("the statistic task is `stat` and not `statistic`, which is what the pack got wrong")
    void theStatisticTaskIsNamedStat() {
        // Named as its own case because this is the exact mistake: the generator wrote `tenet:statistic`,
        // which is not a registered type, and the loader reported it as an unknown type rather than as a
        // misspelling. The class is `StatTask` and its id is `tenet:stat`.
        assertEquals("tenet:stat", StatTask.TYPE.toString());
        assertEquals(StatTask.FIELDS, generatorFields("TASK_FIELDS", "tenet:stat"),
                "the generator's fields for the statistic task must be the ones its codec declares");
    }

    @Test
    @DisplayName("the xp task counts by `value`, which is the other half of the same mistake")
    void theXpTaskCountsByValue() {
        assertTrue(XpTask.FIELDS.contains("value"),
                "`tenet:xp` declares `value`; the generator wrote `amount` and every xp row was refused");
        assertTrue(!XpTask.FIELDS.contains("amount"),
                "and it does not accept `amount`, which is what made the refusal silent");
        assertEquals(XpTask.FIELDS, generatorFields("TASK_FIELDS", "tenet:xp"),
                "the generator must write exactly the fields the xp task declares");
    }

    @Test
    @DisplayName("the xp reward counts by `amount`, and that is a different field from the xp task's")
    void theXpRewardCountsByAmount() {
        // **The trap.** Both are `tenet:xp` and they are separate registries with separate codecs: the task
        // counts by `value` and the reward by `amount`. One table keyed on the id could only be right for
        // one of them, so the generator has two -- and this asserts they really do differ, because if they
        // ever became the same field the two tables would be a distinction worth deleting.
        assertTrue(XpReward.FIELDS.contains("amount"),
                "the xp reward counts by `amount`");
        assertTrue(!XpReward.FIELDS.contains("value"),
                "and not by the `value` its task counterpart uses");
        assertEquals(XpReward.FIELDS, generatorFields("REWARD_FIELDS", "tenet:xp"),
                "the generator's reward fields must be the ones the reward codec declares");
    }

    @Test
    @DisplayName("the item reward names its item, and the generator writes the reward table's own fields")
    void rewardFieldsMatch() {
        assertTrue(ItemReward.FIELDS.contains("item"), "the item reward names its item");
        assertTrue(generatorFields("REWARD_FIELDS", "tenet:item").contains("item"),
                "and the generator writes that field for a reward, not the task table's `count`");
    }
}
