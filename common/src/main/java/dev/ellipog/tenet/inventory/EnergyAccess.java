package dev.ellipog.tenet.inventory;

import net.minecraft.server.level.ServerPlayer;

/**
 * The energy stored in carried items — batteries, capacitors, charged tools — in Forge Energy
 * units, whatever the loader calls them.
 *
 * <p>Read through the loader's own machinery — NeoForge's energy-storage capability — because
 * vanilla has no energy API. Fabric has none either without a third-party energy library, so
 * there a task of this type reads zero until such an integration ships; see the task for the
 * standing follow-up.
 *
 * @see EnergyAccesses
 */
public interface EnergyAccess {

    /**
     * Forge Energy units stored across carried items, each item contributing no more than
     * {@code maxPerItem} when that is positive.
     *
     * <p>The per-item cap is the task's {@code maxInput}: zero or negative means unlimited.
     * Pure read: measures, changes nothing.
     */
    int storedOf(ServerPlayer player, int maxPerItem);

    /**
     * Drains up to {@code amount} units from carried items, no item giving more than
     * {@code maxPerItem} when that is positive, answering how much was actually drained.
     */
    int drainFrom(ServerPlayer player, int amount, int maxPerItem);
}
