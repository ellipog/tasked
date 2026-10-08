package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.Problems;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load's problems, as the one message that carries them.
 *
 * <h2>Why the rendering is asserted here rather than at the send site</h2>
 *
 * <p>Because it is the payload's own shape and it had none of these tests. The count and the lines are two
 * fields of one record and the rule between them — the count is how many there <i>are</i>, the text is as
 * many as fit — lived inline in {@code TenetNetworking}, where two senders would each have needed a copy
 * and nothing could reach it. A report cut short that reads as the whole of it is a lie about the pack, and
 * the whole point of the message is that the author acts on it.
 *
 * <p>No client and no server: this is a record with a factory, which is the other reason it moved.
 */
@DisplayName("the load's problems, as one message")
class EditProblemsPayloadTest {

    /** The shape the loader reports in: a file, a position, a severity and a sentence. */
    private static Problems problemsOf(String... filesAndMessages) {
        Problems problems = new Problems();
        for (int i = 0; i < filesAndMessages.length; i += 2) {
            problems.add(filesAndMessages[i], new JsonLocation(1, 1, "$"),
                    DataProblem.Severity.ERROR, filesAndMessages[i + 1]);
        }
        return problems;
    }

    @Test
    @DisplayName("nothing wrong is no message at all, rather than a message about nothing")
    void nothingIsNoPayload() {
        // Null rather than an empty payload, and both callers depend on it: the broadcast would put a report
        // about nothing on every screen, and the join would do the same to every player who arrives on a
        // healthy server. A `Problems` that was never filled and one that was never created are the same
        // answer, which is why both are asserted.
        assertNull(EditProblemsPayload.of(new Problems()));
        assertNull(EditProblemsPayload.of(null));
    }

    @Test
    @DisplayName("every line is carried, and the count is how many there were")
    void linesAndCount() {
        EditProblemsPayload payload = EditProblemsPayload.of(problemsOf(
                "a.json", "no quest with id or alias \"gone\" exists",
                "b.json", "circular dependency: p -> q -> p"));

        assertNotNull(payload);
        assertEquals(2, payload.lines(), "the count is how many problems there were, not how many lines");
        // Asserted as containment rather than as the exact text, because how one problem renders is
        // `DataProblem`'s business and not this payload's. What this class owns is carrying it verbatim,
        // counting it, and -- see `oneLinePerProblem` -- one line per problem.
        assertTrue(payload.text().contains("no quest with id or alias \"gone\" exists"), payload.text());
        assertTrue(payload.text().contains("circular dependency: p -> q -> p"), payload.text());
        assertTrue(payload.text().contains("a.json:1:1") && payload.text().contains("b.json:1:1"),
                "and where each one is: " + payload.text());
    }

    @Test
    @DisplayName("one line per problem, and the JSON path is not one of them")
    void oneLinePerProblem() {
        // The path used to arrive as a second line -- `    at $.tasks[2]` -- and both readers of this payload
        // put one line on a screen: the Chapter tab's list, and the toast stack. So every problem put a bare
        // "at $" card on the author's screen beside the fault itself, twice over for two problems. The rule
        // belongs here because this payload is what decided it: the path is validator detail, and the file
        // and the line already say where the fault is.
        EditProblemsPayload payload = EditProblemsPayload.of(problemsOf(
                "a.json", "no quest with id or alias \"gone\" exists",
                "b.json", "circular dependency: p -> q -> p"));

        assertNotNull(payload);
        assertEquals(2, payload.problems().size(), "two problems, two lines: " + payload.text());
        for (String line : payload.problems()) {
            assertFalse(line.contains("\n"), "no entry is itself two lines: " + line);
            assertFalse(line.trim().startsWith("at "), "and none of them is the path alone: " + line);
        }
    }

    @Test
    @DisplayName("a report too long to send says how many it is not showing")
    void aTruncatedReportKeepsTheCount() {
        // **The rule the two fields exist for.** A payload capped at the wire's limit must not read as the
        // whole of what is wrong: the author fixes what they were shown, reloads, and finds more -- which
        // looks like the faults multiplying rather than like a message that was cut short. So the count is
        // the whole number and the text is what fitted, and the two are asserted against each other.
        String[] entries = new String[32];
        for (int i = 0; i < 16; i++) {
            entries[i * 2] = "file" + i + ".json";
            entries[i * 2 + 1] = "problem_" + i + " " + "x".repeat(EditProblemsPayload.MAX_CHARS / 4);
        }

        EditProblemsPayload payload = EditProblemsPayload.of(problemsOf(entries));

        assertNotNull(payload);
        assertEquals(16, payload.lines(), "every problem is counted, whether or not it was sent");
        assertTrue(payload.text().contains("problem_0"), "the first ones fit: " + payload.text().length());
        assertFalse(payload.text().contains("problem_15"),
                "and the last one did not, which is what the count is there to say");
        assertTrue(payload.text().length() <= EditProblemsPayload.MAX_CHARS,
                "the cap is the codec's own, so a payload over it could not be written at all");
    }
}
