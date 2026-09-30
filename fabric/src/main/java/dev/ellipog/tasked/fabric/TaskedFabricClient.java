package dev.ellipog.tasked.fabric;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureScreens;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.client.DevMode;
import dev.ellipog.tasked.client.QuestBookScreen;
import dev.ellipog.tasked.client.dev.DevScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric's client half.
 *
 * <p>Separate from {@link TaskedFabric} so a dedicated server never loads this class, and so never
 * tries to load {@code KeyMapping}, {@code Screen} or {@code ClientPlayNetworking}.
 *
 * <h2>Four things, and each has a reason for being here</h2>
 *
 * <ul>
 *   <li><b>The screen</b>, registered before the key is declared — or the key would open nothing.</li>
 *   <li><b>The key mapping</b>, declared through Armature so NeoForge's different timing is not this
 *       class's problem.</li>
 *   <li><b>The client tick</b>, which advances the tick counter cooldowns count down from, and polls
 *       the key.</li>
 *   <li><b>The disconnect hook</b>, which clears the cache. Without it, leaving one server and joining
 *       another shows the first server's questline until the new sync arrives — which looks exactly
 *       like a sync failure and is not one.</li>
 * </ul>
 */
public final class TaskedFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Declare and install defensively, so this works whether Fabric ran the common initialiser or
        // this one first. Both are idempotent: declaring twice is a no-op, and install keeps the first
        // backend. Without this, a client that ran its client initialiser first would register zero
        // receivers -- because the declarations list was still empty -- and every sync would vanish
        // with no error anywhere.
        Tasked.init();
        ArmatureNetwork.install(new FabricNetworking());

        // The screen first: declaring the key before the screen exists is a key that opens nothing.
        ArmatureScreens.register(Tasked.QUEST_BOOK_SCREEN, QuestBookScreen::new);

        ArmatureClient.registerKeyMapping(
                Tasked.QUEST_BOOK_SCREEN,
                InputConstants.KEY_B,
                "key.categories.tasked",
                () -> ArmatureClient.openScreen(Tasked.QUEST_BOOK_SCREEN));

        // And the developer screen, which is also where developer mode is turned on. Registered and keyed
        // unconditionally, because a screen that only exists once a mode is on is a screen nobody can use
        // to turn that mode on -- and the mode is off in a fresh config by definition.
        ArmatureScreens.register(Tasked.DEV_SCREEN, DevScreen::new);

        ArmatureClient.registerKeyMapping(
                Tasked.DEV_SCREEN,
                InputConstants.KEY_F9,
                "key.categories.tasked",
                () -> ArmatureClient.openScreen(Tasked.DEV_SCREEN));

        // The setting the screen above edits, read before anything can draw it: a key pressed in the
        // first second of a session must not find the mode off because the file had not been read yet.
        DevMode.loadFromConfig();

        // The client half of Fabric's two-part payload registration.
        FabricClientNetworking.registerClientReceivers();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientTicker.advance();
            ArmatureClient.tick();
        });

        // A payload can arrive for a world being left, and a cache holding it would be read by the
        // next world's screen. Cleared on disconnect rather than on connect, because the last sync of
        // a session is likely to arrive after any connect-time hook has already run.
        //
        // `forgetTransfers` bisects that, and it has to be here rather than inside the cache: the
        // cache knows about quests, and the half-received chunks belong to the networking. A player
        // who disconnects mid-tree would otherwise leave chunks in memory that no later message can
        // complete -- the next connection's transfer ids are fresh ones -- so they would sit there
        // until the cap in SyncWire.Reassembler evicted them.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientQuestCache.clear();
            TaskedNetworking.forgetTransfers();
        });

        Constants.LOG.info("Tasked: Fabric client ready");
    }
}
