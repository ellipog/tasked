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
public record PartySnapshot(UUID teamId, String teamName, UUID owner, List<Member> members) {

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

    /** A snapshot of nobody being in any party, which is a real answer rather than an absent one. */
    public static PartySnapshot none() {
        return new PartySnapshot(new UUID(0L, 0L), "", new UUID(0L, 0L), List.of());
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
        out.append(teamId).append('\n').append(clean(teamName)).append('\n').append(owner).append('\n');
        for (Member member : members) {
            out.append(member.id()).append(SEP)
                    .append(member.role().name()).append(SEP)
                    .append(clean(member.name())).append('\n');
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
        if (lines.length < 3) {
            return none();
        }

        UUID teamId = uuidOrNull(lines[0]);
        UUID owner = uuidOrNull(lines[2]);
        if (teamId == null || owner == null) {
            return none();
        }

        List<Member> members = new ArrayList<>();
        for (int i = 3; i < lines.length; i++) {
            Member member = memberOrNull(lines[i]);
            if (member != null) {
                members.add(member);
            }
        }

        return new PartySnapshot(teamId, lines[1], owner, List.copyOf(members));
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

        return new PartySnapshot(team.id(), team.name(), team.owner(), List.copyOf(members));
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
