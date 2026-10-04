package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.client.ui.party.PartyRoster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster's wire format, as a plain function.
 *
 * <h2>Why this is a test of a string rather than of a packet</h2>
 *
 * <p>{@link PartySnapshot} owns the format in both directions, and {@link PartySyncPayload} only
 * carries the result — so the cases worth checking are string cases: a name that contains the
 * separator, a truncated message, a role this build no longer has. Testing those through a netty
 * buffer would test them through the buffer as well, which is a second thing that can be wrong and
 * says nothing about this one. {@code SyncWiringTest} covers the buffer; this covers the format.
 *
 * <p>This file is what {@code PartySnapshot}'s own javadoc refers to when it says the format is
 * "tested on its own". It did not exist when that sentence was written, which is exactly the kind of
 * claim this project's {@code check_docs.py} exists to stop in documents and cannot stop in javadoc —
 * so the honest note is that the fix was to write the test, not to delete the sentence.
 */
@DisplayName("The party roster's wire format")
class PartySnapshotTest {

    private static final UUID TEAM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID MEMBER = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** The separator the format uses. Duplicated on purpose: a test should state the format, not agree with it. */
    private static final String SEP = "\u001f";

    private static PartySnapshot sample() {
        return new PartySnapshot(TEAM, "the crew", OWNER,
                List.of(new PartySnapshot.Member(OWNER, "Ellipog", TeamRole.OWNER),
                        new PartySnapshot.Member(MEMBER, "Tester", TeamRole.MEMBER)),
                List.of(new PartySnapshot.Invite(TEAM, "another party", MEMBER, "Tester", 44L)),
                List.of("Ellipog", "Tester"),
                "pooled",
                List.of(OWNER),
                List.of(new PartySnapshot.SentInvite("Tester", 33L)),
                new TeamPolicy(true, false),
                8,
                List.of(new PartySnapshot.PublicParty(TEAM, "the crew", 2, 8)));
    }

    @Nested
    @DisplayName("Round tripping")
    class RoundTrip {

        @Test
        @DisplayName("every field survives pack then unpack")
        void everyFieldSurvives() {
            PartySnapshot back = PartySnapshot.unpack(sample().pack());

            assertEquals(TEAM, back.teamId());
            assertEquals("the crew", back.teamName());
            assertEquals(OWNER, back.owner());
            assertEquals(2, back.members().size(), "both members should come back");
            assertEquals("pooled", back.mode(), "the mode is on the header line, so a truncation "
                    + "past it cannot lose it");
            assertEquals("Ellipog", back.members().get(0).name(),
                    "and the name as well as the id, which is the whole reason a name is sent");
            assertEquals(TeamRole.OWNER, back.members().get(0).role());
            assertEquals(1, back.invites().size(), "an invitation is a reason the panel has a button");
            assertEquals("another party", back.invites().get(0).teamName());
            assertEquals(List.of("Ellipog", "Tester"), back.online());
            assertEquals(List.of(OWNER), back.present(),
                    "and who was connected, which is what the marker on a member's row is drawn from "
                            + "-- a field the round trip would otherwise drop in silence, on a test whose "
                            + "name says every field survives");

            // The fields the invitation row grew, and the party's own settings. Each is asserted in
            // its non-default state: an assertion against a default is an assertion a reader that
            // ignored the field entirely would also pass.
            PartySnapshot.Invite invite = back.invites().get(0);
            assertEquals(MEMBER, invite.inviter(), "who sent the invitation");
            assertEquals("Tester", invite.inviterName(), "and their name, so the row can draw it");
            assertEquals(44L, invite.age(), "and how long ago, so an old invitation can be told from a new one");
            assertTrue(invite.hasTime());
            assertEquals(List.of(new PartySnapshot.SentInvite("Tester", 33L)), back.sent(),
                    "the party's outgoing invitations, which its own panel lists with a Cancel");
            assertEquals(new TeamPolicy(true, false), back.policy(),
                    "both switches, in the state that is not the default, so a reader that dropped "
                            + "the settings line cannot pass");
            assertEquals(8, back.memberLimit(), "and the cap the header writes as 3/8");
            assertEquals(1, back.publicParties().size(), "and the browse list a solo player joins from");
            assertEquals(2, back.publicParties().get(0).members());
        }

        @Test
        @DisplayName("an empty roster round trips to an empty roster rather than to nothing")
        void anEmptyRosterSurvives() {
            // `none()` is a real answer — "I am in no party" — and it has to survive the wire, because
            // a client that could not tell "no party" from "no message" would keep drawing the last
            // party it was told about. See `sendNoPartyTo`.
            PartySnapshot back = PartySnapshot.unpack(PartySnapshot.none().pack());

            assertFalse(back.isPresent(), "nobody is in this party");
            assertEquals(PartySnapshot.none().teamId(), back.teamId(),
                    "and the framing survived, so this is a read roster of nobody rather than a "
                            + "message that failed to parse");
        }

        @Test
        @DisplayName("a name containing the separator is cleaned rather than split")
        void aNameContainingTheSeparatorIsCleaned() {
            // The separator is a unit separator, which a player cannot type — and it is still
            // stripped on the way out, because the failure if it ever arrived would be a member list
            // that parses into the wrong number of fields. A roster with somebody *missing* is worse
            // than one with a character removed from a name, so the direction of the fallback matters.
            PartySnapshot hostile = new PartySnapshot(TEAM, "cre" + SEP + "w", OWNER,
                    List.of(new PartySnapshot.Member(OWNER, "Elli" + SEP + "pog", TeamRole.OWNER)),
                    List.of(), List.of(), "one_member", List.of(),
                    List.of(), TeamPolicy.DEFAULT, 0, List.of());

            PartySnapshot back = PartySnapshot.unpack(hostile.pack());

            // "crew", not "cre<sep>w" and not "cre". The character is *removed* rather than replaced,
            // and the reason that matters is the length: a substitution would still leave the name the
            // same width as the thing that was mis-sent, where removal produces the name the author
            // plainly meant.
            assertEquals("crew", back.teamName(),
                    "the separator is removed on the way out, so the name survives as one field");
            assertEquals(1, back.members().size(),
                    "and the member list has not gained a field, which is what the removal prevents");
            assertEquals("Ellipog", back.members().get(0).name());
        }
    }

    @Nested
    @DisplayName("Malformed input, which never throws")
    class Malformed {

        @Test
        @DisplayName("a truncated message loses whole members rather than corrupting the earlier ones")
        void aTruncatedMessageLosesWholeMembers() {
            // The format is one line per entry on purpose, so a truncation lands mid-line and takes
            // that line with it. `unpack` skips the partial trailing member rather than throwing: a
            // client disconnected over a roster it could have drawn most of would be a worse failure
            // than one missing a row.
            //
            // Cut *inside the second member's line*, found rather than counted. The first version of
            // this test cut a fixed twelve characters off the end, which lands in the online-player
            // lines -- so the members were both intact and the assertion below read 2 while claiming
            // to test a lost member. A byte offset from the end is an offset that moves whenever the
            // fixture grows, and the test goes on passing while testing something else.
            String packed = sample().pack();
            int secondMember = packed.indexOf("m" + SEP + MEMBER);
            assertTrue(secondMember > 0, "fixture sanity: the second member should be in the packed form");
            String truncated = packed.substring(0, secondMember + 5);

            PartySnapshot back = PartySnapshot.unpack(truncated);

            assertEquals(1, back.members().size(),
                    "the first member is intact and the second is gone, rather than the whole "
                            + "message being rejected. Two here means the cut did not land in a "
                            + "member line -- see the note above");
            assertEquals("Ellipog", back.members().get(0).name(),
                    "and it is the *first* member, so the survivee is the one the truncation did not "
                            + "reach rather than one reparsed from a fragment");
        }

        @Test
        @DisplayName("an unreadable blob is no party, not an exception")
        void anUnreadableBlobIsNoParty() {
            for (String junk : new String[] {"", "not a roster", "one\ntwo\nthree", "\n\n\n"}) {
                PartySnapshot back = PartySnapshot.unpack(junk);
                assertFalse(back.isPresent(),
                        "an unreadable message should draw the empty state: " + junk);
            }
            assertFalse(PartySnapshot.unpack(null).isPresent(), "and so should a null");
        }

        @Test
        @DisplayName("a member whose role this build does not know is dropped, not defaulted")
        void anUnknownRoleDropsTheMember() {
            // The whole member goes, rather than being defaulted to MEMBER. A rank decides whether a
            // Remove button appears, and guessing one is guessing about authority — so the member is
            // absent, which the panel draws as a shorter list, rather than present with a rank nobody
            // chose.
            String packed = TEAM + "\ncrew\n" + OWNER + "\none_member\n"
                    + "m" + SEP + MEMBER + SEP + "NOT_A_ROLE" + SEP + "Tester\n";

            PartySnapshot back = PartySnapshot.unpack(packed);

            assertTrue(back.members().isEmpty(),
                    "a role this build cannot read is not a role it should invent");
        }

        @Test
        @DisplayName("an unknown line tag is skipped, so an older client reads what it understands")
        void anUnknownTagIsSkipped() {
            // The property that makes the format tolerant: a newer server may add a kind of line, and
            // a reader that does not know the tag skips that line and reads the rest. It is the
            // difference between "a new feature is invisible to an old client" and "a new feature
            // breaks an old client".
            String packed = TEAM + "\ncrew\n" + OWNER + "\npooled\n"
                    + "z" + SEP + "this build has never heard of this\n"
                    + "m" + SEP + OWNER + SEP + "OWNER" + SEP + "Ellipog\n";

            PartySnapshot back = PartySnapshot.unpack(packed);

            assertEquals(1, back.members().size(), "the member after the unknown line still arrives");
            assertEquals("Ellipog", back.members().get(0).name());
        }
    }

    @Nested
    @DisplayName("Growing the format, from both sides")
    class GrowingTheFormat {

        @Test
        @DisplayName("an invitation line from before the sender and time reads as an unknown sender")
        void anOldInvitationLineReads() {
            // The shape this line had before the panel grew its invitation rows: two fields. The
            // reader must take what it recognises rather than fail or mis-parse -- this is what a
            // snapshot from an older server looks like.
            String packed = TEAM + "\ncrew\n" + OWNER + "\none_member\n"
                    + "i" + SEP + TEAM + SEP + "another party\n";

            PartySnapshot back = PartySnapshot.unpack(packed);

            PartySnapshot.Invite invite = back.invites().get(0);
            assertEquals("another party", invite.teamName());
            assertEquals(new UUID(0L, 0L), invite.inviter(), "no sender travelled");
            assertFalse(invite.hasTime(), "and no time -- zero is the honest unknown, see TeamInvite");
        }

        @Test
        @DisplayName("a settings line this build cannot read leaves the default policy in place")
        void junkSettingsLeaveTheDefault() {
            // A malformed settings line must not decide who may invite: a boolean that is neither
            // "true" nor "false" keeps the default, and an unreadable cap reads as unknown.
            String packed = TEAM + "\ncrew\n" + OWNER + "\none_member\n"
                    + "g" + SEP + "maybe" + SEP + "maybe" + SEP + "lots\n";

            PartySnapshot back = PartySnapshot.unpack(packed);

            assertEquals(TeamPolicy.DEFAULT, back.policy());
            assertEquals(0, back.memberLimit(), "zero is 'the server did not say', not a real cap");
        }

        @Test
        @DisplayName("a snapshot from before the new lines keeps every default")
        void anOlderSnapshotKeepsEveryDefault() {
            // The other direction: a client from this build reading a server that predates the
            // settings, the outgoing list and the browse list. Every added field has a documented
            // default, and this is what asserts it is actually reached.
            String packed = TEAM + "\ncrew\n" + OWNER + "\npooled\n"
                    + "m" + SEP + OWNER + SEP + "OWNER" + SEP + "Ellipog\n";

            PartySnapshot back = PartySnapshot.unpack(packed);

            assertEquals(TeamPolicy.DEFAULT, back.policy());
            assertEquals(0, back.memberLimit());
            assertTrue(back.sent().isEmpty());
            assertTrue(back.publicParties().isEmpty());
        }

        @Test
        @DisplayName("the idle snapshot carries the defaults, not nothing")
        void noneCarriesTheDefaults() {
            PartySnapshot empty = PartySnapshot.none();

            assertEquals(TeamPolicy.DEFAULT, empty.policy(),
                    "so a switch drawn from an empty snapshot reads its documented initial state");
            assertEquals(0, empty.memberLimit());
            assertTrue(empty.sent().isEmpty());
            assertTrue(empty.publicParties().isEmpty());
        }
    }

    @Nested
    @DisplayName("As the panel draws it")
    class AsARoster {

        @Test
        @DisplayName("a real snapshot becomes a real roster, owner first")
        void aRealSnapshotBecomesARealRoster() {
            PartyRoster roster = PartySnapshot.toRoster(sample(), MEMBER);

            assertTrue(roster.isReal(), "two members is a party");
            assertEquals(2, roster.memberCount());
            assertEquals("Ellipog", roster.members().get(0).name(),
                    "and the owner is first, which is the roster's rule rather than the wire's — "
                            + "the member order that arrived here has the owner first, but the sort "
                            + "is what guarantees it");
            assertTrue(roster.members().get(1).self(), "the viewer's own row is marked");
        }

        @Test
        @DisplayName("a snapshot of nobody becomes a roster of nobody, not a real empty party")
        void anEmptySnapshotBecomesASoloRoster() {
            // The guard that stops a blank card. `toRoster` rebuilds a `Team` with persistent = true,
            // because a membership that arrived over a wire is a real party by construction — which is
            // true of a snapshot *with* members and false of one without. An empty snapshot that
            // became a persistent empty team made the panel skip its empty state *and* draw no rows.
            PartyRoster roster = PartySnapshot.toRoster(PartySnapshot.none(), MEMBER);

            assertFalse(roster.isReal(),
                    "an empty roster must read as 'you are not in a party', which is the state the "
                            + "panel draws a Create button for");
            assertEquals(1, roster.memberCount(),
                    "a solo team has one row — the viewer — and that is right rather than a leak");
        }

        @Test
        @DisplayName("a member's marker comes from the ids that travelled rather than from their name")
        void presenceIsById() {
            // The check this replaced compared a member's name against the online *names*, which is a
            // guess where the server has the answer: it goes wrong on a rename, and it disagrees with
            // itself about case. Two members whose names are swapped is the case that tells them apart.
            PartySnapshot swapped = new PartySnapshot(TEAM, "the crew", OWNER,
                    List.of(new PartySnapshot.Member(OWNER, "Tester", TeamRole.OWNER),
                            new PartySnapshot.Member(MEMBER, "Ellipog", TeamRole.MEMBER)),
                    List.of(), List.of("Ellipog"), "one_member", List.of(MEMBER),
                    List.of(), TeamPolicy.DEFAULT, 0, List.of());

            PartyRoster roster = PartySnapshot.toRoster(swapped, OWNER);

            assertTrue(memberFor(roster, MEMBER).online(),
                    "the member whose *id* was named as present is online, whatever they are called");
            assertFalse(memberFor(roster, OWNER).online(),
                    "and the one whose id was not named is not -- by name this would be exactly "
                            + "backwards, since the online list holds the other one's new name");
        }

        private static PartyRoster.Member memberFor(PartyRoster roster, UUID id) {
            return roster.members().stream()
                    .filter(member -> member.id().equals(id)).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("the viewer is the only thing that decides which row is theirs")
        void theViewerDecidesWhichRowIsTheirs() {
            PartyRoster asOwner = PartySnapshot.toRoster(sample(), OWNER);
            PartyRoster asMember = PartySnapshot.toRoster(sample(), MEMBER);

            assertTrue(asOwner.members().get(0).self(), "from the owner's seat, the owner is 'you'");
            assertTrue(asMember.members().get(1).self(), "and from the member's seat, the member is");
            assertFalse(asMember.members().get(0).self(),
                    "the two views differ, which is what makes this a check rather than a constant");
            assertNotEquals(asOwner.members().get(0).self(), asMember.members().get(0).self());
        }
    }

    @Nested
    @DisplayName("A roster built for the player who is arriving")
    class Arrival {

        /**
         * The player list a login can offer: everybody <b>except</b> the player who is arriving.
         *
         * <p>That is the whole of the fault these two cases pin. {@code PLAYER_JOIN} fires while the
         * connection is still being accepted, so the list does not answer for the player who is joining
         * — and a roster that trusted it about them named their own row with eight characters of their
         * id, and marked them offline to themselves.
         */
        private PartySnapshot arriving() {
            return sample().withSelf(sample().members(), MEMBER, "Tester", List.of("Ellipog"), List.of());
        }

        @Test
        @DisplayName("names them from the one list that does not have them yet")
        void theArrivingPlayerIsNamed() {
            PartySnapshot.Member mine = arriving().members().stream()
                    .filter(member -> member.id().equals(MEMBER))
                    .findFirst().orElseThrow();

            assertEquals("Tester", mine.name(),
                    "the arriving player's own row must carry their name, not the first eight "
                            + "characters of their id -- which is what a lookup that cannot answer falls "
                            + "back to, and what a login drew");
        }

        @Test
        @DisplayName("has them online to themselves, and touches nobody else")
        void theArrivingPlayerIsOnline() {
            PartySnapshot arriving = arriving();

            assertTrue(arriving.online().contains("Tester"),
                    "they are online to themselves: they are missing from the list this was built from, "
                            + "and the roster a login sends is the one that has to say so");
            assertTrue(arriving.online().contains("Ellipog"),
                    "and the entries the list did have are still there");
            assertEquals("Ellipog", arriving.members().get(0).name(),
                    "the other rows kept their names");
            assertEquals(2, arriving.members().size(), "and nobody was added or dropped");
        }
    }
}
