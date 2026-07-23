package com.charonsecho;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class CharonsEcho implements ModInitializer {

    public static final String MOD_ID = "charons_echo";

    /** The graveyard dimension (data/charons_echo/dimension/graveyard.json). */
    public static final ResourceKey<Level> GRAVEYARD_DIM = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(MOD_ID, "graveyard"));

    /** The Studio authoring dimension (data/charons_echo/dimension/studio.json). */
    public static final ResourceKey<Level> STUDIO_DIM = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(MOD_ID, "studio"));

    @Override
    public void onInitialize() {
        // Live settings (config/charons-echo.properties).
        CharonConfig.load();

        // Real chunk generator — terrain is built at the noise stage so vanilla
        // computes lighting/heightmaps normally (see GraveyardChunkGenerator).
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
                Identifier.fromNamespaceAndPath(MOD_ID, "graveyard"),
                GraveyardChunkGenerator.CODEC);

        // The death loop: Charon takes the goods, the player rises as a ghost.
        DeathHandler.register();
        DeathWake.register();
        GhostState.register();
        // No damage in the world of the dead; Gravekeepers stay passive.
        GraveyardRules.register();
        // Survival crafting for the Ferryman's fare.
        ObolRecipe.register();
        // Death + return portals (particles + proximity, no blocks).
        PortalManager.register();
        // Seed-deterministic decoration scatter (trees, clutter, ruins).
        DecorScatter.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            GraveyardTerrain.setSeed(server.overworld().getSeed());
            GraveManager.load(server);
            GraveyardPlots.load(server); // after graves: legacy-field migration reads them
            GhostState.load(server);
            Gravekeepers.load(server);
            StudioSets.load(server);
            StudioMode.loadDynamic(server);
            DecorScatter.load(server);
            StudioMode.ensureStamped(server); // the studio always has its grid
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            GraveManager.save();
            GhostState.save();
            DecorScatter.save();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                CharonCommands.register(dispatcher));
    }
}
