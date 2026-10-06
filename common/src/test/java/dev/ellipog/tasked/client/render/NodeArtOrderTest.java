package dev.ellipog.tasked.client.render;

import dev.ellipog.tasked.client.CanvasSettings;
import dev.ellipog.tasked.client.QuestNodeArt;
import dev.ellipog.tasked.quest.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestShape;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order a node's furniture is drawn in: panel, then icon, then wash.
 *
 * <h2>Why the order is the thing to test</h2>
 *
 * <p>Because it is what the drawing means. The panel is drawn first so the icon sits on it; the wash is
 * drawn **after** the icon because dimming the item is what the wash is for. Get that order wrong and the
 * picture is wrong in a way no assertion about fills or pixels notices — which is exactly what happened: the
 * flat-icon path drew nothing at all while reporting that it had drawn an item, and the only instrument that
 * saw it was a screenshot.
 *
 * <p>So this asserts the **interleaving**, not the membership: an ordered log of every call, with the icon's
 * position between the panel's fills and the wash. Nothing here needs a client, and that matters, because the
 * preview harness draws no item icons at all — it has no item registry — so the drawn result of an icon is
 * not observable in any automated way in this project.
 *
 * <h2>Which recording renderer, and why it is worth saying</h2>
 *
 * <p>{@code dev.RecordingRenderer} — the one the panel tests use — records **per kind**: a list of fills, a
 * list of texts, a list of scales. Interleaving cannot be asserted against that, and believing it was the
 * only one available is why this test was called blocked and went unwritten for a round. The render
 * package's own recorder keeps one ordered {@code calls()} list with an {@code Op} per call, and its
 * {@code firstIndex}/{@code lastIndex} are documented as being "for asserting relative order".
 */
@DisplayName("the order a node is drawn in")
class NodeArtOrderTest {

    /** The wash's colour, which is how the one fill that must follow the icon is recognised. */
    private static final int WASH = 0xFF203040;

    @BeforeAll
    static void bootstrapMinecraft() {
        // The icon path resolves an ItemStack, so the registry has to exist.
        MinecraftTestBootstrap.boot();
    }

    /** A node with a panel, an icon and a wash, which is every node in the reader's book. */
    private static QuestNodeArt.Look look(int wash) {
        return look(wash, 48, 0.75);
    }

    /** The same, at a size and icon scale of the caller's choosing: the icon box follows both. */
    private static QuestNodeArt.Look look(int wash, int size, double iconScale) {
        return new QuestNodeArt.Look(size, QuestShape.ROUNDED, QuestShape.ROUNDED.geometry(),
                new ItemStack(Items.STICK), iconScale, 0xFF1069B4, 0, wash);
    }

    /** The index of the first fill in this colour, or -1: the wash is the only fill drawn in its own. */
    private static int firstFillIn(RecordingRenderer r, int argb) {
        for (int i = 0; i < r.calls().size(); i++) {
            RecordingRenderer.Call call = r.calls().get(i);
            if (call.op() == RecordingRenderer.Op.FILL && call.argb() == argb) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the icon is drawn after the panel and before the wash")
    void theIconIsDrawnBetweenThePanelAndTheWash() {
        RecordingRenderer r = RecordingRenderer.create();
        QuestNodeArt.draw(r, 0, 0, look(WASH));

        int icon = r.firstIndex(RecordingRenderer.Op.ICON);
        assertTrue(icon >= 0, "the node's icon was not drawn at all");
        assertTrue(r.firstIndex(RecordingRenderer.Op.FILL) >= 0, "and neither was its panel");
        assertTrue(r.firstIndex(RecordingRenderer.Op.FILL) < icon,
                "the panel must be drawn *before* the icon, or the icon is behind its own node's border");

        // The wash is drawn after the icon on purpose: dimming the item is what it is for, and a wash drawn
        // first would be painted over by the very thing it exists to dim.
        int wash = firstFillIn(r, WASH);
        assertTrue(wash >= 0, "the wash was not drawn");
        assertTrue(wash > icon, "the wash must land *after* the icon");
    }

    @Test
    @DisplayName("the node's own box decides whether its icon is drawn")
    void theIconIsRefusedByTheBoxAndNotByTheZoom() {
        // What replaced the zoom tier, and the property a screenshot had to find: an icon is refused when
        // the box it would fill is too small — measured from the node's own outline — and never because of
        // how far out the canvas happens to be zoomed. A landmark is large at any zoom and keeps its icon;
        // this small node runs out of room at a specific size.
        //
        // The threshold is loaded from a file rather than assumed, because it is the client's: a machine
        // whose `canvas.json` asks for tiny icons would otherwise make this test pass or fail by itself.
        Path settings = null;
        try {
            settings = Files.createTempFile("tasked-canvas-test", ".json");
            Files.writeString(settings, "{\"iconMinBox\": 12}", StandardCharsets.UTF_8);
            CanvasSettings.load(settings);
        }
        catch (java.io.IOException e) {
            throw new AssertionError("could not write the test's canvas.json", e);
        }

        try {
            RecordingRenderer big = RecordingRenderer.create();
            QuestNodeArt.draw(big, 0, 0, look(0, 48, 0.75));
            assertTrue(big.firstIndex(RecordingRenderer.Op.ICON) >= 0,
                    "a 48-pixel node has room for its icon");

            RecordingRenderer small = RecordingRenderer.create();
            QuestNodeArt.draw(small, 0, 0, look(0, 12, 0.5));
            assertTrue(small.firstIndex(RecordingRenderer.Op.ICON) < 0,
                    "a node too small for a 12-pixel box must not draw an item: it would be a smudge");
            assertTrue(small.firstIndex(RecordingRenderer.Op.FILL) >= 0,
                    "and it must still be drawn — its stand-in block is what makes an icon-less node visible");
        }
        finally {
            // The setting is static state shared by every test in this JVM, so it is put back to the
            // defaults the moment this one is done.
            CanvasSettings.load(Path.of("no", "such", "canvas.json"));
            if (settings != null) {
                try {
                    Files.deleteIfExists(settings);
                }
                catch (java.io.IOException ignored) {
                    // A temp file that will not delete is not this test's business.
                }
            }
        }
    }

    @Test
    @DisplayName("with no wash, and with the icon refused, the panel is still first and the node is drawn")
    void thePanelIsFirstEitherWay() {
        // Two more arms of the same rule, and the ones a regression would break silently: a node with no
        // wash has nothing to bracket the icon, so panel-before-icon is the only thing keeping it visible —
        // and a node whose icon is *refused* must still draw its panel and its stand-in block rather than
        // nothing at all, which is the other half of the flat-icon lesson.
        RecordingRenderer plain = RecordingRenderer.create();
        QuestNodeArt.draw(plain, 0, 0, look(0));
        assertTrue(plain.firstIndex(RecordingRenderer.Op.FILL) < plain.firstIndex(RecordingRenderer.Op.ICON),
                "with no wash, the panel is still first");

        RecordingRenderer refused = RecordingRenderer.withoutIcons();
        QuestNodeArt.draw(refused, 0, 0, look(WASH));
        assertTrue(refused.firstIndex(RecordingRenderer.Op.FILL) >= 0,
                "a refused icon must still leave a drawn node, not an invisible one");
    }
}
