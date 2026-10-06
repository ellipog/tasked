package dev.ellipog.tasked.client.dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Which chapter each quest of a selection belongs to, so one gesture becomes one op per chapter.
 *
 * <h2>Why the grouping is a class rather than a loop at the call site</h2>
 *
 * <p>Because it is the part of a bulk edit that can be wrong in a way nobody notices: an id that lands
 * under the wrong chapter is sent to an editor that does not hold that quest, and the server answers
 * "that edit would change nothing" — which reads as a broken duplicate rather than as a plan that put
 * one id in the wrong bucket. Selection order, chapter order and the treatment of an id no chapter
 * claims are all decisions, and this is where they are written down and tested.
 *
 * <h2>The three decisions</h2>
 *
 * <ul>
 *   <li><b>Selection order inside a chapter is kept.</b> The ops of one batch run in the order they were
 *       listed, and a duplicate's derived id ("quest_copy", "quest_copy_2") depends on what came before
 *       it, so the order is part of the result rather than a detail of the map.</li>
 *   <li><b>Chapters come in first-seen order.</b> The selection's first id decides which chapter's op
 *       goes out first, so the author's own chapter is usually the one answered first — and the order is
 *       stable rather than a hash's.</li>
 *   <li><b>An id no chapter claims is left out.</b> That is a quest the client's copy no longer holds (a
 *       reload, another author's delete), and the empty chapter means "no session", where a chapter edit
 *       is refused anyway. Leaving it in would produce a batch that can only fail.</li>
 * </ul>
 *
 * <p>Game-free and free of the client's caches: the caller supplies "what chapter is this quest in",
 * which is the one thing this class cannot answer and the one thing a test can.
 */
public final class BatchPlan {

    private BatchPlan() {
    }

    /**
     * The ids of a selection, grouped by the chapter that owns them.
     *
     * @param ids       the selection, in the order the author made it; nulls and blanks are skipped
     * @param chapterOf the chapter a quest id belongs to, or null when nothing knows it
     * @return chapter id to its quest ids, both in first-seen order and both unmodifiable
     */
    public static Map<String, List<String>> byChapter(List<String> ids,
                                                      Function<String, String> chapterOf) {
        Objects.requireNonNull(chapterOf, "chapterOf");
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String chapter = chapterOf.apply(id);
                if (chapter == null || chapter.isBlank()) {
                    continue;
                }
                grouped.computeIfAbsent(chapter, key -> new ArrayList<>()).add(id);
            }
        }
        // Copied and wrapped rather than returned as built: the caller acts on this plan, and a plan
        // that a later reader can still add to is a description of something other than what was sent.
        Map<String, List<String>> plan = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> chapter : grouped.entrySet()) {
            plan.put(chapter.getKey(), List.copyOf(chapter.getValue()));
        }
        return Collections.unmodifiableMap(plan);
    }
}
