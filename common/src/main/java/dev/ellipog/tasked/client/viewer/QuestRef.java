package dev.ellipog.tasked.client.viewer;

import net.minecraft.world.item.ItemStack;

/**
 * One quest, as much of it as every viewer needs: a stable id, the two titles a row or a page shows,
 * and an icon.
 *
 * <p>Deliberately a value and not a handle. A viewer adapter runs at times a screen is not open --
 * EMI registers recipes on a worker thread -- and the only thing it may safely carry across that
 * boundary is immutable data. Everything live (progress, state, claimability) stays behind
 * {@link QuestContent}'s client-thread methods, which is what makes one snapshot safe to read from
 * anywhere and the numbers always current.
 *
 * <p>{@code icon} may be {@link ItemStack#EMPTY} while {@code iconId} is not: a pack can name an item
 * this build does not have, and the rule the book already follows -- keep it and mark it rather than
 * drop it -- applies here too. A viewer that cannot draw the stack draws {@code iconId} instead of
 * silently losing the row.
 */
public record QuestRef(String id, String title, String chapterTitle, ItemStack icon, String iconId) {

    public QuestRef {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a quest reference needs an id");
        }
    }
}
