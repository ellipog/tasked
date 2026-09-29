package dev.ellipog.tasked.progress;

import dev.ellipog.tasked.quest.Quest;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One team's progress through every quest.
 *
 * <h2>Keys are quest ids, and that is how renaming stops being destructive</h2>
 *
 * <p>Progress is stored under whatever id a quest had when it was recorded. When a quest is looked
 * up, its own id is tried first and then each of its {@code aliases} — so renaming a quest and adding
 * the old name to {@code aliases} means the stored progress is still found, and the author's rename
 * costs nobody anything.
 *
 * <p>That is the promise the format makes, and this class is where it is kept. It costs one extra
 * lookup per quest per evaluation, and it is the difference between a supported operation and one
 * that silently wipes a playthrough.
 *
 * <h2>Unknown ids are kept</h2>
 *
 * <p>Progress for a quest that is no longer in any file is <b>not</b> discarded. A quest removed
 * temporarily, or a file that failed to load, would otherwise silently delete progress — and
 * deleting data to tidy up is a bad trade when the data is a player's. Unknown entries cost a few
 * bytes and are reported by {@link #orphanCount}.
 */
public final class TeamProgress {

    /** Quest id (as recorded) to progress. */
    private final Map<String, QuestProgress> quests = new LinkedHashMap<>();

    public static TeamProgress empty() {
        return new TeamProgress();
    }

    /**
     * The progress for a quest, following aliases.
     *
     * <p>Returns {@link QuestProgress#NONE} rather than empty, so no caller has to write the fallback.
     */
    public QuestProgress progressOf(Quest quest) {
        QuestProgress direct = quests.get(quest.id());
        if (direct != null) {
            return direct;
        }
        for (String alias : quest.aliases()) {
            QuestProgress viaAlias = quests.get(alias);
            if (viaAlias != null) {
                return viaAlias;
            }
        }
        return QuestProgress.NONE;
    }

    /** How many stored entries no loaded quest claims, following aliases. Reported, not deleted. */
    public int orphanCount(java.util.function.Predicate<String> knownId) {
        return (int) quests.keySet().stream().filter(id -> !knownId.test(id)).count();
    }

    public int size() {
        return quests.size();
    }

    public java.util.Set<String> storedIds() {
        return java.util.Set.copyOf(quests.keySet());
    }

    /**
     * Stores progress under the quest's <b>current</b> id.
     *
     * <p>If the quest was found through an alias, this migrates the entry: the old key is removed and
     * the value written under the new one. So a rename is repaired the first time the quest is
     * touched, and the file does not accumulate stale ids forever.
     */
    public TeamProgress put(Quest quest, QuestProgress progress) {
        Map<String, QuestProgress> next = new LinkedHashMap<>(quests);
        for (String alias : quest.aliases()) {
            next.remove(alias);
        }
        next.put(quest.id(), progress);
        return with(next);
    }

    /** Removes a quest's progress entirely, aliases included. */
    public TeamProgress remove(Quest quest) {
        Map<String, QuestProgress> next = new LinkedHashMap<>(quests);
        next.remove(quest.id());
        quest.aliases().forEach(next::remove);
        return with(next);
    }

    /** Removes everything. */
    public static TeamProgress cleared() {
        return new TeamProgress();
    }

    private TeamProgress with(Map<String, QuestProgress> next) {
        TeamProgress out = new TeamProgress();
        out.quests.putAll(next);
        return out;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", 1);

        ListTag list = new ListTag();
        quests.forEach((id, progress) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("quest", id);
            entry.putString("state", progress.state().name());
            entry.putInt("timesCompleted", progress.timesCompleted());
            entry.putLong("lastCompletedAt", progress.lastCompletedAt());
            entry.putBoolean("rewardsClaimed", progress.rewardsClaimed());

            ListTag tasks = new ListTag();
            progress.taskProgress().forEach((index, amount) -> {
                CompoundTag task = new CompoundTag();
                task.putInt("index", index);
                task.putInt("amount", amount);
                tasks.add(task);
            });
            entry.put("tasks", tasks);

            list.add(entry);
        });
        tag.put("quests", list);

        return tag;
    }

    public static TeamProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        TeamProgress progress = new TeamProgress();

        ListTag list = tag.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("quest");
            if (id.isEmpty()) {
                continue;
            }

            Map<Integer, Integer> tasks = new LinkedHashMap<>();
            ListTag taskList = entry.getList("tasks", Tag.TAG_COMPOUND);
            for (int t = 0; t < taskList.size(); t++) {
                CompoundTag task = taskList.getCompound(t);
                tasks.put(task.getInt("index"), task.getInt("amount"));
            }

            progress.quests.put(id, new QuestProgress(
                    readState(entry.getString("state")),
                    tasks,
                    entry.getBoolean("rewardsClaimed"),
                    entry.getInt("timesCompleted"),
                    entry.getLong("lastCompletedAt")));
        }

        return progress;
    }

    private static QuestState readState(String raw) {
        try {
            return QuestState.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            // A state name that no longer exists. LOCKED is the safe reading: it grants nothing, and
            // the quest recomputes its own state from the dependency graph on the next evaluation.
            dev.ellipog.tasked.Constants.LOG.warn("tasked: unknown stored quest state '{}', treating as LOCKED", raw);
            return QuestState.LOCKED;
        }
    }
}
