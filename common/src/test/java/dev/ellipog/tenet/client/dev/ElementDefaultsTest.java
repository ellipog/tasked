package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.tenet.quest.ArrowEnds;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.ImageSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The element a canvas menu adds.
 *
 * <h2>Why a default is worth a test</h2>
 *
 * <p>Because a default is a claim about what a thing looks like before anybody has touched it, and this one
 * has a rule behind it that the codec deliberately breaks: <b>a new element is visible</b>. The codec's own
 * default for a colour is fully transparent — the honest reading of a field nobody filled in — so a default
 * tree that named no colour would produce a box the author cannot see, cannot click and cannot tell apart
 * from the menu item having failed. That is the fault these tests exist to catch, and it is one no other test
 * could: everything downstream would be correct.
 *
 * <p>Each tree is also decoded as the element it claims to be, which is the other half: a menu row that
 * inserts a tree the codec refuses would be a row that does nothing at all.
 */
@DisplayName("the element a menu adds")
class ElementDefaultsTest {

    private static final List<String> KINDS = List.of(CanvasElement.TYPE_IMAGE, CanvasElement.TYPE_TEXT,
            CanvasElement.TYPE_RECT, CanvasElement.TYPE_LINE);

    private static CanvasElement decoded(String type) {
        JsonObject tree = ElementDefaults.tree(type, 16, 32);
        return CanvasElement.fromJson(tree)
                .orElseThrow(() -> new AssertionError(type + " does not decode: " + tree));
    }

    @Test
    @DisplayName("each kind decodes as an element of the kind that was asked for")
    void eachKindDecodes() {
        for (String type : KINDS) {
            assertEquals(type, decoded(type).type(), type);
        }
    }

    @Test
    @DisplayName("a new element is visible, where the codec's own defaults are a no-op")
    void aNewElementIsVisible() {
        CanvasElement.Rect box = assertInstanceOf(CanvasElement.Rect.class, decoded(CanvasElement.TYPE_RECT));
        assertNotEquals(0, box.fillColor() >>> 24, "the fill has an alpha, so the box can be seen");
        assertNotEquals(0, box.borderColor() >>> 24, "and so does the border, so its edge is where it looks");
        assertEquals(1, box.borderWidth());
        assertTrue(box.width() > 0 && box.height() > 0, "a box of no size is a box nobody can aim at");

        CanvasElement.Text text = assertInstanceOf(CanvasElement.Text.class, decoded(CanvasElement.TYPE_TEXT));
        assertFalse(text.text().value().isBlank(),
                "a label with no words decodes, draws nothing, and is the invisible default again");
        assertNotEquals(0, text.color() >>> 24);

        CanvasElement.Line line = assertInstanceOf(CanvasElement.Line.class, decoded(CanvasElement.TYPE_LINE));
        assertNotEquals(ArrowEnds.NONE, line.arrowhead(),
                "a line asked for from a canvas menu usually means an arrow");
        assertNotEquals(line.x1(), line.x2(), "and a zero-length line draws nothing at all");

        CanvasElement.Image image = assertInstanceOf(CanvasElement.Image.class,
                decoded(CanvasElement.TYPE_IMAGE));
        assertInstanceOf(ImageSource.Sprite.class, image.image(),
                "a sprite always resolves, where a file that is not in the pack draws nothing");
    }

    @Test
    @DisplayName("the position asked for is the position written, and a line runs from it")
    void thePositionIsTheOneAsked() {
        assertEquals(16, assertInstanceOf(CanvasElement.Rect.class,
                decoded(CanvasElement.TYPE_RECT)).x());
        assertEquals(32, assertInstanceOf(CanvasElement.Rect.class,
                decoded(CanvasElement.TYPE_RECT)).y());

        CanvasElement.Line line = assertInstanceOf(CanvasElement.Line.class, decoded(CanvasElement.TYPE_LINE));
        assertEquals(16, line.x1(), "a line starts where the author pointed");
        assertEquals(32, line.y1());
        assertEquals(16 + ElementDefaults.LINE_LENGTH, line.x2(), "and runs from it rather than to nowhere");
        assertEquals(32, line.y2(), "level, because a first line drawn at an angle is a surprise");
    }

    @Test
    @DisplayName("an id is a word, because the server suffixes one that is taken")
    void anIdIsAReadableWord() {
        for (String type : KINDS) {
            String id = decoded(type).id();
            assertFalse(id.isBlank(), type);
            assertTrue(id.matches("[a-z0-9_]+"),
                    "an id the loader would refuse would make the new element a file the validator reports: "
                            + id);
        }
    }

    @Test
    @DisplayName("a kind this class cannot write a default for gets nothing rather than an invented tree")
    void anUnknownKindGetsNothing() {
        // Returning a tree with a type this build does not know would be a menu row that inserts an element
        // which draws nothing and warns at load -- a worse outcome than the row doing nothing, because the
        // author would have a file to clean up.
        assertNull(ElementDefaults.tree("tenet:badge", 0, 0));
        assertNull(ElementDefaults.tree(null, 0, 0));
    }

    @Test
    @DisplayName("the ink a menu writes is the ink this class says it is")
    void theInkIsReadable() {
        assertEquals(0xFFA0A0A0, ElementDefaults.ink());
        assertEquals(ElementDefaults.INK, dev.ellipog.tenet.quest.Argb.toHex(ElementDefaults.ink()));
    }

    @Test
    @DisplayName("an id the chapter already has is suffixed, on the server's own ladder")
    void aTakenIdIsSuffixed() {
        // The client chooses the id so the element can be drawn before the server answers -- see
        // `ElementDraft`. The ladder has to be the server's, or the optimistic element and the real one would
        // be two names for one thing and the author would see the box twice.
        assertEquals("box", ElementDefaults.uniqueId("box", java.util.Set.of()));
        assertEquals("box", ElementDefaults.uniqueId("box", java.util.Set.of("other")));
        assertEquals("box_2", ElementDefaults.uniqueId("box", java.util.Set.of("box")));
        assertEquals("box_3", ElementDefaults.uniqueId("box", java.util.Set.of("box", "box_2")),
                "and it walks the ladder rather than landing on a name that is taken");
        assertEquals("element", ElementDefaults.uniqueId("", java.util.Set.of()),
                "a tree with no id at all still gets a name");
        assertEquals("element", ElementDefaults.uniqueId(null, java.util.Set.of()));
    }
}
