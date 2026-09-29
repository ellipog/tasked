package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.ProgressionEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Which files are quests, what each one declares, and what each one names underneath it — all
        // decided by QuestFiles, which is the one description of the folder rules. This method's job from
        // here is to decode what was found and assemble it, and it holds no opinion about where anything
        // sits on disk.
        QuestFiles.Discovery found = QuestFiles.discover(directory);
        problems.addAll(found.problems().all());

        QuestTree tree = assemble(found.declarations(), problems);
        QuestIndex index = QuestIndex.assemble(tree, problems);

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

        // Files understood: every declaration whose own file came through with no error against it.
        //
        // Counted from the problems rather than tracked alongside the loop, for the reason above -- a
        // check added later is counted automatically instead of being forgotten. And it is not the same
        // number as "declarations that were decoded", because a declaration can decode and still be
        // reported: a duplicate id, a dangling dependency and a cycle are all found after decoding, and
        // all of them mean the file has a problem an author has to fix.
        int filesDecoded = (int) found.declarations().stream()
                .map(QuestFiles.Declaration::display)
                .distinct()
                .filter(display -> !problems.hasErrorsIn(display))
                .count();

        return new Result(index, problems, found.filesExamined(), filesDecoded, filesWithErrors);
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Decodes every declaration discovery found, and assembles them into one tree.
     *
     * <h2>Two phases, because a group cannot be built before its chapters are</h2>
     *
     * <p>A {@link ChapterGroup} holds its {@code Chapter}s, and a {@link Chapter} holds its
     * {@code Quest}s — so a group's piece cannot be emitted until everything underneath it has been
     * decoded, while the declarations arrive group-first. Phase one walks the declarations in order and
     * nests each one under the group or chapter it belongs to; phase two builds the records bottom-up
     * and emits the pieces.
     *
     * <p>Nesting by the {@code parentDisplay} each declaration already carries, rather than by matching
     * order, is what makes a chapter whose manifest failed to decode take only <i>its own</i> quests down
     * with it. The quests are still under it in the walk — they hang off the chapter's manifest — so they
     * are skipped with it, which is right: a quest is not loaded if the chapter it belongs to is not.
     *
     * <h2>{@code $schema} and the per-kind validators</h2>
     *
     * <p>Each kind is validated by <i>its own</i> entry point, so the field sets are per-kind — a chapter
     * field in a group's manifest is an error — and the positions are the file's own, because the path
     * for a per-kind document is {@code $}. Validation first, decode second, exactly as for version 1
     * and for the same reason: one mistake should produce one message, not a validator complaint
     * followed by a codec complaint about the same field.
     */
    private static QuestTree assemble(List<QuestFiles.Declaration> declarations, Problems problems) {
        List<GroupBuild> groups = new ArrayList<>();
        GroupBuild currentGroup = null;
        ChapterBuild currentChapter = null;

        // Version-1 pieces, built as they are met, and merged back in declaration order below so that a
        // flat file sitting between two group folders keeps its place in the book.
        Map<String, List<QuestTree.Piece>> flatPieces = new LinkedHashMap<>();

        for (QuestFiles.Declaration declaration : declarations) {
            switch (declaration.kind()) {
                case FLAT_V1 -> {
                    // A whole version-1 tree in one document. Its paths are the nested ones, and
                    // QuestTree.of is the only place they are written.
                    QuestValidator.validate(declaration.document(), problems);
                    if (problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    decode(QuestFile.CODEC, declaration.document(), declaration.display(), problems)
                            .ifPresent(file -> flatPieces.put(declaration.display(),
                                    QuestTree.of(List.of(new LoadedQuestFile(declaration.path(),
                                            declaration.display(), declaration.document(), file))).pieces()));
                    currentGroup = null;
                    currentChapter = null;
                }

                case GROUP -> {
                    QuestValidator.validateGroupDocument(declaration.document(), problems);
                    currentChapter = null;
                    if (problems.hasErrorsIn(declaration.display())) {
                        currentGroup = null;
                        continue;
                    }
                    currentGroup = decode(GroupManifest.CODEC, declaration.document(),
                            declaration.display(), problems)
                            .map(manifest -> new GroupBuild(declaration, manifest))
                            .orElse(null);
                    if (currentGroup != null) {
                        groups.add(currentGroup);
                    }
                }

                case CHAPTER -> {
                    QuestValidator.validateChapterDocument(declaration.document(), problems);
                    currentChapter = null;
                    if (currentGroup == null || problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    currentChapter = decode(ChapterManifest.CODEC, declaration.document(),
                            declaration.display(), problems)
                            .map(manifest -> new ChapterBuild(declaration, manifest))
                            .orElse(null);
                    if (currentChapter != null) {
                        currentGroup.chapters.add(currentChapter);
                    }
                }

                case QUEST -> {
                    QuestValidator.validateQuestDocument(declaration.document(), problems);
                    if (currentChapter == null || problems.hasErrorsIn(declaration.display())) {
                        continue;
                    }
                    // Copied to a final local because the lambda below is deferred, while
                    // `currentChapter` is reassigned by the next CHAPTER declaration. The compiler
                    // enforces this, and it is worth knowing what it is enforcing: a captured
                    // reference would attach the quest to whichever chapter the loop had reached by
                    // the time the lambda ran, which is a bug that only shows up if decoding becomes
                    // lazy. It is not lazy today, so this is the compiler closing a hazard that would
                    // otherwise be latent rather than live.
                    final ChapterBuild owning = currentChapter;
                    decode(Quest.CODEC, declaration.document(), declaration.display(), problems)
                            .ifPresent(quest -> owning.quests.add(new QuestBuild(declaration, quest)));
                }
            }
        }

        // Bottom-up: each chapter is built once, so the same object serves the chapter's piece, the
        // group's chapter list and every quest's back-pointer. Building it twice would give two chapters
        // that are equal but not identical -- and `Chapter.indexOf` compares by reference, so LINEAR
        // progression would stop finding a quest's position and silently gate on nothing.
        for (GroupBuild group : groups) {
            for (ChapterBuild chapter : group.chapters) {
                chapter.assembled = chapter.manifest.toChapter(
                        chapter.quests.stream().map(quest -> quest.quest).toList());
            }
        }

        // A map from the file a declaration was read from to the piece it became, so the merge below
        // can replay the declarations in their original order.
        Map<String, QuestTree.Piece> pieceByDisplay = new LinkedHashMap<>();
        for (GroupBuild group : groups) {
            for (ChapterBuild chapter : group.chapters) {
                for (int q = 0; q < chapter.quests.size(); q++) {
                    QuestBuild quest = chapter.quests.get(q);
                    pieceByDisplay.put(quest.declaration.display(), new QuestTree.Piece.QuestPiece(
                            group.manifest.id(), chapter.assembled, quest.quest, q, quest.source()));
                }
                pieceByDisplay.put(chapter.declaration.display(), new QuestTree.Piece.ChapterPiece(
                        group.manifest.id(), chapter.assembled, chapter.source()));
            }
            pieceByDisplay.put(group.declaration.display(), new QuestTree.Piece.GroupPiece(
                    group.manifest.toGroup(group.chapters.stream().map(c -> c.assembled).toList()),
                    group.source()));
        }

        // Declaration order, so a version-1 file keeps its place among the group folders beside it.
        List<QuestTree.Piece> pieces = new ArrayList<>();
        for (QuestFiles.Declaration declaration : declarations) {
            List<QuestTree.Piece> flat = flatPieces.get(declaration.display());
            if (flat != null) {
                pieces.addAll(flat);
                continue;
            }
            QuestTree.Piece piece = pieceByDisplay.get(declaration.display());
            if (piece != null) {
                pieces.add(piece);
            }
        }

        return new QuestTree(List.copyOf(pieces));
    }

    /** A group manifest that decoded, and the chapters under it. Mid-assembly, so mutable. */
    private static final class GroupBuild {

        private final QuestFiles.Declaration declaration;
        private final GroupManifest manifest;
        private final List<ChapterBuild> chapters = new ArrayList<>();

        private GroupBuild(QuestFiles.Declaration declaration, GroupManifest manifest) {
            this.declaration = declaration;
            this.manifest = manifest;
        }

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
        }
    }

    /** A chapter manifest that decoded, and the quests under it. {@code assembled} is filled in later. */
    private static final class ChapterBuild {

        private final QuestFiles.Declaration declaration;
        private final ChapterManifest manifest;
        private final List<QuestBuild> quests = new ArrayList<>();
        private Chapter assembled;

        private ChapterBuild(QuestFiles.Declaration declaration, ChapterManifest manifest) {
            this.declaration = declaration;
            this.manifest = manifest;
        }

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
        }
    }

    /** One quest file that decoded. */
    private record QuestBuild(QuestFiles.Declaration declaration, Quest quest) {

        private QuestTree.Source source() {
            return new QuestTree.Source(declaration.display(), declaration.document(), "$");
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
    private static <T> Optional<T> decode(Codec<T> codec, JsonDocument document, String display,
                                          Problems problems) {
        DataResult<T> result = codec.parse(JsonOps.INSTANCE, document.root());

        Optional<T> decoded = result.result();
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
     *
     * <h2>The per-file line is gone, and its absence is deliberate</h2>
     *
     * <p>This used to print each file's name as a heading and then that file's groups underneath. That
     * is the right shape for version 1, where a file <i>is</i> a tree, and the wrong shape for the
     * folder layout, where a group is not in a file at all — it is in a folder, beside the chapter
     * folders it names. So the heading was a line that would have had to say "the folder called
     * getting_started" and be followed by groups belonging to nobody in particular.
     *
     * <p>The tree is printed as a tree, from the index, and the index no longer remembers which file a
     * declaration came from — every message about one names its own file, which is where that
     * information is wanted. What the log is for is confirming what loaded, and this still does that
     * with one less level of indentation.
     */
    public static void logTree(QuestIndex index, int maxLines) {
        if (index.isEmpty()) {
            Constants.LOG.info("Tasked: no quests loaded");
            return;
        }

        StringBuilder out = new StringBuilder();
        int lines = 0;
        boolean truncated = false;

        for (QuestIndex.GroupEntry groupEntry : index.groups()) {
            ChapterGroup group = groupEntry.group();
            if (lines++ >= maxLines) {
                truncated = true;
                break;
            }
            out.append("\n  ").append(group.title().value());
            for (Chapter chapter : group.chapters()) {
                if (lines++ >= maxLines) {
                    truncated = true;
                    break;
                }
                out.append("\n    ").append(chapter.title().value())
                        .append("  [").append(chapter.id()).append(']');
                for (Quest quest : chapter.quests()) {
                    if (lines++ >= maxLines) {
                        truncated = true;
                        break;
                    }
                    out.append("\n      ").append(quest.title().value())
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

        if (truncated) {
            out.append("\n    … more quests omitted from this log line budget");
        }
        Constants.LOG.info("Tasked: quest tree ({} quests in {} chapters){}",
                index.questCount(), index.chapterCount(), out);
    }
}
