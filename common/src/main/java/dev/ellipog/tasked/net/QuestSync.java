package dev.ellipog.tasked.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestProgress;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.progress.TeamProgress;
import dev.ellipog.tasked.quest.Chapter;
import dev.ellipog.tasked.quest.ChapterGroup;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.LoadedQuestFile;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestRef;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.QuestTask;
import dev.ellipog.tasked.quest.TaskedQuests;
import dev.ellipog.tasked.quest.reward.RewardDisplay;
import dev.ellipog.tasked.quest.reward.RewardTypes;
import dev.ellipog.tasked.quest.task.TaskDisplay;
import dev.ellipog.tasked.quest.task.TaskTypes;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the server's questline into the flat shape the client needs.
 *
 * <h2>Why a separate shape rather than sending the records</h2>
 *
 * <p>A client needs a title, an icon, a position, and what the tasks ask for. It does not need
 * dependencies as objects, codecs, or reward behaviours, and it cannot act on any of them. Sending
 * the records would put the addon API on both sides of the wire and mean a new task type broke the
 * client.
 *
 * <p>So this is a projection, written by hand, and the hand-writing is the point: it is the list of
 * what the client is allowed to know. Adding a field here is a decision about what crosses the wire,
 * which should not happen by accident because a record gained a component.
 *
 * <h2>The apostrophe problem, and why JSON is the right call for now</h2>
 *
 * <p>Text goes over as UTF-8 JSON. A hand-written binary encoding would be smaller — the plan says so
 * — but it would also be a second serialiser to keep in step, and a format nobody can read in a packet
 * dump while the shape is still moving. Binary is for a later stage, and the plan lists it there.
 */
public final class QuestSync {

    /**
     * What each player was last sent, so the next message can be a delta.
     *
     * <p>Keyed by <b>player</b> rather than by team, because it describes one connection's knowledge:
     * two members of one party are sent the same progress and each has their own history of it. A
     * team-keyed snapshot would be wrong for the second player to join, who has been sent nothing.
     *
     * <p>On the server thread only — every writer is a command handler, a tick callback or a payload
     * handler, all of which the server runs on the server thread — so a plain {@code HashMap} is
     * correct and a concurrent one would be a claim about contention that does not exist.
     */
    private static final Map<UUID, Sent> SENT = new HashMap<>();

    /** One player's last sent state: which team it was for, and the exact JSON sent per quest. */
    private record Sent(UUID teamId, Map<String, String> quests) {
    }

    private QuestSync() {
    }

    /**
     * The quest tree, as JSON.
     *
     * <p>Every quest carries its {@code chapterId} rather than being nested under one, so the client
     * groups however it likes without walking a tree. Nesting would be smaller and would fix the
     * client's layout to the server's, which is backwards — grouping is a display decision.
     *
     * <p>{@code invisible} travels as a flag rather than the quest being withheld. Withholding it is
     * tidier right up until a player completes something and the quest should appear, at which point
     * the whole tree has to be re-sent. Sending the flag keeps the tree immutable for as long as the
     * quest files are.
     */
    public static byte[] treeAsJson(QuestIndex index) {
        JsonArray quests = new JsonArray();

        for (LoadedQuestFile file : index.files()) {
            for (ChapterGroup group : file.file().chapterGroups()) {
                for (Chapter chapter : group.chapters()) {
                    // The quest's index within its chapter, and whether the chapter is linear. Both are
                    // per-quest on the wire even though both are properties of the chapter, because the
                    // client groups entries by `chapterId` and has no chapter record to hang them on.
                    //
                    // They are here rather than worked out on the client because a linear chapter
                    // declares no dependencies at all -- the list order *is* the progression -- so from
                    // the quests alone the client cannot tell a road from three unrelated nodes. It
                    // would draw three unconnected boxes for a chapter that is a sequence, which looks
                    // like a missing feature rather than a missing field.
                    int order = 0;
                    for (Quest quest : chapter.quests()) {
                        quests.add(questAsJson(chapter, quest, order++));
                    }
                }
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        // The pack's main theme, absent when it has none. See `packTheme` for why this rides on the
        // tree rather than in a message of its own, and why it is sent both here and in the payload.
        String theme = packTheme(index);
        if (theme != null) {
            root.addProperty("theme", theme);
        }
        root.add("quests", quests);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The pack's own main theme, or null when it declares none.
     *
     * <h2>Why this rides on the tree rather than in a payload of its own</h2>
     *
     * <p>Because it is a property of the questline, and the tree is what arrives when the questline
     * does. A second message would be a second thing to keep in step with the first, and it would need
     * its own answer to "what happens on a server that sends a theme and no tree" — a question with no
     * good answer, which is usually a sign that two things are one thing.
     *
     * <p>It travels in the payload as well as in the JSON, and the duplication is deliberate: the
     * payload's copy is read before the tree is decompressed, so a client can apply the pack's look on
     * the frame the sync arrives rather than a frame later. Nothing here reads the JSON's copy — it is
     * the tree's own record of how it was drawn, which is what a test can assert on where a payload
     * cannot be inspected without a network.
     *
     * <p><b>This returns null today, and that is a deliberate order rather than an unfinished
     * feature.</b> A quest file cannot declare a pack theme yet: no such field exists in the file
     * model, so there is nothing here to read. The field went in first on the wire because a client
     * that <i>reads</i> a field nobody writes is strictly better than a client that has to be changed
     * on the day the writer arrives — the read path is exercised, the payload codec is exercised, and
     * the only edit left when the model gains the field is the one line below. What it must not become
     * is a field that is parsed, validated and printed while nothing reads it, which is the failure
     * this codebase has recorded three times.
     */
    private static String packTheme(QuestIndex index) {
        // When the file model gains a pack-level theme, this is the single line that changes. Left
        // explicit rather than removed so that the two call sites above cannot drift apart.
        return null;
    }

    private static JsonObject questAsJson(Chapter chapter, Quest quest, int orderInChapter) {
        JsonObject json = new JsonObject();
        json.addProperty("chapterId", chapter.id());
        json.addProperty("chapterTitle", chapter.title().value());

        // A chapter may ask to be drawn in a theme of its own, and that rides on every quest in it
        // for the same reason `chapterLinear` does: the client groups entries by `chapterId` and has
        // no chapter record to hang it on.
        //
        // Sent only when the chapter names one, so absence means "no opinion" rather than "the
        // default". Those read the same today and diverge the moment a player sets their own theme —
        // and losing to an accidental default is exactly the bug this field must not introduce.
        //
        // Unlike `invisible`, an unrecognised name is not dropped here. The client is the side that
        // knows which themes exist, so it is the side that can say so, and it does — once, naming the
        // chapter. Filtering it out at the server would be silently discarding an author's mistake,
        // which is the failure mode this project keeps finding: a field that reads as supported.
        chapter.theme().ifPresent(name -> json.addProperty("chapterTheme", name));
        json.addProperty("id", quest.id());
        json.addProperty("title", quest.title().value());
        quest.subtitle().ifPresent(subtitle -> json.addProperty("subtitle", subtitle.value()));
        json.addProperty("icon", quest.icon().item().toString());
        json.addProperty("x", quest.layout().x());
        json.addProperty("y", quest.layout().y());
        json.addProperty("size", quest.layout().size());
        // Lowercase, because that is the spelling a quest file uses and the spelling the validator
        // accepts. The enum's own name is uppercase, and sending that would make the wire format
        // differ from the file format for no reason a reader could guess -- and `byName` on the client
        // is case-insensitive precisely so this cannot matter.
        json.addProperty("shape", quest.layout().shape().name().toLowerCase(java.util.Locale.ROOT));
        // The icon's size as a share of the node, and whether to draw the name under it. Both are
        // presentation, both are per quest, and both are decisions the server holds and the client
        // honours -- so both have to cross.
        //
        // `showTitle` travelling is the same lesson as `shape` before it: a field the validator accepts
        // and the client never hears about is a field that reads as supported and does nothing.
        json.addProperty("iconScale", quest.layout().iconScale());
        json.addProperty("showTitle", quest.showTitle());
        json.addProperty("invisible", quest.invisible());
        // Lowercase for the same reason `shape` is: a linear chapter is written lowercase, and the enum
        // name is uppercase. `byName` on the client would cope either way; matching the file format
        // means a packet dump reads like a quest file.
        json.addProperty("chapterLinear",
                chapter.progressionMode() == dev.ellipog.tasked.quest.ProgressionMode.LINEAR);
        json.addProperty("order", orderInChapter);

        JsonArray description = new JsonArray();
        for (var paragraph : quest.description()) {
            description.add(paragraph.value());
        }
        json.add("description", description);

        JsonArray dependencies = new JsonArray();
        for (QuestRef dependency : quest.dependencies()) {
            dependencies.add(dependency.id());
        }
        json.add("dependsOn", dependencies);

        JsonArray tasks = new JsonArray();
        for (QuestTask task : quest.tasks()) {
            tasks.add(taskAsJson(task));
        }
        json.add("tasks", tasks);

        JsonArray rewards = new JsonArray();
        for (QuestReward reward : quest.rewards()) {
            rewards.add(rewardAsJson(reward));
        }
        json.add("rewards", rewards);

        return json;
    }

    /**
     * One task, as the client draws it.
     *
     * <p>Three things beyond the display: whether it is optional, whether a player can hand it over
     * rather than have it taken, and how much is required. All three are needed to draw a row that
     * says the right thing — a greyed-out optional task, a button only where a button works, and
     * {@code 5 / 8}.
     */
    private static JsonObject taskAsJson(QuestTask task) {
        TaskDisplay display = TaskTypes.displayOf(task);

        JsonObject json = new JsonObject();
        json.addProperty("type", task.type().toString());
        // The type's own icon, so a task with no item still has something to draw.
        json.addProperty("icon", TaskTypes.iconOf(task.type()).item().toString());
        json.addProperty("item", display.item().map(ref -> ref.item().toString()).orElse(""));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        json.addProperty("optional", task.optional());
        json.addProperty("manual", TaskTypes.behaviourOf(task)
                .map(behaviour -> behaviour.canSubmitByHand(task))
                .orElse(false));
        return json;
    }

    private static JsonObject rewardAsJson(QuestReward reward) {
        RewardDisplay display = RewardTypes.displayOf(reward);

        JsonObject json = new JsonObject();
        json.addProperty("type", reward.type().toString());
        json.addProperty("icon", RewardTypes.iconOf(reward.type()).item().toString());
        json.addProperty("item", display.item().map(ref -> ref.item().toString()).orElse(""));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        return json;
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * One team's progress, as JSON, sending every quest.
     *
     * <p>The full form, and now a caller of the delta form rather than a parallel implementation.
     * Kept because a full sync is what a joining client needs and what a reload needs, and because
     * having one name for "all of it" keeps the delta's own name honest.
     */
    public static byte[] progressAsJson(ProgressionEngine.Resolution resolution,
                                        TeamProgress progress,
                                        QuestIndex index) {
        return progressDelta(resolution, progress, index, null).json();
    }

    /**
     * A JSON message and the snapshot of what was sent in it.
     *
     * <p>Two things come out of one call because they cannot be allowed to disagree. The caller stores
     * the snapshot so the next call can compute a delta, and if the snapshot described anything other
     * than the bytes that went out, the next delta would be computed against a fiction — reporting a
     * quest as unchanged that the client has never seen, or as changed on every tick forever.
     *
     * @param json     the bytes to send
     * @param snapshot every quest id and the exact JSON sent for it
     */
    public record Delta(byte[] json, Map<String, String> snapshot) {
    }

    /**
     * One team's progress as JSON, sending only what differs from {@code previous}.
     *
     * <h2>The defect this fixes</h2>
     *
     * <p>Before this, every progress message carried the whole questline and the client replaced its
     * whole map. A quest's state is recomputed on every player tick, so on any tick where something
     * moved, the entire tree crossed the wire to every member of the team — and the more content a pack
     * has, the more it cost, for a change that was almost always one quest's task counter.
     *
     * <p>{@code previous == null} means a full sync, and that is the only way to get one. So the only
     * caller that can produce a full is one that has no snapshot, which is exactly the set of callers
     * that need one: a join, a reconnect, a reload, and a player whose party changed.
     *
     * <h2>What it costs, stated rather than implied</h2>
     *
     * <p>The <b>wire</b> shrinks and the <b>CPU does not</b>. Every quest is still serialised on every
     * call, because the serialised form is what the comparison is made of — there is no cheaper way to
     * ask "is this quest's resolved state different from what I sent" without keeping a parallel
     * structure of resolved states per player, and that structure would be a second description of the
     * same thing. So this is a bandwidth optimisation for large packs, not a general one, and
     * pretending otherwise would be the kind of claim that gets remembered wrongly.
     *
     * <h2>Removals are named, because absence cannot mean removed</h2>
     *
     * <p>A delta says "these quests are now thus". It cannot say "this quest is gone" by omitting it,
     * because omission is also what an unchanged quest looks like. So ids that were in the previous
     * snapshot and are not in the index are listed explicitly under {@code removed}, and the client
     * deletes them. Without it, a quest deleted from a file would live on in the client's cache until
     * the player reconnected — a ghost node on the canvas that cannot be clicked and cannot be
     * explained.
     *
     * @param previous the snapshot from the last message this player was sent, or null for a full sync
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous) {
        JsonObject changed = new JsonObject();
        Map<String, String> snapshot = new LinkedHashMap<>();

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            String encoded = oneQuestAsJson(resolution, progress, quest);

            snapshot.put(quest.id(), encoded);
            if (previous == null || !encoded.equals(previous.get(quest.id()))) {
                changed.add(quest.id(), JsonParser.parseString(encoded));
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("quests", changed);

        if (previous != null) {
            JsonArray removed = new JsonArray();
            for (String id : previous.keySet()) {
                if (!snapshot.containsKey(id)) {
                    removed.add(id);
                }
            }
            if (!removed.isEmpty()) {
                root.add("removed", removed);
            }
        }

        return new Delta(root.toString().getBytes(StandardCharsets.UTF_8), Map.copyOf(snapshot));
    }

    /**
     * One quest's resolved state, as a JSON object's text.
     *
     * <p>Serialised to a string and parsed back by the caller, which looks wasteful and is the point:
     * the string is what the delta comparison is made of, so a quest whose text is byte-identical to
     * what was sent is provably unchanged. Building the {@code JsonObject} directly and comparing
     * those would compare object identity, and two equal-looking trees would read as different on
     * every tick — the delta would send everything and the whole exercise would be a no-op that
     * looked like it worked.
     *
     * <h2>Key order is therefore load-bearing, and stable by construction</h2>
     *
     * <p>{@code JsonObject} preserves insertion order, so the same input produces the same text. If
     * that ever stopped being true the failure would be a delta that always sends everything, which
     * is exactly the behaviour this replaced — so it would look like the optimisation had never been
     * made rather than like a bug.
     */
    private static String oneQuestAsJson(ProgressionEngine.Resolution resolution,
                                         TeamProgress progress,
                                         Quest quest) {
        QuestProgress stored = progress.progressOf(quest);

        JsonObject one = new JsonObject();
        one.addProperty("state", resolution.stateOf(quest).name());

        // Whether the quest is finished with something still to collect.
        //
        // Sent only when true, like the cooldown below, so the field's absence means "nothing to
        // claim" rather than "an older server did not know about this" -- which is the opposite of
        // the default the other fields take. Deliberate: a client that invented a Claim button for
        // a quest with nothing waiting would send a claim the server refuses, and the player would
        // be shown a button that does nothing. Absent is the safe reading in both directions here.
        //
        // Without this the button cannot exist. `rewardsClaimed` is on QuestProgress and this
        // method did not put it on the wire, which is the same shape as `shape` never travelling:
        // a field the engine records, the command prints, and no client ever hears about.
        if (ProgressService.canClaim(progress, quest)) {
            one.addProperty("claimable", true);
        }

        long remaining = resolution.cooldownOf(quest);
        if (remaining > 0) {
            one.addProperty("cooldown", remaining);
        }

        // Per-task progress, indexed by task position -- the same keying QuestProgress uses, and it
        // carries the same limitation: reordering a quest's tasks moves progress to a different task.
        // Sent anyway because "you have 5 of 8 logs" is the single most useful line in a quest book,
        // and a book that cannot show it is not worth opening.
        JsonArray tasks = new JsonArray();
        for (int i = 0; i < quest.tasks().size(); i++) {
            tasks.add(stored.progressOf(i));
        }
        one.add("tasks", tasks);

        return one.toString();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Sends the tree to one player, compressed and chunked. Called on join, and after a reload.
     *
     * <p>Not a delta, and it does not need to be: the tree is immutable for as long as the quest files
     * are, so there is nothing for a delta to describe. A reload re-sends all of it, and the client
     * replaces what it had — which is correct rather than lazy, because a reload can remove a quest and
     * a tree delta would have to carry removals to say so.
     */
    public static void sendTreeTo(ServerPlayer player, QuestIndex index) {
        byte[] packed = SyncWire.pack(treeAsJson(index));
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            send(player, new QuestSyncPayload(
                    index.questCount(),
                    index.chapterCount(),
                    // Read from the same place `treeAsJson` does, so the payload's copy and the tree's
                    // copy cannot disagree -- which they would the moment one of the two was changed.
                    packTheme(index),
                    new SyncChunk(transferId, i, parts.size(), true),
                    parts.get(i)));
        }

        if (parts.size() > 1) {
            Constants.LOG.debug("tasked: sent the tree for {} quest(s) in {} chunk(s)",
                    index.questCount(), parts.size());
        }
    }

    /**
     * Sends one player their team's current progress — a full sync or a delta, as appropriate.
     *
     * <p>The single place progress is pushed from, so every caller gets the same resolution, the same
     * chunking and the same snapshot bookkeeping. Called on join, on a change, on a reload, and when a
     * party changes — all of which want the same thing with a different reason.
     *
     * <h2>When it sends a full sync rather than a delta</h2>
     *
     * <p>Three cases, and each one is a case where a delta would be applied onto nothing:
     *
     * <ul>
     *   <li><b>No snapshot.</b> Nothing has been sent to this player since they joined, so there is
     *       nothing for a delta to be relative to.</li>
     *   <li><b>The reason says so.</b> A join or a reload is a request for the whole thing — the
     *       client has cleared, or the tree under it has changed.</li>
     *   <li><b>The team changed.</b> A player who left a party has a client holding the previous
     *       team's progress. A delta would merge one party's questline onto another's, which produces
     *       a cache that is a mixture of two and looks like neither.</li>
     * </ul>
     *
     * <p>The team check is why the snapshot stores the team id rather than only the quests: a party
     * change is not an event this class is told about, and it does not need to be, because the next
     * call can see it by comparison.
     */
    public static void sendProgress(MinecraftServer server, ServerPlayer player, int reason) {
        QuestIndex index = TaskedQuests.index();
        if (index.isEmpty()) {
            return;
        }

        UUID owner = ProgressService.progressOwner(server, player);
        long gameTime = server.overworld().getGameTime();
        Sent last = SENT.get(player.getUUID());

        boolean full = last == null
                || !last.teamId().equals(owner)
                || reason == ProgressSyncPayload.REASON_JOIN
                || reason == ProgressSyncPayload.REASON_RELOAD;

        TeamProgress progress = ProgressService.progressFor(server, owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, progress, gameTime);

        Delta delta = progressDelta(resolution, progress, index, full ? null : last.quests());
        SENT.put(player.getUUID(), new Sent(owner, delta.snapshot()));

        byte[] packed = SyncWire.pack(delta.json());
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            send(player, new ProgressSyncPayload(
                    owner,
                    gameTime,
                    new SyncChunk(transferId, i, parts.size(), full),
                    parts.get(i),
                    reason));
        }
    }

    /**
     * Sends the tree and this player's progress together — what a join needs.
     *
     * <p>The snapshot is forgotten first, which is what makes a rejoin a full sync. Without it, a
     * player who disconnects and reconnects with the client having cleared its cache would be sent a
     * delta against progress it no longer has — and the result is a book showing a handful of quests
     * and every other one locked, which looks exactly like a working sync of a very small pack.
     */
    public static void sendEverythingTo(ServerPlayer player, MinecraftServer server) {
        QuestIndex index = TaskedQuests.index();
        forget(player.getUUID());

        // The tree is sent even when it is empty, so the client can tell "nothing loaded" from
        // "nothing received" and say the right one of those to a player.
        sendTreeTo(player, index);
        if (!index.isEmpty()) {
            sendProgress(server, player, ProgressSyncPayload.REASON_JOIN);
        }
    }

    /** Forgets what a player was last sent, so their next sync is a full one. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }

    /** Forgets every snapshot. For a reload, where the tree under them has changed. */
    public static void forgetAll() {
        SENT.clear();
    }

    /** How many players have a snapshot. Diagnostics, and a test asserts it stops growing. */
    public static int snapshotCount() {
        return SENT.size();
    }

    /** Sends progress to every member of a team, for a change one of them caused. */
    public static void sendProgressToTeam(MinecraftServer server,
                                          Collection<ServerPlayer> members,
                                          int reason) {
        for (ServerPlayer member : members) {
            sendProgress(server, member, reason);
        }
    }

    /**
     * Sends one payload, tolerating a player who has gone.
     *
     * <p>The check is not defensive noise. A sync is produced by several paths that run on a tick —
     * the engine reporting a change, a team membership change, a command — and a player can disconnect
     * between the moment a change is noticed and the moment it is sent. Sending to a disconnected
     * player is a no-op on Fabric and throws on NeoForge, which is the worst combination: it works on
     * one loader and breaks the other, from the same source file.
     */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player, payload);
    }
}
