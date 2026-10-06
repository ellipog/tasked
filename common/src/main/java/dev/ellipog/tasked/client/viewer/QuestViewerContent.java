package dev.ellipog.tasked.client.viewer;

import dev.ellipog.tasked.client.viewer.ItemQuestIndex;
import dev.ellipog.tasked.client.viewer.PagePalette;
import dev.ellipog.tasked.client.viewer.QuestContent;
import dev.ellipog.tasked.client.viewer.QuestPage;
import dev.ellipog.tasked.client.viewer.QuestRef;
import dev.ellipog.tasked.client.viewer.QuestRow;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.QuestBook;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.QuestBookScreen;
import dev.ellipog.tasked.progress.QuestState;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tasked's half of the recipe-viewer seam: snapshots built from the synced client cache, and live
 * reads of the same cache while a page is open.
 *
 * <h2>Where the data comes from, and why nothing new is sent</h2>
 *
 * <p>The quest tree and the progress both already reach this client on join, so a viewer is one more
 * reader of {@link ClientQuestCache} rather than a second sync. The snapshot — every quest's page,
 * and the item-to-quest index — is rebuilt on the client thread in {@link #tick()} when the tree
 * revision moves, and nothing else here touches the network.
 *
 * <h2>The two halves, and the threading rule that separates them</h2>
 *
 * <p>{@link #index()} and {@link #pages()} are immutable snapshots, safe to read from any thread —
 * EMI registers its recipes on a worker, and that is the whole reason they exist. The
 * {@code live*} methods read the cache as it is now and are client-thread only; a viewer calls them
 * while it draws.
 *
 * <h2>What a viewer page shows, and what only the book shows</h2>
 *
 * <p>A page carries the rows that reference an item — item tasks, tag tasks, item rewards — because
 * those are what the viewer is for: a lookup answers "what wants this, and what gives it". A text
 * task (xp, checkmark, advancement, stat, …) or a non-item reward has no item to look up and is left
 * to the book, and the sections are labelled so a reward is never read as something to hand in. A
 * quest with no item rows at all has no page: nothing could ever lead a player to it, and a blank
 * page is worse than none.
 *
 * <p>Only an item id and a tag id are structured on the wire, so those are what the index holds; a
 * fluid, a dimension, a stat is a sentence with no item in it. A quest icon is not a lookup either —
 * a quest whose icon happens to be a diamond is not a quest about diamonds. Reward tables and choice
 * rewards are out for a different reason: what they will hand out is not known until the roll.
 */
public final class QuestViewerContent implements QuestContent {

    /** The viewer category's id. The words are Tasked's, so the namespace is too. */
    public static final ResourceLocation CATEGORY_ID =
            ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "quests");

    private volatile ItemQuestIndex index = ItemQuestIndex.empty();
    private volatile List<QuestPage> pages = List.of();
    private volatile long builtRevision = -1L;

    @Override
    public void tick() {
        long revision = ClientQuestCache.treeRevision();
        if (revision == builtRevision) {
            return;
        }
        rebuild(revision, QuestViewerContent::registryTagItems);
    }

    @Override
    public long revision() {
        return builtRevision;
    }

    @Override
    public ItemQuestIndex index() {
        return index;
    }

    @Override
    public List<QuestPage> pages() {
        return pages;
    }

    @Override
    public QuestRow liveTask(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || index < 0 || index >= entry.tasks().size()) {
            return blankRow();
        }
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        int have = ClientQuestCache.taskProgressOf(questId, index);
        boolean locked = !ClientQuestCache.taskLockOf(questId, index).isEmpty();
        // `taskDone` rather than `have >= task.count()` written here: the claim menu's progression
        // column asks the same question about the same task, and one rule with two spellings is how a
        // page and a column come to disagree about whether a task is finished.
        return new QuestRow(taskIcon(task), taskLabel(task), have, task.count(),
                ClientQuestCache.taskDone(questId, index), locked, task.tagId(), index);
    }

    @Override
    public QuestRow liveReward(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || index < 0 || index >= entry.rewards().size()) {
            return blankRow();
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        // Null in a headless JVM (and a client with no player yet), where no claim can be known:
        // the row then reads unlocked-and-unclaimed rather than crashing a viewer.
        Minecraft minecraft = Minecraft.getInstance();
        UUID player = minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
        boolean claimed = player != null && ClientQuestCache.rewardClaimedBy(player, questId, index);
        boolean locked = !ClientQuestCache.rewardLockOf(questId, index).isEmpty();
        boolean claimable = !claimed && !locked
                && ClientQuestCache.stateOf(questId) == QuestState.COMPLETED;
        // No count on a reward row: a "0 / 1" under a reward reads as a task still owed, and the
        // status the adapters draw in its place says what the row actually is.
        return new QuestRow(rewardIcon(reward), rewardLabel(reward), 0, 0,
                claimed, locked, claimable, "", index);
    }

    @Override
    public String stateText(String questId) {
        return QuestBookScreen.stateLabel(ClientQuestCache.stateOf(questId));
    }

    @Override
    public int stateColour(String questId) {
        return switch (ClientQuestCache.stateOf(questId)) {
            case COMPLETED -> PagePalette.COMPLETE;
            case STARTED -> PagePalette.PROGRESS;
            case UNLOCKED -> PagePalette.AVAILABLE;
            case LOCKED -> PagePalette.LOCKED;
        };
    }

    @Override
    public Component rewardStatusLabel(RewardStatus status) {
        return switch (status) {
            case READY -> Component.translatableWithFallback("tasked.viewer.ready", "Ready");
            case LOCKED -> Component.translatableWithFallback("tasked.viewer.locked", "Locked");
            case CLAIMED -> Component.translatableWithFallback("tasked.viewer.claimed", "Claimed");
        };
    }

    @Override
    public void openQuest(String questId) {
        QuestBookScreen.openOn(questId);
    }

    @Override
    public ResourceLocation categoryId() {
        return CATEGORY_ID;
    }

    @Override
    public Component categoryTitle() {
        return Component.translatableWithFallback("tasked.viewer.category", "Quests");
    }

    @Override
    public Component tasksLabel() {
        return Component.translatableWithFallback("tasked.viewer.tasks", "Tasks");
    }

    @Override
    public Component rewardsLabel() {
        return Component.translatableWithFallback("tasked.viewer.rewards", "Rewards");
    }

    @Override
    public ItemStack categoryIcon() {
        return QuestBook.ITEM == null ? new ItemStack(Items.WRITABLE_BOOK) : new ItemStack(QuestBook.ITEM);
    }

    // ------------------------------------------------------------------
    // The snapshot
    // ------------------------------------------------------------------

    /**
     * Rebuilds both snapshots from the cache as it stands.
     *
     * <p>Package-private with the tag resolver as a parameter so a test can supply a fake one: a test
     * JVM loads no datapack, so {@code BuiltInRegistries.ITEM.getTag} answers empty there, and the
     * expansion — the part that turns {@code #minecraft:logs} into the items a viewer can find — would
     * otherwise be untestable. Production passes {@link #registryTagItems}.
     */
    void rebuild(long revision, Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        ItemQuestIndex.Builder index = ItemQuestIndex.builder();
        List<QuestPage> built = new ArrayList<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            QuestRef ref = new QuestRef(entry.id(), entry.title(), entry.chapterTitle(),
                    entry.icon(), entry.iconId());
            List<QuestRow> tasks = taskRows(entry, ref, index, tagItems);
            List<QuestRow> rewards = rewardRows(entry, ref, index);
            if (!tasks.isEmpty() || !rewards.isEmpty()) {
                // A page with nothing to show is one a player could never be led to by an item, and a
                // blank page is worse than no page.
                built.add(new QuestPage(ref, tasks, rewards));
            }
        }
        pages = List.copyOf(built);
        this.index = index.build();
        builtRevision = revision;
    }

    private static List<QuestRow> taskRows(ClientQuestCache.Entry entry, QuestRef ref,
                                           ItemQuestIndex.Builder index,
                                           Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        List<QuestRow> rows = new ArrayList<>(entry.tasks().size());
        for (int source = 0; source < entry.tasks().size(); source++) {
            ClientQuestCache.TaskEntry task = entry.tasks().get(source);
            if (!referencesAnItem(task.hasItem(), task.itemId(), task.tagId())) {
                continue;
            }
            rows.add(new QuestRow(taskIcon(task), taskLabel(task), 0, task.count(),
                    false, false, task.tagId(), source));
            addItem(index, task.itemId(), ref, true, false);
            addTag(index, task.tagId(), ref, tagItems);
        }
        return rows;
    }

    private static List<QuestRow> rewardRows(ClientQuestCache.Entry entry, QuestRef ref,
                                             ItemQuestIndex.Builder index) {
        List<QuestRow> rows = new ArrayList<>(entry.rewards().size());
        for (int source = 0; source < entry.rewards().size(); source++) {
            ClientQuestCache.RewardEntry reward = entry.rewards().get(source);
            if (!referencesAnItem(reward.hasItem(), reward.itemId(), "")) {
                continue;
            }
            rows.add(new QuestRow(rewardIcon(reward), rewardLabel(reward), 0, reward.count(),
                    false, false, "", source));
            addItem(index, reward.itemId(), ref, false, true);
        }
        return rows;
    }

    /**
     * Whether a row belongs on a viewer page: it names an item, or a tag, in any form.
     *
     * <p>{@code hasItem} is a resolved stack, {@code itemId} an id that may not resolve in this build
     * and {@code tagId} a tag — all three are item references, and a missing item keeps its id rather
     * than losing its row, the same rule the book follows.
     */
    private static boolean referencesAnItem(boolean hasItem, String itemId, String tagId) {
        return hasItem || !itemId.isEmpty() || !tagId.isEmpty();
    }

    private static void addItem(ItemQuestIndex.Builder index, String itemId, QuestRef ref,
                                boolean required, boolean awarded) {
        if (itemId == null || itemId.isEmpty()) {
            return;
        }
        ResourceLocation item = ResourceLocation.tryParse(itemId);
        if (item == null) {
            // A hand-edited file can carry an id that does not parse; the validator warns about it and
            // the quest stays loadable, so the index skips the entry rather than the page.
            return;
        }
        index.add(item, ref, required, awarded);
    }

    private static void addTag(ItemQuestIndex.Builder index, String tagId, QuestRef ref,
                               Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        if (tagId == null || tagId.isEmpty()) {
            return;
        }
        ResourceLocation tag = ResourceLocation.tryParse(tagId);
        if (tag == null) {
            return;
        }
        for (ResourceLocation item : tagItems.apply(TagKey.create(Registries.ITEM, tag))) {
            index.add(item, ref, true, false);
        }
    }

    /**
     * The tag's members, from the client's own item registry.
     *
     * <p>Client-side tags are synced on join, so this is reading data the client already has — the
     * same registry the picker and the item stack resolution read. A tag nobody declared answers
     * empty, which leaves the quest findable from its label but not from an item, which is the honest
     * reading of a file that names a tag no pack provides.
     */
    private static List<ResourceLocation> registryTagItems(TagKey<Item> tag) {
        Optional<HolderSet.Named<Item>> holders = BuiltInRegistries.ITEM.getTag(tag);
        if (holders.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> items = new ArrayList<>();
        for (Holder<Item> holder : holders.get()) {
            holder.unwrapKey().map(ResourceKey::location).ifPresent(items::add);
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Small conversions
    // ------------------------------------------------------------------

    private static ItemStack taskIcon(ClientQuestCache.TaskEntry task) {
        return task.hasItem() ? task.item() : ItemStack.EMPTY;
    }

    private static ItemStack rewardIcon(ClientQuestCache.RewardEntry reward) {
        return reward.hasItem() ? reward.item() : ItemStack.EMPTY;
    }

    /**
     * A task row's label: the item's name when it resolved, its id when it did not, and the task's own
     * sentence otherwise.
     *
     * <p>The id case matters: an item this build does not have still names an item, and its row has no
     * icon, so the id is what tells the player which item the quest is about.
     */
    private static String taskLabel(ClientQuestCache.TaskEntry task) {
        if (task.hasItem()) {
            return task.text().getString();
        }
        if (!task.itemId().isEmpty()) {
            return task.itemId();
        }
        return task.text().getString();
    }

    /** A reward row's label, with the same missing-item rule as a task's. */
    private static String rewardLabel(ClientQuestCache.RewardEntry reward) {
        if (reward.hasItem()) {
            return reward.text().getString();
        }
        if (!reward.itemId().isEmpty()) {
            return reward.itemId();
        }
        return reward.text().getString();
    }

    private static QuestRow blankRow() {
        return new QuestRow(ItemStack.EMPTY, "", 0, 0, false, false, "");
    }
}
