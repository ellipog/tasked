package dev.ellipog.tasked.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What the reload found wrong, pushed to the authors who are editing.
 *
 * <h2>The faults nothing else can see</h2>
 *
 * <p>The per-file validator runs inside {@code QuestEditor.save} and refuses a file the loader would reject —
 * but it only ever sees <b>one file at a time</b>. Three faults are therefore invisible to it and were written
 * silently, with only the server log knowing:
 *
 * <ul>
 *   <li>a {@code dependsOn} naming a quest that is not in the pack;</li>
 *   <li>a cycle between quests in different files;</li>
 *   <li>an id declared in two chapters.</li>
 * </ul>
 *
 * <p>Each is a fault of the <i>pack</i> rather than of a file, so it can only be found by the index that reads
 * them all — which is the reload's own work. The author who caused one saw their edit succeed and nothing else,
 * and the quest was then quietly absent from the tree or unreachable.
 *
 * <h2>Why this arrives after the reply rather than with it</h2>
 *
 * <p>Because it cannot arrive with it. An edit is applied and answered <b>synchronously</b>, and the reload is
 * coalesced to one per server tick — so at the moment the reply is written the pack has not been re-read and
 * the cross-file faults do not exist yet as an answer. Attaching them to the reply would mean either reloading
 * per op, which is the coalescing this codebase deliberately added, or reporting the <i>previous</i> reload's
 * faults, which is a message about a state the author has already changed.
 *
 * <p>So the reply stays what it is — "your edit was applied and written" — and this carries the separate fact,
 * as soon as it is knowable.
 *
 * @param lines how many problems there are, so a truncated list can say what it is not showing
 * @param text  the problems, one per line, as {@code DataProblem.renderWithPath} wrote them, or "" for none
 */
public record EditProblemsPayload(int lines, String text) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditProblemsPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tasked", "edit_problems"));

    /** As much as one message carries. Beyond it the count says what was left out. */
    public static final int MAX_CHARS = 8192;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditProblemsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EditProblemsPayload::lines,
                    // One string with newlines, like the reply's messages, and for the same reason: the
                    // feedback line shows one and the chat shows them one after another.
                    ByteBufCodecs.stringUtf8(MAX_CHARS), EditProblemsPayload::text,
                    EditProblemsPayload::new);

    /** The problems as a list, which is how both readers of them want them. */
    public java.util.List<String> problems() {
        return text == null || text.isEmpty()
                ? java.util.List.of()
                : java.util.List.of(text.split("\n"));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
