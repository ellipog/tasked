package dev.ellipog.tenet.client.viewer;

import java.util.List;

/**
 * One quest, laid out: the reference, its tasks in file order, its rewards in file order.
 *
 * <p>This is what a viewer draws and what {@link ItemQuestIndex} points at. It is deliberately not a
 * member of {@link QuestRef}: the same quest is a row in an item lookup (which needs only the
 * reference) and a page in EMI (which needs every row), and carrying rows into the index would make
 * every lookup copy the whole quest.
 *
 * <p>Immutable in the strong sense -- the lists are copied on construction -- because an EMI
 * registration reads one from a worker thread while the client thread may already be building the
 * next snapshot.
 */
public record QuestPage(QuestRef quest, List<QuestRow> tasks, List<QuestRow> rewards) {

    public QuestPage {
        if (quest == null) {
            throw new IllegalArgumentException("a page needs a quest");
        }
        tasks = List.copyOf(tasks);
        rewards = List.copyOf(rewards);
    }

    public int rowCount() {
        return tasks.size() + rewards.size();
    }
}
