package dev.ellipog.tenet.party;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways a party combines its members' counts.
 *
 * <h2>Why these are here rather than in the playthrough</h2>
 *
 * <p>Because {@link PartyMode#combine} is a pure function of a list of integers, and the party
 * playthrough is not. The rules are the part with the interesting edge cases — a tie, an empty party,
 * an offline owner, a count of zero — and every one of them can be asserted here in microseconds,
 * against a real server that takes twenty seconds to boot.
 *
 * <p>So the split is by what each can reach, which is the same split this project uses everywhere:
 * the playthrough asserts that a mode <i>reaches</i> the engine (a mode set by command changes what a
 * party's quest does), and this asserts what the mode <i>means</i>. A test of either alone would leave
 * the other free to be wrong — and this is the pair where that matters most, because a
 * {@link PartyMode} that computed correctly and was never consulted, or was consulted with the wrong
 * argument, would look identical from both sides.
 *
 * <h2>The invariant that runs through all three</h2>
 *
 * <p><b>A count of zero has no payer.</b> It is not a coincidence of three similar implementations;
 * it is what the engine uses to tell "nobody has any yet" from "somebody has some", and a mode that
 * reported a payer for a zero count would have a consuming task reach into an empty inventory. It is
 * asserted for every mode and every shape of zero rather than assumed of them, which is the only way
 * it stays true when a fourth mode is added.
 */
@DisplayName("Party modes: whose counts a party adds up")
class PartyModeTest {

    /** Two members, for the cases where the owner is one of them. */
    private static final List<Integer> TWO = List.of(0, 0);

    // ------------------------------------------------------------------
    // One member
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("one_member, which is the default")
    class OneMember {

        @Test
        @DisplayName("the largest single count wins, not the total")
        void largestWins() {
            // Four and three: a maximum of four, a sum of seven. The requirement in the shipped
            // questline is eight, so the two answers straddle it -- which is the case the mode exists
            // to distinguish, and the reason this is asserted with a pair that crosses a threshold
            // rather than with 8 and 0, where both rules agree.
            PartyMode.Tally tally = PartyMode.ONE_MEMBER.combine(List.of(4, 3), 0);

            assertEquals(4, tally.counted(), "a party where one member has four logs has four logs");
            assertEquals(0, tally.payer(), "and the payer is that member");
        }

        @Test
        @DisplayName("a tie goes to the earliest member, so the payer is stable rather than arbitrary")
        void tiesGoToTheEarliest() {
            // Both members holding eight is the ordinary case, not a corner: they both gathered. The
            // member order is sorted before this is called -- see ProgressService.onlineMembersOf --
            // so "earliest" is a reproducible answer rather than whichever the map felt like.
            PartyMode.Tally tally = PartyMode.ONE_MEMBER.combine(List.of(8, 8), 1);

            assertEquals(8, tally.counted());
            assertEquals(0, tally.payer(), "strictly greater, so the first of the two keeps it");

            assertEquals(1, PartyMode.ONE_MEMBER.combine(List.of(2, 8), 0).payer(),
                    "and a strictly larger count takes it from whoever held it");
        }

        @Test
        @DisplayName("the owner's index is ignored")
        void ownerIsIrrelevant() {
            // The mode does not read the index, and asserting that is not pedantry: ONE_MEMBER and
            // OWNER_ONLY would otherwise be indistinguishable on a party of one, and a mode that
            // quietly started reading the index would pass every test written for the solo case.
            assertEquals(PartyMode.ONE_MEMBER.combine(List.of(1, 5), 0),
                    PartyMode.ONE_MEMBER.combine(List.of(1, 5), 1));
            assertEquals(PartyMode.ONE_MEMBER.combine(List.of(1, 5), -1),
                    PartyMode.ONE_MEMBER.combine(List.of(1, 5), 0));
        }
    }

    // ------------------------------------------------------------------
    // Pooled
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("pooled")
    class Pooled {

        @Test
        @DisplayName("the counts are added, and it is the only mode that shares the cost")
        void countsAreAdded() {
            PartyMode.Tally tally = PartyMode.POOLED.combine(List.of(4, 3), 0);

            assertEquals(7, tally.counted(), "four and three is seven, and seven is not four");
            assertEquals(0, tally.payer(), "the largest holder is who the engine starts paying from");

            assertTrue(PartyMode.POOLED.takesFromEveryone(),
                    "and the cost is shared, because the count was -- a count spread across two "
                            + "inventories cannot be settled by reaching into one of them");
            assertFalse(PartyMode.ONE_MEMBER.takesFromEveryone());
            assertFalse(PartyMode.OWNER_ONLY.takesFromEveryone());
        }

        @Test
        @DisplayName("the members the other two modes cannot finish are finished here")
        void poolingReachesWhatTheOthersCannot() {
            // The whole point of the mode, in one assertion: nobody has eight, so neither single-count
            // mode can satisfy a gather-eight task, and together they have exactly eight.
            List<Integer> spread = List.of(2, 3, 3);

            assertEquals(3, PartyMode.ONE_MEMBER.combine(spread, 0).counted(),
                    "one_member sees the largest member's three");
            assertEquals(8, PartyMode.POOLED.combine(spread, 0).counted(),
                    "pooled sees eight, so the party can do what none of them can");
        }

        @Test
        @DisplayName("the payer is the largest holder even when the count is the whole party's")
        void payerIsTheLargestHolder() {
            // Not "who the count came from" -- it came from everybody. It is where the engine starts,
            // and under this mode it does not finish there. Naming the largest holder is the
            // difference between paying out of the inventory that has something and paying out of one
            // that has nothing.
            // Five is at index 1 in the first and index 0 in the second, so the answer moves with the
            // *position* of the largest holder rather than being the last member or the first. Both
            // halves are needed: one assertion alone would pass for a mode that always answered 1.
            assertEquals(1, PartyMode.POOLED.combine(List.of(1, 5, 2), 0).payer());
            assertEquals(0, PartyMode.POOLED.combine(List.of(5, 1, 2), 0).payer());
        }
    }

    // ------------------------------------------------------------------
    // The owner alone
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("owner_only")
    class OwnerOnly {

        @Test
        @DisplayName("another member's items do not count at all")
        void onlyTheOwnerCounts() {
            // Deliberately the reverse of the default's answer for the same input, so the two modes
            // cannot be the same code wearing two names. Under one_member the friend's eight is the
            // party's; under owner_only the party has nothing.
            List<Integer> friendHasEverything = List.of(0, 8);

            assertEquals(8, PartyMode.ONE_MEMBER.combine(friendHasEverything, 0).counted(),
                    "the default counts the member who has them, whoever that is");
            assertEquals(0, PartyMode.OWNER_ONLY.combine(friendHasEverything, 0).counted(),
                    "and this mode counts one named member and nobody else");
        }

        @Test
        @DisplayName("an offline owner counts as zero, which follows from the rule rather than bolting onto it")
        void offlineOwnerCountsNothing() {
            // -1 is the index of a member who is not in the online list. The mode says the owner's
            // inventory is the only one that counts, and an inventory that is not loaded is not one
            // that can be counted -- so this is the rule's own answer rather than an edge case. The
            // alternative, falling back to another member, would be a different mode wearing this
            // one's name.
            assertEquals(PartyMode.Tally.NONE, PartyMode.OWNER_ONLY.combine(List.of(8, 8), -1));

            // And an index that is not a member at all arrives at the same answer, correctly: both
            // cases are "there is no inventory here that this mode is allowed to count".
            assertEquals(PartyMode.Tally.NONE, PartyMode.OWNER_ONLY.combine(TWO, 99));
        }

        @Test
        @DisplayName("the owner having nothing is nothing, even with the party full of items")
        void emptyOwnerIsEmpty() {
            PartyMode.Tally tally = PartyMode.OWNER_ONLY.combine(List.of(0, 8, 8), 0);

            assertEquals(0, tally.counted());
            assertFalse(tally.hasPayer());
        }
    }

    // ------------------------------------------------------------------
    // The invariant every mode keeps
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("every mode")
    class EveryMode {

        @Test
        @DisplayName("an empty party is nothing, and has nobody to blame")
        void emptyPartyCountsNothing() {
            for (PartyMode mode : PartyMode.values()) {
                PartyMode.Tally tally = mode.combine(List.of(), 0);

                assertEquals(PartyMode.Tally.NONE, tally, () -> mode.id() + " on an empty party");
                assertFalse(tally.hasPayer(), () -> mode.id() + " invented a payer for an empty party");
            }
        }

        @Test
        @DisplayName("a count of zero never has a payer")
        void zeroHasNoPayer() {
            // The invariant, swept over every mode and every shape of zero. `hasPayer` is what a
            // consuming task asks before reaching into an inventory, so a mode that answered true
            // here would take items from a player who has none -- and the task would be recorded as
            // satisfied either way, so nothing would report it.
            for (PartyMode mode : PartyMode.values()) {
                for (int ownerIndex = -1; ownerIndex < 3; ownerIndex++) {
                    // Copied to a final local because the loop variable moves and the lambda is
                    // deferred. A compile error rather than a silent bug, and worth keeping as a
                    // local rather than inlining: the message wants the index that failed.
                    final int at = ownerIndex;
                    PartyMode.Tally tally = mode.combine(TWO, at);

                    assertFalse(tally.hasPayer(),
                            () -> mode.id() + " gave a zero count a payer, at owner index " + at);
                    assertEquals(0, tally.counted(), () -> mode.id() + " counted something from nothing");
                }
            }
        }

        @Test
        @DisplayName("a first member with something always has a payer")
        void anyCountHasAPayer() {
            // The other half, and the half that would catch a mode that answered NONE too eagerly --
            // which is how the invariant above could be satisfied by a mode that returned NONE always.
            for (PartyMode mode : PartyMode.values()) {
                PartyMode.Tally tally = mode.combine(List.of(5), 0);

                assertEquals(5, tally.counted(), () -> mode.id() + " lost a count it was given");
                assertTrue(tally.hasPayer(), () -> mode.id() + " counted five and named nobody holding it");
                assertEquals(0, tally.payer());
            }
        }

        @Test
        @DisplayName("the default is one_member, which is what the engine did before there was a choice")
        void defaultIsTheOldBehaviour() {
            // The default is not arbitrary. It is what evaluateTeam did unconditionally before a party
            // could choose, so a world whose file has never been written counts exactly as it counted
            // then -- and "nobody chose" and "we chose the middle option" both land here rather than
            // somewhere new.
            assertSame(PartyMode.ONE_MEMBER, PartyMode.DEFAULT);
        }
    }

    // ------------------------------------------------------------------
    // The names, which a file and a command both spell
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the spelling")
    class Spelling {

        @Test
        @DisplayName("every mode round-trips through its own id")
        void idsRoundTrip() {
            for (PartyMode mode : PartyMode.values()) {
                assertSame(mode, PartyMode.byId(mode.id()).orElseThrow(),
                        () -> "a mode must be found by the name it writes: " + mode.id());
            }
        }

        @Test
        @DisplayName("an id is the enum's own name, lowercased, and an unknown name is not a mode")
        void idsAreLowercaseAndUnknownNamesAreEmpty() {
            // The file format and the command share this spelling deliberately -- a codec on the enum
            // would be a second one, and the two would drift the first time a mode was renamed. So what
            // is asserted is the *rule* the spelling follows rather than a property of the three names:
            // an earlier version of this checked that every id contained an underscore, which is true
            // of one_member and owner_only and false of pooled -- an assertion about the names as they
            // happen to be, dressed up as one about the convention.
            for (PartyMode mode : PartyMode.values()) {
                assertEquals(mode.name().toLowerCase(java.util.Locale.ROOT), mode.id(),
                        () -> mode.name() + " should spell as its own name lowercased, and it spells "
                                + mode.id() + " -- the file and the command both read this, so a second "
                                + "spelling here is a second spelling everywhere");
            }

            assertTrue(PartyMode.byId("nonsense").isEmpty(),
                    "an unknown mode is empty rather than defaulted, so the command can say so");
            assertTrue(PartyMode.byId(null).isEmpty());
            assertTrue(PartyMode.byId("").isEmpty());
        }

        @Test
        @DisplayName("byId is forgiving about case and nothing else")
        void byIdTrimsAndLowercases() {
            // A player typing a mode by hand should not have to get the case right; a typo should
            // still be a typo.
            assertSame(PartyMode.POOLED, PartyMode.byId("POOLED").orElseThrow());
            assertSame(PartyMode.POOLED, PartyMode.byId("  pooled  ").orElseThrow());
            assertTrue(PartyMode.byId("pooled_").isEmpty(), "a near miss is still a miss");
        }

        @Test
        @DisplayName("ids() names all of them, for the error that has to list the options")
        void idsListsEverything() {
            String all = PartyMode.ids();

            for (PartyMode mode : PartyMode.values()) {
                assertTrue(all.contains(mode.id()),
                        () -> "the options list should name " + mode.id() + ", and it reads: " + all);
            }
        }

        @Test
        @DisplayName("every mode has a description, because one is shown to a player")
        void everyModeExplainsItself() {
            for (PartyMode mode : PartyMode.values()) {
                assertFalse(mode.description().isBlank(), () -> mode.id() + " has no description");
            }
        }
    }
}
