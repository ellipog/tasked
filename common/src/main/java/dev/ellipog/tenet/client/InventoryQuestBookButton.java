package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.hud.HudElement;
import dev.ellipog.tenet.client.hud.HudLayout;
import dev.ellipog.tenet.client.hud.HudSettings;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The quest book's other door: a control over the player's own inventory, in the window's top-left corner.
 *
 * <h2>Where it is</h2>
 *
 * <p>Two pixels in from the window's corner, which is where it ships and where {@link HudSettings} keeps it
 * until a player moves it in the HUD editor. That corner is contested by the recipe viewers -- JEI lays its
 * bookmarks out across the strip left of a container panel, and EMI's default left sidebar is its favourites
 * page -- and the answer to that is the editor and the switch rather than a different corner: a player can
 * put the button anywhere or turn it off, and it is worth having somewhere easy to find.
 *
 * <p>It is offered on the player's own inventory -- **in survival and in creative**, which are two screens
 * rather than one -- and not on every container screen: an inventory is where a player looks for what is
 * theirs, and a control over a chest they opened for ten seconds is noise.
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
        // The same icon at the same inset the editor's preview draws, both taken from the element's own
        // entry: the box is the table's, so the sprite that fills it has to be the table's too.
        icon(new ItemStack(QuestBook.ITEM)).iconInset(ELEMENT.iconInset());
        setTooltip(Tooltip.create(label));
    }

    /**
     * The control for this screen, or null when this screen should not have one.
     *
     * <p>Three answers, and each is a different reason: the player switched it off, this is not one of the
     * player's own two inventory screens, or the book item is not registered yet -- the last only reachable
     * if a screen somehow opened before mod construction finished, in which case no button is better than a
     * stack trace in a screen.
     */
    public static InventoryQuestBookButton forScreen(Screen screen) {
        if (!HudSettings.on(ELEMENT)) {
            return null;
        }
        // **Two screens, and neither is the other's parent.** `InventoryScreen` and
        // `CreativeModeInventoryScreen` are both `EffectRenderingInventoryScreen`s and nothing more, so
        // asking for `InventoryScreen` alone answered null in creative -- and the report was exactly that:
        // *"the icon top left in inventory for quest book is not there in creative mode"*. Two explicit
        // names rather than the shared parent, because the rule is the **player's own inventory** and a
        // mod's own inventory-shaped screen is not that.
        if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) {
            return null;
        }
        if (QuestBook.ITEM == null) {
            return null;
        }
        var box = HudLayout.boxAt(ELEMENT, screen.width, screen.height,
                HudSettings.x(ELEMENT), HudSettings.y(ELEMENT), ELEMENT.width(), ELEMENT.height());
        return new InventoryQuestBookButton(box.x(), box.y(),
                Component.translatable("tenet.screen.quest_book.title"));
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
        // Refused with the pack's sentence when the pack disabled its book, like every other
        // client-side open. See `QuestBookScreen.checkOpenAllowed`.
        if (!QuestBookScreen.checkOpenAllowed()) {
            return;
        }
        ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
    }
}
