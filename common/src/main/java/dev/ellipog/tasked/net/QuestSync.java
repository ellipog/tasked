package dev.ellipog.tasked.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.progress.ProgressService;
import dev.ellipog.tasked.progress.ProgressionEngine;
import dev.ellipog.tasked.progress.QuestClaims;
import dev.ellipog.tasked.progress.QuestProgress;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.progress.TeamProgress;
import dev.ellipog.tasked.quest.Chapter;
import dev.ellipog.tasked.quest.ChapterGroup;
import dev.ellipog.tasked.quest.ItemRef;
import dev.ellipog.tasked.quest.Quest;
import dev.ellipog.tasked.quest.QuestIndex;
import dev.ellipog.tasked.quest.QuestRef;
import dev.ellipog.tasked.quest.QuestReward;
import dev.ellipog.tasked.quest.QuestSettings;
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

    /**
     * Nobody is contributing to anything: the answer for a caller with no team behind it.
     *
     * <p>The full-progress form and the tests both use it, and it exists rather than a null so that
     * {@code oneQuestAsJson} has one thing to call.
     */
    private static final ProgressService.Contributors NOBODY = (questId, taskIndex) -> Map.of();

    /** One player's last sent state: which team it was for, and the exact JSON sent per quest. */
    private record Sent(UUID teamId, Map<String, String> quests) {
    }

    /**
     * One message that was produced: why, and to whom.
     *
     * <p>Both halves are needed and neither implies the other. The reason is what a client branches
     * on — it is what decides a full sync over a delta — so counting without it would pass while every
     * message said the wrong thing. The recipient is what makes "who was told" assertable, and a total
     * cannot answer that: two messages that both reached the <i>same</i> player look identical to two
     * that reached one player each, and the second is the entire point of the team-change push.
     */
    private record Told(int reason, UUID player) {
    }

    /**
     * How many progress messages have been produced, per reason and recipient.
     *
     * <h2>Why this exists, rather than a test reading the wire</h2>
     *
     * <p>Because the half of the sync that is easy to get wrong has no other observable. A message
     * goes out, a booted server writes nothing to the log, and the payload is handed to a loader that
     * a test JVM does not have — so "was the client told" is not a question a test can ask of the
     * transport. Counting messages answers it without inventing a second code path: the count is
     * incremented at the one place a message is produced, so a caller that reaches it by a route
     * nobody thought of is counted too. A test that asserted on a route instead would pass while the
     * route it did not know about stayed broken — which is precisely the shape of the bug this
     * counter was added to close, where the only thing pushing progress was the Submit handler.
     *
     * <p>Static and never reset, like the snapshot map above it, so a test asserts a <i>delta</i>
     * rather than an absolute value. Two test classes sharing one JVM cannot then disagree about a
     * number that belongs to neither of them.
     *
     * <p>Deliberately not a count of <i>packets</i>: one logical message is several packets once it is
     * chunked, and "how many times did the server have something to say" is the question. Counting
     * chunks would make a large pack look like a chatty one.
     */
    private static final Map<Told, Integer> MESSAGES = new HashMap<>();

    private QuestSync() {
    }

    /**
     * The version of the tree, as it goes over the wire.
     *
     * <h2>What 2 added, and the rule that keeps bumping it safe</h2>
     *
     * <p>Version 2 added {@code groups[]} at the root and {@code chapterGroupId} to every quest. Both
     * are <b>additions</b>: no field moved, no field was renamed, and every field a version-1 reader
     * asked for is still there meaning the same thing. That is the whole compatibility strategy, and it
     * is worth stating plainly because it is a constraint rather than a description — every future
     * change to {@link #treeAsJson} has to be of the additive kind, or be a deliberate break by a
     * reader that checks this number.
     *
     * <p>Version 3 added {@code chapters[]} at the root, for the same additive reason as the version
     * before it: it is the list of chapters themselves, which the per-quest fields could not carry for
     * a chapter that holds no quests yet. A version-2 reader ignores the array entirely and goes on
     * deriving its rows from the quests, which is what it already did.
     *
     * <p>Version 6 added the reward base mechanics to every reward — {@code auto}, {@code team} and
     * {@code excludeFromClaimAll}, resolved against the tree's settings — for the rewards panel. The
     * same additive kind: a version-5 reader draws its reward rows from the fields it knows and never
     * asks for the three.
     *
     * <p>Version 7 added the observation fields to a task that has them — {@code observeType},
     * {@code observeTarget}, {@code observeTicks} — which is what lets the client do the watching. A
     * version-6 reader ignores them and draws an observation task as a label, which is the honest
     * fallback: it cannot count what it cannot recognise.
     *
     * <p>The consequence, in the direction that matters most: <b>an old client on a new server still
     * draws today's flat list.</b> It reads the fields it knows and ignores the two it does not, which
     * is what Gson does with a key nobody asks for, so an install that has not been updated keeps
     * working against a server that has.
     *
     * <h2>This is read in exactly one place, and deliberately not as a gate</h2>
     *
     * <p>{@link dev.ellipog.tasked.client.ClientQuestCache} compares it and logs a warning when the
     * server is ahead, and then reads the tree anyway. A reader that <i>refused</i> a version it did
     * not know would break the very case the additive design exists to keep working, so refusing is the
     * one thing this field must never be used for. It is a description of the tree and a diagnostic for
     * the mismatch; the field's presence and absence are what the reader actually branches on.
     *
     * <p>The reader importing this constant from the writer is a considered choice rather than an
     * oversight. The two classes are two halves of one hand-written contract — which is the whole point
     * of {@code QuestSyncTest} — so a second copy of this number in the client is precisely the "second
     * answer that can disagree" this codebase keeps finding. {@code net} and {@code client} already
     * reference each other, so this adds an instance of a coupling that is already there rather than a
     * new kind of one.
     */
    public static final int TREE_VERSION = 7;

    /**
     * The quest tree, as JSON.
     *
     * <h2>Flat at both levels, and for the same reason</h2>
     *
     * <p>Every quest carries its {@code chapterId} and its {@code chapterGroupId} rather than being
     * nested under either, so the client groups however it likes without walking a tree. Nesting would
     * be smaller and would fix the client's layout to the server's, which is backwards — grouping is a
     * display decision.
     *
     * <p>The group <b>headings</b> travel in a flat list of their own at the root, and they have to:
     * a quest can say which group it is in, and cannot say what that group is called or whether its
     * chapters start closed. Those are two facts about one object, so they are two fields — and the
     * alternative, nesting the quests inside their group, would buy a single place for both at the cost
     * of the client having no say in the shape of its own sidebar.
     *
     * <p>{@code invisible} travels as a flag rather than the quest being withheld. Withholding it is
     * tidier right up until a player completes something and the quest should appear, at which point
     * the whole tree has to be re-sent. Sending the flag keeps the tree immutable for as long as the
     * quest files are.
     */
    public static byte[] treeAsJson(QuestIndex index) {
        JsonArray quests = new JsonArray();

        // One entry per quest, in declaration order, taken from the index rather than from a walk over
        // the files. That walk was a fifth copy of the same nested loop, and it recounted each quest's
        // position within its chapter with a local counter -- a second description of the number a
        // LINEAR chapter gates on. The entry carries the position the chapter's own list gives it.
        //
        // The position and the linearity stay per-quest on the wire even though both are properties of
        // the chapter, because the client groups entries by `chapterId` and has no chapter record to
        // hang them on. They are sent rather than worked out on the client because a linear chapter
        // declares no dependencies at all -- the list order *is* the progression -- so from the quests
        // alone the client cannot tell a road from three unrelated nodes. It would draw three
        // unconnected boxes for a chapter that is a sequence, which looks like a missing feature rather
        // than a missing field.
        for (QuestIndex.QuestEntry entry : index.quests()) {
            quests.add(questAsJson(entry.groupId(), entry.chapter(), entry.quest(), entry.orderInChapter()));
        }

        // The headings, in declaration order — which for the folder layout is folder-name order, and is
        // the order the book draws its rows in.
        //
        // Sent even when there are none, so that a version-2 tree is self-describing: a reader, or a
        // packet dump, can see that this server describes groups at all rather than having to guess
        // whether a missing key means "older server" or "no groups".
        //
        // Worth being exact about what that does and does not buy, because the tempting overclaim is
        // that the reader *needs* the distinction. It does not. A client draws the flat chapter list
        // whenever it holds no group headings, and that is the right screen for both an older server
        // and a version-2 server with an empty questline — the two cases want the same drawing, so the
        // client never has to ask which one it is looking at. This is a diagnostic, not a
        // compatibility mechanism, and nothing should be built on it as one.
        JsonArray groups = new JsonArray();
        for (QuestIndex.GroupEntry entry : index.groups()) {
            ChapterGroup group = entry.group();
            JsonObject one = new JsonObject();
            one.addProperty("id", group.id());
            one.addProperty("title", group.title().value());
            one.addProperty("collapsedByDefault", group.collapsedByDefault());
            // Optional, and sent only when the group declares one: a group with no icon falls back on
            // the client to the first chapter under it, which is a client-side choice rather than a
            // value the file has to spell out. See `QuestBookScreen.buildSidebar()`.
            group.icon().ifPresent(icon -> {
                one.addProperty("icon", icon.item().toString());
                componentsAsJson(icon, "iconComponents", one);
            });
            groups.add(one);
        }

        // The chapters, in declaration order, since version 3.
        //
        // This is what makes a chapter real before it holds anything. Until it, a chapter reached the
        // client only as a property of the quests inside it, so a chapter somebody had just created --
        // or one they had emptied -- did not exist in the book at all, and there was no id to select,
        // rename or move. The per-quest `chapterTitle`/`chapterIcon` fields stay exactly as they were,
        // because a client older than this version still reads them and this change has to be additive.
        JsonArray chapters = new JsonArray();
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            Chapter chapter = entry.chapter();
            JsonObject one = new JsonObject();
            one.addProperty("id", chapter.id());
            // Empty for a chapter the index places at the root, which is the same "no group" sentinel
            // the per-quest field already uses and the sidebar already draws as a root row.
            one.addProperty("groupId", entry.groupId());
            one.addProperty("title", chapter.title().value());
            one.addProperty("icon", chapter.icon().item().toString());
            componentsAsJson(chapter.icon(), "iconComponents", one);
            chapters.add(one);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", TREE_VERSION);
        // The pack's main theme, absent when it has none. See `packTheme` for why this rides on the
        // tree rather than in a message of its own, and why it is sent both here and in the payload.
        String theme = packTheme(index);
        if (theme != null) {
            root.addProperty("theme", theme);
        }
        root.add("groups", groups);
        root.add("chapters", chapters);
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

    private static JsonObject questAsJson(String groupId, Chapter chapter, Quest quest, int orderInChapter) {
        JsonObject json = new JsonObject();
        // The group its chapter is in, which is the membership the heading list on its own cannot
        // express: `groups[]` says what the headings are called and nothing about what hangs under them.
        //
        // Sent per quest rather than once per chapter, for the same reason `chapterTheme` is -- the
        // client has no chapter record to hang it on, and a second message saying "chapter c is in group
        // g" would be a second thing to keep in step with the first.
        json.addProperty("chapterGroupId", groupId);
        json.addProperty("chapterId", chapter.id());
        json.addProperty("chapterTitle", chapter.title().value());

        // The chapter's own icon, on every quest of it for the same reason `chapterTheme` is below: the
        // client groups entries by `chapterId` and has no chapter record to hang it on. Sent for every
        // chapter -- the model defaults an absent icon to paper -- because a sidebar row with an icon is
        // the readable list the toolkit's own note describes, and "the chapter declares none" is a
        // different fact from "the client was not told".
        //
        // The components ride beside it the same way the quest's own icon's do, so an author who picks a
        // renamed item for a chapter gets the renamed item in the chapter list.
        json.addProperty("chapterIcon", chapter.icon().item().toString());
        componentsAsJson(chapter.icon(), "chapterIconComponents", json);

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
        // The chapter's default line style, resolved: the client draws with it directly and has no
        // chapter record to read one from -- the same reason `chapterDefaultPrerequisiteMode` rides here.
        json.add("chapterDependencyStyle", chapter.dependencyStyle().resolved().asJson());
        // And this quest's per-line overrides, sent only when it has any: absence is the common case and
        // it means "every line of mine follows the chapter", which is what a file that never mentions
        // them says.
        if (!quest.dependencyLines().isEmpty()) {
            JsonObject lines = new JsonObject();
            quest.dependencyLines().forEach((dependency, style) -> lines.add(dependency, style.asJson()));
            json.add("dependencyLines", lines);
        }
        json.addProperty("id", quest.id());
        json.addProperty("title", quest.title().value());
        quest.subtitle().ifPresent(subtitle -> json.addProperty("subtitle", subtitle.value()));
        json.addProperty("icon", quest.icon().item().toString());
        componentsAsJson(quest.icon(), "iconComponents", json);
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
        // Degrees clockwise, and it crosses for the same reason `shape` does: the client is what draws
        // and hit-tests the node, so a rotation the server held and the client never heard about would
        // be a field that reads as supported and does nothing.
        json.addProperty("rotation", quest.layout().rotation());
        // The dependency *rules*, not just the edges. The client is what draws the lines and the card,
        // and without these it can only ask "is this prerequisite completed" -- which is the wrong
        // question for two of the four modes, and made a satisfied prerequisite draw as unmet.
        //
        // Absent rather than defaulted for the mode, because absence is a meaning: the quest has no
        // opinion and the chapter's default applies. Sending "all_completed" for a quest that said
        // nothing would make the client unable to tell the two apart.
        quest.prerequisiteMode().ifPresent(mode ->
                json.addProperty("prerequisiteMode", mode.name().toLowerCase(java.util.Locale.ROOT)));
        // The chapter's default, because the client has no chapter record and an absent
        // `prerequisiteMode` means "whatever the chapter says". Sent per quest like `chapterLinear` is,
        // for the same reason and with the same cost.
        json.addProperty("chapterDefaultPrerequisiteMode",
                chapter.defaultPrerequisiteMode().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("minRequired", quest.minRequired());
        json.addProperty("maxCompletableDependents", quest.rules().maxCompletableDependents());
        // The reveal flags. Every one of them is a presentation decision the client makes against state
        // it already has -- dependency states, task progress -- so they travel as data and the client
        // needs no engine of its own.
        json.addProperty("hideUntilDependenciesComplete", quest.rules().hideUntilDependenciesComplete());
        json.addProperty("hideUntilDependenciesVisible", quest.rules().hideUntilDependenciesVisible());
        json.addProperty("hideDependencyLines", quest.rules().hideDependencyLines());
        json.addProperty("hideTextUntilComplete", quest.rules().hideTextUntilComplete());
        json.addProperty("hideDetailsUntilStartable", quest.rules().hideDetailsUntilStartable());
        json.addProperty("invisibleUntilTasks", quest.rules().invisibleUntilTasks());
        quest.exclusiveGroup().ifPresent(group -> json.addProperty("exclusiveGroup", group));
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
        display.item().ifPresent(ref -> componentsAsJson(ref, "itemComponents", json));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        // The subject the key is formatted with -- the biome, the stage, the mob. Sent rather than left
        // to the client to derive, because only the type knows what its sentence is about; see
        // TaskDisplay#labelArg and ClientQuestCache.TaskEntry#text for the "1" that reading the count
        // into every key produced.
        json.addProperty("labelArg", display.labelArg());
        json.addProperty("optional", task.optional());
        json.addProperty("manual", TaskTypes.behaviourOf(task)
                .map(behaviour -> behaviour.canSubmitByHand(task))
                .orElse(false));
        // The observation fields, which are the only per-type data the client needs to do work with:
        // it ray-traces against them and submits when the timer is done. `manual` stays false above --
        // no button -- and the submission is accepted because the type says so, not because of a flag
        // on the wire.
        if (task instanceof dev.ellipog.tasked.quest.task.ObservationTask observation) {
            json.addProperty("observeType", observation.observeType().wire());
            json.addProperty("observeTarget", observation.toObserve());
            json.addProperty("observeTicks", observation.timer());
        }
        return json;
    }

    private static JsonObject rewardAsJson(QuestReward reward) {
        RewardDisplay display = RewardTypes.displayOf(reward);

        JsonObject json = new JsonObject();
        json.addProperty("type", reward.type().toString());
        json.addProperty("icon", RewardTypes.iconOf(reward.type()).item().toString());
        json.addProperty("item", display.item().map(ref -> ref.item().toString()).orElse(""));
        display.item().ifPresent(ref -> componentsAsJson(ref, "itemComponents", json));
        json.addProperty("count", display.count());
        json.addProperty("label", display.label());
        json.addProperty("labelFallback", display.labelFallback());
        json.addProperty("labelArg", display.labelArg());
        // The base mechanics, resolved against the tree's own settings here rather than on the client:
        // the client draws what the server would do, and a client resolving a file default would be a
        // second opinion about the file. See RewardCommon.
        QuestSettings settings = TaskedQuests.settings();
        json.addProperty("auto", reward.common().autoClaim(settings.defaultAutoClaim()).name()
                .toLowerCase(java.util.Locale.ROOT));
        json.addProperty("team", reward.common().teamReward(settings.defaultTeamReward()));
        json.addProperty("excludeFromClaimAll", reward.common().excludeFromClaimAll());
        return json;
    }

    /**
     * The item's component patch, beside its id, or nothing when it has none.
     *
     * <p>A JSON object in the datapack's spelling, which is the same shape the quest file uses -- so
     * the wire and the file cannot disagree about what a component looks like. Encoding it here with
     * the codec rather than building it by hand keeps that true as versions change what components
     * exist.
     */
    private static void componentsAsJson(ItemRef ref, String field, JsonObject json) {
        if (ref.components().isEmpty()) {
            return;
        }
        net.minecraft.core.component.DataComponentPatch.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, ref.components())
                .result().ifPresent(value -> json.add(field, value));
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
        return progressAsJson(resolution, progress, index, NOBODY);
    }

    /**
     * The same, with the per-member pictures the engine keeps: who is holding what toward each task.
     *
     * <p>A separate form rather than a wider one because the pictures are not part of a quest's
     * <i>state</i> -- they are live numbers that go down as well as up, and a caller that has no team
     * behind it (a dump, a test) has none to give.
     */
    public static byte[] progressAsJson(ProgressionEngine.Resolution resolution,
                                        TeamProgress progress,
                                        QuestIndex index,
                                        ProgressService.Contributors contributors) {
        return progressDelta(resolution, progress, index, null, contributors).json();
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
        return progressDelta(resolution, progress, index, previous, NOBODY);
    }

    /** The same, with the per-member pictures. See {@link #progressAsJson} for why they are separate. */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors) {
        return progressDelta(resolution, progress, index, previous, contributors, java.util.Set.of());
    }

    /**
     * The same, with the quests whose stage gate this player does not pass.
     *
     * <p>The third per-player overlay on this wire, after the contributors and the claimable flag, and for
     * the same reason: a quest's stored state is the team's, and what <i>this</i> player may see and collect
     * is not. A gated quest reads as LOCKED for a player without the stage and as whatever the engine says
     * for one with it, which is what makes a stage gate look like every other gate on the canvas.
     */
    public static Delta progressDelta(ProgressionEngine.Resolution resolution,
                                      TeamProgress progress,
                                      QuestIndex index,
                                      Map<String, String> previous,
                                      ProgressService.Contributors contributors,
                                      java.util.Set<String> stageLocked) {
        JsonObject changed = new JsonObject();
        Map<String, String> snapshot = new LinkedHashMap<>();

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            String encoded = oneQuestAsJson(resolution, progress, quest, contributors, stageLocked);

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
                                         Quest quest,
                                         ProgressService.Contributors contributors,
                                         java.util.Set<String> stageLocked) {
        QuestProgress stored = progress.progressOf(quest);

        JsonObject one = new JsonObject();
        // The gate first: a quest this player has not unlocked reads as locked whatever the team's stored
        // state says, and the claimable flag below is decided from this same answer rather than separately.
        boolean gated = stageLocked.contains(quest.id());
        one.addProperty("state", (gated ? QuestState.LOCKED : resolution.stateOf(quest)).name());

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
        if (!gated && ProgressService.anyoneCouldClaim(progress, quest)) {
            one.addProperty("claimable", true);
        }

        // Who has collected what. Per player, because a claim is a player's own -- the client answers
        // "does the player at this keyboard have something to collect" from this map with its own
        // UUID, which a single team-wide boolean could never do. Sparse: an empty side is absent.
        QuestProgress storedProgress = progress.progressOf(quest);
        QuestClaims claims = storedProgress.claims();
        if (!claims.team().isEmpty()) {
            JsonArray team = new JsonArray();
            claims.team().stream().sorted().forEach(team::add);
            one.add("teamClaims", team);
        }
        if (!claims.players().isEmpty()) {
            JsonObject players = new JsonObject();
            claims.players().forEach((player, indices) -> {
                JsonArray list = new JsonArray();
                indices.stream().sorted().forEach(list::add);
                players.add(player.toString(), list);
            });
            one.add("claims", players);
        }
        // A pre-per-player save's "collected": collected for everyone, for good. Sent so the client
        // does not offer a claim the server would refuse.
        if (storedProgress.legacySettled()) {
            one.addProperty("settled", true);
        }
        // And whether the team's payouts are held, so the panel can say why a claim would be refused
        // rather than showing a live button the server rejects. See TeamProgress#rewardsBlocked.
        if (progress.rewardsBlocked()) {
            one.addProperty("rewardsBlocked", true);
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

        // Who is holding what toward each task, by task position -- the "Ellio has four of the eight"
        // line a quest book could never draw, because the engine added those parts up and threw them
        // away. See `ProgressService.CONTRIBUTIONS`, including why a finished task still names its
        // contributors: for a consuming task that picture is the only record of who did the work.
        //
        // Sparse in both directions: a task nobody is carrying anything toward is absent, and so is a
        // member holding nothing. A party's whole picture is a few small numbers, and a player who is
        // alone has none of it until they pick something up.
        JsonObject who = new JsonObject();
        for (int i = 0; i < quest.tasks().size(); i++) {
            Map<UUID, Integer> picture = contributors.of(quest.id(), i);
            if (picture.isEmpty()) {
                continue;
            }
            JsonObject holders = new JsonObject();
            for (Map.Entry<UUID, Integer> each : picture.entrySet()) {
                holders.addProperty(each.getKey().toString(), each.getValue());
            }
            who.add(String.valueOf(i), holders);
        }
        if (!who.isEmpty()) {
            one.add("who", who);
        }

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

        Delta delta = progressDelta(resolution, progress, index, full ? null : last.quests(),
                ProgressService.contributors(owner),
                // The quests this player's stage gate shuts: the one per-player fact the per-quest text
                // cannot carry, because the text is built from the team's progress. See progressDelta.
                ProgressService.stageLockedQuests(server, player, index));
        SENT.put(player.getUUID(), new Sent(owner, delta.snapshot()));

        // Counted here rather than at the `send` calls below. One logical message can be several
        // chunks, and it is counted once for all of them: "how many times did the server have
        // something to say" is the question, and counting chunks would make a large pack look like a
        // chatty one.
        MESSAGES.merge(new Told(reason, player.getUUID()), 1, Integer::sum);

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

    /**
     * Forgets every snapshot, so the next sync to anybody is a full one.
     *
     * <p><b>Nothing calls this, and the reload path does not need it.</b> A reload is
     * {@code REASON_RELOAD}, and {@link #sendProgress} sends a full sync for that reason on its own —
     * so a caller that forgot every snapshot first would change nothing, and it would also forget the
     * snapshots of players the reload is not about. It is kept because forcing a full sync for a
     * player who never disconnected is exactly what it does and the next reason to want that will not
     * be a reload; the reader who finds it dead should feel free to delete it.
     */
    public static void forgetAll() {
        SENT.clear();
    }

    /** How many progress messages have been produced for one reason, to anybody. Diagnostics. */
    public static int messagesSent(int reason) {
        int total = 0;
        for (Map.Entry<Told, Integer> each : MESSAGES.entrySet()) {
            if (each.getKey().reason() == reason) {
                total += each.getValue();
            }
        }
        return total;
    }

    /** How many progress messages one player has been sent for one reason. Diagnostics. */
    public static int messagesSentTo(int reason, UUID player) {
        return MESSAGES.getOrDefault(new Told(reason, player), 0);
    }

    /** How many progress messages have been produced in total, whatever the reason. */
    public static int messagesSent() {
        int total = 0;
        for (int count : MESSAGES.values()) {
            total += count;
        }
        return total;
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
