package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.client.ui.inspect.InspectRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Chapter tab's rows and header, from a chapter's own tree.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The things the redesigned tab promises: every section in order, the row keys still the dotted
 * paths the commits go to, the shortened labels actually short, the header reading the chapter's own
 * name, and the icon exposed as the object the panel resolves. All of it is answerable with a parsed
 * JSON object and no client -- which is why the rows are built by a game-free class and the drawing
 * is not.
 */
@DisplayName("the chapter panel's rows")
class ChapterPanelLayoutTest {

    /** A chapter with one of everything the panel edits. */
    private static JsonObject chapter() {
        return JsonParser.parseString("""
                {
                  "id": "first_steps",
                  "title": "First Steps",
                  "subtitle": "Five quests, no tricks",
                  "description": ["One.", "Two.", "Three."],
                  "icon": { "item": "minecraft:crafting_table" },
                  "aliases": ["start", "the_beginning"],
                  "progressionMode": "linear",
                  "defaultConsumeItems": true,
                  "defaultPrerequisiteMode": "one_completed",
                  "quests": ["punch_a_tree.json", "make_a_table.json", "stone_tools"]
                }
                """).getAsJsonObject();
    }

    private static InspectRow row(List<InspectRow> rows, String key) {
        return rows.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row keyed " + key + " in " + rows.size() + " rows"));
    }

    @Test
    @DisplayName("the sections are there, in order, and every key is the path it commits to")
    void sectionsInOrder() {
        List<InspectRow> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        assertEquals(List.of(ChapterPanelLayout.IDENTITY, ChapterPanelLayout.RULES,
                        ChapterPanelLayout.QUESTS),
                rows.stream().filter(InspectRow::isHeading).map(InspectRow::key).toList(),
                "one heading per section, in the order an author reads them");
        assertEquals(List.of("title", "subtitle", ChapterPanelLayout.ICON,
                        ChapterPanelLayout.VALUE_PREFIX + "description", "aliases",
                        "progressionMode", "defaultConsumeItems", "defaultPrerequisiteMode",
                        "dependencyStyle.form", "dependencyStyle.arrows", "dependencyStyle.dash",
                        "dependencyStyle.weight"),
                rows.stream().filter(row -> !row.isHeading()
                                && !row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:"))
                        .map(InspectRow::key).toList(),
                "the paths the ops go to, under the headings that own them");
    }

    @Test
    @DisplayName("the labels are short, because the dock is narrow and that is what truncated them")
    void labelsAreShort() {
        // The report: in a 180-pixel panel, side-by-side rows spent 96 pixels on the control and the
        // label truncated -- "Default Prerequisite Mode" became "Default Prereq...". The mode is the
        // main fix; these names are the other half, and a name that grows back to a sentence undoes it.
        List<InspectRow> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        assertEquals("Progression", row(rows, "progressionMode").label());
        assertEquals("Prerequisite", row(rows, "defaultPrerequisiteMode").label());
        assertEquals("Aliases", row(rows, "aliases").label());
        assertEquals("Line form", row(rows, "dependencyStyle.form").label());
        assertEquals("Line arrows", row(rows, "dependencyStyle.arrows").label());
        assertEquals("Line dash", row(rows, "dependencyStyle.dash").label());
        assertEquals("Line weight", row(rows, "dependencyStyle.weight").label());
    }

    @Test
    @DisplayName("the toggle's label carries the state, so the row says what the flag is")
    void theToggleSaysItsState() {
        List<InspectRow> on = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals("Consume items \u00b7 on", row(on, "defaultConsumeItems").label());

        JsonObject off = chapter();
        off.addProperty("defaultConsumeItems", false);
        assertEquals("Consume items \u00b7 off",
                row(ChapterPanelLayout.rows(off, Set.of()), "defaultConsumeItems").label());
    }

    // ------------------------------------------------------------------
    // The cycling rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cycling row carries the value in force, and an absent axis reads as unset")
    void theChoiceRowsCarryTheirValues() {
        List<InspectRow> rows = ChapterPanelLayout.rows(chapter(), Set.of());
        assertEquals("linear", row(rows, "progressionMode").value());
        assertEquals("one_completed", row(rows, "defaultPrerequisiteMode").value());
        assertEquals("", row(rows, "dependencyStyle.form").value(),
                "a chapter with no dependencyStyle is unset on every axis");

        JsonObject styled = chapter();
        styled.add("dependencyStyle", JsonParser.parseString(
                "{\"form\":\"curved\",\"bend\":0.4}").getAsJsonObject());
        assertEquals("curved", row(ChapterPanelLayout.rows(styled, Set.of()),
                "dependencyStyle.form").value());
        assertEquals("", row(ChapterPanelLayout.rows(styled, Set.of()),
                "dependencyStyle.arrows").value(),
                "an axis the object does not name is still unset");
    }

    @Test
    @DisplayName("the picker cycles the closed set and wraps, the unset state included")
    void thePickerCyclesAndWraps() {
        assertEquals(List.of("", "flexible", "linear"),
                ChapterPanelLayout.choiceValues(ChapterPanelLayout.PROGRESSION));
        assertEquals("linear", ChapterPanelLayout.cycleChoice(ChapterPanelLayout.PROGRESSION, "", -1),
                "down from the unset state wraps to the last value");
        assertEquals("", ChapterPanelLayout.cycleChoice(ChapterPanelLayout.PROGRESSION, "linear", 1),
                "up from the last value wraps back to unset");
        assertEquals("curved",
                ChapterPanelLayout.cycleChoice(ChapterPanelLayout.LINE_FORM, "straight", 1));
        assertEquals("many", ChapterPanelLayout.cycleChoice(ChapterPanelLayout.LINE_ARROWS, "", -1),
                "down from unset wraps to the last arrow value");
        assertEquals("", ChapterPanelLayout.cycleChoice(ChapterPanelLayout.LINE_WEIGHT, "not_a_weight", 0),
                "an unknown value the file holds starts the cycle from unset");
    }

    @Test
    @DisplayName("the unset state is labelled with the fallback it means, not a bare Default")
    void theUnsetStateNamesItsFallback() {
        assertEquals("Default (all completed)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.PREREQUISITE, ""));
        assertEquals("Default (orthogonal)",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.LINE_FORM, ""));
        assertEquals("dashed",
                ChapterPanelLayout.choiceLabel(ChapterPanelLayout.LINE_DASH, "dashed"));
    }

    @Test
    @DisplayName("a rule writes itself, and the unset choice removes the field")
    void aRuleWritesItself() {
        ChapterPanelLayout.Edit toDefault =
                ChapterPanelLayout.choiceEdit(chapter(), ChapterPanelLayout.PROGRESSION, "");
        assertEquals("progressionMode", toDefault.path());
        assertNull(toDefault.value(), "the unset choice removes the field rather than writing a default");

        ChapterPanelLayout.Edit linear =
                ChapterPanelLayout.choiceEdit(chapter(), ChapterPanelLayout.PROGRESSION, "linear");
        assertEquals("linear", linear.value().getAsString());
    }

    @Test
    @DisplayName("a line axis rewrites dependencyStyle and keeps every axis and the bend it did not touch")
    void aLineAxisRewritesTheObject() {
        JsonObject styled = chapter();
        styled.add("dependencyStyle", JsonParser.parseString(
                "{\"form\":\"curved\",\"bend\":0.4}").getAsJsonObject());

        ChapterPanelLayout.Edit arrows =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_ARROWS, "none");
        assertEquals("dependencyStyle", arrows.path());
        assertEquals(Set.of("form", "bend", "arrows"), arrows.value().getAsJsonObject().keySet());
        assertEquals("curved", arrows.value().getAsJsonObject().get("form").getAsString(),
                "an axis the step did not touch keeps the file's own value");
        assertEquals(0.4, arrows.value().getAsJsonObject().get("bend").getAsDouble(),
                "and the numeric bend, which has no picker, survives");

        ChapterPanelLayout.Edit removed =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_FORM, "");
        assertEquals(Set.of("bend"), removed.value().getAsJsonObject().keySet());
    }

    @Test
    @DisplayName("clearing the last axis drops dependencyStyle entirely")
    void theLastAxisDropsTheObject() {
        JsonObject styled = chapter();
        styled.add("dependencyStyle",
                JsonParser.parseString("{\"form\":\"curved\"}").getAsJsonObject());

        ChapterPanelLayout.Edit edit =
                ChapterPanelLayout.choiceEdit(styled, ChapterPanelLayout.LINE_FORM, "");
        assertEquals("dependencyStyle", edit.path());
        assertNull(edit.value(), "an empty style is a key that pins nothing");
    }

    @Test
    @DisplayName("the description is counted in paragraphs, and says it is edited in the file")
    void theDescriptionIsCounted() {
        assertEquals("3 paragraphs \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(chapter(), Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());

        JsonObject one = chapter();
        one.add("description", JsonParser.parseString("[\"Only one.\"]"));
        assertEquals("1 paragraph \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(one, Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());

        JsonObject none = chapter();
        none.remove("description");
        assertEquals("None \u00b7 edited in the file",
                row(ChapterPanelLayout.rows(none, Set.of()),
                        ChapterPanelLayout.VALUE_PREFIX + "description").value());
    }

    @Test
    @DisplayName("the quest list is in the authored order, numbered, and its ids lose the .json")
    void theQuestListIsInOrder() {
        List<InspectRow> rows = ChapterPanelLayout.rows(chapter(), Set.of());

        List<String> quests = rows.stream()
                .filter(row -> row.key().startsWith(ChapterPanelLayout.VALUE_PREFIX + "quest:"))
                .map(row -> row.label() + " " + row.value())
                .toList();
        assertEquals(List.of("1. punch_a_tree", "2. make_a_table", "3. stone_tools"), quests,
                "the order is the chapter's own, and it is the progression in a linear chapter");
    }

    @Test
    @DisplayName("folding a section takes its rows and leaves its heading")
    void foldingLeavesTheHeading() {
        List<InspectRow> folded = ChapterPanelLayout.rows(chapter(), Set.of(ChapterPanelLayout.RULES));

        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ChapterPanelLayout.RULES)),
                "the folded section's heading is still drawn, so it can be unfolded");
        assertFalse(folded.stream().anyMatch(row -> row.key().equals("progressionMode")),
                "and its rows are gone rather than hidden behind a flag");
    }

    @Test
    @DisplayName("the header reads the chapter's name, and is empty for a copy that has not arrived")
    void theHeaderReadsTheChapter() {
        ChapterPanelLayout.Header header = ChapterPanelLayout.header(chapter());
        assertEquals("First Steps", header.title());
        assertEquals("Five quests, no tricks", header.subtitle());

        ChapterPanelLayout.Header missing = ChapterPanelLayout.header(null);
        assertEquals("", missing.title());
        assertEquals("", missing.subtitle(), "an empty header, not a trip, is the answer for no copy");
    }

    @Test
    @DisplayName("without edit mode the tab names the state, and the instruction names the button")
    void notEditing() {
        List<InspectRow> rows = ChapterPanelLayout.notEditing();

        assertEquals(1, rows.size(), "one line, and nothing to press: " + rows);
        assertEquals(InspectRow.Kind.VALUE, rows.get(0).kind(),
                "a value row, so the screen builds no widget that could look live and do nothing");
        assertEquals("Chapter", rows.get(0).label());
        assertEquals("Edit mode is off", rows.get(0).value());
        assertTrue(ChapterPanelLayout.EDIT_MODE_HINT.contains("Edit"),
                "the instruction has to name the button it tells the player to press: "
                        + ChapterPanelLayout.EDIT_MODE_HINT);
    }

    @Test
    @DisplayName("the two empty states are different facts, and neither is blank")
    void emptyStatesAreDistinct() {
        // Not editing: nothing was ever asked for, and the tab says what to do about it.
        String notEditing = ChapterPanelLayout.notEditing().get(0).value();
        // In edit mode with no copy: the request is in flight, or the server said no.
        List<InspectRow> waiting = ChapterPanelLayout.rows(new JsonObject(), Set.of());
        assertEquals(1, waiting.size());
        String missing = waiting.get(0).value();

        assertTrue(missing.contains("has not arrived"), missing);
        assertNotEquals(missing, notEditing,
                "a state the player can fix is not a state the server owes them");
        assertFalse(notEditing.isBlank());
        assertFalse(missing.isBlank());
    }

    @Test
    @DisplayName("a refusal is the missing-copy line with the server's own words after it")
    void refusalsAreShown() {
        String refused = ChapterPanelLayout.rows(new JsonObject(), null, Set.of(),
                "no chapter called \"x\"").get(0).value();

        assertTrue(refused.contains("has not arrived"), refused);
        assertTrue(refused.contains("no chapter called \"x\""),
                "the server's own reason has to survive into the line: " + refused);
    }

    @Test
    @DisplayName("the icon is exposed as the file's own object, and an absent icon is an empty one")
    void theIconIsExposed() {
        assertEquals("minecraft:crafting_table",
                ChapterPanelLayout.icon(chapter()).get("item").getAsString());

        JsonObject none = chapter();
        none.remove("icon");
        assertTrue(ChapterPanelLayout.icon(none).isEmpty(), "no icon is an empty object, not a null");
        assertTrue(ChapterPanelLayout.icon(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // The chapter's group
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the group is its own section at the end, with every row keyed to the group's file")
    void theGroupSectionIsItsOwn() {
        // The report this is the answer to: a group's row showed a chapter's icon, and there was nowhere
        // to give the group one of its own. The section is last -- the chapter is the panel, the group is
        // the shelf it sits on -- and each key carries the prefix that sends the commit to group.json
        // rather than to the chapter's file.
        ChapterPanelLayout.GroupInfo group =
                new ChapterPanelLayout.GroupInfo("getting_started", "Getting Started",
                        "minecraft:anvil", true);
        List<InspectRow> rows = ChapterPanelLayout.rows(chapter(), group, Set.of());

        assertEquals(List.of(ChapterPanelLayout.IDENTITY, ChapterPanelLayout.RULES,
                        ChapterPanelLayout.GROUP, ChapterPanelLayout.QUESTS),
                rows.stream().filter(InspectRow::isHeading).map(InspectRow::key).toList(),
                "the group's section sits between the rules and the quest list, ahead of the scroll");
        assertEquals(List.of("group.title", "group.icon.item", "group.collapsedByDefault"),
                rows.stream()
                        .filter(row -> row.key().startsWith(ChapterPanelLayout.GROUP_PREFIX))
                        .map(InspectRow::key).toList(),
                "title, icon and the collapsed flag, all pointed at the group's own file");
        assertEquals("Getting Started", row(rows, "group.title").value());
        assertEquals("minecraft:anvil", row(rows, "group.icon.item").value(),
                "the authored icon, which is what tells the row apart from the sidebar's fallback");
        assertEquals("Collapsed \u00b7 on", row(rows, "group.collapsedByDefault").label());
    }

    @Test
    @DisplayName("a chapter with no group gets no Group section at all")
    void noGroupMeansNoSection() {
        // An ungrouped chapter, or a server that does not describe groups: `rows(chapter, folded)` is
        // that case, and it is the flat book this panel has always drawn.
        assertFalse(ChapterPanelLayout.rows(chapter(), Set.of()).stream()
                        .anyMatch(row -> row.key().startsWith(ChapterPanelLayout.GROUP_PREFIX)),
                "no group rows without a group");

        ChapterPanelLayout.GroupInfo inherited =
                new ChapterPanelLayout.GroupInfo("getting_started", "Getting Started", "", false);
        assertEquals("", row(ChapterPanelLayout.rows(chapter(), inherited, Set.of()),
                        "group.icon.item").value(),
                "a group with no icon of its own says so with an empty value, not the chapter's");
        assertEquals("Collapsed \u00b7 off", row(ChapterPanelLayout.rows(chapter(), inherited, Set.of()),
                "group.collapsedByDefault").label());
    }
}
