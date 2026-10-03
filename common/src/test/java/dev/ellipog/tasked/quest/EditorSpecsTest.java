package dev.ellipog.tasked.quest;

import dev.ellipog.tasked.quest.reward.RewardCommon;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor forms: that every registered type has one, that it draws every field the type has, and that
 * the fields which need knowledge offer it.
 *
 * <h2>Why this is a test rather than a convention</h2>
 *
 * <p>Because the fault it catches is silent. A type that grows a field and does not grow its form looks
 * fine -- the field is simply not on the card, and an author wonders where it went; a form that names a
 * field the codec does not take looks fine too, until the edit it sends is refused. Both are one
 * assertion here, over every registered type, so the next type cannot ship with either.
 *
 * <p>The same place is where the other promise lives: a field that is an id the game can list -- a
 * dimension, a biome, an advancement, a statistic -- must be a search rather than a text box. That is the
 * rule the request was about, written down where it can fail a build.
 */
@DisplayName("editor forms")
class EditorSpecsTest {

    /**
     * The fields that are ids, and the controls that are allowed to edit them.
     *
     * <p>A text box is deliberately not among the allowed kinds for any of them: an id you must know by
     * heart is not an editor, and every one of these is a list the client can show.
     */
    private static final Map<String, Set<EditorField.Kind>> IDS_MUST_BE_LISTED = Map.ofEntries(
            Map.entry("item", Set.of(EditorField.Kind.ITEM)),
            Map.entry("icon", Set.of(EditorField.Kind.ITEM)),
            Map.entry("dimension", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("biome", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("structure", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("advancement", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("stat", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("fluid", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("entity", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("toObserve", Set.of(EditorField.Kind.SEARCH)),
            Map.entry("tag", Set.of(EditorField.Kind.TAG)),
            Map.entry("entityTypeTag", Set.of(EditorField.Kind.TAG)));

    @Test
    @DisplayName("every task type's form draws every field it has, and nothing it does not")
    void taskFormsCoverTheirFields() {
        for (ResourceLocation id : TaskTypes.ids()) {
            Set<String> paths = paths(TaskTypes.editorOf(id));
            Set<String> declared = TaskTypes.fieldsOf(id);

            assertTrue(paths.containsAll(declared),
                    id + " declares " + new TreeSet<>(minus(declared, paths))
                            + ", which its form does not draw");
            assertTrue(paths.containsAll(TaskCommon.FIELDS),
                    id + " has no control for " + new TreeSet<>(minus(TaskCommon.FIELDS, paths))
                            + "; every task has these");
            Set<String> invented = minus(paths, union(declared, TaskCommon.FIELDS));
            assertTrue(invented.isEmpty(),
                    id + " draws " + invented + ", which its codec does not take");
        }
    }

    @Test
    @DisplayName("every reward type's form draws every field it owns, base mechanics included")
    void rewardFormsCoverTheirFields() {
        for (ResourceLocation id : RewardTypes.ids()) {
            Set<String> paths = paths(RewardTypes.editorOf(id));
            Set<String> declared = RewardTypes.fieldsOf(id);

            // Everything the type itself declares, and everything RewardCommon contributes except `team`,
            // which is an optional boolean rather than a switch -- see RewardTypes.COMMON_EDITOR.
            assertTrue(paths.containsAll(minus(declared, RewardCommon.FIELDS)),
                    id + " declares " + new TreeSet<>(minus(minus(declared, RewardCommon.FIELDS), paths))
                            + ", which its form does not draw");
            assertTrue(paths.containsAll(
                            Set.of("auto", "excludeFromClaimAll", "ignoreRewardBlocking")),
                    id + " has no control for the base mechanics");
            Set<String> undrawn = minus(declared, paths);
            assertTrue(undrawn.isEmpty() || undrawn.equals(Set.of("team")),
                    id + " draws no control for " + new TreeSet<>(minus(undrawn, Set.of("team")))
                            + "; only `team` is exempt, and it is the panel's");
        }
    }

    @Test
    @DisplayName("a field that is an id the game can list is never a text box")
    void idsAreSearchedRatherThanTyped() {
        for (ResourceLocation id : TaskTypes.ids()) {
            checkIdsAreListed(id, TaskTypes.editorOf(id));
        }
        for (ResourceLocation id : RewardTypes.ids()) {
            checkIdsAreListed(id, RewardTypes.editorOf(id));
        }
    }

    private static void checkIdsAreListed(ResourceLocation id, List<EditorField> form) {
        for (EditorField field : form) {
            Set<EditorField.Kind> allowed = IDS_MUST_BE_LISTED.get(last(field.path()));
            if (allowed != null) {
                assertTrue(allowed.contains(field.kind()),
                        id + "'s `" + field.path() + "` is edited as " + field.kind()
                                + ", and a field that is an id belongs in " + allowed);
            }
        }
    }

    @Test
    @DisplayName("a number says what it counts")
    void numbersCarryTheirUnits() {
        for (ResourceLocation id : TaskTypes.ids()) {
            assertUnits(id, TaskTypes.editorOf(id));
        }
        for (ResourceLocation id : RewardTypes.ids()) {
            assertUnits(id, RewardTypes.editorOf(id));
        }
    }

    private static void assertUnits(ResourceLocation id, List<EditorField> form) {
        for (EditorField field : form) {
            if (field.path().equals("count") || field.path().equals("amount")) {
                assertFalse(field.unit().isEmpty(),
                        id + "'s `" + field.path() + "` is a bare number, and a bare number "
                                + "makes the author read its neighbours to know what it counts");
            }
        }
    }

    @Test
    @DisplayName("every field of every type says what it does")
    void everyFieldExplainsItself() {
        // The rule the labels' hover rests on. A field with no sentence is a label of one or two words
        // and nothing else -- and a form of switches and steppers reads as a set of guesses without it.
        for (ResourceLocation id : TaskTypes.ids()) {
            assertHints(id, TaskTypes.editorOf(id));
        }
        for (ResourceLocation id : RewardTypes.ids()) {
            assertHints(id, RewardTypes.editorOf(id));
        }
    }

    private static void assertHints(ResourceLocation id, List<EditorField> form) {
        for (EditorField field : form) {
            assertFalse(field.hint().isBlank(),
                    id + "'s `" + field.path() + "` has no hint, so hovering its label says nothing "
                            + "about what the field does");
        }
    }

    @Test
    @DisplayName("a type with no form gets one derived from its names, in a stable order")
    void derivationIsStableAndSensible() {
        List<EditorField> derived = EditorSpecs.derive(
                new LinkedHashSet<>(List.of("dimension", "consumeItems", "count", "wobble")));
        assertEquals(List.of("consumeItems", "count", "dimension", "wobble"),
                derived.stream().map(EditorField::path).toList(),
                "sorted, so two openings of the same type agree");

        assertEquals(EditorField.Kind.FLAG, kind(derived, "consumeItems"));
        assertEquals(EditorField.Kind.NUMBER, kind(derived, "count"));
        assertEquals(EditorField.Source.DIMENSION,
                derived.stream().filter(field -> field.path().equals("dimension")).findFirst()
                        .orElseThrow().source(),
                "a name the game can list is searched even when nobody declared the form");
        assertEquals(EditorField.Kind.TEXT, kind(derived, "wobble"),
                "and a name nothing is known about is still editable");

        assertEquals("Only from crafting", EditorSpecs.label("onlyFromCrafting"));
        assertEquals("Block tag", EditorSpecs.label("block_tag"));
        assertEquals("Count", EditorSpecs.label("count"));
    }

    private static EditorField.Kind kind(List<EditorField> fields, String path) {
        return fields.stream().filter(field -> field.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + path)).kind();
    }

    private static Set<String> paths(List<EditorField> fields) {
        Set<String> out = new TreeSet<>();
        for (EditorField field : fields) {
            // The codec field names, not the boxes: an item also covers its components, and a triple
            // names the array its boxes index. See EditorField#fieldNames.
            out.addAll(field.fieldNames());
        }
        return out;
    }

    /** The last segment of a path: {@code position.0} is about {@code position}. */
    private static String last(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static Set<String> minus(Set<String> from, Set<String> remove) {
        Set<String> out = new TreeSet<>(from);
        out.removeAll(remove);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
