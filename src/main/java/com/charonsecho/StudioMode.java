package com.charonsecho;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Set;

/**
 * The Studio: a creative authoring world where the landmark builds (church,
 * headstones, trees, ...) are constructed by hand inside labeled plots, then
 * exported as structure templates the mod pastes into the graveyard.
 *
 * /charon studio — teleport there (creative) and stamp the plot grid
 * /charon export [name] — save the plot you stand in (or the named plot) to
 *     world/generated/charons_echo/structures/<name>.nbt; copy those files into
 *     src/main/resources/data/charons_echo/structure/ to ship them
 * /charon place <name> — paste a template at your feet for review
 */
public final class StudioMode {

    /** One authoring plot: name, footprint (w × d), build height, whether air is saved. */
    public record StudioPlot(String name, int w, int d, int h, boolean keepAir,
                             int x0, int z0) {
        boolean contains(int x, int z) {
            return x >= x0 && x < x0 + w && z >= z0 && z < z0 + d;
        }
    }

    public static final List<StudioPlot> PLOTS = buildLayout();

    /** Game mode each player had before entering the Studio, restored on /charon back. */
    private static final java.util.Map<java.util.UUID, GameType> MODE_BEFORE_STUDIO =
            new java.util.HashMap<>();

    private StudioMode() {}

    /** Restore the game mode a player had before /charon studio switched them to creative. */
    public static void restoreMode(ServerPlayer player) {
        GameType previous = MODE_BEFORE_STUDIO.remove(player.getUUID());
        if (previous != null && previous != GameType.CREATIVE) {
            player.setGameMode(previous);
        }
    }

    /**
     * Plot grid per the DESIGN.md build list. Rows run along +X with 6-block
     * gaps; anchor (x0, z0) is the NW corner; entrances face south (+Z edge).
     * The church keeps interior air (it overwrites terrain when pasted);
     * decorations ignore air so pastes don't punch holes in the hills.
     */
    private static List<StudioPlot> buildLayout() {
        List<StudioPlot> plots = new ArrayList<>();
        int gap = 6;

        // Row 1 (z = 0): the big builds — church, shrine, dock.
        int x = 0, z = 0;
        x = addPlot(plots, "church", 32, 32, 24, true, x, z, gap);
        x = addPlot(plots, "spawn_shrine", 7, 7, 7, true, x, z, gap);
        addPlot(plots, "styx_dock", 9, 5, 6, false, x, z, gap);

        // Row 2 (z = 44): headstones + plinth + lych gate.
        x = 0; z = 44;
        for (int i = 1; i <= 6; i++) {
            x = addPlot(plots, "headstone_" + i, 3, 3, 4, false, x, z, gap);
        }
        x = addPlot(plots, "plinth", 5, 5, 6, false, x, z, gap);
        addPlot(plots, "lych_gate", 5, 3, 5, false, x, z, gap);

        // Row 3 (z = 58): pale trees.
        x = 0; z = 58;
        for (int i = 1; i <= 4; i++) {
            x = addPlot(plots, "pale_tree_" + i, 7, 7, 10, false, x, z, gap);
        }

        // Row 4 (z = 76): clutter (benches, urns, candle clusters, statues).
        x = 0; z = 76;
        for (int i = 1; i <= 6; i++) {
            x = addPlot(plots, "clutter_" + i, 3, 3, 3, false, x, z, gap);
        }
        return List.copyOf(plots);
    }

    private static int addPlot(List<StudioPlot> plots, String name, int w, int d, int h,
                               boolean keepAir, int x0, int z0, int gap) {
        plots.add(new StudioPlot(name, w, d, h, keepAir, x0, z0));
        return x0 + w + gap;
    }

    // ---- /charon studio ----

    public static void enter(ServerPlayer player) {
        ServerLevel studio = player.level().getServer().getLevel(CharonsEcho.STUDIO_DIM);
        if (studio == null) {
            player.sendSystemMessage(Component.literal("Studio dimension is missing — is the mod installed correctly?")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        stampLayout(studio);
        int y = surfaceY(studio, -4, -4);
        player.teleportTo(studio, -4.5, y + 1, -4.5, Set.<Relative>of(), 45f, 0f, false);
        MODE_BEFORE_STUDIO.putIfAbsent(player.getUUID(), player.gameMode());
        player.setGameMode(GameType.CREATIVE);
        player.sendSystemMessage(Component.literal("Welcome to the Studio. Build inside the outlines; ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("lime = NW anchor, orange = south entrance. ")
                        .withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal("Stand in a plot and run /charon export when done.")
                        .withStyle(ChatFormatting.GRAY)));
    }

    /** Idempotent: outlines + markers + signs are simply re-stamped each time. */
    private static void stampLayout(ServerLevel level) {
        BlockState glass = Blocks.STAINED_GLASS.white().defaultBlockState();
        BlockState lime = Blocks.CONCRETE.lime().defaultBlockState();
        BlockState orange = Blocks.CONCRETE.orange().defaultBlockState();

        for (StudioPlot p : PLOTS) {
            int y = surfaceY(level, p.x0(), p.z0());
            // Perimeter outline in the ground layer.
            for (int x = p.x0() - 1; x <= p.x0() + p.w(); x++) {
                setGround(level, glass, x, y, p.z0() - 1);
                setGround(level, glass, x, y, p.z0() + p.d());
            }
            for (int z = p.z0() - 1; z <= p.z0() + p.d(); z++) {
                setGround(level, glass, p.x0() - 1, y, z);
                setGround(level, glass, p.x0() + p.w(), y, z);
            }
            // NW anchor + south-entrance marker.
            setGround(level, lime, p.x0() - 1, y, p.z0() - 1);
            setGround(level, orange, p.x0() + p.w() / 2, y, p.z0() + p.d());

            // Label sign just north-west of the plot.
            BlockPos signPos = new BlockPos(p.x0(), y + 1, p.z0() - 3);
            level.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 3);
            if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                SignText text = new SignText()
                        .setMessage(0, Component.literal(p.name()))
                        .setMessage(1, Component.literal(p.w() + "x" + p.d() + " h" + p.h()))
                        .setMessage(2, Component.literal("build inside"))
                        .setMessage(3, Component.literal("face south"));
                sign.setText(text, true);
                sign.setChanged();
            }
        }
    }

    private static void setGround(ServerLevel level, BlockState state, int x, int y, int z) {
        level.setBlock(new BlockPos(x, y, z), state, 3);
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        // Force the chunk so the heightmap exists, then take the top solid block.
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    // ---- /charon export ----

    public static void export(ServerPlayer player, String nameOrEmpty) {
        ServerLevel level = (ServerLevel) player.level();
        if (level.dimension() != CharonsEcho.STUDIO_DIM) {
            player.sendSystemMessage(Component.literal("Run this in the Studio (/charon studio).")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        StudioPlot plot = null;
        if (nameOrEmpty.isEmpty()) {
            int px = player.getBlockX(), pz = player.getBlockZ();
            for (StudioPlot p : PLOTS) {
                if (p.contains(px, pz)) { plot = p; break; }
            }
        } else {
            for (StudioPlot p : PLOTS) {
                if (p.name().equals(nameOrEmpty)) { plot = p; break; }
            }
        }
        if (plot == null) {
            player.sendSystemMessage(Component.literal("Stand inside a plot (or name one): " +
                    String.join(", ", PLOTS.stream().map(StudioPlot::name).toList()))
                    .withStyle(ChatFormatting.RED));
            return;
        }

        int y = surfaceY(level, plot.x0(), plot.z0());
        BlockPos start = new BlockPos(plot.x0(), y + 1, plot.z0());
        Vec3i size = new Vec3i(plot.w(), plot.h(), plot.d());

        StructureTemplateManager manager = level.getServer().getStructureManager();
        Identifier id = Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, plot.name());
        StructureTemplate template = manager.getOrCreate(id);
        template.fillFromWorld(level, start, size, false,
                plot.keepAir() ? List.of() : List.of(Blocks.AIR));
        template.setAuthor(player.getName().getString());
        boolean ok = manager.save(id);

        if (ok) {
            player.sendSystemMessage(Component.literal("Exported '" + plot.name() + "' (" +
                    plot.w() + "x" + plot.h() + "x" + plot.d() + ") to world/generated/" +
                    CharonsEcho.MOD_ID + "/structures/. Copy it into the mod's " +
                    "data/charons_echo/structure/ folder to ship it.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.literal("Export failed — see server log.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    // ---- /charon place ----

    public static void place(ServerPlayer player, String name) {
        ServerLevel level = (ServerLevel) player.level();
        StructureTemplateManager manager = level.getServer().getStructureManager();
        Identifier id = Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, name);
        var template = manager.get(id);
        if (template.isEmpty()) {
            player.sendSystemMessage(Component.literal("No template '" + name +
                    "' — export it first, or ship it in data/charons_echo/structure/.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos at = player.blockPosition().offset(2, 0, 2);
        template.get().placeInWorld(level, at, at, new StructurePlaceSettings(),
                RandomSource.create(level.getSeed() ^ at.asLong()), 3);
        player.sendSystemMessage(Component.literal("Placed '" + name + "' at " +
                at.getX() + " " + at.getY() + " " + at.getZ())
                .withStyle(ChatFormatting.GREEN));
    }
}
