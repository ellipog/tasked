package dev.ellipog.tasked.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.client.Look;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.net.QuestSync;
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
                            String label, String labelFallback, String itemId) {

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
                // The count as the key's argument, because these keys are written with one:
                // "tasked.reward.xp.points" is "%s XP", and without the argument the row reads "%s XP"
                // -- which is what a reward row did. The fallback needs no argument, since it is
                // already whole English; Minecraft uses it verbatim when the key has no translation.
                return Component.translatableWithFallback(label, labelFallback, count);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /** One reward, resolved ready to draw. */
    public record RewardEntry(ItemStack icon, ItemStack item, int count, String label, String labelFallback,
                              String itemId) {

        public boolean hasItem() {
            return !item.isEmpty();
        }

        public Component text() {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                // The count, for the same reason as the task label above: the key is written with one.
                return Component.translatableWithFallback(label, labelFallback, count);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * One chapter group heading, as the server described it.
     *
     * <p>Arrives in a flat {@code groups[]} at the tree root rather than nested around its chapters,
     * for the same reason a quest carries its own {@code chapterId}: the client groups however it
     * likes, and a nested shape would fix its outline to the server's.
     *
     * <p>{@code collapsedByDefault} is what the tree says the <i>first</i> time this client sees it,
     * and it is the only thing the server has to say about whether a group is open. What the player
     * toggles afterwards is the player's — it lives in the outline, is never written back, and is never
     * written to {@code config/armature/appearance.json}, which is the player's and Armature's file.
     * The two never meet, which is why this is a boolean from the server and a set of keys on the
     * client rather than a field that travels in both directions.
     */
    public record GroupEntry(String id, String title, boolean collapsedByDefault) {
    }

    /**
     * One quest, as the client needs it.
     *
     * <p>{@code shape} is held as the resolved enum rather than the string that arrived, for the same
     * reason the items are resolved on arrival: the screen asks for it per node per frame, and
     * scanning a string per frame to answer the same question is work with no purpose.
     *
     * <p>{@code chapterGroupId} is what puts a quest's chapter under the right heading, and it is the
     * one thing the heading list on its own cannot express — {@code groups[]} says what a heading is
     * called and nothing about what hangs under it. It is <b>empty</b> for a server that predates
     * groups, and that means "no group", which is the case the sidebar has to draw today's flat chapter
     * list for. An empty string is unambiguously not a group id: {@code Checks.id} refuses one, so no
     * real group can collide with the sentinel.
     *
     * <p>It was missing entirely until the shapes were wired up. The field existed in the quest file
     * format, was validated, and was printed by {@code /tasked} — but {@code QuestSync} never put it on
     * the wire, so the client could not have honoured it, and the screen drew and hit-tested a square
     * whatever the file said. A field that is parsed, validated and reported but never consumed reads
     * as supported, which is worse than one that is absent.
     */
    public record Entry(String chapterGroupId, String chapterId, String chapterTitle, String chapterTheme,
                        String id, String title, String subtitle,
                        List<String> description, ItemStack icon, int x, int y, int size, QuestShape shape,
                        double iconScale, boolean showTitle,
                        boolean chapterLinear, int orderInChapter,
                        List<String> dependencies, List<TaskEntry> tasks, List<RewardEntry> rewards,
                        boolean invisible,
                        /**
                         * The icon's id as the server sent it, kept beside the resolved stack: a stack
                         * that failed to resolve with an id that was sent is a <b>missing item</b>, and
                         * the screens say so; an empty id is simply no icon.
                         */
                        String iconId) {
    }

    /**
     * One quest's progress, as the server last reported it.
     *
     * <p>{@code claimable} is what the Claim button hangs on. The server sends it only when a quest is
     * finished with something still to collect, so its absence — including from a server too old to
     * send it — reads as "nothing to claim", which is the direction that cannot show a button that
     * does nothing.
     */
    private record Progress(QuestState state, long cooldown, List<Integer> tasks, boolean claimable,
                            Map<Integer, Map<UUID, Integer>> contributors) {

        /** Who is holding what toward one task, in the order the server named them. Empty for nobody. */
        Map<UUID, Integer> contributorsOf(int taskIndex) {
            return contributors.getOrDefault(taskIndex, Map.of());
        }
    }

    private static volatile List<Entry> entries = List.of();

    /**
     * The group headings, in the order the server declared them.
     *
     * <p>Empty for a server that predates groups, and that is not a case needing its own handling: the
     * sidebar draws the flat chapter list whenever it holds no headings, which is exactly what an older
     * server wants. So absence needs no flag and no branch — see {@link
     * dev.ellipog.tasked.net.QuestSync#treeAsJson}, which sends the key even when it is empty so a
     * version-2 tree is self-describing, and note that nothing here depends on that.
     */
    private static volatile List<GroupEntry> groups = List.of();

    private static volatile Map<String, Progress> progress = Map.of();
    private static volatile UUID teamId;
    private static volatile long syncedAt;
    private static volatile int questCount;
    private static volatile int chapterCount;
    private static volatile boolean treeReceived;

    /**
     * Which tree this cache holds, as a number that only ever increases.
     *
     * <p>Bumped by every path that changes what the cache holds — a tree arriving, and a disconnect
     * clearing it — and by nothing else.
     *
     * <p>What it is for: a screen builds a collapsible outline, and has to be able to tell "the tree
     * is still the one I built my outline from" from "a new one has arrived". A player toggling a
     * group, resizing the window, or scrolling all leave this alone, so the outline keeps the toggles
     * the player chose. A reload — which is a tree arriving — moves it, so the outline is re-seeded
     * from the authored defaults, which is right rather than unfortunate: a reload means the files
     * changed, and the authored state is the honest one for a tree nobody has seen.
     *
     * <p>So the unit is "a tree arrived", not "the tree is different". Comparing contents would answer
     * the same question a second way with its own answer for an identical re-send, and the two
     * descriptions would disagree about whether the player keeps their toggles — with neither being
     * more correct than the other.
     */
    private static volatile long treeRevision;

    /**
     * Which progress this cache holds, as a counter that only ever moves.
     *
     * <h2>Why progress needs one when the tree has one</h2>
     *
     * <p>They are read for different reasons. The tree's revision says whether the <i>rows</i> a screen
     * built are still the rows to draw; this one says whether what those rows are <i>about</i> has
     * moved, which is a different question with a different answer at a different moment — a quest
     * becoming claimable puts a Claim button on the screen, and a panel that only watched the tree
     * would show it for the first time when the player reopened the book.
     *
     * <p>Moved on the message rather than on the contents, like both counters above it: "progress
     * arrived" is a fact about a message, and two messages may describe the same state.
     */
    private static volatile long progressRevision;

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
     * The group headings, in the order the server declared them.
     *
     * <p>Declaration order, not sorted: the server's order is the author's — folder-name order for the
     * folder layout — and a client that sorted would silently reorder somebody's book.
     */
    public static List<GroupEntry> groups() {
        return groups;
    }

    /**
     * Which tree this cache holds. See the field's own note for why a caller compares it.
     *
     * <p>Read by a screen to decide whether the outline it built is still the one to draw. Only
     * equality is ever asked of it, so nothing depends on the absolute value; the reason it never
     * decreases is that a caller might remember it across a clear, and the one thing that must not
     * happen is a remembered value matching a later, different tree.
     */
    public static long treeRevision() {
        return treeRevision;
    }

    /** Which progress this cache holds. See the field's note for why a screen watches it. */
    public static long progressRevision() {
        return progressRevision;
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
    /**
     * Who is holding what toward one task, in the order the server named them.
     *
     * <p>Empty for a quest this client has no progress for, for a task nobody is carrying anything
     * toward, and for every task of a server that predates the field — one answer for all three, and
     * the safe one: no faces drawn rather than a wrong name beside a task.
     */
    public static Map<UUID, Integer> contributorsOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        return found == null ? Map.of() : found.contributorsOf(taskIndex);
    }

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
        // Moved before anything is parsed, and that is deliberate: the revision says *which tree this
        // cache holds*, and every path out of this method changes that. A parsed tree replaces what was
        // there; a tree that could not be read empties it. A revision that only moved on success would
        // leave a screen drawing the rows of a tree the cache has just thrown away.
        treeRevision++;

        try {
            parseTree(new String(json, StandardCharsets.UTF_8));
            questCount = quests;
            chapterCount = chapters;
            treeReceived = true;

            // The pack's main theme, applied before anything is drawn from this tree. It only takes
            // effect for a player who has never chosen a theme of their own -- `ClientAppearance.LOOK.main`
            // decides that, and this class has no business knowing the rule. Null rather than "leave it
            // alone" when the tree carries none: a server that stops sending one must stop influencing
            // the client, or a player would carry one pack's look onto the next server with nothing on
            // screen to explain it.
            ClientAppearance.LOOK.setServerDefault(packTheme);
            Constants.LOG.info("tasked: received {} quest(s) in {} chapter(s)", quests, chapters);
        }
        catch (RuntimeException e) {
            // A malformed tree is a bug in the serialiser, not the player's problem. Cleared rather
            // than half-kept, so the screen shows "no quests" instead of a list with entries missing
            // in the middle and nothing saying why.
            Constants.LOG.error("tasked: the server sent a quest tree this client could not read", e);
            entries = List.of();
            groups = List.of();
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
        groups = List.of();
        progress = Map.of();
        teamId = null;
        questCount = 0;
        chapterCount = 0;
        syncedAt = 0;
        treeReceived = false;
        // Moved rather than left alone, because clearing changes what the cache holds as surely as
        // receiving does: a screen that seeded an outline at the old revision would otherwise keep
        // drawing that tree's rows for a cache that has nothing in it.
        treeRevision++;
        // And the progress counter, for the same reason again: an empty cache is not the progress that
        // was there a moment ago, and a panel holding a Claim button for it is holding a button for a
        // server this client has left.
        progressRevision++;
        // And the pack's theme, for the reason in this method's javadoc: it describes a connection, so
        // leaving it set would show one server's look on the next one -- an appearance nobody chose,
        // with nothing on screen saying where it came from.
        ClientAppearance.LOOK.setServerDefault(null);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private static void parseTree(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // Read, and only warned about. Deliberately not a gate: refusing a version this client does not
        // know would break exactly the case the additive design exists to keep working — a client on an
        // older install drawing today's flat list from a server that has moved on. So a newer tree is
        // drawn with whatever this build understands, and this log line is the only symptom a player
        // will ever get, which is why it names both numbers.
        int version = root.has("version") ? root.get("version").getAsInt() : 1;
        if (version > QuestSync.TREE_VERSION) {
            Constants.LOG.warn("tasked: the server sent a version {} quest tree and this client"
                    + " understands up to version {}. Anything it added will not be drawn.", version,
                    QuestSync.TREE_VERSION);
        }

        // The group headings, when the server sent any. Absent means a server older than groups, so
        // this defaults rather than requires and that tree draws the flat list it always drew.
        //
        // No `version` test guards this, deliberately: the key's presence is the fact. Testing a number
        // to decide whether a key is there would be a second way to find out something already known,
        // and the two would disagree the first time a server sent one without the other.
        List<GroupEntry> parsedGroups = new ArrayList<>();
        if (root.has("groups")) {
            for (JsonElement element : root.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                parsedGroups.add(new GroupEntry(
                        str(group, "id"),
                        str(group, "title"),
                        group.has("collapsedByDefault") && group.get("collapsedByDefault").getAsBoolean()));
            }
        }

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
            // The ends of the prose are not content -- see `Prose`. Trimmed where the tree is parsed, so a
            // reader's card and the editor's copy of the same file agree about where the prose stops.
            description = new ArrayList<>(Prose.trimmed(description));

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
                    // Empty for a server that predates groups, which the sidebar reads as "no group"
                    // and answers by drawing the flat chapter list.
                    str(quest, "chapterGroupId"),
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
                    stack(str(quest, "icon"), 1, quest.get("iconComponents")),
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
                    quest.has("invisible") && quest.get("invisible").getAsBoolean(),
                    str(quest, "icon")));
        }
        entries = List.copyOf(parsed);
        groups = List.copyOf(parsedGroups);
    }

    private static TaskEntry taskEntry(JsonObject json) {
        return new TaskEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                json.has("optional") && json.get("optional").getAsBoolean(),
                json.has("manual") && json.get("manual").getAsBoolean(),
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "item"));
    }

    private static RewardEntry rewardEntry(JsonObject json) {
        return new RewardEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "item"));
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

                // Who is holding what, by task position. Absent for a task nobody is carrying anything
                // toward, and for every task of a server that predates the field -- so its absence is
                // simply "no faces to draw", which is the direction that cannot show a wrong name.
                Map<Integer, Map<UUID, Integer>> contributors = new LinkedHashMap<>();
                if (one.has("who")) {
                    for (Map.Entry<String, JsonElement> task : one.getAsJsonObject("who").entrySet()) {
                        int index = taskIndex(task.getKey());
                        if (index < 0) {
                            continue;
                        }
                        Map<UUID, Integer> picture = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> held : task.getValue().getAsJsonObject().entrySet()) {
                            UUID who = memberId(held.getKey());
                            if (who != null) {
                                picture.put(who, held.getValue().getAsInt());
                            }
                        }
                        if (!picture.isEmpty()) {
                            contributors.put(index, Map.copyOf(picture));
                        }
                    }
                }

                next.put(entry.getKey(), new Progress(
                        readState(str(one, "state")),
                        one.has("cooldown") ? one.get("cooldown").getAsLong() : 0L,
                        List.copyOf(tasks),
                        one.has("claimable") && one.get("claimable").getAsBoolean(),
                        Map.copyOf(contributors)));
            }
        }
        progress = Map.copyOf(next);
        progressRevision++;
    }

    /** A task position from the wire, or -1 for one that is not a position. */
    private static int taskIndex(String raw) {
        try {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A member's id from the wire, or null for one this client cannot read. */
    private static UUID memberId(String raw) {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
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
        return stack(id, count, null);
    }

    /**
     * The stack, with the server's component patch applied when one travelled.
     *
     * <p>An id with no item behind it resolves to {@link ItemStack#EMPTY} -- a missing mod -- and the
     * callers keep the id they were given beside the stack, which is how a row can say the item is
     * missing instead of drawing nothing.
     */
    private static ItemStack stack(String id, int count, com.google.gson.JsonElement components) {
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
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        if (components != null && components.isJsonObject()) {
            net.minecraft.core.component.DataComponentPatch.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, components)
                    .result().ifPresent(stack::applyComponents);
        }
        return stack;
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
