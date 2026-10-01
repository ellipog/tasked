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
            return EditorOps.Applied.refused("no chapter named");
        }
        QuestEditor editor = open.get(chapter);
        if (editor == null) {
            editor = QuestEditor.open(root.get(), chapter).orElse(null);
            if (editor == null) {
                return EditorOps.Applied.refused("no chapter called \"" + chapter + "\"");
            }
            open.put(chapter, editor);
        }
        return EditorOps.apply(editor, op);
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
        for (String id : editor.questIds()) {
            JsonFile quest = editor.quest(id);
            if (quest != null) {
                all.add(id, quest.root().deepCopy());
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
