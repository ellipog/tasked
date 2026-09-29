package dev.ellipog.tasked.neoforge;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entry point.
 *
 * <p>Three lines, and the third is the one that differs from Fabric: NeoForge will not take a payload
 * registration during construction, so {@link #onRegisterPayloads} is registered as a listener and
 * does the work later. That deferral is the whole reason Armature's networking is a declaration rather
 * than a call.
 */
@Mod(Constants.MOD_ID)
public final class TaskedNeoForge {

    public TaskedNeoForge(IEventBus modEventBus) {
        // Declares the payloads. Must precede the install below, which records them for the event.
        Tasked.init();

        ArmatureNetwork.install(new NeoForgeNetworking());

        // The event is on the mod bus, and fires on both sides -- so one registration serves both
        // directions. See NeoForgeNetworking.onRegisterPayloads.
        modEventBus.addListener(RegisterPayloadHandlersEvent.class, NeoForgeNetworking::onRegisterPayloads);
    }
}
