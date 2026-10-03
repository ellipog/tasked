package dev.ellipog.tasked.client.viewer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-to-quest lookup every viewer reads: roles kept apart, order kept stable, one quest once.
 *
 * <p>No Minecraft is booted: an index entry needs a {@link ResourceLocation} and a {@link QuestRef},
 * and a reference's icon is allowed to be {@link ItemStack#EMPTY} -- which is also the shape a pack
 * with a missing item produces, so the empty-stack case is exercised rather than avoided.
 */
class ItemQuestIndexTest {

    @BeforeAll
    static void bootstrap() {
        // An empty ItemStack still reads a registry key while its class initialises.
        MinecraftTestBootstrap.boot();
    }

    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:stone");
    private static final ResourceLocation DIAMOND = ResourceLocation.parse("minecraft:diamond");

    private static QuestRef quest(String id) {
        return new QuestRef(id, "Quest " + id, "Chapter", ItemStack.EMPTY, "");
    }

    @Test
    @DisplayName("questsUsing returns only the quests that require the item, in insertion order")
    void usesAreOnlyRequiredEntries() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("a"), true, false)
                .add(DIAMOND, quest("b"), false, true)
                .add(STONE, quest("c"), true, false)
                .build();

        assertEquals(List.of("a", "c"), index.questsUsing(STONE).stream().map(QuestRef::id).toList());
        assertTrue(index.questsUsing(DIAMOND).isEmpty(),
                "b awards the diamond and does not use it");
    }

    @Test
    @DisplayName("questsAwarding returns only the quests that award the item")
    void awardsAreOnlyAwardedEntries() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(DIAMOND, quest("a"), true, false)
                .add(DIAMOND, quest("b"), false, true)
                .build();

        assertEquals(List.of("b"), index.questsAwarding(DIAMOND).stream().map(QuestRef::id).toList());
    }

    @Test
    @DisplayName("questsFor puts the required quests first and never lists one twice")
    void forIsRequiredFirstAndDeduped() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("both"), true, true)
                .add(STONE, quest("uses"), true, false)
                .add(STONE, quest("awards"), false, true)
                .build();

        assertEquals(List.of("both", "uses", "awards"),
                index.questsFor(STONE).stream().map(QuestRef::id).toList(),
                "the quest that both uses and awards the item appears once, in the required pass");
    }

    @Test
    @DisplayName("an item nobody references answers empty, and items() holds exactly the keys")
    void unknownItemsAndTheKeySet() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("a"), true, false)
                .build();

        assertTrue(index.questsFor(DIAMOND).isEmpty());
        assertTrue(index.questsUsing(DIAMOND).isEmpty());
        assertTrue(index.questsAwarding(DIAMOND).isEmpty());
        assertEquals(java.util.Set.of(STONE), index.items());
        assertFalse(index.isEmpty());
        assertTrue(ItemQuestIndex.empty().isEmpty(),
                "the empty index is the one an adapter reads before the tree arrives");
        assertTrue(ItemQuestIndex.empty().questsFor(STONE).isEmpty());
    }

    @Test
    @DisplayName("an entry with neither role is a caller bug and is refused")
    void anEntryWithNeitherRoleIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemQuestIndex.Entry(STONE, quest("a"), false, false),
                "an entry that neither requires nor awards would only ever answer 'no quests'");
    }
}
