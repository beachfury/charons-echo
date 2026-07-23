package com.charonsecho;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Echo Shard — Charon's fare, the obol. A server-side custom item (marked
 * amethyst shard, so vanilla + Bedrock clients render it with no client mod):
 * soul-bound through death, consumed at the death portal for free passage.
 * Die without one and Charon takes his toll instead.
 */
public final class EchoShard {

    private static final String MARKER = "charons_echo_shard";

    private EchoShard() {}

    public static ItemStack create(int count) {
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Echo Shard")
                .withStyle(ChatFormatting.AQUA));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Charon's fare. The one thing")
                        .withStyle(ChatFormatting.DARK_PURPLE),
                Component.literal("death cannot take from you.")
                        .withStyle(ChatFormatting.DARK_PURPLE))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARKER, true));
        return stack;
    }

    public static boolean isShard(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.AMETHYST_SHARD)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MARKER, false);
    }
}
