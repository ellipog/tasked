package dev.ellipog.tenet.quest;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree's own settings, as a file writes them.
 */
class QuestSettingsTest {

    private static QuestSettings read(String json) {
        return QuestSettings.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    @DisplayName("a canvas click runs as its presser unless the pack says otherwise")
    void clickCommandLevelDefaultsToZero() {
        assertEquals(0, QuestSettings.DEFAULTS.clickCommandLevel(),
                "the player's own level, until an author asks for elevated");
        assertEquals(0, read("{}").clickCommandLevel(), "absent is the default, not a refusal");
        assertEquals(2, read("{ \"clickCommandLevel\": 2 }").clickCommandLevel(),
                "elevated enough for say, give and summon");
    }

    @Test
    @DisplayName("the level never leaves zero to two, whatever the file claims")
    void clickCommandLevelIsBounded() {
        // The codec refuses rather than clamps, because the settings load is all-or-nothing already:
        // a file that claims level 9 is a file its author should hear about, and the loader's own
        // warning names it. What matters here is that no value outside the range can arrive.
        assertTrue(QuestSettings.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{ \"clickCommandLevel\": 9 }")).error()
                        .isPresent(),
                "level 9 is not a level");
        assertTrue(QuestSettings.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{ \"clickCommandLevel\": -1 }")).error()
                        .isPresent(),
                "and neither is level -1");
    }

    @Test
    @DisplayName("the settings schema offers the field the codec reads")
    void theFieldIsDeclared() {
        assertTrue(QuestSettings.FIELDS.contains("clickCommandLevel"),
                "or the schema comparison names it as undocumented");
    }
}
