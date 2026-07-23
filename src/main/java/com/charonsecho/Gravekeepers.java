package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The gravekeeper roster: trusted builders who may build in Charon's Echo and
 * use the Studio without being ops. Managed with /charon builder add|remove.
 */
public final class Gravekeepers {

    private static final Set<UUID> KEEPERS = ConcurrentHashMap.newKeySet();
    private static Path file;

    private Gravekeepers() {}

    public static boolean isKeeper(UUID uuid) {
        return KEEPERS.contains(uuid);
    }

    /** Build rights + Studio access: gamemaster or roster member. */
    public static boolean canBuild(ServerPlayer player) {
        return GraveyardRules.isGamemaster(player) || isKeeper(player.getUUID());
    }

    public static boolean add(UUID uuid) {
        boolean added = KEEPERS.add(uuid);
        if (added) save();
        return added;
    }

    public static boolean remove(UUID uuid) {
        boolean removed = KEEPERS.remove(uuid);
        if (removed) save();
        return removed;
    }

    public static void load(MinecraftServer server) {
        KEEPERS.clear();
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("keepers.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (Tag t : root.getListOrEmpty("keepers")) {
                try {
                    KEEPERS.add(UUID.fromString(t.asString().orElse("")));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load keepers.dat: " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            ListTag list = new ListTag();
            for (UUID uuid : KEEPERS) {
                list.add(StringTag.valueOf(uuid.toString()));
            }
            CompoundTag root = new CompoundTag();
            root.put("keepers", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save keepers.dat: " + e);
        }
    }
}
