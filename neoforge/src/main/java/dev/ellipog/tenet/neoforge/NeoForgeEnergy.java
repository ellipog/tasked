package dev.ellipog.tenet.neoforge;

import dev.ellipog.tenet.inventory.EnergyAccess;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * NeoForge's stored energy: batteries, capacitors and charged tools through the energy-storage
 * item capability, over every slot the task loops read — armour included, where jetpacks live.
 *
 * <p>Each item contributes no more than {@code maxPerItem} when that is positive, in both
 * directions: the measurement caps and the drain caps in the same slot order, so what one counted
 * is what the other takes.
 */
public final class NeoForgeEnergy implements EnergyAccess {

    @Override
    public int storedOf(ServerPlayer player, int maxPerItem) {
        long found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null) {
                continue;
            }
            int stored = storage.getEnergyStored();
            found += maxPerItem > 0 ? Math.min(stored, maxPerItem) : stored;
        }
        return (int) Math.min(found, Integer.MAX_VALUE);
    }

    @Override
    public int drainFrom(ServerPlayer player, int amount, int maxPerItem) {
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            IEnergyStorage storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
            if (storage == null) {
                continue;
            }
            int capped = maxPerItem > 0
                    ? Math.min(storage.getEnergyStored(), maxPerItem)
                    : storage.getEnergyStored();
            remaining -= storage.extractEnergy(Math.min(remaining, capped), false);
        }
        if (remaining != amount) {
            inventory.setChanged();
        }
        return amount - remaining;
    }
}
