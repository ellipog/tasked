package dev.ellipog.tasked.client.dev;

import dev.ellipog.tasked.quest.QuestShape;

/**
 * What the settings page has asked for and the server has not answered yet.
 *
 * <h2>Why a draft exists at all</h2>
 *
 * <p>Every other control in the editor commits and then draws what the server sent, which is the whole
 * of "the server is the authority". The settings page cannot: its preview has to follow a slider while
 * the slider is being dragged, and a preview that waited for a round trip would lag a pointer by a tick
 * and, on a busy server, by much more — an author dragging the size control would see the node move in
 * steps behind their hand, which reads as a broken control rather than as latency.
 *
 * <p>So a value is remembered here the moment it is asked for, the preview reads it, and it is forgotten
 * the moment the server's tree arrives with a newer revision — after which the file's own answer is the
 * current one and nothing is pending. That is exactly {@code EditorSession}'s contract for a dragged
 * node, and deliberately so: two kinds of pending edit with two different lifetimes would be two things
 * to keep in step, and the one thing that must not differ is when the server's answer wins.
 *
 * <h2>What is not here</h2>
 *
 * <p>The commit itself. The screen sends the operation; this only remembers what was asked for. A class
 * that also wrote would be a second place the client could change a file, which is the mistake the
 * editor's whole design is arranged around.
 */
public final class SettingsDraft {

    private QuestShape shape;
    private Integer size;
    private Double iconScale;
    private Integer rotation;

    /** The revision the pending values were recorded at, or -1 when there are none. */
    private long atRevision = -1;

    /** Remembers a shape until a newer tree arrives. */
    public void shape(QuestShape value, long revision) {
        shape = value;
        atRevision = revision;
    }

    /** Remembers a size until a newer tree arrives. */
    public void size(int value, long revision) {
        size = value;
        atRevision = revision;
    }

    /** Remembers an icon scale until a newer tree arrives. */
    public void iconScale(double value, long revision) {
        iconScale = value;
        atRevision = revision;
    }

    /** Remembers a rotation until a newer tree arrives. */
    public void rotation(int value, long revision) {
        rotation = value;
        atRevision = revision;
    }

    /** The pending shape, or the server's. */
    public QuestShape shape(QuestShape sent) {
        return shape == null ? sent : shape;
    }

    /** The pending size, or the server's. */
    public int size(int sent) {
        return size == null ? sent : size;
    }

    /** The pending icon scale, or the server's. */
    public double iconScale(double sent) {
        return iconScale == null ? sent : iconScale;
    }

    /** The pending rotation, or the server's. */
    public int rotation(int sent) {
        return rotation == null ? sent : rotation;
    }

    /** Whether anything is pending. */
    public boolean isEmpty() {
        return shape == null && size == null && iconScale == null && rotation == null;
    }

    /**
     * Forgets the pending values once the server has sent a tree that is newer than they are.
     *
     * <p>Called with the cache's revision, every frame, because that is where the fact lives: the tree
     * arriving is what makes the files' own answer the current one, and nothing else can see it. A
     * revision that has not moved leaves them alone, which is what keeps the preview following a drag.
     */
    public void onRevision(long revision) {
        if (atRevision >= 0 && revision != atRevision) {
            clear();
        }
    }

    /** Forgets everything pending — when the page closes, and when the server's answer arrives. */
    public void clear() {
        shape = null;
        size = null;
        iconScale = null;
        rotation = null;
        atRevision = -1;
    }
}
