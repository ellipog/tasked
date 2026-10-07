package dev.ellipog.tenet.client;

import dev.ellipog.tenet.net.LocaleRequestPayload;
import dev.ellipog.tenet.quest.QuestLanguages;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * The pack's translations, as this client last received them, and the one place text is resolved.
 *
 * <h2>What this is for</h2>
 *
 * <p>A pack can ship {@code config/tenet/quests/lang/<locale>.json} and the server sends the player
 * the one locale they asked for. The tree itself carries the canonical strings, so this is an
 * <b>overlay</b>: it answers for the keys it holds and changes nothing else.
 *
 * <h2>Why the overlay is not installed into {@code Language}</h2>
 *
 * <p>The obvious implementation is to wrap {@link Language} and inject the wrapper, and it is the
 * wrong one here. {@code Language.inject} replaces a <b>global</b> static for the whole client: every
 * other mod's text and vanilla's own would be resolved through a chain this class put there, and a
 * resource reload — which injects a fresh instance of its own — would silently drop the overlay and
 * leave a stale wrapper behind if this class then wrapped the wrapper. A chain that grows a link per
 * reload is a leak, and the guard against it is a rule somebody has to keep.
 *
 * <p>So there is no injection and no wrapper: this class holds its own map, asks it first, and hands
 * everything else to {@link Language} exactly as it found it. Nesting is not guarded against — it
 * cannot happen, because nothing is wrapped.
 *
 * <h2>The order, and what each step is for</h2>
 *
 * <ol>
 *   <li><b>The server's overlay.</b> The pack is the authority on its own book: a translation that
 *       arrived from the server beats anything the client happens to have on disk.</li>
 *   <li><b>The client's own {@link Language}.</b> A pack may also ship the same keys in a resource
 *       pack, and a player's client may translate keys Tenet never heard of. This is the step that
 *       keeps every existing {@code translatable} path working.</li>
 *   <li><b>The fallback the wire carried.</b> The tree's canonical string. This is what a player
 *       reads when nothing translated the key, and it is why a missing translation never shows a raw
 *       key — the failure mode this whole arrangement exists to avoid.</li>
 * </ol>
 *
 * <h2>Why a change here does not rebuild the tree</h2>
 *
 * <p>Text is resolved when it is <b>drawn</b>, not when the tree is parsed. The records hold the key
 * and the fallback as they arrived; every accessor reads this class at the moment it is called. So a
 * locale that arrives a moment after the tree changes what the next frame draws and nothing else —
 * and a player who switches language mid-session needs no reconnect and no re-send of the questline.
 * {@link ClientQuestCache#textRevision()} is what tells the screens that hold rendered rows to
 * rebuild them.
 */
public final class ClientLocale {

    private ClientLocale() {
    }

    /**
     * How long before a request that was not answered is asked again.
     *
     * <p>Five seconds, and it is a retry rather than a poll: the request goes out once when the
     * language changes, and only a player whose language still disagrees with what the server last
     * sent is asked for again. Without a retry a dropped packet would leave the book in the previous
     * language until the next reconnect; without the interval, one unanswered request would be one
     * packet per tick.
     */
    private static final int RETRY_TICKS = 100;

    /** Key to text, as the server last sent it. Replaced whole, never merged into. */
    private static volatile Map<String, String> entries = Map.of();

    /** The locale this client asked for — what {@link #pollLocale} compares against. */
    private static volatile String asked = "";

    /** The locale the server actually used, which may be a regional relative of {@link #asked}. */
    private static volatile String served = "";

    /** The locale a request is outstanding for, so a change asks once rather than every tick. */
    private static volatile String requested = "";

    private static int ticksUntilRetry;

    /** How many requests have gone out, for the diagnostic accessor below. */
    private static volatile int requestsSent;

    /**
     * Takes the server's overlay for one locale.
     *
     * <h2>Why this replaces rather than merges</h2>
     *
     * <p>Because the server has already merged everything that should be merged — the canonical
     * locale sits under the served one there, where both are known — and a merge here would keep a
     * <i>previous</i> locale's text alive underneath a new one. A player who switches from Spanish
     * to German and back would otherwise accumulate the union of both, and a key deleted from the
     * pack's German file would go on answering from the Spanish one.
     *
     * @param askedLocale  what this client asked for, or what the server read from its own view of
     *                     the client; this is the value {@link #pollLocale} settles on
     * @param servedLocale the locale actually used, or empty when the pack has none for it
     * @param text         the entries, key to text
     */
    public static void accept(String askedLocale, String servedLocale, Map<String, String> text) {
        String nextAsked = QuestLanguages.normalise(askedLocale);
        Map<String, String> next = text == null ? Map.of() : Map.copyOf(text);
        // Only a real change moves the revision: a re-send of the same locale, which a reload
        // produces, must not make every screen holding rendered rows rebuild them for nothing.
        boolean moved = !entries.equals(next);
        entries = next;
        asked = nextAsked;
        served = QuestLanguages.normalise(servedLocale);
        requested = "";
        ticksUntilRetry = 0;
        if (moved) {
            ClientQuestCache.textChanged();
        }
    }

    /** Forgets the overlay: another server's translations are not this one's to draw. */
    public static void clear() {
        boolean had = !entries.isEmpty() || !asked.isEmpty();
        entries = Map.of();
        asked = "";
        served = "";
        requested = "";
        ticksUntilRetry = 0;
        requestsSent = 0;
        if (had) {
            ClientQuestCache.textChanged();
        }
    }

    /** The locale this client last settled on. Empty before anything arrives. */
    public static String asked() {
        return asked;
    }

    /** The locale the server used, which is empty when the pack had none. Diagnostics. */
    public static String served() {
        return served;
    }

    /**
     * The locale a request is outstanding for, or empty when none is.
     *
     * <p>A diagnostic, and the one that makes "this cannot loop" checkable: the state that decides
     * whether another packet goes out is not otherwise visible from outside, and a claim about a
     * request loop that can only be read in the source is a claim nobody re-tests.
     */
    public static String pendingRequest() {
        return requested;
    }

    /** How many requests this client has sent since it last settled. Diagnostics. */
    public static int requestsSent() {
        return requestsSent;
    }

    /**
     * The text for a key: the overlay, then the client's language, then {@code fallback}.
     *
     * @param fallback what to draw when neither knows the key — the tree's own string
     */
    public static String text(String key, String fallback) {
        String found = find(key);
        return found != null ? found : (fallback == null ? "" : fallback);
    }

    /**
     * The same, for a key that is a sentence with a subject formatted into it.
     *
     * <h2>Why this is not just the {@link Component} call</h2>
     *
     * <p>Because the overlay is not in {@link Language}, so {@code Component} cannot see it — and a
     * translated sentence with a subject in it ("Grant the stage %s") has to have the subject put
     * back. When the overlay <b>does not</b> hold the key, this is the existing
     * {@code Component.translatableWithFallback} call unchanged, so every pack that ships no
     * translations renders exactly as it did before this class existed.
     *
     * <p>When it does hold the key, the pattern is formatted here. A stray {@code %} in a translation
     * is a real possibility — a translator writing "100% of the logs" — so a pattern the formatter
     * refuses is drawn <b>verbatim</b>: a sentence with a {@code %} in it, not an exception thrown
     * from inside a frame. A literal percent is written {@code %%}, which is the rule Minecraft's own
     * translations already follow.
     */
    public static String text(String key, String fallback, Object... args) {
        String over = entries.get(key);
        if (over == null) {
            return Component.translatableWithFallback(key, fallback, args).getString();
        }
        return args == null || args.length == 0 ? over : format(over, args);
    }

    /**
     * The text for a key, or null when nothing knows it.
     *
     * <p>Null rather than a fallback because a caller with <b>two</b> candidate keys needs to tell
     * "this one answered" from "this one did not" — the author's own key is tried before the pack's
     * conventional one, and only the first can be reported as absent.
     */
    public static String find(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String mine = entries.get(key);
        if (mine != null) {
            return mine;
        }
        Language language = Language.getInstance();
        // `has` first rather than `getOrDefault(key, null)`: the fallback parameter is not documented
        // to accept null, and asking twice is cheaper than depending on that.
        return language.has(key) ? language.getOrDefault(key, "") : null;
    }

    /**
     * Tells the server the client's language when it stops matching what the server last sent.
     *
     * <p>Called once per client tick with the language the player has selected. The comparison is
     * against {@link #asked} rather than against the pack's files, because the client cannot know
     * which locales a pack ships and does not need to: the server answers with an empty overlay
     * labelled with the locale it was asked for, which settles the comparison either way. That is
     * also what stops a player whose locale the pack has no file for from asking forever.
     *
     * <p>Join needs no request: the server reads the client's language from the login handshake and
     * sends the matching overlay with the tree, so the two already agree by the time this runs.
     */
    public static void pollLocale(String current) {
        String now = QuestLanguages.normalise(current);
        if (now.isEmpty() || now.equals(asked)) {
            requested = "";
            ticksUntilRetry = 0;
            return;
        }
        if (now.equals(requested)) {
            // `> 1` rather than `> 0`, so the window is the hundred ticks it says it is: the counter
            // is set to a hundred by the send below, and the poll that finds it at one is the
            // hundredth tick after that send -- which is the one that asks again. With `> 0` the gap
            // was a hundred and one, and the difference is exactly the kind of off-by-one that a
            // comment would have claimed was right.
            if (ticksUntilRetry > 1) {
                ticksUntilRetry--;
                return;
            }
        }
        requested = now;
        ticksUntilRetry = RETRY_TICKS;
        requestsSent++;
        ArmatureNetwork.sendToServer(new LocaleRequestPayload(now));
    }

    /** A pattern with its subject, or the pattern itself when the formatter refuses it. */
    private static String format(String pattern, Object[] args) {
        try {
            return String.format(java.util.Locale.ROOT, pattern, args);
        }
        catch (java.util.IllegalFormatException e) {
            return pattern;
        }
    }
}
