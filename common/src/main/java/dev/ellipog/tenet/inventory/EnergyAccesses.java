package dev.ellipog.tenet.inventory;

import net.minecraft.server.level.ServerPlayer;

/**
 * The installed energy access, and silence when no loader has replaced it.
 *
 * <p>The same shape as {@link InventoryAccesses}: a loader hands its implementation over once at
 * startup, and everything else asks here. Uninstalled — a unit test, or a loader with no energy
 * library to read through — nothing is measured and nothing drains.
 */
public final class EnergyAccesses {

    private static volatile EnergyAccess installed;

    private EnergyAccesses() {
    }

    /** Hands the loader's implementation over. Called once, from the loader's entry point. */
    public static void install(EnergyAccess access) {
        installed = access;
    }

    /** The implementation in force. */
    public static EnergyAccess current() {
        EnergyAccess access = installed;
        return access == null ? Silent.INSTANCE : access;
    }

    /** Forgets the install, for a test that wants the default back. */
    public static void reset() {
        installed = null;
    }

    /** No energy library: every item holds nothing. */
    private enum Silent implements EnergyAccess {
        INSTANCE;

        @Override
        public int storedOf(ServerPlayer player, int maxPerItem) {
            return 0;
        }

        @Override
        public int drainFrom(ServerPlayer player, int amount, int maxPerItem) {
            return 0;
        }
    }
}
