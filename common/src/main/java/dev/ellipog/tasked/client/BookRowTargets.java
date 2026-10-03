package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.viewer.RecipeLookups;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Which task and reward rows can send the player to a recipe viewer, and with what.
 *
 * <h2>Why this is not decided in the screen</h2>
 *
 * <p>The rule is the feature: an item task and an item reward have something to look up, a tag task
 * has a tag, and every other row — xp, stage, command, checkmark, or an item the client cannot
 * resolve — has nothing. A screen cannot be instantiated by a test, so a rule that lives only inside
 * one is a rule that is verified by playing; this is the same split {@code SidebarLayout},
 * {@code ItemPicker} and {@code BookGeometry} already make, and for the same reason.
 */
public final class BookRowTargets {

    private BookRowTargets() {
    }

    /**
     * The target for a task row, or null when pressing it should do nothing.
     *
     * <p>A tag id that does not parse is treated as no target rather than as an error: the row still
     * draws (its id is the label), and a press on it must not throw over a malformed id the server
     * already accepted.
     */
    public static RecipeLookups.Target ofTask(ClientQuestCache.TaskEntry task) {
        if (task.hasItem()) {
            return RecipeLookups.Target.of(task.item());
        }
        if (task.tagId().isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(task.tagId());
        return id == null ? null : RecipeLookups.Target.ofTag(TagKey.create(Registries.ITEM, id));
    }

    /** The target for a reward row: an item reward, or nothing. Rewards carry no tags. */
    public static RecipeLookups.Target ofReward(ClientQuestCache.RewardEntry reward) {
        return reward.hasItem() ? RecipeLookups.Target.of(reward.item()) : null;
    }
}
