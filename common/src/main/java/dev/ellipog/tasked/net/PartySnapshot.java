package dev.ellipog.tasked.net;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.server.MinecraftServer;

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
 * @param teamId   the party's id, which is also the key its progress is stored under
 * @param teamName its name. Empty for a team that has none
 * @param owner    who owns it
 * @param members  everyone in it, in no particular order. The client sorts for display
 */
public record PartySnapshot(UUID teamId, String teamName, UUID owner, List<Member> members,
                            List<Invite> invites, List<String> online, String mode) {

    /** The field separator. See the class note on why it is stripped from names. */
    private static final String SEP = "\u001f";

    /** How many fields one member occupies. */
    private static final int MEMBER_FIELDS = 3;

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
     * @param teamId   the party that invited them
     * @param teamName its name, so the client does not need a second round trip to say what it is
     */
    public record Invite(UUID teamId, String teamName) {
    }

    /** A snapshot of nobody being in any party, which is a real answer rather than an absent one. */
    public static PartySnapshot none() {
        return new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of(), List.of(), List.of(),
                "one_member");
    }

    /** Whether this describes a party at all. False is the answer for a player who is alone. */
    public boolean isPresent() {
        return !members.isEmpty();
    }

    /**
     * The roster as one string.
     *
     * <p>The header is three lines and each member is one. A truncated message therefore loses whole
     * members rather than corrupting the ones before it, and {@link #unpack} ignores a partial trailing
     * member rather than throwing — which is the right direction, because the alternative is a client
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
        for (Invite invite : invites) {
            out.append('i').append(SEP)
                    .append(invite.teamId()).append(SEP)
                    .append(clean(invite.teamName())).append('\n');
        }
        for (String name : online) {
            // No id: the client invites by *name*, because a command takes a name and the player it
            // names may not be anyone this client has a uuid for. See PartySnapshot's note on why the
            // action goes through a command at all.
            out.append('o').append(SEP).append(clean(name)).append('\n');
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
        List<String> online = new ArrayList<>();

        // One tagged line per entry, so the three lists can grow independently and a reader that does
        // not know a tag skips it rather than mis-parsing the rest. That is the property that makes the
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
                case 'o' -> {
                    if (!body.isEmpty()) {
                        online.add(body);
                    }
                }
                default -> {
                    // An older format, or a newer one: skipped rather than guessed at.
                }
            }
        }

        return new PartySnapshot(teamId, lines[1], owner, List.copyOf(members),
                List.copyOf(invites), List.copyOf(online), lines[3]);
    }

    /** An invitation, or null for a line this build cannot read. */
    private static Invite inviteOrNull(String body) {
        String[] parts = body.split(java.util.regex.Pattern.quote(SEP), 2);
        if (parts.length < 2) {
            return null;
        }
        UUID teamId = uuidOrNull(parts[0]);
        return teamId == null ? null : new Invite(teamId, parts[1]);
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
     * The snapshot of whoever owns {@code teamId}, read from the server's teams.
     *
     * <p>Resolves names from the player list, and falls back to a truncated id for a member who is
     * offline — which is a real state rather than an edge case: a party's roster includes people who
     * have logged off, and a panel that showed them as blank would look like a parsing fault.
     */
    public static PartySnapshot of(MinecraftServer server, UUID teamId) {
        Team team = dev.ellipog.armature.api.teams.Teams.of(server).teamOf(teamId);
        if (!team.persistent()) {
            return none();
        }

        List<Member> members = new ArrayList<>(team.size());
        team.members().forEach((id, role) -> members.add(new Member(id, nameOf(server, id), role)));

        // The invitations and the online list are filled by the caller that has the server, not here:
        // this method's only argument is a team id, and neither list is a property of a team. See
        // `withPlayers`.
        return new PartySnapshot(team.id(), team.name(), team.owner(), List.copyOf(members),
                List.of(), List.of(), "one_member");
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
        for (var player : server.getPlayerList().getPlayers()) {
            names.add(player.getScoreboardName());
        }
        // Sorted, because the player list has no defined order and a roster that shuffled between
        // frames would make the Invite buttons jump under the pointer.
        names.sort(String::compareToIgnoreCase);

        return new PartySnapshot(teamId, teamName, owner, members,
                List.copyOf(invitesFor.apply(recipient)), List.copyOf(names), mode);
    }

    /** The same snapshot with the party's counting mode filled in. See {@link #mode}. */
    public PartySnapshot withMode(String counted) {
        return new PartySnapshot(teamId, teamName, owner, members, invites, online, counted);
    }

    /** The mode this party counts by, or the default when an older server sent none. */
    public dev.ellipog.tasked.party.PartyMode modeOr() {
        return dev.ellipog.tasked.party.PartyMode.byId(mode)
                .orElse(dev.ellipog.tasked.party.PartyMode.DEFAULT);
    }

    private static String nameOf(MinecraftServer server, UUID player) {
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
            return dev.ellipog.armature.client.ui.party.PartyRoster.of(
                    Team.solo(who), who, id -> id.toString());
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
                id -> names.getOrDefault(id, id.toString()));
    }
}
