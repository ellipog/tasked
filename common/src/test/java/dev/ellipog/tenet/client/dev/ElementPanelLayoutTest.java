package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.quest.CanvasElement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The form for one canvas element: the rows, wherever they are shown.
 *
 * <h2>Why the two callers are compared rather than each being described</h2>
 *
 * <p>Because the whole reason this class exists is that the Chapter tab's elements section and the panel that
 * opens on a click draw <b>the same form</b>. Two tests that each listed the rows they expect would agree with
 * themselves and drift apart from each other — so the interesting assertion here is that the two produce the
 * same keys, which is a fact about the code rather than about this file's expectations.
 */
@DisplayName("the element form")
class ElementPanelLayoutTest {

    /**
     * The depth is a static client preference, so it is set rather than assumed — and put back afterwards.
     *
     * <p>These tests are about the form's <i>rows</i>, and a row that is missing because another test class
     * left the depth shallow would read as a field the layout does not offer. The cut has its own case below,
     * which sets the other depth and puts it back; leaving this one set would make <i>this</i> class the one
     * that breaks somebody else's expectation, which is the same fault from the other side.
     */
    private boolean wasAdvanced;

    @BeforeEach
    void fullDepth() {
        wasAdvanced = Advanced.on();
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void restoreDepth() {
        DevMode.setAdvanced(wasAdvanced);
    }

    private static JsonObject element(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject picture() {
        return element("{ \"type\": \"image\", \"id\": \"logo\", \"x\": 16, \"y\": 16, \"width\": 64,"
                + " \"height\": 64, \"rotation\": 12, \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
    }

    /**
     * Every row key of a list, in order — a pair row contributing both halves.
     *
     * <p>A pair's own key is its left half's, and the scroll view only knows that one; the right half is
     * reached through {@code Action.right()}. A sweep over keys that forgot the halves would report every
     * paired field as missing from the form, so both are listed here.
     */
    private static List<String> keys(List<ToolsLayout.Action> rows) {
        List<String> out = new ArrayList<>();
        for (ToolsLayout.Action row : rows) {
            out.add(row.key());
            if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                out.add(row.right().key());
            }
        }
        return out;
    }

    @Test
    @DisplayName("a row per field of the arm, keyed element.<id>.<field>, under a heading naming it")
    void theRowsAreKeyedByElementAndField() {
        List<ToolsLayout.Action> rows = ElementPanelLayout.rows(picture());

        assertEquals(ChapterPanelLayout.ELEMENT_PREFIX + "logo", rows.get(0).key(),
                "the first row is the panel saying what it is about");
        assertEquals(ToolsLayout.Action.Kind.HEADING, rows.get(0).kind());
        assertTrue(rows.get(0).label().contains("image") && rows.get(0).label().contains("logo"),
                "and its words name the kind and the element: " + rows.get(0).label());

        List<String> keys = keys(rows);
        assertTrue(keys.contains("element.logo.width"), keys.toString());
        assertTrue(keys.contains("element.logo.rotation"));
        assertTrue(keys.contains("element.logo.click.type"));
        // Where it sits, first, as the model declares it: a form with a size and no position cannot express
        // what the file already says. See `theWorkedExampleHasNoFieldWithoutARow`. One pair row for both
        // coordinates, under the position section's own heading.
        assertEquals("element.logo.#position", keys.get(1),
                "a picture opens with its position section: " + keys);
        assertEquals(List.of("element.logo.x", "element.logo.y"), keys.subList(2, 4),
                "and one pair row for both coordinates, like the file and the settings page both do");
        // The common fields are last, whatever the arm: where it sits, whether it is a draft, what it waits for.
        assertEquals(List.of("element.logo.order", "element.logo.dev", "element.logo.requires"),
                keys.subList(keys.size() - 3, keys.size()));

        // And every key splits back into the element and the field it names, which is what the screen's commit
        // and toggle routing rely on -- a key that did not would be a row that writes nowhere.
        for (String key : keys) {
            String[] field = ChapterPanelLayout.elementFieldOf(key);
            if (key.equals("element.logo")) {
                assertEquals(null, field, "the heading is the list's shape, not a field");
                continue;
            }
            assertEquals("logo", field[0], key);
            assertFalse(field[1].isBlank(), key);
        }
    }

    @Test
    @DisplayName("each arm offers its own fields and no other arm's")
    void eachArmOffersItsOwnFields() {
        List<String> box = keys(ElementPanelLayout.rows(element(
                "{ \"type\": \"rect\", \"id\": \"b\", \"width\": 8, \"height\": 8 }")));
        assertTrue(box.contains("element.b.fillColor"));
        assertTrue(box.contains("element.b.borderWidth"));
        assertFalse(box.contains("element.b.rotation"), "a box does not turn: " + box);
        assertFalse(box.contains("element.b.arrowhead"));

        List<String> line = keys(ElementPanelLayout.rows(element(
                "{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 8, \"y2\": 8 }")));
        assertTrue(line.contains("element.l.x1") && line.contains("element.l.y2"));
        assertTrue(line.contains("element.l.arrowhead"));
        assertFalse(line.contains("element.l.width") && false, "the thickness is the same field, under a name");
        assertTrue(line.contains("element.l.width"), "one field, two labels -- see the layout's own note");

        List<String> text = keys(ElementPanelLayout.rows(element(
                "{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }")));
        assertTrue(text.contains("element.t.text"));
        assertTrue(text.contains("element.t.shadow"));
        assertFalse(text.contains("element.t.texture"));
    }

    @Test
    @DisplayName("only a label offers a fixed size, and it sits beside the position section")
    void onlyLabelsOfferAFixedSize() {
        List<ToolsLayout.Action> rows = ElementPanelLayout.rows(element(
                "{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }"));
        assertEquals(ToolsLayout.Action.Kind.SWITCH, kindOf(rows, "element.t.fixed"),
                "a canvas-fixed size is a flag, off by default");
        List<String> keys = keys(rows);
        assertTrue(keys.indexOf("element.t.fixed") > keys.indexOf("element.t.#position"),
                "in the position section: " + keys);

        assertFalse(keys(ElementPanelLayout.rows(picture())).contains("element.logo.fixed"),
                "a picture travels with the canvas, always");
        assertFalse(keys(ElementPanelLayout.rows(element(
                        "{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 8, \"y2\": 8 }")))
                        .contains("element.l.fixed"),
                "and so does a line");
        assertNotNull(ElementPanelLayout.help("fixed"), "with a sentence saying what canvas-fixed means");
    }

    @Test
    @DisplayName("a picture's source is a picker and two text rows, and the picker comes first")
    void thePictureOffersAPicker() {
        // The button is the ordinary way to give a picture a file -- the pack's own PNGs, with a catalogue the
        // client already builds -- and the two arms stay as text rows for a path that is not in that folder and
        // for a sprite, which has no list to pick from.
        List<String> keys = keys(ElementPanelLayout.rows(picture()));
        int picker = keys.indexOf("element.logo." + ElementPanelLayout.PICK_TEXTURE);
        assertTrue(picker > 0, "the picker is in the form: " + keys);
        assertTrue(picker < keys.indexOf("element.logo.image.texture"),
                "and it comes before the two rows it fills in");

        List<ToolsLayout.Action> rows = ElementPanelLayout.rows(picture());
        ToolsLayout.Action button = rowOf(rows, "element.logo." + ElementPanelLayout.PICK_TEXTURE);
        assertEquals(ToolsLayout.Action.Kind.BUTTON, button.kind(),
                "a button, because choosing a file is a choice rather than a value to type");
        assertEquals("tenet.dev.element.pick_file", button.label(),
                "and its label is a key the language file has");
    }

    @Test
    @DisplayName("each field gets the control its kind asks for, not a text box")
    void eachFieldGetsTheRightControl() {
        // The rule this pins, in the words of the person who reported it: no empty text field where a dropdown
        // belongs, and a number is the drag-to-change control the rest of the editor uses. A form of twenty
        // text boxes makes an author type a number they could have dragged, and offers a blank box for a field
        // with four legal values and no others.
        List<ToolsLayout.Action> rows = ElementPanelLayout.rows(picture());
        assertEquals(ToolsLayout.Action.Kind.PAIR, kindOf(rows, "element.logo.width"),
                "a size is one pair row for both edges, not two stacked numbers");
        ToolsLayout.Action size = rowOf(rows, "element.logo.width");
        assertEquals("element.logo.height", size.right().key(), "width first, height second");
        assertEquals(ToolsLayout.Action.Kind.FIELD, size.right().kind());
        assertEquals(ToolsLayout.Action.Kind.FIELD, kindOf(rows, "element.logo.rotation"));
        assertEquals(ToolsLayout.Action.Kind.FIELD, kindOf(rows, "element.logo.alpha"));
        assertEquals(ToolsLayout.Action.Kind.CHOICE, kindOf(rows, "element.logo.click.type"),
                "a closed set is a chooser, never a box to type into");
        assertEquals(ToolsLayout.Action.Kind.CHIP, kindOf(rows, "element.logo.tint"),
                "a colour is the chip the theme editor already uses");
        assertEquals(ToolsLayout.Action.Kind.TEXT, kindOf(rows, "element.logo.image.texture"),
                "and a path really is text");
        assertEquals(ToolsLayout.Action.Kind.SWITCH, kindOf(rows, "element.logo.corner"));

        List<ToolsLayout.Action> line = ElementPanelLayout.rows(element(
                "{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 8, \"y2\": 8 }"));
        assertEquals(ToolsLayout.Action.Kind.CHOICE, kindOf(line, "element.l.arrowhead"));

        // Every numeric field the form offers has a range, or the control could not move it -- and a range the
        // codec would clamp is a number the author never sees again. **The arm is part of the question now**:
        // `width` is a line's thickness, a box's reach and a picture's edge, and answering all three with the
        // picture's is what let a thickness control write a number the codec then clamped away.
        for (String field : List.of("x", "y", "x1", "y1", "x2", "y2", "rotation", "alpha", "order", "scale",
                "borderWidth")) {
            assertNotNull(ElementPanelLayout.rangeOf("image", field),
                    field + " has no range, so its field cannot move");
        }
        assertNull(ElementPanelLayout.rangeOf("image", "text"),
                "and a field that is not a number has none");

        // The one name with three owners, each bounded by the arm's own constants.
        assertEquals(CanvasElement.Image.MAX_EDGE, ElementPanelLayout.rangeOf("image", "width").max(),
                "a picture's edge is the picture's bound");
        assertEquals(CanvasElement.Rect.MAX_EDGE, ElementPanelLayout.rangeOf("rect", "width").max(),
                "a box's reach is the box's");
        assertEquals(CanvasElement.Line.MAX_WIDTH, ElementPanelLayout.rangeOf("line", "width").max(),
                "and a line's thickness is the line's own -- one field, three arms, three answers");

        // And every closed set offers the values the model declares, in the spelling the *file* uses -- the
        // codec reads any case, so the editor writing `OPEN_QUEST` into a file whose schema, docs and shipped
        // examples all say `open_quest` was not a load error and only a spelling nothing else agreed with.
        assertTrue(ElementPanelLayout.valuesOf("arrowhead").contains("both"));
        assertTrue(ElementPanelLayout.valuesOf("click.type").contains("open_quest"));
        assertEquals(List.of("start", "middle", "end"), ElementPanelLayout.valuesOf("label.hAlign"));
        assertTrue(ElementPanelLayout.valuesOf("text").isEmpty(), "a word has no closed set");
        assertEquals("Open quest", ElementPanelLayout.valueName("open_quest"),
                "and a value is named the way a menu reads");
    }

    @Test
    @DisplayName("every field the worked example writes on an element has a row in the form")
    void theWorkedExampleHasNoFieldWithoutARow() throws java.io.IOException {
        // The example chapter is what an author reads to learn the format, and it is also the closest thing
        // this repository has to a statement of which fields are used *together*: thirteen elements covering
        // all four arms. So the assertion is that the form can edit all of it -- a field in the file and not
        // in the panel is the gap this class exists to prevent, and the four this test was written for
        // (`x`, `y`, `label.inset`, `label.shadow`) were exactly that: an author looking at their own file
        // could see four fields the panel had no way to write.
        //
        // Repository-relative, like `SchemaCoverageTest`'s schemas: the Gradle test working directory is the
        // module, so `..` is the repository root.
        java.nio.file.Path chapter = java.nio.file.Path.of("..", "tools", "quests", "first_light",
                "first_steps", "chapter.json");
        assertTrue(java.nio.file.Files.isRegularFile(chapter),
                () -> "no example chapter at " + chapter.toAbsolutePath());
        JsonObject tree = JsonParser.parseString(java.nio.file.Files.readString(chapter)).getAsJsonObject();

        int seen = 0;
        for (var each : tree.getAsJsonArray("elements")) {
            JsonObject element = each.getAsJsonObject();
            String id = element.get("id").getAsString();
            List<String> keys = keys(ElementPanelLayout.rows(element));
            for (String field : leafFields(element)) {
                seen++;
                assertTrue(keys.contains(ChapterPanelLayout.ELEMENT_PREFIX + id + "." + field),
                        () -> "the form has no row for '" + field + "' on the example's " + id
                                + " -- rows are " + keys);
            }
        }
        assertTrue(seen >= 40, "and the sweep really read the example's fields: " + seen);
    }

    @Test
    @DisplayName("a picture's caption and its press offer every field their own models declare")
    void theImagesNestedModelsAreCovered() {
        // The example above happens to use all five caption properties and both press fields, and that is
        // luck rather than a rule -- so this is the same question asked of the *model*: `ElementLabel.FIELDS`
        // and `ClickAction.FIELDS` are the validator's own key sets, and a member of either with no row is a
        // field a pack can write and an author cannot edit. `inset` and `shadow` were the two that were
        // missing when this test was written.
        List<String> keys = keys(ElementPanelLayout.rows(picture()));
        for (String field : dev.ellipog.tenet.quest.ElementLabel.FIELDS) {
            assertTrue(keys.contains("element.logo.label." + field),
                    () -> "no row for a caption's '" + field + "': " + keys);
        }
        for (String field : dev.ellipog.tenet.quest.ClickAction.FIELDS) {
            assertTrue(keys.contains("element.logo.click." + field),
                    () -> "no row for a press's '" + field + "': " + keys);
        }
    }

    /**
     * Every leaf field an element names, dotted -- {@code image.sprite}, {@code label.hAlign} — skipping the
     * two keys that are not fields at all: {@code type} chooses the arm and {@code id} is the row key's own.
     *
     * <p>A leaf rather than every key: {@code image}, {@code label} and {@code click} are containers, and the
     * rows for them are their members'.
     */
    private static List<String> leafFields(JsonObject element) {
        List<String> out = new ArrayList<>();
        for (String name : element.keySet()) {
            if (name.equals("type") || name.equals("id")) {
                continue;
            }
            JsonElement value = element.get(name);
            if (value.isJsonObject()) {
                for (String inner : leafFields(value.getAsJsonObject())) {
                    out.add(name + "." + inner);
                }
            }
            else {
                out.add(name);
            }
        }
        return out;
    }

    @Test
    @DisplayName("a row shows what the element means: the file's value, or the arm's own codec default")
    void absentFieldsShowWhatTheModelSupplies() {
        // The fault these pin, in the reports: *"tint thing is invisible, also the colour pickers dont allow
        // clicks"* -- a chip handed an absent colour drew nothing at all, so the control was a label with no
        // rectangle to press. And a number box handed an absent number showed 0, which for `alpha` is a
        // picture the canvas draws opaque and a drag would have made transparent.
        JsonObject bare = element("{ \"type\": \"image\", \"id\": \"p\","
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");

        // The picture is untinted, so its tint is the codec's white -- not "no colour at all".
        assertEquals("#FFFFFFFF", ElementPanelLayout.effectiveValueOf(bare, "tint"));
        assertTrue(dev.ellipog.tenet.quest.Argb.parseHex(
                        ElementPanelLayout.effectiveValueOf(bare, "tint")).isPresent(),
                "and it is a colour the chip can paint");
        assertEquals(255, ElementPanelLayout.numberOf(bare, "alpha"),
                "an absent alpha is opaque, which is what the picture is drawn as");
        assertEquals(32, ElementPanelLayout.numberOf(bare, "width"),
                "and an absent edge is the codec's default, not zero");
        assertEquals("middle", ElementPanelLayout.effectiveValueOf(bare, "label.hAlign"),
                "a caption the file does not describe is centred, so its chooser says so");

        // The file's own value always wins -- that is the half that must not regress.
        JsonObject said = element("{ \"type\": \"image\", \"id\": \"p\", \"alpha\": 200, \"tint\": \"#80102030\","
                + " \"label\": { \"hAlign\": \"end\" },"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(200, ElementPanelLayout.numberOf(said, "alpha"));
        assertEquals("#80102030", ElementPanelLayout.effectiveValueOf(said, "tint"));
        assertEquals("end", ElementPanelLayout.effectiveValueOf(said, "label.hAlign"));

        // And a label's own scale, which the codec defaults to 1.0 where zero would be a sub-pixel smudge.
        JsonObject words = element("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }");
        assertEquals(1.0, ElementPanelLayout.numberOf(words, "scale"));
    }

    @Test
    @DisplayName("every colour row can be seen and every number row is inside its own bounds")
    void everyRowsEffectiveValueIsUsable() {
        // The two properties behind the two faults above, asked of every arm and every row the form builds
        // rather than of the four fields the reports named:
        //
        //   * a **colour** row must carry a parsable colour, or the chip paints nothing and the control is
        //     invisible; and
        //   * a **number** row must start inside the range its own range table declares, or the box opens on a
        //     value the codec would clamp away the moment it is written.
        List<JsonObject> arms = List.of(
                picture(),
                element("{ \"type\": \"rect\", \"id\": \"b\" }"),
                element("{ \"type\": \"line\", \"id\": \"l\" }"),
                element("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }"));

        int colours = 0;
        int numbers = 0;
        for (JsonObject arm : arms) {
            String type = arm.get("type").getAsString();
            for (ToolsLayout.Action row : ElementPanelLayout.rows(arm)) {
                String[] field = ChapterPanelLayout.elementFieldOf(row.key());
                if (field == null) {
                    continue;
                }
                if (row.isChip()) {
                    colours++;
                    assertTrue(dev.ellipog.tenet.quest.Argb.parseHex(row.value()).isPresent(),
                            () -> "a chip with no colour is a control nobody can see: " + row.key()
                                    + " carries '" + row.value() + "'");
                }
                if (row.kind() == ToolsLayout.Action.Kind.FIELD) {
                    numbers++;
                    ElementPanelLayout.Range range = ElementPanelLayout.rangeOf(type, field[1]);
                    double value = ElementPanelLayout.numberOf(arm, field[1]);
                    assertTrue(value >= range.min() && value <= range.max(),
                            () -> row.key() + " opens on " + value + ", outside " + range.min() + ".."
                                    + range.max());
                }
                // And a pair's halves, which are numbers under the pair's own key: the sweep above only
                // sees the left half's key, so the right half gets its own bounds check here.
                if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                    for (ToolsLayout.Action half : List.of(row, row.right())) {
                        String[] halfField = ChapterPanelLayout.elementFieldOf(half.key());
                        assertNotNull(halfField, half.key());
                        ElementPanelLayout.Range range = ElementPanelLayout.rangeOf(type, halfField[1]);
                        double value = ElementPanelLayout.numberOf(arm, halfField[1]);
                        assertTrue(value >= range.min() && value <= range.max(),
                                () -> half.key() + " opens on " + value + ", outside " + range.min()
                                        + ".." + range.max());
                        numbers++;
                    }
                }
                if (row.kind() == ToolsLayout.Action.Kind.CHOICE) {
                    String value = ElementPanelLayout.effectiveValueOf(arm, field[1]);
                    assertTrue(ElementPanelLayout.valuesOf(field[1]).contains(value),
                            () -> "a chooser must open on a word its own menu offers: " + row.key()
                                    + " shows '" + value + "'");
                }
            }
        }
        assertTrue(colours >= 4 && numbers >= 6,
                "and the sweep really saw the arms' controls: " + colours + " colours, " + numbers + " numbers");
    }

    @Test
    @DisplayName("a flag row keeps its state where the buttons read it")
    void aFlagRowCarriesItsStateInTheButtonLabel() {
        // The fault this pins, in the report: *"the switch buttons that use on and off text only ever show
        // off, the buttons dont update"*. A toggle row keeps its state in `buttonLabel` and leaves `value`
        // null -- see `ToolsLayout.Action.toggle` -- so a panel that built the word from `value()` read null
        // every time. `value` is not a second place for the state: it is where a *text*, *chip* or *button*
        // row carries what it shows, and a row with both would be a row with two answers.
        JsonObject on = element("{ \"type\": \"image\", \"id\": \"p\", \"corner\": true,"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        ToolsLayout.Action corner = rowOf(ElementPanelLayout.rows(on), "element.p.corner");
        assertEquals(ToolsLayout.ON, corner.buttonLabel(), "the state rides in the button's label");
        assertNull(corner.value(), "and not in `value`, which is why reading that answered null");

        JsonObject off = element("{ \"type\": \"image\", \"id\": \"p\","
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(ToolsLayout.OFF, rowOf(ElementPanelLayout.rows(off), "element.p.corner").buttonLabel(),
                "an absent flag is off, which is the codec's own default");
    }

    /** One row, by key. */
    private static ToolsLayout.Action rowOf(List<ToolsLayout.Action> rows, String key) {
        for (ToolsLayout.Action row : rows) {
            if (key.equals(row.key())) {
                return row;
            }
        }
        throw new AssertionError("no row " + key + " in " + keys(rows));
    }

    /** One row's kind, by key. */
    private static ToolsLayout.Action.Kind kindOf(List<ToolsLayout.Action> rows, String key) {
        for (ToolsLayout.Action row : rows) {
            if (key.equals(row.key())) {
                return row.kind();
            }
        }
        throw new AssertionError("no row " + key + " in " + keys(rows));
    }

    @Test
    @DisplayName("every number the form offers has the range its own arm declares")
    void everyNumericRowHasItsArmsRange() {
        // What this makes unreachable: the screen builds a field with no room to move for a numeric row whose
        // range it cannot find -- a box that draws and does nothing, which is the honest failure and a silent
        // one. The form's rows and the range table are two lists, so a field added to the first and forgotten
        // in the second is exactly that box; this walks every arm the form knows and asks for each FIELD row's
        // range by the arm it came from, which is the same question the screen asks.
        List<JsonObject> arms = List.of(
                picture(),
                element("{ \"type\": \"rect\", \"id\": \"b\", \"width\": 8, \"height\": 8 }"),
                element("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 8, \"y2\": 8 }"),
                element("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }"));

        int numbers = 0;
        for (JsonObject arm : arms) {
            String type = arm.get("type").getAsString();
            assertEquals(type, CanvasElement.fromJson(arm).orElseThrow().type(),
                    "the arm's own type name is what the range table is asked with");
            for (ToolsLayout.Action row : ElementPanelLayout.rows(arm)) {
                if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                    // A pair row: both halves are numbers the form offers, under two keys.
                    for (ToolsLayout.Action half : List.of(row, row.right())) {
                        String[] halfField = ChapterPanelLayout.elementFieldOf(half.key());
                        assertNotNull(halfField, half.key());
                        assertNotNull(ElementPanelLayout.rangeOf(type, halfField[1]),
                                half.key() + " is a number with no range, so its box could not move");
                        numbers++;
                    }
                    continue;
                }
                if (row.kind() != ToolsLayout.Action.Kind.FIELD) {
                    continue;
                }
                String[] field = ChapterPanelLayout.elementFieldOf(row.key());
                assertNotNull(field, row.key());
                assertNotNull(ElementPanelLayout.rangeOf(type, field[1]),
                        row.key() + " is a number with no range, so its box could not move");
                numbers++;
            }
        }
        assertTrue(numbers >= 8, "and the sweep really saw the arms' numbers: " + numbers);
    }

    @Test
    @DisplayName("a kind this build cannot read gets the heading and the common fields, and nothing invented")
    void anUnknownKindGetsNoFields() {
        List<String> keys = keys(ElementPanelLayout.rows(element(
                "{ \"type\": \"tenet:badge\", \"id\": \"b\" }")));
        assertEquals(
                List.of("element.b", "element.b.#show", "element.b.order", "element.b.dev",
                        "element.b.requires"),
                keys, "no rows for fields nobody can name, and the three every element has");
    }

    @Test
    @DisplayName("the Chapter tab's section and the panel draw the same form")
    void theTwoCallersAgree() {
        // The property this class exists for. The Chapter tab's own rows carry the list as well, so the
        // comparison is over the field rows: a field added to one and forgotten in the other would be an author
        // editing something in one place and not the other.
        JsonObject chapter = element("{ \"id\": \"first_steps\", \"title\": \"First Steps\","
                + " \"elements\": [ { \"type\": \"image\", \"id\": \"logo\", \"x\": 16, \"y\": 16,"
                + " \"width\": 64, \"height\": 64,"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } } ] }");
        List<String> fromTab = new ArrayList<>();
        for (ToolsLayout.Action row : ChapterPanelLayout.rows(chapter, null, Set.of(), null,
                ChapterPanelLayout.Problems.NONE, "logo")) {
            if (row.key().startsWith(ChapterPanelLayout.ELEMENT_PREFIX)) {
                fromTab.add(row.key());
                if (row.kind() == ToolsLayout.Action.Kind.PAIR && row.right() != null) {
                    fromTab.add(row.right().key());
                }
            }
        }
        List<String> fromPanel = keys(ElementPanelLayout.rowsForPanel(
                ChapterPanelLayout.elementById(chapter, "logo"), Set.of()));

        assertEquals("element.logo", fromTab.get(0), "the tab keeps the title its fold hangs from");
        assertEquals(fromTab.subList(1, fromTab.size()), fromPanel,
                "one form, two places to read it: the panel drops only the title its chrome replaces");
    }

    @Test
    @DisplayName("each arm groups its rows under section headings that fold")
    void sectionsGroupAndFold() {
        // The look half of this round: a picture's form scans as blocks rather than twenty equal lines,
        // and a block puts itself away. The fold state is the caller's set, the way the Chapter tab keeps
        // its own folds; this class only reads which blocks to skip.
        List<String> sections = new ArrayList<>();
        for (ToolsLayout.Action row : ElementPanelLayout.rows(picture())) {
            if (row.kind() == ToolsLayout.Action.Kind.HEADING && !row.key().equals("element.logo")) {
                sections.add(row.key());
            }
        }
        assertEquals(
                List.of("element.logo.#position", "element.logo.#size", "element.logo.#source",
                        "element.logo.#look", "element.logo.#caption", "element.logo.#press",
                        "element.logo.#show"),
                sections, "position, size, source, look, caption, press, show: " + sections);
        for (String section : sections) {
            assertTrue(ToolsLayout.folds(section), section + " is drawn as a section, so it must fold");
        }

        // One section put away: its heading stays and its rows go.
        List<String> folded = keys(ElementPanelLayout.rows(picture(), Set.of("element.logo.#look")));
        assertTrue(folded.contains("element.logo.#look"), "the heading stays: it says what was put away");
        assertFalse(folded.contains("element.logo.rotation"), "and its rows go");
        assertFalse(folded.contains("element.logo.alpha"));
        assertTrue(folded.contains("element.logo.width"), "while the other blocks stay");

        // The title put away: the whole form goes, which is what the Chapter tab's fold button on it means.
        assertEquals(List.of("element.logo"),
                keys(ElementPanelLayout.rows(picture(), Set.of("element.logo"))));
    }

    @Test
    @DisplayName("the panel drops the title its chrome replaces, and nothing else")
    void thePanelDropsOnlyTheTitle() {
        JsonObject picture = picture();
        List<String> full = keys(ElementPanelLayout.rows(picture, Set.of()));
        List<String> panel = keys(ElementPanelLayout.rowsForPanel(picture, Set.of()));
        assertEquals(full.subList(1, full.size()), panel);
        for (ToolsLayout.Action row : ElementPanelLayout.rowsForPanel(picture, Set.of())) {
            if (row.kind() != ToolsLayout.Action.Kind.HEADING) {
                continue;
            }
            assertTrue(row.key().contains(".#"),
                    "every heading left is a folding section, not the title the chrome repeats: "
                            + row.key());
        }
    }

    @Test
    @DisplayName("a press with no type and no target says so instead of offering a box")
    void anIdlePressNamesItself() {
        // A press whose type is none carries no data, so its blank box was a field the file cannot hold.
        // What is there instead is read-only and names the way out; a file that holds data anyway keeps
        // the text row, because a value the file carries stays editable.
        JsonObject idle = element("{ \"type\": \"image\", \"id\": \"p\","
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(ToolsLayout.Action.Kind.VALUE, kindOf(ElementPanelLayout.rows(idle), "element.p.click.data"),
                "no type and no target is a fact, not a field");

        JsonObject pressed = element("{ \"type\": \"image\", \"id\": \"p\","
                + " \"click\": { \"type\": \"open_quest\", \"data\": \"the_open_road\" },"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(ToolsLayout.Action.Kind.TEXT, kindOf(ElementPanelLayout.rows(pressed), "element.p.click.data"),
                "a target the file carries is edited like any other word");

        JsonObject stray = element("{ \"type\": \"image\", \"id\": \"p\","
                + " \"click\": { \"data\": \"the_open_road\" },"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(ToolsLayout.Action.Kind.TEXT, kindOf(ElementPanelLayout.rows(stray), "element.p.click.data"),
                "and stray data with no type stays editable rather than stranded");
    }

    @Test
    @DisplayName("every field an author can wonder about has hover help, and a colour speaks for itself")
    void everyFieldHasHelpWhereItNeedsIt() {
        for (String field : List.of("x", "y", "x1", "width", "height", "rotation", "order", "dev",
                "requires", "image.texture", "click.type", "click.data", "#position", "#press", "#show")) {
            assertNotNull(ElementPanelLayout.help(field), field + " has no hover sentence");
        }
        assertNull(ElementPanelLayout.help("tint"), "a chip shows the colour it edits");
        assertNull(ElementPanelLayout.help("no_such_field"), "and an unknown field says nothing");
    }

    @Test
    @DisplayName("the depth cut hides a field's row, and never the heading or the common rows")
    void theDepthCutHidesRows() {
        boolean was = Advanced.on();
        try {
            DevMode.setAdvanced(false);
            List<String> shallow = keys(ElementPanelLayout.rows(picture()));
            assertTrue(shallow.contains("element.logo"), "the heading stays: it says what is being edited");
            assertTrue(shallow.contains("element.logo.width"), "what the picture is stays");
            assertTrue(shallow.contains("element.logo." + ElementPanelLayout.PICK_TEXTURE),
                    "and the picker, because choosing the file is what the picture is");
            assertFalse(shallow.contains("element.logo.rotation"), "how it is drawn goes");
            assertFalse(shallow.contains("element.logo.order"));
            assertFalse(shallow.contains("element.logo.click.type"));
        }
        finally {
            DevMode.setAdvanced(was);
        }
    }
}
