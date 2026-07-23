package com.charonsecho;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * The graveyard's real chunk generator: monochrome hills, river, church plateau.
 * Terrain is written during the proper NOISE stage (before lighting), so light
 * and heightmaps are computed by vanilla exactly as for any world — unlike the
 * old CHUNK_GENERATE-event approach, which injected blocks after lighting and
 * shipped chunks with corrupt light data to the client.
 *
 * Height math lives in {@link GraveyardTerrain} (pure function of x/z).
 */
public final class GraveyardChunkGenerator extends ChunkGenerator {

    public static final MapCodec<GraveyardChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Biome.CODEC.fieldOf("biome").forGetter(g -> g.biome)
            ).apply(instance, GraveyardChunkGenerator::new));

    private final Holder<Biome> biome;

    public GraveyardChunkGenerator(Holder<Biome> biome) {
        super(new FixedBiomeSource(biome));
        this.biome = biome;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX(), baseZ = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState carpet = Blocks.PALE_MOSS_CARPET.defaultBlockState();

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx, z = baseZ + dz;
                int h = GraveyardTerrain.groundHeight(x, z);
                boolean flooded = h < GraveyardTerrain.WATER_TOP;
                BlockState surf = surfaceBlock(x, z, h, flooded);

                for (int y = 0; y <= Math.max(h, flooded ? GraveyardTerrain.WATER_TOP : h); y++) {
                    BlockState state;
                    if (y == 0) state = bedrock;
                    else if (y < h - 3) state = deepslate;
                    else if (y < h) state = tuff;
                    else if (y == h) state = surf;
                    else state = water; // y > h only happens when flooded
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, state);
                    oceanFloor.update(dx, y, dz, state);
                    worldSurface.update(dx, y, dz, state);
                }

                // Sparse pale-moss-carpet tufts on open moss ground.
                if (!flooded && surf.is(Blocks.PALE_MOSS_BLOCK)
                        && GraveyardTerrain.blockHash(x, z) < 0.10) {
                    pos.set(x, h + 1, z);
                    chunk.setBlockState(pos, carpet);
                    worldSurface.update(dx, h + 1, dz, carpet);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Surface material mix — Pale Garden above, Deep Dark seeping up from below:
     * exposed deepslate on steep slopes, sculk pooling in the deep vales and on
     * riverbeds (the Styx runs dark), rare tuff mottling, pale moss elsewhere.
     */
    private static BlockState surfaceBlock(int x, int z, int h, boolean flooded) {
        double sn = GraveyardTerrain.surfaceNoise(x, z);
        if (flooded) {
            return sn > 0.05 ? Blocks.SCULK.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        int slope = Math.max(
                Math.max(Math.abs(h - GraveyardTerrain.groundHeight(x + 1, z)),
                         Math.abs(h - GraveyardTerrain.groundHeight(x - 1, z))),
                Math.max(Math.abs(h - GraveyardTerrain.groundHeight(x, z + 1)),
                         Math.abs(h - GraveyardTerrain.groundHeight(x, z - 1))));
        if (slope >= 3) return Blocks.DEEPSLATE.defaultBlockState();
        if (h <= 56 && sn > 0.10) return Blocks.SCULK.defaultBlockState();
        if (sn < -0.72) return Blocks.TUFF.defaultBlockState();
        return Blocks.PALE_MOSS_BLOCK.defaultBlockState();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState rs) {
        int h = GraveyardTerrain.groundHeight(x, z);
        if (type == Heightmap.Types.WORLD_SURFACE_WG || type == Heightmap.Types.WORLD_SURFACE) {
            return Math.max(h, h < GraveyardTerrain.WATER_TOP ? GraveyardTerrain.WATER_TOP : h) + 1;
        }
        return h + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState rs) {
        int h = GraveyardTerrain.groundHeight(x, z);
        boolean flooded = h < GraveyardTerrain.WATER_TOP;
        int top = flooded ? GraveyardTerrain.WATER_TOP : h;
        BlockState[] column = new BlockState[top + 1];
        for (int y = 0; y <= top; y++) {
            if (y == 0) column[y] = Blocks.BEDROCK.defaultBlockState();
            else if (y < h - 3) column[y] = Blocks.DEEPSLATE.defaultBlockState();
            else if (y < h) column[y] = Blocks.TUFF.defaultBlockState();
            else if (y == h) column[y] = flooded ? Blocks.GRAVEL.defaultBlockState()
                                                : Blocks.PALE_MOSS_BLOCK.defaultBlockState();
            else column[y] = Blocks.WATER.defaultBlockState();
        }
        return new NoiseColumn(0, column);
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState rs, BiomeManager biomes,
                             StructureManager structures, ChunkAccess chunk) {
        // No caves, ever.
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState rs, ChunkAccess chunk) {
        // Surface is written in fillFromNoise.
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Gravekeepers are placed by the mod, not by worldgen.
    }

    @Override
    public int getGenDepth() {
        return 256;
    }

    @Override
    public int getSeaLevel() {
        return GraveyardTerrain.WATER_TOP;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState rs, BlockPos pos) {
        lines.add("Charon's Echo h=" + GraveyardTerrain.groundHeight(pos.getX(), pos.getZ()));
    }
}
