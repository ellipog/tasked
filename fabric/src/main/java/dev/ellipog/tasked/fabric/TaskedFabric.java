package dev.ellipog.tasked.fabric;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.inventory.InventoryAccesses;
import dev.ellipog.tasked.inventory.VanillaInventory;
import dev.ellipog.tasked.net.TaskedNetworking;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point.
 *
 * <p>Four lines, and the order of them is the only interesting part:
 *
 * <ol>
 *   <li>{@code Tasked.init()} declares the payloads. It has to come first — installation reads the
 *       declarations and registers them, so anything declared afterwards is never seen.</li>
 *   <li>{@code ArmatureNetwork.install(...)} hands the loader over, which registers every declared
 *       payload's <b>format</b> on both sides.</li>
 *   <li>{@code FabricNetworking.registerReceivers()} registers the <b>server-side receivers</b>. Done
 *       here rather than in the client initialiser because a client with an integrated server is a
 *       server, and it receives the same packets a dedicated one would.</li>
 * </ol>
 *
 * <p>Runs on client and server both. Nothing here may touch a client class —
 * {@link TaskedFabricClient} exists for that, and it is a separate entry point precisely so the
 * server never loads it.
 */
public final class TaskedFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Declares the payloads. Must precede the install below.
        Tasked.init();

        ArmatureNetwork.install(new FabricNetworking());

        // Fabric has no item-handler capability, so the vanilla semantics are the whole truth and the
        // common implementation is installed explicitly rather than left to the default -- the two
        // loaders then read the same, and the seam is visible at both entry points.
        InventoryAccesses.install(VanillaInventory.INSTANCE);

        // The server half of Fabric's two-part registration. The client half is in TaskedFabricClient.
        TaskedFabric.registerServerReceivers();
    }

    /**
     * A separate method so the client initialiser can reason about it — and so that a reader can see,
     * without hunting, that server receivers are registered from the common initialiser on purpose.
     */
    static void registerServerReceivers() {
        FabricNetworking.registerReceivers();
        dev.ellipog.tasked.Constants.LOG.info("Tasked: Fabric networking ready ({} payload(s))",
                TaskedNetworking.declaredCount());
    }
}
