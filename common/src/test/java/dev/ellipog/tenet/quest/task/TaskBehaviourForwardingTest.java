package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry wrapper around every task behaviour forwards <b>all</b> of {@link TaskBehaviour}.
 *
 * <h2>What this is guarding against, and it happened</h2>
 *
 * <p>{@code TaskTypes.widenBehaviour} existed to erase a type parameter; its first version forwarded
 * {@code required}, {@code current} and {@code canSubmitByHand} and left the interface's newer methods
 * to their defaults. So an item task saying {@code consumeItems: true} answered
 * {@code takesResources == false} at every engine call site, and consuming tasks silently took
 * nothing. No test failed, because the worked examples never consume.
 *
 * <p>A wrapper that forwards "most" methods is the shape of that bug, so this pins the ones a type
 * can override and the engine actually calls — through {@code behaviourOf}, which is the only door
 * the engine uses.
 */
@DisplayName("the task registry's behaviour wrapper")
class TaskBehaviourForwardingTest {

    private static ItemTask itemTask(Optional<Boolean> consume) {
        return new ItemTask(TaskCommon.DEFAULT,
                new ItemRef(ResourceLocation.withDefaultNamespace("oak_log"), 4),
                consume, ComponentMatch.STRICT, false);
    }

    @Test
    @DisplayName("a consuming item task still consumes through the registry")
    void consumingSurvivesTheWrapper() {
        ItemTask task = itemTask(Optional.of(true));
        TaskBehaviour<QuestTask> through = TaskTypes.behaviourOf(task).orElseThrow();

        assertTrue(through.takesResources(task, false),
                "the wrapper dropped takesResources, which is what silently stopped item consumption");
        assertEquals(4, through.required(task));
        assertTrue(through.acceptsClientSubmit(task, false),
                "a consuming task is submit-by-hand, and the client submit gate reads this");
        assertTrue(through.waitsForSubmit(task, false),
                "and it waits for the press, which is the engine's half of the same answer");
    }

    @Test
    @DisplayName("a presence-only item task still takes nothing")
    void presenceStillTakesNothing() {
        ItemTask task = itemTask(Optional.of(false));
        TaskBehaviour<QuestTask> through = TaskTypes.behaviourOf(task).orElseThrow();

        assertFalse(through.takesResources(task, true));
        assertFalse(through.acceptsClientSubmit(task, true),
                "a presence-only task has no button, so its client submit is refused too");
        assertFalse(through.waitsForSubmit(task, true),
                "and the tick registers it: declining the chapter's default is the whole point of the field");
    }

    @Test
    @DisplayName("only the inventory-backed types read the inventory, through the registry")
    void readsInventorySurvivesTheWrapper() {
        // The pack's detection_delay floors exactly these types' re-evaluation. A type missing
        // here keeps its own cadence whatever the pack says; a type wrongly present here is
        // throttled for no reason. Pinned through behaviourOf, the engine's only door.
        assertTrue(TaskTypes.behaviourOf(itemTask(Optional.empty())).orElseThrow().readsInventory(),
                "item tasks count carried items");
        assertTrue(TaskTypes.behaviourOf(new ItemTagTask(TaskCommon.DEFAULT,
                        ResourceLocation.withDefaultNamespace("logs"), 1, Optional.empty()))
                        .orElseThrow().readsInventory(),
                "item-tag tasks count carried items");
        assertTrue(TaskTypes.behaviourOf(new FluidTask(TaskCommon.DEFAULT,
                        ResourceLocation.withDefaultNamespace("water"), 1000))
                        .orElseThrow().readsInventory(),
                "fluid tasks count carried buckets");
        assertFalse(TaskTypes.behaviourOf(new StatTask(TaskCommon.DEFAULT,
                        ResourceLocation.withDefaultNamespace("walk_one_cm"), 100))
                        .orElseThrow().readsInventory(),
                "a stat lookup is not an inventory walk");
        assertFalse(TaskTypes.behaviourOf(new XpTask(TaskCommon.DEFAULT, 30, false))
                        .orElseThrow().readsInventory(),
                "nor is an XP level read");
    }
}
