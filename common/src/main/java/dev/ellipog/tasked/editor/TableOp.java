package dev.ellipog.tasked.editor;

import com.google.gson.JsonObject;

/**
 * The edits the table editor sends: one table, one change.
 *
 * <h2>Why these are their own family rather than {@link EditorOp} records</h2>
 *
 * <p>They travel on the same payload, are read and written by the same shape of code, and are the same
 * kind of thing — an edit the server validates before it writes. They are a second sealed interface
 * because they are applied by a <b>different model</b>: a table draft over a file rather than a chapter,
 * and an op that a chapter's applier had to answer with a dead branch is worse than a family of its own.
 * The wire does not care: {@code TableOps.read} answers "is this one of mine", and the payload handler
 * asks that first.
 *
 * <h2>Creation ops carry a path, not an address</h2>
 *
 * <p>A table that does not exist yet has no handle, so the ops that make one are told where it goes:
 * the owning reward's path ({@code rewards.2}) plus the owner. The server mints the handle and returns
 * it, and everything after that addresses the table by the handle.
 */
public sealed interface TableOp {

    /** A change to one field of a table: a title, a weight, an entry's own reward field. */
    record Set(TableAddress address, String path, com.google.gson.JsonElement value) implements TableOp {
    }

    /**
     * Several fields at once: one snapshot, one save, one undo.
     *
     * <p>What an item pick is. A pick carries the item's id <i>and</i> its data components, and two
     * separate writes would be two saves — with a save in between holding the new item with the old
     * data. The card's own item fields have the same rule and the same reason; this is its table-side
     * spelling. A value of JSON null removes the field.
     */
    record SetFields(TableAddress address, JsonObject fields) implements TableOp {
    }

    /** One entry, inserted at a position (clamped). */
    record Insert(TableAddress address, int index, JsonObject entry) implements TableOp {
    }

    /** One entry, removed by position. */
    record Remove(TableAddress address, int index) implements TableOp {
    }

    /** One entry, moved within the table. */
    record Move(TableAddress address, int from, int to) implements TableOp {
    }

    /** One step back. */
    record Undo(TableAddress address) implements TableOp {
    }

    /** One step forward. */
    record Redo(TableAddress address) implements TableOp {
    }

    /** A new file under {@code reward_tables/}, from the whole table the caller wrote. */
    record Create(String id, JsonObject root) implements TableOp {
    }

    /** A copy of a file, handles re-minted so the copy's nested tables are its own. */
    record Duplicate(String id, String newId) implements TableOp {
    }

    /** A file renamed out of the way — refused while anything still references it. */
    record Delete(String id) implements TableOp {
    }

    /**
     * Points a reward at a named table, or clears it.
     *
     * <p>One op rather than a write and a removal, because it is one intent: {@code tableId} empty
     * clears the reference <i>and</i> the sibling {@code inline} a reward may be carrying, so a reward
     * can never be left holding both. And it is what a browser selection sends, so a selection is one
     * undo step.
     */
    record Select(TableAddress.Owner owner, String owningPath, String tableId) implements TableOp {
    }

}
