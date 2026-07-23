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
    private static final int PLOT = 5;
    private static final int COLS = 8;          // plots per row (x)
    private static final int ROWS = 6;          // rows per field (z)
    private static final int PER_FIELD = COLS * ROWS;

    private GraveyardPlots() {}

    /** Next unused global plot index. */
    public static int nextPlotIndex() {
        int max = -1;
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex > max) max = g.plotIndex;
        }
        return max + 1;
    }

    /** Square-spiral coordinates for field n (n >= 0), skipping the church at origin. */
    static BlockPos fieldCenter(int fieldIndex) {
        int n = fieldIndex + 1; // 0 would be the church
        // Walk the square spiral: right, up, left, down with growing arm lengths.
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

    /** Gate sign position for a field (west side of the south gate). */
    private static BlockPos gateSignPos(int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        int f = FIELD_HALF + 1;
        int x = c.getX() - 2, z = c.getZ() + f;
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
            level.setBlock(new BlockPos(x, h + 2, z), lantern, 2);
        }

        // Gate sign: which field this is, and when it opened.
        BlockPos signPos = gateSignPos(fieldIndex);
        level.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 2);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal("Grave Field " + (fieldIndex + 1)))
                    .setMessage(1, Component.literal("opened " + shortDate()));
            sign.setText(text, true);
            sign.setChanged();
        }
    }

    /** The 48th burial closes the field's ledger on the gate sign. */
    private static void markFieldFull(ServerLevel level, int fieldIndex) {
        BlockPos signPos = gateSignPos(fieldIndex);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            sign.setText(sign.getFrontText()
                    .setMessage(2, Component.literal("filled " + shortDate()))
                    .setMessage(3, Component.literal("48 souls rest")), true);
            sign.setChanged();
        }
    }

    private static void fencePost(ServerLevel level, BlockState fence, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int h = GraveyardTerrain.groundHeight(x, z);
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

    /** Placeholder headstone: mound, stone, and the epitaph sign. */
    private static void placeHeadstone(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = plotSurfaceY(grave.plotIndex);
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
            // "BeachFury hit the ground too hard" → name is line 1, so the
            // cause reads "hit the ground too hard" wrapped over two lines.
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
            SignText text = new SignText()
                    .setMessage(0, Component.literal(grave.ownerName))
                    .setMessage(1, Component.literal(date))
                    .setMessage(2, Component.literal(l3))
                    .setMessage(3, Component.literal(l4));
            sign.setText(text, true);
            sign.setChanged();
        }
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
