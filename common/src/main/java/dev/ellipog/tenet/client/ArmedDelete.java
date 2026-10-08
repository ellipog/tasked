package dev.ellipog.tenet.client;

import java.util.List;
import java.util.Objects;

/**
 * The Delete key's two-press rule: {@link ArmedPress}, plus the ids the arming press named.
 *
 * <h2>Why this is a class, and why it is next to {@code ArmedPress}</h2>
 *
 * <p>Because the Delete key was the one destructive control in this book that acted on its first press.
 * Every other one asks twice — the quest card's button, the canvas menu's row, the chapter and group
 * menus, the reward-table browser's {@code ×} — and each of those holds its arming in a field of the
 * screen, where the rule is arithmetic on a clock and cannot be tested: {@code QuestBookScreen} needs a
 * running Minecraft to exist, so "the second press is what deletes" would only ever be checked by a
 * person pressing a key twice.
 *
 * <p>So the rule lives here, for the same reason {@link ArmedPress} does: a clock the caller supplies,
 * and a test that sweeps the boundaries in microseconds.
 *
 * <h2>What the ids are for, and the fault they prevent</h2>
 *
 * <p>An arming press names a selection — "delete these four quests" — and the press that confirms has to
 * delete <b>those</b> quests, not whatever is selected by the time the hand comes back. The two are
 * usually the same, and the case where they are not is the dangerous one: a wheel scroll, a stray click
 * and a shift-press between the two keys would otherwise delete a set the author was never asked about.
 * So a press whose ids differ from the armed set is a <b>new question</b> — it arms on the new set and
 * asks again — and nothing is ever confirmed but the exact list that was named.
 *
 * <p>That equality is also why the selection may be left alone on the wire: a refusal leaves the ids
 * live, so the confirmation is still armed against them and the author can press again, while a delete
 * that lands takes them out of the tree and {@code pruneSelection} lets them go.
 */
public final class ArmedDelete {

    /**
     * What one press of the key asks for.
     *
     * <p>Three answers rather than a boolean because the caller does three different things: it reports
     * the question, it deletes, or it does nothing at all — and a boolean plus an out-parameter is how a
     * caller comes to treat "nothing to delete" as "delete nothing".
     */
    public sealed interface Answer permits Armed, Confirmed, Nothing {
    }

    /** The first press: the author is being asked, and a second press takes what {@link #armedCount()} names. */
    public record Armed() implements Answer {
    }

    /** The confirming press: the ids exactly as the arming press named them. */
    public record Confirmed(List<String> ids) implements Answer {
    }

    /** Nothing to delete — the key is not this screen's, and no question was asked. */
    public record Nothing() implements Answer {
    }

    private final ArmedPress window;

    /** The chapter and the ids the arming press named; empty when nothing is armed. */
    private String armedChapter = "";
    private List<String> armed = List.of();

    public ArmedDelete() {
        this(new ArmedPress());
    }

    /** A window with its own clock, for a test that wants to reach the lapse without waiting. */
    ArmedDelete(ArmedPress window) {
        this.window = Objects.requireNonNull(window, "window");
    }

    /**
     * One press of the key.
     *
     * <p>The chapter is part of the question rather than an extra guard: a selection survives a chapter
     * switch — deliberately, so a multi-selection can span two — so without it an arming press in one
     * chapter and a confirming press after a switch would delete nodes the author can no longer see,
     * while the sentence they read named the ones they could. A different chapter is a new question, the
     * same way a different selection is.
     *
     * @param chapter   the chapter the canvas is showing; null reads as "none"
     * @param ids       what is selected right now; null and empty both mean "nothing to delete"
     * @param nowMillis the caller's clock, so nothing here has to read a world
     */
    public Answer press(String chapter, List<String> ids, long nowMillis) {
        String here = chapter == null ? "" : chapter;
        List<String> targets = ids == null ? List.of() : List.copyOf(ids);
        if (targets.isEmpty()) {
            // No selection is not a question with an empty answer, and it must not leave an earlier one
            // armed either: the ids it named are gone from the screen.
            disarm();
            return new Nothing();
        }
        // The armed set is compared, not the count: four quests deleted where a different four were
        // named is exactly the mistake this class exists to prevent.
        if (window.armed(nowMillis) && here.equals(armedChapter) && targets.equals(armed)) {
            disarm();
            return new Confirmed(targets);
        }
        // Either nothing was armed, the question lapsed, or the selection or the chapter moved under it.
        // All four are "ask again", and arming fresh is what makes the second press in every one a new
        // answer.
        window.disarm();
        window.press(nowMillis);
        armedChapter = here;
        armed = targets;
        return new Armed();
    }

    /** Forgets an armed press: any other key, any other press, Escape, or leaving the chapter. */
    public void disarm() {
        window.disarm();
        armedChapter = "";
        armed = List.of();
    }

    /** How many ids an arming press named, or zero. For a caller that wants to draw the armed state. */
    public int armedCount() {
        return armed.size();
    }
}
