package dev.ellipog.tasked.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest book's drawing bands, as arithmetic rather than as pixels.
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>A screenshot showed the sidebar's rows, its row icons, the zoom cluster and the header controls
 * standing on top of an open quest card. One cause was {@code modalRedraws} holding controls that
 * belong behind the card; the other was depth. {@code GuiGraphics.renderItem} writes depth <b>150
 * above the pose it inherits</b>, so a band that has to cover the one below it must clear that band's
 * item icons by more than 150 -- and the card and the chrome used to share one Z, which is exactly a
 * card that cannot cover the icons in the layer it is drawn over.
 *
 * <p>The 150 is vanilla's and cannot be imported, so it is named here once: if a future Minecraft
 * moves the item layer, this fails here rather than the screen quietly losing a layer. The bands
 * themselves live in {@link QuestBookScreen} as the named constants the screen reads, package-private
 * so this test can compare them.
 */
class QuestBookLayerTest {

    /** Vanilla's own item offset: {@code GuiGraphics.renderItem} renders at z + 150. */
    private static final float ITEM_Z = 150F;

    @Test
    @DisplayName("every band clears the item icons of the band below it")
    void bandsClearTheLayerBelow() {
        assertTrue(QuestBookScreen.MODAL_Z - QuestBookScreen.CHROME_Z > ITEM_Z,
                "the modal card must be above the chrome's item icons (sidebar row icons)");
        assertTrue(QuestBookScreen.TOOLTIP_Z - QuestBookScreen.MODAL_Z > ITEM_Z,
                "tooltips must be above the modal's item icons (the card's task and reward icons)");
    }

    @Test
    @DisplayName("the bands are ordered chrome < modal < tooltip")
    void bandsAreOrdered() {
        assertTrue(QuestBookScreen.CHROME_Z < QuestBookScreen.MODAL_Z);
        assertTrue(QuestBookScreen.MODAL_Z < QuestBookScreen.TOOLTIP_Z);
    }
}
