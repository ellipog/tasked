package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.tenet.quest.Argb;
import dev.ellipog.tenet.quest.CanvasElement;

/**
 * The element an author gets from "add one here".
 *
 * <h2>Why these are visible, when the codec's own defaults are not</h2>
 *
 * <p>Because the two are answering different questions. A file that names no colour gets a <b>no-op</b> from
 * the codec — fully transparent, which draws nothing and invents nothing — and that is the honest reading of
 * a field nobody filled in. An author who has just asked for a box, however, is asking to <i>see</i> one: a
 * default box that drew nothing would be a rectangle they cannot find, cannot click, and cannot tell apart
 * from the menu item having failed. So this class writes a wash and a border that are visible against a
 * chapter's own canvas, and the author adjusts from there.
 *
 * <p>An image gets a <b>sprite</b> rather than a file for the same reason: a file that is not in the pack
 * draws nothing at all, where a vanilla block texture is always stitched and always draws. The author's next
 * move is to point it at their own picture, which is the one thing they cannot be given a default for.
 *
 * <h2>Why this is not inline in the menu</h2>
 *
 * <p>Because a default is a claim about what an element looks like before anybody has touched it, and a claim
 * worth asserting: {@code ElementDefaultsTest} decodes each of these and checks that it is an element of the
 * type asked for, that its box is visible, and that a line has a head. Inline JSON in a menu handler could not
 * be tested at all, and the day one of them stops decoding is the day the menu item silently does nothing.
 */
public final class ElementDefaults {

    private ElementDefaults() {
    }

    /** How big a new box or picture is, in canvas pixels. */
    public static final int SIZE = 64;

    /** How long a new line is, in canvas pixels. */
    public static final int LINE_LENGTH = 96;

    /** The wash a new box is filled with: dark enough to read against a chapter, light enough to see through. */
    public static final String BOX_FILL = "#40101018";

    /** The border a new box wears, so its edge is where the author can see it. */
    public static final String BOX_BORDER = "#66204060";

    /** The ink a new label or line is drawn in: the same grey the canvas already uses for a body line. */
    public static final String INK = "#FFA0A0A0";

    /** The picture a new image starts with, which is a sprite because a sprite always resolves. */
    public static final String PLACEHOLDER_SPRITE = "minecraft:block/stone";

    /**
     * One new element of a type, at a position.
     *
     * <p>The id is a plain word rather than a number, and the server suffixes it when the chapter already has
     * one — see {@code QuestEditor.insertElement} for why an element's id is scoped to its chapter and a
     * quest's is not. So two boxes in one chapter are {@code box} and {@code box_2}, which is a name an
     * author can read in a file.
     *
     * @param type one of {@link CanvasElement}'s four type names
     * @param x    the left edge, or a line's first endpoint
     * @param y    the top edge, or a line's first endpoint
     */
    public static JsonObject tree(String type, int x, int y) {
        // Null first, because a switch on a String throws on a null selector rather than reaching its default
        // arm -- so the "an unknown kind gets nothing" contract would be a NullPointerException for one
        // particular unknown kind, which is the sort of thing a test finds and a reader does not.
        if (type == null) {
            return null;
        }
        JsonObject tree = new JsonObject();
        tree.addProperty("type", type);
        switch (type) {
            case CanvasElement.TYPE_RECT -> {
                tree.addProperty("id", "box");
                tree.addProperty("x", x);
                tree.addProperty("y", y);
                tree.addProperty("width", SIZE);
                tree.addProperty("height", SIZE);
                tree.addProperty("fillColor", BOX_FILL);
                tree.addProperty("borderColor", BOX_BORDER);
                tree.addProperty("borderWidth", 1);
            }
            case CanvasElement.TYPE_IMAGE -> {
                tree.addProperty("id", "image");
                tree.addProperty("x", x);
                tree.addProperty("y", y);
                tree.addProperty("width", SIZE);
                tree.addProperty("height", SIZE);
                JsonObject source = new JsonObject();
                source.addProperty("sprite", PLACEHOLDER_SPRITE);
                tree.add("image", source);
            }
            case CanvasElement.TYPE_TEXT -> {
                tree.addProperty("id", "label");
                tree.addProperty("x", x);
                tree.addProperty("y", y);
                // A word rather than an empty string, because the field is required: an empty one decodes and
                // draws nothing, which is the invisible-default fault this class exists to avoid.
                tree.addProperty("text", "Label");
                tree.addProperty("color", INK);
            }
            case CanvasElement.TYPE_LINE -> {
                tree.addProperty("id", "line");
                tree.addProperty("x1", x);
                tree.addProperty("y1", y);
                // To the right of where the author pressed, because a line needs two points and the second
                // one cannot be the pointer's own position -- a zero-length line draws nothing at all.
                tree.addProperty("x2", x + LINE_LENGTH);
                tree.addProperty("y2", y);
                tree.addProperty("color", INK);
                // A head, because "add a line" from a canvas menu usually means "draw an arrow": the blunt
                // rule is one field away, and a line with no head is the harder thing to aim at.
                tree.addProperty("arrowhead", "end");
            }
            default -> {
                // An unknown type is not something this class can write a default for, and returning a tree
                // the loader would report is worse than returning none. The caller only ever passes the four
                // names above, which the compiler cannot check because the type is a string in a file.
                return null;
            }
        }
        return tree;
    }

    /** The ink a new label or line is drawn in, as a value rather than as the string a file carries. */
    public static int ink() {
        return Argb.parseHex(INK).orElse(Argb.WHITE);
    }

    /**
     * An id no element of this chapter uses, preferring the one asked for.
     *
     * <h2>Why the client chooses an id at all</h2>
     *
     * <p>Because the server's answer is a round trip away and the element has to be drawable before it
     * arrives — see {@code ElementDraft}. The server's rule is the same and it <i>prefers</i> a free id, so a
     * client that checks what the chapter holds is right almost always; when it is not, because another
     * author took the id in between, the server suffixes and the pending element is dropped by its backstop.
     *
     * <p>The ladder is the server's, spelled the same way on purpose: {@code box}, {@code box_2},
     * {@code box_3}. Two different ladders would make the optimistic element and the real one two names for
     * one thing, which is the disagreement this whole arrangement exists to avoid.
     *
     * @param taken the ids the chapter holds, as this client currently believes them
     */
    public static String uniqueId(String wanted, java.util.Set<String> taken) {
        String base = wanted == null || wanted.isBlank() ? "element" : wanted;
        if (taken == null || !taken.contains(base)) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = base + "_" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        // Distinct even in the pathological case, because two elements sharing an id is a file the validator
        // refuses -- see the canvas element's own note on why uniqueness is checked.
        return base + "_" + System.currentTimeMillis();
    }
}
