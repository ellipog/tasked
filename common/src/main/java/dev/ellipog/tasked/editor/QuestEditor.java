package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonParseException;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.ParsedFiles;
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
    /** The schema's file name, and the path to it from a chapter with no schema of its own. */
    private static final String QUEST_FILE = "quest.schema.json";
    private static final String QUEST_SCHEMA = "_schema/" + QUEST_FILE;

    /**
     * How many steps back the editor remembers. Deep enough for a session's worth of dragging.
     *
     * <p>Package-private rather than private, because a named table remembers the same number of steps
     * and used to carry its own copy of the literal: this is the depth, and {@link TableEditor} reads
     * it. An inline table is on this stack already, so there is nothing for it to keep of its own.
     */
    static final int HISTORY = 60;

    private final Path root;
    private final Path folder;
    private final JsonFile manifest;
    /** The group manifest beside this chapter's folder, or null when there is none to edit. */
    private final JsonFile group;
    private final Map<String, JsonFile> quests = new LinkedHashMap<>();

    /**
     * Declared id → the file name it is written under, for the quests whose two differ.
     *
     * <p>Absent for a quest whose file is named after its id, which is every fixture in this repository and
     * the ordinary case; present is what makes a file that declares its own id writable rather than a second
     * file created beside it. See {@link #reloadQuests} and {@link #pathOf}.
     */
    private final Map<String, String> stems = new LinkedHashMap<>();

    /**
     * The other direction: file name → the declared id of the quest in it.
     *
     * <p>So {@link #quest(String)} answers to the manifest's vocabulary as well as the editor's, which is the
     * lookup that kept the replica from carrying anything at all for a converted pack. Symmetric with
     * {@link #stems} and filled in the same pass, because the two are one fact read two ways.
     */
    private final Map<String, String> byStem = new LinkedHashMap<>();
    private final Deque<History> undo = new ArrayDeque<>();
    private final Deque<History> redo = new ArrayDeque<>();

    /** The meta of the last structural undo or redo. See {@link #takeLastMeta()}. */
    private QuestStructure.Structure.Meta lastMeta;

    /** Whether a {@link #group} is open: its mutations join that one history step. See {@link #push}. */
    private boolean grouping;

    /**
     * One step of this chapter's history.
     *
     * <h2>Two kinds, because two kinds of edit exist</h2>
     *
     * <p>A field edit and a create are "the chapter's files, as they were" — {@link Files}, which is the
     * snapshot this class has always kept. A structural edit — a chapter moved, renamed, duplicated,
     * deleted — is not describable as this chapter's files at all, so it carries the edit's own steps
     * ({@link QuestStructure.Structure}) and knows how to reverse and repeat itself.
     *
     * <p>One history rather than two, because Ctrl+Z must not care which kind of edit it is undoing:
     * the order the edits happened in is the order they are undone in, and splitting the stacks would
     * make the key depend on a question the player never asked.
     */
    private sealed interface History permits Snapshot, Structural {
    }

    /** The chapter's files, text by path. Everything an undo of a field edit has to put back. */
    private record Snapshot(Map<Path, String> files) implements History {
    }

    /** A structural edit, with the steps that reverse it. */
    private record Structural(QuestStructure.Structure structure) implements History {
    }

    private QuestEditor(Path root, Path folder, JsonFile manifest, JsonFile group) {
        this.root = root;
        this.folder = folder;
        this.manifest = manifest;
        this.group = group;
    }

    /**
     * Where the quest tree lives. The structural edits are about the tree above this chapter.
     *
     * <p>Named {@code treeRoot} rather than {@code root} because the static, config-taking
     * {@link #root(Path)} already owns that name, and overloading the two would put "the root under this
     * config directory" and "the root this editor was opened from" one argument apart.
     */
    public Path treeRoot() {
        return root;
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
                QuestEditor editor = new QuestEditor(questRoot, folder, manifest, openGroup(questRoot, folder));
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

    /**
     * The group manifest beside a chapter's folder, or null when there is none to edit.
     *
     * <h2>Beside, not looked up by id</h2>
     *
     * <p>The group's file is {@code group.json} in the chapter folder's parent -- the same adjacency the
     * loader reads a group from, so the editor and the loader cannot disagree about which file a group's
     * fields live in. A chapter with no such file beside it (an ungrouped chapter, or a version-1 file
     * the folder layout does not describe) simply has no group to edit, and an edit aimed at one is
     * refused rather than written somewhere else.
     *
     * <p>A group file that will not read is <b>null</b>, not a failed open: everything else in the
     * chapter is still editable, and only the group's own fields are out of reach.
     */
    private static JsonFile openGroup(Path root, Path chapterFolder) {
        Path parent = chapterFolder == null ? null : chapterFolder.getParent();
        // Strictly *under* the root, so a folder that is not a group folder's child -- a chapter at the
        // root, a version-1 flat file, a path that wandered out of the quest tree -- cannot have the file
        // next to it mistaken for its group's.
        if (parent == null || parent.equals(root) || !parent.startsWith(root)) {
            return null;
        }
        Path groupPath = parent.resolve(QuestFiles.GROUP_MANIFEST);
        if (!Files.isRegularFile(groupPath)) {
            return null;
        }
        try {
            return JsonFile.parse(groupPath, Files.readString(groupPath, StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("tasked: {} could not be read, so the group is not editable.", groupPath, e);
            return null;
        }
    }

    /** Reads every quest file the manifest names. Called once at open; the editor owns them after. */
    private void reloadQuests() {
        quests.clear();
        stems.clear();
        byStem.clear();
        for (String stem : questIds()) {
            Path path = pathOf(stem);
            try {
                JsonFile quest = JsonFile.parse(path, Files.readString(path, StandardCharsets.UTF_8));
                // **The key is the quest's own id, not its file name.** A file may declare `"id"` and the
                // format puts no rule on the two agreeing: the loader, the canvas, every reference and the
                // client's own `editTarget()` all know a quest by the id it declares, so the editor does
                // too -- and `stems` is what keeps the file it writes the right one. A pack generated by a
                // tool that assigns ids (the profile's imported one declares `58b556d40904e3b3` in
                // `first_tree.json`) is editable for the first time because of this; every fixture in this
                // repository names its file after its id, which is why nothing here caught it.
                String id = stem;
                if (quest.root().has("id") && quest.root().get("id").isJsonPrimitive()) {
                    String declared = quest.root().get("id").getAsString();
                    if (!declared.isBlank()) {
                        id = declared;
                    }
                }
                quests.put(id, quest);
                stems.put(id, stem);
                byStem.put(stem, id);
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

    /**
     * The group's own file, as text, or null when this chapter has no group file to edit.
     *
     * <p>The counterpart of {@link #chapterJson()}, for the one file above the chapter: the group the
     * chapter hangs under. Null is a real answer -- an ungrouped chapter, or one whose group file could
     * not be read -- and a caller shows no group rather than an empty one.
     */
    public String groupJson() {
        return group == null ? null : group.json();
    }

    /** The quest ids, in the manifest's order — which for a linear chapter is the progression. */
    public List<String> questIds() {
        List<String> out = new ArrayList<>();
        for (String name : manifest.strings("quests")) {
            out.add(name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name);
        }
        return List.copyOf(out);
    }

    /**
     * One quest's file, for <b>either</b> id this program has for it: the id it declares, or the file name
     * the chapter's manifest lists.
     *
     * <h2>Two vocabularies, one lookup</h2>
     *
     * <p>The edit side keys by the declared id — that is what the tree, the canvas and the client's own
     * `editTarget()` use — while the manifest lists <i>file names</i>, and the format puts no rule on the two
     * agreeing. Every fixture in this repository names its file after its id, so for a long time they were
     * the same string and one lookup answered both. A pack converted from FTB Quests keeps FTB's hex ids
     * while naming files after titles, and there the two part company: keying by the declared id alone left
     * the replica's own iteration — which reads the manifest — asking for `first_tree` and getting null, so
     * the panel received an <b>empty</b> copy and said the copy had not arrived.
     */
    public JsonFile quest(String id) {
        JsonFile direct = quests.get(id);
        if (direct != null) {
            return direct;
        }
        return quests.get(byStem.getOrDefault(id, id));
    }

    /** The ids of the quests this chapter holds, as the editor keys them: the ones the files declare. */
    public List<String> declaredIds() {
        return List.copyOf(quests.keySet());
    }

    /**
     * Where a quest's file lives, for <b>either</b> id: the declared one or the file's own name.
     *
     * <p>One resolution, in the one place a path is built, and that is deliberate: the read side keys by
     * the declared id while the manifest lists file names, so a write that took the id at face value would
     * look for {@code 58b556d40904e3b3.json} and create a second file for one quest -- the quiet version of
     * this bug, which is worse than the loud one.
     */
    public Path pathOf(String id) {
        return folder.resolve(stems.getOrDefault(id, id) + SUFFIX);
    }

    /** The file name stem a quest is written under, for a declared id or a stem. */
    public String stemOf(String id) {
        return stems.getOrDefault(id, id);
    }

    /** Whether anything is unsaved, including a file that was added or removed. */
    public boolean dirty() {
        if (manifest.dirty() || (group != null && group.dirty())) {
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
     * <p>{@code value} is a {@code String}, a {@code Number}, a {@code Boolean}, a list of strings — the
     * shapes the format uses, the list being the ones that are a list of ids — or a whole {@code JsonElement},
     * which is the raw path: a value this build has no shape for (an unknown type's entry, an object field)
     * is still the field's value, and stringifying it would write a file the loader refuses. Anything else is
     * refused rather than stringified, for the same reason.
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
     * Changes one field of the chapter's group's own file.
     *
     * <p>The same shapes and the same rules as {@link #setChapter}, against the group manifest rather
     * than the chapter's: the title the sidebar row shows, the icon it draws, and whether the group's
     * chapters start collapsed. False -- with the history untouched -- for a chapter with no group file
     * beside it, which is the honest refusal rather than a write to whichever file is nearest.
     */
    public boolean setGroup(String path, Object value) {
        if (group == null || path == null || path.isBlank()) {
            return false;
        }
        push();
        try {
            switch (value) {
                case String text -> group.setText(path, text);
                case Number number -> group.setNumber(path, number.doubleValue());
                case Boolean flag -> group.setFlag(path, flag);
                case List<?> list -> group.setStrings(path, list.stream().map(String::valueOf).toList());
                case JsonElement json -> group.setJson(path, json);
                case null -> group.remove(path);
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
     *
     * <p>{@code path} is a dotted path, so the same operation reaches an entry inside a reward's own
     * inline table ({@code rewards.2.inline.entries}) -- see {@link JsonFile#insert} for the one thing
     * such a path may create.
     */
    public boolean insert(String id, String path, int index, JsonObject entry) {
        JsonFile quest = quests.get(id);
        if (quest == null || entry == null) {
            return false;
        }
        push();
        try {
            quest.insert(path, index, entry);
        }
        catch (JsonFile.UnwritablePath unwritable) {
            undo.pop();
            return false;
        }
        return true;
    }

    /** Removes one entry from one of a quest's arrays, by position. */
    public boolean removeEntry(String id, String path, int index) {
        JsonFile quest = quests.get(id);
        if (quest == null) {
            return false;
        }
        push();
        if (!quest.removeIndex(path, index)) {
            undo.pop();
            return false;
        }
        return true;
    }

    /** Moves one entry within its array, by position. */
    public boolean moveEntry(String id, String path, int from, int to) {
        JsonFile quest = quests.get(id);
        if (quest == null) {
            return false;
        }
        push();
        if (!quest.moveIndex(path, from, to)) {
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
        lastMeta = null;
        if (undo.isEmpty()) {
            return false;
        }
        History history = undo.pop();
        if (history instanceof Structural structural) {
            // The structure carries both directions, so redo is the same record run forwards.
            QuestStructure.undo(structural.structure());
            redo.push(history);
            lastMeta = structural.structure().reverseMeta();
            return true;
        }
        redo.push(snapshotFiles());
        restore((Snapshot) history);
        return true;
    }

    /** The same, forward. */
    public boolean redo() {
        lastMeta = null;
        if (redo.isEmpty()) {
            return false;
        }
        History history = redo.pop();
        if (history instanceof Structural structural) {
            QuestStructure.redo(structural.structure());
            undo.push(history);
            lastMeta = structural.structure().forwardMeta();
            return true;
        }
        undo.push(snapshotFiles());
        restore((Snapshot) history);
        return true;
    }

    /**
     * Records the current state, and forgets the redo trail — a new edit is a new future.
     *
     * <p>Silent while a {@link #group} is open: that block took the one snapshot the whole group costs,
     * and a second one per mutation inside it is precisely the seventy-step history this class grew
     * {@code group} to stop writing.
     */
    private void push() {
        if (grouping) {
            return;
        }
        undo.push(snapshotFiles());
        trim();
        redo.clear();
    }

    /**
     * Records a structural edit on this history, so Ctrl+Z reaches it.
     *
     * <p>Called by {@link EditorOps} after {@link QuestStructure} has already performed the edit: the
     * structure is the record of what happened, not a plan, which is why it is pushed rather than run.
     *
     * <p>Refused while a {@link #group} is open, and by throwing rather than by joining it: a structural
     * edit is not covered by a snapshot of one chapter's files, so a group that reached here would be a
     * history step that undoes less than it claims. {@code EditorOps} refuses a batch containing one
     * before the group is opened, so this is a bug-finder for a future caller, not a path a player can
     * reach.
     */
    void record(QuestStructure.Structure structure) {
        if (grouping) {
            throw new IllegalStateException("a structural edit cannot join a group: the group's snapshot"
                    + " is this chapter's files, and a structure is the tree's");
        }
        undo.push(new Structural(structure));
        trim();
        redo.clear();
    }

    /**
     * Runs several chapter edits as <b>one</b> history step.
     *
     * <h2>What this is for</h2>
     *
     * <p>A gesture is not an operation: duplicating seventy selected quests is one act, and seventy
     * snapshots made it seventy presses of Ctrl+Z to take back. So the snapshot is taken <b>here</b>,
     * once, and every mutation inside the block finds {@link #push} already satisfied and does not take
     * another. Undo restores the files as they were before the whole block, which is exactly what "undo
     * that gesture" means — and it works for the file-level mutations (a create, a duplicate, a delete)
     * because {@link #restore} puts the *disk* back, not only the trees.
     *
     * <p>Cannot nest, and the flag is cleared in a {@code finally}: a nested group's snapshot would be a
     * step inside a step, and a group abandoned by an exception must not leave every later edit silently
     * without a history entry.
     */
    void group(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (grouping) {
            throw new IllegalStateException("a group cannot nest: one snapshot is the whole point");
        }
        push();
        grouping = true;
        try {
            work.run();
        }
        finally {
            grouping = false;
        }
    }

    /**
     * Records the chapter's files as they are, for a caller that is about to change one of them.
     *
     * <p>{@link #push} is private because every mutation in this class does its own; this is the door
     * for {@code TableEditor}, whose inline drafts edit a quest file through this editor and must land
     * in this history rather than a second one — an inline table <i>is</i> a quest field, so its undo
     * is the chapter's, and Ctrl+Z after a weight edit is the same key as after a title edit.
     */
    void pushHistory() {
        push();
    }

    /** Puts the last step back, for the same caller. */
    boolean undoHistory() {
        return undo();
    }

    /**
     * Puts the last step back and forgets it entirely: what a refused edit costs.
     *
     * <h2>Why this is not {@link #undo}</h2>
     *
     * <p>Because undo <b>remembers</b> the state it left — that is what redo is for — so a refused save
     * that called {@code undo()} put the refused, mutated state on the redo trail, and the next Ctrl+Y
     * brought it back into memory as though the validator had allowed it: a value the loader had just
     * refused, drawn on the screen until the next edit saved over it. An edit that refused must be as if
     * it never happened, so this restores the snapshot and touches no trail. It is also what makes
     * {@code EditorOps.history}'s promise — "the history is left as it was found" — true rather than
     * nearly true.
     *
     * <p>A structural step is left where it is: only a snapshot of this chapter's files can be abandoned
     * this way, and nothing abandons a structure (its own steps are what reverse it, and they are not
     * reached by a refusal).
     */
    void abandon() {
        if (undo.isEmpty()) {
            return;
        }
        History history = undo.pop();
        if (history instanceof Snapshot snapshot) {
            restore(snapshot);
            return;
        }
        undo.push(history);
    }

    /** The same, forward. */
    boolean redoHistory() {
        return redo();
    }

    /**
     * Drops the last recorded step, for a change that did not happen.
     *
     * <p>The counterpart of the {@code undo.pop()} every mutation in this class does when a write is
     * refused before it changed anything: a snapshot of a state that was never left is a lie, and an
     * undo that restored it would look like it did something.
     */
    void dropHistory() {
        if (!undo.isEmpty()) {
            undo.pop();
        }
    }

    /**
     * Takes another editor's history, when a structural edit moved this chapter to a new folder.
     *
     * <p>An editor is bound to a folder, so the chapter that moved is re-opened at its new path — and a
     * fresh editor has an empty history, which would make Ctrl+Z after a rename do nothing. The steps
     * are still valid because they name the paths as they were at each edit; undoing past the move
     * reverses the move first, which is what makes the older paths real again.
     */
    void adopt(QuestEditor other) {
        undo.clear();
        redo.clear();
        undo.addAll(other.undo);
        redo.addAll(other.redo);
    }

    /**
     * The meta of the structural step last reversed or repeated, taken once.
     *
     * <p>Read by {@link EditorOps} right after an undo or a redo, so the caller can re-key its editor
     * cache to wherever the chapter now is. One-shot, because it describes one step: leaving it set would
     * make the next question about a later undo read the previous answer.
     */
    QuestStructure.Structure.Meta takeLastMeta() {
        QuestStructure.Structure.Meta meta = lastMeta;
        lastMeta = null;
        return meta;
    }

    /**
     * Re-reads this chapter's own files, after a structural edit rewrote one of them.
     *
     * <p>Those edits write manifests this editor is holding in memory: a group's {@code chapters} list,
     * the chapter's own manifest. Without this the next field edit would save the pre-edit copy back over
     * the structural change — the two sides of the model disagreeing, which is the one state this design
     * does not allow.
     */
    void refresh() {
        try {
            if (Files.isRegularFile(manifest.file())) {
                manifest.replaceWith(Files.readString(manifest.file(), StandardCharsets.UTF_8));
            }
            if (group != null && Files.isRegularFile(group.file())) {
                group.replaceWith(Files.readString(group.file(), StandardCharsets.UTF_8));
            }
            reloadQuests();
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("tasked: a chapter could not be re-read after a structural edit.", e);
        }
    }

    private void trim() {
        while (undo.size() > HISTORY) {
            undo.removeLast();
        }
    }

    private Snapshot snapshotFiles() {
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(manifest.file(), manifest.json());
        if (group != null) {
            files.put(group.file(), group.json());
        }
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
        // Everything, not one file: an undo puts a whole chapter back, which can have moved, renamed,
        // recreated or set aside any of its files -- so what is on disk afterwards is a different *set*
        // of files rather than different contents in the ones that were read. See ParsedFiles.
        ParsedFiles.clear();
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
                    // The snapshot's text, verbatim, through JsonWrite rather than a plain writeString.
                    // An undo is the one moment an author is already recovering from something, so a
                    // restore that could itself be cut short would turn a mistake they can undo into one
                    // they cannot -- and the bytes are identical either way, so the byte-for-byte promise
                    // this path makes to QuestEditorTest is unchanged.
                    JsonWrite.atomically(entry.getKey(), entry.getValue());
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
        if (group != null && files.containsKey(group.file())) {
            group.replaceWith(files.get(group.file()));
        }
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

        validate(manifest.file().getFileName().toString(), manifest.json(), DocumentKind.CHAPTER, problems);
        if (manifest.dirty()) {
            toWrite.add(manifest.file());
        }

        // The group's file, when this chapter has one. Validated against the group's own rules -- the
        // reader's rules, not a chapter's -- and written in the same all-or-nothing pass as everything
        // else: a save that half-wrote a chapter and its group would leave the pair disagreeing.
        if (group != null) {
            validate(root.relativize(group.file()).toString(), group.json(), DocumentKind.GROUP, problems);
            if (group.dirty()) {
                toWrite.add(group.file());
            }
        }

        for (Map.Entry<String, JsonFile> entry : quests.entrySet()) {
            validate(entry.getKey() + SUFFIX, entry.getValue().json(), DocumentKind.QUEST, problems);
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
        List<DataProblem> failures = new ArrayList<>();
        for (Path path : toWrite) {
            try {
                JsonFile file = path.equals(manifest.file()) ? manifest
                        : group != null && path.equals(group.file()) ? group
                        : quests.get(idOf(path));
                if (file == null) {
                    continue;
                }
                file.write();
                written++;
            }
            catch (IOException e) {
                // Reported as a refusal, not only logged. `SaveResult.ok()` is `refused.isEmpty()`, so a
                // swallowed IOException told the player their edit had landed while the file was not
                // written. Silence is the one answer that is wrong here: the author is about to close the
                // editor.
                //
                // What a failed write now means is narrower than it was, and the message says the
                // narrower thing. `JsonFile.write` goes through `JsonWrite`, so the target is either its
                // old content or its new one and never a prefix of either -- a failure leaves the file
                // exactly as it was, rather than truncated as it would have been when this wrote through
                // a plain `Files.writeString`. The edit is not lost either: the tree in memory is still
                // dirty, so a second save retries it.
                Constants.LOG.warn("tasked: {} could not be written.", path, e);
                failures.add(new DataProblem(root.relativize(path).toString(), 1, 1, "$",
                        DataProblem.Severity.ERROR, "could not be written: " + e
                        + "\n    nothing on disk was changed, and the edit is still in memory, so saving"
                        + " again retries it"));
            }
        }
        return new SaveResult(written, List.copyOf(failures));
    }

    /** Which document a file is, so the validator reads it with the right rules. */
    private enum DocumentKind { QUEST, CHAPTER, GROUP }

    private void validate(String display, String text, DocumentKind kind, Problems problems) {
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
        switch (kind) {
            case QUEST -> QuestValidator.validateQuestDocument(document, problems);
            case CHAPTER -> QuestValidator.validateChapterDocument(document, problems);
            case GROUP -> QuestValidator.validateGroupDocument(document, problems);
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
        // The prefix already ends in the schema directory -- "../../_schema/" -- so only the file name
        // is appended. Appending the whole path here wrote "_schema/_schema/quest.schema.json" into
        // every quest created in game, which resolves to nothing; it was found in a player's profile
        // before it was found here, which is the wrong order for a two-line bug.
        String prefix = chapters.substring(0, slash + 1);
        return prefix + QUEST_FILE;
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
