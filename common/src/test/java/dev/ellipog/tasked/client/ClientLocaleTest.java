package dev.ellipog.tasked.client;

import dev.ellipog.tasked.client.viewer.MinecraftTestBootstrap;
import dev.ellipog.tasked.quest.QuestLanguages;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's half of the locale: what it resolves, and when it asks the server again.
 *
 * <h2>Why the polling is tested at all</h2>
 *
 * <p>Because it is the one part of this feature that can fail <i>loudly and repeatedly</i>. A client
 * that asks for a locale it will never be sent asks once per tick, forever — a packet storm from every
 * player whose language the pack does not translate, which is most players on most packs. The state
 * that prevents it is private, so this class asserts it through the two diagnostics rather than
 * reading the source and hoping.
 *
 * <p>{@code ArmatureNetwork.sendToServer} does nothing at all when no loader has installed its
 * networking, which is the state of a test JVM — so these run without a connection and without a
 * backend, and what is asserted is the decision rather than the packet.
 */
@DisplayName("the client's locale")
class ClientLocaleTest {

    @BeforeAll
    static void bootstrap() {
        // This class looks like it needs nothing from the game and it does, and the failure it caused
        // is worth writing down because it is the second time this trap has bitten this suite.
        //
        // `ClientLocale.clear()` and `accept()` bump `ClientQuestCache.textRevision()`, which
        // initialises `ClientQuestCache` — and that class's initialiser resolves item stacks against
        // `BuiltInRegistries.ITEM`. Java makes a failed class initialisation permanent, so without
        // this the registry is poisoned for the whole JVM and **every other class that needs it**
        // fails with `NoClassDefFoundError`, in classes that have nothing to do with locales. 249 of
        // them, in this case, reported as unrelated failures.
        //
        // The rule `SyncWiringTest` states is the one that applies: a test class that can reach a
        // registry bootstraps, whether or not it looks like a registry test.
        MinecraftTestBootstrap.boot();
    }

    @AfterEach
    void clear() {
        ClientLocale.clear();
    }

    // ------------------------------------------------------------------
    // Resolving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the overlay answers first, the fallback last, and nothing in between is invented")
    void resolutionOrder() {
        ClientLocale.accept("es_es", "es_es", Map.of("quest.a.title", "Golpea un arbol"));

        assertEquals("Golpea un arbol", ClientLocale.text("quest.a.title", "Punch a Tree"));
        // A key nobody knows reads as the fallback the wire carried, which is what stops a missing
        // translation from drawing a raw key on a node.
        assertEquals("Punch a Tree", ClientLocale.text("quest.b.title", "Punch a Tree"));
        // And an empty fallback with an unknown key is empty rather than null: every caller draws it.
        assertEquals("", ClientLocale.text("quest.b.title", ""));
    }

    @Test
    @DisplayName("an empty key resolves to nothing rather than to a lookup")
    void anEmptyKeyIsNotAKey() {
        // `find` is the two-candidate form the records use: the author's key first, then the pack's
        // conventional one, and null is how the first reports "I did not answer".
        assertNull(ClientLocale.find(""));
        assertNull(ClientLocale.find(null));
        assertEquals("", ClientLocale.text("", ""));
    }

    @Test
    @DisplayName("a locale with a stray percent renders the sentence rather than throwing")
    void aStrayPercentIsNotACrash() {
        // A translator writing "100% of the logs" is a real possibility, and this is the road that
        // formats rather than the Component path. `%%` is the rule Minecraft's own translations follow,
        // and a pattern the formatter refuses is drawn verbatim -- a sentence with a `%` in it, not an
        // exception thrown from inside a frame.
        ClientLocale.accept("es_es", "es_es", Map.of("tasked.test.give", "Da 100% de los troncos"));

        assertEquals("Da 100% de los troncos", ClientLocale.text("tasked.test.give", "Give logs"));
        // And a well-formed pattern still gets its subject.
        ClientLocale.accept("es_es", "es_es", Map.of("tasked.test.give", "Da %s troncos"));
        assertEquals("Da ocho troncos", ClientLocale.text("tasked.test.give", "Give logs", "ocho"));
    }

    @Test
    @DisplayName("an overlay that holds nothing changes nothing")
    void anEmptyOverlayIsInert() {
        ClientLocale.accept("de_de", "", Map.of());

        assertEquals("Punch a Tree", ClientLocale.text("quest.a.title", "Punch a Tree"));
        assertEquals("", ClientLocale.served());
        assertEquals("de_de", ClientLocale.asked());
    }

    // ------------------------------------------------------------------
    // Asking again
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a language the client already settled on asks for nothing")
    void aSettledLanguageIsSilent() {
        // The state after a join: the server read the language from the login handshake and sent the
        // matching overlay, so the two agree and this must be a no-op for the rest of the session.
        ClientLocale.accept("es_es", "es_es", Map.of("quest.a.title", "Golpea un arbol"));

        for (int tick = 0; tick < 500; tick++) {
            ClientLocale.pollLocale("es_es");
        }

        assertEquals(0, ClientLocale.requestsSent(), "a settled client must not ask for anything");
        assertEquals("", ClientLocale.pendingRequest());
    }

    @Test
    @DisplayName("a changed language asks once, and not again while the answer is in flight")
    void aChangeAsksOnce() {
        // The storm this exists to prevent: the player picks a new language and the server has not
        // answered yet. Asking every tick would be one packet per tick per player.
        ClientLocale.accept("en_us", "en_us", Map.of());

        ClientLocale.pollLocale("es_mx");

        assertEquals(1, ClientLocale.requestsSent());
        assertEquals("es_mx", ClientLocale.pendingRequest());

        for (int tick = 0; tick < 99; tick++) {
            ClientLocale.pollLocale("es_mx");
        }

        assertEquals(1, ClientLocale.requestsSent(),
                "ninety-nine more ticks inside the retry window must not send anything");
    }

    @Test
    @DisplayName("an unanswered request is retried, because a dropped packet must not strand a player")
    void anUnansweredRequestIsRetried() {
        // The other half of the same decision: silence forever is the failure a one-shot request has,
        // and a player whose packet was dropped would read the previous language until they
        // reconnected.
        ClientLocale.accept("en_us", "en_us", Map.of());
        ClientLocale.pollLocale("es_mx");
        assertEquals(1, ClientLocale.requestsSent());

        // The window is a hundred ticks, so the hundredth poll is the one that goes out again.
        for (int tick = 0; tick < 100; tick++) {
            ClientLocale.pollLocale("es_mx");
        }

        assertEquals(2, ClientLocale.requestsSent(), "the retry is what makes a lost packet recoverable");
    }

    @Test
    @DisplayName("an answer settles the client, whatever locale it names")
    void anAnswerStopsTheAsking() {
        // The loop the regional fallback would have introduced, from the client's side: an `es_mx`
        // player served the pack's `es_es` file. The client settles on the language it *asked for*, so
        // the answer ends the asking even though the served locale differs.
        ClientLocale.accept("en_us", "en_us", Map.of());
        ClientLocale.pollLocale("es_mx");
        assertEquals("es_mx", ClientLocale.pendingRequest());

        ClientLocale.accept("es_mx", "es_es", Map.of("quest.a.title", "Golpea un arbol"));

        assertEquals("", ClientLocale.pendingRequest());
        for (int tick = 0; tick < 500; tick++) {
            ClientLocale.pollLocale("es_mx");
        }

        assertEquals(1, ClientLocale.requestsSent(),
                "the answer ended it, so the only request is the one before it");
    }

    @Test
    @DisplayName("a blank or unreadable language is not asked for")
    void aBlankLanguageIsNotRequested() {
        // The client's language comes from its own options, so it is a locale id or it is nothing. An
        // empty one is "no answer" rather than a locale to look up, and asking for it would be a
        // request the server can only answer with an empty overlay.
        ClientLocale.accept("en_us", "en_us", Map.of());

        ClientLocale.pollLocale("");
        ClientLocale.pollLocale(null);
        ClientLocale.pollLocale("not a locale");

        assertEquals(0, ClientLocale.requestsSent());
        assertTrue(QuestLanguages.normalise("not a locale").isEmpty(),
                "and the normalisation is what decides that, in one place");
    }

    @Test
    @DisplayName("a disconnect forgets the language, the overlay and the asking")
    void clearForgetsEverything() {
        // Another server's translations are not this one's to draw, and a request outstanding for a
        // server this client has left must not be answered into the next session.
        ClientLocale.accept("es_es", "es_es", Map.of("quest.a.title", "Golpea un arbol"));
        ClientLocale.pollLocale("de_de");
        assertFalse(ClientLocale.text("quest.a.title", "Punch a Tree").equals("Punch a Tree"));

        ClientLocale.clear();

        assertEquals("", ClientLocale.asked());
        assertEquals("", ClientLocale.served());
        assertEquals("", ClientLocale.pendingRequest());
        assertEquals(0, ClientLocale.requestsSent());
        assertEquals("Punch a Tree", ClientLocale.text("quest.a.title", "Punch a Tree"),
                "the previous server's text must not outlive the connection");
    }
}
