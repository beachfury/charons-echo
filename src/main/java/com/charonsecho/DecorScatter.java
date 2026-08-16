package com.charonsecho;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
    private record Slot(int x, int z, int cell, boolean big) {}

    /** Chunks already processed (packed ChunkPos) and what was placed where. */
    private static final Set<Long> DECORATED = new HashSet<>();
    private static final Map<Long, Placement> PLACEMENTS = new HashMap<>();
    private static final Deque<Long> PENDING = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static Path file;
    private static boolean dirty = false;
    private static Long currentKey;
    private static List<Slot> currentSlots = List.of();
    private static int currentSlot;

    private DecorScatter() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PENDING.clear();
            QUEUED.clear();
            currentKey = null;
            currentSlots = List.of();
            currentSlot = 0;
            dirty = false;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (level.dimension() != CharonsEcho.GRAVEYARD_DIM) return;
            long key = chunk.getPos().pack();
            if (!DECORATED.contains(key) && QUEUED.add(key)) {
                PENDING.addLast(key); // decorate on a later tick — never during load
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(DecorScatter::tick);
    }

    private static void tick(MinecraftServer server) {
        if (dirty && server.getTickCount() % 200 == 0) {
            save();
            dirty = false;
        }
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        if (currentKey == null) {
            while (!PENDING.isEmpty()) {
                Long key = PENDING.pollFirst();
                if (key == null) return;
                if (DECORATED.contains(key)) {
                    QUEUED.remove(key);
                    continue;
                }
                ChunkPos cp = ChunkPos.unpack(key);
                if (!graveyard.hasChunk(cp.x(), cp.z())) {
                    QUEUED.remove(key);
                    continue;
                }
                currentKey = key;
                currentSlots = candidateSlots(cp);
                currentSlot = 0;
                break;
            }
        }
        if (currentKey == null) return;

        ChunkPos currentChunk = ChunkPos.unpack(currentKey);
        if (!graveyard.hasChunk(currentChunk.x(), currentChunk.z())) {
            QUEUED.remove(currentKey);
            currentKey = null;
            currentSlots = List.of();
            return;
        }

        long deadline = System.nanoTime() + 1_500_000L;
        boolean pasted = false;
        int evaluated = 0;
        while (currentSlot < currentSlots.size() && !pasted
                && (evaluated == 0 || System.nanoTime() < deadline)) {
            Slot slot = currentSlots.get(currentSlot++);
            pasted = trySlot(graveyard, slot.x(), slot.z(), slot.cell(), slot.big());
            evaluated++;
        }
        if (currentSlot >= currentSlots.size()) {
            DECORATED.add(currentKey);
            QUEUED.remove(currentKey);
            dirty = true;
            currentKey = null;
            currentSlots = List.of();
            currentSlot = 0;
        }
    }

    /** Deterministic slots whose anchors fall inside this chunk. */
    private static List<Slot> candidateSlots(ChunkPos cp) {
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ();
        List<Slot> slots = new ArrayList<>();
        collectCandidateSlots(slots, minX, minZ, SMALL_CELL, false);
        collectCandidateSlots(slots, minX, minZ, BIG_CELL, true);
        return slots;
    }

    /** Evaluate only the jittered cell anchors that can fall in this chunk. */
    private static void collectCandidateSlots(List<Slot> slots, int minX, int minZ,
            int cell, boolean big) {
        int maxX = minX + 15, maxZ = minZ + 15;
        for (int cx = Math.floorDiv(minX, cell); cx <= Math.floorDiv(maxX, cell); cx++) {
            for (int cz = Math.floorDiv(minZ, cell); cz <= Math.floorDiv(maxZ, cell); cz++) {
                long h = mix(cx, cz, big ? 77L : 33L);
                int x = cx * cell + (int) Math.floorMod(h, cell);
                int z = cz * cell + (int) Math.floorMod(h >> 16, cell);
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    slots.add(new Slot(x, z, cell, big));
                }
            }
        }
    }

    /** If (x,z) is the jittered candidate of its cell, evaluate and place. */
    private static boolean trySlot(ServerLevel level, int x, int z, int cell, boolean big) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        long h = mix(cx, cz, big ? 77L : 33L);
        int jx = (int) Math.floorMod(h, cell), jz = (int) Math.floorMod(h >> 16, cell);
        if (cx * cell + jx != x || cz * cell + jz != z) return false;

        // Density: not every cell hosts a piece.
        double roll = (Math.floorMod(h >> 32, 1000)) / 1000.0;
        if (roll > (big ? 0.35 : 0.55)) return false;

        String category = pickCategory(h, big);
        if (!suitable(level, x, z, category)) return false;

        long slotKey = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        // A prior partial run may already have pasted this exact slot. Its
        // placement ledger makes the resumed chunk idempotent.
        if (PLACEMENTS.containsKey(slotKey)) return false;
        String piece = choosePiece(level, category, x, z);
        PLACEMENTS.put(slotKey, new Placement(x, z, category, piece));
        dirty = true;
        if (!piece.isEmpty()) {
            paste(level, x, z, piece, category);
            return true;
        }
        return false;
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

    /** Does any living decor piece's footprint touch this rectangle? */
    public static boolean decorOverlapping(int x0, int z0, int x1, int z1) {
        for (Placement p : PLACEMENTS.values()) {
            if (p.piece().isEmpty() || p.category().equals("consumed")) continue;
            int w = StudioMode.widthOfCategory(p.category());
            if (p.x() <= x1 && p.x() + w - 1 >= x0
                    && p.z() <= z1 && p.z() + w - 1 >= z0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The GATE claims its ground: any decor piece whose footprint touches the
     * claimed rectangle is removed WHOLE — no half-chopped trunks, no floating
     * canopies — and its slot is marked consumed so no rebuild ever resurrects
     * it. (Burials no longer claim: graves go AROUND the trees.)
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
        if (!CharonStorage.hasData(file)) return;
        try {
            CompoundTag root = CharonStorage.read(file);
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
            CharonStorage.write(file, root);
            dirty = false;
        } catch (RuntimeException e) {
            System.out.println("[CharonsEcho] failed to snapshot decor.dat: " + e);
        }
    }

    public static void flush() {
        if (dirty) {
            save();
            dirty = false;
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
