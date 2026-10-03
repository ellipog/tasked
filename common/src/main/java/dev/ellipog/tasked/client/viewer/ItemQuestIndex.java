package dev.ellipog.tasked.client.viewer;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which quests use an item, which award it, and which do either -- the one thing every viewer asks.
 *
 * <p>All three viewers find an item's page through their own ingredient index, so why does this exist?
 * Because the index they build is of <i>pages</i>, and a page is only registered for the quests a
 * viewer knows about: EMI registers them in one batch on a worker thread, and JEI and REI generate
 * them at lookup time. Every one of those paths needs the same answer -- "which quests, in what
 * order, and in which role" -- and three implementations of that walk would be three places for the
 * snapshot to be read wrongly.
 *
 * <p>Two roles, kept apart rather than merged into a boolean, because a viewer words them
 * differently: an item under <i>uses</i> is a task, under <i>recipes</i> a reward. A quest that both
 * uses and awards one item appears once in {@link #questsFor} -- required first -- so a lookup never
 * shows the same quest twice.
 *
 * <p>Built by the content side (it has the quest data) and read by the adapters (they have the
 * viewers). The builder is the only mutable part; a built index is immutable and safe to read from
 * any thread, which is the property EMI's registration depends on.
 */
public final class ItemQuestIndex {

    /**
     * One reference: the item, the quest, and the roles.
     *
     * <p>{@code required} is a task that hands the item in; {@code awarded} is a reward that grants it.
     * An entry with neither is a caller bug and is refused at build time -- the alternative is an
     * index that quietly answers "no quests" to a query it was handed the wrong shape for.
     */
    public record Entry(ResourceLocation item, QuestRef quest, boolean required, boolean awarded) {

        public Entry {
            Objects.requireNonNull(item, "an index entry needs an item");
            Objects.requireNonNull(quest, "an index entry needs a quest");
            if (!required && !awarded) {
                throw new IllegalArgumentException(
                        "an index entry for " + quest.id() + " must be required, awarded, or both");
            }
        }
    }

    private static final ItemQuestIndex EMPTY =
            new ItemQuestIndex(Map.of(), Set.of());

    private final Map<ResourceLocation, List<Entry>> byItem;
    private final Set<ResourceLocation> items;

    private ItemQuestIndex(Map<ResourceLocation, List<Entry>> byItem, Set<ResourceLocation> items) {
        this.byItem = byItem;
        this.items = items;
    }

    /** The index with nothing in it -- what an adapter reads before the tree has arrived. */
    public static ItemQuestIndex empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return byItem.isEmpty();
    }

    /** Every item some quest references, in no particular order. */
    public Set<ResourceLocation> items() {
        return items;
    }

    /** The quests that require the item, in insertion order, each once. */
    public List<QuestRef> questsUsing(ResourceLocation item) {
        return collect(item, true);
    }

    /** The quests that award the item, in insertion order, each once. */
    public List<QuestRef> questsAwarding(ResourceLocation item) {
        return collect(item, false);
    }

    /**
     * The quests that either require or award the item: the required ones first, then the awarded
     * ones that are not already there. The order matters to a row list, which reads top to bottom,
     * and so does the dedupe, which is what stops one quest appearing as both.
     */
    public List<QuestRef> questsFor(ResourceLocation item) {
        List<Entry> hits = byItem.get(item);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<QuestRef> out = new ArrayList<>(hits.size());
        Set<String> seen = new HashSet<>(hits.size());
        pass(hits, true, seen, out);
        pass(hits, false, seen, out);
        return List.copyOf(out);
    }

    private List<QuestRef> collect(ResourceLocation item, boolean required) {
        List<Entry> hits = byItem.get(item);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<QuestRef> out = new ArrayList<>(hits.size());
        Set<String> seen = new HashSet<>(hits.size());
        pass(hits, required, seen, out);
        return List.copyOf(out);
    }

    private static void pass(List<Entry> hits, boolean required, Set<String> seen, List<QuestRef> out) {
        for (Entry entry : hits) {
            if (entry.required() == required && seen.add(entry.quest().id())) {
                out.add(entry.quest());
            }
        }
    }

    /** Fills an index. One builder per snapshot; not thread-safe, and never shared. */
    public static final class Builder {

        private final Map<ResourceLocation, List<Entry>> byItem = new HashMap<>();
        private final Set<ResourceLocation> items = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder add(Entry entry) {
            Objects.requireNonNull(entry, "entry");
            byItem.computeIfAbsent(entry.item(), key -> new ArrayList<>(2)).add(entry);
            items.add(entry.item());
            return this;
        }

        /** Convenience for the common shape: a quest that uses an item, awards one, or both. */
        public Builder add(ResourceLocation item, QuestRef quest, boolean required, boolean awarded) {
            return add(new Entry(item, quest, required, awarded));
        }

        public ItemQuestIndex build() {
            if (byItem.isEmpty()) {
                return EMPTY;
            }
            Map<ResourceLocation, List<Entry>> frozen = new HashMap<>(byItem.size());
            byItem.forEach((item, entries) -> frozen.put(item, List.copyOf(entries)));
            return new ItemQuestIndex(Collections.unmodifiableMap(frozen),
                    Collections.unmodifiableSet(new LinkedHashSet<>(items)));
        }
    }
}
