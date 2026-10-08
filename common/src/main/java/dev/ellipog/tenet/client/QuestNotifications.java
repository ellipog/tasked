package dev.ellipog.tenet.client;

import dev.ellipog.tenet.progress.QuestState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The diff behind the completion, task, claim and chapter notices: what the cache held, against what it
 * holds now.
 *
 * <h2>Why the client derives this rather than being told</h2>
 *
 * <p>A progress sync carries a state, not a change — the same shape arrives whether a quest finished
 * a moment ago or was already finished when the player joined. The server's own line about a
 * completion goes to chat, and chat is not readable while a screen is open. So the difference
 * between "this quest is finished" and "this quest <i>just</i> finished" can only be the client
 * comparing two samples, and this is that comparison, kept game-free so it can be asserted without
 * a client. A task finishing is the same problem one level down, and it is derived the same way.
 *
 * <h2>The three silences, and each is a rule rather than an accident</h2>
 *
 * <ul>
 *   <li><b>The first sample seeds and says nothing.</b> It is a join, and a join into a world whose
 *       book is half finished must not announce the whole finished half — nor every task of it, which
 *       would be the same fault with a hundred times the volume.</li>
 *   <li><b>An empty sample is a reset, not a hundred removals.</b> A disconnect clears the cache and
 *       bumps its revision; a notifier holding the last map across that would read the next server's
 *       tree against the previous server's states — and a quest that was locked there and is
 *       complete here would be announced as a completion that never happened in front of this
 *       player. Empty means "no news", so the map is cleared and the next sample seeds again.
 *       {@link #chapters} reads an empty list the same way, and for the same reason.</li>
 *   <li><b>A silent auto-claim mode suppresses the completion.</b> An author who turned auto-claim
 *       on for fifty starter quests asked for exactly this; see {@code RewardAutoClaim.notifies}.
 *       The flag is computed by the caller, which is where the enum lives. It suppresses the
 *       <b>task</b> notices of that quest too, because the two are one author's answer to "do not
 *       tell me about this quest" — fifty quests that must not be announced would otherwise become
 *       four hundred task rows that were.</li>
 * </ul>
 *
 * <h2>A claim is a different transition from a completion</h2>
 *
 * <p>A claim is the moment the server stops owing: the quest was COMPLETED at both samples, what was
 * claimable is not any more. Requiring the state at both ends is what keeps a reset (COMPLETED →
 * LOCKED) or a fresh completion (UNLOCKED → COMPLETED) from reading as a claim, and it means the
 * sound fires when the server has confirmed the claim rather than when the button was pressed —
 * a refusal is not a claim.
 *
 * <h2>A task is a transition in the other direction, and one of them is not news</h2>
 *
 * <p><b>Unfinished → finished is told. Finished → unfinished is not.</b> The second happens for two
 * ordinary reasons — a repeatable quest cycling, and a counter that went down — and neither is
 * something a player needs a row about. So the baseline is replaced either way and only the arrival
 * is announced, which is the same shape {@link #compare}'s claim rule uses: a transition has a
 * direction, and naming the wrong one is a notice about nothing.
 *
 * <p><b>And a quest's own completion swallows its tasks'</b> in that sample. A quest becomes
 * COMPLETED when its last task does, so the alternative is one completion notice followed by the
 * whole task list arriving at once — the volume the cap would then spend on rows nobody needs,
 * because the sentence above them already said it.
 *
 * <h2>What a task baseline cannot see</h2>
 *
 * <p>A task a sample does not picture has no baseline, and a transition against nothing is not a
 * transition: a task at an index the previous sample did not reach is skipped rather than read as
 * newly finished. That is the tree having changed mid-session, where the honest answer is the one
 * the seeding rule already gives a joining client — silence, and a fresh baseline.
 *
 * <h2>Chapters are their own key space, and their own map</h2>
 *
 * <p>A chapter is not a quest: its state is computed by the server ({@code ChapterStates}), its id
 * is not a quest id, and a reader that folded the two into one map would answer "is this completed"
 * for whichever of the two happened to share an id. So {@link #chapters} keeps its own baseline and
 * is called on every progress change — the server sends the chapter map <b>whole in both a full sync
 * and a delta</b> ({@code QuestSync}), so unlike a quest there is nothing partial to be careful
 * about. The empty-list rule still applies, because a server older than chapter gates sends no map
 * at all and "every chapter open" is not "every chapter was just reopened".
 */
public final class QuestNotifications {

    /** What a notice is about. */
    public enum Kind {

        /** A quest that was not finished and now is. */
        COMPLETED,

        /** Rewards that were waiting and now are not: the server confirmed a claim. */
        CLAIMED,

        /** One task of a quest that was unfinished and now is not. */
        TASK_COMPLETED,

        /** A chapter whose quests are all done, where they were not a moment ago. */
        CHAPTER_COMPLETED
    }

    /**
     * One quest as the diff needs it.
     *
     * @param questId   the quest's id
     * @param state     the state the server last reported
     * @param claimable whether this player has rewards waiting on it
     * @param silent    whether the author asked for a completion to go unannounced
     * @param tasks     whether each task is finished, in the tree's order. Empty is legal and means this
     *                  caller has no task picture — a server too old to send one, or a test that is only
     *                  about quests — and it suppresses every task notice for that quest rather than
     *                  announcing all of them, which is the safe direction: a missing field must not read
     *                  as "everything just finished"
     */
    public record Snapshot(String questId, QuestState state, boolean claimable, boolean silent,
                           List<Boolean> tasks) {

        public Snapshot {
            tasks = List.copyOf(tasks);
        }

        /** The three-argument shape, for a caller with no task picture. See {@link #tasks}. */
        public Snapshot(String questId, QuestState state, boolean claimable, boolean silent) {
            this(questId, state, claimable, silent, List.of());
        }
    }

    /**
     * One chapter as the diff needs it: an id, and how far the server says it has got.
     *
     * <p>No "silent": a chapter has no auto-claim mode and no author flag about notices, and inventing
     * one here would be a second answer to a question the pack already answers for the quest inside it.
     */
    public record ChapterSnapshot(String chapterId, QuestState state) {
    }

    /**
     * One thing that just happened.
     *
     * <p>The caller resolves the title it shows; this carries the id. {@code index} is the task's position
     * for a {@link Kind#TASK_COMPLETED} and -1 for everything else, which is what lets the two-argument
     * constructor stay the whole of what a quest- or chapter-level notice has to say.
     *
     * @param subjectId the quest's id, or a chapter's for {@link Kind#CHAPTER_COMPLETED}
     * @param index     which task, or -1
     */
    public record Notice(Kind kind, String subjectId, int index) {

        /** A notice about a quest or a chapter as a whole. */
        public Notice(Kind kind, String subjectId) {
            this(kind, subjectId, -1);
        }

        /** Whether this names one task, and so has an index to read. */
        public boolean namesTask() {
            return kind == Kind.TASK_COMPLETED && index >= 0;
        }
    }

    /** What one quest was at the last sample. */
    private record Sample(QuestState state, boolean claimable, List<Boolean> tasks) {
    }

    private final Map<String, Sample> last = new LinkedHashMap<>();

    /** The same, for chapters: their own map because their ids are their own space. See the class note. */
    private final Map<String, QuestState> lastChapters = new LinkedHashMap<>();

    /**
     * The notices since the last sample, oldest first.
     *
     * <p>Also the seeding: the first sample after a reset returns nothing and remembers everything,
     * which is what makes a join quiet.
     */
    public List<Notice> sample(List<Snapshot> now) {
        if (now.isEmpty()) {
            // See the class note: a cleared or not-yet-arrived cache is not "everything was just
            // removed". Forgetting is the honest reading, and the next sample seeds silently.
            last.clear();
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<Notice> notices = compare(now, seen);
        // A quest the tree no longer carries is forgotten rather than kept: a reload that removes one
        // must not leave its id behind to announce on a later reappearance, and an unbounded map of
        // every id a long session has ever seen is a leak with no reader.
        last.keySet().retainAll(seen);
        return List.copyOf(notices);
    }

    /**
     * The notices for a sample that names only <b>some</b> of the quests — the ids one delta carried.
     *
     * <h2>Why this is not {@link #sample} with a shorter list</h2>
     *
     * <p>Because of what the two do with the ids they were <i>not</i> given, and the two answers are
     * opposites rather than variations:
     *
     * <ul>
     *   <li>The full sample <b>forgets</b> every quest it does not name, because it is a statement about
     *       the whole cache: a quest the tree no longer carries must not be left behind to announce on
     *       a later reappearance.</li>
     *   <li>A partial sample must <b>keep</b> every quest it does not name, because those quests did
     *       not move. Forgetting them is not a tidy-up, it is a blindness: this diff only announces a
     *       transition it has a previous sample for, so a quest dropped from the baseline can never be
     *       announced again — a completion would arrive and be compared against nothing.</li>
     * </ul>
     *
     * <p>And an <b>empty</b> partial sample means the opposite of an empty full one. The full reading is
     * "the cache is empty, forget it"; this reading is "the message named nothing, so nothing
     * happened", which is a delta about a quest that turned out to be unchanged. Both are pinned in
     * {@code QuestNotificationsTest}, because the two methods differ in exactly the places where being
     * wrong is silent.
     *
     * <h2>What a partial sample still cannot see</h2>
     *
     * <p>It forgets the ids <b>the message named</b> that the tree no longer holds, which is everything
     * a delta can say about a removal. A quest removed by a <i>tree</i> edit, with no progress message
     * naming it, keeps its baseline until the next full sync — a join or a reload, both of which send
     * one. That is a bounded residue rather than a leak: it is at most the ids this session has been
     * told about, and it is cleared by the first full sample after it. Stated here rather than left to
     * be discovered, because a baseline kept for a quest that no longer exists is exactly the kind of
     * thing that announces a completion nobody just made.
     *
     * @param named the ids the message named — a delta's keys plus anything it removed
     * @param some  the pictures taken for those of them the tree still holds
     */
    public List<Notice> sampleSome(Set<String> named, List<Snapshot> some) {
        // What the message named and the tree no longer holds. The full sample forgets these by
        // omission — it walks the tree, so a quest that is not there is not in its sample — and a
        // partial one has to be told, because the ids it was given came from the message rather than
        // from the tree.
        Set<String> pictured = new HashSet<>();
        for (Snapshot snapshot : some) {
            pictured.add(snapshot.questId());
        }
        for (String id : named) {
            if (!pictured.contains(id)) {
                last.remove(id);
            }
        }
        if (some.isEmpty()) {
            return List.of();
        }
        // The set is filled and dropped: pruning is the full sample's business, and passing it here is
        // the price of one comparison rule rather than two.
        return List.copyOf(compare(some, new HashSet<>()));
    }

    /**
     * The chapter notices since the last chapter sample, oldest first.
     *
     * <p>Called on <b>every</b> progress change rather than only on a full sync, and the reason is the
     * server's own shape: {@code QuestSync} writes the chapter map whole into both forms, so a delta carries
     * as much of it as a full sync does. Its seeding and its empty-list rule are {@link #sample}'s, one
     * granularity over — see the class note.
     */
    public List<Notice> chapters(List<ChapterSnapshot> now) {
        if (now.isEmpty()) {
            lastChapters.clear();
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<Notice> notices = new ArrayList<>();
        for (ChapterSnapshot snapshot : now) {
            seen.add(snapshot.chapterId());
            QuestState was = lastChapters.put(snapshot.chapterId(), snapshot.state());
            if (was == null) {
                continue;   // a join, or a chapter the tree just gained: nothing to compare against
            }
            if (was != QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED) {
                notices.add(new Notice(Kind.CHAPTER_COMPLETED, snapshot.chapterId()));
            }
        }
        // A chapter the pack no longer carries goes the same way a quest does, and for the same reason.
        lastChapters.keySet().retainAll(seen);
        return List.copyOf(notices);
    }

    /**
     * The transitions, in one place: what each named quest is now against what it was, with every
     * snapshot becoming the new baseline as it is compared.
     *
     * @param seen filled with every id this sample names, for a caller that prunes afterwards
     */
    private List<Notice> compare(List<Snapshot> now, Set<String> seen) {
        List<Notice> notices = new ArrayList<>();
        for (Snapshot snapshot : now) {
            seen.add(snapshot.questId());
            Sample was = last.put(snapshot.questId(),
                    new Sample(snapshot.state(), snapshot.claimable(), snapshot.tasks()));
            if (was == null) {
                continue;   // a join, or a quest the tree just gained: nothing to compare against
            }
            // Asked once and read twice: the completion is a notice of its own, and it is also what
            // suppresses the tasks below it. See the class note.
            boolean justCompleted = was.state() != QuestState.COMPLETED
                    && snapshot.state() == QuestState.COMPLETED;
            if (justCompleted && !snapshot.silent()) {
                notices.add(new Notice(Kind.COMPLETED, snapshot.questId()));
            }
            if (was.state() == QuestState.COMPLETED && snapshot.state() == QuestState.COMPLETED
                    && was.claimable() && !snapshot.claimable()) {
                notices.add(new Notice(Kind.CLAIMED, snapshot.questId()));
            }
            if (!justCompleted && !snapshot.silent()) {
                addTaskNotices(notices, snapshot, was);
            }
        }
        return notices;
    }

    /**
     * The tasks that arrived, oldest first.
     *
     * <p>Only as far as both pictures reach: an index the previous sample did not carry has no baseline, and
     * a transition against nothing is not a transition. See the class note's "what a task baseline cannot
     * see".
     */
    private static void addTaskNotices(List<Notice> notices, Snapshot now, Sample was) {
        int counted = Math.min(now.tasks().size(), was.tasks().size());
        for (int i = 0; i < counted; i++) {
            if (!was.tasks().get(i) && now.tasks().get(i)) {
                notices.add(new Notice(Kind.TASK_COMPLETED, now.questId(), i));
            }
        }
    }

    /** Forgets everything: a disconnect, or a change of team. */
    public void reset() {
        last.clear();
        lastChapters.clear();
    }
}
