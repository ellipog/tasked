package dev.ellipog.tasked.editor;

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
 * <p>{@link #forget} exists for exactly one caller: {@code /tasked reload}, which is the path by which the
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
        if (chapter == null || chapter.isBlank()) {
            // No session, which is the state of a questline with no chapters: there is no editor to
            // open and no history to record on, but a structural edit can still be applied — that is how
            // the first chapter gets made. Every other kind is refused there with a sentence, because an
            // edit to a chapter needs one to edit. See `EditorOps.applyWithoutSession`.
            return EditorOps.applyWithoutSession(root.get(), op);
        }
        QuestEditor editor = open(chapter).orElse(null);
        if (editor == null) {
            return EditorOps.Applied.refused("no chapter called \"" + chapter + "\"");
        }
        EditorOps.Applied applied = EditorOps.apply(editor, op);
        if (applied.ok() && touchesStructure(applied)) {
            follow(chapter, editor, applied);
        }
        return applied;
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
            return;
        }
        fresh.adopt(acting);
        fresh.refresh();
        open.put(target, fresh);
    }

    /** The first chapter the tree still has, for a history whose own chapter has just been deleted. */
    private String firstChapterId() {
        return dev.ellipog.tasked.quest.QuestFiles.discover(root.get())
                .of(dev.ellipog.tasked.quest.QuestFiles.Kind.CHAPTER).stream()
                .map(dev.ellipog.tasked.quest.QuestFiles.Declaration::id)
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
     * <p>Called by {@code /tasked reload} and nothing else: that is the one path by which the files can have
     * changed without an op having written them.
     */
    public void forget() {
        open.clear();
    }

    /** Whether a chapter is open. For tests, and for a log line when one is dropped. */
    public boolean isOpen(String chapter) {
        return open.containsKey(chapter);
    }
}
