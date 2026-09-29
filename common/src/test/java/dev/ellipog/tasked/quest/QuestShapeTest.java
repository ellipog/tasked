package dev.ellipog.tasked.quest;

import com.mojang.serialization.JsonOps;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shapes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape <i>names</i> a quest file writes, and that they resolve to Armature's geometry.
 *
 * <h2>This class used to hold the geometry, and the tests went with it</h2>
 *
 * <p>Worth stating plainly, because it is the whole point of the round this shrank in. There were
 * invariant sweeps here — every shape at every size from 1 to 80, every row, checking that no span was
 * empty, that every shape was symmetric, that the hit test agreed with the drawing — and they were
 * <b>good tests of a copy</b>. The circle equation lived in this package because a screen needed a
 * circle, and Armature could not see it; so the tests that kept it honest lived here too, and nothing
 * else could benefit from either.
 *
 * <p>The geometry is {@link Shapes} now and the sweeps are {@code ShapeTest}, in Armature, where they
 * belong — because that is where the code is. What is left here is the part that is genuinely this
 * project's: <b>four names that a data format promises not to change</b>, and the two decisions about
 * what to do with a name that does not resolve.
 *
 * <h2>Why the names are not tested in Armature</h2>
 *
 * <p>Because Armature does not know them. {@code Shapes.byName} accepts a superset — {@code "rectangle"}
 * and {@code "square"} as well as the four — and which of those a quest file may write is a decision
 * about the quest file format, not about geometry. A library that knew the format's vocabulary would be
 * a library that has to change when the format does.
 */
@DisplayName("Quest shapes, by name")
class QuestShapeTest {

    @Test
    @DisplayName("each name resolves to its own geometry, and no two share one")
    void eachNameHasItsOwnGeometry() {
        // The assertion that makes the enum more than decoration. If two names forwarded to one Shape,
        // a quest file asking for a hexagon would silently get a circle -- and the file, the validator
        // and `/tasked` would all agree it was a hexagon.
        assertSame(Shapes.ROUNDED, QuestShape.ROUNDED.geometry());
        assertSame(Shapes.CIRCLE, QuestShape.CIRCLE.geometry());
        assertSame(Shapes.HEXAGON, QuestShape.HEXAGON.geometry());
        assertSame(Shapes.TOME, QuestShape.TOME.geometry());

        for (QuestShape a : QuestShape.values()) {
            for (QuestShape b : QuestShape.values()) {
                if (a != b) {
                    assertTrue(a.geometry() != b.geometry(),
                            a + " and " + b + " share one geometry, so one of them cannot be drawn");
                }
            }
        }
    }

    @Test
    @DisplayName("every method forwards to the geometry rather than computing anything")
    void everyMethodForwards() {
        // Delegation is the one thing that can go wrong once the maths has moved, and it goes wrong
        // silently: a `span` that returned its own array would compile, pass anything that only checked
        // the shape was non-empty, and drift from the hit test.
        //
        // So this compares against the geometry directly, for every method, rather than asserting
        // properties -- properties would pass for a second implementation that happened to be right
        // today.
        for (QuestShape shape : QuestShape.values()) {
            Shape geometry = shape.geometry();
            for (int size : new int[] {12, 26, 33, 48, 64}) {
                for (int row = 0; row < size; row++) {
                    int[] viaEnum = shape.span(row, size);
                    int[] viaGeometry = geometry.span(row, size);
                    assertNotNull(viaEnum, shape + " " + size + " row " + row);
                    assertEquals(viaGeometry[0], viaEnum[0], shape + " " + size + " row " + row + " from");
                    assertEquals(viaGeometry[1], viaEnum[1], shape + " " + size + " row " + row + " to");
                }

                assertEquals(geometry.maxInset(size), shape.maxIconInset(size), shape + " " + size);
                assertEquals(geometry.maxInset(size), shape.iconInset(size), shape + " " + size);

                for (int[] point : new int[][] {{0, 0}, {5, 5}, {size - 2, size - 2}, {-1, 5}, {5, -1}}) {
                    assertEquals(geometry.contains(point[0] + 0.5, point[1] + 0.5, 0, 0, size),
                            shape.contains(point[0] + 0.5, point[1] + 0.5, 0, 0, size),
                            shape + " " + size + " at " + point[0] + "," + point[1]);
                }

                for (double scale : new double[] {0.25, 0.75, 1.0}) {
                    int[] viaEnum = shape.iconBox(10, 20, size, scale);
                    int[] viaGeometry = geometry.iconBox(10, 20, size, scale);
                    assertEquals(viaGeometry[0], viaEnum[0], shape + " " + size + " at " + scale + " x");
                    assertEquals(viaGeometry[1], viaEnum[1], shape + " " + size + " at " + scale + " y");
                    assertEquals(viaGeometry[2], viaEnum[2], shape + " " + size + " at " + scale + " box");
                }
            }
        }
    }

    @Test
    @DisplayName("the icon scale bounds are the geometry's, not a second copy of them")
    void theScaleBoundsComeFromTheGeometry() {
        // The reason they are re-exported rather than restated. Two numbers in two places is how a
        // bound and its validation come to disagree -- and a validator that allowed 0.1 while the
        // geometry clamped to 0.25 would accept a file whose icon is then silently enlarged.
        assertEquals(Shape.MIN_ICON_SCALE, QuestShape.MIN_ICON_SCALE);
        assertEquals(Shape.MAX_ICON_SCALE, QuestShape.MAX_ICON_SCALE);
    }

    @Test
    @DisplayName("a name resolves by case, and an unknown one falls back rather than throwing")
    void namesResolveOrFallBack() {
        // The client reads a name off the wire, so an unknown one means the server is a different
        // version. Drawing the fallback is better than a screen that throws while a player is standing
        // in front of it -- and different from the validator's decision, which does error, because
        // there the author can still fix the file.
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("rounded", QuestShape.CIRCLE));
        assertEquals(QuestShape.CIRCLE, QuestShape.byName("CIRCLE", QuestShape.ROUNDED));
        assertEquals(QuestShape.HEXAGON, QuestShape.byName("Hexagon", QuestShape.ROUNDED));
        assertEquals(QuestShape.TOME, QuestShape.byName("tome", QuestShape.ROUNDED));

        assertEquals(QuestShape.ROUNDED, QuestShape.byName("dodecahedron", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName("", QuestShape.ROUNDED));
        assertEquals(QuestShape.ROUNDED, QuestShape.byName(null, QuestShape.ROUNDED));
    }

    @Test
    @DisplayName("the enum's own codec round-trips every name, spelled as a quest file spells it")
    void theCodecRoundTrips() {
        // The file format's vocabulary, which is the part Armature has no business asserting. A codec
        // that wrote `ROUNDED` would produce a file the validator rejects and the client cannot read --
        // and since the client reads by name with a fallback, it would draw the default instead of
        // failing, which is the quiet version of the same bug.
        for (QuestShape shape : QuestShape.values()) {
            var encoded = QuestShape.CODEC.encodeStart(JsonOps.INSTANCE, shape).getOrThrow();
            assertEquals(shape.name().toLowerCase(java.util.Locale.ROOT), encoded.getAsString(),
                    shape + " did not encode as the lowercase name a file uses");

            var decoded = QuestShape.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(shape, decoded, shape + " did not survive its own codec");
        }
    }

    @Test
    @DisplayName("a caller can reach the geometry without going through the enum")
    void theGeometryIsReachable() {
        // The seam that makes this enum a vocabulary rather than a wall. A screen that wants the shape
        // itself -- to hand to a fill routine, or to pass to tooling -- asks for it here, rather than
        // the geometry being reachable only as seven delegating methods.
        for (QuestShape shape : QuestShape.values()) {
            assertNotNull(shape.geometry());
            assertTrue(shape.geometry().span(0, 48) != null,
                    shape + "'s geometry returned nothing for a row inside it");
        }
    }
}
