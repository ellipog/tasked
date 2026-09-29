package dev.ellipog.tasked.quest;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Starts enough of vanilla for a test to read its registries.
 *
 * <h2>Why {@code net.minecraft.server.Bootstrap}, not {@code net.minecraft.Bootstrap}</h2>
 *
 * <p>There are two Bootstrap classes and this cost three wrong attempts to find, so it is worth
 * writing down. The one nearly every example uses is {@code net.minecraft.Bootstrap}, and it is a
 * <b>client</b> class — absent from the server merged jar that {@code common} compiles against. The
 * compiler reports {@code cannot find symbol: class Bootstrap, location: package net.minecraft},
 * which reads like a missing dependency rather than a class that was never in this artefact.
 *
 * <p>The dedicated server has its own, at {@code net.minecraft.server.Bootstrap}, with the same
 * {@code bootStrap()} entry point. That is the one to call here.
 *
 * <p>The reason the distinction matters rather than being cosmetic: {@code bootStrap()} does two
 * things, and only the first is obvious. It sets the flag that {@code Registry} checks, and it
 * populates the static registries. Calling {@code BuiltInRegistries.bootStrap()} directly — which
 * looks equivalent — sets nothing, so the first registry that builds a {@code ResourceKey} throws
 * {@code IllegalArgumentException: Not bootstrapped (called from registry ... minecraft:game_event)}
 * from inside a class initialiser that vanilla owns. The flag has to be set first, and
 * {@code bootStrap()} is what sets it.
 *
 * <h2>Why tests need it</h2>
 *
 * <p>Anything that asks whether an item exists reads {@code BuiltInRegistries.ITEM}, and that
 * registry is empty until vanilla fills it. Without this, every item in every fixture is reported
 * missing — including {@code minecraft:oak_log} — and the item tests pass for entirely the wrong
 * reason. So this runs once before the class that checks items, and it is the difference between a
 * test that proves something and one that proves a registry was empty.
 *
 * <p>Costs about a second, once per JVM.
 */
public final class MinecraftTestBootstrap {

    private static boolean done;

    private MinecraftTestBootstrap() {
    }

    /**
     * Idempotent, so every test class can call it without coordination.
     *
     * <p>Both calls, in this order. {@code tryDetectVersion()} first because {@code bootStrap()}
     * reads the game version and throws {@code IllegalStateException: Game version not set} without
     * it; {@code bootStrap()} second because it is what sets the flag {@code Registry} checks.
     *
     * <p>Getting this wrong is instructive about how these failures read. Calling
     * {@code BuiltInRegistries.bootStrap()} alone gave {@code Not bootstrapped (called from registry
     * ... minecraft:game_event)} — an exception from inside a class initialiser vanilla owns, naming
     * a registry that has nothing to do with what is being tested. Calling
     * {@code server.Bootstrap.bootStrap()} without the version gave {@code Game version not set}.
     * Neither message mentions the missing call.
     */
    public static synchronized void boot() {
        if (done) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        done = true;
    }
}
