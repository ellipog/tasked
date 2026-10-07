package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependency pick's rules: what a clicked quest means.
 *
 * <h2>Why this is testable and the click routing is not</h2>
 *
 * <p>The screen needs a running client to instantiate, so whether a press reaches the landing code is
 * verified in game — and that is where the bug was: the landing code was correct and unreachable for the
 * one gesture it exists for. What a click <i>means</i> is arithmetic on a list, and it lives in
 * {@link DependencyPick} precisely so it can be held here: appended at the end, refused against the
 * quest itself, refused when it is already a prerequisite. A test that cannot see the thing it is
 * testing only helps if the thing is built from what the test can see — so the screen asks this class
 * rather than deciding for itself.
 */
@DisplayName("The dependency pick")
class DependencyPickTest {

    private static DependencyPick pick(String... dependsOn) {
        return new DependencyPick("smelt_iron", "first_steps", List.of(dependsOn));
    }

    @Test
    @DisplayName("a clicked quest is appended to the end of the list as it was armed")
    void aClickIsAppended() {
        // The end, not sorted: the list a file holds is the list an author sees, and a pick that
        // re-ordered it would move rows nobody was editing.
        DependencyPick.Result result = pick("mine_wood", "craft_pickaxe").with("smelt_ore");
        assertEquals(DependencyPick.Outcome.ADDED, result.outcome());
        assertEquals(List.of("mine_wood", "craft_pickaxe", "smelt_ore"), result.dependsOn());
    }

    @Test
    @DisplayName("the armed list is not modified, so a refused pick leaves it exactly as it was")
    void theArmedListIsUntouched() {
        // The list travels in the record because the replica is gone by click time; a `with` that
        // appended to it in place would mutate the very thing the refusal is meant to preserve.
        DependencyPick armed = pick("mine_wood");
        armed.with("smelt_ore");
        assertEquals(List.of("mine_wood"), armed.dependsOn(),
                "the pick's own list changed under it");

        DependencyPick.Result refused = armed.with("mine_wood");
        assertEquals(List.of("mine_wood"), refused.dependsOn(),
                "a refusal returned a different list");
    }

    @Test
    @DisplayName("a quest cannot depend on itself")
    void selfIsRefused() {
        DependencyPick.Result result = pick("mine_wood").with("smelt_iron");
        assertEquals(DependencyPick.Outcome.ITSELF, result.outcome());
        assertEquals(List.of("mine_wood"), result.dependsOn());
    }

    @Test
    @DisplayName("a quest that is already a prerequisite is refused rather than added twice")
    void aDuplicateIsRefused() {
        // The same refusal the loader makes, reported here so the status line can say which happened
        // rather than the author finding out from a file that did not change.
        DependencyPick.Result result = pick("mine_wood", "smelt_ore").with("mine_wood");
        assertEquals(DependencyPick.Outcome.ALREADY, result.outcome());
        assertEquals(List.of("mine_wood", "smelt_ore"), result.dependsOn());
    }

    @Test
    @DisplayName("a pick with nothing armed yet takes the first prerequisite")
    void anEmptyPickTakesTheFirst() {
        DependencyPick.Result result = new DependencyPick("smelt_iron", "first_steps", List.of())
                .with("mine_wood");
        assertEquals(DependencyPick.Outcome.ADDED, result.outcome());
        assertEquals(List.of("mine_wood"), result.dependsOn());
    }

    @Test
    @DisplayName("the list a pick carries cannot be edited from outside it")
    void theListIsImmutable() {
        // It is captured from the replica and sent back to the server: a caller that could append to it
        // would be a second place a dependency list is edited.
        DependencyPick armed = pick("mine_wood");
        assertThrows(UnsupportedOperationException.class, () -> armed.dependsOn().add("smelt_ore"));
        assertTrue(armed.dependsOn().contains("mine_wood"), "fixture sanity");
    }
}
