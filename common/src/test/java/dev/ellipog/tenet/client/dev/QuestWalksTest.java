package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.DevMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instrument that counts how much of the quest cache a tick walked.
 *
 * <h2>What this asserts, and what it deliberately does not</h2>
 *
 * <p>It asserts that the counter records what it is told, keeps its walkers apart, ignores a walk of
 * nothing, and costs nothing at all while the mode is off. Those are the properties the number's
 * honesty rests on: a counter that folded two walkers together or counted a zero would produce a line
 * nobody could read a claim out of.
 *
 * <p>It does <b>not</b> assert that anything walks less than it used to. That is a claim about a tick
 * against a loaded cache, and the honest place for it is the test of the walker itself — see
 * {@code ObservationWatcherTest} — where the pack can be a fixture and the count can be read as a
 * number rather than inferred from a log line.
 */
@DisplayName("The cache-walk counter")
class QuestWalksTest {

    @Test
    @DisplayName("nothing is counted while developer mode is off")
    void offCountsNothing() {
        DevMode.reset();
        try {
            QuestWalks.walked("somewhere", 900);

            assertTrue(QuestWalks.tally().isEmpty(),
                    "a client with the mode off must not pay for the instrument, which is the property "
                            + "that lets it sit in a tick path at all");
        }
        finally {
            DevMode.reset();
        }
    }

    @Test
    @DisplayName("each walker is counted on its own, and a walk of nothing is not a walk")
    void onCountsPerWalker() {
        DevMode.reset();
        DevMode.setOn(true);
        try {
            QuestWalks.walked("observation", 240);
            QuestWalks.walked("observation", 60);
            QuestWalks.walked("notifier", 12);
            QuestWalks.walked("observation", 0);
            QuestWalks.walked("observation", -5);

            assertEquals(Map.of("observation", 300, "notifier", 12), QuestWalks.tally(),
                    "totals accumulate per walker; a zero or negative count is ignored, so a walker "
                            + "that stopped walking does not keep its line alive at zero");
        }
        finally {
            DevMode.reset();
        }
    }

    @Test
    @DisplayName("forgetting the tally is part of what a reset does")
    void resetForgets() {
        DevMode.reset();
        DevMode.setOn(true);
        try {
            QuestWalks.walked("observation", 5);
            QuestWalks.reset();

            assertTrue(QuestWalks.tally().isEmpty(), "a reset a test did not get would leak into the next");
        }
        finally {
            DevMode.reset();
        }
    }
}
