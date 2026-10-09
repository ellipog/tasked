package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version-2 per-kind documents: {@code group.json}, {@code chapter.json}, and one quest per file.
 *
 * <h2>What is being tested, and what is deliberately not</h2>
 *
 * <p>Three things, and they are separably interesting:
 *
 * <ol>
 *   <li><b>The manifests decode</b>, and every field survives the trip — including the ones with
 *       defaults, where "absent" and "false" have to stay distinguishable in the only direction that
 *       matters.</li>
 *   <li><b>The per-kind field sets are per-kind.</b> A {@code group.json} carrying
 *       {@code progressionMode} is an error, because that is a chapter's field. This is the check that
 *       makes "per-kind field sets" mean something rather than being a thing the plan says.</li>
 *   <li><b>A child list holds names, not objects.</b> The version-1 shape nested inline; the version-2
 *       shape names a folder. An element copied across is the mistake this is most likely to meet, and
 *       the message says so.</li>
 * </ol>
 *
 * <p>What is <b>not</b> here is anything about the folder tree: which files exist, whether a name
 * resolves, whether a folder is named after its id. That is {@code QuestFiles}' job and
 * {@code QuestFilesTest} covers it. Keeping the two apart is the same division the loader itself makes,
 * and a test that resolved names here would be asserting about a directory it did not build.
 *
 * <h2>Why the two field sets are compared to each other</h2>
 *
 * <p>{@code GroupManifest} and {@code ChapterGroup} describe the same object at two moments — what a
 * folder claims, and what the loader assembled — so they allow the same fields. That is duplicated by
 * necessity, and a comment asking future editors to keep two lists aligned is the arrangement this
 * codebase has already recorded as not working. So it is a test.
 */
@DisplayName("version 2 manifests")
class QuestManifestTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // The validator checks that a named item exists, which reads BuiltInRegistries.ITEM. Without
        // this every item in every fixture reads as missing -- including minecraft:crafting_table --
        // and the tests below would pass for entirely the wrong reason.
        MinecraftTestBootstrap.boot();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Problems validateGroupDoc(String json) {
        JsonDocument document = Fixtures.document("getting_started/group.json", json);
        Problems problems = new Problems();
        QuestValidator.validateGroupDocument(document, problems);
        return problems;
    }

    private static Problems validateChapterDoc(String json) {
        JsonDocument document = Fixtures.document("getting_started/first_steps/chapter.json", json);
        Problems problems = new Problems();
        QuestValidator.validateChapterDocument(document, problems);
        return problems;
    }

    private static Problems validateQuestDoc(String json) {
        JsonDocument document = Fixtures.document("getting_started/first_steps/punch_a_tree.json", json);
        Problems problems = new Problems();
        QuestValidator.validateQuestDocument(document, problems);
        return problems;
    }

    private static <T> T decode(com.mojang.serialization.Codec<T> codec, String json) {
        DataResult<T> result = codec.parse(JsonOps.INSTANCE,
                Fixtures.document("manifest.json", json).root());
        return result.result().orElseThrow(() -> new AssertionError("did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }

    private static void assertMentions(Problems problems, String text) {
        assertTrue(messages(problems).contains(text),
                "expected a problem mentioning '" + text + "', got:" + messages(problems));
    }

    // ------------------------------------------------------------------
    // A group manifest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("group.json")
    class Group {

        @Test
        @DisplayName("decodes, and its chapter names come back in the order they were written")
        void decodesWithNamesInOrder() {
            // The order is the author's, and nothing else can express it -- there is no file above the
            // group folders to declare an order, so within a group this list is the only source of one.
            GroupManifest manifest = decode(GroupManifest.CODEC, """
                    {
                      "$schema": "../_schema/group.schema.json",
                      "id": "getting_started",
                      "title": "Getting Started",
                      "description": ["Where everybody starts."],
                      "aliases": ["the_beginning"],
                      "chapters": ["first_steps", "tools", "farming"]
                    }
                    """);

            assertEquals("getting_started", manifest.id());
            assertEquals("Getting Started", manifest.title().value());
            assertEquals(List.of("Where everybody starts."),
                    manifest.description().stream().map(QuestText::value).toList());
            assertEquals(List.of("the_beginning"), manifest.aliases());
            assertEquals(List.of("first_steps", "tools", "farming"), manifest.chapters());
        }

        @Test
        @DisplayName("collapsedByDefault is false when absent, which is what a converted file needs")
        void collapsedDefaultsToFalse() {
            // Not a detail. The field is new, so every version-1 file -- and every group folder written
            // before this existed -- has it absent. Absent has to mean "as it has always looked", which
            // is open, or converting a pack would silently collapse a book a player already knows.
            GroupManifest manifest = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "chapters": [] }
                    """);

            assertFalse(manifest.collapsedByDefault());
        }

        @Test
        @DisplayName("and true when it says so")
        void collapsedCanBeAsked() {
            GroupManifest manifest = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "collapsedByDefault": true, "chapters": [] }
                    """);

            assertTrue(manifest.collapsedByDefault());
        }

        @Test
        @DisplayName("the icon is optional, and absent stays distinguishable from one authored as paper")
        void iconIsOptional() {
            // Unlike a chapter's icon, whose codec defaults it to paper, a group's has to be able to say
            // "I declared none": the client falls back to the first chapter under the group for that
            // case, and a default of paper here would make the fallback unreachable.
            GroupManifest none = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "chapters": [] }
                    """);
            assertTrue(none.icon().isEmpty(), "a group that declares no icon has none");

            GroupManifest one = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "icon": { "item": "minecraft:anvil" }, "chapters": [] }
                    """);
            assertEquals("minecraft:anvil", one.icon().orElseThrow().describe());

            Problems problems = validateGroupDoc("""
                    { "id": "g", "title": "G", "icon": { "item": "minecraft:anvil" }, "chapters": [] }
                    """);
            // Errors, not "nothing at all": an empty chapter list is a warning this document earns
            // whatever its icon says, and what is being checked here is that the icon is not refused
            // as an unknown field the way `progressionMode` above is.
            assertFalse(problems.hasErrors(), "the icon is a known field:" + messages(problems));
        }

        @Test
        @DisplayName("a one-line description written as a bare string still works")
        void aBareStringDescriptionWorks() {
            // The same union a chapter group's description has always had, and it came with the field
            // when the field moved. A one-line group description written as a string is not a mistake
            // worth failing a file over.
            GroupManifest manifest = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "description": "one line", "chapters": [] }
                    """);

            assertEquals(List.of("one line"), manifest.description().stream().map(QuestText::value).toList());
        }

        @Test
        @DisplayName("a well-formed document reports nothing at all")
        void aCleanDocumentIsSilent() {
            Problems problems = validateGroupDoc("""
                    {
                      "$schema": "../_schema/group.schema.json",
                      "id": "getting_started",
                      "title": "Getting Started",
                      "chapters": ["first_steps"]
                    }
                    """);

            assertTrue(problems.isEmpty(), "expected no problems, got:" + messages(problems));
        }

        @Test
        @DisplayName("a chapter's field in a group's document is an unknown field, which is the point of per-kind sets")
        void aChapterFieldIsRefusedInAGroup() {
            // The assertion that makes "per-kind field sets" mean something. `progressionMode` is a
            // perfectly good field -- in a chapter. In a group it is nothing, and a validator that
            // allowed every field everywhere would let it sit there looking supported forever.
            Problems problems = validateGroupDoc("""
                    { "id": "g", "title": "G", "progressionMode": "linear", "chapters": [] }
                    """);

            assertTrue(problems.hasErrors(), "a chapter field in a group is fatal:" + messages(problems));
            assertMentions(problems, "unknown field \"progressionMode\"");
        }

        @Test
        @DisplayName("so is a quest's field, and the message names it rather than the whole file")
        void aQuestFieldIsRefusedInAGroup() {
            // Task and reward fields, position and shape: all quest-level, none belonging here. The
            // message has to name the field, or an author reads "unknown field" beside an object with
            // nine legitimately-known ones and has to work out which.
            Problems problems = validateGroupDoc("""
                    { "id": "g", "title": "G", "dependsOn": ["a"], "chapters": [] }
                    """);

            assertMentions(problems, "unknown field \"dependsOn\"");
        }

        @Test
        @DisplayName("a nested chapter object where a name belongs says what to do about it")
        void aNestedObjectWhereANameBelongsIsNamedAsTheFormatChange() {
            // The mistake this is most likely to meet: an element copied out of a version-1 file into a
            // version-2 list. "Expected a string, found an object" is a true statement that leaves the
            // author looking at a field that looks right to them, so the message names the change.
            Problems problems = validateGroupDoc("""
                    { "id": "g", "title": "G", "chapters": [ { "id": "one", "title": "One" } ] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "expected the name of a chapter as a string");
            assertMentions(problems, "found an object");
            assertMentions(problems, "version-2 layout");
        }

        @Test
        @DisplayName("a missing title is still required, exactly as it is in version 1")
        void aMissingTitleIsRequired() {
            // The per-kind checks are the *same* checks at a different root, so this is a check that
            // they were genuinely shared rather than reimplemented: a second implementation would be a
            // second place for the requirement to be forgotten.
            Problems problems = validateGroupDoc("""
                    { "id": "g", "chapters": [] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "missing required field title");
        }

        @Test
        @DisplayName("and a version field warns rather than refusing, because the file still loads")
        void aVersionFieldWarnsRatherThanRefusing() {
            // A group.json cannot be anything but version 2 -- the folder it sits in settles it, the same
            // way a flat file at the root is version 1 by position. A version number would be a second
            // answer to a settled question, so it is still reported -- but as a warning naming the
            // removal, not as a refusal: the codec never read it, and refusing the file over one
            // ignored number would cost the whole group. See QuestValidator's retired-fields rule.
            Problems problems = validateGroupDoc("""
                    { "id": "g", "title": "G", "version": 2, "chapters": [] }
                    """);

            assertFalse(problems.hasErrors(), messages(problems));
            assertMentions(problems, "\"version\" means nothing here");
        }
    }

    // ------------------------------------------------------------------
    // A chapter manifest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("chapter.json")
    class ChapterDoc {

        @Test
        @DisplayName("decodes, and its quest file names come back in the order they were written")
        void decodesWithQuestNamesInOrder() {
            // The load-bearing one. For a LINEAR chapter this order *is* the progression -- there are no
            // dependsOn edges to read -- so a list that came back shuffled would be a chapter that
            // unlocks its quests in the wrong sequence, with nothing anywhere reporting a fault.
            ChapterManifest manifest = decode(ChapterManifest.CODEC, """
                    {
                      "$schema": "../../_schema/chapter.schema.json",
                      "id": "first_steps",
                      "title": "First Steps",
                      "icon": { "item": "minecraft:crafting_table" },
                      "progressionMode": "linear",
                      "quests": ["punch_a_tree.json", "make_a_table.json", "stone_tools.json"]
                    }
                    """);

            assertEquals("first_steps", manifest.id());
            assertEquals(ProgressionMode.LINEAR, manifest.progressionMode());
            assertEquals(List.of("punch_a_tree.json", "make_a_table.json", "stone_tools.json"),
                    manifest.quests());
        }

        @Test
        @DisplayName("a chapter that declared no icon is told apart from one that declared paper")
        void anAbsentChapterIconIsNotPaper() {
            // The property `QuestSync.chapterIcon` rests on, and it is asserted here rather than assumed
            // because it is a codec's behaviour rather than this project's: `optionalFieldOf` substitutes
            // the *same instance* when the field is absent, so `==` separates "the file said nothing"
            // from "the file said paper" -- and comparing by value cannot, because the two are equal.
            //
            // What it buys: a chapter that authored no icon sends an empty id, which the client already
            // reads as "no icon", instead of sending a paper item that draws as a blank white square on
            // the claim menu's banner. And a chapter that genuinely wants paper keeps it.
            ChapterManifest absent = decode(ChapterManifest.CODEC, """
                    { "id": "c", "title": "C", "quests": [] }
                    """);
            assertSame(Icon.DEFAULT_ICON, absent.icon(),
                    "an absent icon is the default instance itself, not a copy of it");

            ChapterManifest authored = decode(ChapterManifest.CODEC, """
                    { "id": "c", "title": "C", "icon": { "item": "minecraft:paper" }, "quests": [] }
                    """);
            assertEquals("minecraft:paper", authored.icon().describe(), "authored paper is paper");
            assertNotSame(Icon.DEFAULT_ICON, authored.icon(),
                    "and it is a different instance, which is the only thing that tells the two apart");
        }

        @Test
        @DisplayName("every chapter default survives the trip, so a converted file keeps its behaviour")
        void everyChapterDefaultSurvives() {
            // These five are what makes a chapter more than a list of quests: the defaults a quest
            // inherits, and the palette it asks to be drawn in. A conversion that dropped one would not
            // fail to load -- it would load and behave differently, which is the worse outcome.
            ChapterManifest manifest = decode(ChapterManifest.CODEC, """
                    {
                      "id": "smithing",
                      "title": "Smithing",
                      "subtitle": "Hot work",
                      "description": ["One paragraph.", "", "And another."],
                      "icon": { "item": "minecraft:anvil" },
                      "aliases": ["toolsmith"],
                      "defaultPrerequisiteMode": "one_completed",
                      "progressionMode": "linear",
                      "defaultConsumeItems": true,
                      "autoClaim": "no_toast",
                      "theme": "copper",
                      "quests": ["a.json"]
                    }
                    """);

            assertEquals("Hot work", manifest.subtitle().orElseThrow().value());
            assertEquals(3, manifest.description().size(), "a blank paragraph is a line break, not a mistake");
            assertEquals("minecraft:anvil", manifest.icon().describe());
            assertEquals(List.of("toolsmith"), manifest.aliases());
            assertEquals(PrerequisiteMode.ONE_COMPLETED, manifest.defaultPrerequisiteMode());
            assertEquals(ProgressionMode.LINEAR, manifest.progressionMode());
            assertTrue(manifest.defaultConsumeItems());
            assertEquals(dev.ellipog.tenet.quest.reward.RewardAutoClaim.NO_TOAST, manifest.autoClaim(),
                    "the auto-claim default a chapter sets survives the conversion");
            assertEquals("copper", manifest.theme().orElseThrow());
        }

        @Test
        @DisplayName("the defaults are the same as a version-1 chapter's when nothing is declared")
        void defaultsMatchVersionOne() {
            // FLEXIBLE and ALL_COMPLETED, and both for the reasons Chapter's javadoc gives: a chapter
            // where declaring a dependency silently did nothing, because the chapter was linear, is a
            // confusing thing to debug. A conversion must not change a behaviour by defaulting
            // differently from the format it came from.
            ChapterManifest manifest = decode(ChapterManifest.CODEC, """
                    { "id": "c", "title": "C", "quests": [] }
                    """);

            assertEquals(ProgressionMode.FLEXIBLE, manifest.progressionMode(),
                    "which is Chapter's own default, and the codec says so in the same words");
            assertEquals(PrerequisiteMode.ALL_COMPLETED, manifest.defaultPrerequisiteMode());
            assertFalse(manifest.defaultConsumeItems(), "silently taking a player's items is the surprising one");
            assertTrue(manifest.theme().isEmpty(), "absent means no opinion, not the default theme");
            assertTrue(manifest.subtitle().isEmpty());
        }

        @Test
        @DisplayName("a group's field in a chapter's document is an unknown field")
        void aGroupFieldIsRefusedInAChapter() {
            // The per-kind check from the other side, and it is the same assertion rather than a mirror
            // of it: `chapters` is a group's field, and a chapter holding a chapter is the nesting the
            // group level exists to prevent.
            Problems problems = validateChapterDoc("""
                    { "id": "c", "title": "C", "chapters": ["nested"], "quests": [] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "unknown field \"chapters\"");
        }

        @Test
        @DisplayName("a nested quest object where a file name belongs names the format change")
        void aNestedQuestWhereANameBelongsIsNamed() {
            Problems problems = validateChapterDoc("""
                    { "id": "c", "title": "C", "quests": [ { "id": "q", "title": "Q" } ] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "expected the name of a quest as a string");
            assertMentions(problems, "version-2 layout");
        }

        @Test
        @DisplayName("a theme name that is empty is refused, and an unrecognised one is not")
        void anEmptyThemeIsRefusedButAnUnknownOneIsNot() {
            // The boundary Chapter's javadoc draws, and it has to be drawn the same way here or a
            // converted file behaves differently from the one it was converted from. An empty string is
            // not "no opinion" -- it is a chapter asking for a theme called nothing, which can only ever
            // be reported as unrecognised. An unrecognised *name* is a client's business: the catalogue
            // lives on the client, and a dedicated server has no appearance to check it against.
            Problems empty = validateChapterDoc("""
                    { "id": "c", "title": "C", "theme": "", "quests": [] }
                    """);
            Problems unknown = validateChapterDoc("""
                    { "id": "c", "title": "C", "theme": "no_such_theme", "quests": [] }
                    """);

            assertTrue(empty.hasErrors(), "an empty theme name can never resolve:" + messages(empty));
            assertMentions(empty, "a theme name may not be empty");
            assertFalse(unknown.hasErrors(),
                    "an unknown name is the client's to report, once, naming the chapter:" + messages(unknown));
        }

        @Test
        @DisplayName("an enum value that is close to a real one lists the options")
        void aBadEnumListsTheOptions() {
            Problems problems = validateChapterDoc("""
                    { "id": "c", "title": "C", "progressionMode": "lineaer", "quests": [] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "is not one of");
            assertMentions(problems, "linear");
        }
    }

    // ------------------------------------------------------------------
    // One quest per file
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a whole quest in one file")
    class QuestDocument {

        @Test
        @DisplayName("is validated as one quest, with its fields at the root")
        void aQuestDocumentIsOneQuest() {
            // The path is the only thing that differs from a version-1 quest -- its fields sit at `$`
            // instead of `$.chapterGroups[0].chapters[0].quests[3]` -- so this asserts the field checks
            // followed the quest rather than being reimplemented for it.
            Problems problems = validateQuestDoc("""
                    {
                      "$schema": "../../../_schema/quest.schema.json",
                      "id": "punch_a_tree",
                      "title": "Punch a Tree",
                      "icon": { "item": "minecraft:oak_log" },
                      "tasks": [ { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 } ],
                      "rewards": [ { "type": "tenet:item", "item": "minecraft:wooden_axe", "count": 1 } ]
                    }
                    """);

            assertTrue(problems.isEmpty(), "expected no problems, got:" + messages(problems));
        }

        @Test
        @DisplayName("a chapter's field in a quest's file is an unknown field, and is reported at the root")
        void aChapterFieldIsRefusedInAQuest() {
            // The error has to land at `$...progressionMode` rather than at some path computed from the
            // version-1 nesting, which is exactly the class of fault the whole per-kind split exists to
            // avoid: a line number that points into a document the author is not looking at.
            Problems problems = validateQuestDoc("""
                    { "id": "q", "title": "Q", "progressionMode": "linear", "tasks": [] }
                    """);

            assertTrue(problems.hasErrors(), messages(problems));
            assertMentions(problems, "unknown field \"progressionMode\"");
            assertEquals(1, problems.forFile("getting_started/first_steps/punch_a_tree.json").size(),
                    "one message, at the file it is about:" + messages(problems));
        }

        @Test
        @DisplayName("a typo inside a quest is reported with the line it is on, as a nested one would be")
        void aTypoInsideAQuestNamesItsLine() {
            // The property the whole per-kind split is for, asserted rather than assumed: a problem
            // inside a quest file names a line *of that file*. With one document per file the path
            // builder has nothing to nest, and the number it points at is the number an author counts in
            // their editor -- which is the thing that stops being true when one document serves a whole
            // tree.
            String json = """
                    {
                      "id": "punch_a_tree",
                      "title": "Punch a Tree",
                      "icon": { "item": "minecraft:oak_log" },
                      "tasks": [ { "type": "tenet:item", "item": "minecraft:oak_log", "counnt": 8 } ]
                    }
                    """;

            Problems problems = validateQuestDoc(json);

            assertTrue(problems.hasErrors(), messages(problems));
            DataProblem problem = problems.all().stream()
                    .filter(candidate -> candidate.message().contains("unknown field \"counnt\""))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no problem about counnt:" + messages(problems)));

            // Derived from the fixture rather than written as a number, so this stays honest if the
            // fixture is reformatted and still fails if the position drifts.
            int expectedLine = 1;
            for (String line : json.split("\n", -1)) {
                if (line.contains("\"counnt\"")) {
                    break;
                }
                expectedLine++;
            }
            assertEquals(expectedLine, problem.line(),
                    "the typo is on line " + expectedLine + " of this file, and the message should point "
                            + "at it -- line " + problem.line() + " is a line of somewhere else");
        }
    }

    // ------------------------------------------------------------------
    // One field set per kind, declared once
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("field sets")
    class FieldSets {

        @Test
        @DisplayName("a manifest and the thing it becomes allow the same fields")
        void manifestFieldSetsMatchTheObjectsTheyBecome() {
            // The duplication the two record pairs necessarily have -- a manifest describes what a
            // folder claims, a group is what the loader assembled, and they allow the same fields -- is
            // kept in step by this rather than by a comment. A comment asking future editors to keep two
            // lists aligned is the arrangement this codebase has already recorded as not working.
            assertEquals(ChapterGroup.FIELDS, GroupManifest.FIELDS,
                    "a group.json may carry exactly the fields a version-1 inline group may");
            assertEquals(Chapter.FIELDS, ChapterManifest.FIELDS,
                    "and a chapter.json exactly the fields a version-1 inline chapter may");
        }

        @Test
        @DisplayName("collapsedByDefault is in the group's set, which is what a version-1 file needs too")
        void collapsedByDefaultIsAllowedInBothFormats() {
            // The field is new and belongs to the object, not to the file layout, so both formats allow
            // it. Asserted by name because the failure mode of getting it wrong is pointed: the codec
            // reads the field, the validator calls it unknown, and the same file loads with checking
            // disabled and is refused with it on.
            assertTrue(ChapterGroup.FIELDS.contains("collapsedByDefault"));
            assertTrue(GroupManifest.FIELDS.contains("collapsedByDefault"));

            Problems inline = new Problems();
            QuestValidator.validate(Fixtures.document("v1.json", """
                    { "version": 1, "chapterGroups": [
                        { "id": "g", "title": "G", "collapsedByDefault": true, "chapters": [] } ] }
                    """), inline);

            assertFalse(inline.hasErrors(),
                    "a version-1 file may ask for a collapsed group too -- it is a property of the group, "
                            + "not of the layout:" + messages(inline));
        }

        @Test
        @DisplayName("a version-1 file still decodes, with collapsedByDefault defaulted to false")
        void versionOneStillDecodes() {
            // Read forever, and the new component must not change what an existing file means. A file
            // written before the field existed has it absent, and absent has to be "as it has always
            // looked" -- open.
            QuestFile file = decode(QuestFile.CODEC, """
                    { "version": 1, "chapterGroups": [
                        { "id": "g", "title": "G", "chapters": [
                            { "id": "c", "title": "C", "quests": [] } ] } ] }
                    """);

            ChapterGroup group = file.chapterGroups().get(0);
            assertFalse(group.collapsedByDefault(), "an existing file's groups stay open");
            assertEquals("c", group.chapters().get(0).id(), "and the inline chapters are still assembled");
        }

        @Test
        @DisplayName("a manifest becomes its assembled object with every field carried across")
        void manifestsBecomeTheirObjects() {
            // toGroup and toChapter are the only places the record pairs meet, so they are the only
            // places a field can be dropped on the way. Asserted by building both a manifest and the
            // object it must produce and comparing them, rather than by checking two fields and hoping.
            GroupManifest groupManifest = decode(GroupManifest.CODEC, """
                    { "id": "g", "title": "G", "description": "one line", "aliases": ["old"],
                      "collapsedByDefault": true, "chapters": ["one"] }
                    """);
            Chapter built = decode(ChapterManifest.CODEC, """
                    { "id": "one", "title": "One", "defaultPrerequisiteMode": "all_started",
                      "progressionMode": "linear", "defaultConsumeItems": true, "theme": "tome",
                      "quests": ["a.json"] }
                    """).toChapter(List.of());

            ChapterGroup assembled = groupManifest.toGroup(List.of(built));

            assertEquals(groupManifest.id(), assembled.id());
            assertEquals(groupManifest.title(), assembled.title());
            assertEquals(groupManifest.description(), assembled.description());
            assertEquals(groupManifest.aliases(), assembled.aliases());
            assertEquals(groupManifest.collapsedByDefault(), assembled.collapsedByDefault());
            assertEquals(List.of(built), assembled.chapters());

            ChapterManifest chapterManifest = decode(ChapterManifest.CODEC, """
                    { "id": "one", "title": "One", "defaultPrerequisiteMode": "all_started",
                      "progressionMode": "linear", "defaultConsumeItems": true, "theme": "tome",
                      "quests": ["a.json"] }
                    """);
            Chapter roundTripped = chapterManifest.toChapter(List.of());

            assertEquals(chapterManifest.id(), roundTripped.id());
            assertEquals(chapterManifest.title(), roundTripped.title());
            assertEquals(chapterManifest.subtitle(), roundTripped.subtitle());
            assertEquals(chapterManifest.description(), roundTripped.description());
            assertEquals(chapterManifest.icon(), roundTripped.icon());
            assertEquals(chapterManifest.aliases(), roundTripped.aliases());
            assertEquals(chapterManifest.defaultPrerequisiteMode(), roundTripped.defaultPrerequisiteMode());
            assertEquals(chapterManifest.progressionMode(), roundTripped.progressionMode());
            assertEquals(chapterManifest.defaultConsumeItems(), roundTripped.defaultConsumeItems());
            assertEquals(chapterManifest.theme(), roundTripped.theme());
        }

        @Test
        @DisplayName("the problem messages name the file they are about")
        void problemsNameTheFile() {
            // Two files with the same shape and different names, so a reader can tell them apart. This is
            // the property the per-declaration document is for: with one document per declaration, a
            // problem's file name is a file on disk rather than a member of a tree.
            Problems group = validateGroupDoc("{ \"id\": \"g\", \"chapters\": [] }");
            Problems chapter = validateChapterDoc("{ \"id\": \"c\", \"quests\": [] }");

            assertTrue(group.all().stream().allMatch(p -> p.file().equals("getting_started/group.json")),
                    messages(group));
            assertTrue(chapter.all().stream()
                            .allMatch(p -> p.file().equals("getting_started/first_steps/chapter.json")),
                    messages(chapter));
        }
    }
}
