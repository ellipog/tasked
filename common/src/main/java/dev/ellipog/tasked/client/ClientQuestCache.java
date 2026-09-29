package dev.ellipog.tasked.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.client.Appearance;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.QuestLayout;
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
    public record Entry(String chapterId, String chapterTitle, String chapterTheme, String id, String title,
                        String subtitle,
                        List<String> description, ItemStack icon, int x, int y, int size, QuestShape shape,
                        double iconScale, boolean showTitle,
                        boolean chapterLinear, int orderInChapter,
                        List<String> dependencies, List<TaskEntry> tasks, List<RewardEntry> rewards,
                        boolean invisible) {
    }

    /**
     * One quest's progress, as the server last reported it.
     *
     * <p>{@code claimable} is what the Claim button hangs on. The server sends it only when a quest is
     * finished with something still to collect, so its absence — including from a server too old to
     * send it — reads as "nothing to claim", which is the direction that cannot show a button that
     * does nothing.
     */
    private record Progress(QuestState state, long cooldown, List<Integer> tasks, boolean claimable) {
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

    /**
     * The theme a chapter asks to be drawn in, or null when it has no opinion.
     *
     * <p>Null rather than the default theme's name, and that is not pedantry: this is what the screen
     * hands to the override, and an override of "modern" would beat a player who has chosen "tome" —
     * which is the exact bug the two-tier design exists to prevent. Absence has to survive the trip
     * so that "no opinion" and "the default" stay distinguishable.
     *
     * <p>Looks the chapter up in the entries rather than in a map of its own: the theme arrives on
     * every quest of the chapter, so a second structure would be a second thing to keep in step with
     * this one, and the entry list is the one the whole screen already walks.
     */
    public static String chapterTheme(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterTheme().isEmpty() ? null : entry.chapterTheme();
            }
        }
        return null;
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
     * Whether this quest is finished with rewards the player has not collected.
     *
     * <p>The client's copy of the answer, and the reason the Claim button can be drawn at all: it is
     * asked on arrival rather than on every frame, and the server recomputes the same thing when the
     * claim arrives. Asking is not claiming — a client that shows the button wrongly gets a refusal.
     */
    public static boolean canClaim(String questId) {
        Progress found = progress.get(questId);
        return found != null && found.claimable();
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

    /**
     * A tree that carries no pack theme.
     *
     * <p>An overload rather than a {@code null} at each call site, and the reason is what a bare
     * {@code null} third argument reads like: {@code acceptTree(2, 1, null, json)} says nothing about
     * what is absent, and there are seventeen callers that mean "no theme" — every one of them a test
     * asserting on the tree's contents rather than on the connection's appearance.
     *
     * <p><b>A production caller with a theme must use the four-argument form.</b> This exists for the
     * case where there is no theme to pass, not as the convenient path; a wire handler that reached
     * for it would silently stop applying a pack's look, which is the kind of omission that shows up
     * as "the pack's theme works on my machine".
     */
    public static void acceptTree(int quests, int chapters, byte[] json) {
        acceptTree(quests, chapters, null, json);
    }

    /** Called from the payload handler on the client thread. */
    public static void acceptTree(int quests, int chapters, String packTheme, byte[] json) {
        try {
            parseTree(new String(json, StandardCharsets.UTF_8));
            questCount = quests;
            chapterCount = chapters;
            treeReceived = true;

            // The pack's main theme, applied before anything is drawn from this tree. It only takes
            // effect for a player who has never chosen a theme of their own -- `Appearance.main`
            // decides that, and this class has no business knowing the rule. Null rather than "leave it
            // alone" when the tree carries none: a server that stops sending one must stop influencing
            // the client, or a player would carry one pack's look onto the next server with nothing on
            // screen to explain it.
            Appearance.setServerDefault(packTheme);
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
     * A full progress sync. Called from the payload handler on the client thread.
     *
     * <p>The short form, kept because a full sync is what most callers mean and what every test
     * written before deltas existed passes. It delegates rather than duplicating: the two paths must
     * not be able to disagree about what "full" does.
     *
     * @param clientTickNow the client's tick count, so cooldowns can be counted down from here
     */
    public static void acceptProgress(UUID incomingTeam, long gameTime, byte[] json, long clientTickNow) {
        acceptProgress(incomingTeam, gameTime, json, clientTickNow, true);
    }

    /**
     * A progress message — full or a delta.
     *
     * <h2>A delta with no full sync behind it is refused, not applied</h2>
     *
     * <p>This is the one refusal in this class, and it is here because applying such a delta produces
     * a cache holding a handful of quests and everything else {@code LOCKED} — which looks exactly
     * like a working sync of a very small pack. There is no symptom that points at the cause, so the
     * failure has to be prevented rather than diagnosed: the server sends a full sync on join and on
     * reload, so a client that has none has missed something, and the honest response is to keep what
     * it has and wait.
     *
     * <p>The check is the team id rather than whether anything is held. A delta for a team this client
     * has never heard of cannot be relative to anything; a delta for the team it already holds can, and
     * that includes the case where the server's questline is empty.
     *
     * @param full whether the server said this message is the whole of its progress
     */
    public static void acceptProgress(UUID incomingTeam, long gameTime, byte[] json, long clientTickNow,
                                      boolean full) {
        UUID previous = teamId;

        if (full) {
            // Replaced, not merged. A full sync is the server saying "this is all of it", and merging
            // would leave a quest the server has since removed sitting in the cache forever.
            progress = Map.of();
        }
        else if (previous == null || !previous.equals(incomingTeam)) {
            Constants.LOG.warn("tasked: refused a progress delta for team {} -- this client holds no "
                    + "full sync for it (it holds {}), so there is nothing for the delta to be relative "
                    + "to. The server sends a full sync on join and on reload.",
                    incomingTeam, previous);
            return;
        }

        if (previous != null && !previous.equals(incomingTeam)) {
            Constants.LOG.info("tasked: progress is now for team {} (was {})", incomingTeam, previous);
        }
        teamId = incomingTeam;

        try {
            parseProgress(new String(json, StandardCharsets.UTF_8), full);
            syncedAt = clientTickNow;
        }
        catch (RuntimeException e) {
            Constants.LOG.error("tasked: the server sent progress this client could not read", e);
            progress = Map.of();
            // The team is forgotten too, and that is the important half: leaving it set would make the
            // *next* delta look applicable, and it would be applied onto the empty map this catch just
            // left behind. Clearing it means the next message has to be a full sync to be accepted,
            // which is the correct resynchronisation.
            teamId = null;
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
        // And the pack's theme, for the reason in this method's javadoc: it describes a connection, so
        // leaving it set would show one server's look on the next one -- an appearance nobody chose,
        // with nothing on screen saying where it came from.
        Appearance.setServerDefault(null);
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
                    // A chapter asking for a theme of its own, or "" for one that has no opinion.
                    // Read into the entry rather than into a map of chapter to theme, because it
                    // arrives on every quest of the chapter and a second structure keyed by chapter
                    // would be a second thing to keep in step with the first.
                    str(quest, "chapterTheme"),
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
                    // Clamped here as well as in the codec, and this is not belt-and-braces: the codec
                    // ran on the *server*, over a file that server had. What arrives is a number from
                    // possibly a different version, and the screen must not draw outside its node
                    // because of one. Same reasoning as QuestShape.span clamping its own output.
                    quest.has("iconScale")
                            ? Math.min(Math.max(quest.get("iconScale").getAsDouble(),
                                    QuestShape.MIN_ICON_SCALE), QuestShape.MAX_ICON_SCALE)
                            : QuestLayout.DEFAULT_ICON_SCALE,
                    quest.has("showTitle") && quest.get("showTitle").getAsBoolean(),
                    quest.has("chapterLinear") && quest.get("chapterLinear").getAsBoolean(),
                    // Defaulted to a large number rather than to zero, so a server too old to send it
                    // cannot claim every quest is the first one in its chapter. A chapter that is not
                    // linear never reads this, and that is the only case an old server can produce.
                    quest.has("order") ? quest.get("order").getAsInt() : Integer.MAX_VALUE,
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

    /**
     * Reads progress into the cache.
     *
     * <h2>{@code removed} is why a delta cannot be inferred from absence</h2>
     *
     * <p>A delta carries only what changed, so a quest missing from it means "unchanged" — which is
     * also what a quest deleted from a server file would look like. The two are indistinguishable
     * from the message alone, so the server names deletions explicitly and the client applies them
     * first. Without that, a quest removed from a file would live on in this cache until the player
     * reconnected: a ghost node on the canvas that cannot be clicked and cannot be explained.
     *
     * @param full whether to start from nothing or from what is already held
     */
    private static void parseProgress(String json, boolean full) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Progress> next = full ? new LinkedHashMap<>() : new LinkedHashMap<>(progress);

        if (root.has("removed")) {
            for (JsonElement gone : root.getAsJsonArray("removed")) {
                next.remove(gone.getAsString());
            }
        }

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
                        List.copyOf(tasks),
                        one.has("claimable") && one.get("claimable").getAsBoolean()));
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
