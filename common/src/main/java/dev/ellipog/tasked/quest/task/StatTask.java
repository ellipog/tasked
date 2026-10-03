package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;

import java.util.Set;
import java.util.function.Function;

/**
 * Reach a value of a vanilla statistic.
 *
 * <pre>{@code { "type": "tasked:stat", "stat": "minecraft:walk_one_cm", "value": 1000 } }</pre>
 *
 * <p>Progress is shown as the stat's own number — "434 / 1000" — and clamped to the requirement, as
 * FTB Quests clamps it, so a stat in the millions does not overflow a row. Polled every three ticks:
 * reading a stat is a map lookup, and it is the cadence FTBQ gives it.
 */
public record StatTask(TaskCommon common, ResourceLocation stat, int value) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "stat");

    public static final Set<String> FIELDS = Set.of("stat", "value");

    public static final MapCodec<StatTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(3).forGetter(StatTask::common),
            ResourceLocation.CODEC.fieldOf("stat").forGetter(StatTask::stat),
            Codec.intRange(1, 1_000_000_000).fieldOf("value").forGetter(StatTask::value)
    ).apply(instance, StatTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final TaskBehaviour<StatTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(StatTask task) {
            return task.value();
        }

        @Override
        public int current(StatTask task, TaskContext context) {
            int held = context.player().getStats().getValue(Stats.CUSTOM.get(task.stat()));
            return Math.min(held, task.value());
        }

        @Override
        public boolean canSubmitByHand(StatTask task) {
            return false;
        }
    };

    public static final Function<StatTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tasked.task.stat", "Reach " + task.stat(),
                    task.stat().toString(), task.value());
}
