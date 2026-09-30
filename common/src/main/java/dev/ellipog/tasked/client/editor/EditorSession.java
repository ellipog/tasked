package dev.ellipog.tasked.client.editor;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.tasked.quest.QuestLoader;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The editors a session has open, one per chapter, and what the canvas has to know about them.
 *
 * <h2>Why a map rather than one editor</h2>
 *
 * <p>Because the book lets a reader move between chapters, and an editor is a chapter's worth of edits.
 * One editor would have to be thrown away on every chapter change, which would throw away unsaved work for
 * a glance at another chapter — a book you cannot look things up in while editing is a book that fights
 * the person using it. So an editor stays open per chapter, and going back to it finds it as it was left.
 *
 * <h2>The pending moves, and why the canvas needs them</h2>
 *
 * <p>The book draws what the server sent: dragging a node changes a file, and the position only reaches
 * the canvas when the file is read again and the new tree arrives. Between the drag and that round trip
 * the server's answer is the old one, so a canvas reading it would snap the node back under the pointer —
 * which reads as "the drag did nothing". So a committed move is remembered here, the drawing prefers it,
 * and it is forgotten the moment a tree arrives (a reload means the file now says the same thing). One
 * exception with an expiry, rather than a second source of truth.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a screen, and not game-free by accident: it holds no Minecraft types at all, so the state machine
 * of "which chapters are open, what has not been reloaded" is a thing a test can drive. Everything that
 * touches the client — the key that saves, the command that reloads, the chat line that reports — is in
 * the screen, which is the part that cannot be tested anyway.
 */
public final class EditorSession {

    private final Path root;
    private final Map<String, QuestEditor> open = new LinkedHashMap<>();

    /** Positions the file has but the server has not sent yet: id to {x, y}. */
    private final Map<String, double[]> moved = new LinkedHashMap<>();

    /** The tree revision the pending positions were recorded at, or -1 when there are none. */
    private long movedAtRevision = -1;

    public EditorSession(Path root) {
        this.root = root;
    }

    /**
     * The quest root, as the loader reads it.
     *
     * <p>{@code QuestLoader.DIRECTORY} rather than a second copy of the string: the editor must write
     * where the loader looks, and two constants that agree today are two constants that disagree after
     * one of them is changed.
     */
    public static Path root() {
        return ArmatureApi.platform().configDir().resolve(QuestLoader.DIRECTORY);
    }

    /** The editor for a chapter, opening one if this session has not looked at it yet. Null if it cannot. */
    public QuestEditor editor(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        QuestEditor known = open.get(chapterId);
        if (known != null) {
            return known;
        }
        QuestEditor opened = QuestEditor.open(root, chapterId).orElse(null);
        if (opened != null) {
            open.put(chapterId, opened);
        }
        return opened;
    }

    /** Whether any open chapter has edits that are not on disk. */
    public boolean anyDirty() {
        for (QuestEditor editor : open.values()) {
            if (editor.dirty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether any chapter has been opened at all, which is what tells a caller there is anything to save. */
    public boolean anyOpen() {
        return !open.isEmpty();
    }

    // ------------------------------------------------------------------
    // Positions the server has not sent yet
    // ------------------------------------------------------------------

    /** Remembers where a node was moved to, until a tree arrives that says the same thing. */
    public void moved(String id, double x, double y, long revision) {
        moved.put(id, new double[] {x, y});
        movedAtRevision = revision;
    }

    public boolean hasMoved(String id) {
        return moved.containsKey(id);
    }

    public double movedX(String id) {
        double[] at = moved.get(id);
        return at == null ? 0 : at[0];
    }

    public double movedY(String id) {
        double[] at = moved.get(id);
        return at == null ? 0 : at[1];
    }

    /**
     * Forgets the pending positions once the server has sent a tree that is newer than they are.
     *
     * <p>Called with the cache's revision, every frame, because that is where the fact lives: the tree
     * arriving is what makes the files' own answer the current one, and nothing else in this class can
     * see it. A revision that has not moved leaves them alone, which is what keeps a node where the author
     * put it while they are still working.
     */
    public void onRevision(long revision) {
        if (movedAtRevision >= 0 && revision != movedAtRevision) {
            moved.clear();
            movedAtRevision = -1;
        }
    }
}
