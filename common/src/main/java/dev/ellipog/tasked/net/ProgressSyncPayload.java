package dev.ellipog.tasked.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * One team's progress, sent whenever it changes.
 *
 * <h2>Sent after a change, not on a timer</h2>
 *
 * <p>The server sends this when something actually moved: a task advanced, a quest completed, a
 * cooldown expired. A periodic sync is easier to write and worse in every way — it puts traffic on the
 * wire proportional to uptime rather than to activity, and it makes every client-side bug look like
 * lag rather than a missed packet.
 *
 * <h2>The team id is on it, and that matters after a party changes</h2>
 *
 * <p>A player can leave a party, and the next sync they receive is for a different team. Without the
 * id, a client would apply the new progress over the old team's without noticing, showing a questline
 * that belongs to nobody. With it, the client can tell its cached progress is for the wrong team and
 * start fresh.
 *
 * <p>The UUID goes as two longs rather than a string: fixed size, no parsing, no allocation. Two
 * {@code VAR_LONG}s and not a chance of a malformed one, which a string would permit.
 *
 * @param teamId   whose progress this is
 * @param gameTime the server's tick count, so a client can tell how fresh this is
 * @param data     the progress, as UTF-8 JSON
 * @param reason   why it was sent, for a log line and for a client that reacts differently
 */
public record ProgressSyncPayload(UUID teamId, long gameTime, byte[] data, int reason) implements CustomPacketPayload {

    public static final int REASON_JOIN = 0;
    public static final int REASON_CHANGED = 1;
    public static final int REASON_RELOAD = 2;
    public static final int REASON_TEAM_CHANGED = 3;

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ProgressSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tasked", "progress_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ProgressSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, payload -> payload.teamId().getMostSignificantBits(),
                    ByteBufCodecs.VAR_LONG, payload -> payload.teamId().getLeastSignificantBits(),
                    ByteBufCodecs.VAR_LONG, ProgressSyncPayload::gameTime,
                    ByteBufCodecs.BYTE_ARRAY, ProgressSyncPayload::data,
                    ByteBufCodecs.VAR_INT, ProgressSyncPayload::reason,
                    (most, least, gameTime, data, reason) ->
                            new ProgressSyncPayload(new UUID(most, least), gameTime, data, reason));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
