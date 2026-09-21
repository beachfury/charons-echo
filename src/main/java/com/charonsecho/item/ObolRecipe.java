package com.charonsecho.item;

import com.charonsecho.CharonsEcho;

import java.lang.reflect.Field;
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

    /**
     * Four TOLLFRUIT (our marked froglights) — vanilla ingredients ignore
     * components, so the shapeless match is tightened to require the marker.
     * A pile of plain ochre froglights buys nothing from the Ferryman.
     */
    private static final class TollfruitRecipe extends ShapelessRecipe {
        TollfruitRecipe() {
            super(new Recipe.CommonInfo(true),
                    new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, "charons_echo"),
                    ItemStackTemplate.fromNonEmptyStack(CharonObol.create(1)),
                    List.of(Ingredient.of(Items.OCHRE_FROGLIGHT),
                            Ingredient.of(Items.OCHRE_FROGLIGHT),
                            Ingredient.of(Items.OCHRE_FROGLIGHT),
                            Ingredient.of(Items.OCHRE_FROGLIGHT)));
        }

        @Override
        public boolean matches(net.minecraft.world.item.crafting.CraftingInput input,
                               net.minecraft.world.level.Level level) {
            if (!super.matches(input, level)) return false;
            for (int i = 0; i < input.size(); i++) {
                var stack = input.getItem(i);
                if (!stack.isEmpty() && !StygianItems.isTollfruit(stack)) return false;
            }
            return true;
        }
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
            RecipeHolder<?> fruitHolder = new RecipeHolder<>(
                    ResourceKey.create(Registries.RECIPE,
                            Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, "tollfruit_obol")),
                    new TollfruitRecipe());

            RecipeManager manager = server.getRecipeManager();
            Field recipesField = RecipeManager.class.getDeclaredField("recipes");
            recipesField.setAccessible(true);
            RecipeMap current = (RecipeMap) recipesField.get(manager);
            // 26.3: RecipeMap.create wants a registry lookup, not a list —
            // so the merged map is rebuilt through the same private pair of
            // collections the loader fills (byType, byKey).
            Field byTypeField = RecipeMap.class.getDeclaredField("byType");
            Field byKeyField = RecipeMap.class.getDeclaredField("byKey");
            byTypeField.setAccessible(true);
            byKeyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var byType = com.google.common.collect.LinkedHashMultimap.create(
                    (com.google.common.collect.Multimap<net.minecraft.world.item.crafting.RecipeType<?>,
                            RecipeHolder<?>>) byTypeField.get(current));
            @SuppressWarnings("unchecked")
            var byKey = new java.util.HashMap<>(
                    (java.util.Map<ResourceKey<Recipe<?>>, RecipeHolder<?>>) byKeyField.get(current));
            boolean changed = false;
            for (RecipeHolder<?> h : List.of(holder, fruitHolder)) {
                if (!byKey.containsKey(h.id())) {
                    byKey.put(h.id(), h);
                    byType.put(h.value().getType(), h);
                    changed = true;
                }
            }
            if (changed) {
                var ctor = RecipeMap.class.getDeclaredConstructor(
                        com.google.common.collect.Multimap.class, java.util.Map.class);
                ctor.setAccessible(true);
                recipesField.set(manager, ctor.newInstance(byType, byKey));
                manager.finalizeRecipeLoading(server.getWorldData().enabledFeatures());
            }
        } catch (Exception e) {
            System.out.println("[CharonsEcho] obol recipe injection failed: " + e);
        }
    }
}
