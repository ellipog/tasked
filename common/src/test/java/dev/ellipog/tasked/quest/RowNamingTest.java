package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tasked.quest.condition.ConditionDisplay;
import dev.ellipog.tasked.quest.condition.ConditionTypes;
import dev.ellipog.tasked.quest.condition.QuestCondition;
import dev.ellipog.tasked.quest.reward.RewardDisplay;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskDisplay;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every task and reward row, as English, through the registry sweep.
 *
 * <h2>The bug this exists to catch</h2>
 *
 * <p>A row is a sentence built from a translation key and one argument, and for a long time the
 * argument the client supplied was always the <b>count</b> -- so a stage task whose key was written
 * for a subject rendered as the bare number "1". Every type was wrong in its own way, the keys were
 * individually plausible, and nothing anywhere compared the two: the key lives in a resource file the
 * code never reads, and the argument is a number whose meaning depends on the key. This is that
 * comparison, made the only way it can be: build every registered type's display, read the key the
 * client will read, and check the sentence that comes out.
 *
 * <p>It asks the registry rather than a list, so a type registered tomorrow is covered the day it
 * registers -- and it is the file's own English that is checked, not the fallback, because the
 * fallback is the text the key shadows when it exists.
 */
@DisplayName("every task and reward row reads as a sentence")
class RowNamingTest {

    /** The language file the client would load, relative to the {@code common} module's test run. */
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "tasked", "lang",
            "en_us.json");

    /**
     * Keys under the row prefixes that are messages rather than row sentences.
     *
     * <p>A message is a whole sentence with no per-type argument -- the inventory-full notice -- so
     * there is no display it belongs to, and the reverse check below must not ask for one.
     */
    private static final Set<String> MESSAGE_KEYS = Set.of("tasked.reward.inventory_full_count",
            "tasked.reward.claim_done", "tasked.reward.claim_halted");

    /**
     * The keys whose sentence counts something, so the count is the argument and there is no subject.
     *
     * <p>Every other key is written for a subject -- a stage, a table, a mob -- and a type that sends
     * none would have its row formatted with the count, which is the reported "1" from the other side.
     *
     * <p>The singular level key is here as well as the plural one: the count decides which of the two
     * the row carries, and both are formatted with it.
     */
    private static final Set<String> COUNT_KEYS = Set.of("tasked.reward.xp.points",
            "tasked.reward.xp.levels", "tasked.reward.xp.level");

    @Test
    @DisplayName("every registered type's English row names its subject instead of showing a bare value")
    void everyTypeReadsAsASentence() throws IOException {
        Map<String, String> lang = lang();
        Set<String> used = new TreeSet<>();

        for (ResourceLocation id : TaskTypes.ids()) {
            List<JsonObject> trees = new ArrayList<>();
            trees.add(TaskTypes.defaultTree(id).orElseThrow(() ->
                    new AssertionError(id + " has no default tree")));
            for (JsonObject tree : trees) {
                QuestTask task = TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree)
                        .getOrThrow(error -> new AssertionError(id + " did not decode: " + error));
                TaskDisplay display = TaskTypes.displayOf(task);
                if (display.item().isPresent()) {
                    // The row is the item's own name, which the client looks up in its own language.
                    // There is no key of ours to check, and nothing that could render as a number.
                    continue;
                }
                if (lang.containsKey(display.label())) {
                    used.add(display.label());
                    assertSendsItsSubject(id, display.label(), display.labelArg());
                }
                assertSentence(id, display.label(), display.labelFallback(),
                        display.labelArg().isEmpty() ? String.valueOf(display.count()) : display.labelArg(),
                        lang);
            }
        }

        for (ResourceLocation id : RewardTypes.ids()) {
            List<JsonObject> trees = new ArrayList<>();
            trees.add(RewardTypes.defaultTree(id).orElseThrow(() ->
                    new AssertionError(id + " has no default tree")));
            // Two reward types write a different key for a different setting -- a stage taken away,
            // experience given as levels -- and a sweep that built only the default would leave the
            // other key looking unused by the reverse check below.
            if (id.getPath().equals("stage")) {
                trees.add(with(trees.get(0), "remove", true));
            }
            if (id.getPath().equals("xp")) {
                // Both level keys, and the count is what chooses between them: a sweep that built only
                // one of the two would leave the other looking unused by the reverse check below.
                trees.add(with(with(trees.get(0), "levels", true), "amount", 3));
                trees.add(with(with(trees.get(0), "levels", true), "amount", 1));
            }
            for (JsonObject tree : trees) {
                QuestReward reward = RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree)
                        .getOrThrow(error -> new AssertionError(id + " did not decode: " + error));
                RewardDisplay display = RewardTypes.displayOf(reward);
                if (display.item().isPresent()) {
                    continue;
                }
                if (lang.containsKey(display.label())) {
                    used.add(display.label());
                    assertSendsItsSubject(id, display.label(), display.labelArg());
                }
                assertSentence(id, display.label(), display.labelFallback(),
                        display.labelArg().isEmpty() ? String.valueOf(display.count()) : display.labelArg(),
                        lang);
            }
        }

        for (ResourceLocation id : ConditionTypes.ids()) {
            JsonObject tree = ConditionTypes.defaultTree(id).orElseThrow(() ->
                    new AssertionError(id + " has no default tree"));
            QuestCondition condition = ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE, tree)
                    .getOrThrow(error -> new AssertionError(id + " did not decode: " + error));
            ConditionDisplay display = ConditionTypes.displayOf(condition);
            if (display.item().isPresent()) {
                // An item condition draws the item's own name, as an item task does. There is no key of
                // ours to check and nothing that could render as a number.
                continue;
            }
            if (lang.containsKey(display.label())) {
                used.add(display.label());
                assertSendsItsSubject(id, display.label(), display.labelArg());
            }
            // A condition has no count to fall back on: its sentence's number rides the subject, so the
            // subject is what the key is checked against. A condition type that sent none would be
            // caught by assertSendsItsSubject above whenever its key exists.
            assertSentence(id, display.label(), display.labelFallback(),
                    display.labelArg().isEmpty() ? display.label() : display.labelArg(), lang);
        }

        // The other direction: a row key the file holds and no type produces is a leftover of a rename
        // -- and the old `tasked.reward.stage` was exactly that after the sentences landed.
        Set<String> fileRowKeys = new TreeSet<>();
        for (String key : lang.keySet()) {
            if ((key.startsWith("tasked.task.") || key.startsWith("tasked.reward.")
                    || key.startsWith("tasked.condition."))
                    && !MESSAGE_KEYS.contains(key)) {
                fileRowKeys.add(key);
            }
        }
        assertEquals(fileRowKeys, used, "the file's row keys and the registered types' keys must agree");
    }

    /**
     * The text the player reads, and the three things it must be.
     *
     * <p>The key says more than its argument (a key that is only the placeholder is not a sentence --
     * it is the reported row with a subject instead of a number in it), the row is not a bare value
     * (the original report), and the key takes exactly the one argument the client supplies (a key with
     * no placeholder would silently drop the subject, which is the same "1" from the other side).
     */
    private static void assertSentence(ResourceLocation type, String key, String fallback, String supplied,
                                       Map<String, String> lang) {
        String template = lang.get(key);
        if (template != null) {
            assertEquals(1, occurrences(template, "%s"),
                    type + "'s key " + key + " must take exactly the one argument the row supplies: "
                            + template);
            String text = template.replace("%s", supplied);
            assertTrue(text.length() > supplied.length(),
                    type + "'s key " + key + " is only its argument, so the row is not a sentence: \""
                            + text + "\"");
            assertTrue(hasLetters(text), type + " reads as a bare value: \"" + text + "\"");
            return;
        }
        // A key this mod does not own: a checkmark's title is the author's own words, and an addon's
        // display is its own. What the player reads is the fallback -- or the literal, when the display
        // carries no fallback at all -- so that is what has to be a sentence.
        String text = fallback.isEmpty() ? key : fallback;
        assertTrue(hasLetters(text),
                type + " has no translation and its fallback is not a sentence: \"" + text + "\"");
    }

    /**
     * A type whose key is written for a subject must send one.
     *
     * <p>The other half of the "1": the client formats the key with the count when there is no subject,
     * so a display that forgot to pass one reads as a number no matter how the key is written. The two
     * count-shaped keys are the only ones that mean it.
     */
    private static void assertSendsItsSubject(ResourceLocation type, String key, String labelArg) {
        if (!COUNT_KEYS.contains(key)) {
            assertFalse(labelArg.isEmpty(), type + " sends no subject, so " + key
                    + " would be formatted with the count");
        }
    }

    private static JsonObject with(JsonObject tree, String field, boolean value) {
        JsonObject copy = tree.deepCopy();
        copy.addProperty(field, value);
        return copy;
    }

    private static JsonObject with(JsonObject tree, String field, int value) {
        JsonObject copy = tree.deepCopy();
        copy.addProperty(field, value);
        return copy;
    }

    private static Map<String, String> lang() throws IOException {
        JsonObject json = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8))
                .getAsJsonObject();
        Map<String, String> lang = new TreeMap<>();
        for (Map.Entry<String, JsonElement> each : json.entrySet()) {
            lang.put(each.getKey(), each.getValue().getAsString());
        }
        return lang;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static boolean hasLetters(String text) {
        return text.chars().anyMatch(Character::isLetter);
    }
}
