package dev.ellipog.tasked.quest.task;

import dev.ellipog.tasked.quest.MinecraftTestBootstrap;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observation matchers, against real blocks.
 *
 * <p>The client's ray-trace is the only part of an observation that needs a world; the question
 * "does this block answer that filter" is pure, and this is where it is answered against the real
 * registry. Entity matching needs a level to hold an entity, so it is exercised by the playthrough
 * instead.
 */
@DisplayName("observation matching")
class ObservationMatchTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("a block is matched by its own id, and only its own")
    void blockById() {
        var beacon = Blocks.BEACON.defaultBlockState();

        assertTrue(ObservationTask.matchesBlock(beacon, ObservationTask.ObserveType.BLOCK,
                "minecraft:beacon"));
        assertFalse(ObservationTask.matchesBlock(beacon, ObservationTask.ObserveType.BLOCK,
                "minecraft:stone"), "another block is not this one");
        assertFalse(ObservationTask.matchesBlock(beacon, ObservationTask.ObserveType.BLOCK, "beacon"),
                "an unqualified name is not an id");
    }

    @Test
    @DisplayName("a block tag that is not bound matches nothing, and an unparsable one does too")
    void blockByTag() {
        var log = Blocks.OAK_LOG.defaultBlockState();

        // A bare Bootstrap JVM has the registries but not the vanilla datapack, so tags are unbound
        // here: every tag query answers false. That is asserted rather than worked around, because it
        // is also the production behaviour for a tag nothing declares -- and the positive case (a
        // bound tag matching a member) is exercised by the playthrough, where datapacks load.
        assertFalse(ObservationTask.matchesBlock(log, ObservationTask.ObserveType.BLOCK_TAG,
                "minecraft:logs"), "an unbound tag matches nothing in this environment");
        assertFalse(ObservationTask.matchesBlock(log, ObservationTask.ObserveType.BLOCK_TAG,
                "minecraft:planks"));
        assertFalse(ObservationTask.matchesBlock(log, ObservationTask.ObserveType.BLOCK_TAG,
                "not a tag"), "an unparsable tag matches nothing");
    }

    @Test
    @DisplayName("a block state matches the properties it names, not just the block")
    void blockStateWithProperties() {
        var upright = Blocks.OAK_LOG.defaultBlockState();

        assertTrue(ObservationTask.matchesBlock(upright, ObservationTask.ObserveType.BLOCK_STATE,
                "minecraft:oak_log[axis=y]"));
        assertFalse(ObservationTask.matchesBlock(upright, ObservationTask.ObserveType.BLOCK_STATE,
                "minecraft:oak_log[axis=x]"), "the property is part of the filter");
        assertFalse(ObservationTask.matchesBlock(upright, ObservationTask.ObserveType.BLOCK_STATE,
                "minecraft:oak_log[axis=sideways]"), "an unparsable state matches nothing");
    }

    @Test
    @DisplayName("a block filter never answers an entity question, and the reverse")
    void kindsDoNotCross() {
        var beacon = Blocks.BEACON.defaultBlockState();

        assertFalse(ObservationTask.matchesBlock(beacon, ObservationTask.ObserveType.ENTITY_TYPE,
                "minecraft:beacon"));
        assertFalse(ObservationTask.matchesBlock(beacon, ObservationTask.ObserveType.BLOCK_ENTITY,
                "minecraft:beacon"), "block-entity matching needs the block entity, not the state");
    }
}
