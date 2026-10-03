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
 * project's: <b>the names a data format promises not to change</b>, and the two decisions about what to
 * do with a name that does not resolve.
 *
 * <h2>Why the names are not tested in Armature</h2>
 *
 * <p>Because Armature does not know them. {@code Shapes.byName} accepts a superset — {@code "rectangle"}
 * and {@code "square"} as well as the quest names — and which of those a quest file may write is a
 * decision about the quest file format, not about geometry. A library that knew the format's vocabulary
 * would be a library that has to change when the format does.
 */
@DisplayName("Quest shapes, by name")
class QuestShapeTest {

    @Test
    @DisplayName("no two names are indistinguishable: each has its own geometry or its own presentation")
    void eachNameIsItsOwnShape() {
        // The assertion that makes the enum more than decoration. If two names forwarded to one Shape
        // and drew it the same way, a quest file asking for a hexagon would silently get a circle --
        // and the file, the validator and `/tasked` would all agree it was a hexagon.
        //
        // `square` and `none` do share a geometry, and they are not an exception to the rule: one draws
        // the rectangle as a panel and the other draws no panel at all, so an author who picks between
        // them gets two different pictures. That is what this checks -- not "different objects", which
        // would be a test of the factory rather than of the vocabulary.
        for (QuestShape a : QuestShape.values()) {
            for (QuestShape b : QuestShape.values()) {
                if (a != b) {
                    boolean samePicture = a.geometry() == b.geometry()
                            && a.drawsPanel() == b.drawsPanel();
                    assertTrue(!samePicture, a + " and " + b + " draw the same picture, so one of them"
                            + " cannot be chosen");
                }
            }
        }
    }

    @Test
    @DisplayName("every name but none draws a panel, and none's geometry is still the square")
    void noneIsTheOnlyPanelLessShape() {
        // `none` is the one deliberate exception to click-equals-drawing, so it is asserted rather than
        // assumed: it draws no panel, and its geometry is still a real rectangle so the icon has a box
        // to fit and the node has an area to be clicked in. A `none` whose geometry was absent would be
        // a node nobody can select.
        for (QuestShape shape : QuestShape.values()) {
            assertEquals(shape != QuestShape.NONE, shape.drawsPanel(),
                    shape + " draws a panel or does not, contrary to what the drawing assumes");
        }
        assertSame(Shapes.RECT, QuestShape.NONE.geometry(),
                "none's geometry is the square, which is what makes it clickable and fittable");
    }

    @Test
    @DisplayName("each name resolves to its own geometry, and the aliases are deliberate")
    void eachNameResolvesToItsGeometry() {
        assertSame(Shapes.ROUNDED, QuestShape.ROUNDED.geometry());
        assertSame(Shapes.RECT, QuestShape.SQUARE.geometry());
        assertSame(Shapes.CIRCLE, QuestShape.CIRCLE.geometry());
        assertSame(Shapes.DIAMOND, QuestShape.DIAMOND.geometry());
        assertSame(Shapes.HEXAGON, QuestShape.HEXAGON.geometry());
        assertSame(Shapes.OCTAGON, QuestShape.OCTAGON.geometry());
        assertSame(Shapes.PENTAGON, QuestShape.PENTAGON.geometry());
        assertSame(Shapes.GEAR, QuestShape.GEAR.geometry());
        assertSame(Shapes.HEART, QuestShape.HEART.geometry());
        assertSame(Shapes.TOME, QuestShape.TOME.geometry());
        assertSame(Shapes.RECT, QuestShape.NONE.geometry());
    }

    @Test
    @DisplayName("every method forwards to the geometry rather than computing anything")
    void everyMethodForwards() {
        // Delegation is the one thing that can go wrong once the maths has moved, and it goes wrong
        // silently: a `spans` that returned its own array would compile, pass anything that only checked
        // the shape was non-empty, and drift from the hit test.
        //
        // So this compares against the geometry directly, for every method, rather than asserting
        // properties -- properties would pass for a second implementation that happened to be right
        // today.
        for (QuestShape shape : QuestShape.values()) {
            Shape geometry = shape.geometry();
            for (int size : new int[] {12, 26, 33, 48, 64}) {
                for (int row = 0; row < size; row++) {
                    int[] viaEnum = shape.spans(row, size);
                    int[] viaGeometry = geometry.spans(row, size);
                    if (viaEnum == null || viaGeometry == null) {
                        // A sampled shape -- a gear, a heart -- can legitimately have a row with no
                        // material on it: a gear has gaps between its teeth, and a row through one above
                        // the root circle covers nothing. What matters is that the enum and the geometry
                        // agree about it, which is what this asserts.
                        assertEquals(viaGeometry, viaEnum, shape + " " + size + " row " + row
                                + ": the name and its geometry disagree about whether there is material");
                        continue;
                    }
                    assertEquals(viaGeometry.length, viaEnum.length,
                            shape + " " + size + " row " + row + " span count");
                    for (int i = 0; i < viaGeometry.length; i++) {
                        assertEquals(viaGeometry[i], viaEnum[i],
                                shape + " " + size + " row " + row + " endpoint " + i);
                    }
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
        assertEquals(QuestShape.SQUARE, QuestShape.byName("square", QuestShape.ROUNDED));
        assertEquals(QuestShape.DIAMOND, QuestShape.byName("diamond", QuestShape.ROUNDED));
        assertEquals(QuestShape.OCTAGON, QuestShape.byName("octagon", QuestShape.ROUNDED));
        assertEquals(QuestShape.PENTAGON, QuestShape.byName("pentagon", QuestShape.ROUNDED));
        assertEquals(QuestShape.GEAR, QuestShape.byName("gear", QuestShape.ROUNDED));
        assertEquals(QuestShape.HEART, QuestShape.byName("heart", QuestShape.ROUNDED));
        assertEquals(QuestShape.NONE, QuestShape.byName("none", QuestShape.ROUNDED));

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
        // the geometry being reachable only as a set of delegating methods.
        for (QuestShape shape : QuestShape.values()) {
            assertNotNull(shape.geometry());
            assertTrue(shape.geometry().spans(0, 48) != null,
                    shape + "'s geometry returned nothing for a row inside it");
        }
    }
}
