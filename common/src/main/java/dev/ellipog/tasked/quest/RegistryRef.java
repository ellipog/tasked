package dev.ellipog.tasked.quest;

import com.mojang.serialization.Codec;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * A reference to a registry entry, or to a tag of them: {@code minecraft:plains} or
 * {@code #minecraft:is_forest}.
 *
 * <p>FTB Quests writes the tag form into the same field with a leading {@code #} — a shape a
 * {@link ResourceLocation} cannot hold, which is why this exists rather than a second field. The
 * codec round-trips the same spelling, so a file that says {@code "#minecraft:is_forest"} keeps
 * saying it.
 */
public record RegistryRef(ResourceLocation id, boolean tag) {

    public static final Codec<RegistryRef> CODEC =
            Codec.STRING.xmap(RegistryRef::parse, RegistryRef::wire);

    /** Parses either spelling. A {@code #} prefix means a tag; no other syntax is accepted. */
    public static RegistryRef parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        boolean tag = text.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
        if (id == null) {
            throw new IllegalArgumentException("'" + raw + "' is not an id or a #tag");
        }
        return new RegistryRef(id, tag);
    }

    /** The file's own spelling, so a parsed value writes back as it was read. */
    public String wire() {
        return tag ? "#" + id : id.toString();
    }

    @Override
    public String toString() {
        return wire();
    }
}
