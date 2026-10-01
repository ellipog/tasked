package dev.ellipog.tasked.quest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item reference with custom data: the patch rides the codec and reaches the stack.
 *
 * <h2>Why this is the load-bearing test for components</h2>
 *
 * <p>Everything else about custom data is bookkeeping -- fields on records, strings on a wire. What
 * has to be true is that the datapack's own component spelling survives the quest file's codec and
 * comes out on an {@link ItemStack}, because that stack is what a task counts against and what a
 * reward hands over. {@code ItemTask} and {@code ProgressService} already compare with
 * {@code isSameItemSameComponents}, so the day this passes, strict matching of custom items is
 * already the behaviour -- the second test says so out loud.
 */
@DisplayName("an item reference with custom data")
class ItemRefTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // The registry the reference resolves against.
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("a component patch rides the codec and reaches the stack")
    void componentsReachTheStack() {
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.DAMAGE, 5)
                .build();
        ItemRef ref = new ItemRef(ResourceLocation.withDefaultNamespace("diamond_sword"), 2, patch);

        JsonElement wire = ItemRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).getOrThrow();
        assertTrue(wire.getAsJsonObject().has("components"),
                "the patch travels as its own field, in the same shape a quest file uses");
        ItemRef read = ItemRef.CODEC.parse(JsonOps.INSTANCE, wire).getOrThrow();

        assertEquals(ResourceLocation.withDefaultNamespace("diamond_sword"), read.item());
        assertEquals(2, read.toStack().getCount(), "count and all");
        assertEquals(5, read.toStack().getDamageValue(), "the stack arrives damaged, as it was written");
    }

    @Test
    @DisplayName("a plain reference has no patch, and a damaged one is not the same stack")
    void componentsMakeADifferentStack() {
        ItemRef plain = new ItemRef(ResourceLocation.withDefaultNamespace("diamond_sword"), 1);
        assertTrue(plain.components().isEmpty(), "nothing custom, nothing to carry");
        assertTrue(ItemStack.isSameItemSameComponents(new ItemStack(Items.DIAMOND_SWORD),
                        plain.toStack()),
                "so the template is the plain stack");

        ItemRef damaged = new ItemRef(ResourceLocation.withDefaultNamespace("diamond_sword"), 1,
                DataComponentPatch.builder().set(DataComponents.DAMAGE, 3).build());
        assertFalse(ItemStack.isSameItemSameComponents(damaged.toStack(), plain.toStack()),
                "which is why a task naming the damaged sword is not satisfied by a pristine one -- "
                        + "the strict comparison every counting path already uses");
    }

    @Test
    @DisplayName("a custom name in a file is written the datapack's way, and comes out as the name")
    void aCustomNameInAFileReads() {
        // The one spelling question in this feature, answered by running it rather than by recalling
        // a wiki: what does `minecraft:custom_name` look like in quest JSON? This is the example the
        // schema and the worked examples use.
        ItemRef ref = ItemRef.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("""
                        {"item": "minecraft:diamond_sword",
                         "components": {"minecraft:custom_name": "Renamed"}}""")).getOrThrow();

        assertEquals("Renamed", ref.toStack().getHoverName().getString(),
                "a plain JSON string is a plain text component on JsonOps");
    }

    @Test
    @DisplayName("an unknown item still resolves to nothing, patch or no patch")
    void unknownItemsStayEmpty() {
        ItemRef gone = new ItemRef(ResourceLocation.fromNamespaceAndPath("someothermod", "widget"), 1,
                DataComponentPatch.builder().set(DataComponents.DAMAGE, 1).build());

        assertFalse(gone.isKnown(), "the mod is not installed");
        assertTrue(gone.toStack().isEmpty(), "and a missing item stays missing, components and all");
    }
}
