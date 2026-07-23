package com.charonsecho;

import com.mojang.serialization.DynamicOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Grave records: what each death took, where, and whether it was reclaimed.
 * Persisted as compressed NBT at world/charons_echo/graves.dat — items keep
 * their full data components via ItemStack.OPTIONAL_CODEC.
 */
public final class GraveManager {

    public static final class Grave {
        public final UUID id;
        public final UUID owner;
        public final String ownerName;
        public final String dimension;   // dimension id string, e.g. "minecraft:overworld"
        public final BlockPos pos;       // death position
        public final String causeLine;   // localized death message ("BeachFury was blown up by Creeper")
        public final long gameTime;      // overworld game time at death
        public int xpLevels;
        public final float xpProgress;
        public final List<ItemStack> items;
        public boolean claimed;
        /** Global plot index in the graveyard; -1 until the ghost first crosses. */
        public int plotIndex = -1;
        /** XP levels may be halved by Charon's toll before reclaim. */

        public Grave(UUID id, UUID owner, String ownerName, String dimension, BlockPos pos,
                     String causeLine, long gameTime, int xpLevels, float xpProgress,
                     List<ItemStack> items, boolean claimed) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.dimension = dimension;
            this.pos = pos;
            this.causeLine = causeLine;
            this.gameTime = gameTime;
            this.xpLevels = xpLevels;
            this.xpProgress = xpProgress;
            this.items = items;
            this.claimed = claimed;
        }
    }

    private static final List<Grave> GRAVES = new CopyOnWriteArrayList<>();
    private static MinecraftServer server;

    private GraveManager() {}

    public static void load(MinecraftServer srv) {
        server = srv;
        GRAVES.clear();
        Path file = dataFile(srv);
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
            for (Tag t : root.getListOrEmpty("graves")) {
                if (!(t instanceof CompoundTag g)) continue;
                List<ItemStack> items = new ArrayList<>();
                for (Tag it : g.getListOrEmpty("items")) {
                    ItemStack.OPTIONAL_CODEC.parse(ops, it).result().ifPresent(items::add);
                }
                Grave grave = new Grave(
                        UUID.fromString(g.getStringOr("id", UUID.randomUUID().toString())),
                        UUID.fromString(g.getStringOr("owner", new UUID(0, 0).toString())),
                        g.getStringOr("ownerName", "?"),
                        g.getStringOr("dimension", "minecraft:overworld"),
                        new BlockPos(g.getIntOr("x", 0), g.getIntOr("y", 64), g.getIntOr("z", 0)),
                        g.getStringOr("cause", ""),
                        g.getLongOr("gameTime", 0L),
                        g.getIntOr("xpLevels", 0),
                        g.getFloatOr("xpProgress", 0f),
                        items,
                        g.getBooleanOr("claimed", false));
                grave.plotIndex = g.getIntOr("plotIndex", -1);
                GRAVES.add(grave);
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load graves.dat: " + e);
        }
    }

    public static void save() {
        if (server == null) return;
        try {
            Path file = dataFile(server);
            Files.createDirectories(file.getParent());
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
            ListTag list = new ListTag();
            for (Grave g : GRAVES) {
                CompoundTag t = new CompoundTag();
                t.putString("id", g.id.toString());
                t.putString("owner", g.owner.toString());
                t.putString("ownerName", g.ownerName);
                t.putString("dimension", g.dimension);
                t.putInt("x", g.pos.getX());
                t.putInt("y", g.pos.getY());
                t.putInt("z", g.pos.getZ());
                t.putString("cause", g.causeLine);
                t.putLong("gameTime", g.gameTime);
                t.putInt("xpLevels", g.xpLevels);
                t.putFloat("xpProgress", g.xpProgress);
                t.putBoolean("claimed", g.claimed);
                t.putInt("plotIndex", g.plotIndex);
                ListTag items = new ListTag();
                for (ItemStack stack : g.items) {
                    ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent(items::add);
                }
                t.put("items", items);
                list.add(t);
            }
            CompoundTag root = new CompoundTag();
            root.put("graves", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save graves.dat: " + e);
        }
    }

    private static Path dataFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("graves.dat");
    }

    public static void add(Grave grave) {
        GRAVES.add(grave);
        save();
    }

    /** Oldest unclaimed grave for a player — the one the soul wisp leads to first. */
    public static Optional<Grave> oldestUnclaimed(UUID owner) {
        return GRAVES.stream()
                .filter(g -> g.owner.equals(owner) && !g.claimed)
                .min(Comparator.comparingLong(g -> g.gameTime));
    }

    public static List<Grave> all() {
        return List.copyOf(GRAVES);
    }
}
