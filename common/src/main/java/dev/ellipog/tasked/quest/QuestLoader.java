package dev.ellipog.tasked.quest;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.ProgressionEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads {@code config/tasked/quests/} and turns it into a {@link QuestIndex}.
 *
 * <h2>The pipeline, and why in that order</h2>
 *
 * <ol>
 *   <li><b>Parse</b> with {@link JsonDocument}, so every value has a line and a column.</li>
 *   <li><b>Validate</b> against the format — required fields, unknown fields, enum names, item
 *       existence. If this finds anything fatal, the file is <b>not decoded</b>.</li>
 *   <li><b>Decode</b> with the codecs, which is the only step that produces a {@link QuestFile}.</li>
 *   <li><b>Index</b> across all files, which is where duplicate ids and dangling dependencies are
 *       caught.</li>
 * </ol>
 *
 * <p>Step 2 before step 3 is the load-bearing bit. A codec ignores fields it does not recognise and
 * reports failures without positions, so decoding first would mean an author sees one message per
 * reload, in the wrong place. Validating first means one pass reports everything, each at its own
 * line.
 *
 * <p>Files that fail are skipped rather than aborting the load. A questline of ten files with one
 * broken should still load the other nine — that is the difference between a typo being a nuisance
 * and it being a dead server.
 *
 * <h2>It reads, and only reads</h2>
 *
 * <p>Nothing here creates, writes, renames or deletes anything — not even the quest directory itself.
 * That is a rule, and it was arrived at by getting it wrong twice.
 *
 * <p>The first version copied a questline out of the jar whenever the directory was <i>absent</i>. So
 * a shipped file could only ever reach an install at the moment that install was created, and adding
 * a second one to the jar reached nobody: every existing install already had the directory. The
 * second version fixed that by recording which files had been offered and offering the rest — a
 * bigger machine for a smaller problem, and still a mod writing content into a directory that belongs
 * to whoever is playing.
 *
 * <p>The rule that replaced both: <b>the mod ships no quests.</b> A quest file belongs to the person
 * who wrote it, and a loader that also writes has an opinion about content it has no business having
 * an opinion about — a player who installs Tasked wants a quest engine, not three example chapters
 * to delete, and every file a mod ships is a file that has to keep working forever against a format
 * that is still moving.
 *
 * <p>So the worked examples live in the repository, at {@code tasked/tools/quests}, and reach a
 * config directory because somebody ran {@code tasked/tools/seed_quests.py}. That script's header
 * says why it is there rather than here; the short version is that deleting a file you wrote is a
 * different act from discovering one you did not.
 *
 * <p>Two consequences are visible from outside, and both are intended:
 *
 * <ul>
 *   <li><b>An absent directory is reported, not created.</b> The warning names the full path, which
 *       is the useful half of what the old code did, without the write.</li>
 *   <li><b>A fresh install has no quests.</b> An empty book is the designed state rather than a
 *       failure, and it is what a pack author starts from.</li>
 * </ul>
 */
public final class QuestLoader {

    /** Relative to the config directory. */
    public static final String DIRECTORY = "tasked/quests";

    // There is deliberately no list of shipped quest files here.
    //
    // There used to be: a `SHIPPED_FILES` constant naming three files in the jar's resources, which
    // the loader copied into the config directory, plus a `.seeded` marker recording which of them
    // had been offered so a deleted one would stay deleted. The class comment explains why both are
    // gone. The short version is that the mod should not be the delivery mechanism for content it
    // does not own, so the examples moved to `tasked/tools/quests` and out of the jar entirely.
    //
    // The tests followed them there. That is a small improvement in its own right: the file under
    // test is now the file an author is pointed at, rather than a copy that travels through the
    // build's resource processing and could differ from it.

    /** Files whose name starts with this are ignored, so an author can keep notes beside their work. */
    private static final String IGNORED_PREFIX = "_";

    private QuestLoader() {
    }

    /** What a load produced. */
    public record Result(QuestIndex index, Problems problems, int filesFound, int filesDecoded, int filesWithErrors) {

        /**
         * Whether every file that matched was usable.
         *
         * <p>Counts files with <b>errors</b>, not files that failed to decode. Those are different
         * things, and conflating them produced a log line that said "loaded 2 files cleanly" when one
         * of them had a circular dependency — see {@link #filesWithErrors}.
         */
        public boolean ok() {
            return filesWithErrors == 0;
        }
    }

    /**
     * Reads every quest file under {@code <configDir>/tasked/quests}.
     *
     * <p>A directory that is not there is reported and <b>not</b> created, which is the rule stated
     * on the class. An absent or empty directory is a normal state rather than a failure: Tasked
     * ships no quests, so this is what a fresh install looks like, and the warning names the full
     * path so the fix is obvious from the message alone.
     */
    public static Result load(Path configDir) {
        Path directory = configDir.resolve(DIRECTORY);
        Problems problems = new Problems();

        if (!Files.isDirectory(directory)) {
            problems.add(DIRECTORY, new JsonLocation(1, 1, "$"), DataProblem.Severity.WARNING,
                    "no quest directory at " + directory + ", so there are no quests to load. Tasked"
                            + " ships no quests of its own; this directory is where they go.");
            return new Result(QuestIndex.build(List.of(), problems), problems, 0, 0, 0);
        }

        List<Path> candidates = findQuestFiles(directory);
        List<LoadedQuestFile> loaded = new ArrayList<>();

        for (Path path : candidates) {
            String display = directory.relativize(path).toString().replace('\\', '/');

            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                problems.add(display, new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                        "could not be read: " + e.getMessage());
                continue;
            }

            JsonDocument document;
            try {
                document = JsonDocument.parse(display, text);
            }
            catch (JsonParseException e) {
                // The whole text of the message, which already includes the offending line and a caret.
                problems.add(display, e.location(), DataProblem.Severity.ERROR, e.getMessage());
                continue;
            }

            QuestValidator.validate(document, problems);

            if (problems.hasErrorsIn(display)) {
                // Do not decode. One error, one message — rather than a validator complaint followed
                // by a codec complaint about the same field.
                continue;
            }

            Optional<QuestFile> file = decode(document, display, problems);
            file.ifPresent(value -> loaded.add(new LoadedQuestFile(path, display, document, value)));
        }

        QuestIndex index = QuestIndex.build(loaded, problems);

        // Cycle detection, after the index is built because a cycle can run across files -- a
        // dependency in one file pointing at a quest in another, which points back. Checked here
        // rather than in QuestIndex.build so that the index stays a lookup structure and this
        // stays a diagnostic pass over it.
        //
        // A cycle is the worst failure a questline can have and the quietest: the quest simply never
        // unlocks, nothing is logged, and the author has no reason to suspect the two quests they
        // wired to each other by mistake. So it is an error, with the whole loop printed.
        for (List<String> cycle : ProgressionEngine.findCycles(index)) {
            String chain = String.join(" -> ", cycle);
            // Reported against the first quest in the loop, which is where the file and line come
            // from. The chain names the rest, so the message is actionable as written.
            index.quest(cycle.get(0)).ifPresent(entry -> problems.error(entry.document(), entry.path(),
                    "circular dependency: " + chain
                            + "\n    no quest in this loop can ever be unlocked. Break it by removing one dependsOn."));
        }

        // How many files have at least one error against them, whatever stage found it. Counted from
        // the problems rather than tracked alongside, so a check added later is counted automatically
        // instead of being forgotten.
        int filesWithErrors = (int) problems.all().stream()
                .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                .map(DataProblem::file)
                .distinct()
                .count();

        return new Result(index, problems, candidates.size(), loaded.size(), filesWithErrors);
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Every JSON file under the quest directory, sorted by name.
     *
     * <p>Sorted so the load order — and therefore the order quests appear in {@code /tasked quests}
     * and in the log — is stable between runs. File-system order is not, and a list that shuffles
     * itself is maddening to read.
     */
    private static List<Path> findQuestFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith(IGNORED_PREFIX))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        catch (IOException e) {
            Constants.LOG.error("Could not list {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Runs the codecs over a file that has already passed validation.
     *
     * <p>Reaching an error here means the codec rejects something the validator allowed — a range
     * with no matching check, or a cross-field rule. So the position is best-effort: DFU's message
     * names the field it was unhappy about, and where it does, this looks that key up in the document
     * to place the caret on it. Where it does not, the problem is reported at the root of the file,
     * which is honest rather than precise.
     *
     * <p>The validator is the thing that produces good positions; this is the backstop for the cases
     * it does not cover.
     */
    private static Optional<QuestFile> decode(JsonDocument document, String display, Problems problems) {
        DataResult<QuestFile> result = QuestFile.CODEC.parse(JsonOps.INSTANCE, document.root());

        Optional<QuestFile> decoded = result.result();
        if (decoded.isPresent()) {
            return decoded;
        }

        String message = result.error().map(DataResult.Error::message).orElse("the codec rejected this file");
        String path = guessPath(document, message);
        problems.add(display, document.nearestLocation(path), DataProblem.Severity.ERROR,
                "this file is well-formed JSON but the format rejected it:\n    " + message.replace("\n", "\n    ")
                        + "\n    (this is a gap in the validator - please report it)");
        return Optional.empty();
    }

    /** DFU says {@code No key x in ...} and {@code ... field "x" ...}; either is worth trying. */
    private static final Pattern KEY_PATTERN = Pattern.compile("No key (\\S+) in|field \"([^\"]+)\"");

    private static String guessPath(JsonDocument document, String message) {
        Matcher matcher = KEY_PATTERN.matcher(message);
        while (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            for (String candidate : List.of("$." + key, "$.chapterGroups[0]." + key,
                    "$.chapterGroups[0].chapters[0]." + key)) {
                if (document.has(candidate)) {
                    return candidate;
                }
            }
        }
        return "$";
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    /**
     * Writes the tree to the log, with task and reward counts per quest.
     *
     * <p>This is the "done when" of the stage: a hand-written questline logs its tree. It is also how
     * an author confirms what actually loaded, which is otherwise invisible — the quest screen does not
     * exist yet, and a quest that failed to appear gives no clue as to why.
     *
     * <p>Capped, because a real pack has hundreds of quests and a log nobody can scroll is a log
     * nobody reads.
     */
    public static void logTree(QuestIndex index, int maxLines) {
        if (index.isEmpty()) {
            Constants.LOG.info("Tasked: no quests loaded");
            return;
        }

        StringBuilder out = new StringBuilder();
        int lines = 0;
        boolean truncated = false;

        for (LoadedQuestFile loaded : index.files()) {
            out.append("\n  ").append(loaded.displayName());
            lines++;
            for (ChapterGroup group : loaded.file().chapterGroups()) {
                if (lines++ >= maxLines) {
                    truncated = true;
                    break;
                }
                out.append("\n    ").append(group.title().value());
                for (Chapter chapter : group.chapters()) {
                    if (lines++ >= maxLines) {
                        truncated = true;
                        break;
                    }
                    out.append("\n      ").append(chapter.title().value())
                            .append("  [").append(chapter.id()).append(']');
                    for (Quest quest : chapter.quests()) {
                        if (lines++ >= maxLines) {
                            truncated = true;
                            break;
                        }
                        out.append("\n        ").append(quest.title().value())
                                .append("  [").append(quest.id()).append(']')
                                .append("  ").append(quest.tasks().size()).append(" task(s)")
                                .append(", ").append(quest.rewards().size()).append(" reward(s)");
                        if (!quest.dependencies().isEmpty()) {
                            out.append(", after ")
                                    .append(quest.dependencies().stream().map(QuestRef::id).toList());
                        }
                    }
                }
            }
        }

        if (truncated) {
            out.append("\n    … more quests omitted from this log line budget");
        }
        Constants.LOG.info("Tasked: quest tree ({} quests in {} chapters){}",
                index.questCount(), index.chapterCount(), out);
    }
}
