package dev.ellipog.tenet.client.dev;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

/**
 * One editor label, resolved: a key if it names one, the string itself otherwise.
 *
 * <h2>Why a tolerant resolver rather than a key everywhere</h2>
 *
 * <p>The editor's layout records hold <b>keys</b> now — {@code "tenet.dev.tools.snap"} rather than
 * "Snap" — and the draw sites resolve them here. The tolerance is not a convenience: the same call
 * site draws a row label, a chapter's own title, a quest's id and a player's name, and only the first of
 * those is a key. A resolver that insisted on a key would need every caller to know which kind of string
 * it holds, which is the split the layout records exist to avoid.
 *
 * <h2>The marker in front</h2>
 *
 * <p>A section heading carries its fold marker in the record — {@code "\u25bc tenet.dev.tools.palette"} —
 * because the fold state is what the layout is handed and the marker is part of the drawn string's width.
 * So a key is resolved <i>where it starts</i>, and anything before it (the marker and its space) is kept.
 * That is the second shape {@code LangSweepTest}'s reverse scan counts as referencing a key, and the note
 * there says so.
 *
 * <p>The idiom is {@code QuestBookScreen.partyText}'s, promoted: the party panel needed exactly this
 * tolerance for the same reason, and two copies of a rule is how they drift.
 */
public final class Labels {

    /** The prefix every key in this mod carries. A string without it is not a key. */
    private static final String PREFIX = "tenet.";

    private Labels() {
    }

    /**
     * The label to draw: the key resolved through the language, or the string unchanged.
     *
     * <p>A missing language entry renders the key itself rather than throwing — the same behaviour
     * {@code Component.translatable} has in vanilla, and what lets a game-free test assert the key.
     *
     * <p>With values, though, vanilla's missing-entry path <i>drops</i> them, and one of the properties
     * the tests assert is exactly that a value survives into the line — "the server's own reason has to
     * survive". So when there is no entry the values are kept visible after the key instead of being
     * lost: a test, a log line and a language file that lost the sentence all still name what was said.
     * In game the entry exists and {@code translatable} substitutes them, which is the normal path.
     *
     * @param args values for the key's {@code %s} placeholders; ignored for a plain string
     */
    public static String of(String label, Object... args) {
        if (label == null) {
            return "";
        }
        int at = label.indexOf(PREFIX);
        if (at < 0) {
            return label;
        }
        // Everything before the key is the marker (empty for a bare key), and it stays.
        String marker = label.substring(0, at);
        String key = label.substring(at);
        if (args.length > 0 && Language.getInstance().getOrDefault(key).equals(key)) {
            return marker + key + " " + Arrays.toString(args);
        }
        return marker + Component.translatable(key, args).getString();
    }
}
