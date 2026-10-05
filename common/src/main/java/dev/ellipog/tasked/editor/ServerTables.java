package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.loot.InlineTables;
import dev.ellipog.tasked.quest.loot.RewardTable;
import dev.ellipog.tasked.quest.loot.RewardTableRefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The tables a server has open, and the ops applied to them.
 *
 * <h2>The counterpart of {@link ServerEditors}, with one more job</h2>
 *
 * <p>It caches a draft per named file for the same reason that class caches an editor per chapter: an
 * undo history lives <i>in</i> a draft, and a draft opened per request would undo nothing. The extra job
 * is <b>resolution</b>: a table is named either by a file's id or by a handle inside a quest or another
 * table, and only this class knows how to turn either into a file and a prefix — and how to say why it
 * could not.
 *
 * <h2>Everything a table op needs is injected</h2>
 *
 * <p>The root, the loaded tables and the quest index arrive as suppliers rather than being read from
 * {@code TaskedQuests} directly, so a test can hand this three tables in a temporary folder and a quest
 * index it built itself. That is the difference between testing the delete guard and hoping.
 */
public final class ServerTables {

    private final ServerEditors editors;
    private final Supplier<Path> root;
    private final Supplier<Map<String, RewardTable>> loaded;
    private final Supplier<QuestIndex> index;

    /** The named tables open for editing, one draft per file. */
    private final Map<String, TableEditor> open = new LinkedHashMap<>();

    public ServerTables(ServerEditors editors, Supplier<Path> root,
                        Supplier<Map<String, RewardTable>> loaded, Supplier<QuestIndex> index) {
        this.editors = editors;
        this.root = root;
        this.loaded = loaded;
        this.index = index;
    }

    /** Forgets every open draft: called by {@code /tasked reload}, which is when the files may differ. */
    public void forget() {
        open.clear();
    }

    /** Whether the loader has a table with this id, for a caller that must refuse a typo first. */
    public boolean exists(String id) {
        return id != null && loaded.get().containsKey(id);
    }

    /** The named tables the loader has, for a listing. */
    public Set<String> ids() {
        return new LinkedHashSet<>(loaded.get().keySet());
    }

    /** A named table's file as text, for a replica. Empty when it cannot be read. */
    public String replica(String id) {
        Path file = fileOf(id);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException unreadable) {
            Constants.LOG.warn("tasked: reward_tables/{}.json could not be read for a client.", id, unreadable);
            return "";
        }
    }

    /**
     * The table an address names, decoded, for a caller that wants to read it rather than edit it.
     *
     * <p>Empty for a refusal as well as for a table that is not there: the caller is a roll report or a
     * listing, and both say the same sentence about either.
     */
    public Optional<RewardTable> resolveTable(TableAddress address) {
        Resolved resolved = resolve(address);
        if (resolved.refusal() != null || resolved.editor() == null) {
            return Optional.empty();
        }
        JsonObject root = resolved.editor().root();
        if (root == null) {
            return Optional.empty();
        }
        return RewardTable.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, root).result();
    }

    /**
     * Appends entries to a table as <b>one</b> edit.
     *
     * <p>What an import is, from either direction: one snapshot, one validated save, one reload, and one
     * Ctrl+Z for the whole thing. An op per item would be a write and a history step each — the
     * difference between a feature and a way to make the undo stack useless.
     */
    public EditorOps.Applied importInto(TableAddress address, List<JsonObject> entries) {
        Resolved resolved = resolve(address);
        if (resolved.refusal() != null) {
            return EditorOps.Applied.refused(resolved.refusal());
        }
        if (entries.isEmpty()) {
            return EditorOps.Applied.refused("there was nothing in there to import");
        }
        TableEditor editor = resolved.editor();
        int at = editor.entries().size();
        return finish(editor, editor.insertBatch(at, entries) > 0, address);
    }

    /** Where a named table lives. */
    public Path fileOf(String id) {
        return root.get().resolve(dev.ellipog.tasked.quest.QuestFiles.REWARD_TABLES_DIRECTORY)
                .resolve(id + ".json");
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * Applies one table op, validating on the way.
     *
     * <p>A refusal — a table that is not there, an edit the validator rejects — is an
     * {@link EditorOps.Applied} with {@code ok} false and a sentence, never an exception: this runs on
     * the server thread with a player's message in its hand.
     */
    public EditorOps.Applied apply(TableOp op) {
        if (op == null) {
            return EditorOps.Applied.refused("that is not a table edit this version knows");
        }
        try {
            return applyOne(op);
        }
        catch (RuntimeException unexpected) {
            Constants.LOG.warn("tasked: applying a table edit failed", unexpected);
            return EditorOps.Applied.refused("that edit could not be applied");
        }
    }

    private EditorOps.Applied applyOne(TableOp op) {
        return switch (op) {
            case TableOp.Set set -> {
                Resolved resolved = resolve(set.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                yield finish(resolved.editor(), resolved.editor().set(set.path(), set.value()), set.address());
            }
            case TableOp.SetFields fields -> {
                Resolved resolved = resolve(fields.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                if (fields.fields() == null) {
                    yield EditorOps.Applied.refused("that edit has no fields in it");
                }
                java.util.Map<String, JsonElement> writes = new LinkedHashMap<>();
                for (var field : fields.fields().entrySet()) {
                    writes.put(field.getKey(), field.getValue());
                }
                yield finish(resolved.editor(), resolved.editor().setAll(writes), fields.address());
            }
            case TableOp.Insert insert -> {
                Resolved resolved = resolve(insert.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                if (insert.entry() == null) {
                    yield EditorOps.Applied.refused("that edit has no entry in it");
                }
                yield finish(resolved.editor(), resolved.editor().insert(insert.index(), insert.entry()),
                        insert.address());
            }
            case TableOp.Remove remove -> {
                Resolved resolved = resolve(remove.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                yield finish(resolved.editor(), resolved.editor().remove(remove.index()), remove.address());
            }
            case TableOp.Move move -> {
                Resolved resolved = resolve(move.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                yield finish(resolved.editor(), resolved.editor().move(move.from(), move.to()), move.address());
            }
            case TableOp.Undo undo -> {
                Resolved resolved = resolve(undo.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                if (!resolved.editor().undo()) {
                    yield EditorOps.Applied.refused("there is nothing to undo in this table");
                }
                // An undo puts the model back; the save is what makes the disk agree with it, and a
                // refusal here redoes the step so the two are never left disagreeing.
                QuestEditor.SaveResult saved = resolved.editor().save(loaded.get());
                if (!saved.ok()) {
                    resolved.editor().redo();
                    yield new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
                }
                yield EditorOps.Applied.changed(null, null, List.of());
            }
            case TableOp.Redo redo -> {
                Resolved resolved = resolve(redo.address());
                if (resolved.refusal() != null) {
                    yield EditorOps.Applied.refused(resolved.refusal());
                }
                if (!resolved.editor().redo()) {
                    yield EditorOps.Applied.refused("there is nothing to redo in this table");
                }
                QuestEditor.SaveResult saved = resolved.editor().save(loaded.get());
                if (!saved.ok()) {
                    resolved.editor().undo();
                    yield new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
                }
                yield EditorOps.Applied.changed(null, null, List.of());
            }
            case TableOp.Create create -> create(create);
            case TableOp.Duplicate duplicate -> duplicate(duplicate);
            case TableOp.Delete delete -> delete(delete);
            case TableOp.Select select -> select(select);
        };
    }

    /** Saves what an edit changed, or undoes it — the rule every op in this mod follows. */
    private EditorOps.Applied finish(TableEditor editor, boolean changed, TableAddress address) {
        if (!changed) {
            return EditorOps.Applied.refused("that edit would change nothing, or could not be written");
        }
        QuestEditor.SaveResult saved = editor.save(loaded.get());
        if (!saved.ok()) {
            editor.undo();
            return new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
        }
        return EditorOps.Applied.changed(null, null, List.of());
    }

    // ------------------------------------------------------------------
    // The ops that are not a field write
    // ------------------------------------------------------------------

    /**
     * A new file, from the table the caller wrote.
     *
     * <p>Refused when the id is taken — including by a file the loader refused, because a name that is
     * already on disk is a name an author would lose work by reusing.
     */
    private EditorOps.Applied create(TableOp.Create create) {
        if (create.id() == null || create.id().isBlank() || create.root() == null) {
            return EditorOps.Applied.refused("a new table needs a name and a table");
        }
        if (!freeId(create.id())) {
            return EditorOps.Applied.refused("there is already a table called \"" + create.id() + "\"");
        }
        TableEditor fresh = TableEditor.fresh(fileOf(create.id()), create.id(), create.root());
        QuestEditor.SaveResult saved = fresh.save(loaded.get());
        if (!saved.ok()) {
            return new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
        }
        open.put(create.id(), fresh);
        // A sentence on success, so the author hears what happened from the side that did it. The client
        // stopped saying it optimistically: a line said before the answer can be a lie, and "removed" over
        // a table that is still there is exactly the lie the first version told.
        return new EditorOps.Applied(true, create.id(),
                List.of("made table \"" + create.id() + "\""), null, null, List.of());
    }

    /**
     * A copy of a file, with every handle inside it re-minted.
     *
     * <p>Unconditionally, which is the one place that rule is right: nothing addresses the copy's
     * tables yet, and a copied file that kept its handles would collide with the original the moment a
     * reward from each was materialised into one quest.
     */
    private EditorOps.Applied duplicate(TableOp.Duplicate duplicate) {
        if (!exists(duplicate.id())) {
            return EditorOps.Applied.refused("no reward table named \"" + duplicate.id() + "\"");
        }
        if (duplicate.newId() == null || duplicate.newId().isBlank() || !freeId(duplicate.newId())) {
            return EditorOps.Applied.refused("\"" + duplicate.newId() + "\" is not a free table name");
        }
        Optional<TableEditor> source = draft(duplicate.id());
        if (source.isEmpty()) {
            return EditorOps.Applied.refused("reward_tables/" + duplicate.id() + ".json could not be read");
        }
        JsonObject copy = source.get().root().deepCopy();
        InlineTables.remintAll(copy);
        TableEditor fresh = TableEditor.fresh(fileOf(duplicate.newId()), duplicate.newId(), copy);
        QuestEditor.SaveResult saved = fresh.save(loaded.get());
        if (!saved.ok()) {
            return new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
        }
        open.put(duplicate.newId(), fresh);
        return new EditorOps.Applied(true, duplicate.newId(),
                List.of("copied \"" + duplicate.id() + "\" to \"" + duplicate.newId() + "\""),
                null, null, List.of());
    }

    /**
     * A file renamed out of the way, refused while anything still points at it.
     *
     * <p>The guard is the whole point: a delete is one press, and every reward or table that named it
     * would be a dangling reference the next time the folder is read — a reward that pays nothing, with
     * the reason in another file. The refusal names the referrers, so the author can go and look.
     */
    private EditorOps.Applied delete(TableOp.Delete delete) {
        if (!exists(delete.id())) {
            return EditorOps.Applied.refused("no reward table named \"" + delete.id() + "\"");
        }
        List<String> referrers = referrers(delete.id());
        if (!referrers.isEmpty()) {
            return EditorOps.Applied.refused("this table is still used by " + String.join(", ", referrers)
                    + " - point those somewhere else first, or delete it by hand");
        }
        try {
            Path file = fileOf(delete.id());
            // Renamed rather than removed, the way a quest is: a file deleted by a program is not
            // recoverable, and a press is one keystroke away from a mis-click. REPLACE_EXISTING because
            // the tombstone of an earlier delete of the same name is worth less than this one.
            Files.move(file, file.resolveSibling(delete.id() + ".json.deleted"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException failed) {
            return EditorOps.Applied.refused("reward_tables/" + delete.id() + ".json could not be moved"
                    + " out of the way: " + failed.getMessage());
        }
        open.remove(delete.id());
        return new EditorOps.Applied(true, null,
                List.of("removed \"" + delete.id() + "\" - the file is beside it as .json.deleted"),
                null, null, List.of());
    }

    /**
     * Points a reward at a named table, or clears it.
     *
     * <p>One edit: the reference is set and the inline copy a reward may be carrying is removed in the
     * same push, so a reward can never be left holding both — which is a state the loader reads one way
     * and an author would read the other.
     */
    private EditorOps.Applied select(TableOp.Select select) {
        TableEditor holder = holder(select.owner(), select.owningPath());
        if (holder == null) {
            return EditorOps.Applied.refused("that reward could not be found to point at a table");
        }
        String id = select.tableId();
        if (id != null && !id.isBlank() && !exists(id)) {
            return EditorOps.Applied.refused("no reward table named \"" + id + "\"");
        }
        Map<String, JsonElement> fields = new LinkedHashMap<>();
        fields.put("table", id == null || id.isBlank() ? null : new com.google.gson.JsonPrimitive(id));
        fields.put("inline", null);
        boolean changed = holder.setAll(fields);
        if (!changed) {
            return EditorOps.Applied.refused("that reward could not be written to");
        }
        QuestEditor.SaveResult saved = holder.save(loaded.get());
        if (!saved.ok()) {
            holder.undo();
            return new EditorOps.Applied(false, null, saved.messages(), null, null, List.of());
        }
        return new EditorOps.Applied(true, null,
                List.of(id == null || id.isBlank()
                        ? "this reward rolls no table now"
                        : "this reward now rolls \"" + id + "\""),
                null, null, List.of());
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** A draft, or the sentence that says why there is none. */
    private record Resolved(TableEditor editor, String refusal) {
    }

    /**
     * The draft an address names.
     *
     * <p>An address is a named table's file: the panels edit tables that live in files, and a table
     * written inline in a reward is reached through its <i>owner</i> — a `Select` on the reward, which
     * is the op that replaces one.
     */
    private Resolved resolve(TableAddress address) {
        if (address == null) {
            return new Resolved(null, "that edit names no table");
        }
        return switch (address.owner()) {
            case TableAddress.Owner.Named named -> namedAt(named.id());
            // A quest's own table is a reward's field, not a table the editor addresses: `Select` and
            // the browser reach it through its owner, and no operation carries a table address for one.
            case TableAddress.Owner.InQuest quest -> new Resolved(null,
                    "that is a reward's table reference rather than a table of its own");
        };
    }

    private Resolved namedAt(String id) {
        Optional<TableEditor> draft = draft(id);
        if (draft.isEmpty()) {
            return new Resolved(null, exists(id)
                    ? "reward_tables/" + id + ".json could not be read"
                    : "no reward table named \"" + id + "\"");
        }
        return new Resolved(draft.get(), null);
    }

    /**
     * A draft over the reward that owns a table, for the ops that write the owner rather than the table.
     *
     * <p>Null when the owner's file cannot be found — the caller's refusal, because the sentence depends
     * on which op was asking.
     */
    private TableEditor holder(TableAddress.Owner owner, String owningPath) {
        if (owner == null || owningPath == null || owningPath.isBlank()) {
            return null;
        }
        return switch (owner) {
            case TableAddress.Owner.Named named -> draft(named.id())
                    .map(editor -> editor.at(owningPath))
                    .orElse(null);
            case TableAddress.Owner.InQuest quest -> editors.open(quest.chapter())
                    .filter(chapter -> chapter.quest(quest.quest()) != null)
                    .map(chapter -> TableEditor.inQuest(chapter, quest.quest(), owningPath))
                    .orElse(null);
        };
    }

    /** The open draft for a named table, opening it if it is not open. */
    private Optional<TableEditor> draft(String id) {
        TableEditor open = this.open.get(id);
        if (open != null) {
            return Optional.of(open);
        }
        Optional<TableEditor> parsed = TableEditor.named(fileOf(id), id);
        parsed.ifPresent(editor -> open(id, editor));
        return parsed;
    }

    private void open(String id, TableEditor editor) {
        this.open.put(id, editor);
    }

    /** Whether a table name is free: not loaded, and not a file on disk either. */
    private boolean freeId(String id) {
        return !exists(id) && !Files.exists(fileOf(id));
    }

    /**
     * Who still points at a table: a quest reward or another table, named by file and path.
     *
     * <p>Read from the index and the loaded tables rather than from the files, so it is the same
     * information the loader would refuse the pack for — and the same walk
     * ({@link RewardTableRefs}) that finds a reference inside an inline table.
     *
     * <p>Public because two callers want it now: the delete guard, which refuses with these names, and
     * the replica, which sends them to the editor that is looking at the table. One walk, so an author
     * cannot be told two different sets of names about one file.
     */
    public List<String> referrers(String id) {
        List<String> referrers = new ArrayList<>();
        QuestIndex quests = index == null ? null : index.get();
        if (quests != null) {
            for (QuestIndex.QuestEntry entry : quests.quests()) {
                for (RewardTableRefs.Ref ref : RewardTableRefs.refsOf(entry.quest().rewards(), "$.rewards")) {
                    if (ref.id().equals(id)) {
                        referrers.add("the quest \"" + entry.quest().id() + "\"");
                    }
                }
            }
        }
        for (Map.Entry<String, RewardTable> table : loaded.get().entrySet()) {
            for (RewardTableRefs.Ref ref : RewardTableRefs.refsOf(table.getValue(), "$")) {
                if (ref.id().equals(id)) {
                    referrers.add("reward_tables/" + table.getKey() + ".json");
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(referrers));
    }

}
