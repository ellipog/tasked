package dev.ellipog.tenet.quest;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line's style: which axes a file may say, and how the three layers of "what is this line" resolve.
 *
 * <h2>The one thing that must not go wrong</h2>
 *
 * <p>An override that names a single axis must leave the others to the chapter. A record of concrete
 * enums, or a parse that filled missing axes with built-ins, would reset a line's arrows every time
 * somebody changed its form — silently, and only on the lines that had been customised, which is the
 * hardest kind of report to act on. Every test here is about that in some form.
 */
@DisplayName("DependencyStyle")
class DependencyStyleTest {

    private static DependencyStyle parse(String json) {
        return DependencyStyle.from(com.google.gson.JsonParser.parseString(json));
    }

    @Test
    @DisplayName("an object naming one axis sets that axis and nothing else")
    void aPartialOverrideSaysOnlyWhatItNames() {
        DependencyStyle style = parse("{\"form\":\"curved\"}");

        assertEquals(Optional.of(DependencyStyle.Form.CURVED), style.form());
        assertTrue(style.arrows().isEmpty(), "the arrows were not mentioned and must stay unsaid");
        assertTrue(style.dash().isEmpty());
        assertTrue(style.weight().isEmpty());
    }

    @Test
    @DisplayName("an override layers over a chapter default, which layers over the built-ins")
    void theLayersResolveInOrder() {
        DependencyStyle chapter = parse("{\"arrows\":\"many\",\"weight\":\"thick\"}");
        DependencyStyle line = parse("{\"form\":\"straight\"}");

        DependencyStyle resolved = line.over(chapter).resolved();

        assertEquals(DependencyStyle.Form.STRAIGHT, resolved.formOr(null), "the line wins");
        assertEquals(DependencyStyle.Arrows.MANY, resolved.arrowsOr(null), "then the chapter");
        assertEquals(DependencyStyle.Weight.THICK, resolved.weightOr(null));
        assertEquals(DependencyStyle.Dash.SOLID, resolved.dashOr(null), "then the built-in");
    }

    @Test
    @DisplayName("a value this build does not know is left unsaid rather than refusing the tree")
    void unknownValuesAreLenient() {
        // A client must be able to draw a pack written by a newer server. Saying so is the validator's
        // job, at the file's own line, on the side that can refuse it.
        DependencyStyle style = parse("{\"form\":\"spiral\",\"arrows\":\"many\"}");

        assertTrue(style.form().isEmpty());
        assertEquals(Optional.of(DependencyStyle.Arrows.MANY), style.arrows());
        assertFalse(DependencyStyle.known(DependencyStyle.Form.class, "spiral"));
        assertTrue(DependencyStyle.known(DependencyStyle.Form.class, "Curved"), "case-insensitively");
    }

    @Test
    @DisplayName("writing a style writes only the axes it sets")
    void asJsonKeepsAnOverrideAnOverride() {
        JsonObject json = parse("{\"form\":\"curved\"}").asJson();

        assertEquals(1, json.size(), "a file that said one axis must not come back saying four: " + json);
        assertEquals("curved", json.get("form").getAsString());
    }

    @Test
    @DisplayName("the codec reads the same shape the hand parse does, and defaults to nothing said")
    void theCodecAgreesWithTheFileShape() {
        DependencyStyle decoded = DependencyStyle.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString("{\"dash\":\"dashed\"}"))
                .getOrThrow();

        assertEquals(Optional.of(DependencyStyle.Dash.DASHED), decoded.dash());
        assertTrue(decoded.form().isEmpty(), "unsaid, not defaulted — that is the whole design");
        assertTrue(DependencyStyle.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().isUnset(),
                "an empty object says nothing at all");
    }

    @Test
    @DisplayName("the built-in default is the look a file that says nothing gets")
    void theBuiltInDefault() {
        DependencyStyle resolved = DependencyStyle.UNSET.resolved();

        assertEquals(DependencyStyle.Form.CHAMFERED, resolved.formOr(null),
                "the built-in form is the circuit trace");
        assertEquals(DependencyStyle.ArrowHead.CHEVRON, resolved.headOr(null),
                "an arrow, because a dependency has a direction");
        assertEquals(DependencyStyle.ArrowPlace.TARGET, resolved.placeOr(null));
        assertEquals(DependencyStyle.Dash.SOLID, resolved.dashOr(null));
        assertEquals(DependencyStyle.Weight.THIN, resolved.weightOr(null));
        assertTrue(resolved.arrows().isEmpty(),
                "the built-ins speak the current vocabulary now; the legacy axis is only for files");
    }

    @Test
    @DisplayName("a split's control points parse as a pair and write back as one")
    void splitHandlesRoundTrip() {
        DependencyStyle style = parse("{\"fromHandle\":[0.33,0.2],\"toHandle\":[0.66,-0.15]}");

        assertEquals(List.of(0.33, 0.2), style.fromHandle().orElseThrow());
        assertEquals(List.of(0.66, -0.15), style.toHandle().orElseThrow());
        assertFalse(style.isUnset(), "a split is a fact about the line, so the override is not empty");

        JsonObject json = style.asJson();
        assertEquals(2, json.getAsJsonArray("fromHandle").size());
        assertEquals(0.66, json.getAsJsonArray("toHandle").get(0).getAsDouble(), 1e-9);

        DependencyStyle again = DependencyStyle.from(com.google.gson.JsonParser.parseString(json.toString()));
        assertEquals(style.fromHandle(), again.fromHandle(), "the writer and the reader agree");
        assertTrue(parse("{\"form\":\"curved\"}").asJson().get("fromHandle") == null,
                "a style that is not split must not grow handles on the way out");
    }

    @Test
    @DisplayName("a pair that is not exactly two numbers is left unsaid, like any unknown value")
    void malformedHandlesAreLenient() {
        // Lenient because this is the read path: a newer server may write a shape this build does not
        // know, and a client that refused the tree would show nothing at all. Refusing is the
        // validator's job, on the side that can refuse it -- and it does, see QuestValidatorTest.
        assertTrue(parse("{\"fromHandle\":[0.3]}").fromHandle().isEmpty(), "one number is not a pair");
        assertTrue(parse("{\"fromHandle\":[0.3,0.2,0.1]}").fromHandle().isEmpty());
        assertTrue(parse("{\"fromHandle\":\"nope\"}").fromHandle().isEmpty());
        assertTrue(parse("{\"fromHandle\":[0.3,\"x\"]}").fromHandle().isEmpty());
    }

    @Test
    @DisplayName("over() carries handles through, so a chapter default cannot erase a split")
    void overCarriesHandles() {
        DependencyStyle chapter = parse("{\"form\":\"curved\",\"bend\":0.4}");
        DependencyStyle line = parse("{\"fromHandle\":[0.33,0.2],\"toHandle\":[0.66,-0.2]}");

        DependencyStyle resolved = line.over(chapter).resolved();
        assertEquals(Optional.of(List.of(0.33, 0.2)), resolved.fromHandle());
        assertEquals(0.4, resolved.bendOr(0), 1e-9, "and the chapter's other axes still arrive");
    }

    // ------------------------------------------------------------------
    // Arrows: three current axes, one legacy axis still read
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the legacy arrows axis keeps its old meaning through the new reading")
    void legacyArrowsMapOntoTheNewAxes() {
        assertEquals(DependencyStyle.ArrowHead.NONE, parse("{\"arrows\":\"none\"}").headOr(null));
        assertEquals(DependencyStyle.ArrowHead.CHEVRON, parse("{\"arrows\":\"one\"}").headOr(null));
        assertEquals(DependencyStyle.ArrowPlace.TARGET, parse("{\"arrows\":\"one\"}").placeOr(null));
        assertEquals(DependencyStyle.ArrowPlace.BOTH, parse("{\"arrows\":\"both\"}").placeOr(null));
        assertEquals(DependencyStyle.ArrowPlace.STREAM, parse("{\"arrows\":\"many\"}").placeOr(null));
        assertEquals(DependencyStyle.ArrowHead.CHEVRON, parse("{\"arrows\":\"many\"}").headOr(null));
    }

    @Test
    @DisplayName("a stream written as the legacy many keeps the spacing it was always drawn with")
    void legacyManyKeepsItsSpacing() {
        // The density axis was added to give authors a choice, not to redraw every pack that had already
        // made one: a bare "many" still repeats every 24 pixels, exactly as it did.
        assertEquals(DependencyStyle.LEGACY_STREAM_SPACING, parse("{\"arrows\":\"many\"}").arrowSpacing());
        assertEquals(DependencyStyle.ArrowDensity.MEDIUM.spacing(),
                DependencyStyle.UNSET.resolved().arrowSpacing(),
                "a line that says nothing gets the medium density");
        assertEquals(DependencyStyle.ArrowDensity.HIGH.spacing(),
                parse("{\"arrowDensity\":\"high\"}").arrowSpacing());
        assertEquals(DependencyStyle.ArrowDensity.LOW.spacing(),
                parse("{\"arrows\":\"many\",\"arrowDensity\":\"low\"}").arrowSpacing(),
                "a stated density outranks the legacy spacing");
    }

    @Test
    @DisplayName("the current axes win over the legacy one, and mix with it")
    void newArrowAxesWinOverTheLegacy() {
        DependencyStyle mixed = parse("{\"arrows\":\"both\",\"arrowHead\":\"triangle\"}");

        assertEquals(DependencyStyle.ArrowHead.TRIANGLE, mixed.headOr(null));
        assertEquals(DependencyStyle.ArrowPlace.BOTH, mixed.placeOr(null),
                "the axis the line did not restate still comes from the legacy value");

        DependencyStyle replaced =
                parse("{\"arrows\":\"many\",\"arrowPlace\":\"mid\",\"arrowDensity\":\"high\"}");
        assertEquals(DependencyStyle.ArrowPlace.MID, replaced.placeOr(null));
        assertEquals(DependencyStyle.ArrowDensity.HIGH.spacing(), replaced.arrowSpacing());
    }

    @Test
    @DisplayName("the three arrow axes round-trip through the file and the codec")
    void arrowAxesRoundTrip() {
        DependencyStyle style =
                parse("{\"arrowHead\":\"diamond\",\"arrowPlace\":\"stream\",\"arrowDensity\":\"low\"}");

        JsonObject json = style.asJson();
        assertEquals("diamond", json.get("arrowHead").getAsString());
        assertEquals("stream", json.get("arrowPlace").getAsString());
        assertEquals("low", json.get("arrowDensity").getAsString());
        assertEquals(style, DependencyStyle.from(com.google.gson.JsonParser.parseString(json.toString())),
                "the writer and the reader agree");

        DependencyStyle decoded = DependencyStyle.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json.toString()))
                .getOrThrow();
        assertEquals(style, decoded);
        assertFalse(style.isUnset(), "an arrow axis is something the line says");
    }

    @Test
    @DisplayName("a value this build does not know is left unsaid on the new axes too")
    void unknownArrowValuesAreLenient() {
        DependencyStyle style = parse("{\"arrowHead\":\"harpoon\"}");

        assertTrue(style.arrowHead().isEmpty());
        assertEquals(DependencyStyle.ArrowHead.CHEVRON, style.headOr(DependencyStyle.ArrowHead.CHEVRON));
    }
}
