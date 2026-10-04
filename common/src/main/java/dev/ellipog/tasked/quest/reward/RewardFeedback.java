package dev.ellipog.tasked.quest.reward;

import net.minecraft.world.item.ItemStack;

/**
 * What a grant had to leave behind, counted as it happens.
 *
 * <h2>Why a tally and not a message</h2>
 *
 * <p>A reward that does not fit is dropped at the player's feet, and the player has to be told — but not
 * once per stack. A claim-all can overflow on twenty stacks across five quests, and twenty action-bar
 * messages are a wall of text that names the last thing that happened rather than the one fact the
 * player needs: something is on the floor. So the drop site reports here and the claim operation, which
 * is the only place that knows how many grants one press produced, says the one sentence.
 *
 * <p>Mutable and passed through {@link RewardContext}, because the alternative — a grant returning its
 * own result — would change {@link RewardBehaviour} for every reward type including an addon's, and the
 * question this answers is about the operation, not about any one reward.
 */
public final class RewardFeedback {

    private int droppedStacks;
    private int droppedItems;

    /** Records one stack that did not fit and was dropped. Empty stacks are not a drop. */
    public void dropped(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        droppedStacks++;
        droppedItems += stack.getCount();
    }

    /** How many stacks hit the ground. What the player is told, because that is what they can see. */
    public int droppedStacks() {
        return droppedStacks;
    }

    /** How many items those stacks held, for a caller that wants to say something finer. */
    public int droppedItems() {
        return droppedItems;
    }

    /** Whether anything was dropped at all. */
    public boolean anythingDropped() {
        return droppedStacks > 0;
    }
}
