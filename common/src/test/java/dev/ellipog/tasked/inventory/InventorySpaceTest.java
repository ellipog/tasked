package dev.ellipog.tasked.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fit simulation: merge first, then empty slots, and the offhand's merge-only rule.
 *
 * <p>Game-free — keys are strings where the game uses items — and every rule asserted here was read
 * out of the 1.21.1 {@code PlayerInventory} rather than invented; see {@link InventorySpace}'s javadoc
 * for where each one comes from.
 */
@DisplayName("the inventory space model")
class InventorySpaceTest {

    private static InventorySpace.Slot slot(String key, int count, int max) {
        return new InventorySpace.Slot(key, count, max, true);
    }

    private static InventorySpace.Slot mergeOnly(String key, int count, int max) {
        return new InventorySpace.Slot(key, count, max, false);
    }

    private static InventorySpace.Slot empty() {
        return new InventorySpace.Slot("empty", 0, 0, true);
    }

    private static InventorySpace.Slot emptyMergeOnly() {
        return new InventorySpace.Slot("empty", 0, 0, false);
    }

    private static InventorySpace.Stack need(String key, int count, int max) {
        return new InventorySpace.Stack(key, count, max);
    }

    private static InventorySpace of(InventorySpace.Slot... slots) {
        return InventorySpace.of(new ArrayList<>(List.of(slots)));
    }

    @Test
    @DisplayName("a stack fills a partial slot before it takes an empty one")
    void mergesBeforePlacing() {
        // Sixty stone with room for four, and one empty slot: eight stone is four merged and four
        // placed -- and it fits exactly, which a model that counted only whole empty slots would deny.
        assertTrue(of(slot("stone", 60, 64), empty())
                .fitsAll(List.of(need("stone", 8, 64))));
    }

    @Test
    @DisplayName("a stack that cannot be fully placed is refused rather than partly counted")
    void overflowIsRefused() {
        // One empty slot takes 64; 65 does not fit anywhere.
        assertFalse(of(empty()).fitsAll(List.of(need("stone", 65, 64))));
        assertTrue(of(empty(), empty()).fitsAll(List.of(need("stone", 65, 64))));
    }

    @Test
    @DisplayName("a merge-only slot takes a merge and never a new stack -- the offhand's rule")
    void mergeOnlyTakesMergesOnly() {
        // Vanilla merges into a part-filled offhand and never places a stack into an empty one.
        assertTrue(of(mergeOnly("stone", 60, 64))
                .fitsAll(List.of(need("stone", 4, 64))));
        assertFalse(of(mergeOnly("stone", 60, 64))
                .fitsAll(List.of(need("dirt", 1, 64))));
        assertFalse(of(emptyMergeOnly())
                .fitsAll(List.of(need("dirt", 1, 64))));
    }

    @Test
    @DisplayName("different keys never merge, however much room a slot has")
    void differentKeysDoNotMerge() {
        assertFalse(of(slot("stone", 1, 64)).fitsAll(List.of(need("dirt", 1, 64))));
    }

    @Test
    @DisplayName("a batch is placed in order, each stack before the next is asked about")
    void batchesAreSequential() {
        // Two empty slots: the first stack takes one, the second the other. A model that checked each
        // stack against the same untouched snapshot would call a three-stack batch a fit.
        assertTrue(of(empty(), empty()).fitsAll(List.of(
                need("stone", 64, 64), need("dirt", 64, 64))));
        assertFalse(of(empty(), empty()).fitsAll(List.of(
                need("stone", 64, 64), need("dirt", 64, 64), need("sand", 1, 64))));
    }

    @Test
    @DisplayName("a full inventory refuses even one item, and an empty one takes a whole stack")
    void edges() {
        assertFalse(of(slot("stone", 64, 64)).fitsAll(List.of(need("stone", 1, 64))));
        assertTrue(of(empty()).fitsAll(List.of(need("stone", 64, 64))));
        // Nothing to place is not a failure: a reward that gives nothing is granted, not refused.
        assertTrue(of().fitsAll(List.of()));
    }

    @Test
    @DisplayName("a stack with no count is nothing to place, and a stack of zero is not a need")
    void zeroCountsAreNotNeeds() {
        assertTrue(of(slot("stone", 64, 64)).fitsAll(List.of(need("stone", 0, 64))));
    }
}
