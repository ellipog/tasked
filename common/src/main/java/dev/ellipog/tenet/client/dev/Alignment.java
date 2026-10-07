package dev.ellipog.tenet.client.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where a node belongs when it is straightened: the midpoint of the two nearest of its links.
 *
 * <h2>Why "nearest" rather than "first two"</h2>
 *
 * <p>A quest's dependency list is authored order, which is meaningful for progression and meaningless
 * for geometry: the two links that bracket a node on the canvas are whichever two are closest to it, not
 * whichever two were written first. So the candidates are sorted by distance here, and the caller is
 * responsible only for saying which points count as links — the quests this one depends on, and the
 * quests that depend on it.
 */
public final class Alignment {

    /**
     * The midpoint of the two candidates nearest {@code node}, or empty when there are fewer than two.
     *
     * <p>Empty rather than a fallback: a node with one link has no line to be centred on, and moving it
     * somewhere invented would be worse than doing nothing — the caller says so out loud.
     */
    public static Optional<LineArt.Point> midpointOfTwoNearest(LineArt.Point node, List<LineArt.Point> candidates) {
        List<LineArt.Point> points = new ArrayList<>(candidates);
        points.removeIf(point -> point.equals(node));
        if (points.size() < 2) {
            return Optional.empty();
        }
        points.sort(Comparator.comparingDouble(point ->
                Math.hypot(point.x() - node.x(), point.y() - node.y())));
        LineArt.Point first = points.get(0);
        LineArt.Point second = points.get(1);
        return Optional.of(new LineArt.Point((first.x() + second.x()) / 2, (first.y() + second.y()) / 2));
    }

    private Alignment() {
    }
}
