package dev.ellipog.tasked.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
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
                    for (Quest quest : chapter.quests()) {
                        quests.add(questAsJson(chapter, quest));
                    }
                }
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("quests", quests);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject questAsJson(Chapter chapter, Quest quest) {
        JsonObject json = new JsonObject();
        json.addProperty("chapterId", chapter.id());
        json.addProperty("chapterTitle", chapter.title().value());
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
        json.addProperty("invisible", quest.invisible());

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
     * One team's progress, as JSON.
     *
     * <h2>Per-task progress is sent, and the keying is acknowledged</h2>
     *
     * <p>{@code tasks} is an array indexed by task position, which is the same keying
     * {@li QuestProgress} uses and carries the same limitation: reordering a quest's tasks moves
     * progress to a different task. It is sent anyway because "you have 5 of 8 logs" is the single
     * most useful line in a quest book, and a book that cannot show it is not worth opening.
     *
     * <p>The client never writes this. It is a reading, not a claim — the server recomputes it and
     * the client replaces whatever it had.
     */
    public static byte[] progressAsJson(ProgressionEngine.Resolution resolution,
                                        TeamProgress progress,
                                        QuestIndex index) {
        JsonObject quests = new JsonObject();

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            QuestProgress stored = progress.progressOf(quest);

            JsonObject one = new JsonObject();
            one.addProperty("state", resolution.stateOf(quest).name());

            long remaining = resolution.cooldownOf(quest);
            if (remaining > 0) {
                one.addProperty("cooldown", remaining);
            }

            int[] perTask = new int[quest.tasks().size()];
            for (int i = 0; i < perTask.length; i++) {
                perTask[i] = stored.progressOf(i);
            }
            JsonArray tasks = new JsonArray();
            for (int value : perTask) {
                tasks.add(value);
            }
            one.add("tasks", tasks);

            quests.add(quest.id(), one);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("quests", quests);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Sends the tree to one player. Called on join, and after a reload. */
    public static void sendTreeTo(ServerPlayer player, QuestIndex index) {
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player, new QuestSyncPayload(
                index.questCount(), index.chapterCount(), treeAsJson(index)));
    }

    /**
     * Sends one player their team's current progress.
     *
     * <p>The single place progress is pushed from, so every caller gets the same resolution and the
     * same reason field. Called on join, on a change, on a reload, and when a party changes — all of
     * which want the same thing with a different reason.
     */
    public static void sendProgress(MinecraftServer server, ServerPlayer player, int reason) {
        QuestIndex index = TaskedQuests.index();
        if (index.isEmpty()) {
            return;
        }
        UUID owner = ProgressService.progressOwner(server, player);
        TeamProgress progress = ProgressService.progressFor(server, owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(
                index, progress, server.overworld().getGameTime());

        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player, new ProgressSyncPayload(
                owner,
                server.overworld().getGameTime(),
                progressAsJson(resolution, progress, index),
                reason));
    }

    /** Sends the tree and this player's progress together — what a join needs. */
    public static void sendEverythingTo(ServerPlayer player, MinecraftServer server) {
        QuestIndex index = TaskedQuests.index();
        // The tree is sent even when it is empty, so the client can tell "nothing loaded" from
        // "nothing received" and say the right one of those to a player.
        sendTreeTo(player, index);
        if (!index.isEmpty()) {
            sendProgress(server, player, ProgressSyncPayload.REASON_JOIN);
        }
    }

    /** Sends progress to every member of a team, for a change one of them caused. */
    public static void sendProgressToTeam(MinecraftServer server,
                                          java.util.Collection<ServerPlayer> members,
                                          int reason) {
        for (ServerPlayer member : members) {
            sendProgress(server, member, reason);
        }
    }
}
