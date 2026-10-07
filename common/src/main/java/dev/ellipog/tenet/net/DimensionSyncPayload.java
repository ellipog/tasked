package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The dimensions this server has, sent to a client so the editor can offer them.
 *
 * <h2>Why the client cannot work this out for itself</h2>
 *
 * <p>Every other list the editor searches -- biomes, structures, enchantments, fluids, entities -- the
 * client already holds: they are registries, and a client is sent the synced ones and registers the
 * static ones at mod init. The dimensions are the exception, and the exception is structural rather than
 * an oversight. A dimension is a {@code LevelStem}: level data on the server, not a registry entry the
 * client is shipped. So the client knows the three vanilla ids and the one it is standing in, and a
 * modded or datapack dimension is invisible to it -- which is exactly the case a quest book for modpacks
 * has to serve. The server sends the list, and it includes everything: vanilla, modded, and datapack.
 *
 * <p>Ids only. A dimension has no icon and no name a data-driven client can render beyond its id, so a
 * payload carrying more would be carrying a copy of something the client would prettify anyway.
 *
 * <p>Sent on join, with the tree and the roster. A datapack dimension added by a reload arrives on the
 * next join rather than mid-session, which is the same deal the tree makes and worth knowing rather than
 * discovering.
 */
public record DimensionSyncPayload(List<String> dimensions) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<DimensionSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("tenet", "dimension_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, DimensionSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list()),
                    DimensionSyncPayload::dimensions,
                    DimensionSyncPayload::new);

    public DimensionSyncPayload {
        dimensions = List.copyOf(dimensions);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
