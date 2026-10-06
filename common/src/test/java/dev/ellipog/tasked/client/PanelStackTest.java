package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.PanelStack.Columns;
import dev.ellipog.tasked.client.PanelStack.Fold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column rules, without a screen.
 *
 * <h2>What these are for</h2>
 *
 * <p>Every one of them is a rule that would otherwise live in a branch of {@code QuestBookScreen}, where
 * no test can reach it: what a click on a node does to a picker that was open, what Escape peels first,
 * and — the one worth the file — <b>what a folded rail is allowed to have registered</b>. That last rule
 * is asserted here because its failure is invisible in a screenshot: two columns' controls on one
 * rectangle means the hidden one answers the pointer, and nothing about the picture says so.
 */
@DisplayName("the side panels' column rules")
class PanelStackTest {

    private static final Columns QUEST_OPEN = new Columns(PanelKind.QUEST, PanelKind.NONE, Fold.AUTO);

    @Test
    @DisplayName("every kind is a root, a child, or neither — and none is both")
    void everyKindIsClassifiedOnce() {
        for (PanelKind kind : PanelKind.values()) {
            boolean root = PanelStack.isRoot(kind);
            boolean child = PanelStack.isChild(kind);
            assertFalse(root && child, kind + " cannot be both something you asked for and something it opened");
            if (kind == PanelKind.NONE) {
                assertFalse(root || child, "NONE is the absence of a panel, not a panel");
            }
            else if (kind == PanelKind.CHOICE) {
                assertFalse(root || child,
                        kind + " stays a centred card in both presentations, so it belongs to no column: it "
                                + "asks the player a question that interrupts, rather than being a place they "
                                + "went. (PARTY used to be here beside it and is not any more -- the party is "
                                + "a place, and the panel now docks. See `theConvertedKinds`.)");
            }
            else {
                assertTrue(root || child, kind + " must be reachable somehow");
            }
        }
    }

    @Test
    @DisplayName("a child fills column 2 over a docked panel, and replaces a card")
    void childrenNeedSomewhereToBeSecond() {
        // Over a docked panel: beside it.
        Columns beside = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        assertEquals(PanelKind.QUEST, beside.left());
        assertEquals(PanelKind.PICKER, beside.right());

        // Over a kind that still draws a card: *replaces* it, because a column is not drawn at all while a
        // card is up -- so a child filed beside one would be in no picture, answering presses the card
        // behind it also answers. Choice is the example because it is, with Party, the only kind left that
        // draws a card at all: every root and every child is docked now.
        Columns overCard = PanelStack.afterOpen(
                new Columns(PanelKind.CHOICE, PanelKind.NONE, Fold.AUTO), PanelKind.PICKER);
        assertEquals(PanelKind.PICKER, overCard.left(),
                "a picker opened over a panel that still draws a card takes its place");
        assertEquals(PanelKind.NONE, overCard.right(),
                "rather than being filed in a column that is not drawn");
    }

    @Test
    @DisplayName("a root takes column 1 and empties column 2")
    void aRootReplacesTheFirstColumn() {
        Columns withChild = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        Columns rewards = PanelStack.afterOpen(withChild, PanelKind.REWARDS);

        assertEquals(PanelKind.REWARDS, rewards.left(), "the thing you asked for is column 1");
        assertEquals(PanelKind.NONE, rewards.right(),
                "the picker was opened from the quest, and the quest is no longer showing");
    }

    @Test
    @DisplayName("a kind that is neither root nor child still replaces column 1")
    void anUnclassifiedKindReplacesTheFirstColumn() {
        // Party and Choice are questions the player is asked, not places they went, so neither is a root --
        // and writing the rule as "a child is the exception" is what keeps them out of the second column.
        // Written the other way round, asking for the party panel would have filed it beside the quest.
        Columns withChild = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        for (PanelKind kind : List.of(PanelKind.PARTY, PanelKind.CHOICE)) {
            Columns after = PanelStack.afterOpen(withChild, kind);
            assertEquals(kind, after.left(), kind + " is what the player asked for, so it is column 1");
            assertEquals(PanelKind.NONE, after.right(),
                    "and the child it was opened over goes with the panel it belonged to");
        }
    }

    @Test
    @DisplayName("a child fills column 2 and a second child replaces it rather than growing a third")
    void childrenReplaceEachOther() {
        Columns one = PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER);
        assertEquals(PanelKind.QUEST, one.left(), "the quest it was opened from is untouched");
        assertEquals(PanelKind.PICKER, one.right());

        Columns two = PanelStack.afterOpen(one, PanelKind.TABLE_EDITOR);
        assertEquals(PanelKind.QUEST, two.left());
        assertEquals(PanelKind.TABLE_EDITOR, two.right(), "one child column, not a stack of them");
        assertEquals(2, PanelStack.presented(two, false).size(), "and still exactly two things on screen");
    }

    @Test
    @DisplayName("a node click swaps column 1 and empties column 2")
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
    @DisplayName("the fold is the player's, so no transition loses it")
    void theFoldSurvivesEveryTransition() {
        Columns folded = new Columns(PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);

        assertEquals(Fold.ALWAYS, PanelStack.afterOpen(folded, PanelKind.REWARDS).fold());
        assertEquals(Fold.ALWAYS, PanelStack.afterNodeClick(folded).fold());
        assertEquals(Fold.ALWAYS, PanelStack.afterClose(folded, true).fold(),
                "closing the child shows the parent again; it does not un-ask for folding");
        assertEquals(Fold.ALWAYS, PanelStack.afterModeFlip(folded, false).fold());
    }

    @Test
    @DisplayName("the outer close drops the child, the inner one drops everything")
    void closingPeelsOutermostFirst() {
        Columns two = new Columns(PanelKind.QUEST, PanelKind.TABLE_EDITOR, Fold.AUTO);

        Columns childGone = PanelStack.afterClose(two, true);
        assertEquals(PanelKind.QUEST, childGone.left());
        assertEquals(PanelKind.NONE, childGone.right(), "the child goes first, which is what Escape peels");

        Columns allGone = PanelStack.afterClose(two, false);
        assertEquals(PanelKind.NONE, allGone.left());
        assertEquals(PanelKind.NONE, allGone.right(),
                "a second column is always something the first opened, so it cannot outlive it");
    }

    @Test
    @DisplayName("a folded rail shows the child, and names its parent")
    void foldedShowsTheChild() {
        Columns two = new Columns(PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);

        assertEquals(PanelKind.PICKER, PanelStack.shown(two), "the child is what was being looked at");
        assertEquals(List.of(PanelKind.PICKER), PanelStack.presented(two, true),
                "one entry, and the parent is not drawn behind it");
    }

    @Test
    @DisplayName("folding with nothing open shows the parent, and folding nothing shows nothing")
    void foldingWithoutAChildShowsTheParent() {
        assertEquals(List.of(PanelKind.QUEST), PanelStack.presented(QUEST_OPEN, true));
        assertEquals(List.of(), PanelStack.presented(Columns.EMPTY, true));
        assertEquals(List.of(), PanelStack.presented(Columns.EMPTY, false));
    }

    @Test
    @DisplayName("two columns are presented child first, which is the draw order")
    void presentedIsDrawOrder() {
        Columns two = new Columns(PanelKind.QUEST, PanelKind.PICKER, Fold.AUTO);
        assertEquals(List.of(PanelKind.PICKER, PanelKind.QUEST), PanelStack.presented(two, false),
                "the first column is drawn last, so its edge and its corners sit over the second's");
    }

    @Test
    @DisplayName("NONE is never presented, in either column")
    void absenceIsNeverPresented() {
        assertEquals(List.of(PanelKind.QUEST), PanelStack.presented(QUEST_OPEN, false));
        assertEquals(List.of(PanelKind.PICKER),
                PanelStack.presented(new Columns(PanelKind.NONE, PanelKind.PICKER, Fold.AUTO), false));
    }

    @Test
    @DisplayName("flipping to a card promotes the child; flipping to a panel carries the first column")
    void theModeFlipKeepsWhatMatters() {
        Columns two = new Columns(PanelKind.QUEST, PanelKind.TABLE_EDITOR, Fold.AUTO);

        Columns asCard = PanelStack.afterModeFlip(two, false);
        assertEquals(PanelKind.TABLE_EDITOR, asCard.left(), "the card shows the innermost thing you were on");
        assertEquals(PanelKind.NONE, asCard.right(), "a card has no second column to leave behind");

        Columns asPanel = PanelStack.afterModeFlip(asCard, true);
        assertEquals(PanelKind.TABLE_EDITOR, asPanel.left(), "and the panel takes the card's kind back");
        assertEquals(PanelKind.NONE, asPanel.right());
    }

    @Test
    @DisplayName("flipping with no child keeps the parent, which is the same rule with nothing to promote")
    void theModeFlipWithoutAChild() {
        assertEquals(PanelKind.QUEST, PanelStack.afterModeFlip(QUEST_OPEN, false).left());
        assertEquals(PanelKind.NONE, PanelStack.afterModeFlip(Columns.EMPTY, false).left());
    }

    @Test
    @DisplayName("a wide kind asks for the card's width and keeps a floor a table can be read at")
    void wideKindsKeepTheirWidth() {
        for (PanelKind wide : List.of(PanelKind.REWARDS, PanelKind.TABLE_EDITOR, PanelKind.ASSETS)) {
            assertEquals(PanelStack.WIDE_WIDTH, PanelStack.preferredWidth(wide), wide + " was drawn wide");
            assertEquals(PanelStack.WIDE_MIN_WIDTH, PanelStack.minimumWidth(wide),
                    wide + " below this reads as broken rather than as narrow");
        }
        assertEquals(PanelStack.WIDTH, PanelStack.preferredWidth(PanelKind.QUEST));
        assertEquals(PanelStack.MIN_WIDTH, PanelStack.minimumWidth(PanelKind.QUEST));
        // The dock is an ordinary column width-wise: its rows are labelled fields and switches rather than
        // prose, so it asks for the same width every root asks for and shares the same floor. What makes it
        // different is that it has no card form, which the width policy knows nothing about.
        assertEquals(PanelStack.WIDTH, PanelStack.preferredWidth(PanelKind.TOOLS));
        assertEquals(PanelStack.MIN_WIDTH, PanelStack.minimumWidth(PanelKind.TOOLS));
        assertFalse(PanelStack.isWide(PanelKind.TOOLS), "and it is not a wide kind");
    }

    @Test
    @DisplayName("a wide child wants its own width and folds when the pair cannot fit")
    void aWideChildAsksForTheWideWidth() {
        assertEquals(PanelStack.SECOND_WIDTH, PanelStack.secondPreferredWidth(PanelKind.PICKER));
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.secondPreferredWidth(PanelKind.TABLE_EDITOR),
                "a table does not reflow into a 260-pixel column, so it asks for its width and folds");
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
    @DisplayName("a stored width may be a wide kind's, so it is not clamped to a prose column")
    void theStoredWidthKeepsAWideChoice() {
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.clampStoredWidth(PanelStack.WIDE_WIDTH),
                "the player may have chosen this width for a rewards inbox");
        assertEquals(PanelStack.MIN_WIDTH, PanelStack.clampStoredWidth(-1));
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.clampStoredWidth(99_999));
    }

    @Test
    @DisplayName("the stored width is column 1's, and each kind has the last word on its own width")
    void theDrawnWidths() {
        assertEquals(300, PanelStack.columnWidth(PanelKind.QUEST, true, 300),
                "an ordinary root is drawn at the player's width");
        assertEquals(PanelStack.MIN_WIDTH, PanelStack.columnWidth(PanelKind.QUEST, true, 10),
                "clamped up to the narrowest a panel may be");
        assertEquals(PanelStack.MAX_WIDTH, PanelStack.columnWidth(PanelKind.QUEST, true, 900),
                "and down to the widest an ordinary one may be");

        assertEquals(PanelStack.WIDE_MIN_WIDTH, PanelStack.columnWidth(PanelKind.REWARDS, true, 300),
                "a wide kind opened at a prose width is widened to its own floor instead");
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.columnWidth(PanelKind.REWARDS, true, 900),
                "and may use the whole width the centred card gave it");

        assertEquals(PanelStack.SECOND_WIDTH, PanelStack.columnWidth(PanelKind.PICKER, false, 900),
                "column 2 does not take the player's width: it is about what it holds, not about reading");
        assertEquals(PanelStack.WIDE_WIDTH, PanelStack.columnWidth(PanelKind.TABLE_EDITOR, false, 300),
                "a table in column 2 asks for the width a table needs, which is what folds it on any "
                        + "ordinary window and gives it its columns on a very wide one");
        assertEquals(0, PanelStack.columnWidth(PanelKind.NONE, true, 300), "and nothing has no width");
    }

    @Test
    @DisplayName("dragging the inner edge left widens the column, and the kind stops it at its own limits")
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
                "dragged far to the right, an ordinary column stops at the narrowest a panel may be");
        assertEquals(PanelStack.MAX_WIDTH, PanelStack.widthWhileDragging(340, 500, -5_000, PanelKind.QUEST),
                "and far to the left, at the widest");
        assertEquals(PanelStack.WIDE_WIDTH,
                PanelStack.widthWhileDragging(PanelStack.WIDE_MIN_WIDTH, 500, -5_000,
                        PanelKind.REWARDS),
                "a wide kind has further to go, and stops at the width its own layout was drawn for");
    }

    @Test
    @DisplayName("the converted kinds are listed, and Choice never is")
    void theConvertedKinds() {
        // The mode's safety-critical fact, and the reason this list is in the library rather than in the
        // screen: a kind presented in a column but not listed here draws its card inside a column's frame.
        for (PanelKind kind : PanelKind.values()) {
            if (PanelStack.isDocked(kind)) {
                assertFalse(kind == PanelKind.CHOICE,
                        kind + " must never be docked: it asks the player a question that interrupts rather "
                                + "than being a place they went");
                assertTrue(PanelStack.isRoot(kind) || PanelStack.isChild(kind),
                        kind + " is docked, so it must be a root or a child -- a kind neither knows about "
                                + "has no column rule to follow");
            }
        }

        assertTrue(PanelStack.isDocked(PanelKind.QUEST), "the quest is converted");
        assertTrue(PanelStack.isDocked(PanelKind.PICKER),
                "and so are the pickers: both take their body from `overlayBody`, so they follow the surface");
        assertTrue(PanelStack.isDocked(PanelKind.TEXTURE));
        assertTrue(PanelStack.isDocked(PanelKind.TABLE_EDITOR),
                "the tables joined once their one body expression started asking the surface being drawn");
        assertTrue(PanelStack.isDocked(PanelKind.TABLE_BROWSER));
        for (PanelKind root : List.of(PanelKind.REWARDS, PanelKind.SETTINGS, PanelKind.NAMING,
                PanelKind.ASSETS, PanelKind.PARTY)) {
            assertTrue(PanelStack.isDocked(root),
                    root + " is a root that draws at its own width, and it follows the surface it is given");
        }
        // **And it is not wide**: it was, while its two faces sat side by side. Docked, the sidebar has the
        // canvas's whole height, so the party spends that instead of the width -- its faces stack down one
        // column. The wide column is for panels that cannot reflow, and this one now does.
        assertFalse(PanelStack.isWide(PanelKind.PARTY), "the party stacked its faces, so it takes the rail");
        assertFalse(PanelStack.isDocked(PanelKind.NONE), "and nothing is not a kind that occupies a column");
        // The one that never joins, and the assertion this whole list exists for: it asks the player a
        // question rather than being a place they went.
        assertFalse(PanelStack.isDocked(PanelKind.CHOICE));
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

    @Test
    @DisplayName("the empty arrangement is nothing open, and the window deciding")
    void theEmptyArrangement() {
        assertEquals(PanelKind.NONE, Columns.EMPTY.left());
        assertEquals(PanelKind.NONE, Columns.EMPTY.right());
        assertEquals(Fold.AUTO, Columns.EMPTY.fold(), "a player who never opened the file gets the window's answer");
        assertFalse(PanelStack.hasChild(Columns.EMPTY));
        assertTrue(PanelStack.hasChild(PanelStack.afterOpen(QUEST_OPEN, PanelKind.PICKER)));
    }

    @Test
    @DisplayName("with the columns off, a child has nowhere to sit and takes the first column")
    void aChildWithNoColumnsTakesTheCard() {
        // The switch is not a rule this class may read -- it arrives as a parameter -- and this test is the
        // consequence of getting it wrong, written where it can be run: with the mode off every kind draws its
        // card, so a picker filed into a second column was in no picture at all and the Escape that should have
        // closed it had nothing to peel. The whole matrix rather than one example, because the fault was one
        // arrangement out of many and the ones that worked hid it.
        for (PanelKind left : PanelKind.values()) {
            for (PanelKind opened : PanelKind.values()) {
                Columns with = PanelStack.afterOpen(
                        new Columns(left, PanelKind.NONE, Fold.AUTO), opened, false);
                String at = " opening " + opened + " over " + left;

                // No exemption, not even for the kind that is always docked: the switch decides whether an
                // opened overlay is a card, and the dock's exemption is about the dock's *own* presentation.
                // A child filed beside the dock on a client with columns off would be drawn in a column
                // nobody asked for -- see `afterOpen`, which had exactly that bug once.
                assertEquals(PanelKind.NONE, with.right(),
                        () -> "a card holds one thing, so nothing may be filed beside it" + at);
                assertEquals(opened == PanelKind.NONE ? left : opened, with.left(),
                        () -> "what was asked for takes the first column, and nothing changes nothing" + at);
            }
        }
    }

    @Test
    @DisplayName("one kind is docked whatever the switch says, and it is the author's dock")
    void theAlwaysDockedKind() {
        // The exemption above is one kind wide, and it is the mode's own limit rather than a preference: the
        // switch decides how an *overlay* is presented, and a kind with no card form has nothing for it to
        // decide. Asserted against every other kind, so a kind added later cannot quietly claim it -- which
        // is how a "temporarily" exempt kind becomes a second presentation nobody chose.
        for (PanelKind kind : PanelKind.values()) {
            if (kind == PanelKind.TOOLS) {
                assertTrue(PanelStack.isAlwaysDocked(kind), "the dock has no card form");
                assertTrue(PanelStack.isDocked(kind), "so it is docked, whatever the mode says");
                assertTrue(PanelStack.isRoot(kind), "and it is a place the author went: it takes column 1");
                assertFalse(PanelStack.isChild(kind), "nothing opens the dock from another panel");
                continue;
            }
            assertFalse(PanelStack.isAlwaysDocked(kind),
                    kind + " must not be exempt from the switch: a card is the default shape for everything "
                            + "the switch governs, and this dock is the only kind that has no card at all");
        }
    }

    @Test
    @DisplayName("with the columns on, a child still needs a panel that can hold it")
    void aChildNeedsSomewhereToBeSecondWhenTheModeIsOn() {
        // The same matrix with the mode on: beside a docked kind, and replacing a card -- which is the rule
        // the screen's own list of converted kinds feeds in as `isDocked(left)`.
        for (PanelKind left : PanelKind.values()) {
            for (PanelKind child : List.of(PanelKind.PICKER, PanelKind.TEXTURE, PanelKind.TABLE_BROWSER,
                    PanelKind.TABLE_EDITOR)) {
                Columns with = PanelStack.afterOpen(
                        new Columns(left, PanelKind.NONE, Fold.AUTO), child, true);
                String at = " opening " + child + " over " + left;

                if (PanelStack.isDocked(left)) {
                    assertEquals(left, with.left(), () -> "the panel it was opened from is untouched" + at);
                    assertEquals(child, with.right(), () -> "and the child sits beside it" + at);
                }
                else {
                    assertEquals(child, with.left(), () -> "a card cannot hold a child, so it is replaced" + at);
                    assertEquals(PanelKind.NONE, with.right(), () -> "and nothing is left behind it" + at);
                }
            }
        }
    }

    @Test
    @DisplayName("a root takes the arrangement outright, whatever its classification")
    void aRootReplacesBothColumns() {
        // The two table kinds are children by classification and roots by the road that reaches them -- a
        // reward's table chip, the pack's assets panel -- which is why the screen needs a transition that
        // does not consult the classification at all. This is that transition, and the reason it exists:
        // without it the screen assigned the fields directly, and a second column outlived its parent.
        Columns two = new Columns(PanelKind.QUEST, PanelKind.PICKER, Fold.ALWAYS);
        for (PanelKind root : List.of(PanelKind.QUEST, PanelKind.TABLE_EDITOR, PanelKind.TABLE_BROWSER,
                PanelKind.ASSETS, PanelKind.REWARDS, PanelKind.SETTINGS, PanelKind.NAMING)) {
            Columns after = PanelStack.asRoot(two, root);
            assertEquals(root, after.left(), root + " is what the player asked for");
            assertEquals(PanelKind.NONE, after.right(), "and the child it replaced goes with the parent");
            assertEquals(Fold.ALWAYS, after.fold(), "the player's fold is not the arrangement's to lose");
        }

        assertEquals(PanelKind.NONE, PanelStack.asRoot(two, PanelKind.NONE).left(),
                "closing through a root kind closes the arrangement rather than naming a panel");
        assertEquals(PanelKind.NONE, PanelStack.asRoot(two, PanelKind.NONE).right());
    }
}
