package com.charonsecho;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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
        // The Stygian Orchard: plant Withered Grove trees, harvest Tollfruit.
        Orchard.register();
        Broker.register();
        // The War Below the Moon: the third way to pay Charon.
        War.register();
        // The crypt's day-shelves: a rolling week of the dead.
        Crypt.register();
        // The ledger lectern in the church.
        Church.register();
        // The Scrivener: free blank books for the stories of the dead.
        Scrivener.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            GraveyardTerrain.setSeed(server.overworld().getSeed());
            GraveManager.load(server);
            GraveyardPlots.load(server); // after graves: legacy-field migration reads them
            GhostState.load(server);
            Gravekeepers.load(server);
            StudioSets.load(server);
            StudioMode.loadDynamic(server);
            Orchard.load(server);
            Orchard.detectElder(server);
            War.load(server);
            Church.load(server);
            Church.ensure(server); // the church rises with the terrain
            Crypt.load(server);
            Crypt.ensure(server);  // and the crypt is carved beneath it
            Church.dressLedger(server); // the Book of the Dead on its lectern
            Broker.ensure(server);
            DecorScatter.load(server);
            StudioMode.ensureStamped(server); // the studio always has its grid
            ServerLevel studio = server.getLevel(STUDIO_DIM);
            if (studio != null) {
                StudioMode.restorePlots(studio); // empty plots regrow their builds
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            GraveManager.save();
            GhostState.save();
            DecorScatter.save();
            Orchard.save();
            War.save();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                CharonCommands.register(dispatcher));
    }
}
