package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.PanelStack.Columns;
import dev.ellipog.tenet.client.PanelStack.Fold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every arrangement the rules can <b>reach</b>, and the properties none of them may break.
 *
 * <h2>Why this is a file of its own</h2>
 *
 * <p>Because it is exhaustive rather than illustrative, and the two are different jobs. {@code PanelStackTest}
 * asserts the <i>rules</i> — one test per sentence a person would say about the arrangement — and this asserts
 * the <i>invariants</i> over every arrangement those rules can produce, found by walking the transitions from
 * nothing rather than by writing down the arrangements the author happened to think of.
 *
 * <p>That shape is not decoration, and it is what the obvious version of this file would miss. The two faults
 * this round exists to fix were both "one arrangement out of many, and the ones that worked hid it": the dock
 * was displaced by <i>some</i> openings and not others, and one press arm swallowed the pointer while its
 * neighbours did not. A test that names the arrangements it checks cannot see either, because the set it
 * names is exactly the set that works.
 *
 * <p><b>Reachable, not conceivable.</b> The walk starts at nothing and applies every transition for every
 * kind until it closes, so what it holds is what the screen can actually be in. An arrangement the rules
 * cannot build — the author's tools filed into a panel column, say — is not asserted about, because a
 * property over impossible states is a property about nothing.
 *
 * <h2>The four properties</h2>
 *
 * <ol>
 *   <li><b>No kind is on two rails.</b> A kind drawn twice is two surfaces answering one press.</li>
 *   <li><b>The dock is on screen exactly when it is latched.</b> Never hidden, never twice.</li>
 *   <li><b>The dock is the outermost entry.</b> Which is the draw order, and the thing that keeps its
 *       rectangle from moving when a panel opens beside it.</li>
 *   <li><b>A folded arrangement shows one panel and the dock.</b> Folding pages a panel and its child; it is
 *       not a way to take the author's tools away.</li>
 * </ol>
 */
@DisplayName("every arrangement the rules can reach, and the properties none of them may break")
class PanelArrangementTest {

    /**
     * Every arrangement reachable from nothing, by applying every transition for every kind until closed.
     *
     * <p>A breadth-first walk with a seen-set rather than a nested loop over the record's four fields, and the
     * difference is the whole point: the loop would assert over arrangements the rules cannot build (and would
     * therefore have to weaken the properties to survive them), while this asserts over exactly the states the
     * screen can be in. The state space is small — two dock values, thirteen kinds twice, three folds — so the
     * closure is cheap and finite.
     */
    private static List<Columns> reachable() {
        List<Columns> seen = new ArrayList<>();
        java.util.Deque<Columns> frontier = new java.util.ArrayDeque<>();
        for (Columns start : List.of(
                Columns.EMPTY,
                PanelStack.withDock(Columns.EMPTY, true),
                new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS),
                // The third fold, seeded rather than reached: no transition writes it -- the player's word
                // comes from the settings file at startup -- so a walk that started only at the defaults
                // would never look at an arrangement the player had asked to fold the other way.
                new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.NEVER))) {
            if (!seen.contains(start)) {
                seen.add(start);
                frontier.add(start);
            }
        }
        while (!frontier.isEmpty()) {
            Columns now = frontier.poll();
            for (Columns next : step(now)) {
                if (!seen.contains(next)) {
                    seen.add(next);
                    frontier.add(next);
                }
            }
        }
        return seen;
    }

    /** Every arrangement one transition can take this one to, over every kind. */
    private static List<Columns> step(Columns now) {
        List<Columns> out = new ArrayList<>();
        out.add(PanelStack.afterNodeClick(now));
        out.add(PanelStack.afterClose(now, true));
        out.add(PanelStack.afterClose(now, false));
        out.add(PanelStack.withDock(now, true));
        out.add(PanelStack.withDock(now, false));
        for (PanelKind kind : PanelKind.values()) {
            out.add(PanelStack.afterOpen(now, kind));
            out.add(PanelStack.asRoot(now, kind));
        }
        return out;
    }

    @Test
    @DisplayName("the walk reaches the whole state space, so the properties below are not about nothing")
    void theWalkIsNotVacuous() {
        // A sweep that finds nothing must not pass. This is the check on the check: the closure has to hold
        // both dock states, every kind as the panel on screen, both panel rails occupied, and every fold --
        // or the properties below are being asserted over a handful of arrangements and reported as a proof.
        List<Columns> all = reachable();

        assertTrue(all.size() > 200, "the closure is suspiciously small: " + all.size() + " arrangements");
        assertEquals(2, all.stream().map(Columns::dock).distinct().count(),
                "both dock states have to be reachable, or property 2 is untested");
        assertEquals(PanelKind.values().length - 1,
                all.stream().map(Columns::left).distinct().count(),
                "every kind but the dock has to be reachable as the panel on screen -- the dock is a rail "
                        + "rather than a panel, and `TOOLS` in a panel column is exactly what `afterOpen` "
                        + "refuses to build");
        assertFalse(all.stream().anyMatch(now -> now.left() == PanelKind.TOOLS
                        || now.right() == PanelKind.TOOLS),
                "and so nothing the walk reaches files the author's tools into a panel column");
        assertEquals(4, all.stream().filter(now -> now.right() != PanelKind.NONE).map(Columns::right)
                        .distinct().count(),
                "and every kind that can be a child has to be reachable as one -- four of them, because the "
                        + "child rail is the four lists and nothing else, or property 1 is only tested on "
                        + "one rail");
        assertEquals(3, all.stream().map(Columns::fold).distinct().count(), "every fold");
        assertTrue(all.stream().anyMatch(now -> now.right() != PanelKind.NONE),
                "and at least one arrangement with both panel rails occupied");
    }

    @Test
    @DisplayName("no kind is on two rails, the dock is there exactly when it is latched, and it is outermost")
    void theInvariantsHoldEverywhere() {
        for (Columns now : reachable()) {
            for (boolean folded : List.of(false, true)) {
                List<PanelKind> shown = PanelStack.presented(now, folded);
                String at = now + (folded ? " folded" : " unfolded");

                // Nothing but a kind: NONE is the absence of a panel and must never be named as one.
                assertFalse(shown.contains(PanelKind.NONE), "NONE is presented as a kind" + at);

                // 1. No kind twice. A kind on two rails is two surfaces answering one press, and it is the
                //    fault this property was written for: a pick armed while its own panel was already
                //    column 1 used to be filed into column 2 as well.
                assertEquals(shown.size(), shown.stream().distinct().count(),
                        "a kind is on two rails at once" + at);

                // 2. The dock, exactly once, exactly when it is latched.
                assertEquals(now.dock() == PanelKind.TOOLS, shown.contains(PanelKind.TOOLS),
                        "the dock is on screen exactly when it is latched" + at);

                // 3. Outermost last -- the draw order, and the reason its rectangle cannot move.
                if (now.dock() == PanelKind.TOOLS) {
                    assertEquals(PanelKind.TOOLS, shown.get(shown.size() - 1),
                            "the dock is not the outermost entry, so it is drawn under a panel rather than "
                                    + "beside it" + at);
                }

                // 4. At most three, and at most two panels -- one of them when folded.
                assertTrue(shown.size() <= 3, "three rails is the arrangement" + at);
                long panels = shown.stream().filter(kind -> kind != PanelKind.TOOLS).count();
                assertTrue(panels <= 2, "two panel rails and the dock is the arrangement" + at);
                if (folded) {
                    assertTrue(panels <= 1, "a folded arrangement shows one panel" + at);
                }
            }
        }
    }

    @Test
    @DisplayName("the dock's rail is never emptied or moved by a panel")
    void theDockIsNeverDisplaced() {
        // The property said the long way, because it is the round: for every arrangement and every kind, the
        // dock's presence and its rectangle are the same before and after the opening. The rectangle is what
        // the author sees, so it is what the assertion is about -- see `PanelStackTest` for the same rule
        // stated one transition at a time.
        for (Columns now : reachable()) {
            for (PanelKind kind : PanelKind.values()) {
                if (kind == PanelKind.TOOLS) {
                    continue;
                }
                String at = " opening " + kind + " over " + now;
                for (Columns after : List.of(
                        PanelStack.afterOpen(now, kind),
                        PanelStack.asRoot(now, kind))) {
                    assertEquals(now.dock(), after.dock(), "the dock changed" + at);
                    assertEquals(now.fold(), after.fold(), "and the player's fold changed with it" + at);
                }
                assertEquals(now.dock(), PanelStack.afterNodeClick(now).dock(), "a node click" + at);
                assertEquals(now.dock(), PanelStack.afterClose(now, false).dock(), "and a close" + at);
            }
        }
    }

    @Test
    @DisplayName("the fold is the player's, so no transition loses it")
    void theFoldSurvivesEveryTransition() {
        for (Columns now : reachable()) {
            for (Columns after : step(now)) {
                assertEquals(now.fold(), after.fold(),
                        "a transition forgot how the player asked to fold, from " + now);
            }
        }
    }

    @Test
    @DisplayName("every presented kind is one the drawing can answer for")
    void everyPresentedKindIsRunnable() {
        // The property the drawing, the widget build and the press all rest on: `presented` names kinds, and
        // every kind it can name has a branch somewhere. A kind added later without one is a rail that draws
        // nothing and answers nothing, and this is where that is visible without a client.
        for (Columns now : reachable()) {
            for (PanelKind kind : PanelStack.presented(now, false)) {
                assertNotNull(kind, "presented may not name a null kind: " + now);
                assertNotEquals(PanelKind.NONE, kind, "presented may not name the absence of a panel: " + now);
                assertTrue(PanelStack.isChild(kind) || kind == PanelKind.TOOLS
                                || kind == PanelKind.QUEST || kind == PanelKind.PARTY
                                || kind == PanelKind.REWARDS || kind == PanelKind.SETTINGS
                                || kind == PanelKind.NAMING || kind == PanelKind.ASSETS
                                || kind == PanelKind.CHOICE || kind == PanelKind.ELEMENT,
                        kind + " is a kind the rules can present and the screen has no branch for");
            }
        }
    }
}
