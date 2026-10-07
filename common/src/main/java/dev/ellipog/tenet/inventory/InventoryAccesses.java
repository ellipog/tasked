package dev.ellipog.tenet.inventory;

/**
 * The installed inventory access, and the vanilla one when no loader has replaced it.
 *
 * <p>The same shape as Armature's network install: a loader hands its implementation over once at
 * startup, and everything else asks here. Uninstalled — a unit test, or a loader that has not reached
 * its entry point yet — the vanilla implementation answers, which is exactly right rather than a
 * placeholder: it is the semantics both loaders' inventories are built on.
 */
public final class InventoryAccesses {

    private static volatile InventoryAccess installed;

    private InventoryAccesses() {
    }

    /** Hands the loader's implementation over. Called once, from the loader's entry point. */
    public static void install(InventoryAccess access) {
        installed = access;
    }

    /** The implementation in force. */
    public static InventoryAccess current() {
        InventoryAccess access = installed;
        return access == null ? VanillaInventory.INSTANCE : access;
    }

    /** Forgets the install, for a test that wants the default back. */
    public static void reset() {
        installed = null;
    }
}
