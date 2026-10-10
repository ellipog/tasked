package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.QuestNodeArt;
import dev.ellipog.tenet.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a node is drawn, asserted from the recorded calls.
 *
 * <h2>Why the drawing is assertable at all</h2>
 *
 * <p>Because it takes a {@link dev.ellipog.armature.client.render.GuiRenderer} and plain numbers, which
 * is the whole reason it was extracted from the screen. The properties here are the ones a screenshot
 * would show and a reviewer would have to remember: the panel follows the shape rather than filling its
 * box, the icon box is inside the outline, the wash follows the shape too, the ring is drawn outside the
 * panel, and a node with no shape draws no panel at all.
 *
 * <p>The test lives beside the recording renderer rather than beside the class, because the recorder is
 * package-private to this package — and a test that needed a running client to see a shape would be a
 * test nobody runs.
 */
@DisplayName("The quest node's drawing")
class QuestNodeArtTest {

    private static QuestNodeArt.Look look(QuestShape shape, int size) {
        return new QuestNodeArt.Look(size, shape, shape.geometry(), null, 0.75, 0xFF1069B4, 0, 0);
    }

    @Nested
    @DisplayName("the panel")
    class Panel {

        @Test
        @DisplayName("a circle's panel does not fill its bounding box")
        void thePanelFollowsTheShape() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, look(QuestShape.CIRCLE, 48));

            assertTrue(r.covered(24, 24, 25, 25), "the circle's middle was not filled");
            assertFalse(r.covered(0, 0, 1, 1), "the circle's corner was filled, so this is a box");
            assertFalse(r.covered(47, 47, 48, 48), "the circle's opposite corner was filled");
        }

        @Test
        @DisplayName("a shape that draws no panel draws no panel")
        void noneDrawsNothing() {
            // `none` is the one shape whose node is its icon. It must still draw *something* for an
            // icon-less node -- the block below -- or a quest with no icon would be invisible, and the
            // ring is drawn either way because a node nobody can see is a node nobody can select.
            RecordingRenderer plain = new RecordingRenderer();
            QuestNodeArt.draw(plain, 0, 0, look(QuestShape.NONE, 48));
            // The stand-in block is drawn -- an icon-less `none` node must be visible -- and nothing
            // else is: every fill here is the block's translucent state colour, and none of them is a
            // panel's border at the node's own edge.
            assertTrue(plain.fills().stream().allMatch(fill -> (fill.argb() >>> 24) == 0xB0),
                    "a `none` node drew something other than its stand-in block: " + plain.describe());

            RecordingRenderer ringed = new RecordingRenderer();
            QuestNodeArt.draw(ringed, 0, 0, new QuestNodeArt.Look(48, QuestShape.NONE, QuestShape.NONE.geometry(), null, 0.75, 0xFF1069B4, 0xFF4E9BE8, 0));
            assertTrue(ringed.covered(0, 0, 48, 1),
                    "a selected `none` node has no ring, so nothing says it is selected");
        }

        @Test
        @DisplayName("the ring is drawn outside the panel, so the panel covers all but its edge")
        void theRingIsOutsideThePanel() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 10, 20, new QuestNodeArt.Look(48, QuestShape.ROUNDED, QuestShape.ROUNDED.geometry(), null, 0.75, 0xFF1069B4, 0xFF4E9BE8, 0));
            // The ring is the shape at size + 2, one pixel up and left of the panel, and the panel is
            // drawn over it -- so what survives is the pixel column at x = 9, which is outside the
            // panel's own box (it starts at x = 10).
            boolean ringOutside = r.fills().stream().anyMatch(fill -> fill.argb() == 0xFF4E9BE8
                    && fill.left() == 9 && fill.right() > 9);
            assertTrue(ringOutside, "the ring's outer edge is missing: " + r.describe());
            boolean panelOverIt = r.fills().stream().anyMatch(fill -> fill.left() >= 10);
            assertTrue(panelOverIt, "the panel was not drawn over the ring");
        }
    }

    @Nested
    @DisplayName("the icon and the wash")
    class IconAndWash {

        @Test
        @DisplayName("an icon-less node draws its stand-in block inside the shape, not over it")
        void theStandInBlockFollowsTheShape() {
            // The icon's own box is `QuestShape.iconBox`'s business and is swept in Armature -- centred,
            // inside the outline, the largest that fits -- and this class makes exactly one call to it.
            // What is observable from here is the stand-in: a quarter-inset block in the state colour,
            // drawn with the shape's spans, which is what keeps a small circle a small circle rather
            // than a square inside it.
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, look(QuestShape.CIRCLE, 48));
            int inset = Math.max(1, 48 / 4);
            assertTrue(r.fills().stream().anyMatch(fill -> (fill.argb() >>> 24) == 0xB0),
                    "an icon-less node drew no stand-in block, so it is invisible: " + r.describe());
            boolean blockInside = r.fills().stream()
                    .filter(fill -> (fill.argb() >>> 24) == 0xB0)
                    .allMatch(fill -> fill.left() >= inset && fill.right() <= 48 - inset
                            && fill.top() >= inset && fill.bottom() <= 48 - inset);
            assertTrue(blockInside, "the stand-in block reaches outside its quarter inset: "
                    + r.describe());
        }

        @Test
        @DisplayName("the wash follows the shape rather than filling the box")
        void theWashFollowsTheShape() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(48, QuestShape.CIRCLE, QuestShape.CIRCLE.geometry(), null, 0.75, 0xFF1069B4, 0, 0xFF000000));
            // The wash is the shape at size - 2, inset by one: its middle is covered and the bounding
            // box's corner is not, which is the difference between a wash and a black square.
            assertTrue(r.covered(24, 24, 25, 25), "the wash missed the node's middle");
            assertFalse(r.covered(1, 1, 2, 2), "the wash filled the corner, so it is a rectangle");
        }

        @Test
        @DisplayName("a node with no wash draws none, so an unlocked quest is not dimmed")
        void noWashDrawsNothing() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, look(QuestShape.ROUNDED, 48));
            for (RecordingRenderer.Fill fill : r.fills()) {
                assertTrue(fill.argb() != 0xFF000000, "something black was drawn as a wash");
            }
        }
    }

    @Nested
    @DisplayName("the caption")
    class Caption {

        @Test
        @DisplayName("the name is drawn under the node, inside the room it was given")
        void theCaptionSitsUnderTheNode() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.caption(r, 100, 100, 48, "Smelt Iron", 0, 400, 300);
            assertTrue(r.wroteWithin("Smelt Iron", 80, 150, 170, 175),
                    "the caption is not under its node: " + r.describe());
        }

        @Test
        @DisplayName("a caption with no room below is drawn above, so it is never cut off")
        void aCaptionAtTheBottomFlips() {
            RecordingRenderer r = new RecordingRenderer();
            // The node's bottom is at 200 and the room ends there, so there is no space below.
            QuestNodeArt.caption(r, 100, 152, 48, "Smelt Iron", 0, 400, 200);
            assertTrue(r.wroteWithin("Smelt Iron", 80, 130, 170, 152),
                    "the caption was not flipped above the node: " + r.describe());
        }
    }

    @Nested
    @DisplayName("the texture icon")
    class Texture {

        @Test
        @DisplayName("a texture icon draws through the blit rather than the stack")
        void textureDrawsThroughTheBlit() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED,
                    QuestShape.ROUNDED.geometry(), null, "my_pack:textures/gui/emblem.png", "", 0.75,
                    0xFF1069B4, 0, 0));

            assertEquals(1, r.textures().size(), "one blit, not an item: " + r.describe());
            assertEquals("my_pack:textures/gui/emblem.png",
                    r.textures().get(0).texture().toString(),
                    "the blit names the authored path");
        }

        @Test
        @DisplayName("a texture that is not a path falls back to the block, not a throw")
        void unparseableTextureFallsBack() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED,
                    QuestShape.ROUNDED.geometry(), null, "not a path :::", "", 0.75,
                    0xFF1069B4, 0, 0));

            assertTrue(r.textures().isEmpty(), "nothing unparseable reaches the blit: " + r.describe());
        }

        @Test
        @DisplayName("a sprite icon draws from the atlas rather than the stack")
        void spriteDrawsFromTheAtlas() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED,
                    QuestShape.ROUNDED.geometry(), null, "", "occultism:block/chalk_glyph/0", 0.75,
                    0xFF1069B4, 0, 0));

            assertEquals(1, r.sprites().size(), "one atlas draw, not an item: " + r.describe());
            assertEquals("occultism:block/chalk_glyph/0",
                    r.sprites().get(0).sprite().toString(),
                    "the draw names the authored region");
        }

        @Test
        @DisplayName("a texture wins over a sprite when both arrive")
        void textureWinsOverSprite() {
            RecordingRenderer r = new RecordingRenderer();
            QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED,
                    QuestShape.ROUNDED.geometry(), null, "my_pack:textures/gui/emblem.png",
                    "occultism:block/chalk_glyph/0", 0.75, 0xFF1069B4, 0, 0));

            assertEquals(1, r.textures().size(), "the blit, not the atlas: " + r.describe());
            assertTrue(r.sprites().isEmpty(), "one picture wins: " + r.describe());
        }

        @Test
        @DisplayName("the welcome chapter's texture quests blit at played sizes")
        void welcomeTexturesBlitAtPlayedSizes() {
            // claiming_chunks (circle 72), creating_a_team (circle 72), useful_commands
            // (rounded 72): file sizes times zoom 1.0, the canvas as opened.
            String[] paths = {
                    "ftbchunks:textures/waypoint_home.png",
                    "ftbteams:textures/teams.png",
                    "ftbteams:textures/settings.png" };
            QuestShape[] shapes = { QuestShape.CIRCLE, QuestShape.CIRCLE, QuestShape.ROUNDED };
            for (int i = 0; i < paths.length; i++) {
                RecordingRenderer r = new RecordingRenderer();
                QuestNodeArt.draw(r, 0, 0, new QuestNodeArt.Look(72, shapes[i],
                        shapes[i].geometry(), null, paths[i], "", 1.0, 0xFF1069B4, 0, 0));

                assertEquals(1, r.textures().size(),
                        paths[i] + " must blit on its node: " + r.describe());
                assertEquals(paths[i], r.textures().get(0).texture().toString(),
                        "the blit names the authored path");
            }
        }
    }

    @Test
    @DisplayName("the panel's colours come from the caller, not from a second state table here")
    void theCallerOwnsTheState() {
        // The canvas decides a node's state colour and this decides the art, so the same shape with a
        // different edge colour must be a different picture -- which is the seam that lets the settings
        // preview draw a node in the "available" colour without a second state machine. The first fill
        // of a panel is its border, drawn at full size before the fill.
        RecordingRenderer blue = new RecordingRenderer();
        QuestNodeArt.draw(blue, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED, QuestShape.ROUNDED.geometry(), null, 0.75, 0xFF1069B4, 0, 0));
        assertEquals(0xFF1069B4, blue.fills().get(0).argb(),
                "the border colour the caller passed was ignored: " + blue.describe());

        RecordingRenderer gold = new RecordingRenderer();
        QuestNodeArt.draw(gold, 0, 0, new QuestNodeArt.Look(48, QuestShape.ROUNDED, QuestShape.ROUNDED.geometry(), null, 0.75, 0xFFB48A10, 0, 0));
        assertEquals(0xFFB48A10, gold.fills().get(0).argb(), "the second call reused the first colour");
    }
}
