package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Seed-deterministic decoration: trees, clutter, ruins and big trees scattered
 * over the hills of Charon's Echo.
 *
 * Decoration SLOTS are a pure function of the world seed — they exist before
 * any content does and never move. Each slot picks its piece by RENDEZVOUS
 * hashing over the currently approved template set, so approving a new piece
 * claims only its fair share of slots and every other slot keeps exactly what
 * it had. /charon rebuild-decor re-evaluates placed slots against the current
 * set — the world rebuilds just like the first time, with the new items
 * scattered in.
 */
public final class DecorScatter {

    /** Small pieces: one candidate per 24×24 cell. Big pieces: per 96×96 cell. */
    private static final int SMALL_CELL = 24;
    private static final int BIG_CELL = 96;

    public record Placement(int x, int z, String category, String piece) {}

    /** Chunks already processed (packed ChunkPos) and what was placed where. */
    private static final Set<Long> DECORATED = ConcurrentHashMap.newKeySet();
    private static final Map<Long, Placement> PLACEMENTS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> PENDING = new ConcurrentLinkedQueue<>();
    private static Path file;
    private static boolean dirty = false;

    private DecorScatter() {}

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (level.dimension() != CharonsEcho.GRAVEYARD_DIM) return;
            long key = chunk.getPos().pack();
            if (!DECORATED.contains(key)) {
                PENDING.add(key); // decorate on a later tick — never during load
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(DecorScatter::tick);
    }

    private static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            if (dirty && server.getTickCount() % 200 == 0) {
                save();
                dirty = false;
            }
            return;
        }
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        for (int i = 0; i < 4; i++) { // a few chunks per tick keeps the pace smooth
            Long key = PENDING.poll();
            if (key == null) break;
            if (DECORATED.contains(key)) continue;
            if (!graveyard.hasChunk(ChunkPos.unpack(key).x(), ChunkPos.unpack(key).z())) continue;
            decorateChunk(graveyard, ChunkPos.unpack(key));
            DECORATED.add(key);
            dirty = true;
        }
    }

    /** Place every slot whose anchor falls inside this chunk. */
    private static void decorateChunk(ServerLevel level, ChunkPos cp) {
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                trySlot(level, x, z, SMALL_CELL, false);
                trySlot(level, x, z, BIG_CELL, true);
            }
        }
    }

    /** If (x,z) is the jittered candidate of its cell, evaluate and place. */
    private static void trySlot(ServerLevel level, int x, int z, int cell, boolean big) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        long h = mix(cx, cz, big ? 77L : 33L);
        int jx = (int) Math.floorMod(h, cell), jz = (int) Math.floorMod(h >> 16, cell);
        if (cx * cell + jx != x || cz * cell + jz != z) return;

        // Density: not every cell hosts a piece.
        double roll = (Math.floorMod(h >> 32, 1000)) / 1000.0;
        if (roll > (big ? 0.35 : 0.55)) return;

        String category = pickCategory(h, big);
        if (!suitable(level, x, z, category)) return;

        long slotKey = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        String piece = choosePiece(level, category, x, z);
        PLACEMENTS.put(slotKey, new Placement(x, z, category, piece));
        if (!piece.isEmpty()) {
            paste(level, x, z, piece, category);
        }
    }

    private static String pickCategory(long h, boolean big) {
        double roll = Math.floorMod(h >> 44, 1000) / 1000.0;
        // Wild elders are landmarks, not wallpaper — most big slots are ruins.
        if (big) return roll < 0.82 ? "ruin" : "big_tree";
        return roll < 0.65 ? "tree" : "clutter";
    }

    /** Dry, reasonably level ground for the piece's footprint; clear of fields and church. */
    private static boolean suitable(ServerLevel level, int x, int z, String category) {
        StudioMode.Category cat = StudioMode.CATEGORIES.stream()
                .filter(c -> c.name().equals(category)).findFirst().orElse(null);
        if (cat == null) return false;
        if (Math.max(Math.abs(x), Math.abs(z)) < 120) return false; // church grounds
        if (GraveyardPlots.nearAnyField(x, z, cat.w() + 8)) return false;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = 0; dx < cat.w(); dx += Math.max(1, cat.w() - 1)) {
            for (int dz = 0; dz < cat.d(); dz += Math.max(1, cat.d() - 1)) {
                int hh = GraveyardTerrain.groundHeight(x + dx, z + dz);
                if (hh < min) min = hh;
                if (hh > max) max = hh;
            }
        }
        // Big pieces tolerate more slope — knuckle roots and ruin rubble read
        // fine half-buried, and demanding flat 15x15 ground in these hills
        // would leave the wilds without a single elder or ruin.
        int maxRelief = cat.w() >= 12 ? 5 : 3;
        return min >= GraveyardTerrain.WATER_TOP && (max - min) <= maxRelief;
    }

    /**
     * Deterministic piece choice: hash the slot, index into the approved set.
     * Exactly what first-time generation would do with this set — so a rebuild
     * with a grown set re-rolls the whole scatter, same as generating fresh.
     */
    private static String choosePiece(ServerLevel level, String category, int x, int z) {
        List<String> options = StudioMode.approvedTemplates(category,
                level.getServer().getStructureManager(), StudioSets.setForRegion(x, z));
        if (category.equals("big_tree")) {
            // The 6-chain elder never grows wild — every one is earned.
            options = options.stream().filter(o -> !o.equals(Orchard.elderTemplate())).toList();
        }
        if (options.isEmpty()) return "";
        int idx = (int) Math.floorMod(mix(x, z, 991L), options.size());
        return options.get(idx);
    }

    private static void paste(ServerLevel level, int x, int z, String piece, String category) {
        var template = level.getServer().getStructureManager()
                .get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, piece));
        if (template.isEmpty()) return;
        int below = StudioMode.belowGradeOf(template.get(), category);
        int y = GraveyardTerrain.groundHeight(x, z) - below;
        BlockPos at = new BlockPos(x, y + 1, z);

        // Deterministic per-slot rotation so the wilds aren't wallpaper. Odd
        // squares spin freely about their center; even squares only 180° (a
        // quarter-turn would shift their footprint half a block).
        int w = StudioMode.widthOfCategory(category);
        int roll = (int) Math.floorMod(mix(x, z, 777L), 4);
        net.minecraft.world.level.block.Rotation rot;
        if (w % 2 == 1) {
            rot = switch (roll) {
                case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
                case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
                case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                default -> net.minecraft.world.level.block.Rotation.NONE;
            };
        } else {
            rot = roll < 2 ? net.minecraft.world.level.block.Rotation.NONE
                           : net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
        }
        var settings = new StructurePlaceSettings()
                .setRotation(rot)
                .setRotationPivot(new BlockPos(w / 2, 0, w / 2));
        if (category.equals("tree") || category.equals("big_tree")) {
            StudioMode.stripBelowGrade(settings, below, at.getY());
        }
        template.get().placeInWorld(level, at, at, settings,
                RandomSource.create(mix(x, z, 555L)), 2);
        if (category.equals("tree") || category.equals("big_tree")) {
            StudioMode.socketToGround(level, x, z, w, at.getY() + below);
        }

        if (category.equals("big_tree")) {
            // Wild elders bear Tollfruit for whoever dares walk here alive.
            Orchard.registerWild(level, new BlockPos(x + w / 2, y + 1, z + w / 2), piece);
        }
    }

    /**
     * A burial (or the gate) claims its ground: any decor piece whose footprint
     * touches the claimed rectangle is removed WHOLE — no half-chopped trunks,
     * no floating canopies — and its slot is marked consumed so no rebuild ever
     * resurrects it. The yard keeps what it takes.
     */
    public static void clearClaimed(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (Map.Entry<Long, Placement> entry : PLACEMENTS.entrySet()) {
            Placement p = entry.getValue();
            if (p.piece().isEmpty() || p.category().equals("consumed")) continue;
            int w = StudioMode.widthOfCategory(p.category());
            boolean overlaps = p.x() <= x1 && p.x() + w - 1 >= x0
                    && p.z() <= z1 && p.z() + w - 1 >= z0;
            if (!overlaps) continue;
            clearSlot(level, p);
            if (p.category().equals("big_tree")) {
                Orchard.removeWildNear(level.dimension(),
                        p.x() + w / 2, p.z() + w / 2, w);
            }
            entry.setValue(new Placement(p.x(), p.z(), "consumed", ""));
            dirty = true;
        }
    }

    /** Clear a slot's build volume back to air above the terrain. */
    private static void clearSlot(ServerLevel level, Placement p) {
        StudioMode.Category cat = StudioMode.CATEGORIES.stream()
                .filter(c -> c.name().equals(p.category())).findFirst().orElse(null);
        if (cat == null) return;
        for (int x = p.x(); x < p.x() + cat.w(); x++) {
            for (int z = p.z(); z < p.z() + cat.d(); z++) {
                level.getChunk(x >> 4, z >> 4);
                int ground = GraveyardTerrain.groundHeight(x, z);
                for (int y = ground + 1; y <= ground + cat.h() + 2; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * The full reload: every recorded slot is cleared and re-placed from the
     * seed with the CURRENT approved set — the world rebuilds just like the
     * first time, with all items re-scattered. Terrain and graves untouched.
     */
    public static int rebuild(ServerLevel level) {
        Orchard.clearWild(); // wild trees re-register as their slots re-place
        int placed = 0;
        for (Map.Entry<Long, Placement> entry : PLACEMENTS.entrySet()) {
            Placement p = entry.getValue();
            if (p.category().equals("consumed")) continue; // the yard keeps what it takes
            clearSlot(level, p);
            String piece = choosePiece(level, p.category(), p.x(), p.z());
            if (!piece.isEmpty()) {
                paste(level, p.x(), p.z(), piece, p.category());
                placed++;
            }
            entry.setValue(new Placement(p.x(), p.z(), p.category(), piece));
        }
        save();
        return placed;
    }

    // ---- persistence (world/charons_echo/decor.dat) ----

    public static void load(MinecraftServer server) {
        DECORATED.clear();
        PLACEMENTS.clear();
        // PENDING is NOT cleared: forceloaded graveyard chunks fire CHUNK_LOAD
        // during startup, before this runs — clearing here would silently drop
        // them and they'd never decorate. Already-decorated keys are skipped
        // in tick() anyway.
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("decor.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (long l : root.getLongArray("decorated").orElse(new long[0])) {
                DECORATED.add(l);
            }
            for (Tag t : root.getListOrEmpty("placements")) {
                if (!(t instanceof CompoundTag c)) continue;
                int x = c.getIntOr("x", 0), z = c.getIntOr("z", 0);
                PLACEMENTS.put((((long) x) << 32) | (z & 0xFFFFFFFFL),
                        new Placement(x, z, c.getStringOr("category", ""), c.getStringOr("piece", "")));
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load decor.dat: " + e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putLongArray("decorated", DECORATED.stream().mapToLong(Long::longValue).toArray());
            ListTag list = new ListTag();
            for (Placement p : PLACEMENTS.values()) {
                CompoundTag t = new CompoundTag();
                t.putInt("x", p.x());
                t.putInt("z", p.z());
                t.putString("category", p.category());
                t.putString("piece", p.piece());
                list.add(t);
            }
            root.put("placements", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save decor.dat: " + e);
        }
    }

    /** Deterministic 64-bit mix of coordinates + salt + world seed. */
    private static long mix(long a, long b, long salt) {
        long h = a * 341873128712L + b * 132897987541L + salt * 0x632BE59BD9B4E019L
                + GraveyardTerrain.seed() * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        return h ^ (h >>> 33);
    }
}
