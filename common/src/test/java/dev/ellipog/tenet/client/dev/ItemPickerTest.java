package dev.ellipog.tenet.client.dev;

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
    @DisplayName("only the icon may be cleared, and clearing it takes the whole object")
    void whatMayBeCleared() {
        // The defect this pins: a clear row on a task deleted "item", the field-name validator saw
        // nothing wrong, the save was allowed, and the quest left the tree at the next load. A task's
        // item is required, so there is nothing a clear row could do there but corrupt the file.
        // The icon is the one optional item field -- and clearing it removes the whole "icon" object,
        // because deleting only the leaf would leave {"icon": {}}, which the codec refuses too. A
        // task's or a reward's picture override clears the same way, restoring the type's picture.
        assertEquals("icon", ItemPicker.clearPath("icon.item"));
        assertEquals("tasks.0.icon", ItemPicker.clearPath("tasks.0.icon"));
        assertEquals("rewards.2.icon", ItemPicker.clearPath("rewards.2.icon"));
        assertNull(ItemPicker.clearPath("tasks.0.item"), "an item task needs its item");
        assertNull(ItemPicker.clearPath("rewards.2.item"));
        assertNull(ItemPicker.clearPath("title"));
    }

    @Test
    @DisplayName("a typed id that matches nothing is offered anyway -- mods come and go")
    void aTypedIdThatMatchesNothingIsStillAnAnswer() {
        // "Allow items the build does not have" has to include writing one on purpose: an author
        // building a pack before the mod is installed types the id and needs it kept.
        List<ItemPicker.Entry> matches =
                ItemPicker.rank(List.of(OAK), "someothermod:widget", ItemPicker.LIMIT);

        assertEquals("someothermod:widget", ItemPicker.missingCandidate("someothermod:widget", matches));
        assertNull(ItemPicker.missingCandidate("oak", matches), "a search is not an id to keep");
        assertNull(ItemPicker.missingCandidate("not an id", matches), "and neither is a sentence");
        assertNull(ItemPicker.missingCandidate("minecraft:oak_log",
                        ItemPicker.rank(List.of(OAK), "minecraft:oak_log", ItemPicker.LIMIT)),
                "an id that matches something is not missing");
    }

    @Test
    @DisplayName("a typed #tag is kept where the field takes one, and refused where it does not")
    void aTypedTagIsKeptOnlyWhereTheFieldTakesOne() {
        // A RegistryRef field -- a biome, a structure -- writes a tag as `#minecraft:village`, and a
        // resource location cannot hold a `#`. So without the flag the box refused the very spelling the
        // field's codec reads, and a tag an author typed could only be set by editing the file.
        List<ItemPicker.Entry> none = List.of();
        assertEquals("#minecraft:village", ItemPicker.missingCandidate("#minecraft:village", none, true));
        assertNull(ItemPicker.missingCandidate("#minecraft:village", none),
                "an item tag field's codec is a bare resource location, so a `#` there is still not an id");
        assertNull(ItemPicker.missingCandidate("#not a tag", none, true),
                "and the rest of the spelling still has to be a resource location");
        assertNull(ItemPicker.missingCandidate("village", none, true),
                "a bare word is a search, tag or not: a deliberate value names its namespace");
        assertEquals("minecraft:village_plains",
                ItemPicker.missingCandidate("minecraft:village_plains", none, true),
                "the flag does not stop a plain id from being kept by a field that takes tags");

        // A tag the list already holds is not a missing value: the row is there to be pressed.
        List<ItemPicker.Entry> listed =
                ItemPicker.rank(List.of(new ItemPicker.Entry("#minecraft:village", "Village", 0)),
                        "#minecraft:village", ItemPicker.LIMIT);
        assertNull(ItemPicker.missingCandidate("#minecraft:village", listed, true));
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
