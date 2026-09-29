package dev.ellipog.tasked.neoforge;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureScreens;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.client.QuestBookScreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's client half.
 *
 * <p>{@code dist = Dist.CLIENT} keeps a dedicated server from loading this class, and with it
 * {@code KeyMapping}, {@code Screen} and the client event classes.
 *
 * <h2>Nothing to register for payloads here</h2>
 *
 * <p>Unlike Fabric, NeoForge's {@code RegisterPayloadHandlersEvent} fires on the client too — so
 * {@link NeoForgeNetworking#onRegisterPayloads} already registered the client-bound handlers, and
 * doing it again here would be a duplicate registration rather than a missing one. The asymmetry with
 * Fabric is worth knowing: Fabric needs a second registration call on the client, NeoForge does not.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public final class TaskedNeoForgeClient {

    public TaskedNeoForgeClient(IEventBus modEventBus) {
        // Declared and installed defensively -- see TaskedFabricClient for why. On NeoForge the
        // common @Mod constructor is all but certain to have run first, but "all but certain" is not
        // a load order, and both calls are idempotent.
        Tasked.init();
        ArmatureNetwork.install(new NeoForgeNetworking());

        ArmatureScreens.register(Tasked.QUEST_BOOK_SCREEN, QuestBookScreen::new);

        ArmatureClient.registerKeyMapping(
                Tasked.QUEST_BOOK_SCREEN,
                InputConstants.KEY_B,
                "key.categories.tasked",
                () -> ArmatureClient.openScreen(Tasked.QUEST_BOOK_SCREEN));

        // The game bus: ticking a running client is not a startup concern.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientTicker.advance();
            ArmatureClient.tick();
        });

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) ->
                ClientQuestCache.clear());

        Constants.LOG.info("Tasked: NeoForge client ready");
    }
}
