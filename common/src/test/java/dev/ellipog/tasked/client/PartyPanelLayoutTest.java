package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.party.PartyRoster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party panel's composition: what it contains, in what order, and how tall it is.
 *
 * <h2>What is asserted here against what {@code PartyRosterTest} asserts</h2>
 *
 * <p>That test holds the <b>roster</b> — owner first, who may remove whom, a count of zero having no
 * payer. This one holds the <b>panel</b>, which is the wrapping: a heading, a row per member, the
 * actions when there are actions to offer, and an empty state when there is no party. The two are
 * separate because the roster is Armature's and reusable and the panel is this screen's, and separating
 * them is what let the first be tested without a party panel existing.
 *
 * <p>The assertions that carry the most weight are the two structural ones:
 * <b>every key is unique</b>, because a duplicate key is a row drawn twice or a button placed over
 * another; and <b>no row leaves the column</b>, because a negative-width slot reads correctly everywhere
 * it is used.
 */
@DisplayName("The party panel: heading, roster, and the actions")
class PartyPanelLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);
    private static final int WIDTH = 140;

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final Map<UUID, String> NAMES = new HashMap<>();

    static {
        NAMES.put(OWNER, "Ada");
        NAMES.put(OFFICER, "Bo");
        NAMES.put(MEMBER, "Cy");
    }

    private static String nameOf(UUID id) {
        return NAMES.getOrDefault(id, id.toString());
    }

    /** The member a roster holds for an id, or null. For looking up a Remove key's subject. */
    private static PartyRoster.Member memberFor(PartyRoster roster, UUID id) {
        if (id == null) {
            return null;
        }
        return roster.members().stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Team party() {
        return Team.created(UUID.randomUUID(), "the crew", OWNER, 0L)
                .withMember(OFFICER, TeamRole.OFFICER)
                .withMember(MEMBER, TeamRole.MEMBER);
    }

    /** The panel as the owner sees it: the fullest case, with two actions and two buttons. */
    private static PartyRoster asOwner() {
        return PartyRoster.of(party(), OWNER, PartyPanelLayoutTest::nameOf);
    }

    private static PartyRoster solo() {
        return PartyRoster.of(Team.solo(MEMBER), MEMBER, PartyPanelLayoutTest::nameOf);
    }

    // ------------------------------------------------------------------
    // The empty state
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("no party")
    class NoParty {

        @Test
        @DisplayName("the heading and one line, and no actions at all")
        void headingAndOneLine() {
            PartyRoster roster = solo();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            assertNotNull(layout.slot(PartyPanelLayout.HEADING),
                    "the heading is present even with no party -- the panel is where a player finds "
                            + "out they have none, so it cannot be the thing that hides when they do");
            assertNotNull(layout.slot(PartyPanelLayout.NO_PARTY));

            assertNull(layout.slot(PartyPanelLayout.LEAVE),
                    "a Leave button for somebody in no party can only refuse, and a control that exists "
                            + "to refuse is worse than no control");
            assertNull(layout.slot(PartyPanelLayout.DISBAND));
            assertEquals(List.of(), PartyPanelLayout.controlKeys(roster),
                    "so there is nothing clickable on the panel at all");
        }

        @Test
        @DisplayName("the height is the heading, the gap, the line and the tail")
        void emptyHeight() {
            Layout layout = PartyPanelLayout.build(solo(), WIDTH, MEASURE);

            int expected = PartyPanelLayout.HEADING_HEIGHT + PartyPanelLayout.HEADING_TAIL
                    + PartyPanelLayout.EMPTY_ADVANCE + PartyPanelLayout.PANEL_TAIL;
            assertEquals(expected, layout.height(),
                    "and the tail is a placed row rather than a gap -- a gap places no slot, so "
                            + "Layout.height(), which is the lowest slot's bottom edge, would not count "
                            + "it and the scroll range would come out short");
        }
    }

    // ------------------------------------------------------------------
    // A real party
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a party of three, seen by its owner")
    class FullPanel {

        @Test
        @DisplayName("a heading, one row per member, and both actions")
        void contents() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            assertNotNull(layout.slot(PartyPanelLayout.HEADING));
            for (PartyRoster.Member member : roster.members()) {
                assertNotNull(layout.slot(member.key()), () -> "no row for " + member.name());
            }
            assertNotNull(layout.slot(PartyPanelLayout.LEAVE),
                    "the owner is in a party, so they may leave it");
            assertNotNull(layout.slot(PartyPanelLayout.DISBAND),
                    "and they own it, so they may dissolve it");

            assertNull(layout.slot(PartyPanelLayout.NO_PARTY),
                    "and the empty state is absent, because it is not true");
        }

        @Test
        @DisplayName("every key is unique, and every control key is placed")
        void keysAreUniqueAndPlaced() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            List<Object> placed = layout.slots().stream().map(Slot::key).filter(k -> k != null).toList();
            assertEquals(placed.size(), placed.stream().distinct().count(),
                    () -> "two slots share a key, which is a row drawn twice or a button placed over "
                            + "another. Placed: " + placed);

            for (String key : PartyPanelLayout.controlKeys(roster)) {
                boolean isRemove = key.startsWith(PartyRoster.REMOVE_PREFIX);
                if (isRemove) {
                    // A Remove button is not a slot of its own, and that is the design rather than an
                    // omission: its position is *derived* from the row it belongs to, by
                    // PartyRoster.removeSlot, so that the two cannot be computed from different
                    // numbers. So this asserts it resolves rather than that it was placed.
                    assertNotNull(PartyPanelLayout.removeSlot(roster, layout,
                                    memberFor(roster, PartyRoster.removeTarget(key))),
                            () -> "controlKeys offered a Remove button for " + key
                                    + " but no slot could be derived for it");
                    continue;
                }
                assertNotNull(layout.slot(key), () -> "controlKeys offered " + key
                        + " but the layout placed no slot for it, so a widget would be created at 0,0");
            }
        }

        @Test
        @DisplayName("a Remove button exists for exactly the members the roster allows")
        void removeButtonsMatchTheRoster() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            int placed = 0;
            for (PartyRoster.Member member : roster.members()) {
                Slot button = PartyPanelLayout.removeSlot(roster, layout, member);
                if (button == null) {
                    assertFalse(member.canRemove(),
                            () -> "no button placed for " + member.name() + ", who may be removed");
                    continue;
                }
                placed++;
                assertEquals(member.removeKey(), button.key(),
                        "the button must be routed by the member it removes");
            }

            assertEquals(roster.removableCount(), placed,
                    "the panel places exactly as many Remove buttons as the roster permits -- no more, "
                            + "and none missing");

            // And the count in the roster is the count in the keys, which is what a widget pass
            // iterates.
            long buttons = PartyPanelLayout.controlKeys(roster).stream()
                    .filter(k -> k.startsWith(PartyRoster.REMOVE_PREFIX)).count();
            assertEquals(roster.removableCount(), buttons);
        }

        @Test
        @DisplayName("a Remove button is inside its own row and inside the column")
        void removeButtonsAreInsideTheirRows() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            for (PartyRoster.Member member : roster.members()) {
                Slot button = PartyPanelLayout.removeSlot(roster, layout, member);
                if (button == null) {
                    continue;
                }
                Slot row = layout.slot(member.key());
                assertNotNull(row);

                assertTrue(button.x() >= row.x() && button.right() <= row.right(),
                        () -> "a Remove button left its row horizontally: row " + row
                                + ", button " + button);
                assertTrue(button.y() >= row.y() && button.bottom() <= row.bottom(),
                        () -> "a Remove button left its row vertically: row " + row
                                + ", button " + button);
                assertTrue(button.x() >= 0 && button.right() <= WIDTH,
                        () -> "a Remove button left the column: " + button);
            }
        }

        @Test
        @DisplayName("the rows do not overlap, and the columns do not either")
        void nothingOverlaps() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);
            List<Slot> slots = layout.slots();

            for (int i = 1; i < slots.size(); i++) {
                final Slot above = slots.get(i - 1);
                final Slot below = slots.get(i);
                assertTrue(below.y() >= above.bottom(),
                        () -> "a slot starts above the one before it: " + above + " then " + below);
            }
        }

        @Test
        @DisplayName("the height is the bottom of the last thing placed")
        void heightIsTheRoster() {
            PartyRoster roster = asOwner();
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            int last = layout.slots().get(layout.slots().size() - 1).bottom();
            assertEquals(last, layout.height());

            // Written out, so a change to any advance has to fail something.
            int expected = PartyPanelLayout.HEADING_HEIGHT + PartyPanelLayout.HEADING_TAIL
                    + PartyRoster.ROW_HEIGHT * 3
                    + PartyPanelLayout.ACTIONS_GAP
                    + PartyPanelLayout.ACTION_HEIGHT            // leave
                    + PartyPanelLayout.ACTION_GAP
                    + PartyPanelLayout.ACTION_HEIGHT            // disband
                    + PartyPanelLayout.PANEL_TAIL;
            assertEquals(expected, layout.height(),
                    "heading, three member rows, and two stacked actions");
        }
    }

    // ------------------------------------------------------------------
    // The actions, one at a time
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the actions a party offers, and who gets which")
    class Actions {

        @Test
        @DisplayName("a member may leave and not disband, so gets one button")
        void memberGetsLeaveOnly() {
            PartyRoster roster = PartyRoster.of(party(), MEMBER, PartyPanelLayoutTest::nameOf);
            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            assertNotNull(layout.slot(PartyPanelLayout.LEAVE));
            assertNull(layout.slot(PartyPanelLayout.DISBAND),
                    "only the party's owner may dissolve it -- the panel does not offer what the "
                            + "server would refuse");

            assertTrue(PartyPanelLayout.isAction(PartyPanelLayout.LEAVE));
            assertFalse(PartyPanelLayout.isAction("action:nonsense"));
            assertFalse(PartyPanelLayout.isAction(PartyRoster.MEMBER_PREFIX + MEMBER),
                    "a member row is not an action");
        }

        @Test
        @DisplayName("a party of one may be left and disbanded, so an owner alone is not stuck")
        void ownerAloneGetsBoth() {
            PartyRoster roster = PartyRoster.of(
                    Team.created(UUID.randomUUID(), "just me", OWNER, 0L), OWNER,
                    PartyPanelLayoutTest::nameOf);

            Layout layout = PartyPanelLayout.build(roster, WIDTH, MEASURE);

            assertTrue(roster.isReal(), "a party of one is a real party, unlike a solo team");
            assertNotNull(layout.slot(PartyPanelLayout.LEAVE));
            assertNotNull(layout.slot(PartyPanelLayout.DISBAND),
                    "an owner alone may still want to dissolve the party; without this they would be "
                            + "the one player who cannot get rid of one");
        }

        @Test
        @DisplayName("one action, when there is one, does not leave a gap for the other")
        void noGapForAMissingAction() {
            // A member gets Leave and no Disband, so the gap between the two actions must not be
            // placed -- otherwise the panel reserves four pixels for a control that does not exist, and
            // the height is four more than the drawing fills.
            Layout member = PartyPanelLayout.build(
                    PartyRoster.of(party(), MEMBER, PartyPanelLayoutTest::nameOf), WIDTH, MEASURE);

            int expected = PartyPanelLayout.HEADING_HEIGHT + PartyPanelLayout.HEADING_TAIL
                    + PartyRoster.ROW_HEIGHT * 3
                    + PartyPanelLayout.ACTIONS_GAP
                    + PartyPanelLayout.ACTION_HEIGHT
                    + PartyPanelLayout.PANEL_TAIL;
            assertEquals(expected, member.height(),
                    "one action means one action's height, and no gap for the one that is absent");
        }
    }

    // ------------------------------------------------------------------
    // Degenerate columns
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a zero-width column places empty rows rather than a negative rectangle")
    void zeroWidthIsSafe() {
        // A window can be dragged smaller than anything sensible. A negative-width slot places a
        // rectangle that reads correctly everywhere it is used, which is the shape of bug that survives
        // to a screenshot.
        for (int width : new int[] {0, -1, 1, 8}) {
            Layout layout = PartyPanelLayout.build(asOwner(), width, MEASURE);
            for (Slot slot : layout.slots()) {
                assertTrue(slot.width() >= 0,
                        () -> "a negative width at column width " + width + ": " + slot);
            }
        }
    }
}
