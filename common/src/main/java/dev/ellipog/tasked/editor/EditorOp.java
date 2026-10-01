package dev.ellipog.tasked.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
}
