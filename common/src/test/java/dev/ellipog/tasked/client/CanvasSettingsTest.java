package dev.ellipog.tasked.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The canvas's thresholds, as a file.
 *
 * <h2>Why a config file gets its own tests</h2>
 *
 * <p>Because it is the one input to the drawing that no test can control from the inside: it is whatever is
 * on the player's disk. So what is asserted here is not "the number is read" but the two things that decide
 * whether a bad file is survivable — <b>a missing or unreadable file leaves the defaults</b>, and <b>a value
 * that cannot mean anything is clamped rather than obeyed</b>. The second is the one worth arguing: a zoom
 * threshold of 5 would mean "never draw a ring" on a canvas that stops at 2.2, and a blocks threshold above
 * the rings threshold would mean a tier that can never be reached. Neither is an error a person would notice;
 * both are a canvas quietly missing things.
 *
 * <p>Deliberately not a test of the *file path*: that is the platform's answer, and the two loaders read it
 * at the same moment as the other three client files.
 */
@DisplayName("the canvas's thresholds")
class CanvasSettingsTest {

    @Test
    @DisplayName("a missing file leaves the defaults, and says nothing")
    void aMissingFileIsAFirstRun() {
        CanvasSettings.load(Path.of("no", "such", "canvas.json"));

        assertEquals(CanvasSettings.DEFAULT_RINGS_BELOW, CanvasSettings.ringsBelow());
        assertEquals(CanvasSettings.DEFAULT_BLOCKS_BELOW, CanvasSettings.blocksBelow());
        assertEquals(CanvasSettings.DEFAULT_ICON_MIN_BOX, CanvasSettings.iconMinBox());
    }

    @Test
    @DisplayName("a file's values are read, and the shape of the file is the shape of the class")
    void aFileIsRead() {
        CanvasSettings.Parsed parsed = CanvasSettings.parse("""
                {"ringsBelow": 0.75, "blocksBelow": 0.4, "iconMinBox": 20}
                """);

        assertEquals(0.75F, parsed.ringsBelow());
        assertEquals(0.4F, parsed.blocksBelow());
        assertEquals(20, parsed.iconMinBox());
    }

    @Test
    @DisplayName("a field the file does not say takes its default, and an unknown field is ignored")
    void unknownAndMissingFields() {
        CanvasSettings.Parsed parsed = CanvasSettings.parse("""
                {"iconMinBox": 8, "whateverThisIs": "ignored"}
                """);

        assertEquals(CanvasSettings.DEFAULT_RINGS_BELOW, parsed.ringsBelow());
        assertEquals(CanvasSettings.DEFAULT_BLOCKS_BELOW, parsed.blocksBelow());
        assertEquals(8, parsed.iconMinBox(), "and the field it does name is still read");
    }

    @Test
    @DisplayName("a number that cannot mean anything is clamped, not obeyed")
    void nonsenseIsClamped() {
        // A zoom threshold beyond the canvas's own range: 5 means "never", which is a canvas missing its
        // rings for a reason nobody would connect to a file.
        assertEquals(1.0F, CanvasSettings.parse("{\"ringsBelow\": 1.0}").ringsBelow());
        assertEquals(4.0F, CanvasSettings.parse("{\"ringsBelow\": 5}").ringsBelow());
        assertEquals(0.05F, CanvasSettings.parse("{\"ringsBelow\": -3}").ringsBelow());

        // And the inverted pair: a blocks tier above the rings tier is a tier that can never be reached, so
        // the blocks value is held at the rings one rather than the file being rejected.
        CanvasSettings.Parsed inverted = CanvasSettings.parse(
                "{\"ringsBelow\": 0.4, \"blocksBelow\": 0.9}");
        assertEquals(0.4F, inverted.ringsBelow());
        assertEquals(0.4F, inverted.blocksBelow(), "held at the rings threshold, so the ladder still ascends");

        // And the icon's box: 0 would mean "never draw an item", 1000 would mean a box bigger than any node.
        assertEquals(1, CanvasSettings.parse("{\"iconMinBox\": 0}").iconMinBox());
        assertEquals(64, CanvasSettings.parse("{\"iconMinBox\": 1000}").iconMinBox());
    }

    @Test
    @DisplayName("a value of the wrong type is the default, not a crash")
    void aWrongTypeIsTheDefault() {
        // This file is hand-edited, so the failure to make survivable is a person typing a word where a
        // number goes -- which is a client that must still start, on the defaults.
        CanvasSettings.Parsed parsed = CanvasSettings.parse(
                "{\"ringsBelow\": \"soon\", \"blocksBelow\": true, \"iconMinBox\": null}");

        assertEquals(CanvasSettings.DEFAULT_RINGS_BELOW, parsed.ringsBelow());
        assertEquals(CanvasSettings.DEFAULT_BLOCKS_BELOW, parsed.blocksBelow());
        assertEquals(CanvasSettings.DEFAULT_ICON_MIN_BOX, parsed.iconMinBox());
    }

    @Test
    @DisplayName("a file that is not JSON at all leaves the defaults in force")
    void aBrokenFileLeavesTheDefaults() throws Exception {
        Path file = Files.createTempFile("tasked-canvas", ".json");
        try {
            Files.writeString(file, "{ this is not json at all", StandardCharsets.UTF_8);
            CanvasSettings.load(file);

            assertEquals(CanvasSettings.DEFAULT_RINGS_BELOW, CanvasSettings.ringsBelow());
            assertEquals(CanvasSettings.DEFAULT_BLOCKS_BELOW, CanvasSettings.blocksBelow());
            assertEquals(CanvasSettings.DEFAULT_ICON_MIN_BOX, CanvasSettings.iconMinBox());
        }
        finally {
            Files.deleteIfExists(file);
        }
    }
}
