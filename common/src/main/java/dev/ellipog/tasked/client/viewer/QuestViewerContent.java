package dev.ellipog.tasked.client.viewer;

import dev.ellipog.armature.integration.ItemQuestIndex;
import dev.ellipog.armature.integration.QuestContent;
import dev.ellipog.armature.integration.QuestPage;
import dev.ellipog.armature.integration.QuestRef;
import dev.ellipog.armature.integration.QuestRow;
import dev.ellipog.tasked.Tasked;
import dev.ellipog.tasked.QuestBook;
import dev.ellipog.tasked.client.ClientQuestCache;
import dev.ellipog.tasked.client.QuestBookScreen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * while it draws. Splitting them any other way would mean either a page that shows the numbers from
 * when it was registered, or a worker thread reading the game state.
 *
 * <h2>What is deliberately not a lookup</h2>
 *
 * <p>An item id and a tag id are the only structured item references on the wire, so those are what
 * the index holds; a fluid, a dimension, a stat is a sentence with no item in it. A quest icon is
 * not a lookup either — a quest whose icon happens to be a diamond is not a quest about diamonds.
 * Reward tables and choice rewards are out for a different reason: what they will hand out is not
 * known until the roll, so a viewer cannot honestly say an item is awarded by a quest that might
 * never produce it.
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
        return new QuestRow(taskIcon(task), task.text().getString(), have, task.count(),
                have >= task.count(), locked, task.tagId());
    }

    @Override
    public QuestRow liveReward(String questId, int index) {
        ClientQuestCache.Entry entry = ClientQuestCache.entry(questId);
        if (entry == null || index < 0 || index >= entry.rewards().size()) {
            return blankRow();
        }
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        boolean locked = !ClientQuestCache.rewardLockOf(questId, index).isEmpty();
        return new QuestRow(rewardIcon(reward), reward.text().getString(), 0, reward.count(),
                false, locked, "");
    }

    @Override
    public String stateText(String questId) {
        return QuestBookScreen.stateLabel(ClientQuestCache.stateOf(questId));
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
            built.add(new QuestPage(ref, taskRows(entry, ref, index, tagItems), rewardRows(entry, ref, index)));
        }
        pages = List.copyOf(built);
        this.index = index.build();
        builtRevision = revision;
    }

    private static List<QuestRow> taskRows(ClientQuestCache.Entry entry, QuestRef ref,
                                           ItemQuestIndex.Builder index,
                                           Function<TagKey<Item>, List<ResourceLocation>> tagItems) {
        List<QuestRow> rows = new ArrayList<>(entry.tasks().size());
        for (ClientQuestCache.TaskEntry task : entry.tasks()) {
            rows.add(new QuestRow(taskIcon(task), task.text().getString(), 0, task.count(),
                    false, false, task.tagId()));
            addItem(index, task.itemId(), ref, true, false);
            addTag(index, task.tagId(), ref, tagItems);
        }
        return rows;
    }

    private static List<QuestRow> rewardRows(ClientQuestCache.Entry entry, QuestRef ref,
                                             ItemQuestIndex.Builder index) {
        List<QuestRow> rows = new ArrayList<>(entry.rewards().size());
        for (ClientQuestCache.RewardEntry reward : entry.rewards()) {
            rows.add(new QuestRow(rewardIcon(reward), reward.text().getString(), 0, reward.count(),
                    false, false, ""));
            addItem(index, reward.itemId(), ref, false, true);
        }
        return rows;
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
     *
     * <p>An empty tag is re-read on the next tree revision rather than watched live: tags arrive
     * before quests do (both hang off the join), and a {@code /reload} that changes a tag also
     * re-syncs recipes, which is what the viewers reload on anyway.
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
        return task.hasItem() ? task.item() : task.icon();
    }

    private static ItemStack rewardIcon(ClientQuestCache.RewardEntry reward) {
        return reward.hasItem() ? reward.item() : reward.icon();
    }

    private static QuestRow blankRow() {
        return new QuestRow(ItemStack.EMPTY, "", 0, 0, false, false, "");
    }
}
