package dev.ellipog.tasked.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pending card value, and the two moments it stops being needed.
 *
 * <p>Game-free by construction: a draft is a value and two numbers, and the copy it reconciles against
 * is a lambda. Every rule the stepper's responsiveness rests on is pinned here — the draft winning, the
 * copy agreeing, and the backstop that stops a disagreement masking the truth forever.
 */
class FieldDraftTest {

    private static final String CHAPTER = "getting_started";

    /** A copy that answers one value for every field, standing in for the replica. */
    private static FieldDraft.ServerValues holding(JsonElement value) {
        return (quest, path) -> value;
    }

    /**
     * The reward-table panels' shape: a table root, paths relative to it, and an owner that is not a
     * quest id. The table editor draws from {@code overlaid} with exactly these.
     */
    private static JsonObject tableRoot() {
        JsonObject entry = new JsonObject();
        entry.addProperty("weight", 4);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject table = new JsonObject();
        table.addProperty("lootSize", 1);
        table.add("entries", entries);
        return table;
    }

    @Test
    @DisplayName("the draft answers until a newer copy holds the same value")
    void theDraftWinsUntilTheCopyAgrees() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 7L, 0L);

        assertEquals(new JsonPrimitive(5), draft.value(CHAPTER, "q", "count"));

        // A copy at the revision the ask was made at proves nothing yet.
        draft.reconcile(CHAPTER, 7L, holding(new JsonPrimitive(4)), 100L);
        assertEquals(new JsonPrimitive(5), draft.value(CHAPTER, "q", "count"));

        // A newer copy holding the asked value: the server caught up, and the draft is gone.
        draft.reconcile(CHAPTER, 8L, holding(new JsonPrimitive(5)), 200L);
        assertNull(draft.value(CHAPTER, "q", "count"));
        assertTrue(draft.isEmpty());
    }

    @Test
    @DisplayName("a newer copy that disagrees is ignored until the backstop")
    void aDisagreeingCopyIsBoundedByTime() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 7L, 1000L);

        // A burst's own copy can land mid-burst and disagree with the newest ask for a moment.
        draft.reconcile(CHAPTER, 8L, holding(new JsonPrimitive(3)),
                1000L + FieldDraft.STALE_MILLIS);
        assertEquals(new JsonPrimitive(5), draft.value(CHAPTER, "q", "count"),
                "inside the backstop the author's value still stands");

        // Past it, the copy is the truth: a refusal whose reply was lost, or another author's edit.
        draft.reconcile(CHAPTER, 9L, holding(new JsonPrimitive(3)),
                1000L + FieldDraft.STALE_MILLIS + 1);
        assertNull(draft.value(CHAPTER, "q", "count"));
    }

    @Test
    @DisplayName("a refusal forgets its own chapter and nothing else")
    void aRefusalForgetsOneChapter() {
        FieldDraft draft = new FieldDraft();
        draft.set("a", "q1", "count", new JsonPrimitive(2), 1L, 0L);
        draft.set("b", "q2", "count", new JsonPrimitive(3), 1L, 0L);

        draft.forgetChapter("a");

        assertNull(draft.value("a", "q1", "count"));
        assertEquals(new JsonPrimitive(3), draft.value("b", "q2", "count"));
        assertFalse(draft.isEmpty());
    }

    @Test
    @DisplayName("a draft only answers for the chapter it was asked in")
    void aDraftIsChapterScoped() {
        FieldDraft draft = new FieldDraft();
        draft.set("a", "q1", "count", new JsonPrimitive(2), 1L, 0L);

        assertNull(draft.value("b", "q1", "count"));
    }

    @Test
    @DisplayName("two chapters' drafts for one fixed owner are two drafts, not one overwritten slot")
    void twoChaptersDraftsDoNotCollide() {
        // The case the chapter *guard* could not catch, which is why the chapter has to be in the key.
        //
        // `CHAPTER_OWNER` and `BOOK_OWNER` are the same string in every chapter — that is the whole point
        // of them — so with the key being `owner\0path` alone, chapter A's pending chapter-title and
        // chapter B's were one map entry. B's write replaced A's, and then A's read found an entry whose
        // chapter was B and answered **null**: "not mine" and "not there" were the same answer, so a
        // pending edit silently vanished and the author's press looked like it had done nothing.
        FieldDraft draft = new FieldDraft();
        draft.set("alpha", FieldDraft.CHAPTER_OWNER, "title", new JsonPrimitive("First"), 1L, 0L);
        draft.set("beta", FieldDraft.CHAPTER_OWNER, "title", new JsonPrimitive("Second"), 1L, 0L);

        assertEquals(new JsonPrimitive("First"),
                draft.value("alpha", FieldDraft.CHAPTER_OWNER, "title"),
                "alpha's own pending title survives beta's write");
        assertEquals(new JsonPrimitive("Second"),
                draft.value("beta", FieldDraft.CHAPTER_OWNER, "title"),
                "and beta has its own");

        // The same for the book's settings, which is the other fixed owner.
        draft.set("alpha", FieldDraft.BOOK_OWNER, "bookTitle", new JsonPrimitive("A"), 1L, 0L);
        draft.set("beta", FieldDraft.BOOK_OWNER, "bookTitle", new JsonPrimitive("B"), 1L, 0L);

        assertEquals(new JsonPrimitive("A"),
                draft.value("alpha", FieldDraft.BOOK_OWNER, "bookTitle"));
        assertEquals(new JsonPrimitive("B"),
                draft.value("beta", FieldDraft.BOOK_OWNER, "bookTitle"));

        // And the typed readers agree, since they go through the same lookup.
        assertEquals("First", draft.text("alpha", FieldDraft.CHAPTER_OWNER, "title", "server"));
        assertEquals("Second", draft.text("beta", FieldDraft.CHAPTER_OWNER, "title", "server"));

        // Forgetting one chapter leaves the other's alone, which is the other half of "they are two".
        draft.forgetChapter("alpha");
        assertNull(draft.value("alpha", FieldDraft.CHAPTER_OWNER, "title"));
        assertEquals(new JsonPrimitive("Second"),
                draft.value("beta", FieldDraft.CHAPTER_OWNER, "title"),
                "and beta is untouched by alpha's refusal");
    }

    @Test
    @DisplayName("clear forgets everything, and an empty draft answers nothing")
    void clearForgetsEverything() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 7L, 0L);
        draft.clear();

        assertTrue(draft.isEmpty());
        assertNull(draft.value(CHAPTER, "q", "count"));
    }

    @Test
    @DisplayName("an overlay applies one owner's values, nested paths included, and never the tree")
    void anOverlayAppliesOneOwnersValues() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 1L, 0L);
        draft.set(CHAPTER, FieldDraft.CHAPTER_OWNER, "rules.minRequired", new JsonPrimitive(2), 1L, 0L);
        draft.set(CHAPTER, FieldDraft.CHAPTER_OWNER, "subtitle", null, 1L, 0L);
        draft.set(CHAPTER, "other", "count", new JsonPrimitive(9), 1L, 0L);

        JsonObject tree = new JsonObject();
        tree.addProperty("subtitle", "old");
        JsonObject quest = new JsonObject();
        quest.addProperty("count", 1);
        JsonObject overlaid = draft.overlaid(CHAPTER, FieldDraft.CHAPTER_OWNER, tree);
        JsonObject questOverlaid = draft.overlaid(CHAPTER, "q", quest);

        assertEquals(2, overlaid.getAsJsonObject("rules").get("minRequired").getAsInt());
        assertFalse(overlaid.has("subtitle"), "a cleared field reads as absent, like the file will");
        assertTrue(tree.has("subtitle"), "the tree itself is not touched");
        assertFalse(tree.has("rules"));
        assertEquals(5, questOverlaid.get("count").getAsInt());
        assertEquals(1, quest.get("count").getAsInt(), "the quest tree itself is not touched");
    }

    @Test
    @DisplayName("an overlay with nothing pending hands back the tree itself")
    void anEmptyOverlayCopiesNothing() {
        FieldDraft draft = new FieldDraft();
        JsonObject tree = new JsonObject();

        assertSame(tree, draft.overlaid(CHAPTER, FieldDraft.CHAPTER_OWNER, tree));
    }

    @Test
    @DisplayName("the typed readers prefer the draft, fall back to the server, and ignore other chapters")
    void typedReadersPreferTheDraft() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 1L, 0L);
        draft.set(CHAPTER, "q", "title", new JsonPrimitive("New"), 1L, 0L);
        draft.set(CHAPTER, "q", "optional", new JsonPrimitive(true), 1L, 0L);
        draft.set(CHAPTER, "q", "iconScale", new JsonPrimitive(1.5), 1L, 0L);
        draft.set(CHAPTER, "q", "dependsOn", arrayOf("a", "b"), 1L, 0L);

        assertEquals(5, draft.number(CHAPTER, "q", "count", 1));
        assertEquals("New", draft.text(CHAPTER, "q", "title", "Old"));
        assertTrue(draft.flag(CHAPTER, "q", "optional", false));
        assertEquals(1.5, draft.decimal(CHAPTER, "q", "iconScale", 0.75));
        assertEquals(List.of("a", "b"), draft.strings(CHAPTER, "q", "dependsOn", List.of()));

        assertEquals(1, draft.number(CHAPTER, "q", "size", 1), "a field with no draft keeps the server's");
        assertEquals(9, draft.number("elsewhere", "q", "count", 9), "another chapter's draft is ignored");
        assertEquals(List.of("x"), draft.strings(CHAPTER, "q", "other", List.of("x")));
    }

    @Test
    @DisplayName("the overlay is cached until the draft or the tree changes")
    void theOverlayIsCached() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 1L, 0L);
        JsonObject tree = new JsonObject();
        tree.addProperty("count", 1);

        JsonObject first = draft.overlaid(CHAPTER, "q", tree);
        assertSame(first, draft.overlaid(CHAPTER, "q", tree),
                "nothing changed, so the copy is not made again");

        draft.set(CHAPTER, "q", "count", new JsonPrimitive(6), 1L, 0L);
        JsonObject second = draft.overlaid(CHAPTER, "q", tree);
        assertNotSame(first, second, "a write makes a fresh overlay");
        assertEquals(6, second.get("count").getAsInt());

        assertNotSame(second, draft.overlaid(CHAPTER, "q", new JsonObject()),
                "a new tree is a new overlay");
    }

    @Test
    @DisplayName("an overlay writes through arrays: a task's count changes and the list survives")
    void anOverlayKeepsArraysIntact() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.count", new JsonPrimitive(5), 1L, 0L);
        draft.set(CHAPTER, "q", "tasks.1.optional", new JsonPrimitive(true), 1L, 0L);
        draft.set(CHAPTER, "q", "rewards.0.item", new JsonPrimitive("minecraft:diamond"), 1L, 0L);

        JsonObject tree = new JsonObject();
        JsonArray tasks = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("count", 1);
        JsonObject second = new JsonObject();
        second.addProperty("optional", false);
        tasks.add(first);
        tasks.add(second);
        tree.add("tasks", tasks);
        JsonArray rewards = new JsonArray();
        rewards.add(new JsonObject());
        tree.add("rewards", rewards);

        JsonObject overlaid = draft.overlaid(CHAPTER, "q", tree);

        assertTrue(overlaid.get("tasks").isJsonArray(),
                "the task list is still a list -- an object here is every task vanishing from the card");
        assertEquals(2, overlaid.getAsJsonArray("tasks").size(), "and still holds both tasks");
        assertEquals(5, overlaid.getAsJsonArray("tasks").get(0).getAsJsonObject().get("count").getAsInt());
        assertTrue(overlaid.getAsJsonArray("tasks").get(1).getAsJsonObject().get("optional").getAsBoolean());
        assertEquals("minecraft:diamond",
                overlaid.getAsJsonArray("rewards").get(0).getAsJsonObject().get("item").getAsString());

        // The tree itself is untouched: the overlay is a copy, and the replica stays the server's word.
        assertEquals(1, tree.getAsJsonArray("tasks").get(0).getAsJsonObject().get("count").getAsInt());
        assertFalse(tree.getAsJsonArray("tasks").get(1).getAsJsonObject().get("optional").getAsBoolean());
    }

    @Test
    @DisplayName("an overlay into a list index past the end pads rather than corrupting")
    void anOverlayPadsAListItReachesPast() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.2.count", new JsonPrimitive(9), 1L, 0L);

        JsonObject tree = new JsonObject();
        JsonArray tasks = new JsonArray();
        tasks.add(new JsonObject());
        tree.add("tasks", tasks);

        JsonObject overlaid = draft.overlaid(CHAPTER, "q", tree);

        assertEquals(3, overlaid.getAsJsonArray("tasks").size());
        assertEquals(9, overlaid.getAsJsonArray("tasks").get(2).getAsJsonObject().get("count").getAsInt());
        assertEquals(1, tree.getAsJsonArray("tasks").size(), "the tree is not padded, the copy is");
    }

    @Test
    @DisplayName("an overlay writes a nested condition inside a task's own list")
    void anOverlayWritesThroughNestedLists() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.conditions.1.min", new JsonPrimitive(4), 1L, 0L);

        JsonObject tree = new JsonObject();
        JsonArray tasks = new JsonArray();
        JsonObject task = new JsonObject();
        JsonArray conditions = new JsonArray();
        conditions.add(new JsonObject());
        JsonObject second = new JsonObject();
        second.addProperty("min", 1);
        conditions.add(second);
        task.add("conditions", conditions);
        tasks.add(task);
        tree.add("tasks", tasks);

        JsonObject overlaid = draft.overlaid(CHAPTER, "q", tree);

        assertEquals(4, overlaid.getAsJsonArray("tasks").get(0).getAsJsonObject()
                .getAsJsonArray("conditions").get(1).getAsJsonObject().get("min").getAsInt());
        assertEquals(1, tree.getAsJsonArray("tasks").get(0).getAsJsonObject()
                .getAsJsonArray("conditions").get(1).getAsJsonObject().get("min").getAsInt());
    }

    @Test
    @DisplayName("forgetting a list drops only that list's indexed drafts")
    void forgetListDropsOneListsDrafts() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.count", new JsonPrimitive(5), 1L, 0L);
        draft.set(CHAPTER, "q", "tasks.1.count", new JsonPrimitive(6), 1L, 0L);
        draft.set(CHAPTER, "q", "rewards.0.count", new JsonPrimitive(7), 1L, 0L);
        draft.set(CHAPTER, "q", "title", new JsonPrimitive("T"), 1L, 0L);
        draft.set(CHAPTER, "other", "tasks.0.count", new JsonPrimitive(8), 1L, 0L);

        draft.forgetList(CHAPTER, "q", "tasks");

        assertNull(draft.value(CHAPTER, "q", "tasks.0.count"),
                "a structural edit shifts the indices, so the drafted position names another entry");
        assertNull(draft.value(CHAPTER, "q", "tasks.1.count"));
        assertEquals(new JsonPrimitive(7), draft.value(CHAPTER, "q", "rewards.0.count"));
        assertEquals(new JsonPrimitive("T"), draft.value(CHAPTER, "q", "title"));
        assertEquals(new JsonPrimitive(8), draft.value(CHAPTER, "other", "tasks.0.count"),
                "another owner's list is not this one's");
    }

    @Test
    @DisplayName("forgetting a nested list drops the drafts inside it, and only its own")
    void forgetListDropsNestedListDrafts() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.conditions.1.min", new JsonPrimitive(5), 1L, 0L);
        draft.set(CHAPTER, "q", "tasks.0.conditions.1", new JsonPrimitive(1), 1L, 0L);
        draft.set(CHAPTER, "q", "tasks.0.count", new JsonPrimitive(3), 1L, 0L);
        draft.set(CHAPTER, "q", "tasks.1.conditions.0.min", new JsonPrimitive(7), 1L, 0L);

        // The gesture: removing one condition rebuilds that task's condition list, so every position
        // inside it moves -- and nothing outside it does.
        draft.forgetList(CHAPTER, "q", "tasks.0.conditions");

        assertNull(draft.value(CHAPTER, "q", "tasks.0.conditions.1.min"));
        assertNull(draft.value(CHAPTER, "q", "tasks.0.conditions.1"));
        assertEquals(new JsonPrimitive(3), draft.value(CHAPTER, "q", "tasks.0.count"),
                "the task's other fields do not move");
        assertEquals(new JsonPrimitive(7), draft.value(CHAPTER, "q", "tasks.1.conditions.0.min"),
                "another task's conditions are not this list");
    }

    @Test
    @DisplayName("a newer copy shorter than the drafted index drops the draft")
    void aShorterCopyDropsTheIndexedDraft() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.1.count", new JsonPrimitive(5), 1L, 1000L);
        draft.set(CHAPTER, "q", "title", new JsonPrimitive("T"), 1L, 1000L);

        // The ask's own revision proves nothing yet, even with the position absent.
        draft.reconcile(CHAPTER, 1L, valuesOf(oneTaskQuest()), 1000L);
        assertEquals(new JsonPrimitive(5), draft.value(CHAPTER, "q", "tasks.1.count"));

        // Another author removed a task: the copy is newer and the position is gone. The draft is
        // about nothing now -- drawing it, or committing from it, would land on the wrong entry.
        draft.reconcile(CHAPTER, 2L, valuesOf(oneTaskQuest()), 1000L);
        assertNull(draft.value(CHAPTER, "q", "tasks.1.count"));
        assertEquals(new JsonPrimitive("T"), draft.value(CHAPTER, "q", "title"),
                "a draft that names no position still stands until it converges or stales");
    }

    @Test
    @DisplayName("a newer copy with the position present keeps a disagreeing draft")
    void anInRangeDisagreeingCopyKeepsTheDraft() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.count", new JsonPrimitive(5), 1L, 1000L);

        draft.reconcile(CHAPTER, 2L, valuesOf(oneTaskQuest()), 1000L);
        assertEquals(new JsonPrimitive(5), draft.value(CHAPTER, "q", "tasks.0.count"),
                "position zero exists, so the copy is merely behind -- the backstop is the bound");
    }

    @Test
    @DisplayName("a newer copy with a shorter nested list drops the nested draft")
    void aShorterNestedListDropsTheDraft() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.conditions.2.min", new JsonPrimitive(4), 1L, 1000L);

        // The task is there and in range; its condition list holds two entries, so index two is not.
        draft.reconcile(CHAPTER, 2L, valuesOf(oneTaskQuest()), 1000L);
        assertNull(draft.value(CHAPTER, "q", "tasks.0.conditions.2.min"));
    }

    @Test
    @DisplayName("a newer copy with no such list at all drops the indexed draft")
    void aMissingListDropsTheIndexedDraft() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "rewards.0.count", new JsonPrimitive(5), 1L, 1000L);

        draft.reconcile(CHAPTER, 2L, valuesOf(new JsonObject()), 100L);
        assertNull(draft.value(CHAPTER, "q", "rewards.0.count"),
                "the copy holds no such list: the position cannot be there");
    }

    @Test
    @DisplayName("a draft at the whole list path is a value, not a position, and is not dropped")
    void aWholeListDraftIsNotOutOfRange() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks.0.conditions", arrayOf("a"), 1L, 1000L);

        // The shape `removeCondition` writes: the copy is newer and its task has no conditions key at
        // all, but the draft names the list itself, so only convergence or the backstop may end it.
        draft.reconcile(CHAPTER, 2L, valuesOf(oneTaskQuestWithoutConditions()), 1000L);
        assertEquals(List.of("a"), draft.strings(CHAPTER, "q", "tasks.0.conditions", List.of()));

        // And a structural forget does not touch the list's own draft either: the caller that shifts a
        // list rewrites that path in the same breath, so the draft is replaced, never orphaned.
        draft.forgetList(CHAPTER, "q", "tasks.0.conditions");
        assertEquals(List.of("a"), draft.strings(CHAPTER, "q", "tasks.0.conditions", List.of()));
    }

    @Test
    @DisplayName("a cleared field converges when the copy holds nothing there")
    void aClearedFieldConverges() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "subtitle", null, 1L, 0L);

        // `server.equals(null)` is never true, so the cleared case needs its own comparison or it
        // could only ever expire on the clock.
        draft.reconcile(CHAPTER, 2L, (owner, path) -> null, 100L);

        assertTrue(draft.isEmpty());
    }

    @Test
    @DisplayName("a list draft converges by value, element for element")
    void aListDraftConvergesByValue() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "dependsOn", arrayOf("a", "b"), 1L, 0L);

        draft.reconcile(CHAPTER, 2L,
                (owner, path) -> arrayOf("a", "c"), 100L);
        assertEquals(List.of("a", "b"), draft.strings(CHAPTER, "q", "dependsOn", List.of()),
                "a different list is not the answer yet");

        draft.reconcile(CHAPTER, 3L, (owner, path) -> arrayOf("a", "b"), 200L);
        assertNull(draft.value(CHAPTER, "q", "dependsOn"));
    }

    @Test
    @DisplayName("a path that names nothing writes nothing")
    void aMalformedPathWritesNothing() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "tasks..count", new JsonPrimitive(5), 1L, 0L);

        JsonObject tree = new JsonObject();
        JsonObject overlaid = draft.overlaid(CHAPTER, "q", tree);

        assertTrue(overlaid.isEmpty(), "a doubled dot must not add a stray empty member");
        assertTrue(tree.isEmpty());
    }

    @Test
    @DisplayName("forgetting a chapter drops its overlays with its drafts")
    void forgetChapterDropsItsOverlays() {
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "q", "count", new JsonPrimitive(5), 1L, 0L);
        JsonObject tree = new JsonObject();
        tree.addProperty("count", 1);
        assertNotSame(tree, draft.overlaid(CHAPTER, "q", tree), "a draft makes a copy");

        draft.forgetChapter(CHAPTER);

        assertSame(tree, draft.overlaid(CHAPTER, "q", tree),
                "with nothing pending there is nothing to copy, and no cached copy to serve");
    }

    private static JsonArray arrayOf(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** One task and no conditions key -- the copy of a list the server has never held. */
    private static JsonObject oneTaskQuestWithoutConditions() {
        JsonObject task = new JsonObject();
        task.addProperty("count", 1);
        JsonArray tasks = new JsonArray();
        tasks.add(task);
        JsonObject quest = new JsonObject();
        quest.add("tasks", tasks);
        return quest;
    }

    /** One task with two conditions -- a copy where index 1 exists and condition index 2 does not. */
    private static JsonObject oneTaskQuest() {
        JsonObject task = new JsonObject();
        task.addProperty("count", 1);
        JsonArray conditions = new JsonArray();
        conditions.add(new JsonObject());
        conditions.add(new JsonObject());
        task.add("conditions", conditions);
        JsonArray tasks = new JsonArray();
        tasks.add(task);
        JsonObject quest = new JsonObject();
        quest.add("tasks", tasks);
        return quest;
    }

    /** A copy that answers from a real tree, by dotted path, indices included. */
    private static FieldDraft.ServerValues valuesOf(JsonObject tree) {
        return (owner, path) -> {
            JsonElement node = tree;
            for (String step : path.split("\\.")) {
                if (node instanceof JsonObject object) {
                    node = object.get(step);
                }
                else if (node instanceof JsonArray array && !step.isEmpty()
                        && step.chars().allMatch(Character::isDigit)) {
                    int index = Integer.parseInt(step);
                    node = index < array.size() ? array.get(index) : null;
                }
                else {
                    return null;
                }
                if (node == null) {
                    return null;
                }
            }
            return node;
        };
    }

    // ------------------------------------------------------------------
    // The per-owner reconciler, which the table panels use
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a per-owner reconcile judges that owner's drafts and leaves the others alone")
    void reconcileOwnerLeavesOtherOwnersAlone() {
        // The bug this pins: `reconcile` walks every draft in a chapter and asks the chapter's copy
        // about each. A table panel's values are not in that copy at all, so a table draft handed to it
        // would be judged against the wrong file -- converged or dropped by a value that is not its own.
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "#table:dice", "lootSize", new JsonPrimitive(2), 7L, 0L);
        draft.set(CHAPTER, "a_quest", "title", new JsonPrimitive("Two"), 7L, 0L);

        // The table's copy arrives holding what was asked for: the table draft goes.
        draft.reconcileOwner(CHAPTER, "#table:dice", 8L, holding(new JsonPrimitive(2)), 100L);
        assertNull(draft.value(CHAPTER, "#table:dice", "lootSize"));
        assertEquals(new JsonPrimitive("Two"), draft.value(CHAPTER, "a_quest", "title"),
                "and the quest's draft is untouched by a table's copy");

        // And the other way round.
        draft.set(CHAPTER, "#table:dice", "lootSize", new JsonPrimitive(3), 8L, 100L);
        draft.reconcile(CHAPTER, 9L, holding(new JsonPrimitive("Two")), 200L);
        assertEquals(new JsonPrimitive(3), draft.value(CHAPTER, "#table:dice", "lootSize"),
                "the chapter's reconciler does not judge a table's draft");
    }

    @Test
    @DisplayName("forgetOwner drops one owner's drafts, which is what an undo needs")
    void forgetOwnerDropsOneOwnersDrafts() {
        // The bug this pins: an undone value stayed masked by its own draft, so Ctrl+Z looked like it
        // did nothing -- the panel went on drawing the number the server had just put back.
        FieldDraft draft = new FieldDraft();
        draft.set(CHAPTER, "#table:dice", "entries.0.weight", new JsonPrimitive(9), 7L, 0L);
        draft.set(CHAPTER, "#table:dice", "lootSize", new JsonPrimitive(2), 7L, 0L);
        draft.set(CHAPTER, "a_quest", "title", new JsonPrimitive("Two"), 7L, 0L);

        draft.forgetOwner(CHAPTER, "#table:dice");

        assertNull(draft.value(CHAPTER, "#table:dice", "entries.0.weight"));
        assertNull(draft.value(CHAPTER, "#table:dice", "lootSize"));
        assertEquals(new JsonPrimitive("Two"), draft.value(CHAPTER, "a_quest", "title"),
                "and nothing else goes with it");
    }

    @Test
    @DisplayName("an overlay applies a table's own paths, so the editor draws a drafted weight")
    void anOverlayAppliesTablePaths() {
        // The editor's paths are relative to the table root: `entries.0.weight`, not `rewards.0.weight`.
        // The overlay is the one place every read in the panel sees the pending value, which is what
        // makes the odds, the count and the total move on the press rather than on the replica.
        FieldDraft draft = new FieldDraft();
        JsonObject table = tableRoot();
        draft.set(CHAPTER, "#table:dice", "entries.0.weight", new JsonPrimitive(9), 7L, 0L);

        JsonObject drawn = draft.overlaid(CHAPTER, "#table:dice", table);

        assertEquals(9, drawn.getAsJsonArray("entries").get(0).getAsJsonObject().get("weight").getAsInt());
        assertEquals(4, table.getAsJsonArray("entries").get(0).getAsJsonObject().get("weight").getAsInt(),
                "and the tree it was made from is untouched");
        assertEquals(1, drawn.get("lootSize").getAsInt(), "the rest of the table is the file's");
    }
}
