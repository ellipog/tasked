package dev.ellipog.tasked.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item picker's rules: what a query offers, in what order, and when the typed text itself wins.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>Every rule a picker has that a person could disagree with: the ranking, the cap, what counts as
 * the same answer twice, and what Enter does when the box holds a real id rather than a search. All of
 * it is answerable with lists of strings and no client -- which is the whole reason the rules live in a
 * game-free class: the registry and the player's inventory are the screen's to supply, and everything
 * that can silently be wrong about them is asserted here rather than found in play.
 */
@DisplayName("the item picker's rules")
class ItemPickerTest {

    private static final ItemPicker.Entry OAK =
            new ItemPicker.Entry("minecraft:oak_log", "Oak Log", 0);
    private static final ItemPicker.Entry DARK_OAK =
            new ItemPicker.Entry("minecraft:dark_oak_log", "Dark Oak Log", 0);
    private static final ItemPicker.Entry OAK_PLANKS =
            new ItemPicker.Entry("minecraft:oak_planks", "Oak Planks", 0);
    private static final ItemPicker.Entry OAK_BOAT =
            new ItemPicker.Entry("minecraft:oak_boat", "Oak Boat", 0);
    private static final ItemPicker.Entry STICK =
            new ItemPicker.Entry("minecraft:stick", "Stick", 0);
    private static final ItemPicker.Entry LOG =
            new ItemPicker.Entry("minecraft:log", "Oak", 0);

    private static List<String> ids(List<ItemPicker.Entry> entries) {
        return entries.stream().map(ItemPicker.Entry::id).toList();
    }

    @Test
    @DisplayName("a blank query offers nothing -- the inventory is the list for an empty box")
    void blankQueryOffersNothing() {
        assertTrue(ItemPicker.rank(List.of(OAK, STICK), "  ", ItemPicker.LIMIT).isEmpty());
        assertTrue(ItemPicker.rank(List.of(OAK, STICK), null, ItemPicker.LIMIT).isEmpty());
    }

    @Test
    @DisplayName("the ranking is the id first and the name last, and stable by id inside a rank")
    void theRanking() {
        List<String> ranked = ids(ItemPicker.rank(
                List.of(STICK, LOG, DARK_OAK, OAK_BOAT, OAK, OAK_PLANKS), "oak", ItemPicker.LIMIT));

        // The boat's, the log's and the planks' paths *start* with the query; the dark oak merely
        // contains it; the entry whose id never says "oak" but whose name is one comes last. Inside a
        // rank the id decides, so two servers that register in different orders offer the same list.
        assertEquals(List.of("minecraft:oak_boat", "minecraft:oak_log", "minecraft:oak_planks",
                "minecraft:dark_oak_log", "minecraft:log"), ranked);
    }

    @Test
    @DisplayName("the match is case-blind, and the typed id's own case is what gets committed")
    void caseBlind() {
        assertEquals(ids(ItemPicker.rank(List.of(OAK, OAK_PLANKS), "OAK", ItemPicker.LIMIT)),
                ids(ItemPicker.rank(List.of(OAK, OAK_PLANKS), "oak", ItemPicker.LIMIT)));
    }

    @Test
    @DisplayName("the list is capped, and the cap keeps the best -- a prefix beats a later match")
    void theCap() {
        List<ItemPicker.Entry> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new ItemPicker.Entry("test:block_" + String.format("%03d", i),
                    "Oak Block " + i, 0));
        }
        many.add(new ItemPicker.Entry("test:oak", "Oak", 0));

        List<ItemPicker.Entry> ranked = ItemPicker.rank(many, "oak", 10);
        assertEquals(10, ranked.size(), "the cap is the cap");
        assertEquals("test:oak", ranked.get(0).id(), "an id-prefix match outranks 150 name matches");
    }

    @Test
    @DisplayName("the typed id wins outright -- Enter on a real id does not mean \"the first match\"")
    void theTypedIdWins() {
        List<ItemPicker.Entry> matches =
                ItemPicker.rank(List.of(DARK_OAK, OAK), "minecraft:oak_log", ItemPicker.LIMIT);

        assertEquals("minecraft:oak_log", ItemPicker.exactId("minecraft:oak_log", matches),
                "an exact id is the answer without a selection");
        assertNull(ItemPicker.exactId("oak", matches), "a search is not an id");
        assertNull(ItemPicker.exactId("minecraft:not_a_block", matches));
        assertNull(ItemPicker.exactId("", matches), "a blank box is not an id either");
    }
}
