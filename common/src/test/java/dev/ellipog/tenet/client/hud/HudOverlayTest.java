package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.ToastArt;
import dev.ellipog.tenet.client.render.RecordingRenderer;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD's own two elements, drawn through a recording renderer.
 *
 * <h2>Why these are drawn rather than reasoned about</h2>
 *
 * <p>Because the interesting faults here are visible only in the calls: a panel that measured one box and
 * painted another, a notice row drawn after it had faded, a sample that stopped being drawn the moment
 * nobody had pinned anything — which is the one arrangement where an element cannot be found <i>or</i>
 * grabbed. {@code RecordingRenderer} is the second implementation of the drawing seam, so all of them are
 * assertable with no client at all.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Whether any of it reads well: the colours are the theme's, the glyphs are the font's, and this renderer
 * fixes a character at six pixels. That is a screenshot's job, and {@code TESTING.md} says so.
 */
@DisplayName("the HUD's own drawing")
class HudOverlayTest {

    /** The same metric the recording renderer reports, so a measured box and a drawn one can be compared. */
    private static final Measure FONT = Measure.monospace(6, 10);

    /** A time, so that "on screen" and "faded" are two different numbers rather than two clocks. */
    private static final long NOW = 1_000L;

    private static final String A = """
            {"id": "a", "title": "Punch a tree", "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
            """;
    private static final String B = """
            {"id": "b", "title": "Craft a table", "dependsOn": ["a"], "tasks": [
              {"type": "tenet:checkmark", "title": "Say hello"}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void forget() {
        HudOverlay.clear();
        PinnedQuests.reset();
        HudSettings.reset();
        ClientQuestCache.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    /** A progress message that finishes the first task of {@code a}, which asks for eight of something. */
    private static void finishTheFirstTask() {
        ClientQuestCache.acceptProgress(java.util.UUID.randomUUID(), 100L,
                "{\"quests\":{\"a\":{\"state\":\"STARTED\",\"tasks\":[8]}}}"
                        .getBytes(StandardCharsets.UTF_8), 50L);
    }

    private static BookGeometry.Rect boxOf(HudElement element, RecordingRenderer r, int width, int height) {
        HudOverlay.Size size = HudOverlay.size(element, FONT, HudOverlay.Face.LIVE, NOW);
        return HudLayout.boxAt(HudSettings.x(element), HudSettings.y(element), size.width(), size.height(),
                width, height);
    }

    @Test
    @DisplayName("the pinned panel draws nothing live until something is pinned, and a sample in the editor")
    void thePanelIsEmptyUntilSomethingIsPinned() {
        RecordingRenderer live = RecordingRenderer.create();
        HudOverlay.paint(HudElement.PINNED_QUESTS, live, BookGeometry.Rect.at(4, 4, 100, 20), FONT,
                HudOverlay.Face.LIVE, NOW);
        assertTrue(live.calls().isEmpty(), "an element with nothing to hold is not an empty frame");

        RecordingRenderer editor = RecordingRenderer.create();
        HudOverlay.paint(HudElement.PINNED_QUESTS, editor, BookGeometry.Rect.at(4, 4, 100, 20), FONT,
                HudOverlay.Face.EDITOR, NOW);
        assertFalse(editor.fills().isEmpty(), "and in the editor it has a surface to be grabbed by");
        assertTrue(editor.drewText(Component.translatable(HudElement.PINNED_QUESTS.labelKey()).getString()),
                "with the panel's own name on it: " + drawn(editor));
        // A prefix rather than the whole sentence, and the reason is the panel's own rule: the sample is longer
        // than the box can be, so what is drawn is a truncated form of it. Asserting the whole string would be
        // asserting that the panel does *not* do the one thing it must -- and whether the sentence resolves to
        // English or to its own key here depends on whether a language was loaded, so the prefix is the half
        // that is the same either way.
        String sample = Component.translatable("tenet.hud.pinned_sample").getString();
        String opening = sample.substring(0, Math.min(8, sample.length()));
        assertTrue(editor.texts().stream().anyMatch(call -> call.text().startsWith(opening)),
                "and a sentence saying why it is empty, beginning " + opening + ": " + drawn(editor));
    }

    /** Everything a recorder drew, for a failure message: which of these is missing is the whole question. */
    private static String drawn(RecordingRenderer r) {
        return r.texts().stream().map(RecordingRenderer.Call::text).toList().toString();
    }

    @Test
    @DisplayName("the notice element has no box until there is something to say, and a sample in the editor")
    void theNoticesHaveNoSizeUntilTheySpeak() {
        assertTrue(HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, NOW).empty(),
                "nothing to say is nothing to draw");

        HudOverlay.Size editor = HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.EDITOR, NOW);
        assertFalse(editor.empty(), "and the editor still has a box to grab");
        assertEquals(ToastArt.height(1), editor.height(), "one sample row");
        assertTrue(editor.width() >= ToastArt.MIN_WIDTH && editor.width() <= ToastArt.MAX_WIDTH,
                "as wide as the sentence needs, inside the notice's own bounds: " + editor.width());

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.paint(HudElement.NOTIFICATIONS, r, BookGeometry.Rect.at(4, 4, editor.width(), editor.height()),
                FONT, HudOverlay.Face.EDITOR, NOW);
        assertTrue(r.drewText(Component.translatable("tenet.hud.notifications_sample").getString()));
    }

    @Test
    @DisplayName("a notice gets a box and is drawn in it, and it is drawn where the player put it")
    void aNoticeIsDrawnWhereTheElementIs() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        HudOverlay.Size size = HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, NOW);
        assertFalse(size.empty(), "a sentence is a box");
        assertEquals(ToastArt.height(1), size.height());

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, NOW);
        assertTrue(r.drewText("Task done: Oak Log"), "the sentence is the notice");

        BookGeometry.Rect box = boxOf(HudElement.NOTIFICATIONS, r, 320, 240);
        RecordingRenderer.Call drawn = r.callFor("Task done: Oak Log");
        assertEquals(box.x() + ToastArt.PAD, drawn.x(), "the row is drawn inside the box the size was for");
        assertEquals(box.y() + (ToastArt.LINE - 8) / 2, drawn.y());
    }

    @Test
    @DisplayName("a notice that has had its time stops being drawn, and stops being a box")
    void aFadedNoticeIsGone() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        long later = NOW + ToastArt.height(1) + 10_000L;
        assertTrue(HudOverlay.size(HudElement.NOTIFICATIONS, FONT, HudOverlay.Face.LIVE, later).empty(),
                "a notice older than its life is not on screen");
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, later);
        assertFalse(r.drewText("Task done: Oak Log"));
    }

    @Test
    @DisplayName("clear forgets everything, faded or not")
    void clearForgets() {
        HudOverlay.notice("Task done: Oak Log", false, NOW);
        assertEquals(1, HudOverlay.held());

        HudOverlay.clear();
        assertEquals(0, HudOverlay.held(), "a disconnect does not wait for a timer");
        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, NOW);
        assertTrue(r.calls().isEmpty(), "and nothing of the world just left is drawn in the next one");
    }

    @Test
    @DisplayName("a switched-off element is not drawn at all")
    void aSwitchedOffElementIsNotDrawn() {
        HudSettings.setOn(HudElement.NOTIFICATIONS, false);
        HudOverlay.notice("Task done: Oak Log", false, NOW);

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 320, 240, NOW);
        assertFalse(r.drewText("Task done: Oak Log"), "the switch is the way back to a vanilla toast");
    }

    @Test
    @DisplayName("the control element is not the HUD's to draw, in either face")
    void theControlIsNotDrawnHere() {
        for (HudOverlay.Face face : HudOverlay.Face.values()) {
            RecordingRenderer r = RecordingRenderer.create();
            HudOverlay.paint(HudElement.INVENTORY_BUTTON, r, BookGeometry.Rect.at(2, 2, 16, 16), FONT, face,
                    NOW);
            assertTrue(r.calls().isEmpty(), "the button draws itself on the screen that owns it, in " + face);
            assertEquals(HudElement.INVENTORY_BUTTON.width(),
                    HudOverlay.size(HudElement.INVENTORY_BUTTON, FONT, face, NOW).width(),
                    "and its size is the constant in the table");
        }
    }

    @Test
    @DisplayName("a pinned quest draws its name, its task and its count, in its own box")
    void aPinnedQuestIsDrawnInFull() {
        accept(A, B);
        PinnedQuests.pin("a");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.drewText("Punch a tree"), "the head's name");
        assertTrue(r.drewText("0 / 8"), "its task's count, clamped to the target");

        // The task's own sentence, taken from the accessor the painter reads -- which is the claim: there is
        // one derivation of what a task row says, and the HUD's is the same one the book's rows use. What it
        // resolves to in this test is the item's translation key, because nothing here has a language file.
        String sentence = ClientQuestCache.entry("a").tasks().get(0).text().getString();
        assertTrue(r.drewText(sentence), "the task's own sentence: " + sentence);
    }

    @Test
    @DisplayName("a finished task wears the tick, and the row is drawn either way")
    void aFinishedTaskWearsATick() {
        accept(A, B);
        PinnedQuests.pin("a");

        RecordingRenderer before = RecordingRenderer.create();
        HudOverlay.render(before, 640, 480, NOW);
        assertFalse(before.drewText(PinnedPanelLayout.TICK), "an unmet task has no tick");

        finishTheFirstTask();

        RecordingRenderer done = RecordingRenderer.create();
        HudOverlay.render(done, 640, 480, NOW);
        assertTrue(done.drewText("8 / 8"), "a counter that reached its target");
        assertTrue(done.drewText(PinnedPanelLayout.TICK), "and a tick beside it");
        assertTrue(ClientQuestCache.taskDone("a", 0), "which is the cache's own answer, not a second one");
    }

    @Test
    @DisplayName("the pins behind the head are names, and one the tree does not hold is skipped")
    void theRestAreNames() {
        accept(A, B);
        PinnedQuests.pin("a");
        PinnedQuests.pin("b");
        PinnedQuests.pin("a_quest_this_server_has_never_heard_of");

        RecordingRenderer r = RecordingRenderer.create();
        HudOverlay.render(r, 640, 480, NOW);

        assertTrue(r.drewText("Craft a table"), "the second pin is on the panel: " + drawn(r));
        assertFalse(r.drewText("a_quest_this_server_has_never_heard_of"),
                "and an id the tree does not hold is not drawn as anything");
        assertTrue(PinnedQuests.isPinned("a_quest_this_server_has_never_heard_of"),
                "it is still pinned -- the panel is what skips it, and the list is what keeps it for the "
                        + "server that has it");
        assertTrue(PinnedQuests.isPinned("a"), "and the pins the tree does hold are all still there");
    }

    @Test
    @DisplayName("the empty state is the whole panel: the box is gone as well as the rows")
    void theEmptyStateTakesTheBoxWithIt() {
        assertTrue(HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW).empty());

        accept(A, B);
        PinnedQuests.pin("a");
        HudOverlay.Size size = HudOverlay.size(HudElement.PINNED_QUESTS, FONT, HudOverlay.Face.LIVE, NOW);
        assertFalse(size.empty());
        assertNotEquals(0, size.width());
        assertTrue(size.width() >= PinnedPanelLayout.MIN_WIDTH
                        && size.width() <= PinnedPanelLayout.MAX_WIDTH,
                "and the measured size is inside the panel's own bounds: " + size.width());
    }
}
