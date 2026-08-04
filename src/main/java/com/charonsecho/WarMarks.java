package com.charonsecho;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * THE WAR LEAVES MARKS — every casualty paints the ground it fell on.
 *
 * Where a KEEPER falls, sculk blooms: the Restless corrupt the ground they
 * took it on (a golem's death makes the biggest stain, and sometimes grows
 * a sensor that clicks at passing feet). Where a RESTLESS soldier falls,
 * the ground comes back pale: the Keepers cleanse that spot — the canonical
 * surface, asked straight from the generator, no undo data kept. The Wind
 * leaves only a fleck; it holds no ground.
 *
 * Marks only paint natural ground with open air above — stones, paths,
 * builds, tribute flowers, and groundcover are never touched. When a field
 * fills with graves, its marks FREEZE forever: the war's permanent record
 * of who held that yard when it closed. Config: war-marks.
 */
final class WarMarks {

    private WarMarks() {}

    static void stain(ServerLevel level, Mob victim, War.Faction fallen) {
        if (CharonConfig.warMarks == 0 || fallen == null) return;
        BlockPos pos = victim.blockPosition();
        int field = fieldAt(pos);
        if (field < 0 || GraveyardPlots.fieldFull(field)) return; // settled ground keeps its record
        RandomSource rand = level.getRandom();
        switch (fallen) {
            case KEEPERS -> {
                boolean golem = victim.getType() == EntityTypes.IRON_GOLEM;
                bloom(level, pos, golem ? 4 : 3, rand, golem);
            }
            case RESTLESS -> cleanse(level, pos, 3);
            case WIND -> fleck(level, pos);
        }
    }

    /** Which field's ground is this (with a small skirt past the fence)? */
    private static int fieldAt(BlockPos pos) {
        for (int i = 0; i < GraveyardPlots.fieldCount(); i++) {
            BlockPos c = GraveyardPlots.fieldCenter(i);
            if (Math.max(Math.abs(pos.getX() - c.getX()),
                    Math.abs(pos.getZ() - c.getZ())) <= 24) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------- the stains

    private static void bloom(ServerLevel level, BlockPos at, int r, RandomSource rand,
            boolean golem) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = at.getX() + dx, z = at.getZ() + dz;
                // Ragged edges: the corruption grows in fingers, not circles.
                if (dx * dx + dz * dz > (r - 1) * (r - 1)
                        && GraveyardTerrain.blockHash(x, z) > 0.55) continue;
                BlockPos top = topSolid(level, x, z, at.getY());
                if (top == null || !isNaturalGround(level.getBlockState(top))) continue;
                level.setBlock(top, Blocks.SCULK.defaultBlockState(), 3);
                if (rand.nextFloat() < 0.18f) {
                    level.setBlock(top.above(), Blocks.SCULK_VEIN.defaultBlockState(), 3);
                }
            }
        }
        // A golem's grave sometimes grows a sensor — it clicks at the living.
        if (golem && rand.nextFloat() < 0.15f) {
            BlockPos top = topSolid(level, at.getX(), at.getZ(), at.getY());
            if (top != null && level.getBlockState(top).is(Blocks.SCULK)
                    && level.getBlockState(top.above()).isAir()) {
                level.setBlock(top.above(), Blocks.SCULK_SENSOR.defaultBlockState(), 3);
            }
        }
    }

    private static void cleanse(ServerLevel level, BlockPos at, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = at.getX() + dx, z = at.getZ() + dz;
                BlockPos top = topSolid(level, x, z, at.getY());
                if (top == null) continue;
                BlockState above = level.getBlockState(top.above());
                if (above.is(Blocks.SCULK_VEIN) || above.is(Blocks.SCULK_SENSOR)) {
                    level.setBlock(top.above(), Blocks.AIR.defaultBlockState(), 3);
                }
                if (level.getBlockState(top).is(Blocks.SCULK)) {
                    boolean flooded = top.getY() < GraveyardTerrain.WATER_TOP;
                    level.setBlock(top, GraveyardChunkGenerator.surfaceBlock(
                            x, z, top.getY(), flooded), 3);
                }
            }
        }
    }

    private static void fleck(ServerLevel level, BlockPos at) {
        BlockPos top = topSolid(level, at.getX(), at.getZ(), at.getY());
        if (top != null && level.getBlockState(top.above()).isAir()
                && (isNaturalGround(level.getBlockState(top))
                        || level.getBlockState(top).is(Blocks.SCULK))) {
            level.setBlock(top.above(), Blocks.SCULK_VEIN.defaultBlockState(), 3);
        }
    }

    // ------------------------------------------------------------- the rules

    /** Top solid block near the fall — only if the air above it is OPEN.
     *  Flowers, groundcover, stones, and builds shade the ground they stand
     *  on: shaded ground is never painted. */
    private static BlockPos topSolid(ServerLevel level, int x, int z, int yHint) {
        for (int y = yHint + 4; y >= yHint - 8; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            if (!level.getBlockState(p.above()).isAir()) return null;
            return p;
        }
        return null;
    }

    private static boolean isNaturalGround(BlockState state) {
        return state.is(Blocks.PALE_MOSS_BLOCK) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.TUFF)
                || state.is(Blocks.GRAVEL);
    }
}
