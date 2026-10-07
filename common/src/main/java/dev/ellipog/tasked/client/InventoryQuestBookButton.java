package dev.ellipog.tasked.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.tasked.QuestBook;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.client.hud.HudElement;
import dev.ellipog.tasked.client.hud.HudLayout;
import dev.ellipog.tasked.client.hud.HudSettings;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The quest book's other door: a control over the player's own inventory, in the window's top-left corner.
 *
 * <h2>Where it is</h2>
 *
 * <p>Four pixels in from the window's corner, which is where it ships and where {@link HudSettings} keeps it
 * until a player moves it in the HUD editor. That corner is contested by the recipe viewers -- JEI lays its
 * bookmarks out across the strip left of a container panel, and EMI's default left sidebar is its favourites
 * page -- and the answer to that is the editor and the switch rather than a different corner: a player can
 * put the button anywhere or turn it off, and it is worth having somewhere easy to find.
 *
 * <p>It is offered on the player's own inventory and not on every container screen: an inventory is where a
 * player looks for what is theirs, and a control over a chest they opened for ten seconds is noise.
 *
 * <h2>Why it acts on press</h2>
 *
 * <p>Because a container screen never delivers a release to its widgets: {@code AbstractContainerScreen}
 * overrides {@code mouseReleased} and {@code mouseDragged} without calling {@code super}, so the release that
 * {@link ArmatureButton} acts on never arrives, and a control wired the usual way would look alive and do
 * nothing. Acting on press is also what vanilla's own container-screen buttons do -- {@code onClick} is a
 * press callback -- so this is the ordinary behaviour rather than a workaround, and it needs no release, no
 * clock and no state that could get stuck.
 *
 * <p>Opening the book is the whole action. There is nothing to close first: the inventory menu is the
 * player's default menu, the server is not told about a screen changing, and a stack on the cursor stays on
 * the cursor. The book's key does exactly this, and has since the book existed.
 *
 * <h2>Why it is placed once and not every frame</h2>
 *
 * <p>Because there is nothing for it to follow. The position is a window coordinate; the widget is rebuilt
 * from it whenever a screen initialises, which both loaders do on a resize; and {@link HudLayout} clamps it
 * into the window it is given. That is why this class has no per-frame drawing hook and no place on the seam
 * list: it reads a position, it opens a screen on a press, and it draws itself through the base class like
 * every other control.
 */
public final class InventoryQuestBookButton extends ArmatureButton {

    /** The table entry this control is the drawing of. */
    private static final HudElement ELEMENT = HudElement.INVENTORY_BUTTON;

    private InventoryQuestBookButton(int x, int y, Component label) {
        super(x, y, ELEMENT.width(), ELEMENT.height(), label, InventoryQuestBookButton::open);
        icon(new ItemStack(QuestBook.ITEM));
        setTooltip(Tooltip.create(label));
    }

    /**
     * The control for this screen, or null when this screen should not have one.
     *
     * <p>Three answers, and each is a different reason: the player switched it off, this is not the player's
     * inventory, or the book item is not registered yet -- the last only reachable if a screen somehow opened
     * before mod construction finished, in which case no button is better than a stack trace in a screen.
     */
    public static InventoryQuestBookButton forScreen(Screen screen) {
        if (!HudSettings.on(ELEMENT)) {
            return null;
        }
        if (!(screen instanceof InventoryScreen)) {
            return null;
        }
        if (QuestBook.ITEM == null) {
            return null;
        }
        var box = HudLayout.boxAt(ELEMENT, screen.width, screen.height,
                HudSettings.x(ELEMENT), HudSettings.y(ELEMENT));
        return new InventoryQuestBookButton(box.x(), box.y(),
                Component.translatable("tasked.screen.quest_book.title"));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            // On press, and the screen is replaced a moment later, which is what takes the press state with
            // it -- see the class note.
            open(this);
        }
        return handled;
    }

    private static void open(ArmatureButton button) {
        ArmatureClient.openScreen(Tasked.QUEST_BOOK_SCREEN);
    }
}
