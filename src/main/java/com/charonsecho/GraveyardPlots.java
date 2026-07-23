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

    /** Terrace height for the field a plot lives in. */
    public static int fieldSurfaceY(int plotIndex) {
        BlockPos c = fieldCenter(plotIndex / PER_FIELD);
        return GraveyardTerrain.groundHeight(c.getX(), c.getZ());
    }

    /**
     * Assign the next plot to a grave, terracing the field if this is its
     * first burial, and raise the headstone.
     */
    public static void allocate(ServerLevel graveyard, GraveManager.Grave grave) {
        int idx = nextPlotIndex();
        grave.plotIndex = idx;
        if (idx % PER_FIELD == 0) {
            terraceField(graveyard, idx / PER_FIELD);
        }
        placeHeadstone(graveyard, grave);
        GraveManager.save();
    }

    /** Flatten the 40×40 field (plus fence ring) into the hillside. */
    private static void terraceField(ServerLevel level, int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        int h = GraveyardTerrain.groundHeight(c.getX(), c.getZ());
        BlockState moss = Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState fence = Blocks.PALE_OAK_FENCE.defaultBlockState();
        int r = FIELD_HALF + 2; // fence ring sits on the +1 ring

        for (int x = c.getX() - r; x <= c.getX() + r; x++) {
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                level.getChunk(x >> 4, z >> 4);
                for (int y = h + 1; y <= h + 24; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, 2);
                }
                level.setBlock(new BlockPos(x, h, z), moss, 2);
                for (int y = h - 3; y < h; y++) {
                    level.setBlock(new BlockPos(x, y, z), tuff, 2);
                }
            }
        }
        // Fence the perimeter, with a 3-wide gap at the south center (the gate).
        int f = FIELD_HALF + 1;
        for (int x = c.getX() - f; x <= c.getX() + f; x++) {
            boolean southGate = Math.abs(x - c.getX()) <= 1;
            level.setBlock(new BlockPos(x, h + 1, c.getZ() - f), fence, 2);
            if (!southGate) level.setBlock(new BlockPos(x, h + 1, c.getZ() + f), fence, 2);
        }
        for (int z = c.getZ() - f; z <= c.getZ() + f; z++) {
            level.setBlock(new BlockPos(c.getX() - f, h + 1, z), fence, 2);
            level.setBlock(new BlockPos(c.getX() + f, h + 1, z), fence, 2);
        }
        // Soul lanterns on the corners.
        BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState();
        level.setBlock(new BlockPos(c.getX() - f, h + 2, c.getZ() - f), lantern, 2);
        level.setBlock(new BlockPos(c.getX() + f, h + 2, c.getZ() - f), lantern, 2);
        level.setBlock(new BlockPos(c.getX() - f, h + 2, c.getZ() + f), lantern, 2);
        level.setBlock(new BlockPos(c.getX() + f, h + 2, c.getZ() + f), lantern, 2);
    }

    /** Placeholder headstone: mound, stone, and the epitaph sign. */
    private static void placeHeadstone(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = fieldSurfaceY(grave.plotIndex);
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
            long day = grave.gameTime / 24000L;
            String cause = grave.causeLine;
            String l1 = cause.length() > 15 ? cause.substring(0, 15) : cause;
            String l2 = cause.length() > 15 ? cause.substring(15, Math.min(30, cause.length())) : "";
            SignText text = new SignText()
                    .setMessage(0, Component.literal(grave.ownerName))
                    .setMessage(1, Component.literal(l1))
                    .setMessage(2, Component.literal(l2))
                    .setMessage(3, Component.literal("Day " + day));
            sign.setText(text, true);
            sign.setChanged();
        }
    }

    /** Mark a reclaimed grave's sign. */
    public static void markAtRest(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = fieldSurfaceY(grave.plotIndex);
        BlockPos signPos = new BlockPos(o.getX() + 2, y + 1, o.getZ() + 2);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            sign.setText(sign.getFrontText().setMessage(3,
                    Component.literal("— at rest —")), true);
            sign.setChanged();
        }
    }

    /** True if the position lies within the grave's 5×5 plot near stone height. */
    public static boolean isOnPlot(GraveManager.Grave grave, BlockPos pos) {
        if (grave.plotIndex < 0) return false;
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = fieldSurfaceY(grave.plotIndex);
        return pos.getX() >= o.getX() && pos.getX() < o.getX() + PLOT
                && pos.getZ() >= o.getZ() && pos.getZ() < o.getZ() + PLOT
                && Math.abs(pos.getY() - y) <= 4;
    }

    /** Where a ghost arrives / stands to mourn: south of the headstone. */
    public static BlockPos arrivalPos(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        return new BlockPos(o.getX() + 2, fieldSurfaceY(plotIndex) + 1, o.getZ() + 4);
    }
}
