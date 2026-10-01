package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.QuestFiles;
import dev.ellipog.tasked.quest.QuestShape;
import dev.ellipog.tasked.quest.QuestValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One chapter of quest files, open for editing.
 *
 * <h2>What this is</h2>
 *
 * <p>The whole of the editor's state that is not a widget: the files a chapter is made of, the edits, the
 * undo history, and the save. It is deliberately game-free — every operation here is text and paths — so
 * that the part that can corrupt somebody's quest pack is the part with tests, and the screen is left
 * holding nothing but widgets and key codes.
 *
 * <h2>One file per quest, which is why this is a chapter and not a quest</h2>
 *
 * <p>The format is a folder tree: a chapter manifest lists the file names in progression order, and each
 * quest is its own file. So creating a quest is creating a file <i>and</i> adding its name to that list,
 * deleting is both of those in reverse, and a reorder is a change to the list. A document per quest would
 * have to reach outside itself for all three, which is how the manifest and its contents come to
 * disagree — so the unit of editing is the chapter, and {@link JsonFile} is the unit of storage.
 *
 * <h2>Undo is a snapshot of the chapter, not a log of operations</h2>
 *
 * <p>Every mutation pushes the chapter's files as they were — their text — and undo parses them back.
 * That is not the cheapest design and it is the one that cannot be wrong: an operation log has to be
 * inverted correctly, and the inversion for "create a quest" (delete a file, and remove a name from a
 * list, and only if both were absent before) is exactly the kind of arithmetic that is right until the
 * day it is not. A chapter is a few kilobytes of text, and the editor is a tool for one person.
 *
 * <h2>Saving validates first, with the loader's own validator</h2>
 *
 * <p>{@link QuestValidator} is the thing that decides whether a file is loadable, and it runs here on the
 * text this editor is about to write — the same way {@code /tasked reload} would run it a moment later.
 * A save that would write a file the loader refuses is not a save; it is an error message, and the file
 * is left exactly as it was. That is the only ordering that cannot leave a pack broken by an editor
 * session, and it is why {@link #save()} returns the problems rather than logging them.
 */
public final class QuestEditor {

    /** The manifest that lists a chapter's quests, in progression order. */
    public static final String MANIFEST = QuestFiles.CHAPTER_MANIFEST;

    /** The suffix a quest file's name carries. A quest's id is its name without this. */
    public static final String SUFFIX = ".json";

    /** Where a new quest's {@code $schema} points, appended to the chapter's own prefix. */
    private static final String QUEST_SCHEMA = "_schema/quest.schema.json";

    /** How many steps back the editor remembers. Deep enough for a session's worth of dragging. */
    private static final int HISTORY = 60;

    private final Path root;
    private final Path folder;
    private final JsonFile manifest;
    private final Map<String, JsonFile> quests = new LinkedHashMap<>();
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();

    /** One file's text, by path. Everything an undo has to put back. */
    private record Snapshot(Map<Path, String> files) {
    }

    private QuestEditor(Path root, Path folder, JsonFile manifest) {
        this.root = root;
        this.folder = folder;
        this.manifest = manifest;
    }

    /**
     * Opens a chapter, by the id the book shows.
     *
     * <p>The folder is found with {@link QuestFiles#discover} — the loader's own walk — rather than by
     * guessing at a path, so the editor and the loader cannot disagree about where a chapter lives or
     * which files belong to it. A chapter the walk does not find, or one whose files cannot be read, is
     * an editor that reports itself unavailable rather than one that creates a second folder.
     *
     * @return the editor, or empty when this chapter cannot be edited from here
     */
    /**
     * Where a server's — or a client's — quest files live, under a config directory.
     *
     * <p>One expression, in the class both sides open chapters through, because there were two: the client's
     * session resolved the directory itself and the loader resolved it another way, and a client that reads
     * from a different place than the server writes to is a canvas that never shows the edit.
     */
    public static Path root(Path configDir) {
        return configDir.resolve(dev.ellipog.tasked.quest.QuestLoader.DIRECTORY);
    }

    public static Optional<QuestEditor> open(Path questRoot, String chapterId) {
        if (questRoot == null || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        QuestFiles.Discovery discovery;
        try {
            discovery = QuestFiles.discover(questRoot);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("tasked: the quest folder could not be walked, so the editor is"
                    + " unavailable.", e);
            return Optional.empty();
        }

        for (QuestFiles.Declaration declaration : discovery.declarations()) {
            if (declaration.kind() != QuestFiles.Kind.CHAPTER || !chapterId.equals(declaration.id())) {
                continue;
            }
            Path manifestPath = declaration.path();
            Path folder = manifestPath.getParent();
            try {
                JsonFile manifest = JsonFile.parse(manifestPath,
                        Files.readString(manifestPath, StandardCharsets.UTF_8));
                QuestEditor editor = new QuestEditor(questRoot, folder, manifest);
                editor.reloadQuests();
                return Optional.of(editor);
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tasked: {} could not be opened for editing.", manifestPath, e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Reads every quest file the manifest names. Called once at open; the editor owns them after. */
    private void reloadQuests() {
        quests.clear();
        for (String id : questIds()) {
            Path path = pathOf(id);
            try {
                quests.put(id, JsonFile.parse(path, Files.readString(path, StandardCharsets.UTF_8)));
            }
            catch (IOException | RuntimeException e) {
                // One unreadable file does not lose the rest of the chapter: it is left out of the
                // editor and named, so an author can see which file to fix rather than a blank canvas.
                Constants.LOG.warn("tasked: {} could not be read, so it is not open for editing.", path, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // What is open
    // ------------------------------------------------------------------

    /** The quest root every path here is relative to. */
    public Path root() {
        return root;
    }

    /** The chapter's folder. */
    public Path folder() {
        return folder;
    }

    /**
     * The chapter's own file, as text: its title, icon, rules and its quest list.
     *
     * <p>The manifest is a file like any other and the editor holds it open, so the replica can carry it
     * the same way it carries every quest -- one copy, one revision, one read-only rule.
     */
    public String chapterJson() {
        return manifest.json();
    }

    /** The quest ids, in the manifest's order — which for a linear chapter is the progression. */
    public List<String> questIds() {
        List<String> out = new ArrayList<>();
        for (String name : manifest.strings("quests")) {
            out.add(name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name);
        }
        return List.copyOf(out);
    }

    /** One quest's file, or empty for an id this chapter does not hold. */
    public JsonFile quest(String id) {
        return quests.get(id);
    }

    /** Where a quest's file lives. */
    public Path pathOf(String id) {
        return folder.resolve(id + SUFFIX);
    }

    /** Whether anything is unsaved, including a file that was added or removed. */
    public boolean dirty() {
        if (manifest.dirty()) {
            return true;
        }
        for (JsonFile quest : quests.values()) {
            if (quest.dirty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /**
     * Changes one field of one quest.
     *
     * <p>{@code value} is a {@code String}, a {@code Number}, a {@code Boolean} or a list of strings — the
     * shapes the format uses, the list being the ones that are a list of ids. Anything else is refused rather
     * than stringified, because a written file that the loader then refuses is worse than an edit that did
     * nothing.
     *
     * @param path a dotted path, e.g. {@code "icon.item"} or {@code "title"}
     */
    public boolean set(String id, String path, Object value) {
        JsonFile quest = quests.get(id);
        if (quest == null || path == null || path.isBlank()) {
            return false;
        }
        push();
        try {
            switch (value) {
                case String text -> quest.setText(path, text);
                case Number number -> quest.setNumber(path, number.doubleValue());
                case Boolean flag -> quest.setFlag(path, flag);
                case List<?> list -> quest.setStrings(path, list.stream().map(String::valueOf).toList());
                case JsonElement json -> quest.setJson(path, json);
                case null -> quest.remove(path);
                default -> {
                    undo.pop();
                    return false;
                }
            }
        }
        catch (JsonFile.UnwritablePath unwritable) {
            // Refused before anything changed, so the pushed snapshot is a lie and goes.
            undo.pop();
            return false;
        }
        return true;
    }

    /**
     * Changes one field of the chapter's own file.
     *
     * <p>The same shapes and the same rules as {@link #set}, against the manifest rather than a quest:
     * push, write, and a path nothing can be written to costs the pushed snapshot and nothing else.
     */
    public boolean setChapter(String path, Object value) {
        if (path == null || path.isBlank()) {
            return false;
        }
        push();
        try {
            switch (value) {
                case String text -> manifest.setText(path, text);
                case Number number -> manifest.setNumber(path, number.doubleValue());
                case Boolean flag -> manifest.setFlag(path, flag);
                case List<?> list -> manifest.setStrings(path, list.stream().map(String::valueOf).toList());
                case JsonElement json -> manifest.setJson(path, json);
                case null -> manifest.remove(path);
                default -> {
                    undo.pop();
                    return false;
                }
            }
        }
        catch (JsonFile.UnwritablePath unwritable) {
            undo.pop();
            return false;
        }
        return true;
    }

    /**
     * Inserts one entry -- a task, a reward -- into one of a quest's arrays.
     *
     * <p>The tree is the caller's, built from the type's own defaults or copied from a sibling, so the
     * model does not need to know what a task is: what lands in the file is what the loader will read
     * back, and validate-on-apply is what refuses a shape the format does not take.
     */
    public boolean insert(String id, String member, int index, JsonObject entry) {
        JsonFile quest = quests.get(id);
        if (quest == null || entry == null) {
            return false;
        }
        push();
        try {
            quest.insert(member, index, entry);
        }
        catch (JsonFile.UnwritablePath unwritable) {
            undo.pop();
            return false;
        }
        return true;
    }

    /** Removes one entry from one of a quest's arrays, by position. */
    public boolean removeEntry(String id, String member, int index) {
        JsonFile quest = quests.get(id);
        if (quest == null) {
            return false;
        }
        push();
        if (!quest.removeIndex(member, index)) {
            undo.pop();
            return false;
        }
        return true;
    }

    /** Moves one entry within its array, by position. */
    public boolean moveEntry(String id, String member, int from, int to) {
        JsonFile quest = quests.get(id);
        if (quest == null) {
            return false;
        }
        push();
        if (!quest.moveIndex(member, from, to)) {
            undo.pop();
            return false;
        }
        return true;
    }

    /** Moves one quest on the canvas. The one edit the canvas itself makes. */
    public boolean move(String id, double x, double y) {
        JsonFile quest = quests.get(id);
        if (quest == null) {
            return false;
        }
        if (quest.number("x", 0) == x && quest.number("y", 0) == y) {
            return false;
        }
        push();
        quest.setNumber("x", x);
        quest.setNumber("y", y);
        return true;
    }

    /**
     * Adds a quest, at the end of the chapter's list, and returns its id.
     *
     * <p>The id is derived from the title rather than asked for, because a new quest has no title yet
     * either: {@code quest}, {@code quest_2}, {@code quest_3}, whichever is free. It is a name, and the
     * property panel is where it gets a better one — the same argument as the party panel's derived party
     * name, one screen over.
     */
    public String create(double x, double y) {
        String id = freeId("quest");
        push();

        JsonObject root = new JsonObject();
        root.addProperty("$schema", schemaPath());
        root.addProperty("id", id);
        root.addProperty("title", "New quest");
        root.addProperty("x", Math.round(x));
        root.addProperty("y", Math.round(y));
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:paper");
        root.add("icon", icon);
        root.add("tasks", new com.google.gson.JsonArray());

        JsonFile created = JsonFile.of(pathOf(id), root);
        try {
            created.write();
        }
        catch (IOException e) {
            Constants.LOG.warn("tasked: {} could not be written, so no quest was created.",
                    pathOf(id), e);
            undo.pop();
            return null;
        }
        quests.put(id, created);
        manifest.addString("quests", id + SUFFIX);
        return id;
    }

    /**
     * Copies a quest, next to the original, and returns the new id.
     *
     * <p>The copy is the whole file with a new id and a name derived from it, placed a step to the right
     * so it is visibly a second node rather than one drawn on top of another. Dependencies are <b>not</b>
     * rewritten: a copy that depended on what the original depended on is what a person duplicating a
     * node means, and rewriting them would be the editor inventing an intent.
     */
    public String duplicate(String id) {
        JsonFile original = quests.get(id);
        if (original == null) {
            return null;
        }
        String copyId = freeId(id + "_copy");
        push();

        Path path = pathOf(copyId);
        JsonFile copy = JsonFile.parse(path, original.json());
        copy.setText("id", copyId);
        copy.setNumber("x", original.number("x", 0) + 48);
        try {
            copy.write();
        }
        catch (IOException e) {
            // Nothing was added to the chapter, so the undo entry for this attempt is a lie.
            Constants.LOG.warn("tasked: {} could not be written, so nothing was duplicated.", path, e);
            undo.pop();
            return null;
        }
        quests.put(copyId, copy);
        manifest.addString("quests", copyId + SUFFIX);
        return copyId;
    }

    /**
     * Inserts a quest's own tree, under a fresh id, at a canvas position.
     *
     * <p>The clipboard's op, and the reason it is one op rather than a create followed by field writes:
     * the tree is the file, object fields and all, and reconstructing it out of field writes would drop
     * every one the panel cannot carry. The id is the tree's own when it is free here and suffixed when
     * it is not, so pasting back into the chapter a copy came from still lands; a tree without an id
     * gets the same derived name a create gets.
     *
     * <p>The position is the paste point rather than the tree's own coordinates: pasting is putting it
     * where you are looking.
     *
     * @return the new id, or null when nothing was written
     */
    public String paste(JsonObject tree, double x, double y) {
        String base = tree.has("id") && tree.get("id").isJsonPrimitive()
                && !tree.get("id").getAsString().isBlank()
                ? tree.get("id").getAsString() : "quest";
        String id = freeId(base);
        push();

        Path path = pathOf(id);
        JsonFile pasted = JsonFile.of(path, tree.deepCopy());
        pasted.setText("id", id);
        pasted.setNumber("x", Math.round(x));
        pasted.setNumber("y", Math.round(y));
        try {
            pasted.write();
        }
        catch (IOException e) {
            // Nothing was added to the chapter, so the undo entry for this attempt is a lie.
            Constants.LOG.warn("tasked: {} could not be written, so nothing was pasted.", path, e);
            undo.pop();
            return null;
        }
        quests.put(id, pasted);
        manifest.addString("quests", id + SUFFIX);
        return id;
    }

    /**
     * Deletes a quest: its file, and its name from the list.
     *
     * <p>The file is <b>renamed</b> rather than deleted — to {@code <id>.json.deleted} — because a file
     * removed from a folder by a program is not recoverable, and an editor's Delete key is one keystroke
     * away from a mis-click. Undo puts it back, and the loader ignores the suffix, so a chapter with a
     * deleted quest in it still loads.
     */
    public boolean delete(String id) {
        JsonFile gone = quests.get(id);
        if (gone == null) {
            return false;
        }
        // Before anything moves: the snapshot is the state an undo has to put back, so it has to be
        // taken while the chapter still holds the quest. Removing first and snapshotting second is an
        // undo that restores the deletion.
        push();
        quests.remove(id);
        try {
            Files.move(gone.file(), gone.file().resolveSibling(id + SUFFIX + ".deleted"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            Constants.LOG.warn("tasked: {} could not be renamed out of the way, so nothing was deleted.",
                    gone.file(), e);
            quests.put(id, gone);
            undo.pop();
            return false;
        }
        manifest.removeString("quests", id + SUFFIX);
        return true;
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Puts the chapter back to how it was before the last change. */
    public boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        redo.push(snapshot());
        restore(undo.pop());
        return true;
    }

    /** The same, forward. */
    public boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        undo.push(snapshot());
        restore(redo.pop());
        return true;
    }

    /** Records the current state, and forgets the redo trail — a new edit is a new future. */
    private void push() {
        undo.push(snapshot());
        while (undo.size() > HISTORY) {
            undo.removeLast();
        }
        redo.clear();
    }

    private Snapshot snapshot() {
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(manifest.file(), manifest.json());
        for (JsonFile quest : quests.values()) {
            files.put(quest.file(), quest.json());
        }
        return new Snapshot(files);
    }

    /**
     * Puts a snapshot back.
     *
     * <p>The files on disk are the ones that make it true: a quest the snapshot holds but the folder does
     * not is written back (an undone delete), and one the folder holds but the snapshot does not is
     * renamed out of the way again (an undone create). Doing it through the disk rather than only in
     * memory is what makes the canvas, the loader and the files agree after an undo.
     */
    private void restore(Snapshot snapshot) {
        Map<Path, String> files = snapshot.files();

        for (Map.Entry<Path, String> entry : files.entrySet()) {
            if (Files.isRegularFile(entry.getKey())) {
                continue;
            }
            // Renamed away by a delete, so it comes back by being renamed back -- which is what makes an
            // undo restore the *file*, byte for byte and in the author's own formatting, rather than a
            // re-serialisation of it that happens to hold the same data. A file that is not there at all
            // (an undo across a session, or one somebody removed by hand) is written from the snapshot.
            Path deleted = entry.getKey().resolveSibling(entry.getKey().getFileName() + ".deleted");
            try {
                if (Files.isRegularFile(deleted)) {
                    Files.move(deleted, entry.getKey(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                else {
                    Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
                }
            }
            catch (IOException e) {
                Constants.LOG.warn("tasked: {} could not be restored by an undo.", entry.getKey(), e);
                return;
            }
        }

        for (JsonFile quest : List.copyOf(quests.values())) {
            if (!files.containsKey(quest.file())) {
                try {
                    Files.move(quest.file(), quest.file().resolveSibling(
                            quest.file().getFileName() + ".deleted"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                catch (IOException e) {
                    Constants.LOG.warn("tasked: {} could not be put back to deleted by an undo.",
                            quest.file(), e);
                }
            }
        }

        // And the in-memory trees follow the snapshot, which is what makes the state after an undo
        // identical to the state before the edit rather than merely close to it. Deliberately *not* marked
        // saved: the disk may still hold the edit being undone, and a tree marked clean is a file the next
        // save skips -- which is how an undo used to survive on the screen and vanish on reload. See
        // `JsonFile.replaceWith`, and the op tests that found it.
        manifest.replaceWith(files.get(manifest.file()));
        reloadQuests();
        for (JsonFile quest : quests.values()) {
            if (!files.containsKey(quest.file())) {
                continue;
            }
            quest.replaceWith(files.get(quest.file()));
        }
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /** What a save did: how many files it wrote, and what it refused to write them for. */
    public record SaveResult(int written, List<DataProblem> refused) {

        public boolean ok() {
            return refused.isEmpty();
        }

        /** One line per refusal, in the compiler format the loader's own messages use. */
        public List<String> messages() {
            return refused.stream().map(DataProblem::toString).toList();
        }
    }

    /**
     * Validates every changed file and writes them.
     *
     * <p>All or nothing: if any file has an error, none is written. Half a save is a chapter whose
     * manifest names a quest whose file the loader will refuse, which is a state the author then has to
     * untangle by hand — and the whole point of validating first is that this state is never reachable.
     *
     * <p>Every file is validated, not only the changed ones, because a file that was already broken on
     * disk is worth telling the author about at the moment they save — and because a validation that
     * skips files is a validation whose green result means nothing.
     */
    public SaveResult save() {
        Problems problems = new Problems();
        List<Path> toWrite = new ArrayList<>();

        validate(manifest.file().getFileName().toString(), manifest.json(), false, problems);
        if (manifest.dirty()) {
            toWrite.add(manifest.file());
        }

        for (Map.Entry<String, JsonFile> entry : quests.entrySet()) {
            validate(entry.getKey() + SUFFIX, entry.getValue().json(), true, problems);
            if (entry.getValue().dirty()) {
                toWrite.add(entry.getValue().file());
            }
        }

        List<DataProblem> errors = problems.all().stream()
                .filter(problem -> problem.severity() == DataProblem.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            return new SaveResult(0, errors);
        }

        int written = 0;
        for (Path path : toWrite) {
            try {
                JsonFile file = path.equals(manifest.file()) ? manifest : quests.get(idOf(path));
                if (file == null) {
                    continue;
                }
                file.write();
                written++;
            }
            catch (IOException e) {
                Constants.LOG.warn("tasked: {} could not be written.", path, e);
            }
        }
        return new SaveResult(written, List.of());
    }

    private void validate(String display, String text, boolean quest, Problems problems) {
        JsonDocument document;
        try {
            document = JsonDocument.parse(display, text);
        }
        catch (JsonParseException | RuntimeException e) {
            // The editor wrote something that is not JSON at all. That is a refusal like any other, and
            // it is reported in the loader's own shape -- a file, a position -- rather than as a crash.
            // `JsonParseException` is Armature's own checked one, so it has to be named here.
            problems.addAll(List.of(new DataProblem(display, 0, 0, "", DataProblem.Severity.ERROR,
                    "the editor wrote something that is not JSON: " + e.getMessage())));
            return;
        }
        if (quest) {
            QuestValidator.validateQuestDocument(document, problems);
        }
        else {
            QuestValidator.validateChapterDocument(document, problems);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** An id no file in this chapter uses, from a base name. */
    private String freeId(String base) {
        if (!quests.containsKey(base) && !Files.exists(pathOf(base))) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = base + "_" + n;
            if (!quests.containsKey(candidate) && !Files.exists(pathOf(candidate))) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis();
    }

    /**
     * Where a new quest's {@code $schema} points.
     *
     * <p>Derived from the chapter's own, by keeping its directory part and naming the quest schema — so a
     * new file points where its siblings do, whatever depth the chapter sits at. The examples in this
     * repository are inconsistent about that depth by one {@code ../}; the editor writes the version that
     * resolves, and touches no existing file.
     */
    private String schemaPath() {
        String chapters = manifest.text("$schema", "");
        int slash = chapters.lastIndexOf('/');
        if (slash < 0) {
            return QUEST_SCHEMA;
        }
        String prefix = chapters.substring(0, slash + 1);
        return prefix + QUEST_SCHEMA;
    }

    private String idOf(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
    }

    /** The shapes a quest node can be drawn as, for a control that cycles them. */
    public static List<String> shapes() {
        List<String> out = new ArrayList<>();
        for (QuestShape shape : QuestShape.values()) {
            out.add(shape.name().toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return "QuestEditor(" + folder + ", " + quests.size() + " quest(s)" + (dirty() ? ", unsaved" : "")
                + ")";
    }
}
