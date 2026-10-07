package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.JsonLocation;
import dev.ellipog.armature.api.data.Problems;

import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The pack's own translations: {@code config/tenet/quests/lang/<locale>.json}, one file per locale.
 *
 * <h2>Why the text lives beside the book rather than in it</h2>
 *
 * <p>FTB Quests stores <b>all</b> quest text in {@code lang/<locale>.snbt} and none of it in the
 * chapter files, and ATM10 ships fifteen locales. A Tenet tree that could only hold one language
 * would force every such pack to be flattened on import, so the translations need a home of their
 * own — and it must be a home the tree does not have to be rebuilt around.
 *
 * <p>So this is deliberately <b>not</b> part of {@link QuestIndex}. A locale file is not a chapter,
 * not a quest and not a reward table: nothing in it has a position on a canvas or a state to track,
 * and the tree's shape is identical whichever language it is read in. The book loads without it, the
 * sync carries the tree's canonical strings as the fallback, and a locale is an overlay applied on
 * top of what the tree already says.
 *
 * <h2>What a file is</h2>
 *
 * <p>A flat object of key to text, exactly the shape a resource pack's language file has:
 *
 * <pre>{@code { "quest.punch_a_tree.title": "Találd meg a fát" }}</pre>
 *
 * <p>The file's own name is the locale id — {@code es_es.json} is {@code es_es} — because there is
 * no second place to declare one and a name that no client can ask for is simply never looked up.
 * A name is normalised to lower case with {@code -} read as {@code _}, so {@code en-US.json} is
 * {@code en_us}.
 *
 * <h2>Loading never throws, and a bad file never stops the book</h2>
 *
 * <p>A file that does not parse, or a value that is not a string, is reported against its own line
 * and the file is refused. The rest load, and the questline is unaffected: an author with a typo in
 * a translation should get a working book, the file named, and the offending key pointed at — not a
 * server that refuses to start or a book that will not open.
 */
public final class QuestLanguages {

    /**
     * What a locale id may be, after normalisation.
     *
     * <p>An allowlist rather than a path check, and the reason is the direction the other one comes
     * from: {@link #servedLocale} is handed a string a <b>client</b> chose, and the only safe thing
     * to do with it is to decide whether it is a locale id at all and then use it as a map key. A
     * value that is not one of these characters is not a locale, so it is not looked up — and no
     * client string is ever resolved against the filesystem.
     */
    private static final Pattern LOCALE_ID = Pattern.compile("[a-z0-9_]{1,32}");

    /** The suffix a locale file carries, stripped to give the id. */
    private static final String SUFFIX = ".json";

    /** No folder, or an empty one: the state every pack without translations is in. */
    public static final QuestLanguages EMPTY =
            new QuestLanguages(Map.of(), Set.of());

    /** Locale id to that locale's entries, name-sorted so a resolution cannot depend on read order. */
    private final Map<String, Map<String, String>> bundles;

    /** The locale ids whose file is there and was refused, name-sorted. */
    private final Set<String> refused;

    private QuestLanguages(Map<String, Map<String, String>> bundles, Set<String> refused) {
        this.bundles = bundles;
        this.refused = refused;
    }
    /**
     * Reads {@code lang/*.json} from a quest root, reporting into the caller's problem list.
     *
     * <p>The problems go into the list that is passed in rather than into one of this class's own,
     * because that is the list the reload reports to the log <i>and</i> to every author in game. A
     * mistyped translation is exactly the kind of thing an author should be told about without
     * going to look for it.
     */
    public static QuestLanguages load(Path questRoot, Problems problems) {
        List<Path> files = QuestFiles.localeFiles(questRoot);
        if (files.isEmpty()) {
            return EMPTY;
        }

        Map<String, Map<String, String>> bundles = new TreeMap<>();
        Set<String> refused = new TreeSet<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String id = normalise(name.substring(0, name.length() - SUFFIX.length()));
            if (id.isEmpty()) {
                // Reported against the file's first line, because there is no key to point at: what
                // is wrong is the name itself.
                problems.add(name, new JsonLocation(1, 1, "$"), DataProblem.Severity.ERROR,
                        "the file name is not a locale id - expected something like \"es_es.json\","
                                + " lower case with letters, digits and underscores");
                refused.add(name);
                continue;
            }

            Optional<JsonDocument> parsed = QuestFiles.parseFile(file, name, problems);
            if (parsed.isEmpty()) {
                refused.add(id);
                continue;
            }
            JsonDocument document = parsed.get();
            JsonElement root = document.root();
            if (!root.isJsonObject()) {
                problems.error(document, "$", "expected an object of key to text, found "
                        + Checks.kindOf(root));
                refused.add(id);
                continue;
            }

            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> each : root.getAsJsonObject().entrySet()) {
                JsonElement value = each.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    // `"$.key"` rather than a path into the object: a locale file is flat, and the
                    // key is the member name, so this is the recorded position exactly.
                    problems.error(document, "$." + each.getKey(),
                            "expected the text as a string, found " + Checks.kindOf(value));
                    continue;
                }
                entries.put(each.getKey(), value.getAsString());
            }

            // A file with one bad value is refused whole rather than half-applied. Half a locale is
            // the failure that reads as working: a few sentences translated and the rest silently in
            // the canonical language, with nothing saying which.
            if (problems.hasErrorsIn(name)) {
                refused.add(id);
                continue;
            }
            // Unmodifiable rather than `Map.copyOf`, and this is not style: `Map.copyOf` returns a map
            // whose iteration order is *unspecified*, so wrapping the sorted map in it silently threw
            // the ordering away -- and the sibling rung of the resolution chain reads that order to
            // pick one of several relatives. It picked a different one per run. See
            // `QuestLanguagesTest`'s "any sibling beats English, in name order".
            bundles.put(id, java.util.Collections.unmodifiableMap(entries));
        }

        if (bundles.isEmpty() && refused.isEmpty()) {
            return EMPTY;
        }
        return new QuestLanguages(java.util.Collections.unmodifiableMap(bundles),
                java.util.Collections.unmodifiableSet(refused));
    }

    /** The locales this pack has, name-sorted. Empty when it ships no translations. */
    public Set<String> locales() {
        return bundles.keySet();
    }

    /** The locale ids whose file was refused, name-sorted. The reasons are in the problem list. */
    public Set<String> refused() {
        return refused;
    }

    /** Whether this pack ships no usable translations at all. */
    public boolean isEmpty() {
        return bundles.isEmpty();
    }

    /**
     * The locale whose text a player who asked for {@code asked} should read, or empty for none.
     *
     * <h2>The chain, and why each step is in it</h2>
     *
     * <ol>
     *   <li><b>The exact locale.</b> An author who translated for {@code es_mx} meant it.</li>
     *   <li><b>The language root.</b> A file named {@code es.json} is a translation for Spanish
     *       wherever it is read, so every {@code es_*} client wants it.</li>
     *   <li><b>The pack's own canonical locale, when it shares the root.</b> {@code es_es} is a far
     *       better answer for an {@code es_mx} player than English is, and the author has already
     *       said which locale is the canonical one — so that is the sibling to prefer.</li>
     *   <li><b>Any other sibling, in name order.</b> The last resort, and it is here because the
     *       alternative is English: Minecraft ships {@code es_ar}, {@code es_cl}, {@code es_ec},
     *       {@code es_mx}, {@code es_uy}, {@code es_ve}, {@code pt_br}, {@code pt_pt},
     *       {@code zh_cn}, {@code zh_tw} and {@code zh_hk}, and a pack with one Spanish file would
     *       otherwise serve none of them. Name order rather than any cleverer rule so the answer is
     *       the same on every server and in every run.</li>
     *   <li><b>The canonical locale itself.</b> Nothing matched the language, so the pack's own
     *       language is the honest answer.</li>
     * </ol>
     *
     * <p>There is deliberately <b>no table of locale codes here</b>. The chain is derived from the
     * files the pack actually ships and from string prefixes, so a locale Minecraft adds later is
     * served by the same rule without this file being edited — and a hardcoded list, which could
     * only ever be the list of the day it was written, would be the thing that rots.
     *
     * @return a locale id, or empty when the pack has neither the asked-for language nor its own
     */
    public String servedLocale(String asked, String fallbackLocale) {
        String want = normalise(asked);
        String base = normalise(fallbackLocale);
        String baseOrNothing = bundles.containsKey(base) ? base : "";

        if (want.isEmpty() || want.equals(base)) {
            return baseOrNothing;
        }
        if (bundles.containsKey(want)) {
            return want;
        }
        String root = rootOf(want);
        if (root.isEmpty()) {
            return baseOrNothing;
        }
        if (bundles.containsKey(root)) {
            return root;
        }
        if (rootOf(base).equals(root) && bundles.containsKey(base)) {
            return base;
        }
        return bundles.keySet().stream()
                .filter(id -> rootOf(id).equals(root))
                .findFirst()
                .orElse(baseOrNothing);
    }

    /**
     * The entries a player who asked for {@code asked} should read.
     *
     * <p>The canonical locale's entries sit <b>under</b> the served locale's, so a pack can keep the
     * strings every language shares — a name, a number, a line it did not translate — in its
     * canonical file and only the differences elsewhere. The served layer wins every key it has.
     *
     * <p>Empty when the pack has neither file, which is the state that leaves the tree's own strings
     * on screen. That is not a failure: a pack with no translations is the normal case, and the tree
     * already carries text that reads correctly.
     */
    public Map<String, String> forLocale(String asked, String fallbackLocale) {
        Map<String, String> base = bundles.getOrDefault(normalise(fallbackLocale), Map.of());
        String served = servedLocale(asked, fallbackLocale);
        if (served.isEmpty()) {
            return base;
        }
        Map<String, String> layer = bundles.getOrDefault(served, Map.of());
        if (layer.isEmpty() || layer == base) {
            return base;
        }
        Map<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(layer);
        // Unmodifiable rather than `Map.copyOf` again: the merge's order is the canonical locale's
        // entries followed by the served one's, and the packed JSON is written in it. A payload whose
        // key order varied per build would be a cache whose bytes differed for no reason.
        return java.util.Collections.unmodifiableMap(merged);
    }

    /**
     * A locale id as this class spells it: lower case, {@code -} read as {@code _}, or empty.
     *
     * <p>Empty means "not a locale id", and every caller treats it as "no answer" rather than as an
     * error. Both directions go through here — the file name when loading and the client's own
     * language when resolving — so the two can never be spelled differently and miss each other.
     */
    public static String normalise(String locale) {
        if (locale == null) {
            return "";
        }
        String lower = locale.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return LOCALE_ID.matcher(lower).matches() ? lower : "";
    }

    /** The part before the first {@code _}: {@code es_mx} is {@code es}, and {@code es} is empty. */
    private static String rootOf(String locale) {
        int cut = locale.indexOf('_');
        return cut <= 0 ? "" : locale.substring(0, cut);
    }
}
