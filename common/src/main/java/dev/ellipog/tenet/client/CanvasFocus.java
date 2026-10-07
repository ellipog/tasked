package dev.ellipog.tenet.client;

import java.util.List;
import java.util.Optional;

/**
 * Keyboard focus over the canvas: which node the arrow keys are standing on.
 *
 * <h2>Why the canvas needs one at all</h2>
 *
 * <p>The nodes are custom-drawn and hit-tested, which means they are invisible to everything that
 * makes a screen usable without a pointer: no focus, no tab order, no narration. A player who cannot
 * use a mouse could reach the sidebar and the header — those are widgets — and never a quest. This is
 * the missing half: a focus the arrow keys move, so the screen can draw a ring around it, say it
 * through the narrator, and open it on Enter.
 *
 * <h2>The navigation rule</h2>
 *
 * <p>From the focused node, a step looks at every node <i>ahead</i> in that direction (the half-plane
 * past the focus, by centre) and takes the cheapest by {@code primary + 2 x cross} — straight ahead
 * beats diagonal, and near beats far. That one rule covers a grid, a constellation and a single
 * column without a per-shape table, which is the same reasoning the shapes' own hit-testing uses.
 *
 * <p>Three quiet cases, each a decision rather than a fallthrough: with nothing focused the first
 * node in the list takes the focus (so the first arrow press is never a no-op); with nothing ahead
 * the focus stays where it is (a wall, not a wrap — wrapping across a canvas reads as a jump); and an
 * empty canvas clears the focus rather than holding an id that is no longer drawn.
 *
 * <p>Game-free by design: ids and rectangles, no client class, which is what lets the rule above be
 * asserted without a game.
 */
public final class CanvasFocus {

    /** The way a step goes. */
    public enum Direction {
        LEFT, RIGHT, UP, DOWN
    }

    /**
     * A node as navigation sees it.
     *
     * @param questId the quest's id
     * @param x       the node's left edge, in canvas coordinates
     * @param y       the node's top edge
     * @param size    the node's width and height
     */
    public record Node(String questId, int x, int y, int size) {

        int centreX() {
            return x + size / 2;
        }

        int centreY() {
            return y + size / 2;
        }
    }

    private String focused;

    /** The focused quest, if any. */
    public Optional<String> focused() {
        return Optional.ofNullable(focused);
    }

    /** Focuses a node by id — a click on the canvas, so the arrows continue from where the pointer left. */
    public void focus(String questId) {
        focused = questId;
    }

    /** Drops the focus. Escape, or a canvas with nothing left to stand on. */
    public void clear() {
        focused = null;
    }

    /**
     * Steps the focus, returning where it landed.
     *
     * @see CanvasFocus for the rule and the three quiet cases
     */
    public Optional<String> step(List<Node> nodes, Direction direction) {
        if (nodes.isEmpty()) {
            focused = null;
            return Optional.empty();
        }

        Node from = null;
        for (Node node : nodes) {
            if (node.questId().equals(focused)) {
                from = node;
                break;
            }
        }
        if (from == null) {
            // Nothing focused, or the focused quest is no longer drawn: the first node takes it.
            focused = nodes.get(0).questId();
            return Optional.of(focused);
        }

        Node best = null;
        double bestScore = Double.MAX_VALUE;
        for (Node node : nodes) {
            if (node == from) {
                continue;
            }
            int dx = node.centreX() - from.centreX();
            int dy = node.centreY() - from.centreY();
            boolean ahead = switch (direction) {
                case LEFT -> dx < 0;
                case RIGHT -> dx > 0;
                case UP -> dy < 0;
                case DOWN -> dy > 0;
            };
            if (!ahead) {
                continue;
            }
            double primary = switch (direction) {
                case LEFT, RIGHT -> Math.abs(dx);
                case UP, DOWN -> Math.abs(dy);
            };
            double cross = switch (direction) {
                case LEFT, RIGHT -> Math.abs(dy);
                case UP, DOWN -> Math.abs(dx);
            };
            double score = primary + cross * 2.0D;
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }

        if (best == null) {
            return Optional.of(focused);   // a wall: stay put rather than wrap
        }
        focused = best.questId();
        return Optional.of(focused);
    }
}
