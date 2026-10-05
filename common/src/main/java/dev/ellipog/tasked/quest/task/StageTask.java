package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.progress.StageService;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.Function;

/**
 * Have a stage.
 *
 * <pre>{@code { "type": "tasked:stage", "stage": "my_pack:left_the_village" } }</pre>
 *
 * <p>A stage is a named flag a player has -- see {@code ProgressStore}'s note on why it is per player --
 * and this is the task that asks about one. It is the read half of the same feature whose write half is
 * the stage reward, a command, or a script: a pack grants "left_the_village" somewhere and asks about it
 * here, which is how a questline says "you were there" without a bookkeeping item.
 *
 * <p>Nothing is required of the id: a stage exists by being granted, so any resource location is a valid
 * one to ask about, and a typo shows up as a task that never completes rather than as a file that will not
 * load. There is nothing for the validator to check and no list to pick from, which is why the editor draws
 * this as a text field with a hint rather than as a search.
 *
 * <p>Measured, never handed in: whether the flag is set is not something the player can attest to, and a
 * Submit button for it would be a button that either does nothing or lets a player grant their own stages.
 */
public record StageTask(TaskCommon common, ResourceLocation stage) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "stage");

    public static final Set<String> FIELDS = Set.of("stage");

    public static final MapCodec<StageTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(StageTask::common),
            ResourceLocation.CODEC.fieldOf("stage").forGetter(StageTask::stage)
    ).apply(instance, StageTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final TaskBehaviour<StageTask> BEHAVIOUR = new TaskBehaviour<>() {

        /** One flag: you have it or you do not. */
        @Override
        public int required(StageTask task) {
            return 1;
        }

        @Override
        public int current(StageTask task, TaskContext context) {
            return StageService.has(context.player().getServer(), context.player().getUUID(), task.stage())
                    ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(StageTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<StageTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tasked.task.stage", "Have the stage " + task.stage(),
                    task.stage().toString(), 1);
}
