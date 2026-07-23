package com.charonsecho;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Spiral grave-field allocation in Charon's Echo.
 *
 * The yard is built from 40×40 fields on a square spiral around the church
 * (pitch 96). Each field holds a 6×8 grid of 5×5 plots = 48 graves, fenced,
 * terraced flat into the hills when its first grave is dug. Plot indices are
 * global and sequential; a grave's plotIndex is assigned at portal-crossing
 * time and never changes.
 *
 * Headstones here are generated placeholders — they'll be swapped for the
 * hand-built Studio templates when those are exported.
 */
public final class GraveyardPlots {

    private static final int FIELD_PITCH = 96;  // field center-to-center
    private static final int FIELD_HALF = 20;   // 40×40 interior
    private static final int PLOT = 6;          // 6×6 plots: 4×4 stones + 2-block aisles
    private static final int COLS = 6;          // plots per row (x)
    private static final int ROWS = 6;          // rows per field (z)
    private static final int PER_FIELD = COLS * ROWS; // 36 graves per field

    private GraveyardPlots() {}

    /** Next unused global plot index (ignoring suitability). */
    public static int nextPlotIndex() {
        int max = -1;
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex > max) max = g.plotIndex;
        }
        return max + 1;
    }


    /**
     * Field positions are FOUND, not computed: the square spiral suggests an
     * anchor, then the field slides outward from it until its entire footprint
     * fits on dry, workable ground (no river crossing, no cliff). Found
     * positions are persisted — the terrain-scattered layout is the graveyard's
     * character.
     */
    private static final java.util.List<BlockPos> FIELD_CENTERS = new java.util.ArrayList<>();
    private static java.nio.file.Path fieldsFile;

    static BlockPos fieldCenter(int fieldIndex) {
        synchronized (FIELD_CENTERS) {
            while (FIELD_CENTERS.size() <= fieldIndex) {
                FIELD_CENTERS.add(findFieldSpot(FIELD_CENTERS.size()));
                saveFields();
            }
            return FIELD_CENTERS.get(fieldIndex);
        }
    }

    /** Square-spiral anchor suggestion for field n, skipping the church at origin. */
    private static BlockPos spiralAnchor(int fieldIndex) {
        int n = fieldIndex + 1; // 0 would be the church
        int x = 0, z = 0, dx = 1, dz = 0, arm = 1, steps = 0, turns = 0;
        for (int i = 0; i < n; i++) {
            x += dx; z += dz;
            if (++steps == arm) {
                steps = 0;
                int t = dx; dx = -dz; dz = t; // rotate
                if (++turns % 2 == 0) arm++;
            }
        }
        return new BlockPos(x * FIELD_PITCH, 0, z * FIELD_PITCH);
    }

    /** Ring-scan outward from the spiral anchor for the first spot that fits. */
    private static BlockPos findFieldSpot(int fieldIndex) {
        BlockPos ideal = spiralAnchor(fieldIndex);
        for (int r = 0; r <= 480; r += 16) {
            for (int dx = -r; dx <= r; dx += 16) {
                for (int dz = -r; dz <= r; dz += 16) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = ideal.getX() + dx, cz = ideal.getZ() + dz;
                    if (fieldFits(cx, cz) && farFromOtherFields(cx, cz)) {
                        return new BlockPos(cx, 0, cz);
                    }
                }
            }
        }
        return ideal; // last resort — should not happen in these hills
    }

    /** The whole footprint (incl. fence ring) dry and ≤10 blocks of relief. */
    private static boolean fieldFits(int cx, int cz) {
        int r = FIELD_HALF + 2;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                int h = GraveyardTerrain.groundHeight(x, z);
                if (h < GraveyardTerrain.WATER_TOP) return false;
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }
        // Keep clear of the church plateau too.
        if (Math.max(Math.abs(cx), Math.abs(cz)) < 96) return false;
        return (max - min) <= 10;
    }

    /** True if (x,z) is within margin of any established field footprint. */
    public static boolean nearAnyField(int x, int z, int margin) {
        synchronized (FIELD_CENTERS) {
            for (BlockPos c : FIELD_CENTERS) {
                if (Math.max(Math.abs(c.getX() - x), Math.abs(c.getZ() - z)) < FIELD_HALF + margin) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean farFromOtherFields(int cx, int cz) {
        for (BlockPos c : FIELD_CENTERS) {
            if (Math.max(Math.abs(c.getX() - cx), Math.abs(c.getZ() - cz)) < 60) return false;
        }
        return true;
    }

    // ---- field-position persistence (world/charons_echo/fields.dat) ----

    public static void load(net.minecraft.server.MinecraftServer server) {
        synchronized (FIELD_CENTERS) {
            FIELD_CENTERS.clear();
            fieldsFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("charons_echo").resolve("fields.dat");
            try {
                if (java.nio.file.Files.exists(fieldsFile)) {
                    var root = net.minecraft.nbt.NbtIo.readCompressed(fieldsFile,
                            net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    for (var t : root.getListOrEmpty("fields")) {
                        if (t instanceof net.minecraft.nbt.CompoundTag c) {
                            FIELD_CENTERS.add(new BlockPos(c.getIntOr("x", 0), 0, c.getIntOr("z", 0)));
                        }
                    }
                }
            } catch (java.io.IOException e) {
                System.out.println("[CharonsEcho] failed to load fields.dat: " + e);
            }
            // Migration: worlds with graves placed before positions were stored
            // used the raw spiral — seed those positions so old graves resolve.
            if (FIELD_CENTERS.isEmpty()) {
                int maxField = -1;
                for (GraveManager.Grave g : GraveManager.all()) {
                    if (g.plotIndex >= 0) maxField = Math.max(maxField, g.plotIndex / PER_FIELD);
                }
                for (int f = 0; f <= maxField; f++) {
                    FIELD_CENTERS.add(spiralAnchor(f));
                }
                if (maxField >= 0) saveFields();
            }
        }
    }

    private static void saveFields() {
        if (fieldsFile == null) return;
        try {
            java.nio.file.Files.createDirectories(fieldsFile.getParent());
            var list = new net.minecraft.nbt.ListTag();
            for (BlockPos c : FIELD_CENTERS) {
                var t = new net.minecraft.nbt.CompoundTag();
                t.putInt("x", c.getX());
                t.putInt("z", c.getZ());
                list.add(t);
            }
            var root = new net.minecraft.nbt.CompoundTag();
            root.put("fields", list);
            net.minecraft.nbt.NbtIo.writeCompressed(root, fieldsFile);
        } catch (java.io.IOException e) {
            System.out.println("[CharonsEcho] failed to save fields.dat: " + e);
        }
    }

    /** NW corner of a plot (global index) in world coords. */
    public static BlockPos plotOrigin(int plotIndex) {
        int field = plotIndex / PER_FIELD;
        int within = plotIndex % PER_FIELD;
        int col = within % COLS, row = within / COLS;
        BlockPos c = fieldCenter(field);
        return new BlockPos(c.getX() - FIELD_HALF + col * PLOT,
                0, c.getZ() - FIELD_HALF + row * PLOT);
    }

    /**
     * Surface height of a plot's own little terrace: the terrain height at the
     * plot center. Plots step down hillsides individually — a churchyard
     * climbing the hill, not a bulldozed platform.
     */
    public static int plotSurfaceY(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        return GraveyardTerrain.groundHeight(o.getX() + 2, o.getZ() + 2);
    }

    /**
     * Assign the next plot to a grave: fence the field if its fence is missing
     * (first burial, or regenerated terrain), cut the plot's own terrace,
     * raise the stone, and note on the gate sign when the field fills.
     */
    public static void allocate(ServerLevel graveyard, GraveManager.Grave grave) {
        int idx = nextPlotIndex();
        grave.plotIndex = idx;
        ensureField(graveyard, idx / PER_FIELD);
        terracePlot(graveyard, idx);
        placeHeadstone(graveyard, grave);
        if (idx % PER_FIELD == PER_FIELD - 1) {
            markFieldFull(graveyard, idx / PER_FIELD);
        }
        GraveManager.save();
    }

    /**
     * Gate sign position: first dry spot on the south fence line, starting
     * beside the gate. Deterministic, so re-fence checks find the same spot.
     */
    private static BlockPos gateSignPos(int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        int f = FIELD_HALF + 1, z = c.getZ() + f;
        for (int dx = -2; dx <= FIELD_HALF; dx++) {
            int x = c.getX() + dx;
            int h = GraveyardTerrain.groundHeight(x, z);
            if (h >= GraveyardTerrain.WATER_TOP && Math.abs(dx) > 1) { // not in the gate gap
                return new BlockPos(x, h + 2, z);
            }
        }
        int x = c.getX() - 2;
        return new BlockPos(x, GraveyardTerrain.groundHeight(x, z) + 2, z);
    }

    /** Re-fence if the fence is missing (fresh field OR wiped/regenerated terrain). */
    private static void ensureField(ServerLevel level, int fieldIndex) {
        BlockPos signPos = gateSignPos(fieldIndex);
        level.getChunk(signPos.getX() >> 4, signPos.getZ() >> 4);
        if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity)) {
            fenceField(level, fieldIndex);
        }
    }

    private static String shortDate() {
        return new java.text.SimpleDateFormat("M/d/yy").format(new java.util.Date());
    }

    /** Fence the field perimeter, following the terrain, gate at south center. */
    private static void fenceField(ServerLevel level, int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        BlockState fence = Blocks.PALE_OAK_FENCE.defaultBlockState();
        BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState();
        int f = FIELD_HALF + 1;
        for (int x = c.getX() - f; x <= c.getX() + f; x++) {
            boolean southGate = Math.abs(x - c.getX()) <= 1;
            fencePost(level, fence, x, c.getZ() - f);
            if (!southGate) fencePost(level, fence, x, c.getZ() + f);
        }
        for (int z = c.getZ() - f; z <= c.getZ() + f; z++) {
            fencePost(level, fence, c.getX() - f, z);
            fencePost(level, fence, c.getX() + f, z);
        }
        for (int[] corner : new int[][]{{-f, -f}, {f, -f}, {-f, f}, {f, f}}) {
            int x = c.getX() + corner[0], z = c.getZ() + corner[1];
            int h = GraveyardTerrain.groundHeight(x, z);
            if (h < GraveyardTerrain.WATER_TOP) continue; // no drowned lanterns
            level.setBlock(new BlockPos(x, h + 2, z), lantern, 2);
        }

        // Gate sign: which field this is, and when it opened — glowing, and
        // written on BOTH faces so it reads from outside and inside the yard.
        BlockPos signPos = gateSignPos(fieldIndex);
        level.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 2);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("Grave Field " + (fieldIndex + 1)))
                    .setMessage(1, Component.literal("opened " + shortDate()))
                    .setHasGlowingText(true);
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setChanged();
        }
    }

    /** The last dry plot closes the field's ledger on the gate sign. */
    private static void markFieldFull(ServerLevel level, int fieldIndex) {
        int souls = 0;
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex >= 0 && g.plotIndex / PER_FIELD == fieldIndex) souls++;
        }
        BlockPos signPos = gateSignPos(fieldIndex);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText updated = sign.getFrontText()
                    .setMessage(2, Component.literal("filled " + shortDate()))
                    .setMessage(3, Component.literal(souls + " souls rest"))
                    .setHasGlowingText(true);
            sign.setText(updated, true);
            sign.setText(updated, false);
            sign.setChanged();
        }
    }

    private static void fencePost(ServerLevel level, BlockState fence, int x, int z) {
        int h = GraveyardTerrain.groundHeight(x, z);
        if (h < GraveyardTerrain.WATER_TOP) return; // the fence breaks at the water
        level.getChunk(x >> 4, z >> 4);
        level.setBlock(new BlockPos(x, h + 1, z), fence, 2);
    }

    /** Cut the 5×5 plot flat at its own height, with tuff fill below. */
    private static void terracePlot(ServerLevel level, int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        int h = plotSurfaceY(plotIndex);
        BlockState moss = Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = o.getX(); x < o.getX() + PLOT; x++) {
            for (int z = o.getZ(); z < o.getZ() + PLOT; z++) {
                level.getChunk(x >> 4, z >> 4);
                for (int y = h + 1; y <= h + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, 2);
                }
                level.setBlock(new BlockPos(x, h, z), moss, 2);
                for (int y = h - 2; y < h; y++) {
                    level.setBlock(new BlockPos(x, y, z), tuff, 2);
                }
            }
        }
    }

    /**
     * The headstone: a hand-built approved template when one exists (variant
     * chosen once per grave and remembered), else the generated placeholder.
     */
    static void placeHeadstone(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = plotSurfaceY(grave.plotIndex);

        var manager = level.getServer().getStructureManager();
        if (grave.stoneName.isEmpty()) {
            var variants = StudioMode.approvedTemplates("headstone", manager,
                    StudioSets.setForRegion(o.getX(), o.getZ()));
            if (!variants.isEmpty()) {
                grave.stoneName = variants.get(Math.floorMod(grave.id.hashCode(), variants.size()));
            }
        }
        if (!grave.stoneName.isEmpty()) {
            var template = manager.get(net.minecraft.resources.Identifier
                    .fromNamespaceAndPath(CharonsEcho.MOD_ID, grave.stoneName));
            if (template.isPresent()) {
                BlockPos at = new BlockPos(o.getX(), y + 1, o.getZ());
                level.getChunk(o.getX() >> 4, o.getZ() >> 4);
                template.get().placeInWorld(level, at, at,
                        new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                        net.minecraft.util.RandomSource.create(grave.id.hashCode()), 3);
                writeEpitaphOnAnySign(level, grave, o, y);
                return;
            }
        }
        placePlaceholderStone(level, grave, o, y);
    }

    /** The template's sign (wherever the builder put it) gets the epitaph. */
    private static void writeEpitaphOnAnySign(ServerLevel level, GraveManager.Grave grave, BlockPos o, int y) {
        for (int x = o.getX(); x < o.getX() + PLOT; x++) {
            for (int z = o.getZ(); z < o.getZ() + PLOT; z++) {
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                        sign.setText(epitaphText(grave), true);
                        sign.setChanged();
                        return;
                    }
                }
            }
        }
    }

    /** Generated fallback headstone: mound, stone, and the epitaph sign. */
    private static void placePlaceholderStone(ServerLevel level, GraveManager.Grave grave, BlockPos o, int y) {
        int cx = o.getX() + 2, cz = o.getZ() + 1; // stone near plot's north edge

        level.getChunk(cx >> 4, cz >> 4);
        // Grave mound (coarse-textured): 1×2 of tuff in front of the stone.
        level.setBlock(new BlockPos(cx, y, cz + 1), Blocks.TUFF.defaultBlockState(), 2);
        level.setBlock(new BlockPos(cx, y, cz + 2), Blocks.TUFF.defaultBlockState(), 2);
        // The stone: chiseled deepslate with a slab cap.
        level.setBlock(new BlockPos(cx, y + 1, cz), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(cx, y + 2, cz),
                Blocks.DEEPSLATE_BRICK_SLAB.defaultBlockState(), 2);
        // Epitaph sign on the south face.
        BlockPos signPos = new BlockPos(cx, y + 1, cz + 1);
        level.setBlock(signPos, Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH), 2);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            sign.setText(epitaphText(grave), true);
            sign.setChanged();
        }
    }

    /** name / real date / cause wrapped over two lines (name prefix stripped). */
    private static SignText epitaphText(GraveManager.Grave grave) {
        String cause = grave.causeLine;
        if (cause.startsWith(grave.ownerName)) {
            cause = cause.substring(grave.ownerName.length()).trim();
        }
        String l3 = cause.length() > 15 ? cause.substring(0, 15) : cause;
        String rest = cause.length() > 15 ? cause.substring(15).trim() : "";
        String l4 = rest.length() > 15 ? rest.substring(0, 14) + "…" : rest;
        String date = grave.epochMillis > 0
                ? new java.text.SimpleDateFormat("MMM d, yyyy").format(new java.util.Date(grave.epochMillis))
                : "Day " + (grave.gameTime / 24000L);
        return new SignText()
                .setMessage(0, Component.literal(grave.ownerName))
                .setMessage(1, Component.literal(date))
                .setMessage(2, Component.literal(l3))
                .setMessage(3, Component.literal(l4));
    }

    /** Re-terrace and re-paste every grave's headstone from its record — run
     *  after new stone variants are approved so the yard upgrades in place. */
    public static int rebuildAll(ServerLevel graveyard) {
        int count = 0;
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex < 0) continue;
            terracePlot(graveyard, g.plotIndex);
            placeHeadstone(graveyard, g);
            count++;
        }
        GraveManager.save();
        return count;
    }

    /** A reclaimed grave's sign glows softly — the soul is at rest. */
    public static void markAtRest(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = plotSurfaceY(grave.plotIndex);
        BlockPos signPos = new BlockPos(o.getX() + 2, y + 1, o.getZ() + 2);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            sign.setText(sign.getFrontText().setHasGlowingText(true), true);
            sign.setChanged();
        }
    }

    /** True if the position lies within the grave's 5×5 plot near stone height. */
    public static boolean isOnPlot(GraveManager.Grave grave, BlockPos pos) {
        if (grave.plotIndex < 0) return false;
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = plotSurfaceY(grave.plotIndex);
        return pos.getX() >= o.getX() && pos.getX() < o.getX() + PLOT
                && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + PLOT
                && Math.abs(pos.getY() - y) <= 4;
    }

    /** Where a ghost arrives / stands to mourn: south of the headstone. */
    public static BlockPos arrivalPos(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        return new BlockPos(o.getX() + 2, plotSurfaceY(plotIndex) + 1, o.getZ() + 4);
    }
}
