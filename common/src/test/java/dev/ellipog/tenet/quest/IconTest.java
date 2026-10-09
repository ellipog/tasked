package dev.ellipog.tenet.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.armature.api.data.Problems;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon union: an item, a texture file, or an entity drawn as its egg.
 *
 * <p>Batch 3 of the migration work. FTB Quests' icons can be {@code custom_icon} texture paths or
 * {@code entity_face} entity ids; this mod knew only items, so a converted pack's pictures had
 * nowhere to land. Each test decodes the JSON the tool would write through the real codec, or runs
 * the real validator over it, which is what makes this page fail first if an arm quietly becomes
 * required or changes meaning.
 */
@DisplayName("icon union: item, texture, or entity")
class IconTest {

    private static Icon icon(String text) {
        DataResult<Icon> result = Icon.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text));
        return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static Quest quest(String text) {
        DataResult<Quest> result = Quest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text));
        return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    // ------------------------------------------------------------------
    // The three arms
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the arms")
    class Arms {

        @Test
        @DisplayName("an item object is the item arm, exactly as yesterday's files write it")
        void itemArmIsTheOldShape() {
            Icon parsed = icon("""
                    {"item": "minecraft:oak_log", "count": 8}""");

            var item = assertInstanceOf(Icon.Item.class, parsed);
            assertEquals("minecraft:oak_log", item.ref().item().toString());
            assertEquals(8, item.ref().count());
        }

        @Test
        @DisplayName("a texture object is the texture arm")
        void textureArm() {
            Icon parsed = icon("""
                    {"texture": "my_pack:textures/gui/emblem.png"}""");

            var texture = assertInstanceOf(Icon.Texture.class, parsed);
            assertEquals("my_pack:textures/gui/emblem.png", texture.texture().toString());
        }

        @Test
        @DisplayName("an entity object is the entity arm")
        void entityArm() {
            Icon parsed = icon("""
                    {"entity": "minecraft:creeper"}""");

            var entity = assertInstanceOf(Icon.Entity.class, parsed);
            assertEquals("minecraft:creeper", entity.entity().toString());
        }

        @Test
        @DisplayName("an object with no arm key is refused, naming the three keys")
        void noArmIsRefused() {
            DataResult<Icon> result =
                    Icon.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}"));

            assertTrue(result.error().isPresent(), "an armless icon must not decode");
            assertTrue(result.error().orElseThrow().message().contains("texture"),
                    "and the message names the arms: " + result.error().orElseThrow().message());
        }

        @Test
        @DisplayName("each arm encodes back to the object it decoded from")
        void armsRoundTrip() {
            for (String text : new String[] {
                    """
                            {"item": "minecraft:stone", "count": 2}""",
                    """
                            {"texture": "my_pack:textures/gui/emblem.png"}""",
                    """
                            {"entity": "minecraft:creeper"}""" }) {
                Icon parsed = icon(text);
                Icon decoded = icon(Icon.CODEC.encodeStart(JsonOps.INSTANCE, parsed)
                        .getOrThrow().toString());
                assertEquals(parsed, decoded, "round trip of " + text.trim());
            }
        }
    }

    // ------------------------------------------------------------------
    // Where icons live
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the homes")
    class Homes {

        @Test
        @DisplayName("a quest that says nothing wears paper, like every file before the union")
        void absentIsPaper() {
            assertEquals(Icon.DEFAULT_ICON, quest("""
                    {"id": "one", "title": "One"}""").icon());
        }

        @Test
        @DisplayName("a quest, a chapter and a group all read the texture and entity arms")
        void allHomesReadAllArms() {
            assertInstanceOf(Icon.Texture.class, quest("""
                    {"id": "one", "title": "One",
                     "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""").icon());
            assertInstanceOf(Icon.Entity.class, quest("""
                    {"id": "one", "title": "One",
                     "icon": {"entity": "minecraft:creeper"}}""").icon());

            DataResult<Chapter> chapter = Chapter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    """
                            {"id": "c", "title": "C",
                             "icon": {"texture": "my_pack:textures/gui/emblem.png"}}"""));
            assertInstanceOf(Icon.Texture.class, chapter.getOrThrow().icon());

            DataResult<ChapterGroup> group = ChapterGroup.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("""
                            {"id": "g", "title": "G",
                             "icon": {"entity": "minecraft:creeper"}}"""));
            assertInstanceOf(Icon.Entity.class, group.getOrThrow().icon().orElseThrow());
        }

        @Test
        @DisplayName("the book reads a bare string, an empty string, and an object")
        void bookIconReadsAllShapes() {
            assertEquals(Optional.empty(), settings("{\"bookIcon\": \"\"}").bookIcon(),
                    "empty is no icon");
            Icon.Item legacy = assertInstanceOf(Icon.Item.class,
                    settings("{\"bookIcon\": \"minecraft:spyglass\"}").bookIcon().orElseThrow());
            assertEquals("minecraft:spyglass", legacy.ref().item().toString());
            assertInstanceOf(Icon.Texture.class,
                    settings("{\"bookIcon\": {\"texture\": \"my_pack:textures/gui/emblem.png\"}}")
                            .bookIcon().orElseThrow());
            assertEquals(Optional.empty(), settings("{}").bookIcon(), "absent is no icon");
        }

        private static QuestSettings settings(String text) {
            DataResult<QuestSettings> result =
                    QuestSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text));
            return result.result().orElseThrow(() -> new AssertionError(text + " did not decode: "
                    + result.error().map(DataResult.Error::message).orElse("no message")));
        }
    }

    // ------------------------------------------------------------------
    // The validator's sentences
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("validator")
    class Validation {

        private static Problems validateQuest(String json) {
            Problems problems = new Problems();
            QuestValidator.validateQuestDocument(Fixtures.document("quest.json", json), problems);
            return problems;
        }

        @Test
        @DisplayName("all three arms are known fields on a quest icon")
        void allArmsAreKnown() {
            assertFalse(validateQuest("""
                    {"id": "one", "title": "One",
                     "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""").hasErrors());
            assertFalse(validateQuest("""
                    {"id": "one", "title": "One",
                     "icon": {"entity": "minecraft:creeper"}}""").hasErrors());
            assertFalse(validateQuest("""
                    {"id": "one", "title": "One",
                     "icon": {"item": "minecraft:stone"}}""").hasErrors());
        }

        @Test
        @DisplayName("a blank texture path is an error, not a picture of nothing")
        void blankTextureIsAnError() {
            assertTrue(validateQuest("""
                    {"id": "one", "title": "One", "icon": {"texture": "  "}}""").hasErrors());
        }

        @Test
        @DisplayName("a blank entity id is an error")
        void blankEntityIsAnError() {
            assertTrue(validateQuest("""
                    {"id": "one", "title": "One", "icon": {"entity": ""}}""").hasErrors());
        }

        @Test
        @DisplayName("an entity from a missing mod is a warning, and the quest still loads")
        void unknownEntityIsAWarning() {
            Problems problems = validateQuest("""
                    {"id": "one", "title": "One",
                     "icon": {"entity": "no_such_mod:some_mob"}}""");

            assertFalse(problems.hasErrors(), "a missing mod must not cost the quest");
            var warning = problems.all().stream()
                    .filter(problem -> problem.message().contains("no_such_mod:some_mob"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the missing mod is still said, naming it"));
            assertEquals(dev.ellipog.armature.api.data.DataProblem.Severity.WARNING,
                    warning.severity(), "a warning, like an unknown item");
        }
    }
}
