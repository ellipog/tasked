package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.RegistryRef;
import dev.ellipog.tasked.quest.TaskCommon;
import dev.ellipog.tasked.quest.TaskContext;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;
import java.util.function.Function;

/**
 * Be in a biome, or in any biome of a tag.
 *
 * <pre>{@code { "type": "tasked:biome", "biome": "#minecraft:is_forest" } }</pre>
 *
 * <p>Polled every twenty ticks; FTB Quests skips this one on login because a biome lookup is not
 * free, and the same cadence answers that without a special case.
 */
public record BiomeTask(TaskCommon common, RegistryRef biome) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "biome");

    public static final Set<String> FIELDS = Set.of("biome");

    public static final MapCodec<BiomeTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(BiomeTask::common),
            RegistryRef.CODEC.fieldOf("biome").forGetter(BiomeTask::biome)
    ).apply(instance, BiomeTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** Whether a biome holder is the one named, or a member of the tag named. */
    public static boolean matches(Holder<Biome> holder, RegistryRef wanted) {
        if (wanted.tag()) {
            return holder.is(TagKey.create(Registries.BIOME, wanted.id()));
        }
        return holder.unwrapKey().map(key -> key.location().equals(wanted.id())).orElse(false);
    }

    public static final TaskBehaviour<BiomeTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(BiomeTask task) {
            return 1;
        }

        @Override
        public int current(BiomeTask task, TaskContext context) {
            Holder<Biome> here = context.player().level().getBiome(context.player().blockPosition());
            return matches(here, task.biome()) ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(BiomeTask task) {
            return false;
        }
    };

    public static final Function<BiomeTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tasked.task.biome", "Find " + task.biome(),
                    task.biome().toString(), 1);
}
