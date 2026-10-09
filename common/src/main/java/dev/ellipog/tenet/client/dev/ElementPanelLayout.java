package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.tenet.quest.CanvasElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One canvas element's own rows: the form, wherever it is shown.
 *
 * <h2>Why this is its own class rather than a page of the Chapter tab</h2>
 *
 * <p>Because it has two callers now — the Chapter tab's elements section and the panel that opens when an
 * author clicks an element on the canvas — and a second copy of twenty rows keyed by field name is a second
 * place for a field to be forgotten. The rows are the same rows: one definition, two places to read them.
 *
 * <h2>Every row is keyed {@code element.<id>.<field>}</h2>
 *
 * <p>The key is the row's identity <i>and</i> the path a commit goes to, which is the convention the whole
 * Chapter tab follows: {@code ChapterPanelLayout.elementFieldOf} splits it, and the screen turns it into an
 * {@code EditorOp.SetElement}. A nested member — {@code image.texture}, {@code click.type},
 * {@code label.hAlign} — is one step with a dot in it, and the screen writes the whole object it belongs to,
 * because the two arms of a picture's source are exclusive and the object may not exist yet.
 *
 * <h2>Sections, not a wall: position, size, look, source, caption, press, show</h2>
 *
 * <p>The form was a flat list of twenty equal rows, and every field weighed the same as every other: where
 * the thing sits read the same as what a press does. Related fields are grouped under short section
 * headings now — a picture's form scans as six blocks rather than twenty lines — and a section folds like
 * every other section in this editor, through the folded set both callers already keep. A section whose
 * every row is hidden at this depth is omitted with its heading, so Normal mode never shows a name over
 * nothing; that is the same rule {@code Advanced} states for whole sections, kept here because these
 * headings are built here rather than at a builder with a gate around the block.
 *
 * <p>A section heading's key is {@code element.<id>.#<section>}. The {@code #} marks it as a fold rather
 * than a field: {@code ChapterPanelLayout.elementFieldOf} still splits it (the commit paths never see a
 * heading, which holds no widget), and {@code ToolsLayout.folds} names it a section so it is drawn as
 * one. The title heading folds the whole form; a section heading folds its block.
 *
 * <h2>Pairs for the coordinates that belong together</h2>
 *
 * <p>An x without its y is half a position, and two full-width stacked rows for one corner is a form twice
 * as tall as the idea it carries. Related numbers share one {@code PAIR} row — x with y, width with
 * height, a line's from with its from, its to with its to — through the same {@code ScrubPairField} the
 * canvas's own compact lines use. When one half is hidden at this depth the other stands alone as an
 * ordinary number row, so the shallow form stays a subset of the deep one rather than losing a field to
 * the pairing.
 *
 * <h2>A row is the control its kind asks for, and a text box is the last resort rather than the rule</h2>
 *
 * <p>Every row here was a text box once, on the argument that the field is a string in the file and the
 * validator is what knows the legal values. That is true about the <i>file</i> and wrong about the
 * <i>control</i>: an author typing a number a drag would have moved, and a blank box offered for a field with
 * four legal values and no others, is what changed it. A number is a {@code FIELD}, a closed set is a
 * {@code CHOICE} whose values are read from the model's own enums, a colour is a {@code CHIP} with the hex
 * popover behind it, and a flag is a {@code SWITCH}. What stays a text row is what really is text: a label's
 * words, a picture's path or sprite id, the quest a gate names, and what a press acts on.
 *
 * <p>The one button is the picture's file: a texture <b>is</b> a pickable thing — the pack's own PNGs, with a
 * catalogue the client already builds — so it gets the picker rather than a path to type. A sprite stays a
 * text row, because the block atlas is not catalogued on this side and there is no list to offer.
 */
public final class ElementPanelLayout {

    /**
     * The field name of the picture's "choose a file" row.
     *
     * <p>Not a field of the file at all — it is a button — but it is spelled like one, which is what lets the
     * press routing be the same one rule: a key that starts with the element prefix and names the element, and
     * a field after it that the screen recognises. See {@code QuestBookScreen.commitElementField}.
     */
    public static final String PICK_TEXTURE = "pickTexture";

    private ElementPanelLayout() {
    }

    /** The rows for one element, from the element's own tree. */
    public static List<ToolsLayout.Action> rows(JsonObject element) {
        return rows(element, Set.of());
    }

    /**
     * The same, with folded sections put away.
     *
     * <p>Both callers keep the set the way the Chapter tab keeps its own folds: the screen owns it, this
     * class only reads it. A key in it is a heading — the title heading, or a section's — whose block is
     * not built.
     */
    public static List<ToolsLayout.Action> rows(JsonObject element, Set<String> folded) {
        List<ToolsLayout.Action> rows = new ArrayList<>();
        if (element == null) {
            return List.copyOf(rows);
        }
        String id = read(element, "id", "");
        String type = read(element, "type", "");
        // What is being edited, at the top: a panel that opens on a click has to say which thing it is about,
        // and the name an author recognises is the element's own words or its id.
        String titleKey = ChapterPanelLayout.ELEMENT_PREFIX + id;
        rows.add(ToolsLayout.Action.heading(titleKey, type + " - " + nameOf(element, id)));
        if (isFolded(folded, titleKey)) {
            return List.copyOf(rows);
        }

        switch (type) {
            case CanvasElement.TYPE_RECT -> {
                section(rows, id, "position", "tenet.dev.element.section.position", folded,
                        inner -> pairNumber(inner, element, id, "x", "tenet.dev.element.x",
                                "y", "tenet.dev.element.y"));
                section(rows, id, "size", "tenet.dev.element.section.size", folded,
                        inner -> pairNumber(inner, element, id, "width", "tenet.dev.element.width",
                                "height", "tenet.dev.element.height"));
                section(rows, id, "look", "tenet.dev.element.section.look", folded, inner -> {
                    colour(inner, element, id, "fillColor", "tenet.dev.element.fill_color");
                    colour(inner, element, id, "borderColor", "tenet.dev.element.border_color");
                    number(inner, element, id, "borderWidth", "tenet.dev.element.border_width");
                });
            }
            case CanvasElement.TYPE_TEXT -> {
                section(rows, id, "position", "tenet.dev.element.section.position", folded, inner -> {
                    pairNumber(inner, element, id, "x", "tenet.dev.element.x",
                            "y", "tenet.dev.element.y");
                    // Whether the label's size is fixed to the canvas: it is positioned like anything on
                    // it either way, and at zoom one it sits exactly where an anchored label would -- so
                    // it sits beside the position section rather than two sections away from it. On, the
                    // size grows and shrinks with the zoom like a node's; off, it stays the file's own on
                    // screen at every zoom.
                    flag(inner, element, id, "fixed", "tenet.dev.element.fixed");
                });
                section(rows, id, "words", "tenet.dev.element.section.words", folded, inner -> {
                    text(inner, element, id, "text", "tenet.dev.element.text");
                    number(inner, element, id, "scale", "tenet.dev.element.scale");
                    colour(inner, element, id, "color", "tenet.dev.element.color");
                    flag(inner, element, id, "shadow", "tenet.dev.element.shadow");
                });
            }
            case CanvasElement.TYPE_LINE -> {
                // A line has no position of its own: its two endpoints are the whole of where it is, and a
                // separate x/y would be a second place to move it from. So this arm gets no position section.
                section(rows, id, "from", "tenet.dev.element.section.from", folded,
                        inner -> pairNumber(inner, element, id, "x1", "tenet.dev.element.x1",
                                "y1", "tenet.dev.element.y1"));
                section(rows, id, "to", "tenet.dev.element.section.to", folded,
                        inner -> pairNumber(inner, element, id, "x2", "tenet.dev.element.x2",
                                "y2", "tenet.dev.element.y2"));
                section(rows, id, "line", "tenet.dev.element.section.line", folded, inner -> {
                    // The field is `width` and the word is not: a line's width is how thick it is, where a box's
                    // is how far it reaches. One field, two labels, because a row that said "Width" over a line
                    // would be read as its length.
                    number(inner, element, id, "width", "tenet.dev.element.thickness");
                    colour(inner, element, id, "color", "tenet.dev.element.color");
                    choice(inner, element, id, "arrowhead", "tenet.dev.element.arrowhead");
                });
            }
            case CanvasElement.TYPE_IMAGE -> {
                section(rows, id, "position", "tenet.dev.element.section.position", folded,
                        inner -> {
                            pairNumber(inner, element, id, "x", "tenet.dev.element.x",
                                    "y", "tenet.dev.element.y");
                            // Pinned beside the coordinates it protects: a lock anywhere else would read
                            // as a property of the picture rather than of where it sits.
                            flag(inner, element, id, "locked", "tenet.dev.element.locked");
                        });
                section(rows, id, "size", "tenet.dev.element.section.size", folded,
                        inner -> pairNumber(inner, element, id, "width", "tenet.dev.element.width",
                                "height", "tenet.dev.element.height"));
                section(rows, id, "source", "tenet.dev.element.section.source", folded, inner -> {
                    // The button first, then the two arms as text rows: pressing the button is the ordinary way
                    // to give a picture a file, and typing a path is what an author does when the file is not in
                    // the pack's own folder. The sprite is the other arm -- one source or the other, never both.
                    if (!Advanced.hidesElement("image.texture")) {
                        inner.add(ToolsLayout.Action.button(
                                ChapterPanelLayout.ELEMENT_PREFIX + id + "." + PICK_TEXTURE,
                                "tenet.dev.element.pick_file", textureOf(element)));
                    }
                    text(inner, element, id, "image.texture", "tenet.dev.element.texture");
                    text(inner, element, id, "image.sprite", "tenet.dev.element.sprite");
                });
                section(rows, id, "look", "tenet.dev.element.section.look", folded, inner -> {
                    number(inner, element, id, "rotation", "tenet.dev.element.rotation");
                    flag(inner, element, id, "corner", "tenet.dev.element.corner");
                    colour(inner, element, id, "tint", "tenet.dev.element.tint");
                    number(inner, element, id, "alpha", "tenet.dev.element.alpha");
                });
                section(rows, id, "caption", "tenet.dev.element.section.caption", folded, inner -> {
                    text(inner, element, id, "title", "tenet.dev.element.title");
                    // **The picture's caption, in the order `ElementLabel` declares its own five fields** -- and
                    // all five, because the worked example in `tools/quests` paints one with `inset` and `shadow`
                    // set and an author who can see a field in the file and not in the form is looking at a gap.
                    flag(inner, element, id, "label.onImage", "tenet.dev.element.label_on_image");
                    flag(inner, element, id, "label.shadow", "tenet.dev.element.label_shadow");
                    number(inner, element, id, "label.inset", "tenet.dev.element.label_inset");
                    choice(inner, element, id, "label.hAlign", "tenet.dev.element.label_h_align");
                    choice(inner, element, id, "label.vAlign", "tenet.dev.element.label_v_align");
                });
                section(rows, id, "press", "tenet.dev.element.section.press", folded, inner -> {
                    choice(inner, element, id, "click.type", "tenet.dev.element.click_type");
                    pressTarget(inner, element, id);
                });
            }
            default -> {
                // A kind this build does not know has no fields to offer, and the heading above already says
                // what it is. Offering none is the honest answer; inventing rows for a shape this build cannot
                // read would be offering to edit fields nobody can name.
            }
        }
        // And what every element has, whatever it is: where it sits in the draw order, whether it is a draft,
        // and what it waits for. Last, because these are the common fields and the arm's own come first.
        section(rows, id, "show", "tenet.dev.element.section.show", folded, inner -> {
            number(inner, element, id, "order", "tenet.dev.element.order");
            flag(inner, element, id, "dev", "tenet.dev.element.dev");
            text(inner, element, id, "requires", "tenet.dev.element.requires");
        });
        return List.copyOf(rows);
    }

    /**
     * The rows for the panel that opens on a click: the same form without the title heading.
     *
     * <p>The card's own chrome names the element now — type, name, id, draw order and its badges — so the
     * first row repeating it inside the scroll is a title twice. The Chapter tab keeps the heading, which
     * is what its fold button hangs from and what says which element the fields under the list belong to.
     */
    public static List<ToolsLayout.Action> rowsForPanel(JsonObject element, Set<String> folded) {
        List<ToolsLayout.Action> rows = new ArrayList<>(rows(element, folded));
        if (!rows.isEmpty() && rows.get(0).kind() == ToolsLayout.Action.Kind.HEADING
                && ChapterPanelLayout.elementFieldOf(rows.get(0).key()) == null) {
            rows.remove(0);
        }
        return List.copyOf(rows);
    }

    /** Whether a heading's block is put away. A set nobody passed is nothing put away. */
    private static boolean isFolded(Set<String> folded, String key) {
        return folded != null && key != null && folded.contains(key);
    }

    /**
     * One group of rows under its own folding heading.
     *
     * <p>Built into a temporary list first, because a section whose every row is hidden at this depth is
     * omitted with its heading: a name over nothing is a control that promises fields it has not got,
     * which is the same fault {@code Advanced} keeps out of the settings page by gating whole sections
     * at the builder. A folded section keeps its heading and drops its rows, so the form can be put away
     * a block at a time.
     */
    private static void section(List<ToolsLayout.Action> rows, String id, String name, String label,
                                Set<String> folded,
                                java.util.function.Consumer<List<ToolsLayout.Action>> fill) {
        List<ToolsLayout.Action> inner = new ArrayList<>();
        fill.accept(inner);
        if (inner.isEmpty()) {
            return;
        }
        String key = ChapterPanelLayout.ELEMENT_PREFIX + id + ".#" + name;
        boolean shut = isFolded(folded, key);
        rows.add(ToolsLayout.Action.heading(key, (shut ? "\u203a " : "\u25bc ") + label));
        if (!shut) {
            rows.addAll(inner);
        }
    }

    /** The same for a model element, which is what the canvas holds. */
    public static List<ToolsLayout.Action> rows(CanvasElement element) {
        return element == null ? List.of() : rows(CanvasElement.asJson(element));
    }

    /** A plain string of the element's own tree, or the fallback. The same read as {@link #valueOf}. */
    private static String read(JsonObject element, String field, String fallback) {
        String value = valueOf(element, field);
        return value.isEmpty() ? fallback : value;
    }

    /** What an element calls itself: its words when it has any, and its id when it has none. */
    private static String nameOf(JsonObject element, String id) {
        return ChapterPanelLayout.elementName(element, id);
    }

    /** The file a picture is drawn from, or empty when it names a sprite or nothing at all. */
    private static String textureOf(JsonObject element) {
        JsonElement image = element.get("image");
        if (image == null || !image.isJsonObject()) {
            return "";
        }
        JsonElement texture = image.getAsJsonObject().get("texture");
        return texture != null && texture.isJsonPrimitive() ? texture.getAsString() : "";
    }

    /**
     * One element field as the control its kind asks for.
     *
     * <h2>Why the kinds are not all text rows any more</h2>
     *
     * <p>Because a text row is the right control for a <i>word</i> and the wrong one for everything else, and
     * the rest of this editor already says so: a number is a {@code FIELD} -- a scrubbable value an author
     * drags to change, which is what the appearance rows use -- a closed set is a {@code CHOICE}, a colour is a
     * {@code CHIP} with the hex popover behind it, and a flag is a {@code SWITCH}. A form of twenty text boxes
     * makes an author type a number they could have dragged, and offers a blank box for a field that has four
     * legal values and no others.
     *
     * <p>What stays a text row is what really is text: a label's words, a picture's path or sprite id, the
     * quest a gate names, and what a press acts on.
     */
    private static void text(List<ToolsLayout.Action> rows, JsonObject element, String id, String field,
                             String label) {
        if (!Advanced.hidesElement(field)) {
            rows.add(ToolsLayout.Action.text(key(id, field), label, valueOf(element, field)));
        }
    }

    /** One element field as a scrubbable number, unless this depth hides it. */
    private static void number(List<ToolsLayout.Action> rows, JsonObject element, String id, String field,
                               String label) {
        if (!Advanced.hidesElement(field)) {
            rows.add(ToolsLayout.Action.field(key(id, field), label));
        }
    }

    /**
     * Two coordinates that belong together on one pair row.
     *
     * <p>One widget for the row rather than two stacked ones: an x without its y is half a position, and
     * the row halves the tallest numeric stretch of the form. When one half is hidden at this depth the
     * other stands alone as an ordinary number row, so the shallow form stays a subset of the deep one.
     * The pair's own key is the left half's, which is what the scroll view places and what a commit goes
     * to; the right half is reached through {@code Action.right()}, the same arrangement the canvas's own
     * compact lines use.
     */
    private static void pairNumber(List<ToolsLayout.Action> rows, JsonObject element, String id,
                                   String leftField, String leftLabel,
                                   String rightField, String rightLabel) {
        boolean leftHidden = Advanced.hidesElement(leftField);
        boolean rightHidden = Advanced.hidesElement(rightField);
        if (leftHidden && rightHidden) {
            return;
        }
        if (leftHidden) {
            rows.add(ToolsLayout.Action.field(key(id, rightField), rightLabel));
            return;
        }
        if (rightHidden) {
            rows.add(ToolsLayout.Action.field(key(id, leftField), leftLabel));
            return;
        }
        rows.add(ToolsLayout.Action.pair(
                ToolsLayout.Action.field(key(id, leftField), leftLabel),
                ToolsLayout.Action.field(key(id, rightField), rightLabel)));
    }

    /** One element field as a value chosen from its own closed set, unless this depth hides it. */
    private static void choice(List<ToolsLayout.Action> rows, JsonObject element, String id, String field,
                               String label) {
        if (!Advanced.hidesElement(field)) {
            rows.add(ToolsLayout.Action.choice(key(id, field), label));
        }
    }

    /**
     * What a press acts on: the target, or the reason there is none.
     *
     * <p>A press whose type is {@code none} carries no data — the validator refuses a target on one — so
     * offering its blank box beside the chooser is offering a field the file cannot hold. What is there
     * instead says so and names the way out, as a read-only row with no widget. A file that somehow holds
     * data with no type keeps the text row, because a value the file carries must stay editable whatever
     * the chooser says.
     */
    private static void pressTarget(List<ToolsLayout.Action> rows, JsonObject element, String id) {
        if (Advanced.hidesElement("click.data")) {
            return;
        }
        String held = valueOf(element, "click.data");
        if (!held.isEmpty() || !"none".equals(effectiveValueOf(element, "click.type"))) {
            rows.add(ToolsLayout.Action.text(key(id, "click.data"), "tenet.dev.element.click_data", held));
            return;
        }
        rows.add(ToolsLayout.Action.value(key(id, "click.data"), "tenet.dev.element.click_data",
                Labels.of("tenet.dev.element.click_none")));
    }

    /**
     * One element field as an inline colour chip, unless this depth hides it.
     *
     * <p>The chip carries the colour the row <b>shows</b> rather than the text the file holds: the file is
     * allowed to say nothing, and the picture is drawn untinted when it does. See {@link #colourOf}.
     */
    private static void colour(List<ToolsLayout.Action> rows, JsonObject element, String id, String field,
                               String label) {
        if (!Advanced.hidesElement(field)) {
            rows.add(ToolsLayout.Action.chip(key(id, field), label, effectiveValueOf(element, field)));
        }
    }

    /**
     * What a row's field holds, in the file's own spelling: the file's value, or the one the arm's codec
     * supplies when the file says nothing.
     *
     * <h2>Why a row must be handed this rather than {@link #valueOf}</h2>
     *
     * <p>Because a field the file does not carry is not <i>nothing</i>: it is the codec's default, and the
     * canvas draws that default. A control built from an absent field therefore disagreed with the picture
     * beside it, and for two kinds it was worse than a disagreement:
     *
     * <ul>
     *   <li><b>A colour chip drew nothing at all.</b> The chip <i>is</i> the control, so a row whose file has
     *       no {@code tint} was a label with no control under it and no rectangle to aim at -- the report was
     *       *"tint thing is invisible, also the colour pickers dont allow clicks"*, from an element drawn
     *       untinted because that is what its arm's default says.</li>
     *   <li><b>A number box read {@code 0}.</b> An absent {@code alpha} showed 0 where the picture is opaque
     *       (255), and a drag starts from what the box shows -- so the first pixel of a gesture would have
     *       made the picture vanish. `width` (32), `alpha` (255), `scale` (1.0) and a caption's `inset` (1.0)
     *       are the four the codec fills in with something other than zero.</li>
     * </ul>
     *
     * <p>A chooser's word comes through here for the same reason: an absent {@code hAlign} is `middle`, and
     * a box that showed nothing for it offered no way to tell an unset field from a broken one.
     *
     * <h2>Decoded rather than tabulated</h2>
     *
     * <p>{@link CanvasElement#fromJson} applies the same codecs the canvas, the loader and the validator read,
     * so a default that moves in the model moves here with it -- this class cannot come to hold a second
     * statement of the format. What is spelled here is only the field <i>name</i>, which is the same thing
     * every row in this form already spells.
     *
     * <p>An element this build's codec refuses answers {@code ""} or zero, which is where the row was before:
     * a file the loader will not read is the validator's to report, not this form's to guess at.
     */
    public static String effectiveValueOf(JsonObject element, String field) {
        String held = valueOf(element, field);
        if (!held.isEmpty()) {
            return held;
        }
        return CanvasElement.fromJson(element).map(arm -> switch (arm) {
            case CanvasElement.Image image -> switch (field) {
                case "x" -> Integer.toString(image.x());
                case "y" -> Integer.toString(image.y());
                case "width" -> Integer.toString(image.width());
                case "height" -> Integer.toString(image.height());
                case "rotation" -> Integer.toString(image.rotation());
                case "alpha" -> Integer.toString(image.alpha());
                case "tint" -> dev.ellipog.tenet.quest.Argb.toHex(image.tint());
                case "label.hAlign" -> wire(
                        image.label().orElse(dev.ellipog.tenet.quest.ElementLabel.DEFAULT).hAlign());
                case "label.vAlign" -> wire(
                        image.label().orElse(dev.ellipog.tenet.quest.ElementLabel.DEFAULT).vAlign());
                case "label.inset" -> Double.toString(
                        image.label().orElse(dev.ellipog.tenet.quest.ElementLabel.DEFAULT).inset());
                case "click.type" -> wire(image.click().type());
                default -> "";
            };
            case CanvasElement.Text text -> switch (field) {
                case "x" -> Integer.toString(text.x());
                case "y" -> Integer.toString(text.y());
                case "scale" -> Double.toString(text.scale());
                case "color" -> dev.ellipog.tenet.quest.Argb.toHex(text.color());
                default -> "";
            };
            case CanvasElement.Line line -> switch (field) {
                case "x1" -> Integer.toString(line.x1());
                case "y1" -> Integer.toString(line.y1());
                case "x2" -> Integer.toString(line.x2());
                case "y2" -> Integer.toString(line.y2());
                case "width" -> Integer.toString(line.width());
                case "color" -> dev.ellipog.tenet.quest.Argb.toHex(line.color());
                case "arrowhead" -> wire(line.arrowhead());
                default -> "";
            };
            case CanvasElement.Rect rect -> switch (field) {
                case "x" -> Integer.toString(rect.x());
                case "y" -> Integer.toString(rect.y());
                case "width" -> Integer.toString(rect.width());
                case "height" -> Integer.toString(rect.height());
                case "borderWidth" -> Integer.toString(rect.borderWidth());
                case "fillColor" -> dev.ellipog.tenet.quest.Argb.toHex(rect.fillColor());
                case "borderColor" -> dev.ellipog.tenet.quest.Argb.toHex(rect.borderColor());
                default -> "";
            };
            case CanvasElement.Unknown ignored -> "";
        }).orElse("");
    }

    /** The same, as the number a scrubbable row starts its drag from. Unreadable text is zero. */
    public static double numberOf(JsonObject element, String field) {
        try {
            return Double.parseDouble(effectiveValueOf(element, field));
        }
        catch (NumberFormatException notANumber) {
            // A field this build's codec has no value for, or an element it refuses: the box shows zero, which
            // is where it was before this method existed and is the honest answer for a value nobody can read.
            return 0;
        }
    }

    /** A row's key: the element, then the field. */
    private static String key(String id, String field) {
        return ChapterPanelLayout.ELEMENT_PREFIX + id + "." + field;
    }

    /**
     * How far one numeric field may go, and what its drag steps by.
     *
     * <h2>Why the bounds are here rather than only in the codec</h2>
     *
     * <p>Because the codec <b>clamps</b> what it reads: a file that says a box is four thousand pixels wide
     * loads as the largest legal box, so a control that could write more than that would write a number the
     * author never sees again. The bounds are the model's own constants, so the two cannot drift.
     */
    public record Range(double min, double max, double step, String unit) {
    }

    /**
     * The range for one numeric field, or null when the field is not one this form offers as a number.
     *
     * <h2>Why the arm's type is an argument</h2>
     *
     * <p>Because <b>one field name means three different ranges</b>, and this method used to answer with one
     * of them: {@code width} is a line's thickness (1 to 16, {@code Line.MAX_WIDTH}), a box's reach (up to
     * 8192) and a picture's edge (up to 4096). A thickness control dragged to 40 wrote a number the codec
     * then clamped to 16, which is exactly the drift {@link Range}'s note says these bounds exist to stop --
     * and the dead {@code "widthOfLine"} case that was meant to prevent it named a field no row produces,
     * because the thickness row is the line's own {@code width} under a different label. So the type comes
     * in, and the answer is chosen by the arm rather than by a spelling three arms share.
     *
     * @param type  the element's own type name, as the file spells it. See {@code CanvasElement.TYPE_*}
     * @param field the field name, dotted for a member of a nested object
     */
    public static Range rangeOf(String type, String field) {
        String arm = type == null ? "" : type;
        return switch (field == null ? "" : field) {
            // The one name with two owners: a box and a picture are measured by their edges, a line by how
            // thick it is drawn.
            case "width", "height" -> switch (arm) {
                case CanvasElement.TYPE_LINE -> new Range(CanvasElement.Line.MIN_WIDTH,
                        CanvasElement.Line.MAX_WIDTH, 1, " px");
                case CanvasElement.TYPE_RECT -> new Range(CanvasElement.Rect.MIN_EDGE,
                        CanvasElement.Rect.MAX_EDGE, 1, " px");
                default -> new Range(CanvasElement.Image.MIN_EDGE, CanvasElement.Image.MAX_EDGE, 1, " px");
            };
            case "x", "y", "x1", "y1", "x2", "y2" -> new Range(-4096, 4096, 1, " px");
            case "rotation" -> new Range(0, 359, 1, "\u00b0");
            case "alpha" -> new Range(CanvasElement.Image.MIN_ALPHA, CanvasElement.Image.MAX_ALPHA, 1, "");
            case "order" -> new Range(-64, 64, 1, "");
            case "scale" -> new Range(CanvasElement.Text.MIN_SCALE, CanvasElement.Text.MAX_SCALE, 0.05, "x");
            case "borderWidth" -> new Range(CanvasElement.Rect.MIN_BORDER, CanvasElement.Rect.MAX_BORDER,
                    1, " px");
            // How far the caption sits from the edge its alignment names, in whole pixels: the model holds a
            // double, and an integer is a value it reads.
            case "label.inset" -> new Range(dev.ellipog.tenet.quest.ElementLabel.MIN_INSET,
                    dev.ellipog.tenet.quest.ElementLabel.MAX_INSET, 1, " px");
            default -> null;
        };
    }

    /**
     * The values one closed set accepts, in the order an author meets them, <b>in the spelling the file
     * uses</b>.
     *
     * <p>Read from the model's own enums rather than listed here, so a value a model adds is offered by the
     * control without anybody remembering to add it twice -- and the validator, which reads the same enums,
     * cannot then disagree with the menu.
     *
     * <p><b>Lower-cased, because that is how the format spells them.</b> The codec reads any case
     * ({@code Codecs.enumByName} lower-cases before it looks one up), so the editor writing {@code OPEN_URI}
     * into a file whose schema, documentation and shipped examples all say {@code open_uri} was not a load
     * error -- it was a file that no longer matched the spelling of everything written about it, which is a
     * difference an author reads as a typo in one of the two.
     */
    public static List<String> valuesOf(String field) {
        return switch (field == null ? "" : field) {
            case "arrowhead" -> wire(dev.ellipog.tenet.quest.ArrowEnds.values());
            case "click.type" -> wire(dev.ellipog.tenet.quest.ClickAction.Type.values());
            case "label.hAlign", "label.vAlign" ->
                    wire(dev.ellipog.tenet.quest.ElementLabel.TextAlign.values());
            default -> List.of();
        };
    }

    /** Enum constants as the file spells them: {@code OPEN_QUEST} is written {@code open_quest}. */
    private static <E extends Enum<E>> List<String> wire(E[] values) {
        return java.util.Arrays.stream(values)
                .map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    /** The same, for one constant: a default a row shows must be spelled the way {@link #valuesOf} offers it. */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** What one of those values is called on screen: the file's own word, in the case a menu reads. */
    public static String valueName(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** One element flag as a toggle, unless this depth hides it. An absent flag is off. */
    private static void flag(List<ToolsLayout.Action> rows, JsonObject element, String id, String field,
                             String label) {
        if (Advanced.hidesElement(field)) {
            return;
        }
        String now = valueOf(element, field);
        rows.add(ToolsLayout.Action.toggle(key(id, field), label, now.isEmpty() ? ToolsLayout.OFF : now));
    }

    /**
     * The help a row's field offers on hover, as a language key, or null for a row that says it itself.
     *
     * <p>Keyed by field name alone rather than by the row's whole key, because the key carries the
     * element's id and the id has nothing to do with what the field means. Section folds (whose field
     * starts with {@code #}) share their block's sentence, so a heading explains the group it folds.
     */
    public static String help(String field) {
        return field == null ? null : HELP.get(field);
    }

    /**
     * What each field means, in one sentence a tooltip can carry.
     *
     * <p>A table rather than a field on the row, the shape {@code ToolsLayout.HELP} and
     * {@code ChapterPanelLayout.HELP} already use: a row is key, label and kind, and help is a property
     * of the key. Colour rows and plain words are absent — a chip shows the colour it edits and a path
     * says what it is — and the press rows share the press block's sentences with the section.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("x", "tenet.dev.element.help.position"),
            Map.entry("y", "tenet.dev.element.help.position"),
            Map.entry("fixed", "tenet.dev.element.help.fixed"),
            Map.entry("x1", "tenet.dev.element.help.endpoints"),
            Map.entry("y1", "tenet.dev.element.help.endpoints"),
            Map.entry("x2", "tenet.dev.element.help.endpoints"),
            Map.entry("y2", "tenet.dev.element.help.endpoints"),
            Map.entry("width", "tenet.dev.element.help.size"),
            Map.entry("height", "tenet.dev.element.help.size"),
            Map.entry("rotation", "tenet.dev.element.help.rotation"),
            Map.entry("corner", "tenet.dev.element.help.corner"),
            Map.entry("locked", "tenet.dev.element.help.locked"),
            Map.entry("alpha", "tenet.dev.element.help.alpha"),
            Map.entry("order", "tenet.dev.element.help.order"),
            Map.entry("dev", "tenet.dev.element.help.dev"),
            Map.entry("requires", "tenet.dev.element.help.requires"),
            Map.entry("borderWidth", "tenet.dev.element.help.border_width"),
            Map.entry("image.texture", "tenet.dev.element.help.source"),
            Map.entry("image.sprite", "tenet.dev.element.help.source"),
            Map.entry("title", "tenet.dev.element.help.caption"),
            Map.entry("label.onImage", "tenet.dev.element.help.caption"),
            Map.entry("label.shadow", "tenet.dev.element.help.caption"),
            Map.entry("label.inset", "tenet.dev.element.help.caption"),
            Map.entry("label.hAlign", "tenet.dev.element.help.caption"),
            Map.entry("label.vAlign", "tenet.dev.element.help.caption"),
            Map.entry("click.type", "tenet.dev.element.help.press"),
            Map.entry("click.data", "tenet.dev.element.help.press"),
            Map.entry("#position", "tenet.dev.element.help.position"),
            Map.entry("#size", "tenet.dev.element.help.size"),
            Map.entry("#look", "tenet.dev.element.help.look"),
            Map.entry("#words", "tenet.dev.element.help.words"),
            Map.entry("#from", "tenet.dev.element.help.endpoints"),
            Map.entry("#to", "tenet.dev.element.help.endpoints"),
            Map.entry("#line", "tenet.dev.element.help.line"),
            Map.entry("#source", "tenet.dev.element.help.source"),
            Map.entry("#caption", "tenet.dev.element.help.caption"),
            Map.entry("#press", "tenet.dev.element.help.press"),
            Map.entry("#show", "tenet.dev.element.help.show"));

    /**
     * What the <b>file</b> says for one element field, through the panel's own dotted read.
     *
     * <p>Empty when the file does not carry the field, and that is the whole difference between this and
     * {@link #effectiveValueOf}: this is what the JSON holds, that one is what the element means -- the codec's
     * default standing in for a field the file leaves out. <b>A row is built from the second</b>, because the
     * canvas draws the second; this one is for the commit path, which must know whether a value is there to
     * remove or has to be written.
     *
     * <p>Empty for anything that is not a primitive, and that is deliberate: an object or an array has no
     * single-line value, and a source that is an object is shown by its own two rows — {@code image.texture}
     * and {@code image.sprite} — rather than rendered as JSON in a row one line tall.
     */
    public static String valueOf(JsonObject element, String field) {
        JsonElement value = QuestPanelLayout.get(element, field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        // A boolean reads as the words a toggle draws, because the flag rows and the text rows are built from
        // this one string and a toggle showing "true" would be a toggle nobody could read.
        return primitive.isBoolean()
                ? (primitive.getAsBoolean() ? ToolsLayout.ON : ToolsLayout.OFF)
                : primitive.getAsString();
    }
}
