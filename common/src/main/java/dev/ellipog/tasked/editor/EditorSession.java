package dev.ellipog.tasked.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the canvas remembers until the server's answer arrives: positions asked for and not yet seen.
 *
 * <h2>One exception with an expiry, and this is it</h2>
 *
 * <p>The canvas draws the tree the server sent, always — that is the whole of "the server is the authority".
 * The one thing that cannot wait for a round trip is a node the author has just dragged: the drop has to land
 * under the pointer rather than snap back for a tick and then move. So a position is remembered here, keyed by
 * quest, and forgotten the moment a tree arrives whose revision has moved — after which the files' own answer
 * is the current one and nothing is pending.
 *
 * <h2>What is no longer here</h2>
 *
 * <p>This held opened {@code QuestEditor}s as well, because the client used to edit chapters itself when it
 * was the host. It does not any more: the client sends operations and the server owns the files, so a client
 * on a dedicated server — where the files are not — has nothing to open. What is left is the pending
 * positions, which are a fact about this client's screen and nobody else's.
 */
public final class EditorSession {

    /** Positions the files have but the server has not sent yet: id to {x, y}. */
    private final Map<String, double[]> moved = new LinkedHashMap<>();

    /** The tree revision the pending positions were recorded at, or -1 when there are none. */
    private long movedAtRevision = -1;

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
     * arriving is what makes the files' own answer the current one, and nothing else in this class can see it.
     * A revision that has not moved leaves them alone, which is what keeps a node where the author put it
     * while they are still working.
     */
    public void onRevision(long revision) {
        if (movedAtRevision >= 0 && revision != movedAtRevision) {
            moved.clear();
            movedAtRevision = -1;
        }
    }
}
