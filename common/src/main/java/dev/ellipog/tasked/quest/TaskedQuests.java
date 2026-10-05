package dev.ellipog.tasked.quest;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tasked.editor.QuestEditor;
import dev.ellipog.tasked.editor.ServerEditors;
import dev.ellipog.tasked.editor.ServerTables;
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

    /**
     * The tree's own settings, from {@code index.json}.
     *
     * <p>Here beside the index rather than on it because they are a different thing: the index is the
     * chapters and quests the server serves, and this is how the book as a whole behaves. A reload
     * replaces both, and they must come from the same read of the same file.
     */
    private static volatile QuestSettings settings = QuestSettings.DEFAULTS;

    /**
     * The reward tables, by id, from {@code reward_tables/*.json}.
     *
     * <p>Replaced wholesale by a reload, like the index and the settings — a table that is renamed or
     * removed stops existing at the same moment the reference to it does.
     */
    private static volatile java.util.Map<String, dev.ellipog.tasked.quest.loot.RewardTable> rewardTables =
            java.util.Map.of();

    /**
     * The table files that did not load, by id, each with its reason — the other half of the folder.
     *
     * <p>Held rather than derived because there is nothing to derive it from: a refused file has no
     * {@code RewardTable} to look at, and the problems that explain it are thrown away with the load's
     * result. The client's Assets panel is what reads this, so that a broken table is a row an author can
     * see rather than a line in a log they have to go and find.
     */
    private static volatile java.util.Map<String, String> refusedTables = java.util.Map.of();
    /**
     * The chapters this server has open for editing.
     *
     * <p>Here rather than in the payload handler because this class owns the quest directory: the editors and
     * the index must be reading the same files, and two places resolving that path is two places to get it
     * wrong. Dropped by {@code /tasked reload} and nothing else — see {@link ServerEditors#forget}.
     */
    private static final ServerEditors EDITORS =
            new ServerEditors(() -> QuestEditor.root(ArmatureApi.platform().configDir()));

    /**
     * The reward tables this server has open for editing.
     *
     * <p>Beside {@link #EDITORS} and for the same reason: it owns drafts over the same folder, so the
     * two must be reading the same files. It reads the loaded tables and the index through suppliers
     * rather than reaching for them, so a test can hand it its own — see {@link ServerTables}.
     */
    private static final ServerTables TABLES = new ServerTables(
            EDITORS,
            () -> QuestEditor.root(ArmatureApi.platform().configDir()),
            TaskedQuests::rewardTables,
            TaskedQuests::index);

    private TaskedQuests() {
    }

    /** The chapters open for editing on this server. */
    public static ServerEditors editors() {
        return EDITORS;
    }

    /** The reward tables open for editing on this server. */
    public static ServerTables tables() {
        return TABLES;
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
        settings = QuestSettings.load(QuestEditor.root(ArmatureApi.platform().configDir()));
        rewardTables = result.rewardTables();
        refusedTables = refusals(result.problems(), result.refusedTables());

        report(result);
        return result;
    }

    /**
     * Re-reads the reward tables alone, keeping the index and the settings.
     *
     * <p>What a table edit needs: the quests did not move, and {@link #reload()} would re-read and
     * re-validate all of them — plus rebuild the index — to learn that.
     *
     * @return the problems the table folder reported, for the caller to log
     */
    public static dev.ellipog.armature.api.data.Problems reloadTables() {
        dev.ellipog.armature.api.data.Problems problems =
                new dev.ellipog.armature.api.data.Problems();
        QuestLoader.Tables tables = QuestLoader.loadTables(ArmatureApi.platform().configDir(), problems);
        rewardTables = tables.loaded();
        refusedTables = refusals(problems, tables.refused());
        if (!problems.all().isEmpty()) {
            Constants.LOG.warn("tasked: {} problem(s) in the reward tables:", problems.all().size());
            for (var problem : problems.all()) {
                Constants.LOG.warn("tasked:   {}", problem.render());
            }
        }
        return problems;
    }

    /**
     * The refused table files, each with the first thing wrong with it.
     *
     * <p>The loader names the ids; the reason is the error already reported against {@code <id>.json},
     * taken from the same {@code Problems} the load filled — so the sentence the Assets panel shows an
     * author is the sentence the log and the reload report show, and there is one description of what is
     * wrong with a file rather than three that drift.
     *
     * <p>Keyed and ordered by id, so the panel's list does not reshuffle between reloads.
     */
    private static java.util.Map<String, String> refusals(
            dev.ellipog.armature.api.data.Problems problems, java.util.Set<String> refused) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (String id : refused) {
            out.put(id, problems.forFile(id + ".json").stream()
                    .filter(problem ->
                            problem.severity() == dev.ellipog.armature.api.data.DataProblem.Severity.ERROR)
                    .map(dev.ellipog.armature.api.data.DataProblem::message)
                    .findFirst()
                    // A refused file with no error against it is a contradiction the loader cannot
                    // currently produce; saying so beats an empty row if that ever changes.
                    .orElse("this file did not load, and the load reported no reason for it"));
        }
        return java.util.Map.copyOf(out);
    }

    /** The tree's own settings. Never null; the defaults until something declares otherwise. */
    public static QuestSettings settings() {
        return settings;
    }

    /** The reward tables, by id. Never null; empty until a tree declares any. */
    public static java.util.Map<String, dev.ellipog.tasked.quest.loot.RewardTable> rewardTables() {
        return rewardTables;
    }

    /**
     * The table files that are there and did not load, by id, each with its reason. Never null.
     *
     * <p>A subset of the folder rather than of {@link #rewardTables()}: the two are disjoint by
     * construction, because a file that refuses is one that never became a table.
     */
    public static java.util.Map<String, String> refusedTables() {
        return refusedTables;
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
