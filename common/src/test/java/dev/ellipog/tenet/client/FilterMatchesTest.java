package dev.ellipog.tenet.client;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a filter expression names, enumerated against bootstrapped vanilla.
 */
class FilterMatchesTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("a single item matches itself")
    void singleItemMatches() {
        FilterMatches.Matches matches = FilterMatches.of("item(minecraft:stone)");
        assertTrue(matches.total() >= 1);
        assertTrue(matches.shown().stream().anyMatch(stack -> stack.is(Items.STONE)));
        assertFalse(matches.truncated());
    }

    @Test
    @DisplayName("a namespace matches everything under it, truncated to the list cap")
    void namespaceTruncates() {
        FilterMatches.Matches matches = FilterMatches.of("mod(minecraft)");
        assertTrue(matches.total() > FilterMatches.MAX_LISTED);
        assertEquals(FilterMatches.MAX_LISTED, matches.shown().size());
        assertTrue(matches.truncated());
    }

    @Test
    @DisplayName("empty and unparseable expressions match nothing")
    void emptyAndBrokenMatchNothing() {
        assertTrue(FilterMatches.of("").isEmpty());
        assertTrue(FilterMatches.of("or(item(minecraft:stone)").isEmpty());
    }

    @Test
    @DisplayName("forgetting clears the cache without changing the answer")
    void forgetClearsCache() {
        FilterMatches.Matches before = FilterMatches.of("item(minecraft:dirt)");
        FilterMatches.forget();
        FilterMatches.Matches after = FilterMatches.of("item(minecraft:dirt)");
        assertEquals(before.total(), after.total());
    }
}
