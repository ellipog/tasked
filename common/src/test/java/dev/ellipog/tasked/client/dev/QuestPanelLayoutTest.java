package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest panel's rows, from a quest's own tree.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>Everything the panel promises an author: every section in order, a field row per field the format
 * has, a task's own fields from the registry the validator reads, an unknown type shown as what it is
 * rather than as nothing, and a folded section gone but for its heading. All of it is answerable with a
 * parsed JSON object and no client -- which is why the rows are built by a game-free class and the
 * drawing is not.
 */
@DisplayName("the quest panel's rows")
class QuestPanelLayoutTest {

    /** A quest with one of everything the panel edits. */
    private static JsonObject quest() {
        return JsonParser.parseString("""
                {
                  "id": "smelt_iron",
                  "title": "Iron, Twice",
                  "subtitle": "The first one is a lesson",
                  "icon": { "item": "minecraft:iron_ingot" },
                  "x": 12, "y": -4,
                  "showTitle": true,
                  "iconScale": 1.5,
                  "aliases": ["iron_lesson"],
                  "dependsOn": ["make_a_furnace", "mine_iron"],
                  "tasks": [
                    { "type": "tasked:item", "item": "minecraft:iron_ingot", "count": 3 },
                    { "type": "addon:mystery", "custom": true, "nested": { "deep": 1 } }
                  ],
                  "rewards": [
                    { "type": "tasked:item", "item": "minecraft:iron_block" }
                  ]
                }
                """).getAsJsonObject();
    }

    private static List<InspectRow> rows(JsonObject quest) {
        return QuestPanelLayout.rows(quest, "smelt_iron", Set.of());
    }

    private static InspectRow row(List<InspectRow> rows, String key) {
        return rows.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row keyed " + key + " in " + rows.size() + " rows"));
    }

    @Test
    @DisplayName("the sections are there, in order, and a field row's key is the path it commits to")
    void sectionsInOrder() {
        List<InspectRow> rows = rows(quest());

        List<String> headings = rows.stream().filter(row -> row.isHeading() || row.isEntry())
                .map(InspectRow::key).toList();
        assertEquals(List.of(QuestPanelLayout.IDENTITY, QuestPanelLayout.PLACEMENT,
                QuestPanelLayout.RULES, QuestPanelLayout.DEPENDENCIES,
                "h:tasks.0", "h:tasks.1", "h:rewards.0"), headings,
                "one section per part of the quest, in the order an author reads them");

        InspectRow title = row(rows, "title");
        assertEquals(InspectRow.Kind.FIELD, title.kind());
        assertEquals("Iron, Twice", title.value(), "the field starts showing what the tree says");

        assertEquals("12", row(rows, "x").value());
    }

    @Test
    @DisplayName("a task of a known type gets its own fields, sorted, read from its own object")
    void knownTypesGetTheirFields() {
        List<InspectRow> rows = rows(quest());

        InspectRow heading = row(rows, "h:tasks.0");
        assertFalse(heading.isWarning(), "tasked:item is known, so its section is a heading");
        assertTrue(heading.label().contains("tasked:item"), "the section names the type");

        // The registry's own field names, sorted: item, count, consumeItems.
        assertEquals(List.of("tasks.0.consumeItems", "tasks.0.count", "tasks.0.item"),
                rows.stream().map(InspectRow::key)
                        .filter(key -> key.startsWith("tasks.0.") && !key.equals("tasks.0.type"))
                        .toList(),
                "the type's own fields, from the registry, not a second list");

        assertEquals("3", row(rows, "tasks.0.count").value());
        assertEquals("minecraft:iron_ingot", row(rows, "tasks.0.item").value());
    }

    @Test
    @DisplayName("a task of an unknown type is a warning heading over the value itself")
    void unknownTypesGetTheFallback() {
        List<InspectRow> rows = rows(quest());

        InspectRow heading = row(rows, "h:tasks.1");
        assertTrue(heading.isWarning(), "the fallback opens in the voice that says something is wrong");
        assertTrue(heading.label().contains("addon:mystery"), "and names the type it cannot take apart");

        InspectRow raw = row(rows, QuestPanelLayout.RAW_PREFIX + "tasks.1");
        assertEquals(InspectRow.Kind.RAW, raw.kind());
        assertTrue(raw.value().contains("\"custom\":true"), "the value as stored, not a summary");
        assertTrue(raw.value().contains("\"nested\":{\"deep\":1}"),
                "and everything in it -- a fallback that dropped fields would be a save that does");
    }

    @Test
    @DisplayName("dependencies are a row each with a strip, and one row to add by typed id")
    void dependenciesAreRowsWithStrips() {
        List<InspectRow> rows = rows(quest());

        InspectRow first = row(rows, QuestPanelLayout.DEPENDENCY_PREFIX + "make_a_furnace");
        assertEquals(InspectRow.Kind.TOGGLE, first.kind(),
                "a strip row, which is where the screen puts the Remove button");
        assertEquals("make_a_furnace", first.label(), "the row is named for the dependency it is");

        InspectRow add = row(rows, QuestPanelLayout.DEPENDENCY_ADD);
        assertEquals(InspectRow.Kind.FIELD, add.kind(), "the Add row is a field: the id is typed into it");
    }

    @Test
    @DisplayName("a folded section is its heading and nothing else")
    void foldingLeavesTheHeading() {
        List<InspectRow> rows = QuestPanelLayout.rows(quest(), "smelt_iron",
                Set.of(QuestPanelLayout.PLACEMENT, "h:tasks.0"));

        assertTrue(rows.stream().anyMatch(candidate -> candidate.key().equals(QuestPanelLayout.PLACEMENT)),
                "the heading stays");
        assertTrue(rows.stream().noneMatch(candidate -> candidate.key().equals("x")),
                "and nothing under it: a folded section is folded");

        assertTrue(rows.stream().anyMatch(candidate -> candidate.key().equals("h:tasks.0")),
                "a folded task keeps its heading too");
        assertTrue(rows.stream().noneMatch(candidate -> candidate.key().equals("tasks.0.item")),
                "and loses its fields with it");
    }

    @Test
    @DisplayName("no selection is a line that says so, not a blank panel")
    void nothingSelectedSaysSo() {
        List<InspectRow> rows = QuestPanelLayout.rows(null, null, Set.of());
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).value().contains("Select a quest"),
                "the panel's first answer tells the author what to do");

        List<InspectRow> missing = QuestPanelLayout.rows(null, "gone_quest", Set.of());
        assertTrue(missing.get(0).value().contains("gone_quest"),
                "and a quest that is not in the chapter is named, not vague");
    }

    @Test
    @DisplayName("the type a field edits as comes from the format, then the tree, then text")
    void fieldKinds() {
        JsonObject quest = quest();

        assertTrue(refuses(QuestPanelLayout.fieldFor(quest, "x"), "1.5"),
                "a coordinate is a whole number, and refuses a fraction");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "x"), "12"));
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "iconScale"), "1.5"),
                "a scale takes a fraction, which the whole-number field would refuse");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "showTitle"), "true"),
                "a flag edits as a flag");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "tasks.0.count"), "3"),
                "a task's count is reached through its array step and edits whole");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "tasks.1.count"), "4"),
                "an absent count still edits whole, from what the name declares");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "title"), "anything at all"),
                "a title is text");
    }

    private static boolean accepts(InspectField<?> field, String text) {
        return field.parse(text).ok();
    }

    private static boolean refuses(InspectField<?> field, String text) {
        return !field.parse(text).ok();
    }

    @Test
    @DisplayName("labels are said like fields, and the index is not one of them")
    void labels() {
        assertEquals("Icon Item", QuestPanelLayout.labelFor("icon.item"));
        assertEquals("Count", QuestPanelLayout.labelFor("tasks.0.count"));
        assertEquals("Repeat Cooldown Ticks", QuestPanelLayout.labelFor("repeatCooldownTicks"));
        assertEquals("Item", QuestPanelLayout.labelFor("rewards.0.item"));
    }

    @Test
    @DisplayName("reading the tree takes array steps, the same shape the model writes")
    void readsThroughArraySteps() {
        JsonObject quest = quest();

        assertEquals(3, QuestPanelLayout.get(quest, "tasks.0.count").getAsInt(),
                "the commit's path and the read's path are one shape");
        assertNull(QuestPanelLayout.get(quest, "tasks.9.count"), "past the end is nothing");
        assertNull(QuestPanelLayout.get(quest, "tasks.notAnIndex"), "and so is a word where an index belongs");
        assertEquals("minecraft:iron_block", QuestPanelLayout.get(quest, "rewards.0.item").getAsString());
    }
}
