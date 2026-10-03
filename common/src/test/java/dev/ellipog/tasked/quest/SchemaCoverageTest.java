package dev.ellipog.tasked.quest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tasked.quest.condition.ConditionTypes;
import dev.ellipog.tasked.quest.loot.RewardTable;
import dev.ellipog.tasked.quest.reward.RewardCommon;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two published schemas against the registries: every field the code has is a field the schemas document.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The schemas are hand-written, and every feature lands fields in code that the schema does not learn
 * about on its own -- the stage work is only the latest example. Drift is invisible from either side: the
 * loader never reads the schema, and a schema that is missing a field still validates every file that does
 * not use it. So an author's editor offers autocomplete for the types of two features ago, and nothing
 * anywhere says so.
 *
 * <h2>What is checked, and in which direction</h2>
 *
 * <p>Coverage, not equality of prose: every field of every registered task and reward type, both common
 * tails, the reward table's own fields, and every quest-level rule must appear in both files. The reverse
 * direction is checked only for {@code anyType} -- the schema must not invent a field the code does not
 * read -- because the quest block legitimately documents identity and layout fields (title, x, shape) that
 * live on the Quest codec rather than on QuestRules.
 *
 * <p>Paths are repository-relative, like the playthrough harness's: the Gradle test working directory is
 * the {@code common} module, so {@code ..} is the repository root.
 */
@DisplayName("the published schemas know every field the code does")
class SchemaCoverageTest {

    /** The folder format's quest schema, under the worked examples it describes. */
    private static final Path PER_KIND = Path.of("..", "tools", "quests", "_schema", "quest.schema.json");

    /** The one-file format's published schema, which is the reference until the authoring guide lands. */
    private static final Path PUBLISHED = Path.of("..", "docs", "tasked-quests.schema.json");

    @Test
    @DisplayName("every registered task and reward field is documented, in both schemas")
    void everyRegisteredFieldIsDocumented() throws IOException {
        Set<String> expected = registeredFields();

        for (Path schema : List.of(PER_KIND, PUBLISHED)) {
            Set<String> documented = anyTypeFields(schema);

            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(documented);
            assertTrue(missing.isEmpty(), () -> schema + " does not document these fields, which the "
                    + "registries have: " + missing);

            // And the other way, which is what catches a field deleted from the code and left in the
            // schema: an editor would keep offering it, and the loader would keep reporting it as
            // unknown. "type" is the one field here that is not any type's -- it is the dispatch.
            Set<String> invented = new TreeSet<>(documented);
            invented.removeAll(expected);
            invented.remove("type");
            assertTrue(invented.isEmpty(), () -> schema + " documents fields no registered type has: "
                    + invented);
        }
    }

    @Test
    @DisplayName("the reward table's own fields are documented, in both schemas")
    void theTableIsDocumented() throws IOException {
        for (Path schema : List.of(PER_KIND, PUBLISHED)) {
            JsonObject table = read(schema).getAsJsonObject("definitions").getAsJsonObject("rewardTable");

            Set<String> documented = table.getAsJsonObject("properties").keySet();
            Set<String> missing = new TreeSet<>(RewardTable.FIELDS);
            missing.removeAll(documented);
            assertTrue(missing.isEmpty(), () -> schema + " does not document the table's fields: " + missing);

            Set<String> entryFields = table.getAsJsonObject("properties").getAsJsonObject("entries")
                    .getAsJsonObject("items").getAsJsonObject("properties").keySet();
            Set<String> missingEntry = new TreeSet<>(RewardTable.Entry.FIELDS);
            missingEntry.removeAll(entryFields);
            assertTrue(missingEntry.isEmpty(), () -> schema + " does not document a table entry's fields: "
                    + missingEntry);
        }
    }

    @Test
    @DisplayName("every quest-level rule is documented, in both schemas")
    void everyRuleIsDocumented() throws IOException {
        for (Path schema : List.of(PER_KIND, PUBLISHED)) {
            Set<String> documented = questFields(schema);

            Set<String> missing = new TreeSet<>(QuestRules.FIELDS);
            missing.removeAll(documented);
            assertTrue(missing.isEmpty(), () -> schema + " does not document these quest fields: " + missing);
        }
    }

    @Test
    @DisplayName("every condition field is documented, in both schemas, in the condition definition")
    void theConditionFamilyIsDocumented() throws IOException {
        Set<String> expected = new TreeSet<>();
        for (ResourceLocation id : ConditionTypes.ids()) {
            expected.addAll(ConditionTypes.fieldsOf(id));
        }

        for (Path schema : List.of(PER_KIND, PUBLISHED)) {
            // The condition family is a definition of its own rather than properties of anyType: a
            // condition's fields live one level down, and documenting them at the task level would put
            // names in anyType that no task or reward reads -- which the reverse check above refuses.
            JsonObject condition = read(schema).getAsJsonObject("definitions").getAsJsonObject("condition");
            Set<String> documented = condition.getAsJsonObject("properties").keySet();

            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(documented);
            assertTrue(missing.isEmpty(), () -> schema + " does not document these condition fields: "
                    + missing);

            Set<String> invented = new TreeSet<>(documented);
            invented.removeAll(expected);
            invented.remove("type");
            assertTrue(invented.isEmpty(), () -> schema + " documents condition fields no registered "
                    + "type has: " + invented);
        }
    }

    /** Every field any registered type can read: the type's own, and the tail that rides on all of them. */
    private static Set<String> registeredFields() {
        Set<String> fields = new TreeSet<>(TaskCommon.FIELDS);
        for (ResourceLocation id : TaskTypes.ids()) {
            fields.addAll(TaskTypes.fieldsOf(id));
        }

        // RewardTypes.fieldsOf already unions RewardCommon in at registration; adding it again is a
        // no-op, and it keeps this test's source of truth the same two constants the codec uses.
        fields.addAll(RewardCommon.FIELDS);
        for (ResourceLocation id : RewardTypes.ids()) {
            fields.addAll(RewardTypes.fieldsOf(id));
        }
        return fields;
    }

    private static Set<String> anyTypeFields(Path schema) throws IOException {
        return read(schema).getAsJsonObject("definitions").getAsJsonObject("anyType")
                .getAsJsonObject("properties").keySet();
    }

    /**
     * A quest's own fields: the file's root properties in the folder format, and the nested
     * {@code definitions.quest} in the published one-file format.
     */
    private static Set<String> questFields(Path schema) throws IOException {
        JsonObject root = read(schema);
        JsonObject definitions = root.getAsJsonObject("definitions");
        JsonObject holder = definitions != null && definitions.has("quest")
                ? definitions.getAsJsonObject("quest")
                : root;
        return holder.getAsJsonObject("properties").keySet();
    }

    private static JsonObject read(Path schema) throws IOException {
        assertTrue(Files.isRegularFile(schema), () -> "no schema at " + schema.toAbsolutePath()
                + " -- this test reads the repository, so its working directory must be the common module");
        return JsonParser.parseString(Files.readString(schema)).getAsJsonObject();
    }
}
