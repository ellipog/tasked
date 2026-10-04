package dev.ellipog.tasked;

import dev.ellipog.armature.api.teams.TeamRole;

import net.minecraft.network.chat.Component;

/**
 * The words a party wears, in one place.
 *
 * <h2>Why a class rather than two call sites</h2>
 *
 * <p>The panel and the command both name a member's role, and the two must not disagree about what an
 * owner is called — the same argument {@code QuestAuthority} settles for the permission number. A
 * switch here also means each key is a literal the language sweep can see: a key built by
 * concatenating {@code "tasked.screen.party.role."} with a lowercased enum name is invisible to any
 * static scan, and the reverse-direction test would report all three as orphans.
 *
 * <p>Common code, deliberately: {@code /tasked party} runs on a dedicated server, so the mapping
 * cannot live in the screen's package.
 */
public final class PartyWords {

    private PartyWords() {
    }

    /** The key a role's word lives under. */
    public static String roleKey(TeamRole role) {
        return switch (role) {
            case OWNER -> "tasked.screen.party.role.owner";
            case OFFICER -> "tasked.screen.party.role.officer";
            case MEMBER -> "tasked.screen.party.role.member";
        };
    }

    /** The role's word, translated. */
    public static Component role(TeamRole role) {
        return Component.translatable(roleKey(role));
    }
}
