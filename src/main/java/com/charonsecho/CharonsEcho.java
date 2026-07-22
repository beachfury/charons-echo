package com.charonsecho;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
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
        // Hills, river, and church plateau are sculpted as graveyard chunks generate.
        ServerChunkEvents.CHUNK_GENERATE.register(GraveyardTerrain::onGenerate);

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
                CharonCommands.register(dispatcher));
    }
}
