package com.charonsecho;

/**
 * Pure terrain math for the graveyard dimension: gentle monochrome hills, a
 * flattened plateau for the church at the origin, a winding river, and small
 * ponds in the lowest vales. Deterministic — pure function of (x, z), no
 * Random state. Blocks are written by {@link GraveyardChunkGenerator}.
 */
public final class GraveyardTerrain {

    /**
     * Mean terrain height. Relief is asymmetric: hills rise hard (up to ~+26)
     * while vales sink gently (to ~-13), so the land reads as dramatic hills
     * without flooding into lakes everywhere.
     */
    private static final int MEAN_H = 64;
    private static final double AMP_UP = 26.0;
    private static final double AMP_DOWN = 13.0;
    /** Church plateau: flat at PLATEAU_H within FLAT_R, blended out to BLEND_R. */
    private static final int PLATEAU_H = 64;
    private static final double FLAT_R = 32.0;
    private static final double BLEND_R = 80.0;
    /** Any column whose ground ends below this gets still water up to it. */
    public static final int WATER_TOP = 52;

    private GraveyardTerrain() {}

    /** Final ground height (y of the surface block) for a column. Pure function of (x, z). */
    public static int groundHeight(int x, int z) {
        double n = 0.45 * valueNoise(x / 220.0, z / 220.0)
                 + 0.35 * valueNoise(x / 80.0 + 100.0, z / 80.0 + 100.0)
                 + 0.20 * valueNoise(x / 28.0 + 200.0, z / 28.0 + 200.0);
        double h = MEAN_H + (n >= 0 ? n * AMP_UP : n * AMP_DOWN);

        // River: a winding band where a large-scale noise field crosses zero.
        double rn = valueNoise(x / 150.0 + 500.0, z / 150.0 + 500.0);
        double band = Math.abs(rn);
        if (band < 0.10) {
            double cut = 1.0 - smooth(Math.max(0.0, (band - 0.035) / 0.065)); // 1 in channel → 0 at banks
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
