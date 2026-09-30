package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.party.PartyRoster;
import dev.ellipog.tasked.net.PartySnapshot;

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
 * <p>That test holds the <b>roster</b> -- owner first, who may remove whom, a count of zero having no
 * payer. This one holds the <b>panel</b>, which is the wrapping: a title, a row per member, the actions
 * when there are actions to offer, and an empty state when there is no party. The two are separate
 * because the roster is Armature's and reusable and the panel is this screen's, and separating them is
 * what let the first be tested without a party panel existing.
 *
 * <h2>Why the height assertions carry the most weight</h2>
 *
 * <p>The panel describes the <b>whole</b> card's contents, the action rows included, and the screen
 * sizes the card from {@code layout.height()}. So a term missing from this composition is not a layout
 * that comes out a little short -- it is a row drawn outside the box it was built for, which is the
 * fault this class was rewritten for. The expected heights are therefore written out longhand below, so
 * a change to any advance has to fail something.
 *
 * <p>The other structural assertions are that <b>every key is unique</b>, because a duplicate key is a
 * row drawn twice or a button placed over another; and that <b>no row leaves the column</b>, because a
 * negative-width slot reads correctly everywhere it is used.
 */
@DisplayName("The party panel: title, roster, and the actions")
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

    /** The panel as the owner sees it: the fullest case, three members and two action rows. */
    private static PartyRoster asOwner() {
        return PartyRoster.of(party(), OWNER, PartyPanelLayoutTest::nameOf, id -> true);
    }

    private static PartyRoster solo() {
        return PartyRoster.of(Team.solo(MEMBER), MEMBER, PartyPanelLayoutTest::nameOf, id -> true);
    }

    /**
     * The action rows a panel is built with: the mode row and one invitation.
     *
     * <p>Two rather than one, because the gap between two action rows only exists from the second
     * onward -- a one-row list cannot tell a layout that places them from one that does not.
     */
    private static List<PartyPanelLayout.Action> actions() {
        return List.of(
                PartyPanelLayout.Action.button("mode", "Counts: pooled", "Change",
                        "/tasked party mode owner_only"),
                PartyPanelLayout.Action.button("invite:Cy", "Cy", "Invite", "/tasked party invite Cy"));
    }

    private static Layout build(PartyRoster roster, List<PartyPanelLayout.Action> actions) {
        return PartyPanelLayout.build(roster, actions, WIDTH, MEASURE);
    }

    // ------------------------------------------------------------------
    // The empty state
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("no party")
    class NoParty {

        @Test
        @DisplayName("the title and both lines, and no rule")
        void titleAndEmptyState() {
            Layout layout = build(solo(), List.of());

            assertNotNull(layout.slot(PartyPanelLayout.TITLE),
                    "the title is present even with no party -- the panel is where a player finds out "
                            + "they have none, so it cannot be the thing that hides when they do");
            assertNotNull(layout.slot(PartyPanelLayout.NO_PARTY));
            assertNotNull(layout.slot(PartyPanelLayout.HINT),
                    "the second line is the only place a player is told what to do about it -- the old "
                            + "empty state stopped at the first line and left the Create button to be "
                            + "found by experiment");
            assertNull(layout.slot(PartyPanelLayout.RULE),
                    "with no actions there is nothing for a rule to separate the empty state from");
        }

        @Test
        @DisplayName("the height is the title, the tail, and the two lines")
        void emptyHeight() {
            Layout layout = build(solo(), List.of());

            int expected = PartyPanelLayout.TITLE_HEIGHT
                    + PartyPanelLayout.TITLE_TAIL
                    + PartyPanelLayout.EMPTY_ADVANCE
                    + PartyPanelLayout.EMPTY_GAP
                    + PartyPanelLayout.EMPTY_ADVANCE;
            assertEquals(expected, layout.height(),
                    "and the last line is a placed row rather than a gap -- a gap places no slot, so "
                            + "Layout.height(), which is the lowest slot's bottom edge, would not count "
                            + "it and the card would be built short");
        }

        @Test
        @DisplayName("the actions still appear, under their rule")
        void actionsUnderARule() {
            Layout layout = build(solo(), actions());

            assertNotNull(layout.slot(PartyPanelLayout.RULE),
                    "a player with no party is the one who most needs the Create row, so the action list "
                            + "is drawn in the empty state too");
            for (PartyPanelLayout.Action action : actions()) {
                assertNotNull(layout.slot(action.key()));
            }
        }
    }

    // ------------------------------------------------------------------
    // A real party
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a party of three, seen by its owner")
    class FullPanel {

        @Test
        @DisplayName("a title, one row per member, a rule, and the actions")
        void contents() {
            PartyRoster roster = asOwner();
            Layout layout = build(roster, actions());

            assertNotNull(layout.slot(PartyPanelLayout.TITLE));
            for (PartyRoster.Member member : roster.members()) {
                assertNotNull(layout.slot(member.key()), () -> "no row for " + member.name());
            }
            assertNotNull(layout.slot(PartyPanelLayout.RULE));
            for (PartyPanelLayout.Action action : actions()) {
                assertNotNull(layout.slot(action.key()));
            }

            assertNull(layout.slot(PartyPanelLayout.NO_PARTY),
                    "and the empty state is absent, because it is not true");
        }

        @Test
        @DisplayName("Leave and Disband are the footer's, not rows of the panel")
        void leaveAndDisbandAreNotRows() {
            Layout layout = build(asOwner(), actions());

            assertNull(layout.slot(PartyPanelLayout.LEAVE),
                    "they are placed inside the card by BookGeometry.modalControls; a row here would be a "
                            + "second description of where they go");
            assertNull(layout.slot(PartyPanelLayout.DISBAND));
        }

        @Test
        @DisplayName("every key is unique, and every control key is placed")
        void keysAreUniqueAndPlaced() {
            PartyRoster roster = asOwner();
            Layout layout = build(roster, actions());

            List<Object> placed = layout.slots().stream().map(Slot::key).filter(k -> k != null).toList();
            assertEquals(placed.size(), placed.stream().distinct().count(),
                    () -> "two slots share a key, which is a row drawn twice or a button placed over "
                            + "another. Placed: " + placed);

            for (PartyRoster.Member member : roster.members()) {
                if (!member.canRemove()) {
                    assertNull(PartyPanelLayout.removeSlot(roster, layout, member));
                    continue;
                }
                // A Remove button is not a slot of its own, and that is the design rather than an
                // omission: its position is *derived* from the row it belongs to, by
                // PartyRoster.removeSlot, so that the two cannot be computed from different numbers.
                assertNotNull(PartyPanelLayout.removeSlot(roster, layout, member),
                        () -> "no slot could be derived for " + member.removeKey());
            }
            for (PartyPanelLayout.Action action : actions()) {
                assertNotNull(PartyPanelLayout.actionSlot(layout, action),
                        () -> "no button slot for " + action.key());
            }
        }

        @Test
        @DisplayName("a Remove button exists for exactly the members the roster allows")
        void removeButtonsMatchTheRoster() {
            PartyRoster roster = asOwner();
            Layout layout = build(roster, actions());

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
        }

        @Test
        @DisplayName("a Remove button is inside its own row and inside the column")
        void removeButtonsAreInsideTheirRows() {
            PartyRoster roster = asOwner();
            Layout layout = build(roster, actions());

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
        @DisplayName("the rows do not overlap, and none is past the height")
        void nothingOverlaps() {
            Layout layout = build(asOwner(), actions());
            List<Slot> slots = layout.slots();

            for (int i = 1; i < slots.size(); i++) {
                final Slot above = slots.get(i - 1);
                final Slot below = slots.get(i);
                assertTrue(below.y() >= above.bottom(),
                        () -> "a slot starts above the one before it: " + above + " then " + below);
            }
            for (Slot slot : slots) {
                assertTrue(slot.bottom() <= layout.height(),
                        () -> "a slot falls past the height the card will be built for: " + slot);
            }
        }

        @Test
        @DisplayName("the height is the bottom of the last thing placed")
        void heightIsTheRoster() {
            Layout layout = build(asOwner(), actions());

            int last = layout.slots().get(layout.slots().size() - 1).bottom();
            assertEquals(last, layout.height());

            // Written out, so a change to any advance has to fail something.
            int expected = PartyPanelLayout.TITLE_HEIGHT
                    + PartyPanelLayout.TITLE_TAIL
                    + PartyRoster.ROW_HEIGHT * 3
                    + PartyRoster.ROW_GAP * 2
                    + PartyPanelLayout.SECTION_GAP
                    + 1
                    + PartyPanelLayout.SECTION_GAP
                    + PartyPanelLayout.ACTION_HEIGHT
                    + PartyPanelLayout.ACTION_GAP
                    + PartyPanelLayout.ACTION_HEIGHT;
            assertEquals(expected, layout.height(),
                    "the title, three member rows, the rule, and two action rows");
        }
    }

    // ------------------------------------------------------------------
    // The actions, one at a time
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the action rows")
    class Actions {

        @Test
        @DisplayName("a button sits in the strip its row reserved, inside the column")
        void actionButtonsSitInTheirRows() {
            Layout layout = build(asOwner(), actions());

            for (PartyPanelLayout.Action action : actions()) {
                Slot row = layout.slot(action.key());
                Slot button = PartyPanelLayout.actionSlot(layout, action);
                assertNotNull(row);
                assertNotNull(button, () -> "no button placed for " + action.key());

                assertTrue(button.x() >= row.right(),
                        () -> "the button overlaps the row's own text: row " + row + ", button " + button);
                assertTrue(button.right() <= WIDTH,
                        () -> "the button left the column: " + button);
                assertTrue(button.y() >= row.y() && button.bottom() <= row.bottom(),
                        () -> "the button left its row vertically: row " + row + ", button " + button);
            }
        }

        @Test
        @DisplayName("the strip the scroll view places from is the rectangle the lookup gives")
        void theStripAndTheLookupAgree() {
            // The screen registers an action's button with `buttonStrip` as its *shape* -- the function
            // the scroll view applies to the row's slot -- while everything below asks
            // `actionSlot(layout, action)` for the same rectangle. One arithmetic, two entry points, and
            // this is what says so: if they ever drift, a scrolled button sits beside its own row instead
            // of in it, which only a panel tall enough to scroll would show.
            Layout layout = build(asOwner(), actions());

            for (PartyPanelLayout.Action action : actions()) {
                Slot row = layout.slot(action.key());
                assertEquals(PartyPanelLayout.actionSlot(layout, action), PartyPanelLayout.buttonStrip(row),
                        () -> "the shape and the lookup disagree for " + action.key());
            }
        }

        @Test
        @DisplayName("a row with no button gets no button slot, and neither does an unknown key")
        void noButtonNoSlot() {
            PartyPanelLayout.Action plain = new PartyPanelLayout.Action("plain", "Just a line", null, null);
            assertFalse(plain.hasButton(), "a button needs both a label and a command");

            Layout layout = build(asOwner(), List.of(plain));
            assertNotNull(layout.slot("plain"), "the row is placed; only its control is absent");
            assertNull(PartyPanelLayout.actionSlot(layout, plain));

            assertNull(PartyPanelLayout.actionSlot(layout,
                            PartyPanelLayout.Action.button("absent", "x", "Go", "/tasked party leave")),
                    "a key the layout does not hold has nowhere to put a button");
        }

        @Test
        @DisplayName("one action means one row's height, and no gap for the one that is absent")
        void noGapForAMissingAction() {
            Layout one = build(asOwner(), List.of(actions().get(0)));

            int expected = PartyPanelLayout.TITLE_HEIGHT
                    + PartyPanelLayout.TITLE_TAIL
                    + PartyRoster.ROW_HEIGHT * 3
                    + PartyRoster.ROW_GAP * 2
                    + PartyPanelLayout.SECTION_GAP
                    + 1
                    + PartyPanelLayout.SECTION_GAP
                    + PartyPanelLayout.ACTION_HEIGHT;
            assertEquals(expected, one.height(),
                    "one action means one action's height, and no gap after it");
        }

        @Test
        @DisplayName("isAction tells the footer's two keys from the rows")
        void isActionDiscriminates() {
            assertTrue(PartyPanelLayout.isAction(PartyPanelLayout.LEAVE));
            assertTrue(PartyPanelLayout.isAction(PartyPanelLayout.DISBAND));
            assertFalse(PartyPanelLayout.isAction("action:nonsense"));
            assertFalse(PartyPanelLayout.isAction("mode"), "an action *row* is not a footer action");
            assertFalse(PartyPanelLayout.isAction(PartyRoster.MEMBER_PREFIX + MEMBER),
                    "a member row is not an action");
        }
    }

    // ------------------------------------------------------------------
    // The rows
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the rows the panel offers")
    class Rows {

        /** A snapshot of no party, with whatever is waiting and whoever is online. */
        private static PartySnapshot noParty(List<PartySnapshot.Invite> invites, List<String> online) {
            return new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L),
                    List.of(), invites, online, "one_member", List.of());
        }

        @Test
        @DisplayName("no party offers Create named from the viewer, and one Accept per invitation")
        void noPartyOffersCreateAndAccepts() {
            PartySnapshot.Invite invite = new PartySnapshot.Invite(UUID.randomUUID(), "the crew");
            List<PartyPanelLayout.Action> rows = PartyPanelLayout.actions(
                    PartyRoster.of(Team.solo(MEMBER), MEMBER, PartyPanelLayoutTest::nameOf, id -> true),
                    noParty(List.of(invite), List.of()), "Cy");

            assertEquals("create", rows.get(0).key());
            assertEquals("/tasked party create Cy party", rows.get(0).command(),
                    "the name a player does not have to type is derived from their own");
            assertEquals("accept:" + invite.teamId(), rows.get(1).key());
            assertEquals("/tasked party accept", rows.get(1).command());
        }

        @Test
        @DisplayName("the derived name survives Brigadier, whatever the player is called")
        void createSurvivesBrigadier() {
            // The first version built `Ellipog's party`, and an apostrophe is not a character
            // Brigadier's unquoted argument is obliged to accept -- so the button produced a command
            // the server refused. This is the rule, not the anecdote: the derived name is sanitised.
            List<PartyPanelLayout.Action> rows = PartyPanelLayout.actions(
                    PartyRoster.of(Team.solo(MEMBER), MEMBER, PartyPanelLayoutTest::nameOf, id -> true),
                    noParty(List.of(), List.of()), "Ell'io");

            assertEquals("/tasked party create Ell_io party", rows.get(0).command());
        }

        @Test
        @DisplayName("a party offers the mode line, read-only, and no Create")
        void inAPartyOffersTheModeLine() {
            PartySnapshot snapshot = new PartySnapshot(UUID.randomUUID(), "the crew", OWNER,
                    List.of(new PartySnapshot.Member(OWNER, "Ada", TeamRole.OWNER)),
                    List.of(), List.of("Ada"), "pooled", List.of(OWNER));

            List<PartyPanelLayout.Action> rows = PartyPanelLayout.actions(asOwner(), snapshot, "Ada");

            assertEquals("mode", rows.get(0).key());
            assertEquals("Counts: pooled", rows.get(0).label());
            assertFalse(rows.get(0).hasButton(), "the counting rule is read, not pressed");
            assertTrue(rows.stream().noneMatch(row -> "create".equals(row.key())),
                    "a party that exists is not offered a second one");
        }

        @Test
        @DisplayName("one Invite per online player who is not in the party, never the viewer")
        void invitesEveryoneElse() {
            // "Bo" and "Cy" are the party; "Dee" is not, and is the only row that could work.
            PartySnapshot snapshot = new PartySnapshot(UUID.randomUUID(), "the crew", OWNER,
                    List.of(), List.of(), List.of("ada", "Bo", "Cy", "Dee"), "one_member", List.of());

            List<PartyPanelLayout.Action> rows = PartyPanelLayout.actions(asOwner(), snapshot, "Ada");

            assertEquals(List.of("mode", "invite:Dee"),
                    rows.stream().map(PartyPanelLayout.Action::key).toList(),
                    "the viewer's own name is not an invitation, and neither is a member's -- the "
                            + "server refuses both, and a row whose only answer is a refusal is not the "
                            + "useful thing the panel offers");
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
            Layout layout = PartyPanelLayout.build(asOwner(), actions(), width, MEASURE);
            for (Slot slot : layout.slots()) {
                assertTrue(slot.width() >= 0,
                        () -> "a negative width at column width " + width + ": " + slot);
            }
        }
    }
}
