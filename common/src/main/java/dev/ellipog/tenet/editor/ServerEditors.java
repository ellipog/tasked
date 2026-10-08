package dev.ellipog.tenet.editor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The chapters a server has open, and the ops applied to them.
 *
 * <h2>Why the server keeps them open</h2>
 *
 * <p>Because the undo history lives <i>in</i> a {@link QuestEditor}, and an editor opened per request would
 * undo nothing: every op would arrive at a fresh model with an empty stack, and Ctrl+Z would be a key that
 * does nothing while looking like one that does. One editor per chapter, opened on the first op that mentions
 * it, kept for the session.
 *
 * <h2>When they are dropped</h2>
 *
 * <p>{@link #forget} exists for exactly one caller: {@code /tenet reload}, which is the path by which the
 * files may have changed <i>under</i> the model — an author editing JSON by hand, or a pack being updated. A
 * cached model after that would be a second opinion about the tree, which is the one thing the design's
 * "the server is the authority" rules out. The op path does not forget: what it applies is what it wrote, so
 * the model and the disk agree by construction.
 *
 * <p>The root is a {@link Supplier} rather than a {@code Path} so this class is constructible in a test with a
 * temporary directory, and so the real one resolves the platform's config directory at the moment it is used
 * rather than at class-init — which is the difference between a path and a path that exists yet.
 */
public final class ServerEditors {

    private final Supplier<Path> root;
    private final Map<String, QuestEditor> open = new LinkedHashMap<>();

    /**
     * The editor whose own chapter has just been deleted, when it was the last one in the book.
     *
     * <p>Kept because it is the only thing that can still reverse the delete. See
     * {@link #applyWithoutChapter} for the state this exists for, and {@link #follow} for where it is set.
     */
    private QuestEditor orphan;

    /** @param root where the quest files live, resolved when a chapter is first opened */
    public ServerEditors(Supplier<Path> root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Applies one op to one chapter, opening it if it is not open already.
     *
     * <p>A refusal — an unknown chapter, an edit the validator rejects — is an {@link EditorOps.Applied} with
     * {@code ok} false and a sentence, never an exception: this is called from a payload handler, on the
     * server thread, holding a player's message.
     */
    public EditorOps.Applied apply(String chapter, EditorOp op) {
        if (op == null) {
            // The same sentence `applyWithoutSession` gives, so "not an edit this version knows" reads the
            // same whether or not a chapter happened to be named. A null op would otherwise reach the
            // exhaustive switch in `applyOne` and throw out of a payload handler.
            return EditorOps.Applied.refused("that is not an edit this version knows");
        }
        if (chapter == null || chapter.isBlank()) {
            // No session, which is the state of a questline with no chapters: there is no editor to
            // open and no history to record on, but a structural edit can still be applied — that is how
            // the first chapter gets made. Every other kind is refused there with a sentence, because an
            // edit to a chapter needs one to edit. See `EditorOps.applyWithoutSession`, and
            // `applyWithoutChapter` for the one op that is neither.
            return applyWithoutChapter(op);
        }
        QuestEditor editor = open(chapter).orElse(null);
        if (editor == null) {
            return EditorOps.Applied.refused("no chapter called \"" + chapter + "\"");
        }
        // The op names a quest; the payload names the chapter it is meant for, and the two come from
        // different places on the client -- the selection is the screen's and the chapter is the one it is
        // looking at, and a card left open across a chapter switch is a pair that no longer agrees.
        //
        // Refused here rather than left to each mutation's own `map.get(id) == null`, which returns false
        // and reaches the author as "that edit would change nothing" -- a sentence that reads as a no-op
        // when the truth is that the edit was aimed at another chapter. Every quest op answers
        // {@code quest()} with its id; a chapter's own edit and a structural one answer null, and are not
        // this question's business.
        for (String target : questTargets(op)) {
            if (!editor.holds(target)) {
                return EditorOps.Applied.refused("no quest \"" + target
                        + "\" in the chapter you are editing (\"" + chapter + "\")");
            }
        }
        EditorOps.Applied applied = EditorOps.apply(editor, op);
        if (applied.ok()) {
            // A chapter is being edited again, so the history kept for a book with none is not the way
            // back to anything: the client names its own chapter from here on.
            orphan = null;
        }
        if (applied.ok() && touchesStructure(applied)) {
            follow(chapter, editor, applied);
        }
        return applied;
    }

    /**
     * An op that arrived with no chapter to name.
     *
     * <h2>Two states, and they are not the same state</h2>
     *
     * <p>A book with no chapters can still be given its first one, which is what
     * {@link EditorOps#applyWithoutSession} is for. And a book whose <b>last</b> chapter has just been
     * deleted is the one state where a Ctrl+Z has something to reach and no session to reach it through:
     * the client's effective chapter is empty, so the op names none, and answering "this edit belongs to a
     * chapter, and there is none yet" is a sentence about a chapter sitting right there under a
     * {@code .deleted} name. So the editor that recorded the delete is kept for exactly this, and only for
     * the two keys that carry no chapter of their own.
     *
     * <p>An undo that puts the chapter back re-opens it under its own id and carries the history across,
     * so the rest of that history is not stranded either.
     */
    private EditorOps.Applied applyWithoutChapter(EditorOp op) {
        if (orphan == null || !(op instanceof EditorOp.Undo || op instanceof EditorOp.Redo)) {
            return EditorOps.applyWithoutSession(root.get(), op);
        }
        EditorOps.Applied applied = EditorOps.apply(orphan, op);
        String back = applied.chapterId();
        if (!applied.ok() || back == null || back.isBlank()) {
            return applied;
        }
        QuestEditor reopened = QuestEditor.open(root.get(), back).orElse(null);
        if (reopened == null) {
            return applied;
        }
        reopened.adopt(orphan);
        reopened.refresh();
        open.put(back, reopened);
        orphan = null;
        return applied;
    }

    /**
     * Every quest an op is about, one level into a batch.
     *
     * <p>A batch answers {@code quest()} with null — it is the gesture, not a quest — so asking only the op
     * itself would let a bulk edit aimed at another chapter through the one check that has to hold for all
     * of it. One level is the whole depth: {@code EditorOps.joinable} refuses a batch inside a batch.
     */
    private static java.util.List<String> questTargets(EditorOp op) {
        if (op instanceof EditorOp.Batch batch) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (EditorOp element : batch.ops()) {
                if (element.quest() != null) {
                    out.add(element.quest());
                }
            }
            return out;
        }
        return op.quest() == null ? java.util.List.of() : java.util.List.of(op.quest());
    }

    /**
     * The chapter's editor, opening it if it is not open.
     *
     * <p>For the callers that need the model rather than an op applied to it: a table's inline draft
     * edits a quest file through this editor, so that its history and its save are the chapter's —
     * an inline table <i>is</i> a quest field, and its undo is the same key as a title edit's.
     */
    public java.util.Optional<QuestEditor> open(String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return java.util.Optional.empty();
        }
        QuestEditor editor = open.get(chapter);
        if (editor != null) {
            return java.util.Optional.of(editor);
        }
        editor = QuestEditor.open(root.get(), chapter).orElse(null);
        if (editor == null) {
            return java.util.Optional.empty();
        }
        open.put(chapter, editor);
        return java.util.Optional.of(editor);
    }

    /**
     * Whether an op changed the shape of the tree, rather than a field in one chapter.
     *
     * <p>True for the structural edits and for an undo or redo of one — those carry meta naming what
     * moved, whose cached editor is now stale, or both. A plain field edit carries none, and is left
     * exactly as it was: the write and the model already agree about it.
     */
    private static boolean touchesStructure(EditorOps.Applied applied) {
        return applied.chapterId() != null || applied.groupId() != null || !applied.forget().isEmpty();
    }

    /**
     * Moves open editors to wherever their chapters are now, after a structural edit moved them.
     *
     * <h2>Why the cache cannot simply be left alone</h2>
     *
     * <p>An editor is bound to a folder at the moment it is opened: it reads its manifest there and every
     * write goes back to the same path — and {@code JsonFile.write} creates the folders it needs. So an
     * editor left open across a rename does not fail, which is the dangerous part: the next edit arrives,
     * is written to the <i>old</i> path, and recreates a folder the tree no longer lists. The discovery
     * then reports it as unlisted content and the whole chapter is an error an author can see and cannot
     * explain.
     *
     * <p>So every chapter whose folder moved is dropped, and the one the op was sent <i>from</i> is
     * re-opened where it is now with its history carried across — a fresh editor has an empty stack, and
     * Ctrl+Z after a rename would otherwise do nothing. When that chapter is <b>gone</b> — a delete — the
     * history moves to the first chapter that survives, so the delete is still the thing the next Ctrl+Z
     * undoes. That is a deliberate trade: the surviving chapter's own edit history is replaced by the
     * history of the action the player just took, which is the one they are about to want back.
     *
     * <p><b>And when no chapter survives</b>, which is one chapter deleted from a one-chapter book, there
     * is nowhere to move it to — so it stays here, under no chapter's name, and
     * {@link #applyWithoutChapter} is what reaches it. Dropping it instead, which is what this did, made
     * the delete of the <i>only</i> chapter the one edit in the book that could not be taken back in game.
     */
    private void follow(String session, QuestEditor acting, EditorOps.Applied applied) {
        boolean actingMoved = applied.forget().contains(session);
        for (String gone : applied.forget()) {
            open.remove(gone);
        }
        if (!actingMoved) {
            open.put(session, acting);
            // The files this editor holds may have been rewritten by the edit -- a group's chapter list,
            // a manifest. Re-reading them is what stops the next field edit saving the old copy back.
            acting.refresh();
            return;
        }
        String target = applied.chapterId() != null && !applied.chapterId().isBlank()
                ? applied.chapterId() : session;
        QuestEditor fresh = QuestEditor.open(root.get(), target).orElse(null);
        if (fresh == null) {
            target = firstChapterId();
            fresh = target == null ? null : QuestEditor.open(root.get(), target).orElse(null);
        }
        if (fresh == null) {
            // **The last chapter in the book, deleted.** There is no surviving chapter for the history to
            // move to, and dropping it is what made Ctrl+Z do nothing at the one moment an author wants it
            // most. It is kept instead, under no chapter's name, and `applyWithoutChapter` reaches it.
            orphan = acting;
            return;
        }
        orphan = null;
        fresh.adopt(acting);
        fresh.refresh();
        open.put(target, fresh);
    }

    /** The first chapter the tree still has, for a history whose own chapter has just been deleted. */
    private String firstChapterId() {
        return dev.ellipog.tenet.quest.QuestFiles.discover(root.get())
                .of(dev.ellipog.tenet.quest.QuestFiles.Kind.CHAPTER).stream()
                .map(dev.ellipog.tenet.quest.QuestFiles.Declaration::id)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Every quest's own tree in a chapter, for a client that may display it.
     *
     * <p>The fields the synced tree does not carry are the point: a panel has to show what a quest file says,
     * including an addon's type this build has never heard of, and a copy of the file is the only way to show
     * it without a second opinion about the format. Read-only on the client — the copy is display, and every
     * change is an operation like any other.
     *
     * <p>Opens the chapter if it is not open, which is the same thing a read of a file would do and costs one
     * parse: an author who opens the book to look has not edited anything yet.
     */
    public com.google.gson.JsonObject replica(String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return null;
        }
        QuestEditor editor = open.get(chapter);
        if (editor == null) {
            editor = QuestEditor.open(root.get(), chapter).orElse(null);
            if (editor == null) {
                return null;
            }
            open.put(chapter, editor);
        }
        com.google.gson.JsonObject all = new com.google.gson.JsonObject();
        // **The editor's own entries, not the manifest's names.** Iterating `questIds()` meant this loop spoke
        // the manifest's vocabulary (file names) while `quest()` answers to the declared ids -- so for a pack
        // whose two differ, every lookup missed and the replica went out carrying *nothing*, which the panel
        // can only read as "the copy has not arrived". Reading the entries the editor holds removes the
        // question entirely: there is no second vocabulary to agree with.
        for (String id : editor.declaredIds()) {
            JsonFile quest = editor.quest(id);
            if (quest != null) {
                // **Both keys, one file.** Its two readers speak different vocabularies: the quest card reads
                // the tree's declared ids, and the chapter tab lists the manifest's file names and looks those
                // up. One copy under two keys, so neither has to translate.
                com.google.gson.JsonObject copy = quest.root().deepCopy();
                all.add(id, copy);
                String stem = editor.stemOf(id);
                if (!stem.equals(id)) {
                    all.add(stem, copy);
                }
            }
        }
        return all;
    }

    /**
     * The chapter's own file, as the tree a panel edits — its title, icon, rules and quest list.
     *
     * <p>Opens the chapter if it is not open, exactly as {@link #replica} does and for the same reason:
     * looking is not editing, and the first look should not be an error.
     */
    public com.google.gson.JsonObject chapterTree(String chapter) {
        if (chapter == null || chapter.isBlank()) {
            return null;
        }
        QuestEditor editor = open.get(chapter);
        if (editor == null) {
            editor = QuestEditor.open(root.get(), chapter).orElse(null);
            if (editor == null) {
                return null;
            }
            open.put(chapter, editor);
        }
        try {
            return com.google.gson.JsonParser.parseString(editor.chapterJson()).getAsJsonObject();
        }
        catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Forgets every open chapter.
     *
     * <p>Called by {@code /tenet reload} and nothing else: that is the one path by which the files can have
     * changed without an op having written them.
     */
    public void forget() {
        open.clear();
        orphan = null;
    }

    /** Whether a chapter is open. For tests, and for a log line when one is dropped. */
    public boolean isOpen(String chapter) {
        return open.containsKey(chapter);
    }
}
