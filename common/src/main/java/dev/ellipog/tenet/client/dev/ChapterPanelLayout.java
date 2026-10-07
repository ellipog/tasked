package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.tenet.quest.DependencyStyle;

import java.util.ArrayList;
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

    /**
     * The pack's own faults, above everything else and <b>not foldable</b>.
     *
     * <h2>Why this heading has no fold when every other one does</h2>
     *
     * <p>Because a section is a category a reader may put away and this is a fault. A folded fault is a
     * hidden one, and hiding it is the whole of what it exists to stop: the report used to be a toast that
     * scrolled away, so an author who missed it went on working on a pack whose quests were quietly absent
     * from the tree. So it carries no marker, {@code ToolsLayout.folds} does not list it, and neither the
     * panel nor the screen gives it a press — the arrangement {@code SHAPE_SECTION} already has, and for
     * the same reason. It is also why the key is declared here and not mirrored in {@code ToolsLayout}: that
     * file's copies exist to be named by {@code folds}, and this one must never be.
     *
     * <p>Above Identity, because it is about the pack rather than about this chapter, and the reader who
     * opens this tab is the one who can act on it.
     */
    public static final String PROBLEMS = "h:problems";

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
    public static List<ToolsLayout.Action> rows(JsonObject chapter, Set<String> folded) {
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
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded) {
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
     *
     * <h2>These are the drawer's rows now, not the inspector's</h2>
     *
     * <p>{@code ToolsLayout.Action}, drawn by {@code ToolsPanel} -- the same record and the same drawer as
     * the Book tab beside it. They were {@code InspectRow}s, drawn by {@code QuestPanel.drawRows}, with the
     * cycling rows' arrows drawn by a third class and hit-tested by the screen: one tab of one panel built
     * three ways, and the two halves of it could not be changed together. The keys and the values are
     * unchanged, which is what kept the commits -- every one of them keyed by string -- untouched.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote) {
        return rows(chapter, group, folded, missingNote, Problems.NONE);
    }

    /**
     * What the last load found wrong with the pack, as this panel shows it.
     *
     * <p>Declared here rather than passed as {@code ClientEditProblems.Report} so the layout stays a
     * function of its own inputs, the way {@link GroupInfo} and {@link Header} are: this file is asserted
     * without a client, and reaching into the store that holds the news would make the test reach for it
     * too. The screen is where the two shapes meet, which is one line of glue rather than a dependency.
     *
     * @param count how many the server found, which may exceed the lines it could fit
     * @param lines the faults, one per line, as the server rendered them
     */
    public record Problems(int count, List<String> lines) {

        /** Nothing wrong, or nothing heard yet. The two are the same to this panel. */
        public static final Problems NONE = new Problems(0, List.of());
    }

    /**
     * The same, with the pack's own faults to show above everything else.
     *
     * <p>The report is the <b>pack's</b> and not this chapter's, which is why it is a parameter of the
     * panel rather than a field of the tree it draws: a chapter's copy can have arrived perfectly and the
     * pack still be broken. It is passed in rather than read here for the reason {@link Problems} gives.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote, Problems problems) {
        List<ToolsLayout.Action> rows = new ArrayList<>();
        problems(rows, problems);
        if (chapter == null || chapter.isEmpty()) {
            String why = missingNote == null || missingNote.isBlank()
                    ? Labels.of("tenet.dev.chapter.missing_copy")
                    : Labels.of("tenet.dev.chapter.missing_copy_said", missingNote);
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "none", "tenet.dev.chapter.chapter", why));
            return List.copyOf(rows);
        }

        rows.add(section(IDENTITY, "tenet.dev.chapter.identity", folded));
        if (!folded.contains(IDENTITY)) {
            rows.add(ToolsLayout.Action.text("title", "tenet.dev.chapter.title",
                    text(chapter, "title", "")));
            rows.add(ToolsLayout.Action.text("subtitle", "tenet.dev.chapter.subtitle",
                    text(chapter, "subtitle", "")));
            rows.add(ToolsLayout.Action.button(ICON, "tenet.dev.chapter.icon", iconId(chapter)));
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "description", "tenet.dev.chapter.description",
                    description(chapter)));
            rows.add(ToolsLayout.Action.text("aliases", "tenet.dev.chapter.aliases",
                    String.join(", ", QuestPanelLayout.strings(chapter, "aliases"))));
        }

        rows.add(section(RULES, "tenet.dev.chapter.rules", folded));
        if (!folded.contains(RULES)) {
            rows.add(choiceRow(chapter, PROGRESSION));
            rows.add(ToolsLayout.Action.toggle("defaultConsumeItems", "tenet.dev.chapter.consume",
                    flagOn(chapter, "defaultConsumeItems") ? ToolsLayout.ON : ToolsLayout.OFF));
            rows.add(choiceRow(chapter, PREREQUISITE));
            rows.add(choiceRow(chapter, AUTO_CLAIM));
            // The chapter's own gate, above the line-style rows because it is what the chapter *is* in
            // the progression rather than how its lines are drawn. Both id lists are TEXT rows, the shape
            // the aliases row above already uses: a chapter is picked by name here, and the validator is
            // what says a name does not resolve. A click-to-pick overlay for chapters is the obvious next
            // step and is deliberately not this one -- the picker is shaped around canvas nodes.
            rows.add(choiceRow(chapter, GATE_MODE));
            rows.add(ToolsLayout.Action.text("minRequired", "tenet.dev.chapter.gate_min",
                    numberText(chapter, "minRequired")));
            rows.add(ToolsLayout.Action.text("dependsOn", "tenet.dev.chapter.depends_on",
                    String.join(", ", QuestPanelLayout.strings(chapter, "dependsOn"))));
            rows.add(ToolsLayout.Action.text("completesWhen", "tenet.dev.chapter.completes_when",
                    String.join(", ", QuestPanelLayout.strings(chapter, "completesWhen"))));
            rows.add(ToolsLayout.Action.toggle("hideUntilDependenciesComplete",
                    "tenet.dev.chapter.hide_until_deps",
                    flagOn(chapter, "hideUntilDependenciesComplete") ? ToolsLayout.ON : ToolsLayout.OFF));
            // What this chapter's quests do about their *own* dependencies, unless a quest says
            // otherwise. Beside the row above because they are the pair an author will confuse: that one
            // withholds this chapter's row from a reader, these withhold its quests from everybody.
            rows.add(ToolsLayout.Action.toggle("defaultHideUntilDependenciesComplete",
                    "tenet.dev.chapter.default_hide_deps_complete",
                    flagOn(chapter, "defaultHideUntilDependenciesComplete")
                            ? ToolsLayout.ON : ToolsLayout.OFF));
            rows.add(ToolsLayout.Action.toggle("defaultHideUntilDependenciesVisible",
                    "tenet.dev.chapter.default_hide_deps_visible",
                    flagOn(chapter, "defaultHideUntilDependenciesVisible")
                            ? ToolsLayout.ON : ToolsLayout.OFF));
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
            rows.add(section(GROUP, "tenet.dev.chapter.group", folded));
            if (!folded.contains(GROUP)) {
                rows.add(ToolsLayout.Action.text(GROUP_PREFIX + "title", "tenet.dev.chapter.title",
                        group.title()));
                rows.add(ToolsLayout.Action.button(GROUP_PREFIX + ICON, "tenet.dev.chapter.icon",
                        group.iconId()));
                rows.add(ToolsLayout.Action.toggle(GROUP_PREFIX + "collapsedByDefault",
                        "tenet.dev.chapter.collapsed",
                        group.collapsedByDefault() ? ToolsLayout.ON : ToolsLayout.OFF));
            }
        }

        rows.add(section(QUESTS, "tenet.dev.chapter.quests", folded));
        if (!folded.contains(QUESTS)) {
            List<String> quests = QuestPanelLayout.strings(chapter, "quests");
            for (int i = 0; i < quests.size(); i++) {
                String name = quests.get(i);
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                // A read-only row: it holds no widget, and the press it does answer is the *drag* the
                // screen hit-tests from these same rectangles (`chapterQuestRowRects`), which is why it
                // stays a value row rather than becoming one whose widget is the row -- a widget would
                // take the press first and the reorder would have nothing to grab.
                rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "quest:" + id, (i + 1) + ".", id));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * One foldable section's heading: the marker, then the key the panel resolves.
     *
     * <p>The marker is part of the drawn string rather than a decoration the panel adds, because the
     * layout is handed the fold state and the marker is part of the label's own width -- the convention
     * {@code Labels.of} documents, and the one the Book tab's headings already follow.
     */
    private static ToolsLayout.Action section(String key, String label, Set<String> folded) {
        return ToolsLayout.Action.heading(key, (folded.contains(key) ? "\u203a " : "\u25bc ") + label);
    }

    /**
     * The pack's faults, above everything and never folded away.
     *
     * <h2>What it draws, and what it deliberately does not</h2>
     *
     * <p>A heading carrying the count, then one line per fault, then a line saying how many were too long to
     * send. The count is in the heading rather than in a row of its own because it is the one thing worth
     * reading at a glance — the badge exists so that a broken pack is visible without opening anything — and
     * the lines are there because the alternative is a number the author has to go to the log to interpret.
     *
     * <p><b>The lines are long and this column is narrow.</b> They are rendered by the server as
     * {@code file:line:column: error: message} and the panel truncates to the band, so a fault may be cut
     * mid-sentence. That is accepted rather than solved: the row is a pointer at a message whose whole text
     * is in the log and in {@code /tenet reload}'s output, and a truncated first clause plus the file name
     * is enough to know which file to open. Worth stating because the truncation is visible and would
     * otherwise read as a rendering fault.
     *
     * <p>A {@code VALUE} row per line, so the screen builds no widget for any of them: a fault is something
     * to read, and a row that looked pressable and did nothing would be the worse of the two.
     */
    private static void problems(List<ToolsLayout.Action> rows, Problems problems) {
        if (problems == null || problems.count() <= 0) {
            return;
        }
        // Resolved here rather than at the draw site: a heading's label goes through `Labels.of` with no
        // values, so a `%s` left in it would be drawn as one. A resolved string is not a key, and the
        // resolver passes it through unchanged -- which is what makes this the one place it can be done.
        rows.add(ToolsLayout.Action.heading(PROBLEMS,
                Labels.of("tenet.dev.chapter.problems", problems.count())));
        for (int i = 0; i < problems.lines().size(); i++) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "problem:" + i, "", problems.lines().get(i)));
        }
        int hidden = problems.count() - problems.lines().size();
        if (hidden > 0) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "problems:more", "",
                    Labels.of("tenet.dev.chapter.problems_more", hidden)));
        }
    }

    /** Whether one of the chapter's flags is on, as the file holds it. */
    private static boolean flagOn(JsonObject chapter, String path) {
        JsonElement found = chapter == null ? null : chapter.get(path);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isBoolean()
                && found.getAsBoolean();
    }

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
    public static List<ToolsLayout.Action> notEditing() {
        return List.of(ToolsLayout.Action.value(VALUE_PREFIX + "notEditing", "tenet.dev.chapter.chapter",
                Labels.of("tenet.dev.chapter.not_editing")));
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

    /**
     * The help a chapter row offers on hover, or null for a row whose label says it itself.
     *
     * <h2>Why a table, and why these rows needed one</h2>
     *
     * <p>The same shape {@code ToolsLayout.HELP} uses for the appearance rows, so the two halves of the
     * Chapter tab answer the pointer the same way. The rules are the reason it exists: "Default
     * Prerequisite Mode" is a name rather than an explanation, and the six line-style rows read like
     * JSON keys. The keys are the commit paths, so the table and the rows cannot drift about which key
     * a sentence belongs to.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("title", "tenet.dev.chapter.help.title"),
            Map.entry("subtitle", "tenet.dev.chapter.help.subtitle"),
            Map.entry(ICON, "tenet.dev.chapter.help.icon"),
            Map.entry("aliases", "tenet.dev.chapter.help.aliases"),
            Map.entry("progressionMode", "tenet.dev.chapter.help.progression"),
            Map.entry("defaultConsumeItems", "tenet.dev.chapter.help.consume_items"),
            Map.entry("defaultPrerequisiteMode", "tenet.dev.chapter.help.prerequisite"),
            Map.entry("autoClaim", "tenet.dev.chapter.help.auto_claim"),
            Map.entry("prerequisiteMode", "tenet.dev.chapter.help.gate_mode"),
            Map.entry("minRequired", "tenet.dev.chapter.help.gate_min"),
            Map.entry("dependsOn", "tenet.dev.chapter.help.depends_on"),
            Map.entry("completesWhen", "tenet.dev.chapter.help.completes_when"),
            Map.entry("hideUntilDependenciesComplete", "tenet.dev.chapter.help.hide_until_deps"),
            Map.entry("defaultHideUntilDependenciesComplete",
                    "tenet.dev.chapter.help.default_hide_deps_complete"),
            Map.entry("defaultHideUntilDependenciesVisible",
                    "tenet.dev.chapter.help.default_hide_deps_visible"),
            Map.entry(DEPENDENCY_STYLE + ".form", "tenet.dev.chapter.help.line_form"),
            Map.entry(DEPENDENCY_STYLE + ".arrowHead", "tenet.dev.chapter.help.line_head"),
            Map.entry(DEPENDENCY_STYLE + ".arrowPlace", "tenet.dev.chapter.help.line_place"),
            Map.entry(DEPENDENCY_STYLE + ".arrowDensity", "tenet.dev.chapter.help.line_density"),
            Map.entry(DEPENDENCY_STYLE + ".dash", "tenet.dev.chapter.help.line_pattern"),
            Map.entry(DEPENDENCY_STYLE + ".weight", "tenet.dev.chapter.help.line_weight"),
            Map.entry(GROUP_PREFIX + "title", "tenet.dev.chapter.help.group_title"),
            Map.entry(GROUP_PREFIX + ICON, "tenet.dev.chapter.help.group_icon"),
            Map.entry(GROUP_PREFIX + "collapsedByDefault", "tenet.dev.chapter.help.group_collapsed"));

    /** The help a chapter row key offers, or null. Headings and quest rows say themselves. */
    public static String help(String key) {
        return key == null ? null : HELP.get(key);
    }

    public static final Choice PROGRESSION =
            new Choice("progressionMode", "tenet.dev.chapter.progression",
                    List.of("flexible", "linear"), "flexible");

    public static final Choice PREREQUISITE = new Choice("defaultPrerequisiteMode",
            "tenet.dev.chapter.prerequisite",
            List.of("all_completed", "one_completed", "all_started", "one_started"), "all_completed");

    /**
     * What this chapter's <b>own</b> dependencies have to reach.
     *
     * <p>The row beside {@link #PREREQUISITE}, and the two are deliberately named apart on screen: this
     * one is about the chapters this chapter waits on, and that one is the mode its quests inherit. On
     * disk they are {@code prerequisiteMode} and {@code defaultPrerequisiteMode} — one letter apart, and
     * the difference is the direction the rule applies in.
     */
    public static final Choice GATE_MODE = new Choice("prerequisiteMode", "tenet.dev.chapter.gate_mode",
            List.of("all_completed", "one_completed", "all_started", "one_started"), "all_completed");

    /**
     * Whether this chapter's quests hand their rewards over on completion.
     *
     * <p>The chapter rung of the auto-claim ladder, and the row that makes the feature an author's
     * rather than fifty per-reward settings: the unset state defers to the pack setting in
     * {@code index.json}, and a quest can still override this for itself.
     */
    public static final Choice AUTO_CLAIM = new Choice("autoClaim", "tenet.dev.chapter.auto_claim",
            List.of("disabled", "enabled", "no_toast", "invisible"), "pack setting");

    /** The chapter's line-style default, one axis per row: what a line's "Use chapter default" resets to. */
    public static final Choice LINE_FORM = new Choice(DEPENDENCY_STYLE + ".form",
            "tenet.dev.chapter.line_form",
            List.of("orthogonal", "chamfered", "straight", "curved"), "chamfered");
    public static final Choice LINE_ARROW_HEAD = new Choice(DEPENDENCY_STYLE + ".arrowHead",
            "tenet.dev.chapter.line_head",
            List.of("chevron", "triangle", "dot", "diamond", "none"), "chevron");
    public static final Choice LINE_ARROW_PLACE = new Choice(DEPENDENCY_STYLE + ".arrowPlace",
            "tenet.dev.chapter.line_place",
            List.of("target", "both", "mid", "stream"), "target");
    public static final Choice LINE_ARROW_DENSITY = new Choice(DEPENDENCY_STYLE + ".arrowDensity",
            "tenet.dev.chapter.line_density", List.of("low", "medium", "high"), "medium");
    public static final Choice LINE_DASH = new Choice(DEPENDENCY_STYLE + ".dash",
            "tenet.dev.chapter.line_pattern",
            List.of("solid", "dashed", "dotted", "dash_dot", "double", "hazard"), "solid");
    public static final Choice LINE_WEIGHT = new Choice(DEPENDENCY_STYLE + ".weight",
            "tenet.dev.chapter.line_weight",
            List.of("thin", "thick", "bold", "conduit"), "thin");

    /** The cycling rows, in the order the Rules section carries them. */
    public static final List<Choice> CHOICES = List.of(PROGRESSION, PREREQUISITE, AUTO_CLAIM, GATE_MODE,
            LINE_FORM, LINE_ARROW_HEAD, LINE_ARROW_PLACE, LINE_ARROW_DENSITY, LINE_DASH, LINE_WEIGHT);

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

    /**
     * A row's values as its menu offers them: the unset state first, then the file's own names.
     *
     * <p>This is what the picker shows, in this order. It was the ring a pair of arrows stepped around --
     * {@code cycleChoice} said where one press landed -- and the arrows are gone: the chooser opens the
     * list and the author takes the value they want, which is the same control the book tab's choices use.
     */
    public static List<String> choiceValues(Choice choice) {
        List<String> all = new ArrayList<>();
        all.add("");
        all.addAll(choice.values());
        return List.copyOf(all);
    }

    /** A value as a person reads it; the unset state names the fallback it falls back to. */
    public static String choiceLabel(Choice choice, String value) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tenet.dev.chapter.default_value",
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

    /**
     * One cycling row: a choice, which is what gives it a label and the chooser the drawer's other
     * choices have.
     *
     * <p>The value rides on the row because the vocabulary is the <i>file's</i> -- nine axes, each with
     * its own names and its own unset state -- so the class that reads the file states the word and the
     * screen only names it. See {@code ChapterPanelLayout.choiceLabel}.
     */
    private static ToolsLayout.Action choiceRow(JsonObject chapter, Choice choice) {
        return ToolsLayout.Action.choice(choice.key(), choice.label(), choiceValue(chapter, choice));
    }

    private static String text(JsonObject object, String member, String fallback) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : fallback;
    }

    /**
     * A numeric field as a text row's value, or empty when the file says nothing.
     *
     * <p>Empty rather than the codec's default, for the same reason every other row here shows the
     * <i>file's</i> value: a row that printed the default would make "unset" and "set to the default"
     * look alike, and the first of those is what an author resets to.
     */
    private static String numberText(JsonObject object, String member) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()
                ? String.valueOf(found.getAsInt()) : "";
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
            return Labels.of("tenet.dev.chapter.none_in_file");
        }
        // One paragraph and several are two keys rather than one "paragraph(s)": the plural form is the
        // language's business, and a language may not have the same rule English does.
        return Labels.of(paragraphs == 1 ? "tenet.dev.chapter.paragraph_in_file"
                : "tenet.dev.chapter.paragraphs_in_file", paragraphs);
    }
}
