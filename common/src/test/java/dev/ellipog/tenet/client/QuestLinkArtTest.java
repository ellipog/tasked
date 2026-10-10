package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.armature.client.ui.shape.Shape;
import dev.ellipog.tenet.client.render.RecordingRenderer;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.QuestLink;
import dev.ellipog.tenet.quest.QuestRef;
import dev.ellipog.tenet.quest.QuestShape;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A quest link, as it is drawn and picked.
 *
 * <h2>What a recording renderer can see that a screenshot cannot</h2>
 *
 * <p>Each of these is a claim about <i>which call</i> was made rather than about a picture: a link
 * wearing the wrong state's wash reads as its target being somewhere it is not, a ring drawn for
 * a link that has none reads as a selection that never happened, and a circle's transparent
 * corners answering a press reads as a node that cannot be missed. A recorder turns all three
 * into a failing assertion.
 *
 * <p>The frame is a fixed one-to-one viewport and constant values, so the numbers are the link's
 * own coordinates rather than a client's. Icons are always null: the game-free tests cannot build
 * a stack, and "no item" is a real state the stand-in block is for.
 */
@DisplayName("a quest link, drawn and picked")
class QuestLinkArtTest {

    /** A canvas whose content coordinates map one-to-one onto screen pixels. */
    private static Viewport view() {
        return Viewport.fixed().bounds(0, 0, 400, 300);
    }

    private static QuestLinkArt.Frame frame(RecordingRenderer r) {
        return new QuestLinkArt.Frame(r, view());
    }

    private static QuestLink link(String id, String quest, int x, int y) {
        return new QuestLink(id, new QuestRef(quest), x, y, QuestShape.ROUNDED, 48);
    }

    private static QuestLinkArt.Slot slot(QuestLink link) {
        CanvasElementArt.Box box = QuestLinkArt.boxOf(link, view());
        // The shape's own table rather than the client's cached one: links never turn, so the two
        // are the same table by construction -- and the cache's class init touches the item
        // registry, which there is no registry to satisfy here.
        Shape geometry = link.shape().geometry();
        return new QuestLinkArt.Slot(link, box, geometry);
    }

    private static RecordingRenderer draw(QuestLinkArt.Slot slot, QuestState state, int ring) {
        RecordingRenderer r = RecordingRenderer.create();
        QuestLinkArt.draw(frame(r), slot, null, "", "", state, ring, false);
        return r;
    }

    @Test
    @DisplayName("a box is the link's corner and its size, floored like a node's")
    void aBoxIsCornerAndSize() {
        CanvasElementArt.Box box = QuestLinkArt.boxOf(link("l", "a", 64, 32), view());
        assertEquals(64, box.left());
        assertEquals(32, box.top());
        assertEquals(112, box.right());
        assertEquals(80, box.bottom(), "48 pixels at one-to-one zoom");

        CanvasElementArt.Box small = QuestLinkArt.boxOf(
                new QuestLink("l", new QuestRef("a"), 0, 0, QuestShape.ROUNDED, 16),
                Viewport.fixed().bounds(0, 0, 400, 300));
        assertTrue(small.width() >= 12, "the node's own floor, reached by zooming out: " + small);
    }

    @Test
    @DisplayName("a link wears its target's wash, and an unlocked target wears none")
    void theWashFollowsTheTargetsState() {
        QuestLinkArt.Slot slot = slot(link("l", "a", 64, 32));

        RecordingRenderer done = draw(slot, QuestState.COMPLETED, 0);
        RecordingRenderer open = draw(slot, QuestState.UNLOCKED, 0);
        assertTrue(!done.fills().isEmpty() && !open.fills().isEmpty(), "both draw a node");
        assertNotEquals(done.calls().toString(), open.calls().toString(),
                "and a finished target dims what an open one does not");

        RecordingRenderer locked = draw(slot, QuestState.LOCKED, 0);
        assertNotEquals(open.calls().toString(), locked.calls().toString(),
                "and a locked target dims differently again");
    }

    @Test
    @DisplayName("a ring is drawn when hovered and only then")
    void theRingFollowsTheHover() {
        QuestLinkArt.Slot slot = slot(link("l", "a", 64, 32));

        RecordingRenderer plain = draw(slot, QuestState.UNLOCKED, 0);
        RecordingRenderer hovered = draw(slot, QuestState.UNLOCKED, 0x80FFFFFF);
        assertNotEquals(plain.calls().toString(), hovered.calls().toString(),
                "a hovered link wears the ring an unhovered one does not");
    }

    @Test
    @DisplayName("the edge and the wash are one description for nodes and links alike")
    void stateInksAreShared() {
        assertEquals(ArmatureTheme.nodeEdgeComplete(), QuestNodeArt.edgeFor(QuestState.COMPLETED));
        assertEquals(ArmatureTheme.nodeEdgeInProgress(), QuestNodeArt.edgeFor(QuestState.STARTED));
        assertEquals(ArmatureTheme.nodeEdgeAvailable(), QuestNodeArt.edgeFor(QuestState.UNLOCKED));
        assertEquals(ArmatureTheme.nodeEdgeBlocked(), QuestNodeArt.edgeFor(QuestState.LOCKED));
        assertEquals(ArmatureTheme.nodeDoneWash(), QuestNodeArt.washFor(QuestState.COMPLETED));
        assertEquals(ArmatureTheme.nodeDim(), QuestNodeArt.washFor(QuestState.LOCKED));
        assertEquals(0, QuestNodeArt.washFor(QuestState.STARTED));
        assertEquals(0, QuestNodeArt.washFor(QuestState.UNLOCKED));
    }

    @Test
    @DisplayName("the topmost link answers, and a circle's transparent corners do not")
    void pickingIsTopmostAndShapeAware() {
        QuestLinkArt.Slot lower = slot(link("lower", "a", 64, 32));
        QuestLinkArt.Slot upper = slot(link("upper", "b", 64, 32));
        assertEquals("upper", QuestLinkArt.at(List.of(lower, upper), 70, 40).link().id(),
                "the link drawn last is the one picked");

        QuestLink circle = new QuestLink("ring", new QuestRef("a"), 64, 32, QuestShape.CIRCLE, 48);
        QuestLinkArt.Slot round = slot(circle);
        assertEquals("ring", QuestLinkArt.at(List.of(round), 88, 56).link().id(),
                "the middle answers");
        assertNull(QuestLinkArt.at(List.of(round), 65, 33),
                "but the corner is outside the disc, so a bounding box must not answer for it");
        assertNull(QuestLinkArt.at(List.of(round), 200, 200), "nor does far away");
        assertNull(QuestLinkArt.at(List.of(), 70, 40), "nor does an empty canvas");
    }

    @Test
    @DisplayName("drawing issues fills a recorder can see, and no icon without an icon")
    void drawingIsRecordedCalls() {
        RecordingRenderer r = draw(slot(link("l", "a", 64, 32)), QuestState.UNLOCKED, 0);
        assertTrue(!r.fills().isEmpty(), "a panel and its border are fills: " + r.calls());
        assertTrue(r.icons().isEmpty(), "null icon draws the stand-in, not an item");
        assertTrue(r.clipsBalanced() && r.turnsBalanced(), "and leaves the seam as it found it");
    }
}
