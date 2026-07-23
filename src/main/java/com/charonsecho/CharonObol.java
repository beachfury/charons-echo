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
 * Charon's Obol — the Ferryman's fare, named for the coin the ancient Greeks
 * placed with their dead. A server-side custom item on the vanilla echo shard
 * (the sculk-teal texture fits the graveyard, and vanilla + Bedrock clients
 * render it with no client mod): soul-bound through death, consumed at the
 * death portal for free passage. Die without one and Charon takes his toll.
 */
public final class CharonObol {

    private static final String MARKER = "charons_echo_obol";
    /** Old marker from when the item was amethyst-based — still honored. */
    private static final String LEGACY_MARKER = "charons_echo_shard";

    private CharonObol() {}

    public static ItemStack create(int count) {
        ItemStack stack = new ItemStack(Items.ECHO_SHARD, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Charon's Obol")
                .withStyle(ChatFormatting.DARK_AQUA));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("The Ferryman's fare. The one thing")
                        .withStyle(ChatFormatting.DARK_PURPLE),
                Component.literal("death cannot take from you.")
                        .withStyle(ChatFormatting.DARK_PURPLE))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARKER, true));
        return stack;
    }

    public static boolean isObol(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        var tag = data.copyTag();
        return tag.getBooleanOr(MARKER, false) || tag.getBooleanOr(LEGACY_MARKER, false);
    }
}
