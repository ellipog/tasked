package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a load's problems are worth telling the players about, without a server.
 */
@DisplayName("whether problems are news")
class ProblemsSyncTest {

    private static DataProblem fault(String file) {
        return new DataProblem(file, 1, 1, "$", DataProblem.Severity.WARNING, "something is off");
    }

    @Test
    @DisplayName("the same faults twice are not news, however they are ordered")
    void sameFaultsAreNotNews() {
        Problems first = new Problems();
        first.addAll(java.util.List.of(fault("a.json"), fault("b.json")));
        Problems second = new Problems();
        second.addAll(java.util.List.of(fault("b.json"), fault("a.json")));

        assertFalse(ProblemsSync.changed(first, second),
                "re-sending them would re-toast every fault on every edit");
        assertFalse(ProblemsSync.changed(first, first));
    }

    @Test
    @DisplayName("a new fault, a fixed one, or a first report are all news")
    void changedFaultsAreNews() {
        Problems before = new Problems();
        before.addAll(java.util.List.of(fault("a.json")));
        Problems after = new Problems();
        after.addAll(java.util.List.of(fault("a.json"), fault("c.json")));
        assertTrue(ProblemsSync.changed(before, after));

        Problems fixed = new Problems();
        fixed.addAll(java.util.List.of());
        assertTrue(ProblemsSync.changed(before, fixed), "a fix is news too");

        assertTrue(ProblemsSync.changed(new Problems(), before), "as is the first report");
        assertTrue(ProblemsSync.changed(null, before));
        assertFalse(ProblemsSync.changed(null, null));
    }
}
