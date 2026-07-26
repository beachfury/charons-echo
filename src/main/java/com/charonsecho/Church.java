package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The church — the singleton heart of Charon's Echo, pasted once onto the
 * plateau at the origin, entrance facing south (the studio convention).
 *
 * After the paste, the MARKER VOCABULARY is read from the build itself:
 *   gilded blackstone = vendor post (the Broker moves there)
 *   lodestone         = crypt stairwell (the crypt digs from here, later)
 *   lectern           = the ledger (Book of the Dead)
 * Wiping the graveyard wipes church.dat too — the church returns with the
 * terrain, like everything else.
 */
public final class Church {

    /** Footprint: 32x32 centered on the origin — the plateau is flat to r32. */
    private static final int HALF = 16;

    private static Path file;
    private static boolean placed;
    private static BlockPos vendor;
    private static BlockPos lodestone;
    private static BlockPos lectern;

    private Church() {}

    public static void ensure(MinecraftServer server) {
        if (placed) return;
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        var template = server.getStructureManager()
                .get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, "church"));
        if (template.isEmpty()) return;

        int ground = GraveyardTerrain.groundHeight(0, 0); // the plateau: 64
        // The church's below-grade FLOOR layer (dug one down in the studio)
        // lands AT ground, replacing the plateau surface — flush, with a
        // proper floor. Measured, so an old floorless export still stands.
        int below = StudioMode.belowGradeOf(template.get(), "church");
        BlockPos origin = new BlockPos(-HALF, ground + 1 - below, -HALF);
        for (int cx = (-HALF) >> 4; cx <= (HALF - 1) >> 4; cx++) {
            for (int cz = (-HALF) >> 4; cz <= (HALF - 1) >> 4; cz++) {
                graveyard.getChunk(cx, cz);
            }
        }
        // Clear the footprint first — a re-paste (marker fixes, updated build)
        // must never leave crusts of the old church behind. With a floor
        // shipping, the surface layer clears too (the floor replaces it).
        int clearFrom = below > 0 ? ground : ground + 1;
        for (int x = -HALF; x < HALF; x++) {
            for (int z = -HALF; z < HALF; z++) {
                for (int y = clearFrom; y <= ground + 50; y++) {
                    graveyard.setBlock(new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        template.get().placeInWorld(graveyard, origin, origin,
                new StructurePlaceSettings(), RandomSource.create(0xC0FF1AL), 2);

        // Read the marker vocabulary from the build itself.
        vendor = null;
        lodestone = null;
        lectern = null;
        for (int x = -HALF; x < HALF; x++) {
            for (int z = -HALF; z < HALF; z++) {
                for (int y = ground; y <= ground + 50; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = graveyard.getBlockState(pos);
                    if (vendor == null && state.is(Blocks.GILDED_BLACKSTONE)) {
                        vendor = pos.immutable();
                    } else if (lodestone == null && state.is(Blocks.LODESTONE)) {
                        lodestone = pos.immutable();
                    } else if (lectern == null && state.is(Blocks.LECTERN)) {
                        lectern = pos.immutable();
                    }
                }
            }
        }
        // Markers are INSTRUCTIONS, not furniture: once read, the gilded
        // blackstone and lodestone dissolve — only the lectern is real.
        if (vendor != null) {
            graveyard.setBlock(vendor, Blocks.AIR.defaultBlockState(), 3);
        }
        if (lodestone != null) {
            graveyard.setBlock(lodestone, Blocks.AIR.defaultBlockState(), 3);
        }
        placed = true;
        save();
        System.out.println("[CharonsEcho] the church stands on the plateau"
                + (vendor != null ? "; vendor post at " + vendor.toShortString() : "")
                + (lodestone != null ? "; crypt stairwell marked at " + lodestone.toShortString() : "")
                + (lectern != null ? "; ledger at " + lectern.toShortString() : ""));
    }

    /** Where the Broker belongs: on the floor where the vendor marker stood. */
    public static BlockPos vendorStand() {
        return placed && vendor != null ? vendor : null;
    }

    public static BlockPos cryptMarker() {
        return placed ? lodestone : null;
    }

    public static BlockPos ledgerMarker() {
        return placed ? lectern : null;
    }

    // ---- persistence (world/charons_echo/church.dat) ----

    public static void load(MinecraftServer server) {
        placed = false;
        vendor = null;
        lodestone = null;
        lectern = null;
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("church.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            placed = root.getBooleanOr("placed", false);
            if (root.getLongOr("vendor", Long.MIN_VALUE) != Long.MIN_VALUE) {
                vendor = BlockPos.of(root.getLongOr("vendor", 0));
            }
            if (root.getLongOr("lodestone", Long.MIN_VALUE) != Long.MIN_VALUE) {
                lodestone = BlockPos.of(root.getLongOr("lodestone", 0));
            }
            if (root.getLongOr("lectern", Long.MIN_VALUE) != Long.MIN_VALUE) {
                lectern = BlockPos.of(root.getLongOr("lectern", 0));
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load church.dat: " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putBoolean("placed", placed);
            if (vendor != null) root.putLong("vendor", vendor.asLong());
            if (lodestone != null) root.putLong("lodestone", lodestone.asLong());
            if (lectern != null) root.putLong("lectern", lectern.asLong());
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save church.dat: " + e);
        }
    }
}
