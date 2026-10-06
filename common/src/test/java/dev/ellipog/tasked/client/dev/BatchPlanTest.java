package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which chapter a selection's quests belong to, and the three decisions that go with it.
 *
 * <p>Game-free, because it is arithmetic on names: the caller supplies "what chapter is this quest in",
 * which is the one thing the client's caches know and this class cannot. The failure it exists to prevent
 * is quiet rather than loud — an id under the wrong chapter is sent to an editor that does not hold that
 * quest, and the server answers "that edit would change nothing", which reads as a broken duplicate
 * rather than as a plan that mis-filed one id.
 */
@DisplayName("A selection, grouped by chapter")
class BatchPlanTest {

    /** Two chapters, and a quest nobody knows. */
    private static String chapterOf(String id) {
        return switch (id) {
            case "one", "two" -> "first_steps";
            case "three" -> "second_steps";
            default -> null;
        };
    }

    @Test
    @DisplayName("the plan keeps selection order inside a chapter, and first-seen order between them")
    void thePlanKeepsOrder() {
        Map<String, List<String>> plan = BatchPlan.byChapter(
                List.of("three", "one", "two"), BatchPlanTest::chapterOf);

        assertEquals(List.of("second_steps", "first_steps"), List.copyOf(plan.keySet()),
                "the first id seen decides which chapter's op goes out first");
        assertEquals(List.of("three"), plan.get("second_steps"));
        assertEquals(List.of("one", "two"), plan.get("first_steps"),
                "and inside a chapter the author's own order is kept: a duplicate's derived id depends "
                        + "on what came before it");
    }

    @Test
    @DisplayName("a quest no chapter claims is left out rather than sent where it cannot be edited")
    void unknownQuestsAreLeftOut() {
        Map<String, List<String>> plan = BatchPlan.byChapter(
                List.of("one", "ghost", "two"), BatchPlanTest::chapterOf);

        assertEquals(Map.of("first_steps", List.of("one", "two")), plan);
    }

    @Test
    @DisplayName("blanks and nulls are skipped, and a lone id is one chapter's one quest")
    void degenerateSelections() {
        assertEquals(Map.of("first_steps", List.of("one")),
                BatchPlan.byChapter(List.of("one"), BatchPlanTest::chapterOf));
        assertEquals(Map.of(), BatchPlan.byChapter(List.of(), BatchPlanTest::chapterOf));
        assertEquals(Map.of(), BatchPlan.byChapter(null, BatchPlanTest::chapterOf));
        assertEquals(Map.of(), BatchPlan.byChapter(Arrays.asList(null, "", "   ", "ghost"),
                BatchPlanTest::chapterOf), "nothing that cannot be edited makes a plan");
    }

    @Test
    @DisplayName("the plan cannot be edited by whoever acts on it")
    void thePlanIsReadOnly() {
        Map<String, List<String>> plan = BatchPlan.byChapter(List.of("one"), BatchPlanTest::chapterOf);

        assertThrows(UnsupportedOperationException.class, () -> plan.put("elsewhere", List.of("three")),
                "a plan is a description of what was sent, not a working list");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.get("first_steps").add("two"));
        assertFalse(plan.isEmpty());
        assertTrue(plan.containsKey("first_steps"));
    }
}
