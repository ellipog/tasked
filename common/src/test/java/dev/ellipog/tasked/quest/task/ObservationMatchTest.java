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
    @DisplayName("a block tag nothing declares matches nothing, and an unparsable one does too")
    void blockByTag() {
        var log = Blocks.OAK_LOG.defaultBlockState();

        // A bare Bootstrap JVM has the registries but not the vanilla datapack, so tags are unbound
        // there: every tag query answers false. But this class shares a JVM with the playthrough,
        // whose server loads the vanilla datapack and binds every real tag -- whether that has
        // happened by the time this test runs is JVM ordering, and a test may not depend on it. So
        // the negative case is asked about a tag NO datapack declares, which answers false either
        // way and is the production behaviour for a tag nothing declares. The positive case (a bound
        // tag matching a member) is exercised by the playthrough, where datapacks load.
        assertFalse(ObservationTask.matchesBlock(log, ObservationTask.ObserveType.BLOCK_TAG,
                "minecraft:tag_no_datapack_declares"), "a tag nothing declares matches nothing");
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
