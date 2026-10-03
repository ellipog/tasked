package dev.ellipog.tasked.client.viewer;

import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The little drawing every adapter repeats: the truncation fit, a status pill, and the pin star's
 * two textures.
 *
 * <p>Three adapters draw the same row twice over — the same label, the same badge — and the pieces
 * that must not drift are the arithmetic ones: how a string is trimmed to a pixel width, and how big
 * a pill is for the word inside it. They live here, mod-free and viewer-free, so the three pages
 * cannot disagree about a pill's height or a trim's ellipsis. The star is here for the same reason
 * one level up: one adapter draws it, but the image it draws is the content's, not a viewer's.
 */
public final class PageArt {

    /** A pill's height: a word's line plus a pixel of padding above and below. */
    public static final int PILL_HEIGHT = 10;
    private static final int PILL_PADDING = 3;

    private PageArt() {
    }

    /**
     * Trims a string to a pixel width, for text a viewer draws without a wrap.
     *
     * <p>ASCII ellipsis deliberately: the font's coverage is measured, and a codepoint outside it is
     * a box rather than a character.
     */
    public static String fit(GuiRenderer renderer, String text, int width) {
        if (width <= 0 || renderer.textWidth(text) <= width) {
            return width <= 0 ? "" : text;
        }
        String cut = text;
        while (!cut.isEmpty() && renderer.textWidth(cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.isEmpty() ? "" : cut + "...";
    }

    /** How wide a pill is for this word. */
    public static int pillWidth(GuiRenderer renderer, String text) {
        return renderer.textWidth(text) + 2 * PILL_PADDING;
    }

    /**
     * Draws a status pill: a filled rectangle with its word on it, white so the fill carries it.
     *
     * @return the pill's width, for a caller placing it against a right edge
     */
    public static int pill(GuiRenderer renderer, String text, int x, int y, int colour) {
        int width = pillWidth(renderer, text);
        renderer.fill(x, y, x + width, y + PILL_HEIGHT, colour);
        renderer.text(text, x + PILL_PADDING, y + 1, PagePalette.PILL_TEXT);
        return width;
    }

    /**
     * A reward row's standing, or null when the quest is not finished and there is nothing to say.
     *
     * <p>One rule for three adapters, so a page cannot call the same row Ready in one viewer and
     * Claimed in another.
     */
    public static QuestContent.RewardStatus rewardStatus(QuestRow live) {
        if (live.locked()) {
            return QuestContent.RewardStatus.LOCKED;
        }
        if (live.done()) {
            return QuestContent.RewardStatus.CLAIMED;
        }
        return live.claimable() ? QuestContent.RewardStatus.READY : null;
    }

    /** The colour a reward status is drawn in: the same three the palette uses elsewhere. */
    public static int rewardStatusColour(QuestContent.RewardStatus status) {
        return switch (status) {
            case READY -> PagePalette.COMPLETE;
            case LOCKED -> PagePalette.LOCKED;
            case CLAIMED -> PagePalette.MUTED;
        };
    }

    // ------------------------------------------------------------------
    // The star, and what a quest looks like in a viewer's sidebar
    // ------------------------------------------------------------------

    /**
     * The two pin-star textures: the empty outline for an unpinned quest, the filled one for a
     * pinned quest.
     *
     * <p>Files rather than a drawn shape, and that is the point now. The old mask guaranteed the two
     * states were the same star by computing the hollow ring from the filled pixels; two images
     * cannot be made to agree that way, so they are drawn as a pair and ship together — a pack that
     * replaces one replaces both.
     */
    public static final ResourceLocation STAR_EMPTY =
            ResourceLocation.fromNamespaceAndPath("tasked", "textures/gui/star_empty.png");
    public static final ResourceLocation STAR_FILLED =
            ResourceLocation.fromNamespaceAndPath("tasked", "textures/gui/star_filled.png");

    /** The star's side in pixels: the two images' size, and so the pin button's. */
    private static final int STAR_SIZE = 12;

    /** The star's drawn size in pixels -- and so the pin button's, which the layout reserves. */
    public static int starSize() {
        return STAR_SIZE;
    }

    /**
     * Draws the pin star: the filled texture when the quest is pinned, the empty one otherwise.
     *
     * <p>Hover is not this method's: the header draws its own wash behind the button, so the star
     * itself is one texture and no state.
     */
    public static void star(GuiRenderer renderer, int x, int y, boolean pinned) {
        renderer.texture(pinned ? STAR_FILLED : STAR_EMPTY, x, y, STAR_SIZE, STAR_SIZE);
    }

    /**
     * What a viewer's recipe for this quest offers as its outputs.
     *
     * <p>The item rewards, in order; and when there are none — an xp-only, stage or command reward —
     * the quest's own icon, with the category's icon behind it as a last resort. Never empty, and
     * that is the point: a viewer favourites a recipe by its first output, draws the sidebar entry
     * from it, and refuses an empty stack, so an empty list is the difference between a quest that
     * can be pinned and one that cannot.
     */
    public static List<ItemStack> outputs(QuestPage page, ItemStack fallbackIcon) {
        List<ItemStack> out = new ArrayList<>();
        for (QuestRow row : page.rewards()) {
            if (!row.icon().isEmpty()) {
                out.add(row.icon());
            }
        }
        if (out.isEmpty() && !page.quest().icon().isEmpty()) {
            out.add(page.quest().icon());
        }
        if (out.isEmpty() && fallbackIcon != null && !fallbackIcon.isEmpty()) {
            out.add(fallbackIcon);
        }
        return List.copyOf(out);
    }
}
