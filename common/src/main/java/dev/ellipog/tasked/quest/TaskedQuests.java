package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;

import java.util.List;
import java.util.Optional;

/**
 * The loaded questline, and the one place that loads it.
 *
 * <h2>Which event, and why it matters</h2>
 *
 * <p>Loaded from {@code SERVER_STARTED}, not {@code SERVER_STARTING}. The two fire at genuinely
 * different points on the two loaders — on Fabric, {@code SERVER_STARTING} arrives before the game has
 * even logged starting the server, with no level in existence. Reading files there would work, but
 * anything that wanted the level would not, and the same code would then break on one loader only.
 *
 * <p>{@code SERVER_STARTED} is well-defined on both. Cheap to get right, and the alternative is a
 * bug that appears on one loader and not the other.
 *
 * <h2>Reload</h2>
 *
 * <p>{@link #reload} is what makes hand-editing bearable: change a file, run {@code /tasked reload},
 * see the tree and any problems in the log, without restarting. That loop is the reason the
 * line-numbered validator exists at all.
 */
public final class TaskedQuests {

    /** How many lines of quest tree to put in the log. A real pack has hundreds; a log nobody can scroll is unread. */
    private static final int TREE_LOG_BUDGET = 200;

    private static volatile QuestIndex index;

    private TaskedQuests() {
    }

    /** The current questline, or an empty index if nothing has loaded yet. Never null. */
    public static QuestIndex index() {
        QuestIndex current = index;
        return current != null ? current : QuestIndex.build(List.of(), new dev.ellipog.armature.api.data.Problems());
    }

    /**
     * Loads from disk and reports into the log.
     *
     * <p>Called on server start, and by {@code /tasked reload}.
     *
     * @return the load result, for a command to report back
     */
    public static QuestLoader.Result reload() {
        QuestLoader.Result result = QuestLoader.load(ArmatureApi.platform().configDir());
        index = result.index();

        report(result);
        return result;
    }

    /**
     * Writes the outcome to the log.
     *
     * <p>Every problem, at its own line, in the order they appear in the files — which is the whole
     * point of tracking positions. A load that produced errors says so and names the files, rather
     * than quietly loading a subset and leaving someone to wonder why their quests are missing.
     */
    private static void report(QuestLoader.Result result) {
        var problems = result.problems();

        if (!problems.isEmpty()) {
            Constants.LOG.warn("Tasked: {} problem(s) in the quest files:", problems.all().size());
            for (DataProblem problem : problems.all()) {
                Constants.LOG.warn("  {}", problem.render());
            }
        }

        // Three outcomes, and they are genuinely different. An earlier version had two, and reported
        // "loaded 2 file(s) cleanly" for a file with a circular dependency in it -- because it counted
        // files that had *decoded* and treated that as "had no problems". Decoding successfully and
        // being correct are not the same thing, and a log line that says otherwise sends someone
        // looking in the wrong place.
        if (result.filesFound() == 0) {
            Constants.LOG.info("Tasked: no quest files found");
        }
        else if (result.filesWithErrors() == 0) {
            Constants.LOG.info("Tasked: loaded {} file(s) cleanly", result.filesDecoded());
        }
        else {
            Constants.LOG.warn("Tasked: loaded {} of {} file(s); {} had errors - see above",
                    result.filesDecoded(), result.filesFound(), result.filesWithErrors());
        }

        QuestLoader.logTree(result.index(), TREE_LOG_BUDGET);
    }

    /** Loads once, from the server-started hook. Logs, and never throws. */
    public static void loadOnServerStart() {
        try {
            reload();
        }
        catch (RuntimeException e) {
            // A broken quest file must not stop a server from starting. The world matters more than
            // the questline, and an author with a half-written file should still be able to play.
            Constants.LOG.error("Tasked: the quest files could not be loaded at all", e);
        }
    }

    /**
     * One line describing what is loaded, for the boot log.
     *
     * <p>Counted in groups, chapters and quests, and it used to end "from N file(s)". That term has no
     * meaning in the folder layout — a group is a folder holding a manifest, and a chapter is a folder
     * inside it — so the number it reported would have been a file count that no longer corresponded
     * to anything a reader could point at. The loader's own result still reports files found and
     * decoded, which is where that question belongs.
     */
    public static String summary() {
        QuestIndex current = index();
        return current.questCount() + " quest(s) in " + current.chapterCount() + " chapter(s), "
                + current.groupCount() + " chapter group(s)";
    }

    /** A quest by id or alias, if it is loaded. */
    public static Optional<QuestIndex.QuestEntry> find(String idOrAlias) {
        return index().quest(idOrAlias);
    }
}
