package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Where one chunk of one sync message sits: which message, which part of it, how many parts, and
 * whether the message as a whole is a full sync or a delta.
 *
 * <h2>Why the four fields travel together rather than beside the data</h2>
 *
 * <p>Because a payload is limited to <b>six</b> components — {@code StreamCodec.composite} has
 * overloads for one through six and no more — and both sync payloads were already using most of
 * theirs. {@code ProgressSyncPayload} needs a UUID (two longs), a game time, this, the data and a
 * reason, which is exactly six. Folding these four into one component is what keeps that inside the
 * ceiling without either payload growing a second version of itself.
 *
 * <p>The limit was read from the jar rather than recalled, and it is worth writing down because it
 * is not discoverable from a compile error: past six components the compiler reports a type
 * inference failure on the {@code BiFunction} argument, which reads as a problem with the arguments
 * rather than with how many there are.
 *
 * <h2>Why {@code full} is on every chunk rather than on the message</h2>
 *
 * <p>It belongs to the message, and putting it here repeats it once per chunk. The reason is that
 * there is nowhere else for it to go that does not cost a component, and the alternative — inferring
 * it from the reason code — would make the payload's meaning depend on a value that means something
 * else. The repetition is harmless because the reassembler compares them and drops a transfer whose
 * chunks disagree: a flag that cannot disagree with itself is worth more than one byte per chunk.
 *
 * @param transferId which message this chunk belongs to. Allocated by {@link SyncWire#newTransferId()}.
 * @param index      this chunk's position within the message, from zero
 * @param count      how many chunks the message was split into. At least one, even for empty data.
 * @param full       whether the reassembled message is a full sync rather than a delta
 */
public record SyncChunk(int transferId, int index, int count, boolean full) {

    public static final StreamCodec<? super RegistryFriendlyByteBuf, SyncChunk> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SyncChunk::transferId,
                    ByteBufCodecs.VAR_INT, SyncChunk::index,
                    ByteBufCodecs.VAR_INT, SyncChunk::count,
                    ByteBufCodecs.BOOL, SyncChunk::full,
                    SyncChunk::new);

    /** Whether this chunk is the only one, which is the common case for a delta. */
    public boolean whole() {
        return count == 1;
    }

    @Override
    public String toString() {
        return (index + 1) + "/" + count + " of transfer " + transferId + (full ? " (full)" : " (delta)");
    }
}
