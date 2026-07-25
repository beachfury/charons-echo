package com.charonsecho;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Stygian Orchard's two items, both server-side marked vanilla items in
 * the obol tradition:
 *
 *  - Stygian Seed  — a sculk sensor that listened too long at the river's
 *    edge, and took root. Sold by the Broker; plants a Withered Grove tree.
 *  - Tollfruit     — an ochre froglight cut ripe from an elder's chains.
 *    Four of them craft one Charon's Obol.
 *
 * Elder-lineage seeds carry a glint and a lineage id; the mother seed adds
 * one unreadable line. NONE of that is explained anywhere — the items simply
 * differ, and players do the rest.
 */
public final class StygianItems {

    private static final String SEED_MARKER = "charons_echo_seed";
    private static final String FRUIT_MARKER = "charons_echo_tollfruit";
    private static final String ELDER_KEY = "charons_echo_lineage";
    private static final String MOTHER_KEY = "charons_echo_mother";

    private StygianItems() {}

    // ---- Stygian Seed ----

    public static ItemStack seed(int count) {
        return seedStack(count, null, false);
    }

    public static ItemStack elderSeed(UUID lineageId) {
        return seedStack(1, lineageId, false);
    }

    public static ItemStack motherSeed(UUID lineageId) {
        return seedStack(1, lineageId, true);
    }

    private static ItemStack seedStack(int count, UUID lineageId, boolean mother) {
        ItemStack stack = new ItemStack(Items.SCULK_SENSOR, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Stygian Seed")
                .withStyle(ChatFormatting.DARK_AQUA));
        List<Component> lore = new ArrayList<>(List.of(
                Component.literal("A sensor that listened too long")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("at the river's edge, and took root.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("Plant it, and be patient.")
                        .withStyle(ChatFormatting.GRAY)));
        if (mother) {
            lore.add(Component.literal("the first of the withered line")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.OBFUSCATED));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        if (lineageId != null) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(SEED_MARKER, true);
            if (lineageId != null) {
                tag.putString(ELDER_KEY, lineageId.toString());
                if (mother) tag.putBoolean(MOTHER_KEY, true);
            }
        });
        return stack;
    }

    public static boolean isSeed(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(SEED_MARKER, false);
    }

    /** Lineage id carried by an elder/mother seed, or null for a plain seed. */
    public static UUID lineageOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String id = data.copyTag().getStringOr(ELDER_KEY, "");
        if (id.isEmpty()) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isMotherSeed(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(MOTHER_KEY, false);
    }

    // ---- Tollfruit ----

    public static ItemStack tollfruit(int count) {
        ItemStack stack = new ItemStack(Items.OCHRE_FROGLIGHT, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Tollfruit")
                .withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("It ripened in the dark, on a chain,")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("and it is warm to the touch.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("The Ferryman accepts four.")
                        .withStyle(ChatFormatting.GRAY))));
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(FRUIT_MARKER, true));
        return stack;
    }

    public static boolean isTollfruit(ItemStack stack) {
        if (stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBooleanOr(FRUIT_MARKER, false);
    }
}
