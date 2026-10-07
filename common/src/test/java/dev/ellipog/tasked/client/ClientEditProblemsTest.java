package dev.ellipog.tasked.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reload's cross-file faults, waiting for whoever is editing to read them.
 *
 * <h2>Why a queue here, when the undo-history notice is a flag</h2>
 *
 * <p>They are different kinds of news. "The undo history is gone" is one fact that cannot be said twice — two
 * reloads between two frames are one loss. Problems are <b>a set that changes</b>: an edit can introduce one
 * and the next can remove it, so the newest report is a different statement from the one before it rather than
 * a repeat of it. Keeping them in order means an author who made two edits sees both.
 *
 * <p>Reading consumes, because these are news rather than state — a problem reported again on the next frame
 * reads as a repeating error.
 */
@DisplayName("the client's reload-problem store")
class ClientEditProblemsTest {

    @BeforeEach
    void reset() {
        ClientEditProblems.clear();
    }

    @Test
    @DisplayName("nothing is waiting until the server reports something")
    void nothingIsWaitingAtFirst() {
        assertTrue(ClientEditProblems.drain().isEmpty(), "a client nobody has told has nothing to report");
    }

    @Test
    @DisplayName("a report arrives as its count and its lines, and reading consumes it")
    void aReportIsDrainedOnce() {
        ClientEditProblems.accept(2, "one.json: dangling dependsOn \"gone\"\ntwo.json: cycle one -> two");

        List<ClientEditProblems.Report> drained = ClientEditProblems.drain();

        assertEquals(1, drained.size());
        assertEquals(2, drained.get(0).count(), "the count says how many the server found");
        assertEquals(List.of("one.json: dangling dependsOn \"gone\"", "two.json: cycle one -> two"),
                drained.get(0).lines(), "and the lines are what the author reads");
        assertTrue(ClientEditProblems.drain().isEmpty(), "reading consumes them");
    }

    @Test
    @DisplayName("an empty report is no lines, not one blank line")
    void anEmptyReportHasNoLines() {
        // `split("\n")` on the empty string answers `[""]`, which would be a toast with nothing in it on
        // every coalesced edit that found nothing wrong.
        ClientEditProblems.accept(0, "");

        List<ClientEditProblems.Report> drained = ClientEditProblems.drain();

        assertEquals(1, drained.size());
        assertTrue(drained.get(0).lines().isEmpty(), "an empty report carries no lines at all");
    }

    @Test
    @DisplayName("two reports are two facts, in order")
    void twoReportsAreKeptInOrder() {
        // Unlike the history notice: the second report is a statement about a *different* pack than the
        // first, so collapsing them would lose what the author needs to know about the newer one.
        ClientEditProblems.accept(1, "first");
        ClientEditProblems.accept(1, "second");

        List<ClientEditProblems.Report> drained = ClientEditProblems.drain();

        assertEquals(2, drained.size(), "both reports are news");
        assertEquals("first", drained.get(0).lines().get(0), "oldest first");
        assertEquals("second", drained.get(1).lines().get(0));
    }

    @Test
    @DisplayName("clear forgets a report nobody read")
    void clearForgetsUnreadReports() {
        ClientEditProblems.accept(1, "a problem");
        ClientEditProblems.clear();

        assertTrue(ClientEditProblems.drain().isEmpty(),
                "a report about the world just left does not follow the client into the next one");
    }
}
