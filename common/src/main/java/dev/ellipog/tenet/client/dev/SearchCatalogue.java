package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.ClientDimensions;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSpecs;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * What a search field offers: the things the game actually has, resolved on the client.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Because a field that is an id -- a dimension, a biome, a statistic -- used to be a text box you had
 * to know the answer to. Everything here is a list the client already holds: the registries the server
 * synced, the static ones the game ships, the advancement tree, and the custom statistics. So the editor
 * can say "here is what exists" rather than "type it and hope".
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not pretend to know more than it does. A datapack's dimensions are not on the client -- the
 * server does not sync its level list -- so the dimension list holds the three every player knows plus
 * the one the player is standing in, and the picker's search box still takes any id typed into it. That
 * is the honest shape: a convenience list, and a field that accepts what the list cannot know.
 *
 * <p>Labels come from the ids, prettified: a biome's own translated name is not reachable from a registry
 * key without resolving the biome's display, and an id a person can read ("The nether") is what a picker
 * row needs. The raw id is beside it as small print, where the picker puts it for every row.
 */
public final class SearchCatalogue {

    private SearchCatalogue() {
    }

    /** The three dimensions every player knows, in the order a file lists them. */
    private static final List<String> KNOWN_DIMENSIONS =
            List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    /**
     * The list one source offers, or an empty one when the client cannot see it -- disconnected, or a
     * registry the server has not sent.
     *
     * @param observeType the observation task's own mode, for the source that depends on it
     */
    public static List<ItemPicker.Entry> list(EditorField.Source source, String observeType) {
        Minecraft minecraft = Minecraft.getInstance();
        RegistryAccess access = minecraft.level == null ? null : minecraft.level.registryAccess();
        List<ItemPicker.Entry> out = new ArrayList<>();

        switch (source) {
            case DIMENSION -> dimensions(minecraft, out);
            case BIOME -> registry(access, Registries.BIOME, out, true);
            case STRUCTURE -> registry(access, Registries.STRUCTURE, out, true);
            case ENCHANTMENT -> registry(access, Registries.ENCHANTMENT, out, false);
            case EFFECT -> builtIn(BuiltInRegistries.MOB_EFFECT, out, false);
            case ATTRIBUTE -> builtIn(BuiltInRegistries.ATTRIBUTE, out, false);
            case FLUID -> builtIn(BuiltInRegistries.FLUID, out, false);
            case ENTITY -> builtIn(BuiltInRegistries.ENTITY_TYPE, out, false);
            case ITEM_TAG -> tags(BuiltInRegistries.ITEM, out, false);
            case ENTITY_TAG -> tags(BuiltInRegistries.ENTITY_TYPE, out, false);
            case ADVANCEMENT -> advancements(minecraft, out);
            case STAT -> BuiltInRegistries.CUSTOM_STAT.keySet()
                    // The custom statistics are exactly what a stat task reads -- `Stats.CUSTOM` -- and
                    // their keys are the ids a file writes. Block and item statistics are not listed:
                    // the task resolves through CUSTOM only, so offering the others would be offering
                    // values the engine cannot read.
                    .forEach(stat -> out.add(entry(stat.toString())));
            case OBSERVATION_TARGET -> observation(observeType, out);
        }
        // Alphabetical by id, so a picker opened before anything is typed shows the same ten rows twice
        // running -- and so "the first ten" means the first ten anything an author would look for.
        out.sort(java.util.Comparator.comparing(ItemPicker.Entry::id));
        return List.copyOf(out);
    }

    /**
     * The dimensions: the server's own list when it has sent one, and the three every player knows when it
     * has not.
     *
     * <p>The server's list is the one that matters, and it is the only way a modded or datapack dimension
     * can appear: the client is never sent them, because a dimension is level data rather than a registry
     * entry. See {@code DimensionSyncPayload}. The fallback covers the moment before it arrives -- a
     * client that has not been told yet -- and it is deliberately not a lie about the rest: the box takes
     * a typed id whatever the list holds.
     */
    private static void dimensions(Minecraft minecraft, List<ItemPicker.Entry> out) {
        if (ClientDimensions.known()) {
            ClientDimensions.ids().forEach(id -> out.add(entry(id)));
            addCurrentDimension(minecraft, out);
            return;
        }
        KNOWN_DIMENSIONS.forEach(id -> out.add(entry(id)));
        addCurrentDimension(minecraft, out);
    }

    /** The dimension the player is standing in, when the list does not already hold it. */
    private static void addCurrentDimension(Minecraft minecraft, List<ItemPicker.Entry> out) {
        if (minecraft.level == null) {
            return;
        }
        String here = minecraft.level.dimension().location().toString();
        boolean listed = out.stream().anyMatch(entry -> entry.id().equals(here));
        if (!listed) {
            out.add(entry(here));
        }
    }

    /**
     * An observation's target: a block or an entity, by the task's own mode.
     *
     * <p>The mode decides which list is right -- an entity id in a block field is a task that can never
     * be satisfied -- so the picker asks the same question the engine does.
     */
    private static void observation(String observeType, List<ItemPicker.Entry> out) {
        boolean entity = observeType.startsWith("entity");
        if (entity) {
            if (observeType.equals("entity_type_tag")) {
                tags(BuiltInRegistries.ENTITY_TYPE, out, false);
            }
            else {
                builtIn(BuiltInRegistries.ENTITY_TYPE, out, false);
            }
            return;
        }
        if (observeType.equals("block_tag")) {
            tags(BuiltInRegistries.BLOCK, out, false);
        }
        else {
            builtIn(BuiltInRegistries.BLOCK, out, false);
        }
    }

    /** A datapack registry the server synced, ids only -- and its tags where the field takes one. */
    private static <T> void registry(RegistryAccess access, ResourceKey<? extends Registry<T>> key,
                                    List<ItemPicker.Entry> out, boolean withTags) {
        if (access == null) {
            return;
        }
        Registry<T> registry = access.registry(key).orElse(null);
        if (registry == null) {
            return;
        }
        registry.keySet().forEach(id -> out.add(entry(id.toString())));
        if (withTags) {
            tags(registry, out, true);
        }
    }

    /** One of the game's own registries: ids, and -- for the ones a task takes a tag of -- its tags. */
    private static <T> void builtIn(Registry<T> registry, List<ItemPicker.Entry> out, boolean withTags) {
        registry.keySet().forEach(id -> out.add(entry(id.toString())));
        if (withTags) {
            tags(registry, out, true);
        }
    }

    /**
     * A registry's tags, as the field that consumes them spells one.
     *
     * <p>{@code hash} because the format has two spellings and both are right where they are used: a
     * {@code RegistryRef} field -- a biome, a structure -- writes a tag as {@code #minecraft:is_forest},
     * while an item or entity tag field holds the plain id, because its codec is a resource location and a
     * {@code #} is not one.
     */
    private static <T> void tags(Registry<T> registry, List<ItemPicker.Entry> out, boolean hash) {
        registry.getTagNames().forEach(tag ->
                out.add(entry((hash ? "#" : "") + tag.location())));
    }

    /** The client's own advancement tree: everything the server has told it about. */
    private static void advancements(Minecraft minecraft, List<ItemPicker.Entry> out) {
        if (minecraft.getConnection() == null) {
            return;
        }
        for (AdvancementNode node : minecraft.getConnection().getAdvancements().getTree().nodes()) {
            AdvancementHolder holder = node.holder();
            String label = holder.value().display()
                    .map(display -> display.getTitle().getString())
                    .orElseGet(() -> EditorSpecs.label(holder.id().getPath()));
            out.add(new ItemPicker.Entry(holder.id().toString(), label, 1, ""));
        }
    }

    /** One row: the id, and the name a person reads. */
    private static ItemPicker.Entry entry(String id) {
        ResourceLocation location = id.startsWith("#")
                ? ResourceLocation.tryParse(id.substring(1))
                : ResourceLocation.tryParse(id);
        String label = location == null ? id : EditorSpecs.label(location.getPath());
        return new ItemPicker.Entry(id, label, 1, "");
    }
}
