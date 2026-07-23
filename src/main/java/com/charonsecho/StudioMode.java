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

    private static final List<StudioPlot> BASE_PLOTS = buildLayout();

    /**
     * Builder-created plots (via /charon plot new), persisted with an approved
     * flag. Approved + exported templates join the generation pool.
     */
    public static final class DynamicPlot {
        public final StudioPlot plot;
        public final String category;
        public final String author;
        public boolean approved;

        DynamicPlot(StudioPlot plot, String category, String author, boolean approved) {
            this.plot = plot;
            this.category = category;
            this.author = author;
            this.approved = approved;
        }
    }

    /** Category presets — sizes are LOCKED to the established plot standards. */
    public record Category(String name, int w, int d, int h, boolean keepAir, int rowZ) {}

    public static final List<Category> CATEGORIES = List.of(
            new Category("headstone", 3, 3, 4, false, 44),
            new Category("tree", 7, 7, 10, false, 58),
            new Category("clutter", 3, 3, 3, false, 76),
            new Category("ruin", 12, 12, 9, true, 94),
            new Category("big_tree", 11, 11, 14, false, 116),
            new Category("gate", 5, 3, 5, false, 140),
            new Category("building", 16, 16, 12, true, 152));

    private static final List<DynamicPlot> DYNAMIC = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static java.nio.file.Path dynamicFile;

    /** All plots: the hand-authored base layout plus builder-created ones. */
    public static List<StudioPlot> allPlots() {
        List<StudioPlot> all = new ArrayList<>(BASE_PLOTS);
        for (DynamicPlot d : DYNAMIC) all.add(d.plot);
        return all;
    }

    /** Base-plot category membership by name prefix (headstone_3 → headstone). */
    private static String baseCategory(String plotName) {
        for (Category c : CATEGORIES) {
            if (plotName.startsWith(c.name() + "_") || plotName.startsWith("pale_" + c.name())) return c.name();
        }
        if (plotName.startsWith("pale_tree")) return "tree";
        return "";
    }

    /**
     * Templates eligible for generation in a category: base plots (auto-trusted)
     * and APPROVED dynamic plots — in both cases only if actually exported.
     */
    public static List<String> approvedTemplates(String category,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager) {
        List<String> out = new ArrayList<>();
        for (StudioPlot p : BASE_PLOTS) {
            if (baseCategory(p.name()).equals(category)
                    && manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, p.name())).isPresent()) {
                out.add(p.name());
            }
        }
        for (DynamicPlot d : DYNAMIC) {
            if (d.approved && d.category.equals(category)
                    && manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, d.plot.name())).isPresent()) {
                out.add(d.plot.name());
            }
        }
        return out;
    }

    /** Create a new plot at the end of its category row; returns it (or null on bad input). */
    public static StudioPlot createPlot(ServerLevel studio, String categoryName, String name, String author) {
        Category cat = CATEGORIES.stream().filter(c -> c.name().equals(categoryName)).findFirst().orElse(null);
        if (cat == null) return null;
        boolean taken = allPlots().stream().anyMatch(p -> p.name().equals(name));
        if (taken) return null;
        int endX = 0;
        for (StudioPlot p : allPlots()) {
            if (p.z0() == cat.rowZ()) endX = Math.max(endX, p.x0() + p.w() + 6);
        }
        StudioPlot plot = new StudioPlot(name, cat.w(), cat.d(), cat.h(), cat.keepAir(), endX, cat.rowZ());
        DYNAMIC.add(new DynamicPlot(plot, cat.name(), author, false));
        saveDynamic();
        stampPlot(studio, plot);
        return plot;
    }

    public static DynamicPlot findDynamic(String name) {
        for (DynamicPlot d : DYNAMIC) {
            if (d.plot.name().equals(name)) return d;
        }
        return null;
    }

    public static List<DynamicPlot> dynamicPlots() {
        return List.copyOf(DYNAMIC);
    }

    public static void markApproved(String name) {
        DynamicPlot d = findDynamic(name);
        if (d != null) {
            d.approved = true;
            saveDynamic();
        }
    }

    // ---- dynamic-plot persistence (world/charons_echo/studio_plots.dat) ----

    public static void loadDynamic(net.minecraft.server.MinecraftServer server) {
        DYNAMIC.clear();
        dynamicFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("charons_echo").resolve("studio_plots.dat");
        if (!java.nio.file.Files.exists(dynamicFile)) return;
        try {
            var root = net.minecraft.nbt.NbtIo.readCompressed(dynamicFile,
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            for (var t : root.getListOrEmpty("plots")) {
                if (!(t instanceof net.minecraft.nbt.CompoundTag c)) continue;
                StudioPlot plot = new StudioPlot(
                        c.getStringOr("name", "?"), c.getIntOr("w", 3), c.getIntOr("d", 3),
                        c.getIntOr("h", 4), c.getBooleanOr("keepAir", false),
                        c.getIntOr("x0", 0), c.getIntOr("z0", 0));
                DYNAMIC.add(new DynamicPlot(plot, c.getStringOr("category", ""),
                        c.getStringOr("author", "?"), c.getBooleanOr("approved", false)));
            }
        } catch (java.io.IOException e) {
            System.out.println("[CharonsEcho] failed to load studio_plots.dat: " + e);
        }
    }

    private static void saveDynamic() {
        if (dynamicFile == null) return;
        try {
            java.nio.file.Files.createDirectories(dynamicFile.getParent());
            var list = new net.minecraft.nbt.ListTag();
            for (DynamicPlot d : DYNAMIC) {
                var t = new net.minecraft.nbt.CompoundTag();
                t.putString("name", d.plot.name());
                t.putInt("w", d.plot.w());
                t.putInt("d", d.plot.d());
                t.putInt("h", d.plot.h());
                t.putBoolean("keepAir", d.plot.keepAir());
                t.putInt("x0", d.plot.x0());
                t.putInt("z0", d.plot.z0());
                t.putString("category", d.category);
                t.putString("author", d.author);
                t.putBoolean("approved", d.approved);
                list.add(t);
            }
            var root = new net.minecraft.nbt.CompoundTag();
            root.put("plots", list);
            net.minecraft.nbt.NbtIo.writeCompressed(root, dynamicFile);
        } catch (java.io.IOException e) {
            System.out.println("[CharonsEcho] failed to save studio_plots.dat: " + e);
        }
    }

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
    /**
     * One row per category — graves in the graves row, gates in the gates row,
     * trees with trees, tall trees with tall trees. Builder-created plots
     * (/charon plot new) append to the end of the matching row.
     */
    private static List<StudioPlot> buildLayout() {
        List<StudioPlot> plots = new ArrayList<>();
        int gap = 6;

        // Row 0 (z = 0): landmarks — one-off builds, not scatter categories.
        int x = 0, z = 0;
        x = addPlot(plots, "church", 32, 32, 24, true, x, z, gap);
        x = addPlot(plots, "spawn_shrine", 7, 7, 7, true, x, z, gap);
        x = addPlot(plots, "styx_dock", 9, 5, 6, false, x, z, gap);
        addPlot(plots, "plinth", 5, 5, 6, false, x, z, gap);

        // Graves row (z = 44).
        x = 0; z = 44;
        for (int i = 1; i <= 6; i++) {
            x = addPlot(plots, "headstone_" + i, 3, 3, 4, false, x, z, gap);
        }

        // Trees row (z = 58).
        x = 0; z = 58;
        for (int i = 1; i <= 6; i++) {
            x = addPlot(plots, "pale_tree_" + i, 7, 7, 10, false, x, z, gap);
        }

        // Clutter row (z = 76): benches, urns, candle clusters, statues.
        x = 0; z = 76;
        for (int i = 1; i <= 8; i++) {
            x = addPlot(plots, "clutter_" + i, 3, 3, 3, false, x, z, gap);
        }

        // Ruins row (z = 94): echoes of the folk who came before.
        x = 0; z = 94;
        x = addPlot(plots, "ruin_cottage", 12, 12, 9, true, x, z, gap);
        x = addPlot(plots, "ruin_tower", 7, 7, 12, true, x, z, gap);
        x = addPlot(plots, "ruin_wall_a", 7, 3, 4, false, x, z, gap);
        x = addPlot(plots, "ruin_wall_b", 7, 3, 4, false, x, z, gap);
        x = addPlot(plots, "ruin_well", 5, 5, 6, false, x, z, gap);
        addPlot(plots, "ruin_arch", 7, 3, 7, false, x, z, gap);

        // Tall trees row (z = 116): ridgeline pieces.
        x = 0; z = 116;
        x = addPlot(plots, "big_tree_1", 11, 11, 14, false, x, z, gap);
        addPlot(plots, "big_tree_2", 11, 11, 14, false, x, z, gap);

        // Gates row (z = 140): field entrances — lych gate + variations.
        x = 0; z = 140;
        addPlot(plots, "gate_lych", 5, 3, 5, false, x, z, gap);

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
        for (StudioPlot p : allPlots()) {
            stampPlot(level, p);
        }
    }

    static void stampPlot(ServerLevel level, StudioPlot p) {
        BlockState glass = Blocks.STAINED_GLASS.white().defaultBlockState();
        BlockState lime = Blocks.CONCRETE.lime().defaultBlockState();
        BlockState orange = Blocks.CONCRETE.orange().defaultBlockState();

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
                    .setMessage(1, Component.literal(p.w() + "x" + p.d() + ", max h " + p.h()))
                    .setMessage(2, Component.literal("lime = NW anchor"))
                    .setMessage(3, Component.literal("orange = south"));
            sign.setText(text, true);
            sign.setChanged();
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
        List<StudioPlot> plots = allPlots();
        StudioPlot plot = null;
        if (nameOrEmpty.isEmpty()) {
            int px = player.getBlockX(), pz = player.getBlockZ();
            for (StudioPlot p : plots) {
                if (p.contains(px, pz)) { plot = p; break; }
            }
        } else {
            for (StudioPlot p : plots) {
                if (p.name().equals(nameOrEmpty)) { plot = p; break; }
            }
        }
        if (plot == null) {
            player.sendSystemMessage(Component.literal("Stand inside a plot (or name one): " +
                    String.join(", ", plots.stream().map(StudioPlot::name).toList()))
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
