package dev.ellipog.tasked.progress;

import dev.ellipog.tasked.quest.Chapter;
import dev.ellipog.tasked.quest.ChapterRef;
import dev.ellipog.tasked.quest.ChapterRules;
import dev.ellipog.tasked.quest.PrerequisiteMode;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestRef;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out which chapters are open, and which are finished.
 *
 * <h2>The one thing that keeps this from being circular</h2>
 *
 * <p>A chapter's state depends on its gate, and a quest's state depends on its chapter's state, so the
 * obvious reading of "resolve the chapters" runs into the quest resolver and back again. It does not,
 * because a chapter's state is a function of <b>stored progress and the chapter graph only</b>: what
 * players have actually done ({@link TeamProgress}) and which chapters wait on which. No step here asks
 * what state a quest resolved to, which is what makes the two passes independent rather than mutually
 * recursive — chapters first, quests after, one direction.
 *
 * <p>The same property makes a chapter cycle harmless to compute around: it is reported at load
 * ({@code ProgressionEngine.findChapterCycles}), and here a chapter already on the stack comes back
 * LOCKED, so a loop simply never opens rather than hanging.
 *
 * <h2>The ladder</h2>
 *
 * <ul>
 *   <li>{@link QuestState#LOCKED} — the chapter's own rule over {@code dependsOn} is unmet.</li>
 *   <li>{@link QuestState#COMPLETED} — {@code completesWhen} is non-empty and every quest it names is
 *       completed. A repeatable milestone counts after its first completion and stays counted, which is
 *       the reading a quest dependency gets -- see {@link ProgressionEngine#satisfiedForDependents}.</li>
 *   <li>{@link QuestState#STARTED} — any quest in the chapter has been touched or finished.</li>
 *   <li>{@link QuestState#UNLOCKED} — open, with nothing done in it.</li>
 * </ul>
 *
 * <p>A chapter with no {@code completesWhen} therefore never reports COMPLETED, which is a real state
 * rather than an oversight: a chapter that is only ever waited on as "started" needs no completion to
 * declare, and a gate that asks for completed anyway is refused at load.
 */
public final class ChapterStates {

    private ChapterStates() {
    }

    /**
     * The state of every chapter in the index, keyed by the chapter's own id.
     *
     * <p>Chapters are resolved from the progress a team has actually recorded, which is the only thing
     * that cannot be derived. Nothing is stored about chapters, for the reason the engine gives about
     * quests: a stored chapter state could drift out of step with the files, and then a player is locked
     * out of a chapter whose conditions they have actually met.
     */
    public static Map<String, QuestState> resolve(QuestIndex index, TeamProgress progress) {
        Map<String, QuestState> states = new LinkedHashMap<>();
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            resolveOne(index, entry.chapter(), progress, states, new ArrayDeque<>());
        }
        return states;
    }

    /**
     * One chapter's state, resolving the chapters it waits on first.
     *
     * <p>{@code visiting} is the cycle guard, exactly as the quest resolver's is: a chapter already
     * being resolved higher up the stack comes back LOCKED instead of recursing again.
     */
    private static QuestState resolveOne(QuestIndex index,
                                         Chapter chapter,
                                         TeamProgress progress,
                                         Map<String, QuestState> states,
                                         Deque<String> visiting) {
        QuestState known = states.get(chapter.id());
        if (known != null) {
            return known;
        }
        if (visiting.contains(chapter.id())) {
            return QuestState.LOCKED;
        }
        visiting.push(chapter.id());
        try {
            ChapterRules rules = chapter.rules();

            // The gate. An unresolvable reference locks the chapter for the same reason a quest's does:
            // the loader reports it, and a chapter that cannot be opened is visible as broken, whereas
            // one that opened for the wrong reason is not.
            PrerequisiteMode mode = rules.prerequisiteMode();
            int satisfied = 0;
            for (ChapterRef dependency : rules.dependsOn()) {
                Optional<QuestIndex.ChapterEntry> found = index.chapter(dependency.id());
                if (found.isEmpty()) {
                    continue;
                }
                QuestState state = resolveOne(index, found.get().chapter(), progress, states, visiting);
                if (state.isAtLeast(QuestState.bar(mode))) {
                    satisfied++;
                }
            }
            if (satisfied < rules.requiredCount()) {
                states.put(chapter.id(), QuestState.LOCKED);
                return QuestState.LOCKED;
            }

            QuestState result = openState(index, chapter, progress);
            states.put(chapter.id(), result);
            return result;
        }
        finally {
            visiting.pop();
        }
    }

    /**
     * Which of UNLOCKED, STARTED and COMPLETED a chapter whose gate is met is in.
     *
     * <p>Separate from the gate because the two questions have nothing to do with each other: the gate
     * is about other chapters, and this is about the quests inside this one.
     */
    private static QuestState openState(QuestIndex index, Chapter chapter, TeamProgress progress) {
        List<QuestRef> milestones = chapter.rules().completesWhen();
        boolean anyProgress = false;

        for (QuestIndex.QuestEntry entry : index.questsIn(chapter.id())) {
            Quest quest = entry.quest();
            if (storedState(progress, quest).isAtLeast(QuestState.STARTED)) {
                anyProgress = true;
            }
        }

        if (!milestones.isEmpty()) {
            boolean allDone = true;
            for (QuestRef milestone : milestones) {
                Optional<QuestIndex.QuestEntry> found = index.quest(milestone.id());
                if (found.isEmpty()
                        || !ProgressionEngine.satisfiedForDependents(found.get().quest(), progress)) {
                    // A milestone that does not resolve cannot be completed, so the chapter never
                    // reports completed -- the same safe reading an unresolved dependency gets. The
                    // loader has already reported the name.
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                return QuestState.COMPLETED;
            }
        }
        return anyProgress ? QuestState.STARTED : QuestState.UNLOCKED;
    }

    /**
     * What one quest's <b>stored</b> progress says about it, without resolving anything.
     *
     * <p>The three answers are the same three the quest resolver reaches at the end of its own walk —
     * finished, touched, or nothing yet — and they are read here off the record rather than off the
     * resolution, which is what keeps the two passes from depending on each other.
     */
    private static QuestState storedState(TeamProgress progress, Quest quest) {
        if (ProgressionEngine.satisfiedForDependents(quest, progress)) {
            return QuestState.COMPLETED;
        }
        return progress.progressOf(quest).anyTaskProgress() ? QuestState.STARTED : QuestState.UNLOCKED;
    }

    /** Whether a chapter's state opens its quests. The one question the quest resolver asks. */
    public static boolean gatesQuests(QuestState chapterState) {
        return chapterState == QuestState.LOCKED;
    }
}
