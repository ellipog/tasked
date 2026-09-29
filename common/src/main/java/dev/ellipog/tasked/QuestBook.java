package dev.ellipog.tasked;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.event.ArmatureEvents;
import dev.ellipog.armature.api.registry.Registrar;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Tasked's game objects.
 *
 * <p>Registration is the one place the two loaders genuinely differ in mechanism, and this is
 * what exercises Armature's {@link Registrar} on both: Fabric writes to the registry
 * immediately, NeoForge queues the entry on Tasked's own event bus and creates it later.
 *
 * <p><b>Must run during mod construction.</b> On NeoForge the registration window closes
 * before the game finishes loading, and a late call throws. That is a deliberate design in
 * Armature: a clear failure at construction beats an item that silently does not exist.
 */
public final class QuestBook {

    private QuestBook() {
    }

    public static final ResourceLocation ITEM_ID = ResourceLocation.fromNamespaceAndPath(Tasked.MOD_ID, "quest_book");

    /** Held so a later stage can hand it out, or put it in a quest reward. */
    public static Item ITEM;

    public static void register() {
        Registrar registrar = ArmatureApi.registrar().forMod(Tasked.MOD_ID);

        registrar.register(BuiltInRegistries.ITEM, ITEM_ID,
                () -> {
                    Item item = new QuestBookItem(new Item.Properties().stacksTo(1));
                    ITEM = item;
                    return item;
                });
    }
}
