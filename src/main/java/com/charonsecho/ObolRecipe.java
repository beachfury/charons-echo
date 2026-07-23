package com.charonsecho;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Survival crafting for Charon's Obol, injected at runtime (the SKE pattern —
 * the recipe RESULT carries full data components via ItemStackTemplate, which
 * a datapack recipe JSON can't do for our marked item):
 *
 *   1 echo shard + 1 gold ingot + 1 soul sand  →  1 Charon's Obol
 */
public final class ObolRecipe {

    private ObolRecipe() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(ObolRecipe::inject);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> inject(server));
    }

    private static void inject(MinecraftServer server) {
        try {
            ShapelessRecipe recipe = new ShapelessRecipe(
                    new Recipe.CommonInfo(true),
                    new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, "charons_echo"),
                    ItemStackTemplate.fromNonEmptyStack(CharonObol.create(1)),
                    List.of(Ingredient.of(Items.ECHO_SHARD),
                            Ingredient.of(Items.GOLD_INGOT),
                            Ingredient.of(Items.SOUL_SAND)));
            RecipeHolder<?> holder = new RecipeHolder<>(
                    ResourceKey.create(Registries.RECIPE,
                            Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, "obol")),
                    recipe);

            RecipeManager manager = server.getRecipeManager();
            Field recipesField = RecipeManager.class.getDeclaredField("recipes");
            recipesField.setAccessible(true);
            RecipeMap current = (RecipeMap) recipesField.get(manager);
            List<RecipeHolder<?>> all = new ArrayList<>(current.values());
            boolean present = all.stream().anyMatch(h -> h.id().equals(holder.id()));
            if (!present) {
                all.add(holder);
                recipesField.set(manager, RecipeMap.create(all));
                manager.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
            }
        } catch (Exception e) {
            System.out.println("[CharonsEcho] obol recipe injection failed: " + e);
        }
    }
}
