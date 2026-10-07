package dev.ellipog.tasked.neoforge;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.client.ArmatureScreens;
import dev.ellipog.tasked.client.viewer.Integrations;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.client.CanvasSettings;
import dev.ellipog.tasked.client.ClientChapterReplica;
import dev.ellipog.tasked.client.ClientTableOpen;
import dev.ellipog.tasked.client.ClientTableReplica;
import dev.ellipog.tasked.client.ClientTableRoll;
import dev.ellipog.tasked.client.ClientEditReplies;
import dev.ellipog.tasked.client.ClientPartyCache;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.viewer.QuestViewerContent;
import dev.ellipog.tasked.net.TaskedNetworking;
import dev.ellipog.tasked.client.ClientTicker;
import dev.ellipog.tasked.client.ClientAppearance;
import dev.ellipog.tasked.client.ClientWorking;
import dev.ellipog.tasked.client.DevMode;
import dev.ellipog.tasked.client.ObservationWatcher;
import dev.ellipog.tasked.client.QuestBookScreen;
import dev.ellipog.tasked.client.QuestNotifier;

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

        // No developer screen and no F9: the tools are a panel in the book, reached from its header.
        DevMode.loadFromConfig();
        ClientAppearance.loadFromConfig();
        // Where the author was — the last table, the drawer's tab, the Assets section. Read here for the
        // same reason the two above are: a panel opened in the first second of a session must not open on
        // a default because the file had not been read yet.
        ClientWorking.loadFromConfig();
        // And the canvas's own thresholds: how much of it to draw, and from how far out. Read here for the
        // same reason as the rest — a canvas drawn in the first second must not be drawn at the mod's
        // defaults because the file had not been read. See CanvasSettings.
        CanvasSettings.loadFromConfig();

        // The recipe-viewer seam: the content goes in as soon as the client exists, and whichever
        // viewer is installed reads it when it registers. No viewer is named here -- the seam is this
        // mod's, and this only says what the content is.
        Integrations.install(new QuestViewerContent());

        // The game bus: ticking a running client is not a startup concern.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            ClientTicker.advance();
            // The crosshair's half of the engine: observation tasks are judged here because a server
            // never sees where a player looks. See ObservationWatcher.
            ObservationWatcher.tick(net.minecraft.client.Minecraft.getInstance());
            ArmatureClient.tick();
            // The viewer's half: the content's revision check, then the one chosen adapter's tick.
            Integrations.tick();
            // The notice half: the one detector of completions and claims, which speaks whether or not
            // the book is open. See QuestNotifier.
            QuestNotifier.tick();
        });

        // Both, for the same reason as on Fabric: the cache's contents and the half-received chunks
        // that feed it. See the Fabric side's comment for why the second half lives in the networking
        // rather than in the cache.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientQuestCache.clear();
            ClientChapterReplica.clear();
            ClientTableReplica.clear();
            ClientTableRoll.clear();
            ClientTableOpen.clear();
            // The party roster too: it is written by the roster message and nothing else empties it, so
            // without this the next world's panel opens on the last server's party -- every row a real
            // player name, which is what makes it convincing. The join-time push corrects it a moment
            // later, and "a moment later" is a frame of somebody else's party on screen.
            ClientPartyCache.clear();
            // And the edit answers in flight for a world being left: the next screen must not report them.
            ClientEditReplies.clear();
            // And what the canvas held, including its rebuild count: that one is cumulative, so a count
            // carried into the next world would read as a canvas that had been churning when nothing had
            // happened yet. See `CanvasStats`.
            dev.ellipog.tasked.client.dev.CanvasStats.clear();
            // The half-counted observations go with the tree they were counted against.
            ObservationWatcher.reset();
            // And the completion diff, or the next server's progress would be read against this one's
            // states. See the Fabric side's comment.
            QuestNotifier.reset();
            TaskedNetworking.forgetTransfers();
        });

        Constants.LOG.info("Tasked: NeoForge client ready");
    }
}
