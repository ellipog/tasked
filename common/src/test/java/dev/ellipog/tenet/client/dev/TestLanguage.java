package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mod's own language file, installed so a test draws the words a player reads.
 *
 * <h2>Why a test installs a language at all</h2>
 *
 * <p>The editor's labels are <b>keys</b> in the layout records now, and the draw sites resolve them
 * through {@code Component.translatable} — which, with no language loaded, hands back the key itself.
 * A test that asserts what was drawn would therefore assert a key, and a key does not fit the band it
 * is drawn in: "tenet.dev.quest.hide_details_until_startable" is truncated to "tenet.dev.quest.hide",
 * so the assertion would be about truncation rather than about the label.
 *
 * <p>Installing the real file fixes both at once: the test asserts the sentence a player sees, in the
 * one place the sentence lives — the same argument {@code .utils/preview.py} makes when it reads this
 * file rather than keeping a table of its own. What the records hold is still asserted where it lives:
 * the layout tests read the keys out of the rows, and {@code LangSweepTest} holds every key to its
 * definition in both directions.
 *
 * <p>Process-wide, like the thing it installs: the first test that draws a label installs it and every
 * later test sees the same words. That is deliberate rather than tolerated — two tests drawing the
 * same panel must not disagree about what it says.
 *
 * <h2>An overlay, not a replacement</h2>
 *
 * <p>It keeps whatever language the test JVM already had underneath and answers only for the mod's own
 * keys. That is not politeness: vanilla keys resolve in this runtime — an item's name reads "Diamond"
 * rather than "item.minecraft.diamond" — and a language installed as a <i>replacement</i> took that
 * away, which broke five tests about item names in the same run. The mod's file sits on top; everything
 * else falls through to what was there.
 */
public final class TestLanguage implements BeforeAllCallback {

    /** One level in from the common module, which is the test working directory. */
    private static final Path LANG =
            Path.of("src", "main", "resources", "assets", "tenet", "lang", "en_us.json");

    private static boolean installed;

    /** Public because the service loader instantiates it: an extension is a service, not a utility. */
    public TestLanguage() {
    }

    /**
     * Installed for <b>every</b> test class, by autodetection: see this module's
     * {@code junit-platform.properties} and the service file beside it.
     *
     * <p>Autodetection rather than a {@code @BeforeAll} in each class that needs it, and that is the
     * point: the language is process-wide, so a class that asserted resolved words without installing it
     * would pass or fail depending on which class the runner happened to visit first. One hook that
     * cannot be forgotten is worth six that can.
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        install();
    }

    /** Reads the mod's language file and installs it over whatever is in force. Idempotent. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Language.inject(new Overlay(Language.getInstance(), read()));
        }
        catch (IOException | RuntimeException e) {
            // Loud rather than silent: a test that meant to assert the words cannot pass without them,
            // and failing here names the reason instead of leaving a key in an assertion's diff.
            throw new IllegalStateException("the test language could not be read from " + LANG, e);
        }
    }

    private static Map<String, String> read() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8))
                .getAsJsonObject();
        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getAsString());
        }
        return entries;
    }

    /** The mod's entries, with everything else asked of the language that was already installed. */
    private static final class Overlay extends Language {

        private final Language underneath;
        private final Map<String, String> entries;

        Overlay(Language underneath, Map<String, String> entries) {
            this.underneath = underneath;
            this.entries = entries;
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            String mine = entries.get(key);
            return mine != null ? mine : underneath.getOrDefault(key, fallback);
        }

        @Override
        public boolean has(String key) {
            return entries.containsKey(key) || underneath.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return underneath.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            // Nothing in these tests reads right-to-left, and the callers that ask for a visual order
            // only want the string back: one style, one run.
            return FormattedCharSequence.forward(text.getString(), Style.EMPTY);
        }
    }
}
