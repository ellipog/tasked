package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.api.teams.Teams;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A party's membership, as it travels to a client.
 *
 * <h2>Why the roster has to come over the wire at all</h2>
 *
 * <p>Because a party is <b>server state</b>. {@code Team}, the roles and who may remove whom all live
 * on the server, and the client has none of it — the tab list says who is online, not who is in what
 * party or with what rank. So a panel that drew a roster would have nothing to draw it from.
 *
 * <p>That is worth stating because the alternative looks plausible from the client: a player's own
 * party is known to them, and the tempting shortcut is to remember it locally when they joined. It
 * fails exactly where a roster is most useful — somebody else's membership changing — and it fails
 * <i>silently</i>, leaving a panel showing a party that has since gained or lost a member.
 *
 * <h2>The pickled form, and why there is one at all</h2>
 *
 * <p>{@code StreamCodec.composite} has overloads for one through six components and no more, which is
 * the ceiling {@code ProgressSyncPayload} records at length — and a member list is a variable-length
 * thing a composite cannot express anyway. So the members travel as one string, and this class owns
 * the format in both directions.
 *
 * <p>Keeping the packing here rather than in the payload is what makes it <b>testable without a
 * buffer</b>: {@code PartySnapshotTest} round-trips it as a plain function, which is where the cases
 * that matter live — an empty name, a name containing the separator, a truncated message, a role that
 * no longer exists. A format tested through a netty buffer is a format whose edge cases are tested
 * through a buffer.
 *
 * <h2>The separator, and what happens to a name that contains it</h2>
 *
 * <p>Fields are separated by {@code \u001f}, a unit separator: not a character a player can type, and
 * not one a name is validated for. It is still <b>stripped</b> rather than trusted on the way out,
 * because the failure if it ever arrived would be a member list that parses into the wrong number of
 * fields — and a roster with somebody missing is worse than a roster with a character removed from a
 * name.
 *
 * <h2>Growing the format without breaking either end</h2>
 *
 * <p>Every list is a tagged line, and a reader skips a tag it does not know. Extending an existing
 * line is done by appending fields and reading with a limit, which is what makes the invitation line
 * safe to grow: a client from before this build reads the first two fields it expects and ignores the
 * sender and the time, instead of mis-parsing them as part of the name. A payload from before it reads
 * as a party whose policy is the default and whose invitations have no time — the honest reading of
 * "the server did not say".
 *
 * @param teamId       the party's id, which is also the key its progress is stored under
 * @param teamName     its name. Empty for a team that has none
 * @param owner        who owns it
 * @param members      everyone in it, in no particular order. The client sorts for display
 * @param invites      the invitations <b>this recipient</b> holds, with who sent each and when
 * @param online       every connected player's name, which the panel's Invite search filters
 * @param mode         the party's quest-counting mode, by id
 * @param present      the ids of everyone online, which is what decides a member's status dot
 * @param sent         the invitations <b>this party</b> has out, for the panel's outgoing list
 * @param policy       what the members may do beyond their role. See {@code TeamPolicy}
 * @param memberLimit  how many members the party may hold, or zero when the source cannot say
 * @param publicParties open parties a solo player may join, capped and sorted. Empty for a member
 */
public record PartySnapshot(UUID teamId, String teamName, UUID owner, List<Member> members,
                            List<Invite> invites, List<String> online, String mode,
                            List<UUID> present, List<SentInvite> sent, TeamPolicy policy,
                            int memberLimit, List<PublicParty> publicParties) {

    /** The field separator. See the class note on why it is stripped from names. */
    private static final String SEP = "\u001f";

    /** How many fields one member occupies. */
    private static final int MEMBER_FIELDS = 3;

    /**
     * The most open parties one snapshot carries.
     *
     * <p>A browse list is a convenience, not a directory: the panel shows it on the solo screen, and a
     * server where two hundred parties are open would put all of them in every solo player's packet for
     * a list nobody reads to the end of. The cap is applied after sorting by name, so which ones travel
     * is stable rather than dependent on the source's iteration order.
     */
    public static final int MAX_PUBLIC_PARTIES = 20;

    /**
     * One member.
     *
     * @param id   who they are
     * @param name what to call them. Resolved on the server, because the client's tab list may not
     *             have heard of somebody who has just been invited — and a roster that said "unknown"
     *             for a player it could have named is a roster that looks broken
     * @param role their rank
     */
    public record Member(UUID id, String name, TeamRole role) {
    }

    /**
     * A party this player has been asked to join, and has not answered.
     *
     * <h2>Why an invitation travels at all</h2>
     *
     * <p>Because the panel is meant to work without typing, and "you have been invited" is a thing the
     * client cannot derive: an invitation is server state that lives against a player who is not in the
     * party yet, so nothing in the roster or the tree implies it. A panel without this shows an empty
     * state and no way to answer an invitation that is waiting.
     *
     * <h2>The sender and the time, and where they come from</h2>
     *
     * <p>The invitation row shows whose party it is, who asked and when, so all three travel — the
     * sender because an invitee deciding may want to know which member thought of them, and the time so
     * an old invitation can be told from one that arrived a minute ago. A source that cannot say keeps
     * {@code at == 0} and the owner as the sender; see {@code TeamInvite}.
     *
     * @param teamId      the party that invited them
     * @param teamName    its name, so the client does not need a second round trip to say what it is
     * @param inviter     who sent it. May be the owner when the source cannot distinguish
     * @param inviterName the sender's name, resolved server-side like a member's
     * @param age         how long ago it was sent, in ticks, counted on the <b>server's</b> clock when
     *                    the snapshot was built — or zero when the source cannot say. An age rather
     *                    than a timestamp because the client has no server clock: a raw game time
     *                    would render as however old the world is
     */
    public record Invite(UUID teamId, String teamName, UUID inviter, String inviterName, long age) {

        /** Whether the server could say when this was sent. See the class note: zero means unknown. */
        public boolean hasTime() {
            return age > 0L;
        }
    }

    /**
     * An invitation this party has out, and has not had answered.
     *
     * <p>What the panel's outgoing list draws and its Cancel button acts on. Carries the invited
     * player's <b>name</b> rather than their id because the cancel goes through a command, and a
     * command takes a name — a player who logged off can still be uninvited by the name the list
     * shows.
     *
     * @param name who was invited
     * @param age  how long ago it was sent, in ticks on the server's clock, or zero when unknown
     */
    public record SentInvite(String name, long age) {
    }

    /**
     * A party anybody may join, as the solo screen lists it.
     *
     * @param teamId  the party to join
     * @param name    what it calls itself
     * @param members how many are in it now
     * @param limit   how many may be, or zero when the source cannot say
     */
    public record PublicParty(UUID teamId, String name, int members, int limit) {
    }

    /** A snapshot of nobody being in any party, which is a real answer rather than an absent one. */
    public static PartySnapshot none() {
        return new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of(), List.of(), List.of(),
                "one_member", List.of(), List.of(), TeamPolicy.DEFAULT, 0, List.of());
    }

    /** Whether this describes a party at all. False is the answer for a player who is alone. */
    public boolean isPresent() {
        return !members.isEmpty();
    }

    /**
     * The roster as one string.
     *
     * <p>The header is four lines and every entry is one. A truncated message therefore loses whole
     * entries rather than corrupting the ones before it, and {@link #unpack} ignores a partial trailing
     * entry rather than throwing — which is the right direction, because the alternative is a client
     * disconnected by a malformed packet over a roster it could have drawn most of.
     */
    public String pack() {
        StringBuilder out = new StringBuilder();
        // The mode on the header line, because it belongs to the party rather than to a player: one
        // string whose absence is answered with the default, so a snapshot from an older server reads
        // as counting the default way rather than as unreadable.
        out.append(teamId).append('\n').append(clean(teamName)).append('\n').append(owner).append('\n')
                .append(clean(mode)).append('\n');
        for (Member member : members) {
            out.append('m').append(SEP)
                    .append(member.id()).append(SEP)
                    .append(member.role().name()).append(SEP)
                    .append(clean(member.name())).append('\n');
        }
        // The invitation line grew a sender and a time by *appending* them. A reader written against
        // the old shape splits with a limit of two and keeps teamId and teamName; the extra fields are
        // ignored rather than read as part of the name. See the class note.
        for (Invite invite : invites) {
            out.append('i').append(SEP)
                    .append(invite.teamId()).append(SEP)
                    .append(clean(invite.teamName())).append(SEP)
                    .append(invite.inviter()).append(SEP)
                    .append(clean(invite.inviterName())).append(SEP)
                    .append(invite.age()).append('\n');
        }
        for (SentInvite invite : sent) {
            out.append('s').append(SEP)
                    .append(clean(invite.name())).append(SEP)
                    .append(invite.age()).append('\n');
        }
        for (String name : online) {
            // No id: the client invites by *name*, because a command takes a name and the player it
            // names may not be anyone this client has a uuid for. See this class's note on why the
            // action goes through a command at all.
            out.append('o').append(SEP).append(clean(name)).append('\n');
        }
        for (UUID who : present) {
            // Which members are connected, by **id**, as a line of its own rather than as a field on the
            // member's line. Two reasons, and the second is what decided it: the member line is split
            // with a limit, so a fourth field would be read as part of the name by anybody who did not
            // know about it -- and this tag is skipped by a reader that does not recognise it, which is
            // the tolerance the format already states.
            out.append('p').append(SEP).append(who).append('\n');
        }
        // One settings line rather than three header lines, so the header stays the four fields an
        // older reader requires. The line is skipped by a reader that does not know the tag, which
        // leaves it on the default policy -- the same state a snapshot from before the switches has.
        out.append('g').append(SEP)
                .append(policy.openJoin()).append(SEP)
                .append(policy.membersCanInvite()).append(SEP)
                .append(memberLimit).append('\n');
        for (PublicParty party : publicParties) {
            out.append('u').append(SEP)
                    .append(party.teamId()).append(SEP)
                    .append(clean(party.name())).append(SEP)
                    .append(party.members()).append(SEP)
                    .append(party.limit()).append('\n');
        }
        return out.toString();
    }

    /**
     * The roster a {@link #pack}ed string describes, or {@link #none} for anything unreadable.
     *
     * <p>Never throws, and that is deliberate: this parses a string that arrived from a server, and a
     * client that throws on a malformed one is a client disconnected from a world over a payload that
     * was only ever going to draw a side panel. Every unreadable part is skipped, and a message with
     * nothing readable in it becomes "no party", which the panel draws as the empty state.
     */
    public static PartySnapshot unpack(String packed) {
        if (packed == null || packed.isEmpty()) {
            return none();
        }

        String[] lines = packed.split("\n", -1);
        if (lines.length < 4) {
            return none();
        }

        UUID teamId = uuidOrNull(lines[0]);
        UUID owner = uuidOrNull(lines[2]);
        if (teamId == null || owner == null) {
            return none();
        }

        List<Member> members = new ArrayList<>();
        List<Invite> invites = new ArrayList<>();
        List<SentInvite> sent = new ArrayList<>();
        List<String> online = new ArrayList<>();
        List<UUID> present = new ArrayList<>();
        List<PublicParty> publicParties = new ArrayList<>();
        TeamPolicy policy = TeamPolicy.DEFAULT;
        int limit = 0;

        // One tagged line per entry, so the lists can grow independently and a reader that does not
        // know a tag skips it rather than mis-parsing the rest. That is the property that makes the
        // format tolerant of an older client: an unknown prefix is ignored, and everything it does
        // understand still arrives.
        for (int i = 4; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            char tag = line.charAt(0);
            String body = line.length() > 1 && line.charAt(1) == SEP.charAt(0)
                    ? line.substring(2) : "";

            switch (tag) {
                case 'm' -> {
                    Member member = memberOrNull(body);
                    if (member != null) {
                        members.add(member);
                    }
                }
                case 'i' -> {
                    Invite invite = inviteOrNull(body);
                    if (invite != null) {
                        invites.add(invite);
                    }
                }
                case 's' -> {
                    SentInvite invite = sentOrNull(body);
                    if (invite != null) {
                        sent.add(invite);
                    }
                }
                case 'o' -> {
                    if (!body.isEmpty()) {
                        online.add(body);
                    }
                }
                case 'p' -> {
                    UUID who = uuidOrNull(body);
                    if (who != null) {
                        present.add(who);
                    }
                }
                case 'g' -> {
                    String[] parts = body.split(SEP);
                    if (parts.length >= 3) {
                        // Booleans are read leniently: anything that is neither "true" nor "false"
                        // leaves the default in place, because a malformed settings line must not
                        // decide who may invite.
                        boolean open = Boolean.parseBoolean(parts[0]);
                        boolean memberInvites = Boolean.parseBoolean(parts[1]);
                        if (isBoolean(parts[0]) && isBoolean(parts[1])) {
                            policy = new TeamPolicy(open, memberInvites);
                        }
                        limit = intOrZero(parts[2]);
                    }
                }
                case 'u' -> {
                    PublicParty party = publicOrNull(body);
                    if (party != null) {
                        publicParties.add(party);
                    }
                }
                default -> {
                    // An older format, or a newer one: skipped rather than guessed at.
                }
            }
        }

        return new PartySnapshot(teamId, lines[1], owner, List.copyOf(members),
                List.copyOf(invites), List.copyOf(online), lines[3], List.copyOf(present),
                List.copyOf(sent), policy, limit, List.copyOf(publicParties));
    }

    /**
     * An invitation, or null for a line this build cannot read.
     *
     * <p>The first two fields are required and everything after them is optional, which is what makes
     * the line readable by a build from either side of the change: before the sender and the time were
     * appended there were two fields, and a reader of either shape takes what it recognises.
     */
    private static Invite inviteOrNull(String body) {
        String[] parts = body.split(SEP, 6);
        if (parts.length < 2) {
            return null;
        }
        UUID teamId = uuidOrNull(parts[0]);
        if (teamId == null) {
            return null;
        }
        UUID inviter = parts.length >= 3 ? uuidOrNull(parts[2]) : null;
        String inviterName = parts.length >= 4 ? parts[3] : "";
        long at = parts.length >= 5 ? longOrZero(parts[4]) : 0L;
        return new Invite(teamId, parts[1], inviter == null ? new UUID(0L, 0L) : inviter, inviterName, at);
    }

    /** An outgoing invitation, or null for a line this build cannot read. */
    private static SentInvite sentOrNull(String body) {
        String[] parts = body.split(SEP, 3);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        long at = parts.length >= 2 ? longOrZero(parts[1]) : 0L;
        return new SentInvite(parts[0], at);
    }

    /** An open party, or null for a line this build cannot read. */
    private static PublicParty publicOrNull(String body) {
        String[] parts = body.split(SEP, 5);
        if (parts.length < 3) {
            return null;
        }
        UUID teamId = uuidOrNull(parts[0]);
        if (teamId == null) {
            return null;
        }
        return new PublicParty(teamId, parts[1], intOrZero(parts[2]),
                parts.length >= 4 ? intOrZero(parts[3]) : 0);
    }

    private static Member memberOrNull(String line) {
        if (line.isEmpty()) {
            return null;
        }
        // Split with a limit, so a name that somehow kept a separator stays in the name field rather
        // than becoming a fourth field nobody reads. `clean` should have removed it; this is the half
        // that makes a mistake on the way out survivable on the way in.
        String[] parts = line.split(SEP, MEMBER_FIELDS);
        if (parts.length < MEMBER_FIELDS) {
            return null;
        }
        UUID id = uuidOrNull(parts[0]);
        if (id == null) {
            return null;
        }
        Optional<TeamRole> role = roleOrEmpty(parts[1]);
        if (role.isEmpty()) {
            // A role this build does not know. The whole member is skipped rather than defaulted to
            // MEMBER: a rank is what decides whether a Remove button appears, and guessing one is
            // guessing about authority.
            return null;
        }
        return new Member(id, parts[2], role.get());
    }

    private static Optional<TeamRole> roleOrEmpty(String raw) {
        for (TeamRole role : TeamRole.values()) {
            if (role.name().equals(raw)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static UUID uuidOrNull(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long longOrZero(String raw) {
        try {
            return Long.parseLong(raw);
        }
        catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int intOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isBoolean(String raw) {
        return "true".equals(raw) || "false".equals(raw);
    }

    /** A name with the separator and any newline removed. See the class note. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace(SEP, "").replace("\n", "").replace("\r", "");
    }

    // ------------------------------------------------------------------
    // Building one
    // ------------------------------------------------------------------

    /**
     * The snapshot of the party with this id, read from the server's teams.
     *
     * <p>Resolves names from the player list, and falls back to a truncated id for a member who is
     * offline — which is a real state rather than an edge case: a party's roster includes people who
     * have logged off, and a panel that showed them as blank would look like a parsing fault.
     *
     * <h2>{@code byId}, and why that one word is the whole of a reported fault</h2>
     *
     * <p>This asked for {@code teamOf(teamId)} — and {@code teamOf} takes a <b>player's</b> id. It is
     * "the team a player is in, or a solo team of one", so handing it a team id looks up a player who
     * does not exist, finds no team, and synthesises {@code Team.solo(teamId)} — whose own javadoc says
     * {@code persistent} is false. So the guard below was taken on <b>every</b> party that has ever
     * existed, and this method returned "nobody is in a party" for all of them.
     *
     * <p>That is what "the party UI does not update" was, and it is worth being exact about because the
     * symptom points somewhere else entirely. The panel, the roster, the buttons and the commands were
     * all working; {@code sendPartyToTeam} returned early on an empty snapshot at the first line, so no
     * roster message was ever put on the wire. A client that is never told anything draws the empty
     * state — at creation, and again after reopening, which is why both halves of the report have one
     * cause rather than two.
     *
     * <p>The trap is that the two lookups are one word apart and both compile. {@code teamOf} returns a
     * {@code Team} and never fails, so a wrong id produces a plausible solo team rather than an error;
     * {@code byId} returns an {@code Optional} and is the only one of the two that can answer "there is
     * no such team". {@code Teams} is a source of teams <i>and</i> a source of teams-by-player, and the
     * argument's meaning is entirely which of the two is called.
     */
    public static PartySnapshot of(MinecraftServer server, UUID teamId) {
        // `byId`, not `teamOf`. See above: `teamOf` takes a player and would answer for nobody.
        Optional<Team> found = Teams.of(server).byId(teamId);
        if (found.isEmpty()) {
            // No such party. A disbanded one, or an id from a message that outlived its team — and
            // `none()` is the honest answer rather than a synthesised team of one.
            return none();
        }
        Team team = found.get();
        if (!team.persistent()) {
            // Redundant for a stored source, whose `byId` only ever returns real teams, and kept for
            // the ones it is not redundant for: an adapter over somebody else's parties mod answers
            // `byId` from that mod, and a source that reconstructed a solo team there would otherwise
            // reach the panel as a party whose only member is its own id.
            return none();
        }

        List<Member> members = new ArrayList<>(team.size());
        team.members().forEach((id, role) -> members.add(new Member(id, nameOf(server, id), role)));

        // The outgoing invitations are a property of the party, so they are built here rather than by
        // the per-recipient call: every member's panel shows the same outgoing list, and the only list
        // that differs per member is the incoming one. See `withPlayers`.
        List<SentInvite> sent = new ArrayList<>(team.invites().size());
        team.invites().forEach((invited, invite) -> {
            // A stored timestamp becomes an age here, while there is a server clock to subtract it
            // from. The wire carries the age; see Invite and SentInvite on why.
            long age = invite.at() <= 0L
                    ? 0L
                    : Math.max(0L, server.overworld().getGameTime() - invite.at());
            sent.add(new SentInvite(nameOf(server, invited), age));
        });

        // The invitations and the online list are filled by the caller that has the server, not here:
        // this method's only argument is a team id, and neither list is a property of a team. See
        // `withPlayers`.
        return new PartySnapshot(team.id(), team.name(), team.owner(), List.copyOf(members),
                List.of(), List.of(), "one_member", List.of(), List.copyOf(sent), team.policy(),
                Teams.of(server).memberLimit(), List.of());
    }

    /**
     * The same snapshot with the invitations and the online players filled in.
     *
     * <h2>Why this is a second call rather than an argument to {@link #of}</h2>
     *
     * <p>Because the two lists belong to the *player being told*, not to the party. Two members of one
     * party receive different snapshots -- each has their own invitations, and each may see a different
     * player list -- so a method keyed only on a team id cannot produce them. Splitting it keeps
     * {@code of} honest about what a team determines and puts the rest where the caller that knows the
     * recipient already is.
     */
    public PartySnapshot withPlayers(MinecraftServer server, UUID recipient,
                                     java.util.function.Function<UUID, List<Invite>> invitesFor) {
        List<String> names = new ArrayList<>();
        List<UUID> present = new ArrayList<>();
        for (var player : server.getPlayerList().getPlayers()) {
            names.add(player.getScoreboardName());
            present.add(player.getUUID());
        }
        // Sorted, because the player list has no defined order and a roster that shuffled between
        // frames would make the Invite buttons jump under the pointer.
        names.sort(String::compareToIgnoreCase);

        return new PartySnapshot(teamId, teamName, owner, members,
                List.copyOf(invitesFor.apply(recipient)), List.copyOf(names), mode, List.copyOf(present),
                sent, policy, memberLimit, publicParties);
    }

    /** The same snapshot with the party's counting mode filled in. See {@link #mode}. */
    public PartySnapshot withMode(String counted) {
        return new PartySnapshot(teamId, teamName, owner, members, invites, online, counted, present,
                sent, policy, memberLimit, publicParties);
    }

    /**
     * The same snapshot with the server's open parties filled in.
     *
     * <h2>Who the list is for, and why it is capped</h2>
     *
     * <p>The solo screen: a player in no party needs to know which parties on this server are open
     * before a Join button can mean anything. A member's panel never draws it, so only the solo pushes
     * fill it — see {@code TaskedNetworking.sendOwnRosterTo}. Sorted by name and capped at
     * {@link #MAX_PUBLIC_PARTIES}, so which parties travel is stable rather than depending on the
     * source's iteration order.
     */
    public PartySnapshot withPublic(MinecraftServer server) {
        List<PublicParty> open = new ArrayList<>();
        var teams = Teams.of(server);
        int limit = teams.memberLimit();
        for (Team team : teams.allTeams()) {
            if (!team.policy().openJoin()) {
                continue;
            }
            open.add(new PublicParty(team.id(), team.name(), team.size(), limit));
            if (open.size() >= MAX_PUBLIC_PARTIES) {
                break;
            }
        }
        return new PartySnapshot(teamId, teamName, owner, members, invites, online, mode, present,
                sent, policy, memberLimit, List.copyOf(open));
    }

    /**
     * The same snapshot, filled for a player who is <b>arriving</b>.
     *
     * <h2>Why the player object, when {@link #withPlayers} takes the list</h2>
     *
     * <p>Because at {@code PLAYER_JOIN} the player list does not answer for the player who is joining —
     * and that one fact produced three separate symptoms on a login, all of them looking like different
     * faults:
     *
     * <ul>
     *   <li>{@link #nameOf} fell through to eight characters of the id, so a player's own row read
     *       {@code 5d5cfed6} instead of their name.</li>
     *   <li>{@code online} was built from the list, so the player was missing from it and their own row
     *       drew as <b>offline</b>.</li>
     *   <li>and the roster itself was addressed through the list, so it was never sent at all.</li>
     * </ul>
     *
     * <p>This is the one place that knows it is building for an arrival: its recipient is a
     * {@code ServerPlayer}, so their name is in hand and their presence is a fact rather than a lookup.
     * Everything else about the snapshot — the other members, the invitations — still comes from the
     * server.
     */
    public PartySnapshot withArriving(MinecraftServer server, ServerPlayer self,
                                      java.util.function.Function<UUID, List<Invite>> invitesFor) {
        List<String> listed = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            listed.add(online.getScoreboardName());
        }
        return withSelf(members, self.getUUID(), self.getScoreboardName(), listed,
                invitesFor.apply(self.getUUID()));
    }

    /**
     * The pure half of {@link #withArriving}: a roster as the player who is arriving must receive it.
     *
     * <h2>Why this is split from the server</h2>
     *
     * <p>Because both things it does are list arithmetic — put the arriving player's own name on their
     * row, and put them into the online list they are missing from — and both are things a login got
     * wrong. A test that had to build a server to reach them would be testing the harness; this is held
     * by {@code PartySnapshotTest}, which has none.
     */
    PartySnapshot withSelf(List<Member> members, UUID self, String selfName, List<String> listed,
                           List<Invite> invites) {
        List<Member> named = new ArrayList<>(members.size());
        for (Member member : members) {
            named.add(member.id().equals(self) ? new Member(self, selfName, member.role()) : member);
        }

        List<String> online = new ArrayList<>(listed);
        // Said rather than implied: the list a login hands over is the one that does not have them.
        if (online.stream().noneMatch(selfName::equalsIgnoreCase)) {
            online.add(selfName);
        }
        online.sort(String::compareToIgnoreCase);

        return new PartySnapshot(teamId, teamName, owner, List.copyOf(named), List.copyOf(invites),
                List.copyOf(online), mode, present, sent, policy, memberLimit, publicParties);
    }

    /** The mode this party counts by, or the default when an older server sent none. */
    public dev.ellipog.tasked.party.PartyMode modeOr() {
        return dev.ellipog.tasked.party.PartyMode.byId(mode)
                .orElse(dev.ellipog.tasked.party.PartyMode.DEFAULT);
    }

    /**
     * A player's name, from the player list, or eight characters of their id when they are offline.
     *
     * <p>Package-private rather than private because {@code TaskedNetworking} resolves the same two
     * kinds of name — a member's and an invite sender's — and a second copy of this fallback is a
     * second place for the two to disagree about what an offline player is called.
     */
    static String nameOf(MinecraftServer server, UUID player) {
        var found = server.getPlayerList().getPlayer(player);
        if (found != null) {
            return found.getScoreboardName();
        }
        // Eight characters of the id, which is enough to tell two offline members apart and short
        // enough not to run the row out of width.
        return player.toString().substring(0, 8);
    }

    /**
     * The roster as the panel draws it, for one viewer.
     *
     * <h2>Why the conversion is here rather than in the client's cache</h2>
     *
     * <p>Because it is the same conversion on both sides of the wire, and there is no reason for two.
     * A server measuring a panel's height for a preview and a client drawing it agree by construction,
     * and the rules a roster obeys — owner first, may-remove-whom — are then stated once, in Armature,
     * where {@code PartyRosterTest} holds them.
     *
     * <p>Reads the snapshot rather than a live {@code Team}, which is the whole point of it existing:
     * the client has no team to read.
     */
    public static dev.ellipog.armature.client.ui.party.PartyRoster toRoster(PartySnapshot snapshot, UUID viewer) {
        // **A snapshot of nobody is a roster of nobody, and this guard is the fix for a blank card.**
        //
        // `fromParts` below reconstructs a team with `persistent = true` unconditionally, because the
        // only thing it was built from is a membership that arrived over a wire -- and a membership
        // that arrived is a real party by construction. That reasoning is right for a snapshot with
        // members and wrong for one without: an empty snapshot became a party that `isReal()` says is
        // real and that `members()` says is empty. So the panel skipped its empty state *and* drew no
        // rows, which is an entirely blank card -- what the screenshot showed.
        //
        // A solo team is the honest answer: `PartyRoster.of` reads `Team.solo` as not persistent, so
        // `isReal()` is false, the panel draws "you are not in a party", and the tooltip says so too.
        if (!snapshot.isPresent()) {
            UUID who = viewer == null ? new UUID(0L, 0L) : viewer;
            // Nobody is online in a roster of nobody -- and the person reading it is looking at a
            // panel, not at a list of themselves.
            return dev.ellipog.armature.client.ui.party.PartyRoster.of(
                    Team.solo(who), who, id -> id.toString(), dev.ellipog.armature.client.ui.party
                            .PartyRoster.Online.NOBODY);
        }

        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        for (Member member : snapshot.members()) {
            names.put(member.id(), member.name());
        }

        // Built by hand rather than through PartyRoster.of, and the difference is worth a line: that
        // factory takes a *Team*, and there is no Team here — the client has the snapshot precisely
        // because it has none. So the record is reassembled from what travelled, and every derived
        // answer (self, owner, canRemove) is recomputed from it rather than sent, because a boolean
        // that travels can disagree with the roles it was computed from.
        return dev.ellipog.armature.client.ui.party.PartyRoster.fromParts(
                snapshot.teamId(),
                snapshot.teamName(),
                snapshot.owner(),
                snapshot.members().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Member::id, Member::role, (a, b) -> a, java.util.LinkedHashMap::new)),
                viewer,
                id -> names.getOrDefault(id, id.toString()),
                // Who was connected when the server built this, by id. A server that predates the
                // field sends nobody and every row reads as offline, which is the honest reading of
                // "nobody told me" -- and better than the guess this replaced, which joined two lists
                // by a player's name.
                snapshot.present()::contains,
                snapshot.policy(),
                snapshot.memberLimit());
    }
}
