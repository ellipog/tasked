package dev.ellipog.tasked.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.ellipog.armature.api.data.TypeDispatch;
import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.armature.api.registry.SimpleRegistry;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.QuestTask;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Every task type Tasked knows about.
 *
 * <p>The registry is open, which is the whole point: another mod calls {@link #register} during its
 * own construction and its task type works everywhere a built-in one does — in quest files, in the
 * validator, in the editor, and in the engine.
 *
 * <h2>Why {@link #dispatchCodec()} is a method rather than a constant</h2>
 *
 * <p>Because of a class-initialisation cycle that compiled cleanly and failed at runtime, which is
 * worth spelling out because it is invisible in the source:
 *
 * <pre>
 * TaskTypes.&lt;clinit&gt;   reads ItemTask.MAP_CODEC
 *   ItemTask.&lt;clinit&gt;  initialises QuestTask first, because QuestTask declares a default method
 *     QuestTask.&lt;clinit&gt;  reads TaskTypes.DISPATCH_CODEC
 *       TaskTypes is already initialising on this thread, so the JVM hands back null
 *     QuestTask.CODEC = null, permanently, because it is static final
 * </pre>
 *
 * <p>The rule is JLS 12.4.1: initialising a class initialises its superinterfaces that declare
 * default methods. {@code QuestTask.optional()} was a one-line convenience and it was enough to close
 * the loop.
 *
 * <p>The fix is structural rather than a reordering. {@link Dispatch} is a separate class, so its
 * initialisation happens the first time the codec is actually needed — during {@code Quest}'s static
 * init, by which point this class is fully built. Nothing in this class's initialisation touches
 * {@code QuestTask}'s static state, so the cycle cannot re-form.
 *
 * <h2>Why the registry holds an entry rather than a bare spec</h2>
 *
 * <p>Because the engine needs the <b>behaviour</b> for a task instance, and an instance only knows its
 * type id. So the entry carries the widened spec for dispatch and the widened behaviour for
 * evaluation, and one lookup answers both.
 *
 * <p>The widening is a genuine cast and is confined to {@link #widenSpec} and {@link #widenBehaviour}:
 * the downcast can only be reached for a value that was decoded with that same type's codec, because
 * the id, the codec and the behaviour all come from one registration.
 */
public final class TaskTypes {

    /** A registered type: what reads it, how it is evaluated, and what represents it. */
    private record Entry(TypeSpec<QuestTask> spec, TaskBehaviour<QuestTask> behaviour, ItemRef icon,
                         Function<QuestTask, TaskDisplay> display) {
    }

    // Declared first: static initialisation runs in declaration order, and the entries below register
    // into this.
    private static final SimpleRegistry<Entry> REGISTRY = SimpleRegistry.create("quest task types");

    /** {@code tasked:item} — have enough of an item. */
    public static final QuestTaskType<ItemTask> ITEM = register(
            "item", ItemTask.MAP_CODEC, ItemTask.FIELDS, ItemTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("chest"), 1), ItemTask.DISPLAY);

    /** {@code tasked:checkmark} — the player says they did it. */
    public static final QuestTaskType<CheckmarkTask> CHECKMARK = register(
            "checkmark", CheckmarkTask.MAP_CODEC, CheckmarkTask.FIELDS, CheckmarkTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("knowledge_book"), 1), CheckmarkTask.DISPLAY);

    private TaskTypes() {
    }

    /**
     * The codec that reads {@code "type"} and hands the rest to that type's own codec.
     *
     * <p>Lazily built, in its own class — see the class comment for the cycle that makes that necessary
     * rather than merely tidy.
     */
    private static final class Dispatch {
        static final Codec<QuestTask> CODEC = TypeDispatch.codec(
                "quest task", "type", QuestTask::type, () -> REGISTRY.values().stream().map(Entry::spec).toList());
    }

    public static Codec<QuestTask> dispatchCodec() {
        return Dispatch.CODEC;
    }

    /**
     * Adds a task type in Tasked's own namespace.
     *
     * <p>Call during your mod's construction on the client and the server.
     *
     * @throws IllegalStateException if the id is taken
     */
    public static <T extends QuestTask> QuestTaskType<T> register(String path,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display) {
        return register(ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, path), codec, fields, behaviour,
                icon, display);
    }

    /**
     * Adds a task type under an id you choose — which is how an addon registers its own, so the id is
     * namespaced to that mod rather than to Tasked.
     */
    public static <T extends QuestTask> QuestTaskType<T> register(ResourceLocation id,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display) {
        QuestTaskType<T> typed = new SimpleQuestTaskType<>(id, codec, fields, behaviour, icon, display);
        REGISTRY.register(id, new Entry(widenSpec(typed), widenBehaviour(behaviour), icon, widenDisplay(display)));
        return typed;
    }

    /**
     * What a task asks for, as a client should draw it.
     *
     * <p>{@link TaskDisplay#NONE} for an unregistered type. That is reachable only from a listing
     * against a quest that failed to decode, which the validator has already reported — so a blank
     * row is the right degradation rather than a crash in a rendering path.
     */
    public static TaskDisplay displayOf(QuestTask task) {
        return REGISTRY.get(task.type())
                .map(entry -> entry.display().apply(task))
                .orElse(TaskDisplay.NONE);
    }

    /** Every registered id, sorted. For messages, the validator, and {@code /tasked types}. */
    public static Set<ResourceLocation> ids() {
        return REGISTRY.ids();
    }

    public static int count() {
        return REGISTRY.size();
    }

    /**
     * The type-specific field names for a registered type, for the validator.
     *
     * <p>An empty set for an unknown type, which by then the validator has already complained about.
     */
    public static Set<String> fieldsOf(ResourceLocation id) {
        return REGISTRY.get(id).map(entry -> entry.spec().fields()).orElse(Set.of());
    }

    /** How to evaluate a task. Empty for an unregistered type, which cannot have decoded. */
    public static Optional<TaskBehaviour<QuestTask>> behaviourOf(QuestTask task) {
        return REGISTRY.get(task.type()).map(Entry::behaviour);
    }

    /**
     * The icon for a task's type, for a listing.
     *
     * <p>Falls back to paper for an unregistered type. That is reachable only from a listing command
     * run against a quest that failed to decode, which the validator has already reported.
     */
    public static ItemRef iconOf(ResourceLocation id) {
        return REGISTRY.get(id).map(Entry::icon).orElse(ItemRef.DEFAULT_ICON);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> TypeSpec<QuestTask> widenSpec(QuestTaskType<T> type) {
        return new TypeSpec<>() {
            @Override
            public ResourceLocation id() {
                return type.id();
            }

            @Override
            public Set<String> fields() {
                return type.fields();
            }

            @Override
            public MapCodec<QuestTask> codec() {
                return type.codec().xmap(task -> (QuestTask) task, task -> (T) task);
            }
        };
    }

    /**
     * Widens a behaviour to the uniform one the engine needs.
     *
     * <p>The cast in the lambdas is sound for the same reason the spec's is: the behaviour travels
     * with the id and the codec, so the only value ever handed to the downcast is one produced by
     * this very codec.
     */
    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> TaskBehaviour<QuestTask> widenBehaviour(TaskBehaviour<T> behaviour) {
        return new TaskBehaviour<>() {
            @Override
            public int required(QuestTask task) {
                return behaviour.required((T) task);
            }

            @Override
            public int current(QuestTask task, dev.ellipog.tasked.quest.TaskContext context) {
                return behaviour.current((T) task, context);
            }

            @Override
            public boolean canSubmitByHand(QuestTask task) {
                return behaviour.canSubmitByHand((T) task);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> Function<QuestTask, TaskDisplay> widenDisplay(Function<T, TaskDisplay> display) {
        // Same soundness argument as the spec and the behaviour: the display travels with the id and
        // the codec, so the only value ever handed to this cast came from that codec.
        return task -> display.apply((T) task);
    }

    private record SimpleQuestTaskType<T extends QuestTask>(
            ResourceLocation id, MapCodec<T> codec, Set<String> fields,
            TaskBehaviour<T> behaviour, ItemRef icon, Function<T, TaskDisplay> display
    ) implements QuestTaskType<T> {

        @Override
        public TaskDisplay display(T task) {
            return display.apply(task);
        }
    }
}
