package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.viewer.RecipeLookups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What a {@code tenet:filter} task accepts, as a scrollable list.
 *
 * <p>A screen of its own rather than a card inside the book: the book's screen is a bespoke
 * layout with its own scrim and modal rules, and a preview is a plain list with a back button
 * — vanilla widgets, no new layout language. Opened from a filter row's icon; closing returns
 * to the book instance that opened it, which keeps its scroll and selection.
 */
public class FilterPreviewScreen extends Screen {

    private final Screen back;
    private final List<ItemStack> matches;
    private final int total;
    private final boolean truncated;

    private MatchList list;
    private MatchEntry hovered;

    public FilterPreviewScreen(List<ItemStack> matches, int total, boolean truncated, Screen back) {
        super(Component.literal("Accepted items"));
        this.matches = List.copyOf(matches);
        this.total = total;
        this.truncated = truncated;
        this.back = back;
    }

    @Override
    protected void init() {
        this.list = new MatchList(this.minecraft, this.width, this.height - 64, 32, 20, this.matches);
        this.addRenderableWidget(this.list);
        this.addRenderableWidget(Button.builder(Component.literal("Done"), pressed -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
        super.init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        String heading = total == 1 ? "1 accepted item" : total + " accepted items";
        if (truncated) {
            heading += " (first " + matches.size() + " shown)";
        }
        graphics.drawCenteredString(this.font, heading, this.width / 2, 16, 0xFFFFFF);
        if (this.hovered != null && !this.hovered.stack.isEmpty()) {
            graphics.renderTooltip(this.font, this.hovered.stack, mouseX, mouseY);
        }
        this.hovered = null;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.back);
        }
    }

    /**
     * One match: its icon and its name in the player's own language. A press opens the item's
     * recipes where a viewer is installed; without one it does nothing, like a row press with
     * no viewer.
     */
    private static class MatchEntry extends ObjectSelectionList.Entry<MatchEntry> {

        private final ItemStack stack;
        private final java.util.function.Consumer<MatchEntry> onHover;

        private MatchEntry(ItemStack stack, java.util.function.Consumer<MatchEntry> onHover) {
            this.stack = stack;
            this.onHover = onHover;
        }

        @Override
        public Component getNarration() {
            return this.stack.getHoverName();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                RecipeLookups.open(RecipeLookups.Target.of(this.stack));
                return true;
            }
            return false;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (hovering) {
                this.onHover.accept(this);
            }
            Minecraft minecraft = Minecraft.getInstance();
            graphics.renderItem(this.stack, left + 2, top + 2);
            graphics.drawString(minecraft.font, this.stack.getHoverName(), left + 24,
                    top + (height - 8) / 2, 0xFFFFFF, false);
        }
    }

    private class MatchList extends ObjectSelectionList<FilterPreviewScreen.MatchEntry> {

        private MatchList(Minecraft minecraft, int width, int height, int y0, int itemHeight,
                          List<ItemStack> matches) {
            super(minecraft, width, height, y0, itemHeight);
            for (ItemStack stack : matches) {
                this.addEntry(new MatchEntry(stack, entry -> FilterPreviewScreen.this.hovered = entry));
            }
        }
    }
}
