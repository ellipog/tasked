package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.TeamProgress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The converted reference pack's gates, proved on the real tree rather than on fixtures.
 *
 * <p>Small synthetic books prove each rule in isolation all over this suite; what they cannot
 * prove is that a 4790-quest conversion carries every edge, mode and count across intact — a
 * dropped {@code dependsOn} in one file in five thousand is invisible to every fixture. So
 * this test loads the actual converted profile tree (the ATM10 book the migrator wrote),
 * stripped of item data components the plain-JSON ops cannot see, and asserts the topology:
 * every edge resolves, nothing dangles, nothing loops, and empty progress unlocks exactly
 * the roots.
 *
 * <p>Local-only, like the migrator's own reference-pack tests: {@code TENET_QUESTS} names the
 * config directory holding {@code tenet/quests} (defaulting to the developer's ATM10
 * profile), and the test skips where it is absent rather than failing CI on another
 * machine's paths.
 *
 * <p>Why components are stripped: registry-backed data components only decode against
 * registry ops, which no game-free load has. Topology — ids, edges, modes, counts — does
 * not live in components, so they are removed from a temp copy rather than judged. A file
 * that fails for any other reason fails this test by name.
 */
@DisplayName("the converted reference pack gates like its sources")
class ProfileGatesTest {

    private static final String DEFAULT_ROOT =
            "C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/All the Mods 10 - ATM10 (1)/config";

    private static final long NOW = 10_000L;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("every edge resolves and empty progress unlocks exactly the roots")
    void profileGatesResolve(@TempDir Path temp) throws IOException {
        Path source = Path.of(System.getenv().getOrDefault("TENET_QUESTS", DEFAULT_ROOT))
                .resolve(QuestLoader.DIRECTORY);
        assumeTrue(Files.isDirectory(source), "no converted profile tree at " + source);

        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        copyStripped(source, quests);
        writeStrictProbe(quests);

        QuestLoader.Result result = QuestLoader.load(temp);
        assertTrue(!result.problems().hasErrors(),
                "the profile tree has load errors:\n" + firstProblems(result, 10));
        assertTrue(ProgressionEngine.findCycles(result.index()).isEmpty(),
                "the profile tree has dependency cycles");

        // Every edge names a quest that is there. The loader reports dangling references
        // itself; this walks the built index so a resolution miss cannot hide behind a
        // message the test never reads.
        List<String> dangling = new ArrayList<>();
        for (QuestIndex.QuestEntry entry : result.index().quests()) {
            for (QuestRef dependency : entry.quest().dependencies()) {
                if (result.index().quest(dependency.id()).isEmpty()) {
                    dangling.add(entry.quest().id() + " -> " + dependency.id());
                }
            }
        }
        assertTrue(dangling.isEmpty(), "dangling dependsOn:\n" + String.join("\n", dangling));

        Map<String, QuestState> states =
                ProgressionEngine.resolve(result.index(), TeamProgress.empty(), NOW).states();

        // The injected strict chapter proves the strict rule on the real loader path: the
        // root is open, the gated quest is shut, from nothing done.
        assertEquals(QuestState.UNLOCKED, states.get("strictprobe_root"));
        assertEquals(QuestState.LOCKED, states.get("strictprobe_gate"));

        // And the profile tree itself: nothing done means nothing started and nothing
        // finished, and no quest outside the probe reads LOCKED — every gate in the
        // converted book is a flexible one, exactly as the file-level flexible source
        // converts, so they measure rather than shut.
        List<String> wrong = new ArrayList<>();
        for (QuestIndex.QuestEntry entry : result.index().quests()) {
            String id = entry.quest().id();
            if (id.startsWith("strictprobe_")) {
                continue;
            }
            QuestState state = states.get(id);
            if (state == QuestState.COMPLETED || state == QuestState.STARTED) {
                wrong.add(id + " reads " + state + " with nothing done");
            }
            if (state == QuestState.LOCKED) {
                wrong.add(id + " reads LOCKED: a strict gate the census says should not exist");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    private static String firstProblems(QuestLoader.Result result, int limit) {
        List<String> lines = new ArrayList<>();
        result.problems().all().stream().limit(limit)
                .forEach(problem -> lines.add(problem.toString()));
        return String.join("\n", lines);
    }

    /**
     * Copies the tree minus item data components, which plain-JSON ops cannot decode.
     * Skips the migrator's own audit folder, which the loader skips by name anyway.
     */
    private static void copyStripped(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(file);
                if (relative.toString().startsWith("_migrator")) {
                    continue;
                }
                Path out = target.resolve(relative.toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(out);
                }
                else if (file.toString().endsWith(".json")) {
                    JsonElement parsed =
                            JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    stripComponents(parsed);
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, parsed.toString(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void stripComponents(JsonElement element) {
        if (element.isJsonObject()) {
            element.getAsJsonObject().remove("components");
            for (Map.Entry<String, JsonElement> child : element.getAsJsonObject().entrySet()) {
                stripComponents(child.getValue());
            }
        }
        else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                stripComponents(child);
            }
        }
    }

    /** A strict chapter with a root and one gated quest: the LOCKED control. */
    private static void writeStrictProbe(Path quests) throws IOException {
        Path chapter = quests.resolve("strictprobe");
        Files.createDirectories(chapter);
        Files.writeString(chapter.resolve("chapter.json"),
                """
                        { "id": "strictprobe", "title": "Strict probe",
                          "quests": ["strictprobe_root.json", "strictprobe_gate.json"] }
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(chapter.resolve("strictprobe_root.json"),
                """
                        { "id": "strictprobe_root", "title": "Strict root",
                          "tasks": [{ "type": "tenet:checkmark", "title": "Tick" }] }
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(chapter.resolve("strictprobe_gate.json"),
                """
                        { "id": "strictprobe_gate", "title": "Strict gate",
                          "dependsOn": ["strictprobe_root"],
                          "tasks": [{ "type": "tenet:checkmark", "title": "Tick" }] }
                        """,
                StandardCharsets.UTF_8);
        // The walk reports entries the index does not mention, so the probe chapter joins it.
        Path index = quests.resolve("index.json");
        JsonElement parsed = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8));
        parsed.getAsJsonObject().getAsJsonArray("entries").add(JsonParser
                .parseString("{ \"chapter\": \"strictprobe\" }").getAsJsonObject());
        Files.writeString(index, parsed.toString(), StandardCharsets.UTF_8);
    }
}
