package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.armature.client.ui.inspect.InspectRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Chapter tab's rows, from the chapter's own file.
 *
 * <p>The chapter is a file like a quest is, and this is the same inspector over it: identity, rules, and
 * the quest list in its authored order. Row keys are the dotted paths the commits go to, exactly as the
 * quest panel's are -- {@code EditorOp.SetChapter} is the op on the other end.
 *
 * <p>The quest list is shown in order and read-only here for now: reordering is a drag, and the drag is
 * built once for every list in the editor rather than three times. The list itself is worth showing
 * before it is draggable -- for a LINEAR chapter it <i>is</i> the progression, and an author can see
 * what they are about to reorder.
 */
public final class ChapterPanelLayout {

    /** The foldable sections, by their heading key. */
    public static final String IDENTITY = "h:identity";
    public static final String RULES = "h:rules";
    public static final String QUESTS = "h:quests";

    /** The read-only values' prefix. */
    public static final String VALUE_PREFIX = "v:";

    private ChapterPanelLayout() {
    }

    /** The whole panel for one chapter's tree. */
    public static List<InspectRow> rows(JsonObject chapter, Set<String> folded) {
        List<InspectRow> rows = new ArrayList<>();
        if (chapter == null || chapter.isEmpty()) {
            rows.add(InspectRow.value(VALUE_PREFIX + "none", "Chapter",
                    "The chapter's copy has not arrived yet"));
            return List.copyOf(rows);
        }

        rows.add(InspectRow.heading(IDENTITY, "Identity"));
        if (!folded.contains(IDENTITY)) {
            rows.add(InspectRow.field("title", "Title", text(chapter, "title", "")));
            rows.add(InspectRow.field("subtitle", "Subtitle", text(chapter, "subtitle", "")));
            rows.add(InspectRow.field("icon.item", "Icon Item", text(chapter, "icon.item", "")));
            int lines = lines(chapter);
            rows.add(InspectRow.value(VALUE_PREFIX + "description", "Description",
                    lines + (lines == 1 ? " line" : " lines") + " -- edited in the file"));
            rows.add(InspectRow.field("aliases", "Aliases, comma-separated",
                    String.join(", ", QuestPanelLayout.strings(chapter, "aliases"))));
        }

        rows.add(InspectRow.heading(RULES, "Rules"));
        if (!folded.contains(RULES)) {
            rows.add(InspectRow.field("progressionMode", "Progression Mode",
                    text(chapter, "progressionMode", "")));
            rows.add(toggle(chapter, "defaultConsumeItems"));
            rows.add(InspectRow.field("defaultPrerequisiteMode", "Default Prerequisite Mode",
                    text(chapter, "defaultPrerequisiteMode", "")));
        }

        rows.add(InspectRow.heading(QUESTS, "Quests, in order"));
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

    private static InspectRow toggle(JsonObject chapter, String path) {
        boolean on = chapter.has(path) && chapter.get(path).isJsonPrimitive()
                && chapter.get(path).getAsJsonPrimitive().isBoolean() && chapter.get(path).getAsBoolean();
        return InspectRow.toggle(path, QuestPanelLayout.labelFor(path) + (on ? " \u00b7 on" : " \u00b7 off"));
    }

    private static String text(JsonObject object, String member, String fallback) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : fallback;
    }

    private static int lines(JsonObject chapter) {
        JsonElement description = chapter.get("description");
        if (description == null) {
            return 0;
        }
        return description.isJsonArray() ? description.getAsJsonArray().size() : 1;
    }
}
