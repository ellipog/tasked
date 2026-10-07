package dev.ellipog.tenet.client.viewer;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * A screen that accepts an item dragged out of a recipe viewer.
 *
 * <h2>Why the screen is the seam rather than each viewer</h2>
 *
 * <p>Three viewers, three drag APIs — a ghost target, a drop handler, a stack visitor — and one thing
 * they all mean: "this stack, at this point, belongs to that panel". So the panels answer that question
 * ({@code QuestBookScreen} implements this) and each viewer's adapter does nothing but translate its own
 * event into it. A viewer whose API cannot say where the pointer is appends instead of inserting; that
 * is a fact about the API, and it is better stated here than worked around in three places.
 *
 * <h2>Null area means "not now"</h2>
 *
 * <p>{@link #dropArea()} is what a viewer highlights and hit-tests against, and it is null whenever the
 * screen is not showing something that takes a drop — the book open on a chapter, or a table open with
 * its roll report over the list. A viewer that asked a screen with no drop area for targets would be
 * offering a drop that the screen would then refuse, which reads as a broken drag rather than as a
 * screen that does not want one.
 */
public interface ItemDropTarget {

    /** Where a dropped stack may land, or null when this screen takes no drop right now. */
    Rect2i dropArea();

    /**
     * Takes a stack dropped at a point.
     *
     * @return whether the screen took it, which is what tells a viewer to finish the drag
     */
    boolean acceptDrop(double mouseX, double mouseY, ItemStack stack);

    /**
     * Takes a stack dropped on the area as a whole, for a viewer whose API does not report the point.
     *
     * <p>The default says "the end of the list" with the coordinates a panel already reads as one.
     */
    default boolean acceptDrop(ItemStack stack) {
        return acceptDrop(Double.NaN, Double.NaN, stack);
    }
}
