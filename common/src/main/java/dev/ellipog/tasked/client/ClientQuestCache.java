package dev.ellipog.tasked.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.client.Look;
import dev.ellipog.tasked.Constants;
import dev.ellipog.tasked.net.QuestSync;
import dev.ellipog.tasked.progress.QuestState;
import dev.ellipog.tasked.quest.DependencyStyle;
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
import java.util.Set;
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
                            String type, String label, String labelFallback, String labelArg, String itemId,
                            /** The observation fields, empty for every other type: what to watch, how. */
                            String observeType, String observeTarget, int observeTicks,
                            /**
                             * The item tag a {@code tasked:item_tag} task hands in, empty for every other
                             * type. The same distinction {@code itemId} draws for item tasks: a viewer
                             * cannot find a tag task from an item without knowing which tag it is, and
                             * its sentence is not a field.
                             */
                            String tagId,
                            /** The gates this task carries, for the locked row's hover. Empty for none. */
                            List<ConditionEntry> conditions) {

        /** Whether this row draws an item at all, as opposed to text. */
        public boolean hasItem() {
            return !item.isEmpty();
        }

        /**
         * The kind of thing to watch for, or null when this is not an observation task.
         *
         * <p>The value the client ticker matches the crosshair against; see
         * {@code ObservationWatcher}.
         */
        public dev.ellipog.tasked.quest.task.ObservationTask.ObserveType observation() {
            return observeType.isEmpty()
                    ? null
                    : dev.ellipog.tasked.quest.task.ObservationTask.ObserveType.byWire(observeType);
        }

        /**
         * The text for this row.
         *
         * <p>A translatable label when one was sent with a fallback, so a pack can translate its own
         * checkmark titles; the item's own name when there is an item; the literal text otherwise.
         *
         * <h2>What the key is formatted with, and the bug that made it explicit</h2>
         *
         * <p>{@code labelArg} when the server sent one — the biome, the stage, the mob the sentence is
         * about — and the count otherwise, for the types whose sentence counts something ("%s XP").
         * Passing the count for every key is what put "1" on the card where a stage reward should have
         * read "Grant the stage my_pack:inducted": the key was written for a subject the count is not.
         * An older server sends no argument, and those keys are the count-shaped ones, so the fallback
         * to the count is what keeps that pairing working.
         */
        public Component text() {
            return text(labelArg.isEmpty() ? String.valueOf(count) : labelArg);
        }

        /**
         * The same, with the key's argument supplied by the caller.
         *
         * <p>How a row shows a prettified id: the sentence is the server's and stays the server's, and
         * the one word in it the client can say better -- a registry id with a name the client knows --
         * is replaced before the key is formatted. See {@code QuestBookScreen.prettyArg}.
         */
        public Component text(String arg) {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return Component.translatableWithFallback(label, labelFallback, arg);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * One reward, resolved ready to draw.
     *
     * <p>{@code team}, {@code auto} and {@code excludeFromClaimAll} are the base mechanics, resolved
     * server-side against the tree's own settings: {@code team} decides whose claim settles this
     * reward (the player's own, or the whole team's), which is what the claim view reads.
     */
    public record RewardEntry(ItemStack icon, ItemStack item, int count, String type, String label,
                              String labelFallback, String labelArg, String itemId, String auto, boolean team,
                              boolean excludeFromClaimAll,
                              /** The gates this reward carries, for the locked row's hover. Empty for none. */
                              List<ConditionEntry> conditions) {

        public boolean hasItem() {
            return !item.isEmpty();
        }

        public Component text() {
            // The subject when there is one, the count otherwise -- see TaskEntry.text for the
            // "1" that reading the count into every key produced.
            return text(labelArg.isEmpty() ? String.valueOf(count) : labelArg);
        }

        /** The same, with the key's argument supplied by the caller: see {@link TaskEntry#text(String)}. */
        public Component text(String arg) {
            if (hasItem()) {
                return item.getHoverName();
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return Component.translatableWithFallback(label, labelFallback, arg);
            }
            return Component.literal(label.isEmpty() ? "?" : label);
        }
    }

    /**
     * One gate a task or a reward carries, resolved ready to draw.
     *
     * <p>What a locked row's hover names. The item is resolved here, like a task's, and the rest is the
     * sentence: a key and an English fallback, exactly as a row's own text travels, because the server
     * does not know this client's language. The unmet <i>indices</i> travel on the progress channel and
     * say which of these to name — see {@link #taskLockOf}.
     */
    public record ConditionEntry(ItemStack item, int count, String label, String labelFallback,
                                 String labelArg) {

        /** Whether this gate is drawn as an item, with the item's own name. */
        public boolean hasItem() {
            return !item.isEmpty();
        }

        /**
         * The line a hover shows.
         *
         * <p>An item gate reads as the item's own name and count — the number is part of the sentence
         * here because a condition has no progress chip to carry it, which is the one place this
         * differs from a task row. A text gate carries its number in {@code labelArg}, so the key
         * formats to the whole sentence.
         */
        public String line() {
            if (hasItem()) {
                String name = item.getHoverName().getString();
                return count > 1 ? name + " \u00d7" + count : name;
            }
            if (!labelFallback.isEmpty() && !label.isEmpty()) {
                return Component.translatableWithFallback(label, labelFallback, labelArg).getString();
            }
            return label.isEmpty() ? "?" : label;
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
     *
     * <p>{@code icon} is the group's authored icon, empty when the file declares none — in which case
     * the sidebar falls back to the first chapter under it. {@code iconId} is kept beside the stack for
     * the same reason the quest's is: a resolved-empty stack with an id is a missing item, and the two
     * facts are worth telling apart.
     */
    public record GroupEntry(String id, String title, boolean collapsedByDefault, ItemStack icon,
                             String iconId) {
    }

    /**
     * One chapter, as the server described it — whether or not it holds any quests.
     *
     * <p>Since version 3 the tree carries a {@code chapters[]} of its own, and the reason is a chapter
     * that has no quests: until this list existed, a chapter reached the client only as a property of
     * the quests inside it, so an empty one was invisible — it could not be selected, edited, moved or
     * even seen. The per-quest {@code chapterTitle}/{@code chapterIcon} fields still arrive and are
     * still what the canvas draws, so a version-2 server needs nothing from this record.
     *
     * <p>{@code groupId} is empty for a chapter that belongs to no group — the same sentinel a quest's
     * {@code chapterGroupId} uses, and the case the sidebar already draws as a root row.
     */
    public record ChapterEntry(String id, String groupId, String title, ItemStack icon, String iconId) {
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
                        double iconScale, int rotation, boolean showTitle,
                        /** The dependency rule, as the server resolved it: null means the chapter default. */
                        dev.ellipog.tasked.quest.PrerequisiteMode prerequisiteMode,
                        /** What the chapter says when a quest has no opinion. */
                        dev.ellipog.tasked.quest.PrerequisiteMode chapterDefaultPrerequisiteMode,
                        int minRequired, int maxCompletableDependents,
                        /** Empty when the quest names no group. */
                        String exclusiveGroup,
                        /** The reveal flags, as authored. See `QuestVisibility`. */
                        boolean hideUntilDependenciesComplete, boolean hideUntilDependenciesVisible,
                        boolean hideDependencyLines, boolean hideTextUntilComplete,
                        boolean hideDetailsUntilStartable, int invisibleUntilTasks,
                        boolean chapterLinear, int orderInChapter,
                        List<String> dependencies, List<TaskEntry> tasks, List<RewardEntry> rewards,
                        boolean invisible,
                        /**
                         * The icon's id as the server sent it, kept beside the resolved stack: a stack
                         * that failed to resolve with an id that was sent is a <b>missing item</b>, and
                         * the screens say so; an empty id is simply no icon.
                         */
                        String iconId,
                        /**
                         * The chapter's icon, and the id it was resolved from: the sidebar's chapter row
                         * draws it, and the id keeps the same "missing item" reading the quest's own pair
                         * has. Sent on every quest of the chapter, so it is never absent for a chapter --
                         * an unauthored icon is the model's paper default rather than nothing.
                         */
                        ItemStack chapterIcon, String chapterIconId,
                        /**
                         * The per-line styles this quest's own dependencies carry, keyed by dependency
                         * id. Empty means every line follows {@link #chapterDependencyStyle()}, which is
                         * what a quest file that never mentions them says.
                         */
                        java.util.Map<String, dev.ellipog.tasked.quest.DependencyStyle> dependencyLines,
                        /**
                         * The chapter's default line style, already resolved: the canvas draws with it
                         * directly and has no chapter record to read one from. Resolved server-side so
                         * an axis nobody set arrives as the built-in rather than as an absence every
                         * drawing call site would have to remember to fill.
                         */
                        dev.ellipog.tasked.quest.DependencyStyle chapterDependencyStyle,
                        /**
                         * The chapter's token-level theme overrides as the file wrote them, or null when
                         * it names none. Raw JSON, like the chapter's own field: the client parses and
                         * composes it, and the server only carried it here.
                         */
                        com.google.gson.JsonObject chapterThemePatch,
                        /**
                         * The quest's own auto-claim mode, or null when it says nothing — in which case
                         * {@link #chapterAutoClaim} applies. See {@link #effectiveAutoClaim()}.
                         */
                        dev.ellipog.tasked.quest.reward.RewardAutoClaim autoClaim,
                        /**
                         * The chapter's auto-claim mode, already resolved against the pack setting by the
                         * server: the client needs the effective value for a quest that says nothing, and
                         * has no chapter record to resolve one from.
                         */
                        dev.ellipog.tasked.quest.reward.RewardAutoClaim chapterAutoClaim) {

        /**
         * The auto-claim mode in force for this quest: its own, or the chapter's.
         *
         * <p>What the completion toast reads: a mode that grants silently ({@code no_toast},
         * {@code invisible}) suppresses the notice, so an author who turned auto-claim on for fifty
         * starter quests does not get fifty toasts either. A server too old to send either field
         * answers {@code DEFAULT}, which is not automatic and therefore notifies — exactly the
         * behaviour before this existed.
         */
        public dev.ellipog.tasked.quest.reward.RewardAutoClaim effectiveAutoClaim() {
            if (autoClaim != null) {
                return autoClaim;
            }
            return chapterAutoClaim == null
                    ? dev.ellipog.tasked.quest.reward.RewardAutoClaim.DEFAULT : chapterAutoClaim;
        }

        /**
         * The rule this quest's dependencies are judged by: its own, or the chapter's when it has none.
         *
         * <p>One method rather than a ternary at each call site, because two call sites is how the
         * canvas and the card would come to disagree about whether a prerequisite is satisfied.
         */
        public dev.ellipog.tasked.quest.PrerequisiteMode effectivePrerequisiteMode() {
            return prerequisiteMode == null ? chapterDefaultPrerequisiteMode : prerequisiteMode;
        }

        /**
         * The node's outline, with its rotation applied: one shape, resolved once.
         *
         * <p>Not a method that rotates on demand, and that is the point of the field. A rotation is
         * applied by <b>sampling</b> the shape again (see {@code Shapes.rotated}), so a caller that
         * resolved it per frame would rebuild a span table per node per frame. Here it is built once
         * per (shape, rotation) for the whole client, and the drawing, the hit test and the icon fit all
         * read the same table -- which is also what keeps them from disagreeing about where the node is.
         *
         * <p>The cache is static because a record cannot hold one, and keyed by the shape and the angle
         * because those are the whole of what the sampling depends on: a node's position and size are
         * passed to the containment test, not baked into the table.
         */
        public dev.ellipog.armature.client.ui.shape.Shape geometry() {
            return ClientQuestCache.geometry(shape, rotation);
        }
    }

    /**
     * The outline for a shape at an angle, cached for the whole client.
     *
     * <p>What {@link Entry#geometry()} reads, and public because a drafted shape or rotation — a
     * settings-page change the tree has not carried yet — asks for a table the tree has not built.
     * Keyed by the shape and the angle, which are the whole of what the sampling depends on.
     */
    public static dev.ellipog.armature.client.ui.shape.Shape geometry(
            QuestShape shape, int rotation) {
        if (rotation == QuestLayout.DEFAULT_ROTATION) {
            return shape.geometry();
        }
        long key = ((long) shape.ordinal() << 16) | (rotation & 0xFFFF);
        return ROTATED.computeIfAbsent(key, ignored ->
                dev.ellipog.armature.client.ui.shape.Shapes.rotated(shape.geometry(), rotation));
    }

    /** Sampled rotated outlines, keyed by (shape ordinal, rotation). See {@link Entry#geometry()}. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, dev.ellipog.armature.client.ui.shape.Shape>
            ROTATED = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * One quest's progress, as the server last reported it.
     *
     * <p>{@code claimable} is what the Claim button hangs on. The server sends it only when a quest is
     * finished with something still to collect, so its absence — including from a server too old to
     * send it — reads as "nothing to claim", which is the direction that cannot show a button that
     * does nothing.
     */
    private record Progress(QuestState state, long cooldown, List<Integer> tasks, boolean claimable,
                            Map<Integer, Map<UUID, Integer>> contributors,
                            /** Indices settled for the whole team (team-mode and auto claims). */
                            Set<Integer> teamClaims,
                            /** Per player, the indices they have collected themselves. */
                            Map<UUID, Set<Integer>> claimedBy,
                            /** Per task row, the conditions this player does not meet. Absent = unlocked. */
                            Map<Integer, List<Integer>> taskLocks,
                            /** The same for reward rows. */
                            Map<Integer, List<Integer>> rewardLocks,
                            /** A pre-per-player save's "collected": nobody may claim again. */
                            boolean legacySettled) {

        /** Who is holding what toward one task, in the order the server named them. Empty for nobody. */
        Map<UUID, Integer> contributorsOf(int taskIndex) {
            return contributors.getOrDefault(taskIndex, Map.of());
        }

        /** Whether a claim is settled, by the rule the reward's own team flag asks for. */
        boolean claimed(UUID player, int index, boolean teamReward) {
            if (teamReward) {
                return teamClaims.contains(index);
            }
            return claimedBy.getOrDefault(player, Set.of()).contains(index);
        }

        /** The conditions this player is missing on one task, ascending. Empty means not locked. */
        List<Integer> taskLock(int index) {
            return taskLocks.getOrDefault(index, List.of());
        }

        /** The same for one reward. */
        List<Integer> rewardLock(int index) {
            return rewardLocks.getOrDefault(index, List.of());
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

    /**
     * The chapters themselves, in the order the server declared them.
     *
     * <p>Empty for a server older than version 3, and that reads the same way {@link #groups} does: the
     * sidebar derives its chapter rows from the quests when this list is empty, which is exactly what
     * every client did before the list existed. So absence is a fallback rather than a special case.
     */
    private static volatile List<ChapterEntry> chapters = List.of();

    private static volatile Map<String, Progress> progress = Map.of();
    private static volatile UUID teamId;
    private static volatile long syncedAt;
    private static volatile int questCount;
    private static volatile int chapterCount;
    private static volatile boolean treeReceived;

    /**
     * The pack's own name and icon for the book, from the tree root.
     *
     * <p>Empty for a pack that declares neither, and that reads as the client's translatable title and
     * no icon. The id is kept beside the resolved stack for the same reason a chapter's is: an id that
     * did not resolve is a <b>missing item</b> the header marks, while no id at all is simply no icon.
     */
    private static volatile String bookTitle = "";
    private static volatile String bookIcon = "";
    private static volatile ItemStack bookIconStack = ItemStack.EMPTY;

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
        // Chapters count as data since the tree can carry them on their own: a book whose only
        // chapter is empty is a book with something in it, and it is exactly the state that exists
        // between creating a chapter and writing its first quest.
        return treeReceived && (!entries.isEmpty() || !chapters.isEmpty());
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
     * The chapters, in the order the server declared them — including chapters that hold no quests.
     *
     * <p>Declaration order for the same reason {@link #groups()} is: it is the author's order, and a
     * client that sorted would silently reorder somebody's book.
     */
    public static List<ChapterEntry> chapters() {
        return chapters;
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

    /**
     * The chapter's theme patch, or null when it declares none — the counterpart of
     * {@link #chapterTheme}, and looked up the same way for the same reason.
     */
    public static com.google.gson.JsonObject chapterThemePatch(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.chapterId().equals(chapterId)) {
                return entry.chapterThemePatch();
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

    /** The pack's own name for the book, or empty for the client's translatable title. */
    public static String bookTitle() {
        return bookTitle;
    }

    /**
     * The pack's icon id for the book, or empty when it declares none.
     *
     * <p>Kept beside the resolved stack so a missing item can be told from no icon at all: an id that
     * did not resolve is a mark the header draws, while an empty id is simply nothing to draw.
     */
    public static String bookIconId() {
        return bookIcon;
    }

    /** The pack's icon for the book, or {@link ItemStack#EMPTY}. */
    public static ItemStack bookIcon() {
        return bookIconStack;
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
     * The conditions this player does not meet on one task, ascending; empty means the task is open.
     *
     * <p>This player's, not the team's: two members of a party can look at one quest and see different
     * tasks shut. Empty for a quest or task this client has no progress for, and for every task of a
     * server that predates conditions — one answer for all three, and the safe one: a row drawn open
     * that the server would refuse is corrected by the refusal and the sync that follows it.
     *
     * <p>The indices are positions in the task's own {@code conditions} list, which the tree carries;
     * the hover uses them to name exactly what is missing rather than listing the gates that hold too.
     */
    public static List<Integer> taskLockOf(String questId, int taskIndex) {
        Progress found = progress.get(questId);
        return found == null ? List.of() : found.taskLock(taskIndex);
    }

    /** The same for one reward. */
    public static List<Integer> rewardLockOf(String questId, int rewardIndex) {
        Progress found = progress.get(questId);
        return found == null ? List.of() : found.rewardLock(rewardIndex);
    }

    /**
     * Whether <b>this player</b> is finished with rewards they have not collected.
     *
     * <p>Per player, because a claim is a player's own: in a party where a teammate collected their
     * diamond, this player's button must still be there. The server sends who collected what, and
     * the tree sends each reward's own {@code team} flag; this is the two of them against the local
     * player's UUID. The server recomputes the same answer when the claim arrives — asking is not
     * claiming, and a client that shows the button wrongly gets a refusal.
     */
    public static boolean canClaimFor(UUID player, String questId) {
        return outstandingRewards(player, questId) > 0;
    }

    /**
     * How many of a quest's rewards this player could collect right now.
     *
     * <p>The one loop behind {@link #canClaimFor} and the reward badges: a quest is claimable exactly
     * when this is more than zero, so the node badge, the Claim button and the rewards panel cannot
     * disagree about how much is waiting. The count is the number of rewards, not of quests, because a
     * node's badge says "three things are here"; the sidebar's count is of quests, because that is the
     * question a chapter's row answers — see {@link #claimableByChapter}.
     */
    public static int outstandingRewards(UUID player, String questId) {
        Entry entry = entry(questId);
        return entry == null ? 0 : outstandingIn(player, entry);
    }

    /** The same count for an entry already in hand, so the aggregates are one walk and not O(n²). */
    private static int outstandingIn(UUID player, Entry entry) {
        Progress found = progress.get(entry.id());
        if (found == null || player == null) {
            return 0;
        }
        int outstanding = 0;
        for (int index = 0; index < entry.rewards().size(); index++) {
            if (claimable(player, entry, found, index)) {
                outstanding++;
            }
        }
        return outstanding;
    }

    /**
     * Whether <b>this player</b> could collect one reward of a quest right now.
     *
     * <p>The single-row form of {@link #canClaimFor}, and deliberately the same loop: the rewards
     * panel's per-row Claim hangs on this, so a row cannot offer a press the quest-level badge would
     * not count, and the panel's header cannot disagree with its own rows. The server recomputes the
     * same answer when the press arrives — asking is not claiming, and a client that shows the button
     * wrongly gets a refusal.
     */
    public static boolean canClaimReward(UUID player, String questId, int rewardIndex) {
        Entry entry = entry(questId);
        Progress found = progress.get(questId);
        if (entry == null || found == null || player == null
                || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return false;
        }
        return claimable(player, entry, found, rewardIndex);
    }

    /**
     * One reward's claimability against progress already in hand — the one predicate behind both forms.
     *
     * <p>A reward whose conditions this player does not meet is not claimable by them: counting it
     * would show a badge the server refuses. The lock is this player's, so a teammate who meets the
     * conditions still counts theirs.
     */
    private static boolean claimable(UUID player, Entry entry, Progress found, int index) {
        if (found.state() != QuestState.COMPLETED || found.legacySettled()) {
            return false;
        }
        return found.rewardLock(index).isEmpty()
                && !found.claimed(player, index, entry.rewards().get(index).team());
    }

    /**
     * The quests this player could collect from right now, by chapter id — the sidebar's counts.
     *
     * <p>A quest is counted once however many rewards it holds: a chapter's row answers "how many
     * quests have something for me", the same question the rewards panel lists.
     */
    public static java.util.Map<String, Integer> claimableByChapter(UUID player) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : entries) {
            if (outstandingIn(player, entry) > 0) {
                counts.merge(entry.chapterId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The rewards waiting per quest, by quest id — the canvas badges' own count. */
    public static java.util.Map<String, Integer> outstandingByQuest(UUID player) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : entries) {
            int outstanding = outstandingIn(player, entry);
            if (outstanding > 0) {
                counts.put(entry.id(), outstanding);
            }
        }
        return counts;
    }

    /** The tree entry for a quest id, or null. */
    public static Entry entry(String questId) {
        for (Entry entry : entries) {
            if (entry.id().equals(questId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Whether <b>this player</b> has already collected one reward.
     *
     * <p>The same fact {@link #canClaimFor} folds into one answer for the whole quest, asked about a
     * single row: a viewer page draws a status per reward — ready, locked or claimed — and a claim is
     * a player's own, so a teammate's collection does not mark this player's row. The reward's own
     * {@code team} flag decides, exactly as the claim path does.
     */
    public static boolean rewardClaimedBy(UUID player, String questId, int rewardIndex) {
        Progress found = progress.get(questId);
        Entry entry = entry(questId);
        if (found == null || entry == null || player == null
                || rewardIndex < 0 || rewardIndex >= entry.rewards().size()) {
            return false;
        }
        return found.claimed(player, rewardIndex, entry.rewards().get(rewardIndex).team());
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
            // Qualified, because this method's own `chapters` parameter is the count that came with the
            // payload and shadows the list.
            ClientQuestCache.chapters = List.of();
            bookTitle = "";
            bookIcon = "";
            bookIconStack = ItemStack.EMPTY;
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
            // And the revision moves, for the same reason `clear()` moves it: an emptied cache is not
            // the progress that was there a moment ago. Without this, a watcher that trusts "revision
            // == what the cache holds" -- the completion notifier does -- would keep the discarded
            // map's states and could announce against them.
            progressRevision++;
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
        chapters = List.of();
        progress = Map.of();
        teamId = null;
        questCount = 0;
        chapterCount = 0;
        syncedAt = 0;
        bookTitle = "";
        bookIcon = "";
        bookIconStack = ItemStack.EMPTY;
        treeReceived = false;
        // The sampled outlines go with the trees that asked for them: they are keyed by shape and
        // angle, so they cannot go stale, but a world's worth of them is not this world's to keep.
        ROTATED.clear();
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
                        group.has("collapsedByDefault") && group.get("collapsedByDefault").getAsBoolean(),
                        stack(str(group, "icon"), 1, group.get("iconComponents")),
                        str(group, "icon")));
            }
        }

        // The chapters themselves, when the server sent them. Absent means a server older than version
        // 3, whose chapters are still derivable from the quests below -- so this defaults rather than
        // requires, and nothing tests the version number to find out. The key's presence is the fact.
        List<ChapterEntry> parsedChapters = new ArrayList<>();
        if (root.has("chapters")) {
            for (JsonElement element : root.getAsJsonArray("chapters")) {
                JsonObject chapter = element.getAsJsonObject();
                parsedChapters.add(new ChapterEntry(
                        str(chapter, "id"),
                        str(chapter, "groupId"),
                        str(chapter, "title"),
                        stack(str(chapter, "icon"), 1, chapter.get("iconComponents")),
                        str(chapter, "icon")));
            }
        }

        JsonArray quests = root.getAsJsonArray("quests");

        // The pack's own book identity, when the tree carries one. The header's values, read here so
        // they travel with the tree; `acceptTree`'s catch and `clear()` both reset them with everything
        // else, so a malformed tree or a disconnect cannot leave one pack's name on another's book.
        bookTitle = str(root, "bookTitle");
        bookIcon = str(root, "bookIcon");
        bookIconStack = bookIcon.isEmpty() ? ItemStack.EMPTY : iconOf(bookIcon, null);

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

            // The dependency rule, as the server resolved it. Absent means the quest has no opinion and
            // the chapter's default applies -- which is a different thing from the mode written out, so
            // this is null rather than a fallback. A name this build has never heard of is also null:
            // the read-out then treats it as the chapter default, which is the safe reading.
            dev.ellipog.tasked.quest.PrerequisiteMode prerequisiteMode = null;
            if (quest.has("prerequisiteMode")) {
                prerequisiteMode = dev.ellipog.tasked.quest.PrerequisiteMode.CODEC
                        .parse(com.mojang.serialization.JsonOps.INSTANCE, quest.get("prerequisiteMode"))
                        .result().orElse(null);
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
                    // Wrapped rather than clamped, so a server that sent 450 degrees gets the shape it
                    // meant rather than one pinned at the top of the range.
                    quest.has("rotation")
                            ? Math.floorMod(quest.get("rotation").getAsInt(), 360)
                            : QuestLayout.DEFAULT_ROTATION,
                    quest.has("showTitle") && quest.get("showTitle").getAsBoolean(),
                    prerequisiteMode,
                    quest.has("chapterDefaultPrerequisiteMode")
                            ? dev.ellipog.tasked.quest.PrerequisiteMode.CODEC
                                    .parse(com.mojang.serialization.JsonOps.INSTANCE,
                                            quest.get("chapterDefaultPrerequisiteMode"))
                                    .result()
                                    .orElse(dev.ellipog.tasked.quest.PrerequisiteMode.ALL_COMPLETED)
                            : dev.ellipog.tasked.quest.PrerequisiteMode.ALL_COMPLETED,
                    quest.has("minRequired") ? Math.max(0, quest.get("minRequired").getAsInt()) : 0,
                    quest.has("maxCompletableDependents")
                            ? Math.max(0, quest.get("maxCompletableDependents").getAsInt()) : 0,
                    str(quest, "exclusiveGroup"),
                    quest.has("hideUntilDependenciesComplete")
                            && quest.get("hideUntilDependenciesComplete").getAsBoolean(),
                    quest.has("hideUntilDependenciesVisible")
                            && quest.get("hideUntilDependenciesVisible").getAsBoolean(),
                    quest.has("hideDependencyLines") && quest.get("hideDependencyLines").getAsBoolean(),
                    quest.has("hideTextUntilComplete") && quest.get("hideTextUntilComplete").getAsBoolean(),
                    quest.has("hideDetailsUntilStartable")
                            && quest.get("hideDetailsUntilStartable").getAsBoolean(),
                    quest.has("invisibleUntilTasks")
                            ? Math.max(0, quest.get("invisibleUntilTasks").getAsInt()) : 0,
                    quest.has("chapterLinear") && quest.get("chapterLinear").getAsBoolean(),
                    // Defaulted to a large number rather than to zero, so a server too old to send it
                    // cannot claim every quest is the first one in its chapter. A chapter that is not
                    // linear never reads this, and that is the only case an old server can produce.
                    quest.has("order") ? quest.get("order").getAsInt() : Integer.MAX_VALUE,
                    List.copyOf(dependencies),
                    List.copyOf(tasks),
                    List.copyOf(rewards),
                    quest.has("invisible") && quest.get("invisible").getAsBoolean(),
                    str(quest, "icon"),
                    stack(str(quest, "chapterIcon"), 1, quest.get("chapterIconComponents")),
                    str(quest, "chapterIcon"),
                    dependencyLines(quest),
                    DependencyStyle.from(quest.get("chapterDependencyStyle")).resolved(),
                    // Kept as the file wrote it; a server that sends nothing (or something that is not
                    // an object) reads as "no patch", which is the state every chapter was in before
                    // this field existed.
                    quest.has("chapterThemePatch") && quest.get("chapterThemePatch").isJsonObject()
                            ? quest.getAsJsonObject("chapterThemePatch") : null,
                    autoClaim(quest, "autoClaim"),
                    autoClaim(quest, "chapterAutoClaim")));
        }
        entries = List.copyOf(parsed);
        groups = List.copyOf(parsedGroups);
        chapters = List.copyOf(parsedChapters);
    }

    /**
     * An auto-claim mode by wire name, or null for absent or unknown.
     *
     * <p>Null rather than a default, because "the quest said nothing" and "the quest said default" are
     * different states: the first falls through to the chapter's mode, the second is the quest's own
     * answer. The same lenient read the shapes get — a name from a newer server is ignored, not fatal.
     */
    private static dev.ellipog.tasked.quest.reward.RewardAutoClaim autoClaim(JsonObject quest, String key) {
        String name = str(quest, key);
        if (name.isEmpty()) {
            return null;
        }
        for (dev.ellipog.tasked.quest.reward.RewardAutoClaim mode
                : dev.ellipog.tasked.quest.reward.RewardAutoClaim.values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }

    /** A quest's per-line overrides, keyed by dependency id. Absent or malformed reads as none. */    private static java.util.Map<String, DependencyStyle> dependencyLines(JsonObject quest) {
        JsonElement element = quest.get("dependencyLines");
        if (element == null || !element.isJsonObject()) {
            return java.util.Map.of();
        }
        java.util.Map<String, DependencyStyle> lines = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            lines.put(entry.getKey(), DependencyStyle.from(entry.getValue()));
        }
        return java.util.Map.copyOf(lines);
    }

    private static TaskEntry taskEntry(JsonObject json) {
        return new TaskEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                json.has("optional") && json.get("optional").getAsBoolean(),
                json.has("manual") && json.get("manual").getAsBoolean(),
                str(json, "type"),
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "labelArg"),
                str(json, "item"),
                str(json, "observeType"),
                str(json, "observeTarget"),
                json.has("observeTicks") ? json.get("observeTicks").getAsInt() : 0,
                str(json, "tag"),
                conditionEntries(json));
    }

    private static RewardEntry rewardEntry(JsonObject json) {
        return new RewardEntry(
                stack(str(json, "icon"), 1),
                stack(str(json, "item"), json.has("count") ? json.get("count").getAsInt() : 1,
                        json.get("itemComponents")),
                json.has("count") ? json.get("count").getAsInt() : 1,
                str(json, "type"),
                str(json, "label"),
                str(json, "labelFallback"),
                str(json, "labelArg"),
                str(json, "item"),
                str(json, "auto"),
                json.has("team") && json.get("team").getAsBoolean(),
                json.has("excludeFromClaimAll") && json.get("excludeFromClaimAll").getAsBoolean(),
                conditionEntries(json));
    }

    /**
     * The gates a task or a reward carries, resolved.
     *
     * <p>Empty for an entry with none — every entry of a pack without conditions — and for every entry
     * of a server that predates the field, which are the same answer: nothing to explain on hover.
     */
    private static List<ConditionEntry> conditionEntries(JsonObject json) {
        if (!json.has("conditions") || !json.get("conditions").isJsonArray()) {
            return List.of();
        }
        List<ConditionEntry> out = new ArrayList<>();
        for (JsonElement value : json.getAsJsonArray("conditions")) {
            if (!value.isJsonObject()) {
                // A placeholder rather than a skip: the server's unmet mask indexes this list, so
                // dropping an element would shift every later index and name the wrong gate. Only a
                // malformed or foreign server can produce one.
                out.add(new ConditionEntry(ItemStack.EMPTY, 1, "?", "", ""));
                continue;
            }
            JsonObject condition = value.getAsJsonObject();
            String itemId = condition.has("item") && condition.get("item").isJsonPrimitive()
                    && condition.get("item").getAsJsonPrimitive().isString()
                    ? condition.get("item").getAsString() : "";
            int count = condition.has("count") && condition.get("count").isJsonPrimitive()
                    && condition.get("count").getAsJsonPrimitive().isNumber()
                    ? condition.get("count").getAsInt() : 1;
            out.add(new ConditionEntry(
                    itemId.isEmpty()
                            ? ItemStack.EMPTY
                            : stack(itemId, count, condition.get("itemComponents")),
                    count,
                    str(condition, "label"),
                    str(condition, "labelFallback"),
                    str(condition, "labelArg")));
        }
        return List.copyOf(out);
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

                // Who collected what. Sparse in both directions, like the contributor pictures: a
                // quest nobody has claimed anything on sends neither key, which reads as "nobody has"
                // -- the direction that cannot invent a claim.
                Set<Integer> teamClaims = new java.util.LinkedHashSet<>();
                if (one.has("teamClaims")) {
                    for (JsonElement value : one.getAsJsonArray("teamClaims")) {
                        teamClaims.add(value.getAsInt());
                    }
                }
                Map<UUID, Set<Integer>> claimedBy = new LinkedHashMap<>();
                if (one.has("claims")) {
                    for (Map.Entry<String, JsonElement> who : one.getAsJsonObject("claims").entrySet()) {
                        UUID player = memberId(who.getKey());
                        if (player == null || !who.getValue().isJsonArray()) {
                            continue;
                        }
                        Set<Integer> indices = new java.util.LinkedHashSet<>();
                        for (JsonElement value : who.getValue().getAsJsonArray()) {
                            indices.add(value.getAsInt());
                        }
                        claimedBy.put(player, Set.copyOf(indices));
                    }
                }

                next.put(entry.getKey(), new Progress(
                        readState(str(one, "state")),
                        one.has("cooldown") ? one.get("cooldown").getAsLong() : 0L,
                        List.copyOf(tasks),
                        one.has("claimable") && one.get("claimable").getAsBoolean(),
                        Map.copyOf(contributors),
                        Set.copyOf(teamClaims),
                        Map.copyOf(claimedBy),
                        lockMap(one, "taskLocks"),
                        lockMap(one, "rewardLocks"),
                        one.has("settled") && one.get("settled").getAsBoolean()));
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

    /**
     * The row locks from the wire: row index -> the condition indices that failed.
     *
     * <p>Absent is unlocked, the opposite default from {@code claimable} and deliberately so: a server
     * that predates conditions sends no key at all, and a client that defaulted to locked would draw
     * every row of every older server's pack as gated.
     */
    private static Map<Integer, List<Integer>> lockMap(JsonObject one, String key) {
        if (!one.has(key) || !one.get(key).isJsonObject()) {
            return Map.of();
        }
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> row : one.getAsJsonObject(key).entrySet()) {
            int index = taskIndex(row.getKey());
            if (index < 0 || !row.getValue().isJsonArray()) {
                continue;
            }
            List<Integer> unmet = new ArrayList<>();
            for (JsonElement value : row.getValue().getAsJsonArray()) {
                // Guarded, like every other read here: a non-number would throw, and this parser's
                // caller clears the whole progress map on a throw -- one bad byte from a foreign server
                // would cost the client every quest's state.
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    unmet.add(value.getAsInt());
                }
            }
            out.put(index, List.copyOf(unmet));
        }
        return Map.copyOf(out);
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

    /**
     * An item id and its optional component patch, resolved for drawing.
     *
     * <p>The one public door into the resolver above, for a caller that holds an id from a tree the
     * cache did not parse into an entry -- the chapter's icon, which the Chapter tab draws from the
     * replica's own JSON. Empty for anything unknown, the same as every other resolution here, so a
     * caller that needs to tell "absent" from "missing" keeps the id beside it.
     */
    public static ItemStack iconOf(String id, JsonElement components) {
        return stack(id, 1, components);
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
