package dev.ellipog.tasked.client.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A dependency pick in flight: the quest being edited, its chapter, and its prerequisites as they were
 * when the pick was armed.
 *
 * <h2>Why this is captured rather than read when the click lands</h2>
 *
 * <p>Arming a pick closes the card, and the author is then free to switch to another chapter or chapter
 * group to find the quest they mean — which is the whole reason a pick exists instead of a text field.
 * By the time the click lands, the edited quest's id and its replica are both gone from the screen's
 * state: the chapter on screen is no longer the one holding the edit. So the quest, the chapter the
 * operation must be sent to, and the list it must append to all travel with the pick, and the operation
 * goes to the chapter that was being edited however far the sidebar has moved since.
 *
 * <h2>Why the rules are here and not in the screen</h2>
 *
 * <p>Because they are the part of the pick a test can hold. The screen needs a running client to
 * instantiate, so the click routing and the Escape handling are verified in game; what a click
 * <i>means</i> — append, refuse a self-dependency, refuse one that is already there — is arithmetic on
 * a list, and it lives where a test can call it.
 */
public record DependencyPick(String quest, String chapter, List<String> dependsOn) {

    public DependencyPick {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(dependsOn, "dependsOn");
        dependsOn = List.copyOf(dependsOn);
    }

    /** What a clicked quest turned out to be. */
    public enum Outcome {
        /** The dependency was added: the result's list is the armed list with the id on the end. */
        ADDED,
        /** The clicked quest is the one being edited, which cannot depend on itself. */
        ITSELF,
        /** The clicked quest is already a prerequisite. */
        ALREADY
    }

    /**
     * The same pick with {@code id} added, or the reason it was not.
     *
     * <p>Appended to the end rather than inserted in any order: the list a file holds is the list an
     * author sees, and a pick that re-sorted it would move rows the author was not editing. The two
     * refusals are the same two the loader's own check makes, reported here so the status line can say
     * which happened rather than the author finding out from a file that did not change.
     */
    public Result with(String id) {
        Objects.requireNonNull(id, "id");
        if (id.equals(quest)) {
            return new Result(Outcome.ITSELF, dependsOn);
        }
        if (dependsOn.contains(id)) {
            return new Result(Outcome.ALREADY, dependsOn);
        }
        List<String> next = new ArrayList<>(dependsOn);
        next.add(id);
        return new Result(Outcome.ADDED, List.copyOf(next));
    }

    /** The outcome of a click, and the list that follows from it. */
    public record Result(Outcome outcome, List<String> dependsOn) {
    }
}
