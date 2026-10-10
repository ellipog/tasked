package dev.ellipog.tenet.editor;

import com.google.gson.JsonObject;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Editing the converted reference pack: every chapter opens, and the ATM Star quest in
 * particular builds a replica and panel rows.
 *
 * <p>Profile-gated like {@code ProfileGatesTest}: {@code TENET_QUESTS} names the config
 * directory holding {@code tenet/quests} (defaulting to the developer's ATM10 profile), and
 * the tests skip where it is absent. The tree is copied to a temp dir because the editor
 * holds files open; the copy is never stripped, because the editor reads raw JSON.
 *
 * <p>What this does <i>not</i> do is apply ops to profile data: saving validates components
 * against registries no game-free JVM has, so modded stacks refuse here and land in game.
 * Applies are covered against synthetic fixtures (see {@code QuestEditorTest.saveUsesGivenOps});
 * this class covers the validation-free paths — open, replica, panel — for the whole pack.
 */
@DisplayName("editing the converted reference pack")
class ProfileEditTest {

    private static final String DEFAULT_ROOT =
            "C:/Users/Ellio/AppData/Roaming/ModrinthApp/profiles/All the Mods 10 - ATM10 (1)/config";

    private static final String CHAPTER = "chapter_3_the_atm_star";
    private static final String QUEST = "atm_star";

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the ATM Star quest opens, replicates and builds panel rows")
    void atmStarEdits(@TempDir Path temp) throws IOException {
        Path quests = copyProfile(temp);
        QuestEditor editor = QuestEditor.open(quests, CHAPTER).orElse(null);
        assertNotNull(editor, "chapter 3 should open for editing");
        assertTrue(editor.holds(QUEST), "the editor should hold atm_star");

        ServerEditors editors = new ServerEditors(() -> quests);
        JsonObject replica = editors.replica(CHAPTER);
        assertNotNull(replica, "chapter 3 should build a replica");
        assertTrue(replica.has(QUEST), "the replica should carry atm_star");

        ClientChapterReplica.accept(CHAPTER, replica.toString(), "{}", 1L);
        JsonObject copy = ClientChapterReplica.quest(CHAPTER, QUEST);
        assertNotNull(copy, "the replica copy should serve atm_star");
        assertTrue(QuestPanelLayout.rows(copy, QUEST, Set.of()).size() > 10,
                "the editor panel should build rows for atm_star");
    }

    @Test
    @DisplayName("every converted chapter opens and replicates")
    void everyChapterEdits(@TempDir Path temp) throws IOException {
        Path quests = copyProfile(temp);
        List<String> failures = new ArrayList<>();
        ServerEditors editors = new ServerEditors(() -> quests);
        for (String chapter : chapterIds(quests)) {
            QuestEditor editor = QuestEditor.open(quests, chapter).orElse(null);
            if (editor == null) {
                failures.add(chapter + ": would not open");
                continue;
            }
            try {
                com.google.gson.JsonObject replica = editors.replica(chapter);
                if (replica == null) {
                    failures.add(chapter + ": no replica");
                }
            }
            catch (RuntimeException broken) {
                failures.add(chapter + ": replica threw " + broken);
            }
        }
        assertTrue(failures.isEmpty(),
                "chapters that will not edit:\n" + String.join("\n", failures));
    }

    private static Path copyProfile(Path temp) throws IOException {
        Path source = Path.of(System.getenv().getOrDefault("TENET_QUESTS", DEFAULT_ROOT))
                .resolve("tenet/quests");
        assumeTrue(Files.isDirectory(source.resolve("main_questline").resolve(CHAPTER)),
                "no converted profile tree at " + source);
        Path quests = temp.resolve("quests");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(quests.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, quests.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        return quests;
    }

    /** Chapter ids from the converted index, in book order. */
    private static List<String> chapterIds(Path quests) throws IOException {
        List<String> ids = new ArrayList<>();
        Path index = quests.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            return ids;
        }
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        java.util.Map<String, String> groupOf = new java.util.LinkedHashMap<>();
        for (Path group : Files.list(quests).filter(Files::isDirectory).toList()) {
            Path manifest = group.resolve("group.json");
            if (Files.isRegularFile(manifest)) {
                com.google.gson.JsonObject g = com.google.gson.JsonParser
                        .parseString(Files.readString(manifest)).getAsJsonObject();
                if (g.has("id")) {
                    groupOf.put(group.getFileName().toString(), g.get("id").getAsString());
                }
            }
        }
        for (com.google.gson.JsonElement entry : root.getAsJsonArray("entries")) {
            com.google.gson.JsonObject object = entry.getAsJsonObject();
            if (object.has("chapter")) {
                String dir = object.get("chapter").getAsString();
                Path manifest = quests.resolve(dir).resolve("chapter.json");
                if (Files.isRegularFile(manifest)) {
                    com.google.gson.JsonObject c = com.google.gson.JsonParser
                            .parseString(Files.readString(manifest)).getAsJsonObject();
                    if (c.has("id")) {
                        ids.add(c.get("id").getAsString());
                    }
                }
            }
            else if (object.has("group")) {
                String dir = object.get("group").getAsString();
                Path manifest = quests.resolve(dir).resolve("group.json");
                if (Files.isRegularFile(manifest)) {
                    com.google.gson.JsonObject g = com.google.gson.JsonParser
                            .parseString(Files.readString(manifest)).getAsJsonObject();
                    if (g.has("chapters")) {
                        for (com.google.gson.JsonElement name : g.getAsJsonArray("chapters")) {
                            Path chapter = quests.resolve(dir).resolve(name.getAsString())
                                    .resolve("chapter.json");
                            if (Files.isRegularFile(chapter)) {
                                com.google.gson.JsonObject c = com.google.gson.JsonParser
                                        .parseString(Files.readString(chapter)).getAsJsonObject();
                                if (c.has("id")) {
                                    ids.add(c.get("id").getAsString());
                                }
                            }
                        }
                    }
                }
            }
        }
        return ids;
    }
}
