package dev.ellipog.tenet.editor;

import java.util.Objects;
import java.util.Optional;

/**
 * Which table an operation is about.
 *
 * <h2>Two owners, because a table can be reached two ways</h2>
 *
 * <p>A <b>named</b> table is a file under {@code reward_tables/}, and its id is that file's name: that
 * is what an editor opens and what a reward's {@code table} field points at. A <b>quest</b> owner is not
 * a table at all but the file that holds a reward's reference — what {@code TableOp.Select} writes, so
 * that pointing a reward at a table is one edit of that reward.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not an inline table. A table written inside a reward is reached through its <i>owner</i> — the
 * reward's own path, which is what {@code Select} takes — and the panels edit files. The file format
 * still reads one and the card still shows one, but nothing addresses one.
 */
public record TableAddress(Owner owner) {

    /** The file a table lives in. */
    public sealed interface Owner permits Owner.Named, Owner.InQuest {

        /** A file under {@code reward_tables/}, by the id that is its name. */
        record Named(String id) implements Owner {

            public Named {
                Objects.requireNonNull(id, "id");
            }
        }

        /** One quest's file inside a chapter: where the reward a table is being chosen for lives. */
        record InQuest(String chapter, String quest) implements Owner {

            public InQuest {
                Objects.requireNonNull(chapter, "chapter");
                Objects.requireNonNull(quest, "quest");
            }
        }
    }

    public TableAddress {
        Objects.requireNonNull(owner, "owner");
    }

    /** The table a reward points at, by the id that is its file's name. */
    public static TableAddress of(String tableId) {
        return new TableAddress(new Owner.Named(tableId));
    }

    /** The named table's id, or empty for a quest owner (which names a reward, not a table). */
    public Optional<String> tableId() {
        return owner instanceof Owner.Named named ? Optional.of(named.id()) : Optional.empty();
    }

    /**
     * The address on the wire: one kind byte, then the fields that kind has.
     *
     * <p>A byte rather than a JSON blob, because an address is a small closed shape and the payloads that
     * carry one are small.
     */
    public static final net.minecraft.network.codec.StreamCodec<
            net.minecraft.network.RegistryFriendlyByteBuf, TableAddress> CODEC =
            new net.minecraft.network.codec.StreamCodec<>() {

                @Override
                public TableAddress decode(net.minecraft.network.RegistryFriendlyByteBuf buffer) {
                    int kind = buffer.readVarInt();
                    return switch (kind) {
                        case 0 -> new TableAddress(new Owner.Named(buffer.readUtf(128)));
                        case 1 -> new TableAddress(
                                new Owner.InQuest(buffer.readUtf(128), buffer.readUtf(128)));
                        default -> throw new io.netty.handler.codec.DecoderException(
                                "unknown table address kind " + kind);
                    };
                }

                @Override
                public void encode(net.minecraft.network.RegistryFriendlyByteBuf buffer,
                                   TableAddress address) {
                    switch (address.owner()) {
                        case Owner.Named named -> {
                            buffer.writeVarInt(0);
                            buffer.writeUtf(named.id(), 128);
                        }
                        case Owner.InQuest quest -> {
                            buffer.writeVarInt(1);
                            buffer.writeUtf(quest.chapter(), 128);
                            buffer.writeUtf(quest.quest(), 128);
                        }
                    }
                }
            };

    /** How to say it in a message: the file, or the quest whose reward holds the reference. */
    public String describe() {
        return switch (owner) {
            case Owner.Named named -> named.id();
            case Owner.InQuest quest -> quest.quest();
        };
    }
}
