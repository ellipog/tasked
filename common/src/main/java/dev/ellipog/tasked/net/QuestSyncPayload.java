package dev.ellipog.tasked.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The quest tree and the player's progress, sent together when they join.
 *
 * <h2>One payload, not two</h2>
 *
 * <p>The tree and the progress are useless apart — a quest list with no state shows everything as
 * locked, and states with no quests have nothing to attach to. Sending them separately means a window
 * where the client has one and not the other, and no way to tell a rendering bug from a missing
 * packet. Together, the client is either informed or it is not.
 *
 * <h2>Why the codec is declared as {@code ? super RegistryFriendlyByteBuf}</h2>
 *
 * <p>This is not decoration and it is not obvious. {@code ByteBufCodecs.BYTE_ARRAY} is defined over
 * {@code ByteBuf}, not {@code RegistryFriendlyByteBuf} — so {@code StreamCodec.composite} infers
 * {@code ByteBuf} as the buffer type and produces a {@code StreamCodec<ByteBuf, QuestSyncPayload>}.
 * Declaring the field as {@code StreamCodec<RegistryFriendlyByteBuf, ...>} therefore does not compile,
 * even though {@code ByteBuf} is a supertype of it and every use would have been fine.
 *
 * <p>{@code ? super} is the declaration that matches what comes out <i>and</i> what both loaders want:
 * Fabric's {@code playS2C().register} and NeoForge's {@code playToClient} both take
 * {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}. So the natural inference and the required
 * type agree, once the field says so. This took a {@code javap} to be sure of rather than a guess.
 *
 * <h2>The tree is JSON, and that is a deliberate first step</h2>
 *
 * <p>A hand-written binary encoding would be smaller — the plan says so, and it is right — but it
 * would also be a second serialiser to keep in step with the codecs, and a format nobody can read in
 * a packet dump while the shape is still moving. This is the version that gets a screen working; the
 * plan's chunked-and-compressed binary sync is a later-stage optimisation, and optimising before the
 * thing works is how you optimise the wrong part.
 *
 * @param questCount   how many quests the tree holds
 * @param chapterCount how many chapters, for a summary the client can show before parsing
 * @param tree         the quest tree, as UTF-8 JSON
 */
public record QuestSyncPayload(int questCount, int chapterCount, byte[] tree) implements CustomPacketPayload {

    /**
     * The payload's id, <b>not</b> built with {@code CustomPacketPayload.createType}.
     *
     * <p>That method looks like the right one and is not. Its bytecode is
     * {@code new Type(ResourceLocation.withDefaultNamespace(name))} — it takes a <i>path</i> and
     * hardcodes the {@code minecraft} namespace, because it exists for vanilla's own payloads. So
     * passing it {@code "tasked:quest_sync"} produces the location {@code minecraft:tasked:quest_sync},
     * and the colon inside the path throws:
     *
     * <pre>Non [a-z0-9/._-] character in path of location: minecraft:tasked:quest_sync</pre>
     *
     * <p>The constructor takes a real {@link ResourceLocation} and accepts a namespace, which is what
     * a mod's payload needs. Worth reading as a rule: when a factory method in vanilla takes a string
     * and the error names a location you did not write, check whether it prepends a namespace.
     */
    public static final CustomPacketPayload.Type<QuestSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tasked", "quest_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, QuestSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, QuestSyncPayload::questCount,
                    ByteBufCodecs.VAR_INT, QuestSyncPayload::chapterCount,
                    ByteBufCodecs.BYTE_ARRAY, QuestSyncPayload::tree,
                    QuestSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
