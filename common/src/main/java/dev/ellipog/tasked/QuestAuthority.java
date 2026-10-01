package dev.ellipog.tasked;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Who may change quest files.
 *
 * <h2>One rule, because three things ask it</h2>
 *
 * <p>`/tasked reload` and the editor's payload handler both mean *"may this player change the questline"*,
 * and the design notes settled the answer as a single number: **permission level 2**. Written here once and
 * read by all of them, because the failure mode of two copies is not a typo — it is a command that says yes
 * where the wire says no, which is a permission rule nobody can reason about from either side.
 *
 * <p>The book's split control gates the same number, so the button is hidden from players who may not press
 * it. That gate is a courtesy and this one is the check: a client that asked anyway gets a refusal from the
 * handler, which is the only place a check can live when the client is not the authority.
 */
public final class QuestAuthority {

    /** The permission level a player needs to change quest files. */
    public static final int EDIT_LEVEL = 2;

    private QuestAuthority() {
    }

    /** Whether a player may change quest files. The server's question, asked of a player. */
    public static boolean mayEdit(ServerPlayer player) {
        return player != null && player.hasPermissions(EDIT_LEVEL);
    }

    /** The same rule as a command predicate, so every command that changes files asks it the same way. */
    public static Predicate<CommandSourceStack> mayEdit() {
        return source -> source.hasPermission(EDIT_LEVEL);
    }
}
