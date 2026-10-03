package dev.ellipog.tasked.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A player pressing Claim all in the rewards panel.
 *
 * <h2>Why this carries nothing</h2>
 *
 * <p>Which quests are owed is the server's to decide, exactly as it is for {@link ClaimRewardPayload}:
 * a payload naming quests would be a client deciding what it is owed, and {@code
 * ProgressService.claimAll} asks the same {@code canClaimFor} the single claim does rather than
 * trusting a list. So there is nothing to send but the press, and nothing here for a modified client
 * to point at a diamond.
 *
 * <p>A separate payload rather than a sentinel quest id on the single claim: an id that means
 * "everything" is an id the claim's own handler would have to special-case, and the two presses have
 * different answers on the server (one quest, or a walk of the book). One payload each keeps both
 * handlers ordinary.
 *
 * <p>Like the single claim, there is no response payload: success and failure are both learned from
 * the progress sync that follows.
 */
public record ClaimAllPayload() implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ClaimAllPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tasked", "claim_all"));

    /**
     * An empty payload still needs a codec, and {@code unit} is the one for exactly that: it writes
     * nothing and hands back the same instance for every read, which is why the record can have no
     * fields without the buffer ever being asked about them.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ClaimAllPayload> CODEC =
            StreamCodec.unit(new ClaimAllPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
