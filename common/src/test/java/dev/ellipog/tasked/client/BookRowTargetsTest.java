package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.viewer.MinecraftTestBootstrap;
import dev.ellipog.tasked.client.viewer.RecipeLookups;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which rows can send the player to a viewer, and which cannot.
 *
 * <p>The rule is the scope of the feature: item tasks and item rewards have an item, a tag task has
 * a tag, and every other row — a checkmark, a stage, an item the client could not resolve, a reward
 * with no item — has nothing to open. Pinned here because the screen that applies it cannot be
 * instantiated by a test, the same reason {@code SidebarLayout} and {@code ItemPicker} carry their
 * rules in game-free classes.
 */
class BookRowTargetsTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static ClientQuestCache.TaskEntry task(ItemStack item, String itemId, String tagId) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, item, 1, false, false, false, "tasked:item",
                "", "", "", itemId, "", "", 0, tagId, List.of());
    }

    private static ClientQuestCache.RewardEntry reward(ItemStack item, String itemId) {
        return new ClientQuestCache.RewardEntry(ItemStack.EMPTY, item, 1, "tasked:item", "", "", "",
                itemId, "", false, false, List.of());
    }

    @Test
    @DisplayName("an item task's target is its item")
    void itemTaskIsAnItemTarget() {
        ItemStack stack = new ItemStack(Items.STONE);

        RecipeLookups.Target target = BookRowTargets.ofTask(task(stack, "minecraft:stone", ""));

        assertFalse(target.isTag());
        assertSame(stack, target.item());
    }

    @Test
    @DisplayName("a tag task's target is its tag, parsed from the wire id")
    void tagTaskIsATagTarget() {
        RecipeLookups.Target target = BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "minecraft:logs"));

        assertTrue(target.isTag());
        assertEquals("minecraft:logs", target.tag().location().toString());
    }

    @Test
    @DisplayName("a tag id that does not parse is no target rather than a throw")
    void malformedTagIdHasNoTarget() {
        assertNull(BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "Not A Tag")));
    }

    @Test
    @DisplayName("a row with neither an item nor a tag has no target")
    void textRowsHaveNoTarget() {
        assertNull(BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "")));
        assertNull(BookRowTargets.ofReward(reward(ItemStack.EMPTY, "")));
    }

    @Test
    @DisplayName("an item reward's target is its item; rewards carry no tags")
    void itemRewardIsAnItemTarget() {
        ItemStack stack = new ItemStack(Items.DIAMOND);

        RecipeLookups.Target target = BookRowTargets.ofReward(reward(stack, "minecraft:diamond"));

        assertFalse(target.isTag());
        assertSame(stack, target.item());
    }
}
