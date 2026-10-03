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

    @Test
    @DisplayName("a custom task or reward nothing provides is a warning at its id, not an error")
    void unregisteredCustomHandler() {
        // The two reasons a custom id has no handler -- a typo, or a mod the player has not installed --
        // are the same sentence from inside the game, and both leave the task at zero progress forever
        // with nothing on screen saying why. So the file is valid, and this says which fact it found.
        String quest = """
                {"id": "a", "title": "A", "tasks": [
                  {"type": "tasked:custom", "id": "test:no_such_handler", "value": 1}
                ], "rewards": [
                  {"type": "tasked:custom", "id": "test:no_such_handler"}
                ]}""";

        Problems problems = validate(Fixtures.file(quest));

        assertFalse(problems.hasErrors(),
                "an uninstalled handler is not a broken file:\n"
                        + problems.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
        DataProblem task = containing(problems, "nothing is registered for \"test:no_such_handler\"");
        assertPointsAtValue(task, Fixtures.file(quest), "\"test:no_such_handler\"");
        assertTrue(problems.warningCount() >= 2,
                "both halves of the quest say it: " + problems.warningCount() + " warning(s)");

        // And a handler that *is* registered is silent -- the check is against the registry, not a list of
        // known names.
        dev.ellipog.tasked.quest.task.CustomTask.CustomTasks.register("test:provided", (t, context) -> 1);
        Problems provided = validate(Fixtures.file("""
                {"id": "a", "title": "A", "tasks": [
                  {"type": "tasked:custom", "id": "test:provided", "value": 1}
                ]}"""));
        assertTrue(provided.isEmpty(), "a provided handler is nothing to report, got:\n"
                + provided.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
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
    @DisplayName("an unknown condition type lists the condition types, and only those")
    void unknownConditionTypeIsReported() {
        // The third registry, asked the same question as the other two. The listing is the one place
        // the difference shows: a condition type name that silently fell back to the task registry
        // would suggest tasked:checkmark to an author who wrote a condition.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x",
                            "conditions": [{"type": "tasked:no_such"}]}]}""");

        DataProblem problem = containing(problems, "unknown quest condition type \"tasked:no_such\"");
        assertTrue(problem.message().contains("tasked:party_size"),
                "the known list must be the condition registry, got: " + problem.message());
        assertFalse(problem.message().contains("tasked:checkmark"),
                "and not the task registry, got: " + problem.message());
    }

    @Test
    @DisplayName("a condition field no condition type knows is reported, with a suggestion")
    void unknownConditionFieldIsReported() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x",
                            "conditions": [{"type": "tasked:stage", "stag": "pack:x"}]}]}""");

        DataProblem problem = containing(problems, "unknown field \"stag\"");
        assertTrue(problem.message().contains("did you mean \"stage\"?"),
                "a one-letter slip is the case this catches, got: " + problem.message());
    }

    @Test
    @DisplayName("a condition missing its required field is an error naming the condition type")
    void conditionNeedsItsOwnFields() {
        // The nested half of the item-task gap: the field names are only names, and whether they make a
        // value is the condition's own codec's question.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x",
                            "conditions": [{"type": "tasked:stage"}]}]}""");

        DataProblem problem = containing(problems, "No key stage");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "a condition the codec cannot read would drop the quest from the tree at the next load");
        assertTrue(problem.message().contains("tasked:stage"),
                "and it says which condition type the fields failed to make, got: " + problem.message());
    }

    @Test
    @DisplayName("one broken condition is one message, not the task's codec repeating it")
    void aBrokenConditionIsReportedOnce() {
        // The enclosing task's codec decodes the nested list too, so without the skip in checkTask the
        // same root cause arrives twice -- once precisely, once wrapped in the task's name. The class
        // comment's rule is one mistake, one message; this is what holds it for a nested list.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x",
                            "conditions": [{"type": "tasked:stage"}]}]}""");

        long aboutTheCondition = problems.all().stream()
                .filter(problem -> problem.message().contains("No key stage"))
                .count();
        assertEquals(1, aboutTheCondition,
                "the condition's own codec reports it; the task's backstop must not repeat it. Got:\n"
                        + messages(problems));
    }

    @Test
    @DisplayName("an item condition's item is checked where the condition stands")
    void itemConditionChecksItsItem() {
        // The "item" convention check, one level down: an item condition with an id this build does not
        // have is a warning at the condition's own line, and the quest still loads.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x",
                            "conditions": [{"type": "tasked:item", "item": "someothermod:widget"}]}]}""");

        assertFalse(problems.hasErrors(),
                "a missing item is the warning it is everywhere else, got: " + messages(problems));
        containing(problems, "there is no item someothermod:widget");
    }

    @Test
    @DisplayName("a conditions list that is not a list is refused on its own line")
    void conditionsMustBeAList() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tasked:checkmark", "title": "x", "conditions": 5}]}""");

        assertTrue(containing(problems, "expected a list").severity() == DataProblem.Severity.ERROR,
                "a number where a list belongs must not be skipped, got: " + messages(problems));
    }

    @Test
    @DisplayName("a reward inside a reward table cannot carry conditions, and the error says where they go")
    void tableEntryConditionsAreRefused() {
        // The drift this closes: an entry's reward decodes as an ordinary reward, so it can carry
        // `conditions` -- but `TableReward.grantAll` pays entries directly, with no player to ask, so
        // the field was validated and then ignored. Refused rather than honoured: a gated entry inside
        // a roll would be silently lost, the opposite of the promise an unmet reward's conditions carry.
        String table = """
                {"entries": [
                  { "weight": 1, "reward": { "type": "tasked:item", "item": "minecraft:gold_ingot",
                      "conditions": [ { "type": "tasked:stage", "stage": "my_pack:marked" } ] } }
                ]}""";
        JsonDocument document = Fixtures.document("loot.json", table);
        Problems problems = new Problems();
        QuestValidator.validateRewardTableDocument(document, problems);

        DataProblem problem = containing(problems, "cannot carry \"conditions\"");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "a field the engine would ignore must not load quietly");
        assertTrue(problem.message().contains("table reward itself"),
                "and the way out is named: " + problem.message());
        assertPointsAtKeyValue(problem, table, "\"conditions\"");
    }

    @Test
    @DisplayName("an inline table's entries are walked: unknown fields reported, conditions refused")
    void inlineTableEntriesAreChecked() {
        // The walk itself is new. Nothing descended into `inline` before this, so an inline entry's
        // fields were the codec's business alone -- and a codec ignores unknown fields, which is the
        // gap the field-name checks exist to close.
        Problems unknownField = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tasked:loot", "inline": { "entries": [
                   { "weight": 1, "wobble": true,
                     "reward": { "type": "tasked:item", "item": "minecraft:gold_ingot" } } ] } } ]}""");
        assertTrue(containing(unknownField, "unknown field \"wobble\"").severity()
                        == DataProblem.Severity.ERROR,
                "an inline entry's fields are checked now, got: " + messages(unknownField));

        Problems conditioned = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tasked:loot", "inline": { "entries": [
                   { "weight": 1, "reward": { "type": "tasked:item", "item": "minecraft:gold_ingot",
                       "conditions": [ { "type": "tasked:stage", "stage": "my_pack:marked" } ] } } ] } } ]}""");
        assertTrue(containing(conditioned, "cannot carry \"conditions\"").severity()
                        == DataProblem.Severity.ERROR,
                "the inline path refuses it too, got: " + messages(conditioned));

        // And a clean inline table is still clean, so the new walk is not a wall of new errors.
        Problems clean = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tasked:loot", "inline": { "entries": [
                   { "weight": 1,
                     "reward": { "type": "tasked:item", "item": "minecraft:gold_ingot" } } ] } } ]}""");
        assertTrue(clean.isEmpty(), "a well-formed inline table must be clean, got: " + messages(clean));
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

    // ------------------------------------------------------------------
    // Split control points: a per-line pair, refused where it cannot mean anything
    // ------------------------------------------------------------------

    /** A file whose line (a waits on b) is split into two control points. */
    private static final String SPLIT_FILE = """
            {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
              "chapters": [{"id": "c", "title": "C", "quests": [
                {"id": "a", "title": "a", "dependsOn": ["b"],
                 "dependencyLines": {"b": {"form": "curved",
                    "fromHandle": [0.33, 0.2], "toHandle": [0.66, -0.2]}}},
                {"id": "b", "title": "b"}
              ]}]}]}""";

    @Test
    @DisplayName("a split pair on a line is clean")
    void aSplitLineIsClean() {
        Problems problems = validate(SPLIT_FILE);

        assertTrue(problems.isEmpty(), "a well-formed split must be clean, got:" + messages(problems));
    }

    @Test
    @DisplayName("a handle that is not exactly two numbers is refused instead of throwing at load")
    void aMalformedHandleIsRefused() {
        // `[0.33, "x"]` is the pair that matters: a check that answered "two entries, the last one a
        // number" would pass it and then throw on getAsDouble -- a validator crash instead of a report.
        for (String pair : new String[] { "[0.33]", "[0.33, 0.2, 0.1]", "[0.33, \"x\"]", "\"nope\"" }) {
            Problems problems = validate(SPLIT_FILE.replace("[0.33, 0.2]", pair));

            assertTrue(containing(problems, "expected two numbers").severity() == DataProblem.Severity.ERROR,
                    "pair " + pair + " must be refused, got:" + messages(problems));
        }
    }

    @Test
    @DisplayName("a handle dragged out of its range is refused, with the range in the message")
    void aHandleOutOfRangeIsRefused() {
        Problems problems = validate(SPLIT_FILE.replace("[0.66, -0.2]", "[0.66, -2.0]"));

        DataProblem problem = containing(problems, "stay near its line");
        assertTrue(problem.message().contains("-0.9 to 0.9"),
                "the message carries the range it is checking: " + problem.message());
    }

    @Test
    @DisplayName("a split pair in a chapter's default is refused on its own line")
    void aChapterDefaultRefusesSplitHandles() {
        // Same rule as anchors, and for the same reason: where a control point pulls a line is a fact
        // about that line's two ends, so a chapter-wide one would be wrong on almost every edge.
        String extras = "\"dependencyStyle\": {\n                \"fromHandle\": [0.33, 0.2]},";
        String json = Fixtures.fileWithChapter(extras, Fixtures.q("a").build());
        Problems problems = validate(json);
        DataProblem problem = containing(problems, "per line, not per chapter");

        int line = 0;
        String[] lines = json.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("fromHandle")) {
                line = i + 1;
                break;
            }
        }
        assertEquals(line, problem.line(), "the refusal must land where the author wrote it");
    }

    // ------------------------------------------------------------------
    // The line-style vocabulary: the current axes, and the legacy one still read
    // ------------------------------------------------------------------

    /** A file whose line (a waits on b) carries the current arrow vocabulary. */
    private static final String STYLED_FILE = """
            {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
              "chapters": [{"id": "c", "title": "C", "quests": [
                {"id": "a", "title": "a", "dependsOn": ["b"],
                 "dependencyLines": {"b": {"form": "chamfered", "arrowHead": "triangle",
                    "arrowPlace": "stream", "arrowDensity": "high", "dash": "dash_dot",
                    "weight": "conduit"}}},
                {"id": "b", "title": "b"}
              ]}]}]}""";

    @Test
    @DisplayName("the current line vocabulary is clean, new axes and all")
    void theNewVocabularyIsClean() {
        Problems problems = validate(STYLED_FILE);

        assertTrue(problems.isEmpty(), "the new values must validate, got:" + messages(problems));
    }

    @Test
    @DisplayName("an unknown value on a new axis is refused with the names it does know")
    void anUnknownArrowValueIsRefused() {
        Problems problems = validate(STYLED_FILE.replace("\"triangle\"", "\"harpoon\""));

        DataProblem problem = containing(problems, "\"harpoon\" is not a arrowHead this build knows");
        assertTrue(problem.message().contains("chevron"),
                "the message lists the real names: " + problem.message());
    }

    @Test
    @DisplayName("the legacy arrows axis is still accepted, so old files keep validating")
    void theLegacyArrowsAxisIsStillAccepted() {
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
                  "chapters": [{"id": "c", "title": "C", "quests": [
                    {"id": "a", "title": "a", "dependsOn": ["b"],
                     "dependencyLines": {"b": {"arrows": "many", "form": "curved"}}},
                    {"id": "b", "title": "b"}
                  ]}]}]}""");

        assertTrue(problems.isEmpty(), "an old file must still validate, got:" + messages(problems));
    }

    @Test
    @DisplayName("a chapter default may carry the new axes too")
    void aChapterDefaultCarriesTheNewAxes() {
        // Only anchors and split handles are per-line facts; a chapter-wide head, place and density are
        // exactly what a chapter default is for.
        String extras = "\"dependencyStyle\": {\"arrowHead\": \"dot\", \"arrowPlace\": \"mid\"},";
        Problems problems = validate(Fixtures.fileWithChapter(extras, Fixtures.q("a").build()));

        assertTrue(problems.isEmpty(), "a chapter default of the new vocabulary must be clean: "
                + messages(problems));
    }

    private static String file(String quest) {
        return "{\"version\": 1, \"chapterGroups\": [{\"id\": \"g\", \"title\": \"G\", "
                + "\"chapters\": [{\"id\": \"c\", \"title\": \"C\", \"quests\": [" + quest + "]}]}]}";
    }

    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }
}
