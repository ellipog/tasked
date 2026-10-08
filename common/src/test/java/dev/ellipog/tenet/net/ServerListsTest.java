package dev.ellipog.tenet.net;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.levelgen.structure.Structure;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structure list the server sends, against the datapack's own structures.
 *
 * <h2>Why this needs the real datapack</h2>
 *
 * <p>Because the fault it is here to prevent was a list that resolved to nothing, and a test written
 * against a hand-built registry would have agreed with the hand-built registry while the game's own
 * answered empty. So the registries are loaded the way vanilla loads them: the vanilla pack, the worldgen
 * layer, {@code RegistryDataLoader.WORLDGEN_REGISTRIES} — the three lines of {@code WorldLoader.loadLayer}
 * that stand between a pack on disk and a server's worldgen registry access. No world, no server, no
 * player: what is under test is the gathering, so nothing else is asked for.
 *
 * <p>The ids are then checked in both directions: the vanilla facts a person can name
 * ({@code minecraft:village_plains}, {@code minecraft:stronghold}, the {@code #minecraft:village} tag),
 * and the property that the list <b>is</b> the registry's own keys plus its tags — which is what makes a
 * hardcoded vanilla list impossible to ship in place of the real one.
 *
 * <p>The assertions name specific ids rather than a count on purpose. A count pinned to one Minecraft
 * version stops meaning anything the moment the version moves, and this file's subject is not how many
 * structures 1.21.1 has; it is that whatever the server has is what the client is offered.
 */
@DisplayName("server lists")
class ServerListsTest {

    private static RegistryAccess worldgen;
    private static Registry<Structure> structures;

    /**
     * The vanilla worldgen registries, loaded exactly as {@code WorldLoader} loads them.
     *
     * <p>{@code RegistryLayer.createRegistryAccess()} is the static layer, and
     * {@code getAccessForLoading(WORLDGEN)} is what the worldgen entries are read against — the two
     * calls vanilla makes in {@code loadLayer}. Getting this wrong is not silent: the loader reports every
     * element it cannot decode.
     *
     * <p>The pack is closed once the load has returned, because the load reads every entry eagerly: what
     * the registries hold afterwards does not need the files any more.
     */
    @BeforeAll
    static void loadVanillaWorldgen() throws IOException {
        MinecraftTestBootstrap.boot();
        PackResources vanilla = ServerPacksSource.createVanillaPackSource();
        try (CloseableResourceManager packs =
                     new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla))) {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            worldgen = RegistryDataLoader.load(packs,
                    layers.getAccessForLoading(RegistryLayer.WORLDGEN),
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
            // **And then the tags, which are a second step and a second file.** A registry's tags are not
            // in the entry files: they live under `data/<ns>/tags/<registry>/`, and vanilla binds them
            // after every registry is loaded -- `ReloadableServerResources.updateRegistryTags`, called
            // from `WorldLoader`'s last stage. Without this the structure registry loads with no tags at
            // all, which is a green-looking fixture that would have agreed with a tag-less list; the first
            // run of this test failed exactly there.
            bindStructureTags(packs);
        }
        structures = worldgen.registryOrThrow(Registries.STRUCTURE);
    }

    /**
     * The two moves the server makes for one registry's tags, taken from vanilla rather than invented.
     *
     * <p>{@code TagLoader} over `Registries.tagsDirPath(key)` is what {@code TagManager.createLoader} does,
     * and {@code bindTags} with one {@code TagKey} per file is what {@code updateRegistryTags} does. Only
     * the structures are bound: they are this file's subject, and a test that bound every registry would be
     * a slower way to say the same thing.
     */
    private static void bindStructureTags(CloseableResourceManager packs) {
        Registry<Structure> registry = worldgen.registryOrThrow(Registries.STRUCTURE);
        TagLoader<Holder<Structure>> loader =
                new TagLoader<>(registry::getHolder, Registries.tagsDirPath(Registries.STRUCTURE));
        Map<TagKey<Structure>, List<Holder<Structure>>> tags = loader.loadAndBuild(packs)
                .entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> TagKey.create(Registries.STRUCTURE, entry.getKey()),
                        entry -> List.copyOf(entry.getValue())));
        registry.bindTags(tags);
    }

    @Test
    @DisplayName("the structures a server would send are the datapack's own, key for key and tag for tag")
    void theStructureListIsTheRegistriesOwn() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        Set<String> expected = new TreeSet<>();
        structures.keySet().forEach(id -> expected.add(id.toString()));
        structures.getTagNames().forEach(tag -> expected.add("#" + tag.location()));

        assertEquals(List.copyOf(expected), sent,
                "the list the server sends is not the registry it read: a structure the pack has would be "
                        + "missing from every picker, or a value offered that the engine cannot resolve");
        assertFalse(sent.isEmpty(), "the vanilla datapack defines structures, so this cannot be empty");
    }

    @Test
    @DisplayName("the vanilla structures and their tag are in it, in the spelling a file uses")
    void theVanillaFactsAreThere() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        // Named because they are the ones a person would look for: the structure task's own default, the
        // one every pack knows, and the tag that stands for the five villages.
        assertTrue(sent.contains("minecraft:village_plains"), "the vanilla plains village is missing");
        assertTrue(sent.contains("minecraft:stronghold"), "the vanilla stronghold is missing");
        assertTrue(sent.contains("#minecraft:village"),
                "a structure tag is a value the field takes, so it has to be offered: `structure` is a "
                        + "RegistryRef and `#minecraft:village` is how five villages are named at once");

        // Every entry is a spelling a file can hold: an id, or a `#` tag. A list that carried a name, a
        // label or a translated string would look right in a picker and write a value the codec refuses.
        for (String id : sent) {
            String location = id.startsWith("#") ? id.substring(1) : id;
            assertNotNull(ResourceLocation.tryParse(location),
                    id + " is neither an id nor a #tag, and the field's codec takes nothing else");
        }
    }

    @Test
    @DisplayName("the ids and the tags are both in the one sorted list")
    void idsAndTagsAreOneList() {
        List<String> sent = TenetNetworking.structureIds(worldgen);

        assertTrue(sent.containsAll(structures.keySet().stream().map(Object::toString).toList()),
                "every structure id has to be offered");
        assertFalse(structures.getTagNames().toList().isEmpty(),
                "the vanilla datapack defines structure tags, so the tag half of this test is real");
        for (var tag : structures.getTagNames().toList()) {
            assertTrue(sent.contains("#" + tag.location()), tag + " is missing from the tag list");
        }
        // Sorted, so a picker's first ten rows are the same ten twice running.
        assertEquals(sent.stream().sorted().toList(), sent, "the list is not sorted");
    }
}
