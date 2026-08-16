package com.charonsecho;

import com.mojang.serialization.DynamicOps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
        /** Real-world time of death (epoch millis) for the headstone date. */
        public long epochMillis = 0L;
        /** Headstone template variant chosen for this grave ("" = placeholder). */
        public String stoneName = "";
        /** The dead's own words, interred at the stone (null = none yet). */
        public net.minecraft.world.item.component.WrittenBookContent book = null;
        /** Fare already paid (shard dropped on the body during the wake). */
        public boolean farePaid = false;
        /** Flowers laid by the living — the only votes that count here. */
        public int tributes = 0;
        /** One flower per mourner: who has already laid theirs. */
        public final java.util.Set<java.util.UUID> mourners = new HashSet<>();
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

    private static final List<Grave> GRAVES = new ArrayList<>();
    private static final List<Grave> ALL_VIEW = Collections.unmodifiableList(GRAVES);
    private static final GraveIndex INDEX = new GraveIndex();
    private static final Map<Long, List<Grave>> GRAVES_BY_CHUNK = new HashMap<>();
    /** Item data never changes after death, so its expensive codec result is reused. */
    private static final Map<UUID, ListTag> ENCODED_ITEMS = new HashMap<>();
    private static MinecraftServer server;
    private static boolean dirty;
    private static long nextSaveTick = Long.MAX_VALUE;

    private GraveManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GraveManager::tick);
    }

    private static void tick(MinecraftServer srv) {
        if (dirty && srv.getTickCount() >= nextSaveTick) {
            queueSnapshot();
        }
    }

    public static void load(MinecraftServer srv) {
        server = srv;
        GRAVES.clear();
        INDEX.clear();
        GRAVES_BY_CHUNK.clear();
        ENCODED_ITEMS.clear();
        dirty = false;
        nextSaveTick = Long.MAX_VALUE;
        Path file = dataFile(srv);
        if (!CharonStorage.hasData(file)) return;
        try {
            CompoundTag root = CharonStorage.read(file);
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
            for (Tag t : root.getListOrEmpty("graves")) {
                if (!(t instanceof CompoundTag g)) continue;
                List<ItemStack> items = new ArrayList<>();
                ListTag encodedItems = new ListTag();
                for (Tag it : g.getListOrEmpty("items")) {
                    ItemStack.OPTIONAL_CODEC.parse(ops, it).result().ifPresent(items::add);
                    encodedItems.add(it.copy());
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
                grave.epochMillis = g.getLongOr("epochMillis", 0L);
                grave.farePaid = g.getBooleanOr("farePaid", false);
                grave.tributes = g.getIntOr("tributes", 0);
                long[] mourners = g.getLongArray("mourners").orElse(new long[0]);
                for (int i = 0; i + 1 < mourners.length; i += 2) {
                    grave.mourners.add(new java.util.UUID(mourners[i], mourners[i + 1]));
                }
                grave.stoneName = g.getStringOr("stoneName", "");
                if (g.contains("book")) {
                    net.minecraft.world.item.component.WrittenBookContent.CODEC
                            .parse(ops, g.get("book")).result()
                            .ifPresent(b -> grave.book = b);
                }
                GRAVES.add(grave);
                INDEX.add(grave);
                ENCODED_ITEMS.put(grave.id, encodedItems);
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load graves.dat: " + e);
        }
    }

    /** Mark the ledger dirty; repeated mutations become one save within five seconds. */
    public static void save() {
        if (server == null) return;
        dirty = true;
        nextSaveTick = Math.min(nextSaveTick, server.getTickCount() + 100);
    }

    /** Queue critical inventory state promptly without doing disk I/O on the server thread. */
    private static void saveCritical() {
        if (server == null) return;
        dirty = true;
        nextSaveTick = Math.min(nextSaveTick, server.getTickCount() + 1);
    }

    /** Persist consumed items or authored memorial data on the next server tick. */
    public static void saveSoon() {
        saveCritical();
    }

    private static void queueSnapshot() {
        if (server == null || !dirty) return;
        CharonStorage.write(dataFile(server), snapshot(server));
        dirty = false;
        nextSaveTick = Long.MAX_VALUE;
    }

    private static CompoundTag snapshot(MinecraftServer srv) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
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
            t.putLong("epochMillis", g.epochMillis);
            t.putBoolean("farePaid", g.farePaid);
            t.putInt("tributes", g.tributes);
            long[] mourners = new long[g.mourners.size() * 2];
            int mi = 0;
            for (UUID u : g.mourners) {
                mourners[mi++] = u.getMostSignificantBits();
                mourners[mi++] = u.getLeastSignificantBits();
            }
            t.putLongArray("mourners", mourners);
            t.putString("stoneName", g.stoneName);
            if (g.book != null) {
                net.minecraft.world.item.component.WrittenBookContent.CODEC
                        .encodeStart(ops, g.book).result()
                        .ifPresent(b -> t.put("book", b));
            }
            ListTag items = ENCODED_ITEMS.computeIfAbsent(g.id, ignored -> {
                ListTag encoded = new ListTag();
                for (ItemStack stack : g.items) {
                    ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent(encoded::add);
                }
                return encoded;
            });
            t.put("items", items);
            list.add(t);
        }
        CompoundTag root = new CompoundTag();
        root.put("graves", list);
        return root;
    }

    /** Queue any last dirty snapshot before the shared writer is flushed. */
    public static void flush() {
        if (dirty) queueSnapshot();
    }

    private static Path dataFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("graves.dat");
    }

    public static void add(Grave grave) {
        GRAVES.add(grave);
        INDEX.add(grave);
        saveCritical();
    }

    /** Oldest unclaimed grave for a player — the one the soul wisp leads to first. */
    public static Optional<Grave> oldestUnclaimed(UUID owner) {
        return INDEX.oldestUnclaimed(owner);
    }

    /** Latest reclaimed grave for rebuilding a living visitor's way home. */
    public static Optional<Grave> latestClaimed(UUID owner) {
        return INDEX.latestClaimed(owner);
    }

    public static List<Grave> all() {
        return ALL_VIEW;
    }

    public static Optional<Grave> byId(UUID id) {
        return INDEX.byId(id);
    }

    public static void markClaimed(Grave grave) {
        INDEX.markClaimed(grave, GRAVES);
        saveCritical();
    }

    public static void assignPlot(Grave grave, int plotIndex) {
        INDEX.assignPlot(grave, plotIndex);
        indexPlot(grave);
        save();
    }

    public static int nextPlotIndex() {
        return INDEX.nextPlotIndex();
    }

    public static boolean plotUsed(int plotIndex) {
        return INDEX.plotUsed(plotIndex);
    }

    public static int latestAllocatedField(int plotsPerField) {
        return INDEX.latestField(plotsPerField);
    }

    /** Called after field centers load, because plot positions depend on them. */
    public static void rebuildSpatialIndex() {
        GRAVES_BY_CHUNK.clear();
        for (Grave grave : GRAVES) {
            if (grave.plotIndex >= 0) indexPlot(grave);
        }
    }

    private static void indexPlot(Grave grave) {
        BlockPos origin = GraveyardPlots.plotOrigin(grave.plotIndex);
        int minChunkX = origin.getX() >> 4;
        int maxChunkX = (origin.getX() + 5) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + 5) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long chunk = new net.minecraft.world.level.ChunkPos(cx, cz).pack();
                GRAVES_BY_CHUNK.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(grave);
            }
        }
    }

    /** The grave whose plot contains this graveyard position, if any. */
    public static Optional<Grave> graveAt(net.minecraft.core.BlockPos pos) {
        List<Grave> candidates = GRAVES_BY_CHUNK.get(
                new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4).pack());
        if (candidates == null) return Optional.empty();
        return candidates.stream()
                .filter(g -> g.plotIndex >= 0 && GraveyardPlots.isOnPlot(g, pos))
                .findFirst();
    }
}
