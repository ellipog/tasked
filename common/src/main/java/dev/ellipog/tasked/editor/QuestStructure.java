package dev.ellipog.tasked.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.quest.ChapterNaming;
import dev.ellipog.tasked.quest.QuestFiles;
import dev.ellipog.tasked.quest.QuestSettings;
import dev.ellipog.tasked.quest.QuestValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The structural edits: chapters and groups moved, created, renamed, duplicated and deleted.
 *
 * <h2>Why this is not {@link QuestEditor}</h2>
 *
 * <p>Because a {@code QuestEditor} is open on <i>one chapter</i> — its manifest, its group's manifest,
 * its quests — and every structural edit is about the shape <b>above</b> that: which group a chapter
 * hangs under, what order the groups are in, and what exists at all. A model scoped to one chapter can
 * only reach those by accident, and the first thing it would do is write a folder it has no business
 * naming.
 *
 * <h2>Edits are reversible steps, not a diff</h2>
 *
 * <p>Each edit produces a {@link Structure}: the steps that performed it, and the steps that undo it.
 * That is what lets structural changes join the same Ctrl+Z as a field edit — the acting
 * {@link QuestEditor} records the structure on its own history, and undo runs the reverse steps. A diff
 * against the disk would have to be recomputed after every write and would lose on the first case it
 * did not think of; an explicit reverse cannot be wrong about what the edit was.
 *
 * <p><b>Validation before any write.</b> Every manifest this class is about to write is parsed and put
 * through {@link QuestValidator} first, exactly as {@code QuestEditor.save} does, to the same end: an
 * edit that would produce a file the loader refuses is a refusal, not a write. Because the steps are
 * executed only after validation, there is no half-applied edit to unwind.
 *
 * <h2>The index manifest is written when it is needed, and never before</h2>
 *
 * <p>A tree that has never been structurally edited has no {@code index.json}, and nothing this class
 * does may change how it loads. So every edit that needs the index <b>bootstraps</b> it from what is on
 * disk — the group folders in folder-name order, the version-1 files beside them — and writes it as
 * part of the edit. An edit that only reorders a group's own chapters does not touch the index at all.
 */
public final class QuestStructure {

    /** The relative path a chapter manifest's {@code $schema} takes, grouped and loose. */
    private static final String CHAPTER_SCHEMA_GROUPED = "../../_schema/chapter.schema.json";
    private static final String CHAPTER_SCHEMA_ROOT = "../_schema/chapter.schema.json";
    private static final String GROUP_SCHEMA = "../_schema/group.schema.json";
    private static final String INDEX_SCHEMA = "./_schema/index.schema.json";

    private QuestStructure() {
    }

    // ------------------------------------------------------------------
    // Steps
    // ------------------------------------------------------------------

    /** One reversible filesystem step. */
    public sealed interface Step {

        /** A file or folder moved from one path to another. */
        record Move(Path from, Path to) implements Step {
        }

        /** A file written with exactly this text, parents created. */
        record Write(Path path, String content) implements Step {
        }

        /** A file or folder put aside as {@code <name>.deleted}, recoverably. */
        record SetAside(Path path) implements Step {
        }
    }

    /**
     * An edit: the steps that performed it, and the steps that undo it.
     *
     * <p>Both directions are stored rather than one being derived, because the reverse of "move here" is
     * not "move back" in general — a create is undone by setting aside, a delete by moving back — and a
     * derivation would have to know which case it was looking at anyway.
     */
    public record Structure(List<Step> forward, List<Step> reverse, Meta forwardMeta, Meta reverseMeta) {

        /**
         * What one direction of an edit asks of the caller.
         *
         * <p>Per direction, because the two differ: an undo of a rename re-keys the cache to the
         * <i>old</i> id and an undo of a delete learns that the chapter came back, while the forward
         * direction knows the opposite. Passing one set for both is how Ctrl+Z after a rename would
         * leave the cache holding an editor for a folder that no longer exists.
         *
         * @param chapterId the chapter the client should look at, or null
         * @param groupId   the group it is in, or null
         * @param forget    chapters whose cached editors are stale, because their folder moved
         */
        public record Meta(String chapterId, String groupId, List<String> forget) {
        }
    }

    /**
     * What a structural edit did, or why it did not.
     *
     * @param structure the edit, or null when it was refused
     * @param refusal   the sentence to show, or null when it happened
     */
    public record Outcome(Structure structure, String refusal) {

        public boolean ok() {
            return refusal == null;
        }

        static Outcome refused(String message) {
            return new Outcome(null, message);
        }

        static Outcome done(Structure structure) {
            return new Outcome(structure, null);
        }
    }

    // ------------------------------------------------------------------
    // The edits
    // ------------------------------------------------------------------

    /** Moves one chapter to another group — or to none — at a position in that group's list. */
    public static Outcome moveChapter(Path root, String chapter, String toGroup, int index) {
        Optional<Path> found = chapterFolder(root, chapter);
        if (found.isEmpty()) {
            return Outcome.refused("no chapter called \"" + chapter + "\"");
        }
        Path source = found.get();
        String fromGroup = groupIdOf(root, source);
        String target = toGroup == null ? "" : toGroup;
        if (fromGroup.equals(target)) {
            // A move within one group is an order change inside its chapters array, which is what the
            // row drag inside a group already sends. Doing it here as well keeps the menu's "move to
            // this group" honest when the answer is "the group it is already in".
            return reorderChapter(root, chapter, target, index);
        }
        Optional<Path> destination = target.isEmpty()
                ? Optional.of(root.resolve(chapter))
                : groupFolder(root, target).map(folder -> folder.resolve(chapter));
        if (destination.isEmpty()) {
            return Outcome.refused("no group called \"" + target + "\"");
        }
        if (Files.exists(destination.get())) {
            return Outcome.refused("there is already a chapter called \"" + chapter + "\" in "
                    + (target.isEmpty() ? "no group" : "\"" + target + "\""));
        }

        Edit edit = new Edit();
        edit.forget(chapter);
        edit.move(source, destination.get());
        if (!fromGroup.isEmpty()) {
            edit.write(updateGroupChapters(root, fromGroup, names -> names.remove(chapter)));
        }
        else {
            // Out of the root, where the index was the thing listing it. Every other source is a group
            // manifest; this one is not, and leaving the entry behind names a folder that has moved --
            // which the loader refuses on the next reload, so the drop reads as having done nothing.
            edit.writeIndex(root, entries -> removeEntry(entries, "chapter", chapter));
        }
        if (!target.isEmpty()) {
            edit.write(updateGroupChapters(root, target, names -> insert(names, chapter, index)));
        }
        else {
            edit.writeIndex(root, entries -> insertEntry(entries, "chapter", chapter, index));
        }
        return edit.finish(chapter, target, chapter, fromGroup);
    }

    /** Moves one chapter within its own group's list. */
    private static Outcome reorderChapter(Path root, String chapter, String group, int index) {
        if (group.isEmpty()) {
            Edit edit = new Edit();
            edit.writeIndex(root, entries -> moveEntry(entries, "chapter", chapter, index));
            return edit.finish(chapter, "", chapter, "");
        }
        List<String> current = groupChapterNames(root, group);
        int from = current.indexOf(chapter);
        if (from < 0) {
            return Outcome.refused("the group's list does not name \"" + chapter + "\"");
        }
        Edit edit = new Edit();
        edit.write(updateGroupChapters(root, group, names -> {
            names.remove(chapter);
            insert(names, chapter, index);
        }));
        return edit.finish(chapter, group, chapter, group);
    }

    /** Moves one group to a position among the root entries. */
    public static Outcome moveGroup(Path root, String group, int index) {
        if (groupFolder(root, group).isEmpty()) {
            return Outcome.refused("no group called \"" + group + "\"");
        }
        Edit edit = new Edit();
        edit.writeIndex(root, entries -> moveEntry(entries, "group", group, index));
        return edit.finish(null, null, null, null);
    }

    /** Creates a chapter, in a group or at the root, empty. */
    public static Outcome createChapter(Path root, String group, int index, String id, String title) {
        String problem = idProblem(root, "chapter", id);
        if (problem != null) {
            return Outcome.refused(problem);
        }
        String target = group == null ? "" : group;
        Path folder;
        if (target.isEmpty()) {
            folder = root.resolve(id);
        }
        else {
            Optional<Path> parent = groupFolder(root, target);
            if (parent.isEmpty()) {
                return Outcome.refused("no group called \"" + target + "\"");
            }
            folder = parent.get().resolve(id);
        }
        if (Files.exists(folder)) {
            return Outcome.refused("\"" + id + "\" is already a folder in that place");
        }

        Edit edit = new Edit();
        edit.creates(folder);
        edit.writeText(folder.resolve(QuestFiles.CHAPTER_MANIFEST), chapterJson(
                id, title, target.isEmpty() ? CHAPTER_SCHEMA_ROOT : CHAPTER_SCHEMA_GROUPED, List.of()));
        // No editor can exist for a chapter that is about to be made, but an undo of the create puts it
        // aside — and by then one may have been opened, so the name is dropped in both directions.
        edit.forget(id);
        if (target.isEmpty()) {
            edit.writeIndex(root, entries -> insertEntry(entries, "chapter", id, index));
        }
        else {
            edit.write(updateGroupChapters(root, target, names -> insert(names, id, index)));
        }
        return edit.finish(id, target, null, null);
    }

    /** Creates an empty group. */
    public static Outcome createGroup(Path root, String id, String title) {
        String problem = idProblem(root, "group", id);
        if (problem != null) {
            return Outcome.refused(problem);
        }
        Path folder = root.resolve(id);
        if (Files.exists(folder)) {
            return Outcome.refused("\"" + id + "\" is already a folder at the root");
        }

        Edit edit = new Edit();
        edit.creates(folder);
        edit.writeText(folder.resolve(QuestFiles.GROUP_MANIFEST), groupJson(id, title, List.of()));
        // Appended, not inserted: a new group has no neighbours yet, and "wherever it ends up" is a
        // position the author can then drag it out of. The menu's "new group after this one" case is
        // `moveGroup` immediately afterwards, which keeps one implementation of "move".
        edit.writeIndex(root, entries -> entries.add(new Entry("group", id)));
        return edit.finish(null, id, null, null);
    }

    /** Renames a chapter: its folder, its id, and the record of what it used to be called. */
    public static Outcome renameChapter(Path root, String id, String newId, String title) {
        Optional<Path> found = chapterFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no chapter called \"" + id + "\"");
        }
        if (!id.equals(newId)) {
            String problem = idProblem(root, "chapter", newId);
            if (problem != null) {
                return Outcome.refused(problem);
            }
        }
        Path folder = found.get();
        String group = groupIdOf(root, folder);
        Path renamed = folder.resolveSibling(newId);
        if (!id.equals(newId) && Files.exists(renamed)) {
            return Outcome.refused("\"" + newId + "\" is already a folder in that place");
        }

        Edit edit = new Edit();
        edit.forget(id);
        // Both ids, because both directions of the edit leave one of them stale: after the rename an
        // editor keyed by the old id points at a folder that is gone, and after an undo one keyed by the
        // new id points at a folder that has just been renamed back. A name with no editor is a no-op.
        edit.forget(newId);
        if (!id.equals(newId)) {
            edit.move(folder, renamed);
        }
        edit.writeMoved(folder.resolve(QuestFiles.CHAPTER_MANIFEST),
                renamed.resolve(QuestFiles.CHAPTER_MANIFEST),
                editedChapterJson(folder, id, newId, title));
        if (!group.isEmpty()) {
            edit.write(updateGroupChapters(root, group,
                    names -> replace(names, id, newId)));
        }
        else {
            edit.writeIndex(root, entries -> renameEntry(entries, "chapter", id, newId));
        }
        return edit.finish(newId, group, id, group);
    }

    /** Renames a group: its folder, its id, and the record of what it used to be called. */
    public static Outcome renameGroup(Path root, String id, String newId, String title) {
        Optional<Path> found = groupFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no group called \"" + id + "\"");
        }
        if (!id.equals(newId)) {
            String problem = idProblem(root, "group", newId);
            if (problem != null) {
                return Outcome.refused(problem);
            }
        }
        Path folder = found.get();
        Path renamed = root.resolve(newId);
        if (!id.equals(newId) && Files.exists(renamed)) {
            return Outcome.refused("\"" + newId + "\" is already a folder at the root");
        }

        Edit edit = new Edit();
        List<String> chapters = groupChapterNames(root, id);
        edit.forget(chapters);
        if (!id.equals(newId)) {
            edit.move(folder, renamed);
        }
        edit.writeMoved(folder.resolve(QuestFiles.GROUP_MANIFEST),
                renamed.resolve(QuestFiles.GROUP_MANIFEST),
                editedGroupJson(folder, id, newId, title));
        // The chapterGroupId every quest carries is read from this manifest's id, so renaming the group
        // changes it for everything under it without touching one chapter file.
        edit.writeIndex(root, entries -> renameEntry(entries, "group", id, newId));
        return edit.finish(null, newId, null, id);
    }

    /** Duplicates a chapter beside itself, re-id'ing every quest inside so nothing collides. */
    public static Outcome duplicateChapter(Path root, String id, String newId, String newTitle) {
        Optional<Path> found = chapterFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no chapter called \"" + id + "\"");
        }
        String problem = idProblem(root, "chapter", newId);
        if (problem != null) {
            return Outcome.refused(problem);
        }
        Path folder = found.get();
        String group = groupIdOf(root, folder);
        Path copy = folder.resolveSibling(newId);
        if (Files.exists(copy)) {
            return Outcome.refused("\"" + newId + "\" is already a folder in that place");
        }

        Set<String> taken = existingIds(root, QuestFiles.Kind.QUEST);
        List<QuestFiles.Declaration> chapterQuests = questsOf(root, folder);
        List<String> names = new ArrayList<>();
        List<ReId> reIds = new ArrayList<>();
        for (QuestFiles.Declaration quest : chapterQuests) {
            String fileName = quest.path().getFileName().toString();
            String questId = quest.id() == null ? fileName : quest.id();
            String fresh = ChapterNaming.suggested(questId, "_copy", taken);
            taken.add(fresh);
            names.add(fresh + QuestEditor.SUFFIX);
            reIds.add(new ReId(questId, fresh));
        }

        Edit edit = new Edit();
        edit.forget(newId);
        edit.creates(copy);
        edit.writeText(copy.resolve(QuestFiles.CHAPTER_MANIFEST),
                copiedChapterJson(folder, newId, newTitle, names));
        for (QuestFiles.Declaration quest : chapterQuests) {
            String fileName = quest.path().getFileName().toString();
            String questId = quest.id() == null ? fileName : quest.id();
            String fresh = reIds.stream().filter(r -> r.from().equals(questId)).findFirst()
                    .map(ReId::to).orElse(questId);
            edit.writeText(copy.resolve(fresh + QuestEditor.SUFFIX),
                    copiedQuestJson(quest, fresh, reIds));
        }
        int at = indexOfChapter(root, group, id);
        if (group.isEmpty()) {
            edit.writeIndex(root, entries -> insertEntry(entries, "chapter", newId, at + 1));
        }
        else {
            edit.write(updateGroupChapters(root, group, list -> insert(list, newId, at + 1)));
        }
        return edit.finish(newId, group, null, null);
    }

    /** Duplicates a group beside itself, re-id'ing the group, its chapters and every quest in them. */
    public static Outcome duplicateGroup(Path root, String id, String newId, String newTitle) {
        Optional<Path> found = groupFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no group called \"" + id + "\"");
        }
        String problem = idProblem(root, "group", newId);
        if (problem != null) {
            return Outcome.refused(problem);
        }
        Path folder = found.get();
        Path copy = root.resolve(newId);
        if (Files.exists(copy)) {
            return Outcome.refused("\"" + newId + "\" is already a folder at the root");
        }

        Set<String> chapterIds = existingIds(root, QuestFiles.Kind.CHAPTER);
        Set<String> questIds = existingIds(root, QuestFiles.Kind.QUEST);
        List<String> sourceChapters = groupChapterNames(root, id);
        List<String> newChapters = new ArrayList<>();
        List<Rename> chapterRenames = new ArrayList<>();
        for (String chapter : sourceChapters) {
            String fresh = ChapterNaming.suggested(chapter, "_copy", chapterIds);
            chapterIds.add(fresh);
            newChapters.add(fresh);
            chapterRenames.add(new Rename(chapter, fresh));
        }

        Edit edit = new Edit();
        edit.forget(newChapters);
        edit.creates(copy);
        edit.writeText(copy.resolve(QuestFiles.GROUP_MANIFEST), groupJson(newId, newTitle, newChapters));
        for (Rename rename : chapterRenames) {
            Path sourceChapter = folder.resolve(rename.from());
            List<QuestFiles.Declaration> quests = questsOf(root, sourceChapter);
            List<String> names = new ArrayList<>();
            List<ReId> reIds = new ArrayList<>();
            for (QuestFiles.Declaration quest : quests) {
                String fileName = quest.path().getFileName().toString();
                String questId = quest.id() == null ? fileName : quest.id();
                String fresh = ChapterNaming.suggested(questId, "_copy", questIds);
                questIds.add(fresh);
                names.add(fresh + QuestEditor.SUFFIX);
                reIds.add(new ReId(questId, fresh));
            }
            edit.writeText(copy.resolve(rename.to()).resolve(QuestFiles.CHAPTER_MANIFEST),
                    copiedChapterJson(sourceChapter, rename.to(), rename.to(), names));
            for (QuestFiles.Declaration quest : quests) {
                String fileName = quest.path().getFileName().toString();
                String questId = quest.id() == null ? fileName : quest.id();
                String fresh = reIds.stream().filter(r -> r.from().equals(questId)).findFirst()
                        .map(ReId::to).orElse(questId);
                edit.writeText(copy.resolve(rename.to()).resolve(fresh + QuestEditor.SUFFIX),
                        copiedQuestJson(quest, fresh, reIds));
            }
        }
        edit.writeIndex(root, entries -> insertEntry(entries, "group", newId,
                indexOfEntry(entries, "group", id) + 1));
        return edit.finish(null, newId, null, null);
    }

    /** Deletes a chapter recoverably: the folder is put aside with its quests inside. */
    public static Outcome deleteChapter(Path root, String id) {
        Optional<Path> found = chapterFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no chapter called \"" + id + "\"");
        }
        Path folder = found.get();
        String group = groupIdOf(root, folder);

        Edit edit = new Edit();
        edit.forget(id);
        edit.setAside(folder);
        if (group.isEmpty()) {
            edit.writeIndex(root, entries -> removeEntry(entries, "chapter", id));
        }
        else {
            edit.write(updateGroupChapters(root, group, names -> names.remove(id)));
        }
        return edit.finish(null, null, id, group);
    }

    /** Deletes a group recoverably, its chapters with it. */
    public static Outcome deleteGroup(Path root, String id) {
        Optional<Path> found = groupFolder(root, id);
        if (found.isEmpty()) {
            return Outcome.refused("no group called \"" + id + "\"");
        }
        Edit edit = new Edit();
        edit.forget(groupChapterNames(root, id));
        edit.setAside(found.get());
        edit.writeIndex(root, entries -> removeEntry(entries, "group", id));
        return edit.finish(null, null, null, id);
    }

    // ------------------------------------------------------------------
    // The step builder
    // ------------------------------------------------------------------

    /**
     * Collects the forward steps an edit performs and the reverse steps that undo it.
     *
     * <p>Reverse steps are prepended as forward steps are added, so running the reverse list in order
     * undoes the edit from the end: the last thing done is the first thing undone. Getting this wrong is
     * the classic undo bug — a folder moved back before the manifest that names it is restored — and
     * doing it here means no individual edit can get it wrong on its own.
     */
    private static final class Edit {

        private final List<Step> forward = new ArrayList<>();
        private final List<Step> reverse = new ArrayList<>();
        private final List<String> forget = new ArrayList<>();
        /** Folders this edit makes, whose files need no reverse of their own: the folder is put aside. */
        private final List<Path> createdRoots = new ArrayList<>();

        void forget(String chapter) {
            if (chapter != null && !chapter.isBlank() && !forget.contains(chapter)) {
                forget.add(chapter);
            }
        }

        void forget(List<String> chapters) {
            for (String chapter : chapters) {
                forget(chapter);
            }
        }

        void move(Path from, Path to) {
            forward.add(new Step.Move(from, to));
            reverse.add(0, new Step.Move(to, from));
        }

        void setAside(Path path) {
            forward.add(new Step.SetAside(path));
            Path aside = aside(path);
            reverse.add(0, new Step.Move(aside, path));
        }

        /** Writes an existing file: its current text is captured now, for the reverse direction. */
        void write(JsonFile file) {
            writeText(file.file(), file.json());
        }

        /**
         * Records that this edit makes a whole folder, so undoing it puts the folder aside.
         *
         * <p>Called before the folder's files are written. Without it an undo would set aside each file
         * in turn and leave the empty folders behind — a duplicate "undone" into a shell of itself,
         * which the next load would then report as unlisted content.
         */
        void creates(Path folder) {
            createdRoots.add(folder);
            reverse.add(0, new Step.SetAside(folder));
        }

        /**
         * Writes a file the same edit is moving: the before text comes from where the file is now, the
         * write lands where it will be.
         *
         * <p>Capturing at the written path instead is the bug this exists to prevent — at plan time the
         * move has not happened, so the file "is not there" and the reverse sets it aside rather than
         * restoring it. A rename that cannot be undone is what that costs, and it is invisible in the
         * forward direction.
         */
        void writeMoved(Path from, Path to, String after) {
            String before = readText(from).orElse(null);
            forward.add(new Step.Write(to, after));
            if (before == null) {
                reverse.add(0, new Step.SetAside(to));
            }
            else {
                reverse.add(0, new Step.Write(to, before));
            }
        }

        /** Writes index.json with one change applied, bootstrapping the file when it does not exist. */
        void writeIndex(Path root, java.util.function.Consumer<List<Entry>> change) {
            write(updateIndex(root, change));
        }

        void writeText(Path path, String after) {
            String before = readText(path).orElse(null);
            forward.add(new Step.Write(path, after));
            if (before == null) {
                // The file did not exist, so undoing the edit means it should not exist again — unless
                // it is inside a folder this edit made, in which case that folder's own step is the undo
                // and a second one here would run after the folder is already gone.
                boolean insideCreated = createdRoots.stream().anyMatch(path::startsWith);
                if (!insideCreated) {
                    reverse.add(0, new Step.SetAside(path));
                }
            }
            else {
                reverse.add(0, new Step.Write(path, before));
            }
        }

        /** Drops the most recent forward write, for a branch that decided against it. */
        /**
         * Runs the forward steps and returns the structure, or a refusal naming the file that failed.
         *
         * <p>Every write is validated first: the text is parsed and put through the loader's own
         * validator, and a file that would not load stops the whole edit before the first step runs. That
         * is the same rule {@code QuestEditor.save} follows, applied to the manifests this class makes.
         *
         * @param chapterId     the chapter to look at after the edit, or null
         * @param groupId       the group it is in, or null
         * @param undoChapterId the chapter to look at after an undo of it, or null
         * @param undoGroupId   the group it is in then, or null
         */
        Outcome finish(String chapterId, String groupId, String undoChapterId, String undoGroupId) {
            List<Step> writes = forward.stream().filter(step -> step instanceof Step.Write).toList();
            for (Step step : writes) {
                Step.Write write = (Step.Write) step;
                String problem = validate(write.path(), write.content());
                if (problem != null) {
                    return Outcome.refused(problem);
                }
            }
            for (Step step : forward) {
                String problem = run(step);
                if (problem != null) {
                    return Outcome.refused(problem);
                }
            }
            // The same names in both directions: a chapter whose folder moved is stale whichever way the
            // edit is being read, and which id it has now is the thing that differs.
            List<String> stale = List.copyOf(forget);
            return Outcome.done(new Structure(List.copyOf(forward), List.copyOf(reverse),
                    new Structure.Meta(chapterId, groupId, stale),
                    new Structure.Meta(undoChapterId, undoGroupId, stale)));
        }

        private static String run(Step step) {
            try {
                switch (step) {
                    case Step.Move move -> {
                        Files.createDirectories(move.to().getParent());
                        Files.move(move.from(), move.to(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    case Step.Write write -> {
                        Files.createDirectories(write.path().getParent());
                        Files.writeString(write.path(), write.content(), StandardCharsets.UTF_8);
                    }
                    case Step.SetAside setAside -> Files.move(setAside.path(), aside(setAside.path()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return null;
            }
            catch (IOException | RuntimeException e) {
                return "the filesystem refused it: " + e.getMessage();
            }
        }

        /** Where a put-aside path goes, without ever overwriting one already there. */
        private static Path aside(Path path) {
            Path candidate = path.resolveSibling(path.getFileName() + QuestFiles.DELETED_SUFFIX);
            int n = 2;
            while (Files.exists(candidate)) {
                candidate = path.resolveSibling(path.getFileName() + QuestFiles.DELETED_SUFFIX + "." + n++);
            }
            return candidate;
        }
    }

    /** Runs a structure's reverse steps, for an undo. */
    public static void undo(Structure structure) {
        for (Step step : structure.reverse()) {
            runForUndo(step);
        }
    }

    /** Runs a structure's forward steps again, for a redo. */
    public static void redo(Structure structure) {
        for (Step step : structure.forward()) {
            runForUndo(step);
        }
    }

    private static void runForUndo(Step step) {
        try {
            switch (step) {
                case Step.Move move -> {
                    Files.createDirectories(move.to().getParent());
                    Files.move(move.from(), move.to(), StandardCopyOption.REPLACE_EXISTING);
                }
                case Step.Write write -> {
                    Files.createDirectories(write.path().getParent());
                    Files.writeString(write.path(), write.content(), StandardCharsets.UTF_8);
                }
                case Step.SetAside setAside -> {
                    Path aside = setAside.path().resolveSibling(
                            setAside.path().getFileName() + QuestFiles.DELETED_SUFFIX);
                    int n = 2;
                    while (Files.exists(aside)) {
                        aside = setAside.path().resolveSibling(
                                setAside.path().getFileName() + QuestFiles.DELETED_SUFFIX + "." + n++);
                    }
                    Files.move(setAside.path(), aside, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        catch (IOException | RuntimeException e) {
            dev.ellipog.tasked.Constants.LOG.warn("tasked: a structural undo step failed: {}", step, e);
        }
    }

    // ------------------------------------------------------------------
    // Reading the tree
    // ------------------------------------------------------------------

    /** A root entry: a group, a loose chapter, or a version-1 file. */
    private record Entry(String kind, String name) {
    }

    private record Rename(String from, String to) {
    }

    private record ReId(String from, String to) {
    }

    private static Optional<Path> chapterFolder(Path root, String id) {
        for (Path group : rootFolders(root)) {
            if (Files.isRegularFile(group.resolve(QuestFiles.GROUP_MANIFEST))) {
                Path chapter = group.resolve(id);
                if (Files.isRegularFile(chapter.resolve(QuestFiles.CHAPTER_MANIFEST))) {
                    return Optional.of(chapter);
                }
            }
            else if (group.getFileName().toString().equals(id)
                    && Files.isRegularFile(group.resolve(QuestFiles.CHAPTER_MANIFEST))) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> groupFolder(Path root, String id) {
        Path folder = root.resolve(id);
        return Files.isRegularFile(folder.resolve(QuestFiles.GROUP_MANIFEST))
                ? Optional.of(folder) : Optional.empty();
    }

    /** The group a chapter folder is in, or empty for a chapter at the root. */
    private static String groupIdOf(Path root, Path chapterFolder) {
        Path parent = chapterFolder.getParent();
        if (parent == null || parent.equals(root)) {
            return "";
        }
        return parent.getFileName().toString();
    }

    /** The root's folders, name-sorted, skipping the names the walk skips. */
    private static List<Path> rootFolders(Path root) {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory)
                    .filter(path -> !isSkipped(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isSkipped(String name) {
        return dev.ellipog.armature.client.ui.kit.DeclaredPaths.isIgnoredName(name)
                || QuestFiles.isDeletedName(name)
                || QuestFiles.isReservedName(name);
    }

    private static List<String> groupChapterNames(Path root, String group) {
        Optional<Path> folder = groupFolder(root, group);
        if (folder.isEmpty()) {
            return List.of();
        }
        String text = readText(folder.get().resolve(QuestFiles.GROUP_MANIFEST)).orElse("{}");
        try {
            JsonFile file = parse(folder.get().resolve(QuestFiles.GROUP_MANIFEST), text);
            return new ArrayList<>(file.strings("chapters"));
        }
        catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<QuestFiles.Declaration> questsOf(Path root, Path chapterFolder) {
        return QuestFiles.discover(root).of(QuestFiles.Kind.QUEST).stream()
                .filter(quest -> quest.path().getParent().equals(chapterFolder))
                .toList();
    }

    /** Every id of one kind in the tree, from the loader's own walk. */
    private static Set<String> existingIds(Path root, QuestFiles.Kind kind) {
        return QuestFiles.discover(root).of(kind).stream()
                .map(QuestFiles.Declaration::id)
                .filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int indexOfChapter(Path root, String group, String chapter) {
        if (group.isEmpty()) {
            List<Entry> entries = readIndex(root);
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).kind().equals("chapter") && entries.get(i).name().equals(chapter)) {
                    return i;
                }
            }
            return entries.size() - 1;
        }
        int at = groupChapterNames(root, group).indexOf(chapter);
        return Math.max(0, at);
    }

    // ------------------------------------------------------------------
    // index.json
    // ------------------------------------------------------------------

    private static Path indexPath(Path root) {
        return root.resolve(QuestFiles.INDEX_MANIFEST);
    }

    /**
     * The root entries, in order: the manifest's when it exists, the disk's when it does not.
     *
     * <p>The fallback is what makes the first structural edit on an old tree non-destructive: the entry
     * list it writes is the order that tree already loaded in — group folders by name, flat files by
     * name — so gaining an index does not reorder anything.
     */
    private static List<Entry> readIndex(Path root) {
        String text = readText(indexPath(root)).orElse(null);
        if (text == null) {
            List<Entry> boot = new ArrayList<>();
            for (Path folder : rootFolders(root)) {
                if (Files.isRegularFile(folder.resolve(QuestFiles.GROUP_MANIFEST))) {
                    boot.add(new Entry("group", folder.getFileName().toString()));
                }
                else if (Files.isRegularFile(folder.resolve(QuestFiles.CHAPTER_MANIFEST))) {
                    boot.add(new Entry("chapter", folder.getFileName().toString()));
                }
            }
            try (Stream<Path> files = Files.list(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().equals(QuestFiles.INDEX_MANIFEST))
                        .filter(path -> !isSkipped(path.getFileName().toString()))
                        .sorted()
                        .forEach(path -> boot.add(new Entry("file", path.getFileName().toString())));
            }
            catch (IOException ignored) {
                // A root that cannot be listed is a root no edit can help; the write below will say so.
            }
            return boot;
        }
        JsonFile file = parse(indexPath(root), text);
        List<Entry> entries = new ArrayList<>();
        JsonElement entriesElement = file.get("entries");
        JsonArray array = entriesElement != null && entriesElement.isJsonArray()
                ? entriesElement.getAsJsonArray() : new JsonArray();
        for (var element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            for (String key : List.of("group", "chapter", "file")) {
                if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
                    continue;
                }
                String name = object.get(key).getAsString();
                // A root entry for a chapter the root no longer has is a name that has moved into a
                // group, or gone. The editor's view of the index is what is true, so it is dropped here
                // rather than carried into the next write — which is how a stale line from an earlier
                // version stops surviving every edit that rewrites the file. The loader still reports
                // it on a tree nobody edits; see its own note on reading and only reading.
                if (key.equals("chapter")
                        && !Files.isRegularFile(root.resolve(name).resolve(QuestFiles.CHAPTER_MANIFEST))) {
                    continue;
                }
                entries.add(new Entry(key, name));
            }
        }
        return entries;
    }

    /**
     * Writes one key of the tree's settings block in {@code index.json}.
     *
     * <p>Read-modify-write, and the modify is one key: every other root member — the entry list, the
     * other settings, anything a future version adds — is carried over exactly as it was found, which
     * is the same promise {@link #indexFile} makes for structural edits. A null value removes the key,
     * and an emptied settings block is removed rather than left as an empty object.
     *
     * <p>Not a {@link Structure}: there are no steps here to reverse, so this does not join the
     * editor's undo history. A caller that needs that would have to model the settings block, which is
     * a bigger thing than two strings.
     *
     * @return whether the file was written
     */
    public static boolean setIndexSetting(Path root, String key, Object value) {
        if (key == null || key.isBlank()) {
            return false;
        }
        // The keys this writer will touch are the ones the loader knows: an op from a newer client
        // naming a field this build has never heard of is refused rather than written into a file whose
        // schema would then refuse the whole index.
        if (!QuestSettings.FIELDS.contains(key)) {
            return false;
        }
        Path path = indexPath(root);
        JsonObject object = new JsonObject();
        String existingText = readText(path).orElse(null);
        if (existingText != null) {
            try {
                JsonElement existing = com.google.gson.JsonParser.parseString(existingText);
                if (existing.isJsonObject()) {
                    for (var member : existing.getAsJsonObject().entrySet()) {
                        object.add(member.getKey(), member.getValue());
                    }
                }
            }
            catch (RuntimeException ignored) {
                // An index that does not parse is about to be replaced by one that does; there is
                // nothing in it that can be carried over.
            }
        }
        if (!object.has("$schema")) {
            object.addProperty("$schema", INDEX_SCHEMA);
        }

        JsonObject settings = object.has("settings") && object.get("settings").isJsonObject()
                ? object.getAsJsonObject("settings") : new JsonObject();
        if (value == null) {
            settings.remove(key);
        }
        else {
            JsonElement json = switch (value) {
                case JsonElement element -> element;
                case String text -> new com.google.gson.JsonPrimitive(text);
                case Boolean flag -> new com.google.gson.JsonPrimitive(flag);
                case Number number -> new com.google.gson.JsonPrimitive(number);
                default -> null;
            };
            if (json == null) {
                return false;
            }
            settings.add(key, json);
        }
        if (settings.isEmpty()) {
            object.remove("settings");
        }
        else {
            object.add("settings", settings);
        }

        try {
            JsonFile.of(path, object).write();
        }
        catch (IOException e) {
            return false;
        }
        return true;
    }

    private static JsonFile indexFile(Path root, List<Entry> entries) {
        Path path = indexPath(root);
        JsonObject object = new JsonObject();
        object.addProperty("$schema", INDEX_SCHEMA);
        // Whatever else the file already holds, carried over. The editor owns the entry list; it does
        // not own the tree's own declarations, and rebuilding the file from scratch silently destroyed
        // them -- the `settings` block was written by hand, read by the loader, and wiped by the first
        // structural edit. Everything but the two keys the editor owns is preserved, so the next
        // root-level declaration does not have to remember this lesson again.
        String existingText = readText(path).orElse(null);
        if (existingText != null) {
            try {
                JsonElement existing = com.google.gson.JsonParser.parseString(existingText);
                if (existing.isJsonObject()) {
                    for (var member : existing.getAsJsonObject().entrySet()) {
                        if (!member.getKey().equals("entries") && !member.getKey().equals("$schema")) {
                            object.add(member.getKey(), member.getValue());
                        }
                    }
                }
            }
            catch (RuntimeException ignored) {
                // An index that does not parse is about to be replaced by one that does; there is
                // nothing in it that can be carried over.
            }
        }
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject one = new JsonObject();
            one.addProperty(entry.kind(), entry.name());
            array.add(one);
        }
        object.add("entries", array);
        return JsonFile.of(path, object);
    }

    private static void insert(List<String> names, String name, int index) {
        int at = Math.max(0, Math.min(index, names.size()));
        names.add(at, name);
    }

    private static void replace(List<String> names, String from, String to) {
        int at = names.indexOf(from);
        if (at >= 0) {
            names.set(at, to);
        }
    }

    /**
     * Places an entry at a position <b>among the entries of its own kind</b>.
     *
     * <p>Not at an absolute index: the rows a player drags are groups and chapters, and the manifest
     * also holds version-1 files they cannot see. Counting only the kind being placed is what makes the
     * index a client computes from the sidebar rows mean the same thing here as it does on screen.
     *
     * <p><b>Placed, not appended.</b> A name already listed is moved to its new position rather than
     * listed twice — which the first version could do, and a duplicate listing is an error the loader
     * reports on every load. It is reachable from a re-drop and, once the index is healed on read, from
     * the heal itself.
     */
    private static void insertEntry(List<Entry> entries, String kind, String name, int index) {
        entries.removeIf(entry -> entry.kind().equals(kind) && entry.name().equals(name));
        int seen = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind().equals(kind)) {
                if (seen == index) {
                    entries.add(i, new Entry(kind, name));
                    return;
                }
                seen++;
            }
        }
        entries.add(new Entry(kind, name));
    }

    private static int indexOfEntry(List<Entry> entries, String kind, String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind().equals(kind) && entries.get(i).name().equals(name)) {
                return i;
            }
        }
        return entries.size() - 1;
    }

    private static void removeEntry(List<Entry> entries, String kind, String name) {
        entries.removeIf(entry -> entry.kind().equals(kind) && entry.name().equals(name));
    }

    private static void moveEntry(List<Entry> entries, String kind, String name, int index) {
        entries.removeIf(entry -> entry.kind().equals(kind) && entry.name().equals(name));
        insertEntry(entries, kind, name, index);
    }

    private static void renameEntry(List<Entry> entries, String kind, String from, String to) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind().equals(kind) && entries.get(i).name().equals(from)) {
                entries.set(i, new Entry(kind, to));
            }
        }
    }

    /** Writes the index with one change applied, bootstrapping the file when it does not exist. */
    private static JsonFile updateIndex(Path root, java.util.function.Consumer<List<Entry>> change) {
        List<Entry> entries = readIndex(root);
        change.accept(entries);
        return indexFile(root, entries);
    }

    /** Writes a group manifest with one change applied to its {@code chapters}. */
    private static JsonFile updateGroupChapters(Path root, String group,
                                                java.util.function.Consumer<List<String>> change) {
        Path manifest = root.resolve(group).resolve(QuestFiles.GROUP_MANIFEST);
        JsonFile file = parse(manifest, readText(manifest).orElse("{}"));
        List<String> names = new ArrayList<>(file.strings("chapters"));
        change.accept(names);
        file.setStrings("chapters", names);
        return file;
    }

    // ------------------------------------------------------------------
    // JSON the edits make
    // ------------------------------------------------------------------

    private static String chapterJson(String id, String title, String schema, List<String> quests) {
        JsonObject object = new JsonObject();
        object.addProperty("$schema", schema);
        object.addProperty("id", id);
        object.addProperty("title", title == null ? id : title);
        JsonArray names = new JsonArray();
        quests.forEach(names::add);
        object.add("quests", names);
        return object.toString();
    }

    private static String groupJson(String id, String title, List<String> chapters) {
        JsonObject object = new JsonObject();
        object.addProperty("$schema", GROUP_SCHEMA);
        object.addProperty("id", id);
        object.addProperty("title", title == null ? id : title);
        JsonArray names = new JsonArray();
        chapters.forEach(names::add);
        object.add("chapters", names);
        return object.toString();
    }

    /** The chapter's manifest with a new id and title, the old id kept as an alias. */
    private static String editedChapterJson(Path folder, String oldId, String newId, String title) {
        JsonFile file = parse(folder.resolve(QuestFiles.CHAPTER_MANIFEST),
                readText(folder.resolve(QuestFiles.CHAPTER_MANIFEST)).orElse("{}"));
        file.setText("id", newId);
        if (title != null) {
            file.setText("title", title);
        }
        keepAlias(file, oldId, newId);
        return file.json();
    }

    /** The group's manifest with a new id and title, the old id kept as an alias. */
    private static String editedGroupJson(Path folder, String oldId, String newId, String title) {
        JsonFile file = parse(folder.resolve(QuestFiles.GROUP_MANIFEST),
                readText(folder.resolve(QuestFiles.GROUP_MANIFEST)).orElse("{}"));
        file.setText("id", newId);
        if (title != null) {
            file.setText("title", title);
        }
        keepAlias(file, oldId, newId);
        return file.json();
    }

    /**
     * Records the old id as an alias, so a rename does not break a reference to it.
     *
     * <p>Aliases share the id namespace of their kind — the loader refuses a duplicate — so an old id
     * that is already somebody else's name is caught by the validation that runs before the write, and
     * the whole rename is refused with that message. Which is the honest answer: renaming this chapter
     * to a name another chapter already answers to would make the old reference ambiguous.
     */
    private static void keepAlias(JsonFile file, String oldId, String newId) {
        if (oldId == null || oldId.equals(newId)) {
            return;
        }
        List<String> aliases = new ArrayList<>(file.strings("aliases"));
        aliases.remove(newId);
        if (!aliases.contains(oldId)) {
            aliases.add(oldId);
        }
        file.setStrings("aliases", aliases);
    }

    /** A copy of a chapter manifest: new id and title, the quest filenames re-id'd, aliases dropped. */
    private static String copiedChapterJson(Path folder, String newId, String newTitle, List<String> quests) {
        Path manifest = folder.resolve(QuestFiles.CHAPTER_MANIFEST);
        JsonFile file = parse(manifest, readText(manifest).orElse("{}"));
        file.setText("id", newId);
        file.setText("title", newTitle == null ? newId : newTitle);
        file.setStrings("quests", quests);
        // Dropped, not copied: an alias is unique within its kind, so a copy that kept one would be a
        // duplicate id by another name -- and an alias list that named somebody else's old name is a
        // reference to a thing this copy is not.
        file.remove("aliases");
        return file.json();
    }

    /** A copy of one quest: a fresh id, aliases dropped, and sibling references remapped. */
    private static String copiedQuestJson(QuestFiles.Declaration quest, String newId, List<ReId> reIds) {
        Path file = quest.path();
        JsonFile copy = parse(file, readText(file).orElse("{}"));
        copy.setText("id", newId);
        copy.remove("aliases");
        // Dependencies between the quests being copied are remapped to the copies, so a duplicated
        // chapter is a self-contained sequence rather than one that points at the originals. A
        // dependency on a quest outside the copy is left alone, which is the only correct reading of it.
        List<String> dependsOn = new ArrayList<>(copy.strings("dependsOn"));
        for (int i = 0; i < dependsOn.size(); i++) {
            String at = dependsOn.get(i);
            for (ReId reId : reIds) {
                if (reId.from().equals(at)) {
                    dependsOn.set(i, reId.to());
                }
            }
        }
        if (!dependsOn.isEmpty()) {
            copy.setStrings("dependsOn", dependsOn);
        }
        return copy.json();
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /**
     * Why an id is not usable for a new chapter or group, or null when it is.
     *
     * <p>The rule is {@link ChapterNaming}'s, which is where the book asks the same question: one home
     * for it, and one wording, so a name refused here reads as the card would have put it. The collision
     * sentence stays here because this list is the index's own ids -- the book's carries aliases too,
     * which is why it says so and this does not.
     */
    private static String idProblem(Path root, String kind, String id) {
        String problem = ChapterNaming.problemWith(id);
        if (problem != null) {
            return problem;
        }
        if (existingIds(root, kind.equals("group") ? QuestFiles.Kind.GROUP : QuestFiles.Kind.CHAPTER)
                .contains(id)) {
            return "there is already a " + kind + " called \"" + id + "\"";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private static Optional<String> readText(Path path) {
        try {
            return Files.isRegularFile(path)
                    ? Optional.of(Files.readString(path, StandardCharsets.UTF_8))
                    : Optional.empty();
        }
        catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses a file this class is about to edit.
     *
     * <p>The checked parse failure becomes the unchecked one the edit's refusal path already handles, so
     * a manifest somebody has broken by hand stops the edit with a sentence rather than a stack trace in
     * the tick loop.
     */
    private static JsonFile parse(Path path, String text) {
        try {
            return JsonFile.parse(path, text);
        }
        catch (RuntimeException e) {
            throw new IllegalStateException("a file that is already there does not parse: "
                    + e.getMessage());
        }
    }

    /**
     * Why a JSON text would not load, or null when it would.
     *
     * <p>The loader's own validator, on the text about to be written, so an edit that would produce an
     * unloadable file is refused before anything moves. The display name is the path, which is what a
     * message has to name for the author to find it.
     */
    private static String validate(Path path, String text) {
        String display = path.getFileName().toString();
        Problems problems = new Problems();
        try {
            JsonDocument document = JsonDocument.parse(display, text);
            String name = path.getFileName().toString();
            if (name.equals(QuestFiles.GROUP_MANIFEST)) {
                QuestValidator.validateGroupDocument(document, problems);
            }
            else if (name.equals(QuestFiles.CHAPTER_MANIFEST)) {
                QuestValidator.validateChapterDocument(document, problems);
            }
            else if (name.equals(QuestFiles.INDEX_MANIFEST)) {
                return null;  // structural, and validated by the loader's discovery rather than a schema
            }
            else if (path.getParent() != null && path.getParent().getFileName() != null
                    && path.getParent().getFileName().toString()
                            .equals(QuestFiles.REWARD_TABLES_DIRECTORY)) {
                // A table is not a quest; validating it as one refused every table an edit wrote.
                QuestValidator.validateRewardTableDocument(document, problems);
            }
            else {
                QuestValidator.validateQuestDocument(document, problems);
            }
        }
        catch (RuntimeException | dev.ellipog.armature.api.data.JsonParseException e) {
            return "the edit would write a file that does not parse: " + e.getMessage();
        }
        if (problems.hasErrors()) {
            return "the edit would write a file the loader refuses: "
                    + problems.all().stream().map(problem -> problem.render()).findFirst().orElse("");
        }
        return null;
    }
}
