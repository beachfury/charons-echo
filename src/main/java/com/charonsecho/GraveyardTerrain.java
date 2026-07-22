package com.charonsecho;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sculpts the graveyard dimension as chunks generate: gentle monochrome hills,
 * a flattened plateau for the church at the origin, a winding river, and small
 * ponds in the lowest vales. Deterministic — same chunk always generates the
 * same terrain, no Random state.
 *
 * The flat generator supplies bedrock + deepslate up to y=30; everything above
 * is written here with chunk.setBlockState (NEVER level.setBlock mid-generation
 * — that deadlocks chunk gen).
 */
public final class GraveyardTerrain {

    /** Top of the flat-generator base — sculpting starts above this. */
    private static final int BASE_Y = 30;
    /** Mean terrain height and hill amplitude: h ranges ~[50, 76]. */
    private static final int MEAN_H = 63;
    private static final int AMP = 13;
    /** Church plateau: flat at PLATEAU_H within FLAT_R, blended out to BLEND_R. */
    private static final int PLATEAU_H = 64;
    private static final double FLAT_R = 48.0;
    private static final double BLEND_R = 96.0;
    /** Any column whose ground ends below this gets still water up to it. */
    private static final int WATER_TOP = 52;

    private GraveyardTerrain() {}

    public static void onGenerate(ServerLevel level, LevelChunk chunk) {
        if (level.dimension() != CharonsEcho.GRAVEYARD_DIM) return;

        final int baseX = chunk.getPos().getMinBlockX();
        final int baseZ = chunk.getPos().getMinBlockZ();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        final BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        final BlockState tuff = Blocks.TUFF.defaultBlockState();
        final BlockState moss = Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        final BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        final BlockState water = Blocks.WATER.defaultBlockState();

        try {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx, z = baseZ + dz;
                    int h = groundHeight(x, z);
                    boolean flooded = h < WATER_TOP;

                    for (int y = BASE_Y; y < h - 3; y++) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, deepslate);
                    }
                    for (int y = Math.max(BASE_Y, h - 3); y < h; y++) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, tuff);
                    }
                    pos.set(x, h, z);
                    chunk.setBlockState(pos, flooded ? gravel : moss);

                    if (flooded) {
                        for (int y = h + 1; y <= WATER_TOP; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, water);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[CharonsEcho] terrain error during CHUNK_GENERATE: " + t);
            t.printStackTrace();
        }
    }

    /** Final ground height (y of the surface block) for a column. Pure function of (x, z). */
    public static int groundHeight(int x, int z) {
        double n = 0.6 * valueNoise(x / 96.0, z / 96.0)
                 + 0.3 * valueNoise(x / 37.0 + 100.0, z / 37.0 + 100.0)
                 + 0.1 * valueNoise(x / 13.0 + 200.0, z / 13.0 + 200.0);
        double h = MEAN_H + n * AMP;

        // River: a winding band where a large-scale noise field crosses zero.
        double rn = valueNoise(x / 150.0 + 500.0, z / 150.0 + 500.0);
        double band = Math.abs(rn);
        if (band < 0.09) {
            double cut = 1.0 - smooth(Math.max(0.0, (band - 0.03) / 0.06)); // 1 in channel → 0 at banks
            h = h + (49.0 - h) * cut;
        }

        // Church plateau wins near the origin (river dies at the plateau edge).
        double r = Math.sqrt((double) x * x + (double) z * z);
        if (r < BLEND_R) {
            double t = r <= FLAT_R ? 0.0 : smooth((r - FLAT_R) / (BLEND_R - FLAT_R));
            h = PLATEAU_H + (h - PLATEAU_H) * t;
        }
        return (int) Math.round(h);
    }

    // ---- deterministic value noise (bilinear-interpolated lattice hash) ----

    private static double valueNoise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double sx = smooth(fx), sz = smooth(fz);
        double v00 = lattice(x0, z0), v10 = lattice(x0 + 1, z0);
        double v01 = lattice(x0, z0 + 1), v11 = lattice(x0 + 1, z0 + 1);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    /** Hash of a lattice point to [-1, 1]. */
    private static double lattice(int xi, int zi) {
        long h = xi * 341873128712L + zi * 132897987541L + 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return ((h & 0xFFFFFF) / (double) 0x800000) - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
