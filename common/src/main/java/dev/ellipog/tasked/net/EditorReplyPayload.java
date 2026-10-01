package dev.ellipog.tasked.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What the server did about an edit: yes or no, and the words for it.
 *
 * <h2>Why this one answers, when the claim payload does not</h2>
 *
 * <p>Because an edit has more than one interesting outcome and one of them is a question the client asked.
 * A claim either happens or not, and both are visible in the progress sync that follows; an edit can be
 * refused for a reason the author has to read — the validator's own message, at the line it found — and can
 * <i>make something</i>, whose id the client cannot guess. So the reply carries the outcome, the id of a quest
 * that was created or duplicated, and the messages, which are the same ones the model produced.
 *
 * <p>The tree still arrives separately, as a reload broadcast, and this payload deliberately carries none of
 * it: the files are the server's answer to "what is the questline now", and a second copy of that answer on
 * this payload would be a second thing to keep true.
 *
 * @param chapter  the chapter the edit was about, so a client with two open can tell them apart
 * @param ok       whether it was applied and written
 * @param questId  the quest a create or a duplicate made, or "" when the op made none
 * @param messages the refusal's reasons, one per line, or "" when there were none
 */
public record EditorReplyPayload(String chapter, boolean ok, String questId, String messages)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditorReplyPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tasked", "editor_reply"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditorReplyPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), EditorReplyPayload::chapter,
                    ByteBufCodecs.BOOL, EditorReplyPayload::ok,
                    ByteBufCodecs.stringUtf8(64), EditorReplyPayload::questId,
                    // A validator can produce a paragraph of messages; one string with newlines, because the
                    // feedback line shows one and the chat shows them one after another.
                    ByteBufCodecs.stringUtf8(4096), EditorReplyPayload::messages,
                    EditorReplyPayload::new);

    /** The messages as a list, which is how both readers of them want them. */
    public java.util.List<String> lines() {
        return messages == null || messages.isEmpty()
                ? java.util.List.of()
                : java.util.List.of(messages.split("\n"));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
