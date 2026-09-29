package dev.ellipog.tasked.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.QuestShape;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the client knows about the questline, without a server.
 *
 * <h2>Why the client does not just read the quest files itself</h2>
 *
 * <p>It has them on disk. In a single-player world it could parse them directly and it would work. On
 * a multiplayer server it would not — the client has whatever files <i>it</i> has, which may be none,
 * may be an older version, and on a server running a datapack is guaranteed to be the wrong one. Even
 * in single player, a client reading its own copy would be judging itself against a different snapshot
 * from the server's.
 *
 * <p>So the server is the only authority, and this is a projection of what it said.
 *
 * <h2>Everything here runs on the client thread</h2>
 *
 * <p>Written from a payload handler, read from a screen, and both of those are the client thread —
 * that is where the loaders deliver payloads. So no synchronisation is needed and adding any would be
 * a lie about a contention that does not exist. The volatile fields are for the one moment that is
 * genuinely cross-thread: a disconnect clearing the whole thing.
 */
public final class ClientQuestCache {

    private ClientQuestCache() {
    }

    /**
     * One task, resolved ready to draw.
     *
     * <p>The item is resolved here, on arrival, rather than on every frame. A registry lookup per node
     * per frame for a screen showing eighty quests is work with no purpose: registries do not change
     * while a client is connected, so a resolution can be made once and kept.
     */
    public record TaskEntry(ItemStack icon, ItemStack item, int count, boolean optional, boolean manual,
                            String label, String labelFallback) {

        /** Whether this row draws an item at all, as opposed to text. */
        public boolean hasItem() {
            return !item.isEmpty();
        }

        /**
         * The text for this row.
         *
         * <p>A translatable label when one was sent with a fallback, so a pack can translate its own
         * checkmark titles; the item's own name when there is an item; the literal text otherwise.
         */
        public Component text() {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return Component.translatableWithFallback(label, labelFallback);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /** One reward, resolved ready to draw. */
    public record RewardEntry(ItemStack icon, ItemStack item, int count, String label, String labelFallback) {

        public boolean hasItem() {
            return !item.isEmpty();
        }

        public Component text() {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return Component.translatableWithFallback(label, labelFallback);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * One quest, as the client needs it.
     *
     * <p>{@code shape} is held as the resolved enum rather than the string that arrived, for the same
     * reason the items are resolved on arrival: the screen asks for it per node per frame, and
     * scanning a string per frame to answer the same question is work with no purpose.
     *
     * <p>It was missing entirely until the shapes were wired up. The field existed in the quest file
     * format, was validated, and was printed by {@code /tasked} — but {@code QuestSync} never put it on
     * the wire, so the client could not have honoured it, and the screen drew and hit-tested a square
     * whatever the file said. A field that is parsed, validated and reported but never consumed reads
     * as supported, which is worse than one that is absent.
     */
    public record Entry(String chapterId, String chapterTitle, String id, String title, String subtitle,
                        List<String> description, ItemStack icon, int x, int y, int size, QuestShape shape,
                        List<String> dependencies, List<TaskEntry> tasks, List<RewardEntry> rewards,
                        boolean invisible) {
    }

    /** One quest's progress, as the server last reported it. */
    private record Progress(QuestState state, long cooldown, List<Integer> tasks) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile Map<String, Progress> progress = Map.of();
    private static volatile UUID teamId;
    private static volatile long syncedAt;
    private static volatile int questCount;
    private static volatile int chapterCount;
    private static volatile boolean treeReceived;

    /** Whether the tree has arrived — even an empty one. */
    public static boolean hasTree() {
        return treeReceived;
    }

    /**
     * Whether there is anything to show.
     *
     * <p>Deliberately distinct from {@link #hasTree()}: "waiting for the server" is a moment, "this
     * server has no quests" is a state, and telling someone the first when it is the second sends them
     * hunting a sync bug that does not exist.
     */
    public static boolean hasData() {
        return treeReceived && !entries.isEmpty();
    }

    public static List<Entry> entries() {
        return entries;
    }

    public static int questCount() {
        return questCount;
    }

    public static int chapterCount() {
        return chapterCount;
    }

    public static long syncedAt() {
        return syncedAt;
    }

    public static Optional<UUID> teamId() {
        return Optional.ofNullable(teamId);
    }

    /** The state of a quest. LOCKED for anything unknown — which is what a missing quest should look like. */
    public static QuestState stateOf(String questId) {
        Progress found = progress.get(questId);
        return found == null ? QuestState.LOCKED : found.state();
    }

    /** How far along a task is, as the server last reported. Zero for anything unknown. */
    public static int taskProgressOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        if (found == null || taskIndex < 0 || taskIndex >= found.tasks().size()) {
            return 0;
        }
        return found.tasks().get(taskIndex);
    }

    /**
     * Ticks of cooldown left for a quest, adjusted for the time since the sync arrived.
     *
     * <p>Adjusted rather than sent live, because a cooldown is a countdown and counting it down from a
     * known point costs one subtraction per frame instead of a packet per second. The client's tick
     * counter and the server's are different counters, so what gets subtracted is the client ticks
     * elapsed since the sync — close enough to display, and corrected by the next sync anyway.
     */
    public static long cooldownOf(String questId, long clientTickNow) {
        Progress found = progress.get(questId);
        if (found == null || found.cooldown() <= 0) {
            return 0L;
        }
        long elapsed = Math.max(0L, clientTickNow - syncedAt);
        return Math.max(0L, found.cooldown() - elapsed);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Called from the payload handler on the client thread. */
    public static void acceptTree(int quests, int chapters, byte[] json) {
        try {
            parseTree(new String(json, StandardCharsets.UTF_8));
            questCount = quests;
            chapterCount = chapters;
            treeReceived = true;
            Constants.LOG.info("tasked: received {} quest(s) in {} chapter(s)", quests, chapters);
        }
        catch (RuntimeException e) {
            // A malformed tree is a bug in the serialiser, not the player's problem. Cleared rather
            // than half-kept, so the screen shows "no quests" instead of a list with entries missing
            // in the middle and nothing saying why.
            Constants.LOG.error("tasked: the server sent a quest tree this client could not read", e);
            entries = List.of();
            treeReceived = false;
        }
    }

    /**
     * Called from the payload handler on the client thread.
     *
     * @param clientTickNow the client's tick count, so cooldowns can be counted down from here
     */
    public static void acceptProgress(UUID incomingTeam, long gameTime, byte[] json, long clientTickNow) {
        if (teamId != null && !teamId.equals(incomingTeam)) {
            Constants.LOG.info("tasked: progress is now for team {} (was {})", incomingTeam, teamId);
        }
        teamId = incomingTeam;

        try {
            parseProgress(new String(json, StandardCharsets.UTF_8));
            syncedAt = clientTickNow;
        }
        catch (RuntimeException e) {
            Constants.LOG.error("tasked: the server sent progress this client could not read", e);
            progress = Map.of();
        }
    }

    /**
     * Forgets everything.
     *
     * <p>Called on disconnect. Without it, leaving one server and joining another shows the first
     * server's questline until the new sync arrives — which looks exactly like a sync failure and is
     * not one, so it sends you looking in the wrong place.
     */
    public static void clear() {
        entries = List.of();
        progress = Map.of();
        teamId = null;
        questCount = 0;
        chapterCount = 0;
        syncedAt = 0;
        treeReceived = false;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static void parseTree(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray quests = root.getAsJsonArray("quests");

        List<Entry> parsed = new ArrayList<>(quests.size());
        for (JsonElement element : quests) {
            JsonObject quest = element.getAsJsonObject();

            List<String> description = new ArrayList<>();
            if (quest.has("description")) {
                for (JsonElement paragraph : quest.getAsJsonArray("description")) {
                    description.add(paragraph.getAsString());
                }
            }

            List<String> dependencies = new ArrayList<>();
            if (quest.has("dependsOn")) {
                for (JsonElement dependency : quest.getAsJsonArray("dependsOn")) {
                    dependencies.add(dependency.getAsString());
                }
            }

            List<TaskEntry> tasks = new ArrayList<>();
            if (quest.has("tasks")) {
                for (JsonElement task : quest.getAsJsonArray("tasks")) {
                    tasks.add(taskEntry(task.getAsJsonObject()));
                }
            }

            List<RewardEntry> rewards = new ArrayList<>();
            if (quest.has("rewards")) {
                for (JsonElement reward : quest.getAsJsonArray("rewards")) {
                    rewards.add(rewardEntry(reward.getAsJsonObject()));
                }
            }

            parsed.add(new Entry(
                    str(quest, "chapterId"),
                    str(quest, "chapterTitle"),
                    str(quest, "id"),
                    str(quest, "title"),
                    str(quest, "subtitle"),
                    List.copyOf(description),
                    stack(str(quest, "icon"), 1),
                    quest.has("x") ? quest.get("x").getAsInt() : 0,
                    quest.has("y") ? quest.get("y").getAsInt() : 0,
                    quest.has("size") ? quest.get("size").getAsInt() : 48,
                    // Resolved with a fallback rather than by valueOf, so a server running a newer
                    // version that names a shape this client has never heard of draws a square
                    // instead of throwing while a player waits for a screen.
                    QuestShape.byName(str(quest, "shape"), QuestShape.ROUNDED),
                    List.copyOf(dependencies),
                    List.copyOf(tasks),
                    List.copyOf(rewards),
                    quest.has("invisible") && quest.get("invisible").getAsBoolean()));
        }
        entries = List.copyOf(parsed);
    }

    private static TaskEntry taskEntry(JsonObject json) {
        return new TaskEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1),
                json.has("count") ? json.get("count").getAsInt() : 1,
                json.has("optional") && json.get("optional").getAsBoolean(),
                json.has("manual") && json.get("manual").getAsBoolean(),
                str(json, "label"),
                str(json, "labelFallback"));
    }

    private static RewardEntry rewardEntry(JsonObject json) {
        return new RewardEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1),
                json.has("count") ? json.get("count").getAsInt() : 1,
                str(json, "label"),
                str(json, "labelFallback"));
    }

    private static void parseProgress(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Progress> next = new LinkedHashMap<>();

        if (root.has("quests")) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("quests").entrySet()) {
                JsonObject one = entry.getValue().getAsJsonObject();

                List<Integer> tasks = new ArrayList<>();
                if (one.has("tasks")) {
                    for (JsonElement value : one.getAsJsonArray("tasks")) {
                        tasks.add(value.getAsInt());
                    }
                }

                next.put(entry.getKey(), new Progress(
                        readState(str(one, "state")),
                        one.has("cooldown") ? one.get("cooldown").getAsLong() : 0L,
                        List.copyOf(tasks)));
            }
        }
        progress = Map.copyOf(next);
    }

    private static QuestState readState(String raw) {
        try {
            return QuestState.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            // A state this client does not know means the client and server are different versions.
            // LOCKED is the safe reading and the honest one: the client cannot show progress it does
            // not understand, and showing it as complete would be a lie in the more dangerous
            // direction.
            return QuestState.LOCKED;
        }
    }

    /**
     * Resolves an item id to a stack, for drawing.
     *
     * <p>An empty stack for anything unknown, which renders as nothing rather than as a crash. A
     * client missing a mod the server has is a normal situation, not an error — and the alternative,
     * throwing inside a payload handler, disconnects the player over a missing icon.
     */
    private static ItemStack stack(String id, int count) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, Math.max(1, count));
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
