package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the validator says, and where it says it.
 *
 * <h2>The feature under test is the error message</h2>
 *
 * <p>Minecraft's codecs silently ignore fields they do not recognise, so a misspelled key produces
 * a quest that quietly does nothing and no explanation. The whole reason this validator exists is
 * to say which field is wrong, on which line, and — where the name is close to a real one — what
 * was probably meant. So most of these tests assert on the message text and its position, not on
 * a pass/fail verdict.
 */
@DisplayName("QuestValidator")
class QuestValidatorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // The validator checks that a named item exists, which reads BuiltInRegistries.ITEM — and
        // that is empty until vanilla has registered its items. Without this, every item in every
        // fixture would be reported as missing, including minecraft:oak_log, and half these tests
        // would pass for entirely the wrong reason.
        MinecraftTestBootstrap.boot();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Problems validate(String json) {
        JsonDocument document = Fixtures.document("test.json", json);
        Problems problems = new Problems();
        QuestValidator.validate(document, problems);
        return problems;
    }

    private static DataProblem containing(Problems problems, String text) {
        return problems.all().stream()
                .filter(problem -> problem.message().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no problem mentioning '" + text + "'. Got:\n"
                        + problems.all().stream()
                                .map(DataProblem::render)
                                .collect(Collectors.joining("\n"))));
    }

    /**
     * Asserts the problem points at a JSON <b>value</b> — an item name, a task type, an id.
     *
     * <p>Computed from the fixture rather than hardcoded, so the assertions stay honest if the
     * fixture is reformatted, and still fail if the position drifts. {@code token} must include its
     * quotes.
     */
    private static void assertPointsAtValue(DataProblem problem, String json, String token) {
        withToken(json, token, (line, column) -> {
            assertEquals(line, problem.line(), "line, for value " + token);
            assertEquals(column, problem.column(), "column, for value " + token);
        });
    }

    /**
     * Asserts the problem points at the value of a <b>key</b> — the text after the colon.
     *
     * <p>Separate from {@link #assertPointsAtValue} because the two are genuinely different
     * positions, and conflating them was a real bug here: this method was originally the only one,
     * and it computed every token as though it were a key. Three of the four call sites passed
     * values, so three assertions were off by the token's own length and failed. Keeping the two
     * named apart means a call site says which it expects.
     */
    private static void assertPointsAtKeyValue(DataProblem problem, String json, String key) {
        withToken(json, key, (line, column) -> {
            assertEquals(line, problem.line(), "line, for key " + key);
            // key, then ':', then ' ' — so the value begins three characters later, 1-based.
            assertEquals(column + key.length() + 2, problem.column(), "column, for key " + key);
        });
    }

    /** Finds {@code token} and hands its 1-based line and column to {@code check}. */
    private static void withToken(String json, String token, java.util.function.BiConsumer<Integer, Integer> check) {
        String[] lines = json.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf(token);
            if (at >= 0) {
                check.accept(i + 1, at + 1);
                return;
            }
        }
        throw new AssertionError("'" + token + "' does not appear in the fixture");
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a valid file produces nothing at all")
    void cleanFile() {
        Problems problems = validate(Fixtures.file(
                Fixtures.q("a").build(),
                Fixtures.q("b").dependsOn("a").build()));

        assertTrue(problems.isEmpty(), "expected no problems, got:\n"
                + problems.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
    }

    // ------------------------------------------------------------------
    // The check that saves the most time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown field is reported on its own line, with the right name suggested")
    void unknownFieldSuggestsTheRightName() {
        String json = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "group",
                      "title": "Group",
                      "chapters": [
                        {
                          "id": "chapter",
                          "titl": "A typo",
                          "quests": []
                        }
                      ]
                    }
                  ]
                }
                """;

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "unknown field \"titl\"");

        assertTrue(problem.message().contains("did you mean \"title\"?"),
                "should suggest the real field; got: " + problem.message());
        assertPointsAtKeyValue(problem, json, "\"titl\"");
    }

    @Test
    @DisplayName("and lists every field that would have been allowed")
    void unknownFieldListsTheAlternatives() {
        Problems problems = validate(Fixtures.file(Fixtures.q("a").build()));
        // A misspelling of "title" on the quest, to get the quest's own field list.
        Problems withTypo = validate(Fixtures.file(
                "{\"id\": \"a\", \"titl\": \"x\", \"tasks\": []}"));

        assertTrue(containing(withTypo, "unknown field \"titl\"").message().contains("valid fields here:"));
        assertTrue(withTypo.errorCount() > 0);
        assertFalse(problems.hasErrors(), "the unmodified fixture should be clean");
    }

    @Test
    @DisplayName("a comment attempt is named as one, rather than as an unknown field")
    void commentIsNamedAsAComment() {
        // JSON has no comments, so `//` is simply an unknown field -- and "unknown field //"
        // leaves the author wondering what it should have been called.
        String json = """
                {
                  "version": 1,
                  "//": "this is not a comment",
                  "chapterGroups": []
                }
                """;

        DataProblem problem = containing(validate(json), "looks like a comment");
        assertTrue(problem.message().contains("JSON has none"), "got: " + problem.message());
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown task type lists the ones that exist")
    void unknownTaskType() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tasked:items\"} ]}");

        DataProblem problem = containing(validate(json), "unknown quest task type");

        assertTrue(problem.message().contains("tasked:item"), "should list tasked:item: " + problem.message());
        assertTrue(problem.message().contains("tasked:checkmark"),
                "should list the other type too: " + problem.message());
        assertPointsAtValue(problem, json, "\"tasked:items\"");
    }

    @Test
    @DisplayName("a field that no task type knows is reported, but one that another type knows is not")
    void taskFieldsAreCheckedAgainstEveryType() {
        // Deliberately the union of every type's fields rather than just this type's: a task may
        // be changed from one type to another, and flagging the leftover fields of the old type
        // on every file would be noise. What must still be caught is a field nothing understands.
        Problems typo = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:oak_log\", \"counnt\": 4} ]}"));
        assertTrue(containing(typo, "unknown field \"counnt\"").message().contains("did you mean \"count\"?"));

        Problems crossType = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:checkmark\", \"title\": \"t\", \"count\": 4} ]}"));
        assertFalse(crossType.errorCount() > 0,
                "count belongs to tasked:item, so it is a known field overall. Got:\n"
                        + crossType.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item that does not exist warns, names it, and keeps the quest loadable")
    void unknownItem() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:not_a_real_item\"} ]}");

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "there is no item");

        assertTrue(problem.message().contains("minecraft:not_a_real_item"), "should name it");
        assertTrue(problem.message().contains("check the spelling"),
                "a minecraft: item missing is a typo, and the message should say so: " + problem.message());
        assertPointsAtValue(problem, json, "\"minecraft:not_a_real_item\"");
        // Reversed deliberately, and this is where the reversal is pinned: a missing mod used to be an
        // error, and an error skips the file -- so a pack whose mod had been removed lost the whole
        // quest, node and all, with the id still sitting in the file. The id is kept, the quest loads,
        // and the row draws it as missing.
        assertTrue(problem.severity() == DataProblem.Severity.WARNING,
                "a missing item is kept, not fatal");
        assertFalse(problems.hasErrors(), "so the file still loads");
    }

    @Test
    @DisplayName("an item from a mod that is not installed says which mod")
    void unknownItemNamesTheMod() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"someothermod:widget\"} ]}");

        DataProblem problem = containing(validate(json), "there is no item");

        assertTrue(problem.message().contains("someothermod"),
                "the fix is usually installing the mod, so name it: " + problem.message());
    }

    @Test
    @DisplayName("a real item is accepted, which is what makes the check worth having")
    void knownItemIsFine() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tasked:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        assertFalse(problems.hasErrors(), "minecraft:oak_log exists, so there is nothing to report");
    }

    // ------------------------------------------------------------------
    // Ids and enums
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an id with an uppercase letter is rejected, and says which character")
    void uppercaseId() {
        String json = Fixtures.file("{\"id\": \"NOT_AN_ID\", \"title\": \"a\", \"tasks\": []}");

        DataProblem problem = containing(validate(json), "may only contain lowercase");
        assertTrue(problem.message().contains("'N'"), "should point at the offending character: "
                + problem.message());
        assertPointsAtValue(problem, json, "\"NOT_AN_ID\"");
    }

    @Test
    @DisplayName("an enum value that is close to a real one lists the options")
    void badEnumValue() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"prerequisiteMode\": \"all_complete\", \"tasks\": []}"));

        DataProblem problem = containing(problems, "is not one of");
        assertTrue(problem.message().contains("all_completed"), "should list the real value: "
                + problem.message());
    }

    // ------------------------------------------------------------------
    // Warnings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown on a quest that is not repeatable warns rather than failing")
    void cooldownWithoutRepeatWarns() {
        // Not an error: it does nothing, but it is not wrong. A validator that fails a working
        // file is a validator people learn to ignore.
        Problems problems = validate(Fixtures.file(
                Fixtures.q("a").repeatCooldownTicks(600).build()));

        assertFalse(problems.hasErrors(), "a no-op cooldown is not fatal");
        assertTrue(containing(problems, "only means something on a repeatable quest").severity()
                        == DataProblem.Severity.WARNING,
                "it should be a warning");
    }

    @Test
    @DisplayName("a positive report is not an error, so it does not stop the file loading")
    void warningsDoNotBlockALoad() {
        Problems problems = validate(Fixtures.file(Fixtures.q("a").build()));
        assertFalse(problems.hasErrors());
        assertFalse(problems.hasErrorsIn("test.json"), "no errors means nothing to skip");
    }

    // ------------------------------------------------------------------
    // Blank lines, which are paragraphs and not mistakes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank line in a description is a paragraph break, not a mistake")
    void blankParagraphsAreDeliberate() {
        // Found by the shipped questlines, which warn on every deliberate blank line in them. Each
        // entry of a description is drawn as its own paragraph, so an empty entry is the only way an
        // author can write a line break -- and warning about it is the worst shape a check can take:
        // not wrong about the fact, wrong about whether the fact is a problem. A warning like that
        // teaches an author to skim output, which costs the warnings that matter.
        Problems problems = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": [\"one\", \"\", \"two\"]}"));

        assertFalse(messages(problems).contains("this text is empty"),
                "a blank paragraph should not warn, got:" + messages(problems));
    }

    @Test
    @DisplayName("a description that is all blank paragraphs still warns, because nothing shows")
    void anAllBlankDescriptionWarns() {
        // The question moves from each paragraph to the whole list. A description of nothing but
        // blank lines draws nothing at all, which is worth saying once.
        Problems problems = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": [\"\", \"  \"]}"));

        assertTrue(messages(problems).contains("every paragraph here is empty"),
                "expected a warning about the whole list, got:" + messages(problems));
    }

    @Test
    @DisplayName("an empty title still warns, since an empty title is a quest with no name")
    void anEmptyTitleStillWarns() {
        // The distinction the parameter exists for. Blank is meaningful inside a list of paragraphs
        // and meaningless in a title.
        Problems problems = validate(
                "{\"version\": 1, \"chapterGroups\": [{\"id\": \"g\", \"title\": \"G\", "
                        + "\"chapters\": [{\"id\": \"c\", \"title\": \"\", \"quests\": []}]}]}");

        assertTrue(messages(problems).contains("this text is empty"),
                "an empty chapter title should still warn, got:" + messages(problems));
    }

    // ------------------------------------------------------------------
    // A group's description, where a list of paragraphs is the natural thing to write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chapter group description accepts a list of paragraphs, because a chapter's does")
    void groupDescriptionAcceptsAList() {
        // The inconsistency that a second shipped questline found by being written the obvious way.
        // `ChapterGroup.description` was a single Optional<QuestText> while a chapter's and a quest's
        // were both lists -- so an array of lines failed to decode, and the failure arrived as a codec
        // message at line 1 column 1 saying the format had rejected the file.
        //
        // That is the exact message this whole validator exists to prevent: it names the wrong place,
        // about a field the validator had never been taught to look at, so it stayed silent and let the
        // codec speak from the root of the file.
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{
                   "id": "g", "title": "G",
                   "description": ["a line", "", "another line"],
                   "chapters": [{"id": "c", "title": "C", "quests": [
                     {"id": "q", "title": "q", "tasks": [{"type": "tasked:checkmark", "title": "t"}]}
                   ]}]
                }]}""");

        assertTrue(problems.isEmpty(), "a group description as a list should be clean, got:"
                + messages(problems));
    }

    @Test
    @DisplayName("and a bare string still works, so an existing file is not broken by the change")
    void groupDescriptionAcceptsAString() {
        // The union is deliberate rather than lazy. A one-line group description written as a string is
        // not a mistake worth failing a file over, so both spellings work and keep working.
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{
                   "id": "g", "title": "G",
                   "description": "one line",
                   "chapters": [{"id": "c", "title": "C", "quests": [
                     {"id": "q", "title": "q", "tasks": [{"type": "tasked:checkmark", "title": "t"}]}
                   ]}]
                }]}""");

        assertTrue(problems.isEmpty(), "a one-line group description should still load, got:"
                + messages(problems));
    }

    // ------------------------------------------------------------------
    // A type's own required fields, which the field-name checks cannot see
    // ------------------------------------------------------------------

    /** A quest document on its own, the way the loader reads one. */
    private static Problems validateQuest(String json) {
        JsonDocument document = Fixtures.document("quest.json", json);
        Problems problems = new Problems();
        QuestValidator.validateQuestDocument(document, problems);
        return problems;
    }

    @Test
    @DisplayName("an item task with no \"item\" is an error -- this is the one the loader used to catch")
    void itemTaskNeedsItsItem() {
        // The report that made this a test: the picker's clear row deleted "item" from a task, the
        // field-name checks saw nothing wrong (an absent field is not an unknown one), the save was
        // allowed, and the next load dropped the quest from the tree -- the node simply vanished.
        // The codec always knew. Only the loader ever asked it, and only after the write.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:item", "count": 1}]}""");

        DataProblem problem = containing(problems, "No key item");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "an unloadable file is an error, not a warning");
        assertTrue(problem.message().contains("tasked:item"),
                "and it says which type the fields failed to make, got: " + problem.message());
    }

    @Test
    @DisplayName("a reward's own codec gets the last word too")
    void itemRewardNeedsItsItem() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [{"type": "tasked:item", "count": 1}]}""");

        assertTrue(containing(problems, "No key item").severity() == DataProblem.Severity.ERROR,
                "the reward half has the same gap and the same fix");
    }

    @Test
    @DisplayName("an icon whose components do not decode is refused -- the loader would skip the quest")
    void iconWithABrokenPatchIsRefused() {
        // The icon's half of the per-type codec check: a component patch the codec cannot read would
        // otherwise pass every field-name rule and reach the loader, which skips the whole file.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "icon": {"item": "minecraft:paper", "components": 5}}""");

        DataProblem problem = containing(problems, "not a usable item reference");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR, "a file the loader drops is an error");
    }

    @Test
    @DisplayName("an icon object with no item was already refused, and stays refused")
    void iconWithoutAnItemIsRefused() {
        // Pinned because the picker's clear row is about to rely on it: clearing an icon has to remove
        // the whole object, since removing only the leaf leaves this -- which does not load either.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One", "icon": {}}""");

        assertTrue(containing(problems, "missing required field item").severity()
                        == DataProblem.Severity.ERROR,
                "the icon's required item is the existing check the clear path is built around");
    }

    private static String file(String quest) {
        return "{\"version\": 1, \"chapterGroups\": [{\"id\": \"g\", \"title\": \"G\", "
                + "\"chapters\": [{\"id\": \"c\", \"title\": \"C\", \"quests\": [" + quest + "]}]}]}";
    }

    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }
}
