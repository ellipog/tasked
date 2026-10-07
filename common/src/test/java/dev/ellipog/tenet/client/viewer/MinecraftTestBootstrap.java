package dev.ellipog.tenet.client.viewer;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Starts enough of vanilla for a test to construct an {@link net.minecraft.world.item.ItemStack}.
 *
 * <h2>Why this exists here</h2>
 *
 * <p>Most of Tenet's tests never touch a game class, and that is the property the suite is proud
 * of. This seam's records hold {@code ItemStack}s, though, and even {@code ItemStack.EMPTY} reads a
 * registry key while its class initialises — without the bootstrap every such test dies with
 * {@code Not bootstrapped (called from registry ... minecraft:game_event)} from inside a class
 * initialiser vanilla owns. The library's suite solves it the same way, and the two wrong turns are
 * worth repeating: it is {@code net.minecraft.server.Bootstrap} (the client one is not in the merged jar
 * {@code common} compiles against), and {@code tryDetectVersion()} must run first, because
 * {@code bootStrap()} reads the game version.
 *
 * <p>Costs about a second, once per JVM. Only the tests whose fixtures hold an item stack call it.
 */
public final class MinecraftTestBootstrap {

    private static boolean done;

    private MinecraftTestBootstrap() {
    }

    /** Idempotent, so every test class can call it without coordination. */
    public static synchronized void boot() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
