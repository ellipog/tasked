package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One edit, as somebody asks for it.
 *
 * <h2>Why an edit is data</h2>
 *
 * <p>Because the files are on the server's disk and the panel is on a player's screen, so the same class has
 * to describe an edit on both sides of that wire. A record per kind — rather than a method to call over a
 * network, which is not a thing — is what makes an op writable down, readable back, refusable, and testable
 * without a client: {@link EditorOps} writes, reads and applies them, and {@link QuestEditor} is the model
 * underneath, the same class on both sides.
 *
 * <p>The kinds are exactly the model's public edits and no more: a field, a position, a new quest, a copy, a
 * delete, and the two halves of undo. A panel that wants to do something not in this list would be growing a
 * second way to change a file, which is the thing T9's notes spent a table on avoiding.
 *
 * <h2>Sealed, so the compiler holds the list</h2>
 *
 * <p>Every switch over an {@code EditorOp} is exhaustive, which means a new kind cannot be added without the
 * codec and the applier being told. That is not decoration: this project has already paid once for a value
 * that fell through a dispatch into the wrong branch and drew a control as a label — {@code ToolsPanel}
 * dispatches on a row's kind with a throwing default for the same reason.
 */
public sealed interface EditorOp {

    /** The quest this op is about, or null for the ones that are about the chapter. */
    default String quest() {
        return null;
    }

    /**
     * Sets one field of one quest.
     *
     * <p>The value is the JSON the field holds — a string, a number, a flag, a list of strings, or a whole
     * JSON value (an object, or an array that is not a list of ids), which is the raw-editing path a task or
     * reward of an unknown type crosses on. A null value removes the field, which is how a panel clears one
     * rather than writing an empty string into it.
     */
    record SetField(String id, String path, JsonElement value) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /** Puts one quest at a canvas position. The one edit the canvas itself makes. */
    record Move(String id, double x, double y) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /** A new quest at a position, whose id the server chooses. */
    record Create(double x, double y) implements EditorOp {
    }

    /** A copy of one quest, under a fresh id. */
    record Duplicate(String id) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /**
     * A quest's own tree, inserted into this chapter under a fresh id, at a canvas position.
     *
     * <p>The clipboard's op, and the reason it is one op rather than a create followed by field writes:
     * the tree <b>is</b> the file, object fields and all, and a reconstruction out of field writes would
     * drop every one the panel cannot carry -- which is the loss the clipboard exists to not have. The
     * id is the server's to choose, as with a create: the tree's own id is kept when it is free and
     * suffixed when it is not, so pasting into the chapter a copy came from still lands.
     */
    record Paste(JsonObject tree, double x, double y) implements EditorOp {
    }

    /**
     * Inserts one entry into one of a quest's arrays: a task, a reward.
     *
     * <p>The tree travels, like a paste's, so the model does not need to know what a task is -- the
     * caller builds it from the type's own defaults or copies a sibling, and validate-on-apply is what
     * refuses a shape the format does not take. {@code member} is the array's name ("tasks",
     * "rewards"); {@code index} is clamped to the array's length, so appending is {@code size()}.
     */
    record Insert(String id, String member, int index, JsonObject entry) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /** Removes one entry from one of a quest's arrays, by position. */
    record Remove(String id, String member, int index) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /**
     * Sets one field of the chapter's own file: its title, icon, rules.
     *
     * <p>Separate from {@link SetField} because a chapter is not a quest: there is no quest id to
     * carry, and the model writes a different file. Same value shapes, same validate-on-apply.
     */
    record SetChapter(String path, JsonElement value) implements EditorOp {
    }

    /**
     * Sets one field of the chapter's <b>group's</b> own file: its title, its icon, its collapsed flag.
     *
     * <h2>Why this carries no group id</h2>
     *
     * <p>An editor session is open on one chapter, and the only group it has a file for is the one that
     * chapter hangs under -- so an id here could only ever name a group the session cannot reach, which
     * is the same reason {@link SetChapter} names no chapter. A server whose chapter is not in a group,
     * or whose group file could not be opened, refuses this; the model says which of the two by refusing
     * at all rather than writing somewhere else.
     */
    record SetGroup(String path, JsonElement value) implements EditorOp {
    }

    /**
     * Writes one key of the tree's own settings block in {@code index.json}: the pack's book name and
     * icon, today.
     *
     * <p>A key and a value rather than a whole settings object, so the edit writes exactly what it
     * changed and every other declaration in the block — and every unknown root key beside it — is
     * carried over untouched. A null value removes the key. The chapter context the op arrives with is
     * ignored: the block belongs to the root, not to any chapter.
     *
     * @param key   the settings field name, e.g. {@code bookTitle}
     * @param value the new value, or null to remove the key
     */
    record SetIndex(String key, JsonElement value) implements EditorOp {
    }

    /** Moves one entry within its array: the drag-to-reorder, as one edit. */
    record MoveEntry(String id, String member, int from, int to) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /** Removes one quest, recoverably: the model renames its file rather than deleting it. */
    record Delete(String id) implements EditorOp {

        @Override
        public String quest() {
            return id;
        }
    }

    /** One step back in the chapter's history, which is shared by everyone editing it. */
    record Undo() implements EditorOp {
    }

    /** And forward again. */
    record Redo() implements EditorOp {
    }

    /**
     * Several chapter edits as <b>one</b> edit: one history step, one save, one Ctrl+Z.
     *
     * <h2>Why this exists</h2>
     *
     * <p>Because a gesture is not an operation. Selecting seventy quests and pressing Ctrl+D is one act,
     * and sending seventy ops made it seventy history steps and seventy saves — so taking it back was
     * seventy presses of Ctrl+Z, which is not an undo, it is a punishment. The model's history is a
     * snapshot per step, so the fix is not "group the ops on the client": it is one snapshot around the
     * whole batch, which is what {@code QuestEditor.group} is for.
     *
     * <h2>What may be in one</h2>
     *
     * <p>Chapter edits only: fields, entries, moves, creates, duplicates, pastes, deletes. A structural
     * edit — a chapter or a group moved, renamed, made or deleted — is refused, because its undo is the
     * tree's own record and not a snapshot of one chapter's files; {@link Undo}, {@link Redo} and a
     * nested batch are refused for the same reason one level down. The refusal is a sentence rather than
     * a silent partial application, and it costs nothing: the rule is checked before the group is opened.
     *
     * <p><b>Atomic.</b> If any element cannot be applied, or the save refuses, the whole batch is undone.
     * A bulk "duplicate these seventy" that quietly did sixty-eight would leave the author to work out
     * which two are missing, and the Ctrl+Z they would press reverts all sixty-eight anyway — so the
     * honest answer is none of them, with a sentence naming what refused.
     */
    record Batch(List<EditorOp> ops) implements EditorOp {

        public Batch {
            ops = List.copyOf(ops == null ? List.of() : ops);
        }
    }

    // ------------------------------------------------------------------
    // Structural edits
    //
    // These are the edits that change the shape of the book rather than a field in it: which group a
    // chapter hangs under, what order the groups are in, and what exists at all. They carry no session
    // id of their own -- the payload's chapter is still the editor that records them on its history,
    // which is what makes Ctrl+Z after a drag the same key it is after a field edit.
    //
    // Every one of them is applied by `QuestStructure`, which needs the tree's root rather than one
    // chapter's folder; see that class for why it is not a QuestEditor's method.
    // ------------------------------------------------------------------

    /** Moves a chapter to a group -- or to no group, named as the empty string -- at a position. */
    record MoveChapter(String chapter, String groupId, int index) implements EditorOp {
    }

    /** Moves a group to a position among the root entries: the group drag. */
    record MoveGroup(String group, int index) implements EditorOp {
    }

    /** A new, empty chapter, in a group or at the root. */
    record CreateChapter(String groupId, int index, String id, String title) implements EditorOp {
    }

    /** A new, empty group, appended to the root. */
    record CreateGroup(String id, String title) implements EditorOp {
    }

    /** Renames a chapter: its folder and its id, keeping the old id as an alias. */
    record RenameChapter(String id, String newId, String title) implements EditorOp {
    }

    /** Renames a group the same way. */
    record RenameGroup(String id, String newId, String title) implements EditorOp {
    }

    /** A copy of a chapter beside itself, under a fresh id with every quest inside re-id'd. */
    record DuplicateChapter(String id, String newId, String newTitle) implements EditorOp {
    }

    /** A copy of a group beside itself, with fresh ids all the way down. */
    record DuplicateGroup(String id, String newId, String newTitle) implements EditorOp {
    }

    /** Removes a chapter recoverably: its folder is put aside with its quests inside. */
    record DeleteChapter(String id) implements EditorOp {
    }

    /** Removes a group recoverably, its chapters with it. */
    record DeleteGroup(String group) implements EditorOp {
    }
}
