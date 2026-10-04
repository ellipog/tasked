package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tasked.quest.DependencyStyle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Chapter tab's rows, from the chapter's own file.
 *
 * <p>The chapter is a file like a quest is, and this is the same inspector over it: identity, rules, and
 * the quest list in its authored order. Row keys are the dotted paths the commits go to, exactly as the
 * quest panel's are -- {@code EditorOp.SetChapter} is the op on the other end.
 *
 * <p>The quest list is shown in order, and it reorders: the rows drag, on the same gesture the card's
 * tasks and rewards use -- one drag for every list, as the note here said it would be. For a LINEAR
 * chapter the order <i>is</i> the progression, so the list is the thing being edited and not a summary
 * of it.
 *
 * <h2>Labels are short on purpose, and the rows stack</h2>
 *
 * <p>The dock is a narrow column, and this tab draws its labelled rows {@link
 * dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode#STACKED}: the label on its own line, the
 * control across the whole width beneath it. That is what lets "Default Prerequisite Mode" fit at all --
 * as a side-by-side row in a 180-pixel panel it truncated to "Default Prereq...", which is the report
 * this shape answers. The names stay the file's own ({@code defaultPrerequisiteMode} is still the key);
 * only the words on screen are shortened to what the column can hold.
 */
public final class ChapterPanelLayout {

    /** The foldable sections, by their heading key. */
    public static final String IDENTITY = "h:identity";
    public static final String RULES = "h:rules";
    public static final String QUESTS = "h:quests";
    public static final String GROUP = "h:group";

    /** The read-only values' prefix. */
    public static final String VALUE_PREFIX = "v:";

    /**
     * What a group row's key starts with.
     *
     * <p>The group is a different file from the chapter, so its paths carry a prefix: a row key is both
     * the identity in the scroll view and the field path a commit goes to, and {@code "title"} alone
     * would be the chapter's. The commit strips it and sends {@code EditorOp.SetGroup}.
     */
    public static final String GROUP_PREFIX = "group.";

    /**
     * The chapter's group, as the panel needs it: what the sidebar row above this chapter carries.
     *
     * <p>Read from the client's tree rather than from a file, because that is where a group exists on
     * this side -- the title, the icon's id, and whether its chapters start collapsed. {@code iconId} is
     * the <b>authored</b> icon alone: empty means "no icon of its own", which is a different fact from
     * the fallback the sidebar draws, and the row has to be able to say which one it is looking at.
     */
    public record GroupInfo(String id, String title, String iconId, boolean collapsedByDefault) {

        public GroupInfo {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            iconId = iconId == null ? "" : iconId;
        }
    }

    /**
     * The icon row's key, which is also the dotted path a commit goes to.
     *
     * <p>Named here rather than written at the two call sites that must agree about it -- the row's
     * builder and the screen's picker button -- because a key typed twice is a row that commits to the
     * wrong path on the day one of them is edited.
     */
    public static final String ICON = "icon.item";

    private ChapterPanelLayout() {
    }

    /** The chapter's own name, for the tab's header band. */
    public record Header(String title, String subtitle) {

        public Header {
            title = title == null ? "" : title;
            subtitle = subtitle == null ? "" : subtitle;
        }
    }

    /** The header, from the chapter's tree. Empty strings for a chapter whose copy has not arrived. */
    public static Header header(JsonObject chapter) {
        if (chapter == null || chapter.isEmpty()) {
            return new Header("", "");
        }
        return new Header(text(chapter, "title", ""), text(chapter, "subtitle", ""));
    }

    /** The chapter's icon object as it stands in the file, or an empty object when it declares none. */
    public static JsonObject icon(JsonObject chapter) {
        JsonElement found = chapter == null ? null : chapter.get("icon");
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }

    /** The whole panel for one chapter's tree. */
    public static List<InspectRow> rows(JsonObject chapter, Set<String> folded) {
        return rows(chapter, null, folded);
    }

    /**
     * The same, with the chapter's group when it has one.
     *
     * <p>The Group section appears only when there is a group to edit, and sits between the rules and the
     * quest list -- ahead of the list because a chapter with twenty quests would otherwise push the
     * group's fields below a scroll nobody makes. Its rows carry {@link #GROUP_PREFIX} in their keys,
     * because they commit to a different file -- the title and icon the sidebar's heading shows, and the
     * flag that says whether its chapters start closed. The icon's row is a picker button like the
     * chapter's, which is what lets the two be set to different items rather than one inheriting the
     * other.
     */
    public static List<InspectRow> rows(JsonObject chapter, GroupInfo group, Set<String> folded) {
        return rows(chapter, group, folded, null);
    }

    /**
     * The same, with the server's own refusal to show when there is no copy.
     *
     * <p>The placeholder used to say "has not arrived yet" whether a copy was in flight or had been
     * <b>refused</b>, which is the difference between waiting and being told no — and a panel that says
     * "still coming" about a chapter the server will never send is a panel nobody can act on. The reason
     * is passed in rather than looked up here so the layout stays a pure function of its inputs.
     *
     * <p>Only reachable in edit mode now: without it the tab takes {@link #notEditing()} instead, so
     * every "has not arrived" here is about a request that really was made.
     */
    public static List<InspectRow> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                        String missingNote) {
        List<InspectRow> rows = new ArrayList<>();
        if (chapter == null || chapter.isEmpty()) {
            String why = missingNote == null || missingNote.isBlank()
                    ? Labels.of("tasked.dev.chapter.missing_copy")
                    : Labels.of("tasked.dev.chapter.missing_copy_said", missingNote);
            rows.add(InspectRow.value(VALUE_PREFIX + "none", "tasked.dev.chapter.chapter", why));
            return List.copyOf(rows);
        }

        rows.add(InspectRow.heading(IDENTITY, "tasked.dev.chapter.identity"));
        if (!folded.contains(IDENTITY)) {
            rows.add(InspectRow.field("title", "tasked.dev.chapter.title", text(chapter, "title", "")));
            rows.add(InspectRow.field("subtitle", "tasked.dev.chapter.subtitle",
                    text(chapter, "subtitle", "")));
            rows.add(InspectRow.field(ICON, "tasked.dev.chapter.icon", iconId(chapter)));
            rows.add(InspectRow.value(VALUE_PREFIX + "description", "tasked.dev.chapter.description",
                    description(chapter)));
            rows.add(InspectRow.field("aliases", "tasked.dev.chapter.aliases",
                    String.join(", ", QuestPanelLayout.strings(chapter, "aliases"))));
        }

        rows.add(InspectRow.heading(RULES, "tasked.dev.chapter.rules"));
        if (!folded.contains(RULES)) {
            rows.add(choiceRow(chapter, PROGRESSION));
            rows.add(toggle(chapter, "defaultConsumeItems"));
            rows.add(choiceRow(chapter, PREREQUISITE));
            rows.add(choiceRow(chapter, AUTO_CLAIM));
            rows.add(choiceRow(chapter, LINE_FORM));
            rows.add(choiceRow(chapter, LINE_ARROW_HEAD));
            rows.add(choiceRow(chapter, LINE_ARROW_PLACE));
            rows.add(choiceRow(chapter, LINE_ARROW_DENSITY));
            rows.add(choiceRow(chapter, LINE_DASH));
            rows.add(choiceRow(chapter, LINE_WEIGHT));
        }

        if (group != null) {
            // Before the quest list, not after it: a chapter with twenty quests would push the group's
            // own fields below a scroll nobody makes, and the group is the thing this section exists to
            // make reachable.
            rows.add(InspectRow.heading(GROUP, "tasked.dev.chapter.group"));
            if (!folded.contains(GROUP)) {
                rows.add(InspectRow.field(GROUP_PREFIX + "title", "tasked.dev.chapter.title",
                        group.title()));
                rows.add(InspectRow.field(GROUP_PREFIX + ICON, "tasked.dev.chapter.icon",
                        group.iconId()));
                rows.add(InspectRow.toggle(GROUP_PREFIX + "collapsedByDefault",
                        group.collapsedByDefault() ? "tasked.dev.chapter.collapsed_on"
                                : "tasked.dev.chapter.collapsed_off"));
            }
        }

        rows.add(InspectRow.heading(QUESTS, "tasked.dev.chapter.quests"));
        if (!folded.contains(QUESTS)) {
            List<String> quests = QuestPanelLayout.strings(chapter, "quests");
            for (int i = 0; i < quests.size(); i++) {
                String name = quests.get(i);
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                rows.add(InspectRow.value(VALUE_PREFIX + "quest:" + id, (i + 1) + ".", id));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The instruction the tab adds when the tools are shut, drawn by the screen in the panel's feedback
     * band.
     *
     * <p>A constant rather than a literal at the drawing site because the test that says the sentence
     * still names the button it tells you to press reads this, not a copy of it.
     */
    public static final String EDIT_MODE_HINT = "tasked.dev.chapter.edit_hint";

    /**
     * The tab for a player who is not in edit mode: one line naming the state, and nothing to press.
     *
     * <h2>Why this is not the "has not arrived" placeholder</h2>
     *
     * <p>The chapter tools are the author's, and edit mode is the switch that says the author is
     * working: without it the client never asks the server for the chapter's copy, and the tab used to
     * report that copy as still coming -- a "wait" about a request that was never made, drawn truncated
     * in the dock's narrow value column. This says the one thing that is true; {@link #EDIT_MODE_HINT},
     * drawn in the band under the tabs, says the one thing to do about it.
     *
     * <p>A {@code VALUE} row deliberately: the screen builds no widget for one, so the not-editing tab
     * cannot show a field, a toggle or an arrow that looks pressable and silently does nothing.
     */
    public static List<InspectRow> notEditing() {
        return List.of(InspectRow.value(VALUE_PREFIX + "notEditing", "tasked.dev.chapter.chapter",
                Labels.of("tasked.dev.chapter.not_editing")));
    }

    // ------------------------------------------------------------------
    // The cycling rows
    // ------------------------------------------------------------------

    /** The field the chapter's line-style default lives in; its axes are one row each. */
    public static final String DEPENDENCY_STYLE = "dependencyStyle";

    /**
     * One cycling row: a closed set stepped by an arrow at each end.
     *
     * <p>This panel's closed sets -- the two progression rules, the consume-items flag (a toggle,
     * because two states are not a list), and the line-style axes -- are cycled rather than typed: the
     * rules were typed by hand once, where a typo is a value only the server refuses, and the picker
     * makes the valid values the only reachable ones and names the state a file that says nothing is in.
     *
     * <p>{@code key} is the row's identity and, for the two rules, the exact path a commit goes to. The
     * line axes are nested inside {@code dependencyStyle}, and a commit writes that whole object — see
     * {@link #choiceEdit} — so their keys are the dotted path read for information rather than the path
     * the op carries.
     *
     * <p>{@code fallback} is what the axis is worth when nothing, anywhere, says otherwise: the
     * manifest's own default for a rule, the built-ins for a line axis. The unset choice is labelled
     * with it, so the row never shows a bare "Default" the reader has to guess about.
     */
    public record Choice(String key, String label, List<String> values, String fallback) {

        public Choice {
            values = List.copyOf(values);
        }

        /** The dependencyStyle axis this row is, or the key itself for a top-level rule. */
        public String axis() {
            return isLineStyle()
                    ? key.substring(DEPENDENCY_STYLE.length() + 1) : key;
        }

        /** Whether this row is one axis of the chapter's line-style default. */
        public boolean isLineStyle() {
            return key.startsWith(DEPENDENCY_STYLE + ".");
        }
    }

    public static final Choice PROGRESSION =
            new Choice("progressionMode", "tasked.dev.chapter.progression",
                    List.of("flexible", "linear"), "flexible");

    public static final Choice PREREQUISITE = new Choice("defaultPrerequisiteMode",
            "tasked.dev.chapter.prerequisite",
            List.of("all_completed", "one_completed", "all_started", "one_started"), "all_completed");

    /**
     * Whether this chapter's quests hand their rewards over on completion.
     *
     * <p>The chapter rung of the auto-claim ladder, and the row that makes the feature an author's
     * rather than fifty per-reward settings: the unset state defers to the pack setting in
     * {@code index.json}, and a quest can still override this for itself.
     */
    public static final Choice AUTO_CLAIM = new Choice("autoClaim", "tasked.dev.chapter.auto_claim",
            List.of("disabled", "enabled", "no_toast", "invisible"), "pack setting");

    /** The chapter's line-style default, one axis per row: what a line's "Use chapter default" resets to. */
    public static final Choice LINE_FORM = new Choice(DEPENDENCY_STYLE + ".form",
            "tasked.dev.chapter.line_form",
            List.of("orthogonal", "chamfered", "straight", "curved"), "chamfered");
    public static final Choice LINE_ARROW_HEAD = new Choice(DEPENDENCY_STYLE + ".arrowHead",
            "tasked.dev.chapter.line_head",
            List.of("chevron", "triangle", "dot", "diamond", "none"), "chevron");
    public static final Choice LINE_ARROW_PLACE = new Choice(DEPENDENCY_STYLE + ".arrowPlace",
            "tasked.dev.chapter.line_place",
            List.of("target", "both", "mid", "stream"), "target");
    public static final Choice LINE_ARROW_DENSITY = new Choice(DEPENDENCY_STYLE + ".arrowDensity",
            "tasked.dev.chapter.line_density", List.of("low", "medium", "high"), "medium");
    public static final Choice LINE_DASH = new Choice(DEPENDENCY_STYLE + ".dash",
            "tasked.dev.chapter.line_pattern",
            List.of("solid", "dashed", "dotted", "dash_dot", "double", "hazard"), "solid");
    public static final Choice LINE_WEIGHT = new Choice(DEPENDENCY_STYLE + ".weight",
            "tasked.dev.chapter.line_weight",
            List.of("thin", "thick", "bold", "conduit"), "thin");

    /** The cycling rows, in the order the Rules section carries them. */
    public static final List<Choice> CHOICES = List.of(PROGRESSION, PREREQUISITE, AUTO_CLAIM, LINE_FORM,
            LINE_ARROW_HEAD, LINE_ARROW_PLACE, LINE_ARROW_DENSITY, LINE_DASH, LINE_WEIGHT);

    /** The cycling row a key names, or null for every other row. */
    public static Choice choiceForKey(String key) {
        for (Choice choice : CHOICES) {
            if (choice.key().equals(key)) {
                return choice;
            }
        }
        return null;
    }

    /** Whether a row key is one of the cycling rows. */
    public static boolean isChoiceKey(String key) {
        return choiceForKey(key) != null;
    }

    /** The chapter's line-style default as its file holds it, or an empty object when it declares none. */
    public static JsonObject lineStyle(JsonObject chapter) {
        JsonElement found = chapter == null ? null : chapter.get(DEPENDENCY_STYLE);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }

    /**
     * The value in force: the field itself, or the axis inside the chapter's dependencyStyle.
     *
     * <p>An arrow row with no current-axis value but a legacy {@code arrows} value reports what that
     * value means for this row, so a chapter written before the split does not read "Default" while its
     * lines stream — the file genuinely says something about arrows, and the picker's job is to show the
     * state before offering to change it.
     */
    public static String choiceValue(JsonObject chapter, Choice choice) {
        String value = text(choice.isLineStyle() ? lineStyle(chapter) : chapter, choice.axis(), "");
        if (!value.isEmpty() || !choice.isLineStyle()) {
            return value;
        }
        return legacyArrowValue(lineStyle(chapter), choice.axis());
    }

    /** What a legacy arrows value means for one arrow row, or empty for every other row and value. */
    private static String legacyArrowValue(JsonObject style, String axis) {
        String legacy = text(style, DependencyStyle.LEGACY_ARROWS_FIELD, "");
        if (legacy.isEmpty()) {
            return "";
        }
        return switch (axis) {
            case "arrowHead" -> legacy.equals("none") ? "none" : "chevron";
            case "arrowPlace" -> switch (legacy) {
                case "both" -> "both";
                case "many" -> "stream";
                default -> "target";
            };
            default -> "";
        };
    }

    /** A row's values as the picker cycles them: the unset state first, then the file's own names. */
    public static List<String> choiceValues(Choice choice) {
        List<String> all = new ArrayList<>();
        all.add("");
        all.addAll(choice.values());
        return List.copyOf(all);
    }

    /** The value a step lands on, wrapping at both ends. Empty is the unset state. */
    public static String cycleChoice(Choice choice, String current, int step) {
        List<String> all = choiceValues(choice);
        int at = all.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return all.get(Math.floorMod(at + step, all.size()));
    }

    /** A value as a person reads it; the unset state names the fallback it falls back to. */
    public static String choiceLabel(Choice choice, String value) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tasked.dev.chapter.default_value",
                    choice.fallback().replace('_', ' '));
        }
        return value.replace('_', ' ');
    }

    /** The op arguments a chosen value is written with: the path, and the value, null to remove. */
    public record Edit(String path, JsonElement value) {
    }

    /**
     * The write one chosen value lands on, as {@code EditorOp.SetChapter} takes it.
     *
     * <p>A rule writes itself: the value, or null for the unset state, which removes the field. A line
     * axis writes the whole {@code dependencyStyle} object, because that is the field the file has --
     * the object is copied, the one axis set or removed, and every other axis and the numeric bend
     * left exactly as they were. And when the last axis goes the object goes with it, the same rule
     * the per-line overrides follow: an empty object is a key that pins nothing.
     */
    public static Edit choiceEdit(JsonObject chapter, Choice choice, String value) {
        if (!choice.isLineStyle()) {
            return new Edit(choice.key(),
                    value == null || value.isEmpty() ? null : new JsonPrimitive(value));
        }
        JsonObject style = lineStyle(chapter).deepCopy();
        if (DependencyStyle.isArrowAxis(choice.axis())) {
            // The legacy arrows axis said the same three things at once; a chapter that speaks the
            // current vocabulary retires it, or a new axis returned to "Default" would fall back to it.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        if (value == null || value.isEmpty()) {
            style.remove(choice.axis());
        }
        else {
            style.addProperty(choice.axis(), value);
        }
        return new Edit(DEPENDENCY_STYLE, style.isEmpty() ? null : style);
    }

    /** One cycling row: a FIELD, which is what gives it a label band and a full-width control band. */
    private static InspectRow choiceRow(JsonObject chapter, Choice choice) {
        return InspectRow.field(choice.key(), choice.label(), choiceValue(chapter, choice));
    }

    // ------------------------------------------------------------------
    // The picker's arrows: drawn and pressed from the same boxes
    // ------------------------------------------------------------------

    /** One arrow's size: the strip arrows' own, because it is the same gesture. */
    public static final int CHOICE_ARROW_WIDTH = 16;
    public static final int CHOICE_ARROW_HEIGHT = 14;
    private static final int CHOICE_INSET = 2;

    /**
     * A cycling row's two arrows, in the control band they are drawn in: down (the previous value) at
     * the band's left edge, up (the next) at its right, and the value between them.
     *
     * <p>Derived from the band rather than stored, so the drawing and the press read one set of
     * rectangles; see {@link #choiceStepAt}.
     */
    public static Map<String, Slot> choiceArrows(Slot band) {
        int y = band.y() + (band.height() - CHOICE_ARROW_HEIGHT) / 2;
        Map<String, Slot> arrows = new LinkedHashMap<>();
        arrows.put("down", new Slot("down", band.x() + CHOICE_INSET, y,
                CHOICE_ARROW_WIDTH, CHOICE_ARROW_HEIGHT));
        arrows.put("up", new Slot("up",
                Math.max(band.x() + CHOICE_INSET, band.right() - CHOICE_INSET - CHOICE_ARROW_WIDTH), y,
                CHOICE_ARROW_WIDTH, CHOICE_ARROW_HEIGHT));
        return arrows;
    }

    /** Where a cycling row's value is drawn: between the arrows, clear of both. */
    public static Slot choiceValueSlot(Slot band) {
        Map<String, Slot> arrows = choiceArrows(band);
        Slot down = arrows.get("down");
        Slot up = arrows.get("up");
        return new Slot("value", down.right() + 4, band.y(),
                Math.max(0, up.x() - down.right() - 8), band.height());
    }

    /**
     * Which way a press at a screen point steps a cycling row: -1, +1, or null for a miss.
     *
     * <p>The caller maps the row and hands it over; the panel calls {@link #choiceArrows} with the band
     * it is drawing. One derivation, so what is drawn is what is pressed -- the rule the tools panel's
     * radius stepper learned first.
     */
    public static Integer choiceStepAt(Viewport view, Slot row, double mouseX, double mouseY) {
        if (row == null) {
            return null;
        }
        Slot band = InspectLayout.onScreen(view, InspectLayout.controlBand(row));
        Map<String, Slot> arrows = choiceArrows(band);
        for (String way : List.of("down", "up")) {
            if (arrows.get(way).contains((int) mouseX, (int) mouseY)) {
                return way.equals("down") ? -1 : 1;
            }
        }
        return null;
    }

    private static InspectRow toggle(JsonObject chapter, String path) {
        boolean on = chapter.has(path) && chapter.get(path).isJsonPrimitive()
                && chapter.get(path).getAsJsonPrimitive().isBoolean() && chapter.get(path).getAsBoolean();
        return InspectRow.toggle(path, on ? "tasked.dev.chapter.consume_on"
                : "tasked.dev.chapter.consume_off");
    }

    private static String text(JsonObject object, String member, String fallback) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : fallback;
    }

    /** The icon's item id, or "" when the chapter declares none. */
    private static String iconId(JsonObject chapter) {
        JsonObject icon = icon(chapter);
        return icon.has("item") && icon.get("item").isJsonPrimitive()
                ? icon.get("item").getAsString() : "";
    }

    /**
     * The description as the panel says it: how many paragraphs, and that they are edited in the file.
     *
     * <p>"Paragraphs" rather than "lines", which is what the model holds: the file's description is a
     * list of paragraphs, and calling them lines promised a wrapping that is not what the count means.
     */
    private static String description(JsonObject chapter) {
        JsonElement description = chapter.get("description");
        int paragraphs = description == null ? 0
                : description.isJsonArray() ? description.getAsJsonArray().size() : 1;
        if (paragraphs == 0) {
            return Labels.of("tasked.dev.chapter.none_in_file");
        }
        // One paragraph and several are two keys rather than one "paragraph(s)": the plural form is the
        // language's business, and a language may not have the same rule English does.
        return Labels.of(paragraphs == 1 ? "tasked.dev.chapter.paragraph_in_file"
                : "tasked.dev.chapter.paragraphs_in_file", paragraphs);
    }
}
