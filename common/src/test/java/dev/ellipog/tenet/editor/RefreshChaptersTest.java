package dev.ellipog.tenet.editor;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cosmetic refresh without a reload: dirty chapters re-read from their editors, swapped into
 * the loaded index, everything else untouched.
 *
 * <p>Why this shape rather than a full load before and after: the point is what does <i>not</i>
 * move. A move that also reordered the book, dropped an alias or rebuilt a group would still
 * draw correctly on the author's canvas — the pending values cover that — while every other
 * player quietly inherited the damage. So the assertions are mostly negative: same order,
 * same counts, same objects everywhere but the chapter that moved.
 */
@DisplayName("a cosmetic refresh without a reload")
class RefreshChaptersTest {

    private static final DynamicOps<JsonElement> OPS = com.mojang.serialization.JsonOps.INSTANCE;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("a moved quest lands everywhere its chapter is, and nowhere else")
    void movedQuestLands(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("tenet/quests");
        writeQuests(root, "pack", "first_steps",
                quest("one", 0, 0, "minecraft:oak_log"), quest("two", 64, 0, "minecraft:stick"));
        writeQuests(root, "pack", "second_steps",
                quest("three", 0, 0, "minecraft:stone"));
        writeGroup(root, "pack", "first_steps", "second_steps");

        QuestIndex before = QuestLoader.load(dir, OPS).index();
        ServerEditors editors = new ServerEditors(() -> root);
        assertTrue(editors.open("first_steps").isPresent(), "the chapter opens");

        QuestEditor editor = editors.open("first_steps").orElseThrow();
        assertTrue(editor.move("one", 10, 20));

        Optional<QuestIndex.FreshChapter> fresh = editors.refreshModel("first_steps", OPS);
        assertTrue(fresh.isPresent(), "the open model always decodes — it just saved");
        QuestIndex after = before.withRefreshedChapter(fresh.get());
        assertNotNull(after, "a chapter the index holds always swaps");

        assertEquals(3, after.questCount(), "no quest added or lost");
        assertEquals(2, after.chapterCount());
        assertEquals(List.of("one", "two", "three"),
                after.quests().stream().map(entry -> entry.quest().id()).toList(),
                "declaration order survives the swap");
        assertEquals(10, after.quest("one").orElseThrow().quest().layout().x());
        assertEquals(20, after.quest("one").orElseThrow().quest().layout().y());
        assertEquals(64, after.quest("two").orElseThrow().quest().layout().x(),
                "the unmoved neighbour keeps its values");
        assertEquals(0, after.quest("three").orElseThrow().quest().layout().x(),
                "and so does the other chapter");

        QuestIndex.QuestEntry moved = after.quest("one").orElseThrow();
        QuestIndex.ChapterEntry chapter = after.chapters().stream()
                .filter(entry -> entry.chapter().id().equals("first_steps")).findFirst().orElseThrow();
        assertSame(chapter.chapter(), moved.chapter(),
                "the quest entry and the chapter entry share the chapter object");
        dev.ellipog.tenet.quest.ChapterGroup group = after.groups().stream()
                .filter(entry -> entry.group().id().equals("pack")).findFirst().orElseThrow()
                .group();
        assertTrue(group.chapters().contains(chapter.chapter()),
                "and the group's chapter list holds the same object, not the stale one");
    }

    @Test
    @DisplayName("an unknown chapter answers null: the caller reloads fully")
    void unknownChapterIsNull(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("tenet/quests");
        writeQuests(root, "pack", "first_steps", quest("one", 0, 0, "minecraft:oak_log"));
        writeGroup(root, "pack", "first_steps");

        QuestIndex before = QuestLoader.load(dir, OPS).index();
        ServerEditors editors = new ServerEditors(() -> root);
        assertTrue(editors.refreshModel("missing", OPS).isEmpty(),
                "no editor, no model");

        QuestIndex.FreshChapter bogus = new QuestIndex.FreshChapter("missing",
                before.chapters().get(0).chapter(), before.chapters().get(0).document(),
                "missing/chapter.json", List.of());
        assertNull(before.withRefreshedChapter(bogus),
                "and the index says so too, so the flush takes the full reload");
    }

    private static String quest(String id, int x, int y, String item) {
        return """
                {
                  "id": "%s",
                  "title": "%s",
                  "x": %d, "y": %d,
                  "icon": { "item": "%s" },
                  "tasks": []
                }
                """.formatted(id, id, x, y, item);
    }

    private static void writeQuests(Path root, String group, String chapter, String... quests)
            throws IOException {
        Path folder = root.resolve(group).resolve(chapter);
        Files.createDirectories(folder);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < quests.length; i++) {
            String name = "q" + i + ".json";
            Files.writeString(folder.resolve(name), quests[i], StandardCharsets.UTF_8);
            if (i > 0) {
                names.append(", ");
            }
            names.append('"').append(name).append('"');
        }
        Files.writeString(folder.resolve("chapter.json"), """
                {
                  "id": "%s",
                  "title": "%s",
                  "quests": [ %s ]
                }
                """.formatted(chapter, chapter, names), StandardCharsets.UTF_8);
    }

    private static void writeGroup(Path root, String group, String... chapters) throws IOException {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < chapters.length; i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append('"').append(chapters[i]).append('"');
        }
        Files.writeString(root.resolve(group).resolve("group.json"), """
                { "id": "%s", "title": "%s", "chapters": [ %s ] }
                """.formatted(group, group, names), StandardCharsets.UTF_8);
    }
}
