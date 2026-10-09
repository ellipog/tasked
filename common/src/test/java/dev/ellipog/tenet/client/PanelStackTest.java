package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.PanelStack.Columns;
import dev.ellipog.tenet.client.PanelStack.Fold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rail rules, without a screen.
 *
 * <h2>What these are for</h2>
 *
 * <p>Every one of them is a rule that would otherwise live in a branch of {@code QuestBookScreen}, where
 * no test can reach it: what a click on a node does to a picker that was open, what Escape peels first,
 * whether the author's dock survives an opening, and — the one worth the file — <b>what a folded rail is
 * allowed to have registered</b>. That last rule is asserted here because its failure is invisible in a
 * screenshot: two rails' controls on one rectangle means the hidden one answers the pointer, and nothing
 * about the picture says so.
 *
 * <h2>And the invariant this file exists for now</h2>
 *
 * <p><b>No panel transition may empty, hide or fold the dock.</b> It used to be the fallback occupant of
 * the first panel column, so opening a panel took its rail and closing that panel gave the rail back — the
 * author watched their tools disappear and reappear in the same rectangle. Every transition below is
 * asserted to carry it through untouched, and {@code PanelArrangementTest} sweeps every arrangement there
 * is for the same property.
 */
@DisplayName("the side panels' rail rules")
class PanelStackTest {

    private static final Columns QUEST_OPEN = Columns.of(PanelKind.QUEST, PanelKind.NONE, Fold.AUTO);
    private static final Columns QUEST_AND_DOCK =
            new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.NONE, Fold.AUTO);

    @Test
    @DisplayName("a child is the four lists, and nothing else is")
    void theChildKinds() {
        for (PanelKind kind : PanelKind.values()) {
            boolean child = PanelStack.isChild(kind);
            if (kind == PanelKind.PICKER || kind == PanelKind.TEXTURE
                    || kind == PanelKind.TABLE_BROWSER || kind == PanelKind.TABLE_EDITOR) {
                assertTrue(child, kind + " is a list of things rather than a thing");
            }
            else {
                assertFalse(child, kind + " is a place or a question, not something another panel opened");
            }
        }
        assertFalse(PanelStack.isChild(PanelKind.NONE), "NONE is the absence of a panel, not a panel");
    }

    @Test
    @DisplayName("the dock is the third rail, and the only thing that writes it is withDock")
    void theDockIsItsOwnRail() {
        Columns open = PanelStack.withDock(Columns.EMPTY, true);
        assertEquals(PanelKind.TOOLS, open.dock(), "the dock's rail names the dock");
        assertEquals(PanelKind.NONE, open.left(), "and nothing else moved");
        assertEquals(PanelKind.NONE, open.right());

        Columns closed = PanelStack.withDock(QUEST_AND_DOCK, false);
        assertEquals(PanelKind.NONE, closed.dock());
        assertEquals(PanelKind.QUEST, closed.left(), "putting the tools away is not closing a panel: the "
                + "arrangement is carried through untouched");
        assertEquals(Fold.AUTO, closed.fold());
    }

    @Test
    @DisplayName("no opening, closing or node click takes the dock's rail")
    void theDockSurvivesEveryTransition() {
        // The invariant, spelled out one transition at a time. Every one of these was a way the dock used
        // to be displaced, because it *was* the first column rather than a rail of its own.
        for (PanelKind opened : PanelKind.values()) {
            assertEquals(PanelKind.TOOLS, PanelStack.afterOpen(QUEST_AND_DOCK, opened).dock(),
                    "opening " + opened + " over the dock");
            assertEquals(PanelKind.TOOLS, PanelStack.asRoot(QUEST_AND_DOCK, opened).dock(),
                    "asking for " + opened + " outright");
        }
        assertEquals(PanelKind.TOOLS, PanelStack.afterNodeClick(QUEST_AND_DOCK).dock(), "a node click");
        assertEquals(PanelKind.TOOLS, PanelStack.afterClose(QUEST_AND_DOCK, true).dock(), "peeling a child");
        assertEquals(PanelKind.TOOLS, PanelStack.afterClose(QUEST_AND_DOCK, false).dock(), "closing the panel");
        assertEquals(PanelKind.NONE, PanelStack.afterClose(Columns.EMPTY, false).dock(),
                "and closing carries whatever the dock's rail held: a close is not a way to open one");
    }

    @Test
    @DisplayName("asking for what is already open changes nothing, on either rail")
    void openingWhatIsAlreadyOpenIsNotAnOpening() {
        // A pick armed while its own panel is column 1 -- the dock's picker opened twice, or a field re-picked
        // before the first list is closed -- used to file the same kind into column 2 as well, so `presented`
        // named it twice and two surfaces answered one press. This is the line that makes that unbuildable.
        Columns quest = Columns.of(PanelKind.QUEST, PanelKind.NONE, Fold.AUTO);
        assertEquals(quest, PanelStack.afterOpen(quest, PanelKind.QUEST));

        Columns picker = Columns.of(PanelKind.PICKER, PanelKind.NONE, Fold.AUTO);
        assertEquals(picker, PanelStack.afterOpen(picker, PanelKind.PICKER),
                "a pick over itself is the same pick");

        Columns both = new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.AUTO);
        assertEquals(both, PanelStack.afterOpen(both, PanelKind.PICKER),
                "and a pick that is already the child stays the child rather than replacing its parent");
    }

    @Test
    @DisplayName("a child fills column 2 under a panel, and column 1 when there is no panel")
    void aChildNeedsAPanelToBelongTo() {
        Columns beside = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        assertEquals(PanelKind.QUEST, beside.left(), "the panel it was opened from is untouched");
        assertEquals(PanelKind.PICKER, beside.right());

        // **The dock's own picker.** A picker opened from the Chapter tab's icon row has no panel under it,
        // so it takes column 1 -- which is what stops it being a child of the author's tools and, with that,
        // what stops the fold taking the dock away.
        Columns fromDock = PanelStack.afterOpen(PanelStack.withDock(Columns.EMPTY, true), PanelKind.PICKER);
        assertEquals(PanelKind.PICKER, fromDock.left(), "the dock is not a panel for it to belong to");
        assertEquals(PanelKind.NONE, fromDock.right(), "so it is the panel rather than a child of nothing");
        assertEquals(PanelKind.TOOLS, fromDock.dock(), "and the dock stays where it was");

        // Two children do not make a third rail: the second replaces the first.
        Columns two = PanelStack.afterOpen(beside, PanelKind.TABLE_EDITOR);
        assertEquals(PanelKind.QUEST, two.left());
        assertEquals(PanelKind.TABLE_EDITOR, two.right(), "one child rail, not a stack of them");
        assertEquals(2, PanelStack.presented(two, false).size(), "and still exactly two things on screen");
    }

    @Test
    @DisplayName("a panel takes column 1 and empties column 2, and the fold is the player's throughout")
    void aPanelReplacesBothPanelColumns() {
        Columns withChild = new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);
        for (PanelKind panel : List.of(PanelKind.QUEST, PanelKind.TABLE_EDITOR, PanelKind.TABLE_BROWSER,
                PanelKind.ASSETS, PanelKind.REWARDS, PanelKind.SETTINGS, PanelKind.PINNED, PanelKind.NAMING,
                PanelKind.PARTY, PanelKind.CHOICE)) {
            Columns after = PanelStack.asRoot(withChild, panel);
            assertEquals(panel, after.left(), panel + " is what the player asked for");
            assertEquals(PanelKind.NONE, after.right(), "and the child it replaced goes with the parent");
            assertEquals(PanelKind.TOOLS, after.dock(), "and the dock is not the arrangement's to lose");
            assertEquals(Fold.ALWAYS, after.fold(), "nor is the player's fold");
        }
        assertEquals(PanelKind.NONE, PanelStack.asRoot(withChild, PanelKind.NONE).left(),
                "closing through a panel kind closes the panel columns rather than naming one");
        assertEquals(PanelKind.NONE, PanelStack.asRoot(withChild, PanelKind.NONE).right());
        assertEquals(PanelKind.TOOLS, PanelStack.asRoot(withChild, PanelKind.NONE).dock(),
                "and it is not a way to put the tools away either");
    }

    @Test
    @DisplayName("a kind that is neither a child nor a panel you asked for still replaces column 1")
    void anUnclassifiedKindReplacesThePanelColumn() {
        // A question the player is asked rather than a place they went. Writing the rule as "a child is the
        // exception" rather than "a panel is the rule" is what keeps it out of the second rail.
        Columns withChild = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        Columns after = PanelStack.afterOpen(withChild, PanelKind.CHOICE);
        assertEquals(PanelKind.CHOICE, after.left(), "it is what the player is being asked, so it is shown");
        assertEquals(PanelKind.NONE, after.right(),
                "and the child it was opened over goes with the panel it belonged to");
    }

    @Test
    @DisplayName("a node click swaps the panel's rail and empties the child's")
    void aNodeClickClearsTheChild() {
        Columns now = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        Columns after = PanelStack.afterNodeClick(now);

        assertEquals(PanelKind.QUEST, after.left());
        assertEquals(PanelKind.NONE, after.right(),
                "the picker was picking for the quest that is no longer showing");
    }

    @Test
    @DisplayName("opening NONE changes nothing, because NONE is not an opening")
    void openingNothingIsNotAnOpening() {
        Columns now = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        assertEquals(now, PanelStack.afterOpen(now, PanelKind.NONE));
    }

    @Test
    @DisplayName("the outer close drops the child, the inner one drops the panel, and neither drops the dock")
    void closingPeelsOutermostFirst() {
        Columns two = new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.TABLE_EDITOR, Fold.AUTO);

        Columns childGone = PanelStack.afterClose(two, true);
        assertEquals(PanelKind.QUEST, childGone.left());
        assertEquals(PanelKind.NONE, childGone.right(), "the child goes first, which is what Escape peels");

        Columns panelGone = PanelStack.afterClose(two, false);
        assertEquals(PanelKind.NONE, panelGone.left());
        assertEquals(PanelKind.NONE, panelGone.right(),
                "a second rail is always something the first opened, so it cannot outlive it");
        assertEquals(PanelKind.TOOLS, panelGone.dock(), "and the dock is neither of them");
    }

    @Test
    @DisplayName("a folded rail shows the child, and the dock beside it whatever the fold says")
    void foldedShowsTheChild() {
        Columns two = new Columns(PanelKind.NONE, PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);

        assertEquals(PanelKind.PICKER, PanelStack.shown(two), "the child is what was being looked at");
        assertEquals(List.of(PanelKind.PICKER), PanelStack.presented(two, true),
                "one entry, and the parent is not drawn behind it");

        Columns withDock = new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);
        assertEquals(List.of(PanelKind.PICKER, PanelKind.TOOLS), PanelStack.presented(withDock, true),
                "**and the dock is still there**: folding pages a panel and its child, and it is not a way "
                        + "to take the author's tools away -- which is the fault this round exists to fix");
    }

    @Test
    @DisplayName("folding with nothing open shows the panel, and folding nothing shows nothing")
    void foldingWithoutAChildShowsThePanel() {
        assertEquals(List.of(PanelKind.QUEST), PanelStack.presented(QUEST_OPEN, true));
        assertEquals(List.of(PanelKind.TOOLS), PanelStack.presented(
                PanelStack.withDock(Columns.EMPTY, true), true), "the dock is not a panel to fold");
        assertEquals(List.of(), PanelStack.presented(Columns.EMPTY, true));
        assertEquals(List.of(), PanelStack.presented(Columns.EMPTY, false));
    }

    @Test
    @DisplayName("rails are presented innermost first, which is the draw order")
    void presentedIsDrawOrder() {
        Columns three = new Columns(PanelKind.TOOLS, PanelKind.QUEST, PanelKind.PICKER, Fold.AUTO);
        assertEquals(List.of(PanelKind.PICKER, PanelKind.QUEST, PanelKind.TOOLS),
                PanelStack.presented(three, false),
                "the outermost rail is drawn last, so its edge and its corners sit over the inner one's");

        Columns two = new Columns(PanelKind.NONE, PanelKind.QUEST, PanelKind.PICKER, Fold.AUTO);
        assertEquals(List.of(PanelKind.PICKER, PanelKind.QUEST), PanelStack.presented(two, false));
    }

    @Test
    @DisplayName("NONE is never presented, on any rail")
    void absenceIsNeverPresented() {
        assertEquals(List.of(PanelKind.QUEST), PanelStack.presented(QUEST_OPEN, false));
        assertEquals(List.of(PanelKind.PICKER),
                PanelStack.presented(Columns.of(PanelKind.NONE, PanelKind.PICKER, Fold.AUTO), false),
                "a child rail with no panel under it is shown rather than dropped: the rules never build "
                        + "one, and `presented` still has to answer for an arrangement it is given");
        assertEquals(List.of(PanelKind.TOOLS),
                PanelStack.presented(PanelStack.withDock(Columns.EMPTY, true), false),
                "and the dock on its own is the one thing on screen");
    }

    @Test
    @DisplayName("asking for the dock opens the dock, on either transition")
    void askingForTheDockIsTheDocksOwnTransition() {
        // `TOOLS` is a rail rather than a panel column, so both openings delegate rather than filing it into
        // a panel column -- which would be two rails holding the author's tools, one of them under a panel.
        assertEquals(PanelKind.TOOLS, PanelStack.afterOpen(Columns.EMPTY, PanelKind.TOOLS).dock());
        assertEquals(PanelKind.TOOLS, PanelStack.asRoot(Columns.EMPTY, PanelKind.TOOLS).dock());
        assertEquals(PanelKind.NONE, PanelStack.afterOpen(Columns.EMPTY, PanelKind.TOOLS).left(),
                "and neither put it where a panel goes");
        assertEquals(PanelKind.QUEST,
                PanelStack.afterOpen(QUEST_OPEN, PanelKind.TOOLS).left(),
                "and asking for it leaves the panel beside it alone");
    }

    @Test
    @DisplayName("the empty arrangement is nothing open anywhere, and the window deciding")
    void theEmptyArrangement() {
        assertEquals(PanelKind.NONE, Columns.EMPTY.dock());
        assertEquals(PanelKind.NONE, Columns.EMPTY.left());
        assertEquals(PanelKind.NONE, Columns.EMPTY.right());
        assertEquals(Fold.AUTO, Columns.EMPTY.fold(),
                "a player who never opened the file gets the window's answer");
        assertFalse(PanelStack.hasChild(Columns.EMPTY));
        assertTrue(PanelStack.hasChild(PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER)));
    }

    @Test
    @DisplayName("every kind opens at the width its own layout was drawn against")
    void everyKindHasItsOwnDefaultWidth() {
        for (PanelKind kind : PanelKind.values()) {
            int width = PanelStack.defaultWidth(kind);
            if (kind == PanelKind.NONE) {
                assertEquals(0, width, "nothing has no width");
                continue;
            }
            assertTrue(width >= PanelStack.minimumWidth(kind) && width <= PanelStack.WIDE_WIDTH,
                    kind + "'s default must be a width it may legally be drawn at");
            assertEquals(width, PanelStack.clampWidth(width, kind),
                    kind + "'s own default must survive its own clamp");
        }
        assertEquals(PanelStack.WIDTH, PanelStack.defaultWidth(PanelKind.QUEST));
        assertEquals(PanelStack.WIDTH, PanelStack.defaultWidth(PanelKind.TOOLS),
                "the dock is an ordinary rail width-wise: its rows are labelled fields and switches");
        for (PanelKind list : List.of(PanelKind.PICKER, PanelKind.TEXTURE, PanelKind.TABLE_BROWSER)) {
            assertEquals(PanelStack.SECOND_WIDTH, PanelStack.defaultWidth(list),
                    list + " is a list of names rather than something you read, so it opens narrow");
        }
        for (PanelKind wide : List.of(PanelKind.REWARDS, PanelKind.TABLE_EDITOR, PanelKind.ASSETS)) {
            assertTrue(PanelStack.isWide(wide), wide + " is the wide set");
            assertEquals(PanelStack.WIDE_WIDTH, PanelStack.defaultWidth(wide), wide + " was drawn wide");
        }
        // And nothing else is wide, so the two lists above are the whole of the rule rather than a sample
        // of it: a kind added later lands in `isWide` or it does not, and either way this catches it.
        for (PanelKind kind : PanelKind.values()) {
            if (kind == PanelKind.NONE || list(kind) || PanelStack.isWide(kind)) {
                continue;
            }
            assertEquals(PanelStack.WIDTH, PanelStack.defaultWidth(kind),
                    kind + " carries prose, so it opens at an ordinary rail's width");
        }
    }

    /** The kinds whose content is a list of names rather than prose. */
    private static boolean list(PanelKind kind) {
        return kind == PanelKind.PICKER || kind == PanelKind.TEXTURE || kind == PanelKind.TABLE_BROWSER;
    }

    @Test
    @DisplayName("the drag clamp holds each kind inside its own range, and re-clamping changes nothing")
    void theDragClampHoldsEveryKind() {
        for (PanelKind kind : PanelKind.values()) {
            int least = PanelStack.minimumWidth(kind);
            int most = PanelStack.isWide(kind) ? PanelStack.WIDE_WIDTH : PanelStack.MAX_WIDTH;

            assertEquals(least, PanelStack.clampWidth(0, kind), kind + " has a floor");
            assertEquals(most, PanelStack.clampWidth(10_000, kind), kind + " has a ceiling");
            for (int wanted = -50; wanted <= PanelStack.WIDE_WIDTH + 50; wanted += 7) {
                int once = PanelStack.clampWidth(wanted, kind);
                assertTrue(once >= least && once <= most, kind + " must stay inside its own range");
                assertEquals(once, PanelStack.clampWidth(once, kind),
                        "a clamped width is already a legal one, so clamping it again is the same number");
            }
        }
    }

    @Test
    @DisplayName("a kind has the last word on the width it is drawn at")
    void theDrawnWidth() {
        assertEquals(300, PanelStack.columnWidth(PanelKind.QUEST, 300),
                "an ordinary rail is drawn at the player's width");
        assertEquals(PanelStack.MIN_WIDTH, PanelStack.columnWidth(PanelKind.QUEST, 10),
                "clamped up to the narrowest a rail may be");
        assertEquals(PanelStack.MAX_WIDTH, PanelStack.columnWidth(PanelKind.QUEST, 900),
                "and down to the widest an ordinary one may be");

        assertEquals(PanelStack.WIDE_MIN_WIDTH, PanelStack.columnWidth(PanelKind.REWARDS, 300),
                "a wide kind opened at a prose width is widened to its own floor instead");
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.columnWidth(PanelKind.REWARDS, 900),
                "and may use the whole width the centred card gave it");
        assertEquals(0, PanelStack.columnWidth(PanelKind.NONE, 300), "and nothing has no width");
    }

    @Test
    @DisplayName("dragging the inner edge left widens the rail, and the kind stops it at its own limits")
    void theDragArithmetic() {
        // The edge is on the inner side, so the pointer moving left widens -- and the distance travelled
        // is what the width gains, which is what makes the edge follow the hand rather than a formula.
        assertEquals(340, PanelStack.widthWhileDragging(340, 500, 500, PanelKind.QUEST),
                "a press that has not moved has changed nothing");
        assertEquals(360, PanelStack.widthWhileDragging(340, 500, 480, PanelKind.QUEST),
                "20 pixels left is 20 wider");
        assertEquals(320, PanelStack.widthWhileDragging(340, 500, 520, PanelKind.QUEST),
                "and 20 right is 20 narrower");

        assertEquals(PanelStack.MIN_WIDTH, PanelStack.widthWhileDragging(340, 500, 5_000, PanelKind.QUEST),
                "dragged far to the right, an ordinary rail stops at the narrowest a rail may be");
        assertEquals(PanelStack.MAX_WIDTH, PanelStack.widthWhileDragging(340, 500, -5_000, PanelKind.QUEST),
                "and far to the left, at the widest");
        assertEquals(PanelStack.WIDE_WIDTH,
                PanelStack.widthWhileDragging(PanelStack.WIDE_MIN_WIDTH, 500, -5_000, PanelKind.REWARDS),
                "a wide kind has further to go, and stops at the width its own layout was drawn for");
    }

    @Test
    @DisplayName("a footer's floor is the sum of what its controls measure, plus the air they need")
    void theFooterFloorIsMeasured() {
        // The reporter a panel's width is judged by, so it is asserted here rather than measured by eye on
        // a screenshot: every label appears, every gap does, and the inset is on both sides.
        int floor = PanelStack.footerMinimumWidth(List.of(84, 54, 24, 48), 2, 88, 12);
        // 4 labels = 210, three gaps = 6, the bar's two gaps to Back = 4, Back = 88, insets = 24.
        assertEquals(12 * 2 + (84 + 54 + 24 + 48) + 2 * 3 + 2 * 2 + 88, floor,
                "one label left out of the floor is a button whose own label does not fit");
        assertTrue(floor > 12 * 2 + (84 + 54 + 24 + 48) + 2 * 3,
                "and Back and its gaps are part of it, not an afterthought");

        // Wider labels, or more of them, and the floor follows -- which is the whole point of measuring.
        assertTrue(PanelStack.footerMinimumWidth(List.of(84, 54, 24, 48, 60), 2, 88, 12) > floor,
                "a fifth action needs room");
        assertTrue(PanelStack.footerMinimumWidth(List.of(120, 54, 24, 48), 2, 88, 12) > floor,
                "and so does a longer word where one already was");

        // Degenerate input is arithmetic, not an exception: a panel with no controls is still a panel.
        assertEquals(24 + 88 + 4, PanelStack.footerMinimumWidth(List.of(), 2, 88, 12),
                "no actions is Back, its gaps and the insets");
        assertEquals(0, PanelStack.footerMinimumWidth(List.of(-5), 0, 0, 0),
                "and nothing measured is no floor rather than a negative one");
    }

    @Test
    @DisplayName("the fold's words round-trip, and anything else means the window decides")
    void theFoldWords() {
        for (Fold fold : Fold.values()) {
            assertEquals(fold, PanelStack.foldOf(PanelStack.foldWord(fold)), fold + " must survive its own word");
        }
        assertEquals(Fold.AUTO, PanelStack.foldOf(null));
        assertEquals(Fold.AUTO, PanelStack.foldOf("  "));
        assertEquals(Fold.AUTO, PanelStack.foldOf("sometimes"), "a typo costs a preference, not a client");
        assertEquals(Fold.ALWAYS, PanelStack.foldOf(" ALWAYS "), "the long words are read as well");
        assertEquals(Fold.NEVER, PanelStack.foldOf("never"));
    }
}
