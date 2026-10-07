package dev.ellipog.tenet.client;

import dev.ellipog.tenet.net.TableRollPayload;
import dev.ellipog.tenet.quest.reward.TableReward;

import java.util.List;

/**
 * The last roll report a client was sent, and which table it was about.
 *
 * <h2>Why the client keeps it rather than asking again</h2>
 *
 * <p>A roll is a query with an answer, and the editor draws the answer in a pane the author reads. If
 * the pane re-asked on every frame it would roll the dice again on every frame — a different answer each
 * time, and a request per frame on top. So the report is held until the next one arrives, and it is
 * cleared when the table it was about stops being the table on screen.
 */
public final class ClientTableRoll {

    /**
     * A report, and the table it was about.
     *
     * <p>{@code mode} is the resolved enum rather than the wire spelling that arrived, because the pane
     * draws its rate from it: a string would have to be parsed again at the drawing site, and a report
     * read back as the wrong mode is a percentage printed over a table that has no dice.
     */
    public record Held(String table, TableReward.Mode mode, int rolls, int emptyHits, boolean truncated,
                       List<TableRollPayload.Row> rows, List<String> notRolled) {

        public Held {
            rows = List.copyOf(rows);
            notRolled = List.copyOf(notRolled);
        }
    }

    private static volatile Held held;

    private ClientTableRoll() {
    }

    /** Takes the server's answer. Called by the payload handler. */
    public static void accept(String table, TableRollPayload payload) {
        // The server writes this field itself, from a mode it holds, so a spelling this build does not
        // know is a version skew rather than a malformed answer -- and drawing the numbers as a roll is
        // the tolerant direction: a pane that refused to draw would hide the report it was sent.
        TableReward.Mode mode = TableReward.Mode.ofWire(payload.mode())
                .orElse(TableReward.Mode.RANDOM);
        held = new Held(table, mode, payload.rolls(), payload.emptyHits(), payload.truncated(),
                payload.rows(), payload.notRolled());
    }

    /** The report about this table, or null when there is none (or it was about another one). */
    public static Held of(String table) {
        Held current = held;
        return current != null && current.table().equals(table) ? current : null;
    }

    /** Forgets it: called when the editor closes, and when the client leaves the world. */
    public static void clear() {
        held = null;
    }
}
