package com.charonsecho;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
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
    /** Fence ring offset: +2, so edge-plot terracing (footprint+1) can never chew the fence. */
    private static final int FENCE_OFF = 22;
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

    /** How many fields exist NOW — never grows the list (unlike fieldCenter). */
    static int fieldCount() {
        synchronized (FIELD_CENTERS) {
            return FIELD_CENTERS.size();
        }
    }

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
        int r = FENCE_OFF + 1;
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
        return (max - min) <= 6; // gentle ground only — steep fields caused pits AND towers
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

    /** Strict rule: any decor footprint touching the 6x6 plot blocks it forever. */
    static boolean plotBlocked(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        return DecorScatter.decorOverlapping(o.getX(), o.getZ(),
                o.getX() + PLOT - 1, o.getZ() + PLOT - 1);
    }

    /** A field is full when every plot is either claimed or tree-blocked. */
    static boolean fieldFull(int fieldIndex) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex >= 0 && g.plotIndex / PER_FIELD == fieldIndex) {
                used.add(g.plotIndex);
            }
        }
        for (int p = fieldIndex * PER_FIELD; p < (fieldIndex + 1) * PER_FIELD; p++) {
            if (!used.contains(p) && !plotBlocked(p)) return false;
        }
        return true;
    }

    /**
     * A flower laid at a grave: counted as a tribute (the vote), and planted
     * as a REAL flower on the plot — the only color the monochrome world
     * allows is what the living leave behind. Plots hold ~20; when full, the
     * vote still counts and the flower is simply taken by the wind.
     */
    static boolean layTribute(ServerLevel level, GraveManager.Grave grave,
            net.minecraft.world.item.ItemStack held, net.minecraft.server.level.ServerPlayer mourner) {
        if (grave.plotIndex < 0) return false;
        // ONE flower per mourner per grave — grief is not a spam mechanic,
        // and the owner gets the same single vanity vote as everyone else.
        if (!grave.mourners.add(mourner.getUUID())) {
            mourner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "You have already laid your flower here.")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            return false;
        }
        grave.tributes++;
        GraveManager.save();
        BlockPos o = plotOrigin(grave.plotIndex);
        int surf = plotSurfaceY(grave.plotIndex);
        if (held.getItem() instanceof net.minecraft.world.item.BlockItem flower) {
            var state = flower.getBlock().defaultBlockState();
            boolean tall = state.hasProperty(net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.DOUBLE_BLOCK_HALF);
            for (int i = 0; i < PLOT * PLOT; i++) {
                int scan = Math.floorMod(grave.tributes * 7 + i, PLOT * PLOT);
                BlockPos spot = new BlockPos(o.getX() + scan % PLOT, surf + 1, o.getZ() + scan / PLOT);
                if (level.getBlockState(spot).isAir()
                        && !level.getBlockState(spot.below()).isAir()
                        && state.canSurvive(level, spot)
                        && (!tall || level.getBlockState(spot.above()).isAir())) {
                    level.setBlock(spot, state, 3);
                    if (tall) {
                        level.setBlock(spot.above(), state.setValue(
                                net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.DOUBLE_BLOCK_HALF,
                                net.minecraft.world.level.block.state.properties
                                        .DoubleBlockHalf.UPPER), 3);
                    }
                    break;
                }
            }
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CHERRY_LEAVES,
                o.getX() + PLOT / 2.0, surf + 1.5, o.getZ() + PLOT / 2.0, 8, 1.2, 0.5, 1.2, 0.01);
        return true;
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
     * Plot terrace height: the highest natural column within the plot PLUS a
     * 2-block margin ring. High enough that no adjacent ground can pit the
     * stone; local enough that a slope across the field can't stack the plot
     * into a tower. Fill-up only — pits are never dug.
     */
    public static int plotSurfaceY(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        int max = Integer.MIN_VALUE;
        for (int x = o.getX() - 2; x < o.getX() + PLOT + 2; x++) {
            for (int z = o.getZ() - 2; z < o.getZ() + PLOT + 2; z++) {
                max = Math.max(max, GraveyardTerrain.groundHeight(x, z));
            }
        }
        return max;
    }

    /**
     * Assign the next plot to a grave: fence the field if its fence is missing
     * (first burial, or regenerated terrain), cut the plot's own terrace,
     * raise the stone, and note on the gate sign when the field fills.
     */
    public static void allocate(ServerLevel graveyard, GraveManager.Grave grave) {
        // Graves go AROUND the trees: a plot with decor standing on it is
        // never assigned — the dead make room for what already grows.
        int idx = nextPlotIndex();
        while (plotBlocked(idx)) idx++;
        grave.plotIndex = idx;
        ensureField(graveyard, idx / PER_FIELD);
        placeHeadstone(graveyard, grave); // terraces its own footprint
        SignBlockEntity fieldSign = findFieldSign(graveyard, idx / PER_FIELD);
        if (fieldSign != null) {
            writeFieldSign(fieldSign, idx / PER_FIELD); // the soul count ticks up
        }
        if (fieldFull(idx / PER_FIELD)) {
            markFieldFull(graveyard, idx / PER_FIELD);
        }
        Crypt.refreshShelves(graveyard.getServer()); // today's shelf gains a book
        GraveManager.save();
    }

    /**
     * Gate sign position: first dry spot on the south fence line, starting
     * beside the gate. Deterministic, so re-fence checks find the same spot.
     */
    private static BlockPos gateSignPos(int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        int f = FENCE_OFF, z = c.getZ() + f;
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
        if (findFieldSign(level, fieldIndex) == null) {
            fenceField(level, fieldIndex);
        }
    }

    /**
     * ADMIN: tear out a field's fence and gate — legacy ring and current ring
     * both — and rebuild with whatever the set offers today (the fence kit,
     * the wider ring). Existing worlds upgrade on command, never by surprise.
     */
    public static void refence(ServerLevel level, int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        // Sweep fence furniture off both candidate rings.
        for (int r : new int[] { FIELD_HALF + 1, FENCE_OFF }) {
            for (int x = c.getX() - r; x <= c.getX() + r; x++) {
                sweepFencePost(level, x, c.getZ() - r);
                sweepFencePost(level, x, c.getZ() + r);
            }
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++) {
                sweepFencePost(level, c.getX() - r, z);
                sweepFencePost(level, c.getX() + r, z);
            }
        }
        // Clear the gate footprint on both south lines (old and new).
        int w = StudioMode.widthOfCategory("gate") + 2;
        for (int fenceZ : new int[] { c.getZ() + FIELD_HALF + 1, c.getZ() + FENCE_OFF }) {
            for (int x = c.getX() - w / 2 - 1; x <= c.getX() + w / 2 + 1; x++) {
                for (int z = fenceZ - 5; z <= fenceZ + 5; z++) {
                    level.getChunk(x >> 4, z >> 4);
                    int g = GraveyardTerrain.groundHeight(x, z);
                    for (int y = g + 1; y <= g + 12; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!level.getBlockState(p).isAir()) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
        fenceField(level, fieldIndex);
    }

    /** Fences, walls, and lanterns come off the ring — nothing else does. */
    private static void sweepFencePost(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int g = GraveyardTerrain.groundHeight(x, z);
        for (int y = g; y <= g + 5; y++) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(p);
            if (state.is(net.minecraft.tags.BlockTags.FENCES)
                    || state.is(net.minecraft.tags.BlockTags.WALLS)
                    || state.is(Blocks.SOUL_LANTERN) || state.is(Blocks.LANTERN)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    /**
     * The sign that speaks for a field: the lych gate's hanging sign first
     * (any sign inside the gate footprint), the old standing sign as fallback.
     */
    private static SignBlockEntity findFieldSign(ServerLevel level, int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        int fenceZ = c.getZ() + FENCE_OFF;
        for (int x = c.getX() - 5; x <= c.getX() + 5; x++) {
            for (int z = fenceZ - 4; z <= fenceZ + 4; z++) {
                level.getChunk(x >> 4, z >> 4);
                int g = GraveyardTerrain.groundHeight(x, z);
                for (int y = g; y <= g + 10; y++) {
                    if (level.getBlockEntity(new BlockPos(x, y, z)) instanceof SignBlockEntity sign) {
                        return sign;
                    }
                }
            }
        }
        BlockPos p = gateSignPos(fieldIndex);
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        if (level.getBlockEntity(p) instanceof SignBlockEntity sign) {
            return sign;
        }
        return null;
    }

    private static String shortDate() {
        return new java.text.SimpleDateFormat("M/d/yy").format(new java.util.Date());
    }

    /**
     * Fence the field perimeter, following the terrain. The lych gate straddles
     * the south fence line (its own hanging sign carries the field's ledger);
     * fence-kit templates tile the runs when they exist, plain pale-oak fence
     * otherwise.
     */
    private static void fenceField(ServerLevel level, int fieldIndex) {
        BlockPos c = fieldCenter(fieldIndex);
        var manager = level.getServer().getStructureManager();
        String set = StudioSets.setForRegion(c.getX(), c.getZ());

        boolean gate = placeGate(level, fieldIndex, manager, set);
        java.util.List<String> straights = StudioMode.approvedTemplates("fence_straight", manager, set);
        java.util.List<String> corners = StudioMode.approvedTemplates("fence_corner", manager, set);
        if (!straights.isEmpty()) {
            tiledFence(level, fieldIndex, manager, straights, corners, gate);
        } else {
            basicFence(level, fieldIndex, gate ? 4 : 1);
        }

        SignBlockEntity sign = findFieldSign(level, fieldIndex);
        if (sign == null) {
            // No gate (or a gate with no sign in it): the old standing sign.
            BlockPos signPos = gateSignPos(fieldIndex);
            level.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 2);
            if (level.getBlockEntity(signPos) instanceof SignBlockEntity s) sign = s;
        }
        if (sign != null) writeFieldSign(sign, fieldIndex);

        // A field opens with its staff already on duty.
        GraveyardRules.censusField(level, fieldIndex);
    }

    /**
     * The gate's ledger: field number, opening date (preserved once written),
     * soul count kept live as burials happen, filled date when the yard closes.
     */
    static void writeFieldSign(SignBlockEntity sign, int fieldIndex) {
        // Hanging signs clip long lines into nothing — every line stays short.
        // Layout: "Field N" / opening date (real calendar) / soul count / "† date" when full.
        String opened = sign.getFrontText().getMessage(1, false).getString();
        if (opened.isEmpty() || !Character.isDigit(opened.charAt(0))) opened = shortDate();
        String closed = sign.getFrontText().getMessage(3, false).getString();
        int souls = 0;
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.plotIndex >= 0 && g.plotIndex / PER_FIELD == fieldIndex) souls++;
        }
        SignText text = new SignText()
                .setMessage(0, Component.literal("Field " + (fieldIndex + 1)))
                .setMessage(1, Component.literal(opened))
                .setMessage(2, Component.literal(souls + (souls == 1 ? " soul" : " souls")))
                .setHasGlowingText(true);
        if (closed.startsWith("†")) {
            text = text.setMessage(3, Component.literal(closed));
        }
        sign.setText(text, true);
        sign.setText(text, false);
        sign.setChanged();
    }

    /** The last dry plot closes the field's ledger: the dagger and the date. */
    private static void markFieldFull(ServerLevel level, int fieldIndex) {
        SignBlockEntity sign = findFieldSign(level, fieldIndex);
        if (sign == null) return;
        SignText updated = sign.getFrontText()
                .setMessage(3, Component.literal("† " + shortDate()))
                .setHasGlowingText(true);
        sign.setText(updated, true);
        sign.setText(updated, false);
        sign.setChanged();
        writeFieldSign(sign, fieldIndex);
    }

    /** The lych gate, centered on the south fence line, entrance facing out. */
    private static boolean placeGate(ServerLevel level, int fieldIndex,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager,
            String set) {
        java.util.List<String> gates = StudioMode.approvedTemplates("gate", manager, set);
        if (gates.isEmpty()) return false;
        String piece = gates.get(Math.floorMod(fieldIndex * 7919, gates.size()));
        var opt = manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, piece));
        if (opt.isEmpty()) return false;
        var template = opt.get();
        int w = StudioMode.widthOfCategory("gate");
        int d = StudioMode.CATEGORIES.stream().filter(cat -> cat.name().equals("gate"))
                .mapToInt(StudioMode.Category::d).findFirst().orElse(7);
        BlockPos c = fieldCenter(fieldIndex);
        int fenceZ = c.getZ() + FENCE_OFF;
        int x0 = c.getX() - w / 2, z0 = fenceZ - d / 2;

        int pad = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                int g = GraveyardTerrain.groundHeight(x, z);
                pad = Math.max(pad, g);
                low = Math.min(low, g);
            }
        }
        if (low < GraveyardTerrain.WATER_TOP) return false; // the gate never stands in water

        // The gate claims its ground too — no trunks through the arch.
        DecorScatter.clearClaimed(level, x0 - 1, z0 - 1, x0 + w, z0 + d);

        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState moss = Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                level.getChunk(x >> 4, z >> 4);
                for (int y = GraveyardTerrain.groundHeight(x, z); y < pad; y++) {
                    level.setBlock(new BlockPos(x, y, z), tuff, 2);
                }
                level.setBlock(new BlockPos(x, pad, z), moss, 2);
            }
        }
        int below = StudioMode.belowGradeOf(template, "gate");
        BlockPos at = new BlockPos(x0, pad + 1 - below, z0);
        template.placeInWorld(level, at, at, new StructurePlaceSettings(),
                RandomSource.create(fieldIndex * 131L), 2);
        return true;
    }

    /** The pale-oak default fence: posts and corner lanterns (gapHalf widens for the gate). */
    private static void basicFence(ServerLevel level, int fieldIndex, int gapHalf) {
        BlockPos c = fieldCenter(fieldIndex);
        BlockState fence = Blocks.PALE_OAK_FENCE.defaultBlockState();
        BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState();
        int f = FENCE_OFF;
        for (int x = c.getX() - f; x <= c.getX() + f; x++) {
            boolean southGate = Math.abs(x - c.getX()) <= gapHalf;
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
    }

    /**
     * Fence-kit tiling: corner pieces (BUILT AS THE NORTH-WEST CORNER) rotate
     * around the four turns; straight tiles (5 long, front facing the label =
     * outward) repeat along each run, the last tile overlapping to land flush.
     * Fence breaks at water, exactly like the pale-oak default.
     */
    private static void tiledFence(ServerLevel level, int fieldIndex,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager,
            java.util.List<String> straights, java.util.List<String> corners, boolean gate) {
        BlockPos c = fieldCenter(fieldIndex);
        int f = FENCE_OFF;
        int west = c.getX() - f, east = c.getX() + f, north = c.getZ() - f, south = c.getZ() + f;

        if (!corners.isEmpty()) {
            placeFenceCorner(level, manager, corners, fieldIndex, west, north, Rotation.NONE);
            placeFenceCorner(level, manager, corners, fieldIndex, east, north, Rotation.CLOCKWISE_90);
            placeFenceCorner(level, manager, corners, fieldIndex, east, south, Rotation.CLOCKWISE_180);
            placeFenceCorner(level, manager, corners, fieldIndex, west, south, Rotation.COUNTERCLOCKWISE_90);
        } else {
            BlockState fence = Blocks.PALE_OAK_FENCE.defaultBlockState();
            for (int[] cn : new int[][]{{west, north}, {east, north}, {east, south}, {west, south}}) {
                fencePost(level, fence, cn[0], cn[1]);
            }
        }

        // North and south runs (east-west): front faces outward.
        placeFenceRun(level, manager, straights, fieldIndex, true, north, west + 2, east - 2,
                Rotation.CLOCKWISE_180);
        if (gate) {
            placeFenceRun(level, manager, straights, fieldIndex, true, south, west + 2, c.getX() - 5,
                    Rotation.NONE);
            placeFenceRun(level, manager, straights, fieldIndex, true, south, c.getX() + 5, east - 2,
                    Rotation.NONE);
        } else {
            placeFenceRun(level, manager, straights, fieldIndex, true, south, west + 2, east - 2,
                    Rotation.NONE);
        }
        // West and east runs (north-south).
        placeFenceRun(level, manager, straights, fieldIndex, false, west, north + 2, south - 2,
                Rotation.CLOCKWISE_90);
        placeFenceRun(level, manager, straights, fieldIndex, false, east, north + 2, south - 2,
                Rotation.COUNTERCLOCKWISE_90);
    }

    private static void placeFenceCorner(ServerLevel level,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager,
            java.util.List<String> corners, int fieldIndex, int x, int z, Rotation rot) {
        String piece = corners.get(Math.floorMod(fieldIndex * 31 + x + z, corners.size()));
        var opt = manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, piece));
        if (opt.isEmpty()) return;
        int h = GraveyardTerrain.groundHeight(x, z);
        if (h < GraveyardTerrain.WATER_TOP) return;
        int below = StudioMode.belowGradeOf(opt.get(), "fence_corner");
        BlockPos at = new BlockPos(x - 1, h + 1 - below, z - 1);
        var settings = new StructurePlaceSettings().setRotation(rot)
                .setRotationPivot(new BlockPos(1, 0, 1));
        opt.get().placeInWorld(level, at, at, settings, RandomSource.create(x * 31L + z), 2);
    }

    /** One run of straight tiles from `from` to `to` (inclusive, along one axis). */
    private static void placeFenceRun(ServerLevel level,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager manager,
            java.util.List<String> straights, int fieldIndex, boolean eastWest, int line,
            int from, int to, Rotation rot) {
        if (to - from + 1 < 5) return;
        int t = from;
        while (t <= to - 4) {
            String piece = straights.get(Math.floorMod(fieldIndex * 17 + t, straights.size()));
            var opt = manager.get(Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, piece));
            if (opt.isPresent()) {
                int centerA = t + 2;
                int pad = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
                for (int a = t; a <= t + 4; a++) {
                    int g = eastWest ? GraveyardTerrain.groundHeight(a, line)
                                     : GraveyardTerrain.groundHeight(line, a);
                    pad = Math.max(pad, g);
                    low = Math.min(low, g);
                }
                if (low >= GraveyardTerrain.WATER_TOP) {
                    int below = StudioMode.belowGradeOf(opt.get(), "fence_straight");
                    BlockPos at = eastWest
                            ? new BlockPos(centerA - 2, pad + 1 - below, line - 1)
                            : new BlockPos(line - 2, pad + 1 - below, centerA - 1);
                    var settings = new StructurePlaceSettings().setRotation(rot)
                            .setRotationPivot(new BlockPos(2, 0, 1));
                    opt.get().placeInWorld(level, at, at, settings,
                            RandomSource.create(line * 31L + t), 2);
                }
            }
            if (t == to - 4) break;
            t = Math.min(t + 5, to - 4);
        }
    }

    private static void fencePost(ServerLevel level, BlockState fence, int x, int z) {
        int h = GraveyardTerrain.groundHeight(x, z);
        if (h < GraveyardTerrain.WATER_TOP) return; // the fence breaks at the water
        level.getChunk(x >> 4, z >> 4);
        level.setBlock(new BlockPos(x, h + 1, z), fence, 2);
    }

    /** Raise the plot to its terrace height: fill from the natural ground up
     *  (a retaining bed on slopes), moss on top, clear air above. */
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
                int ground = GraveyardTerrain.groundHeight(x, z);
                for (int y = Math.min(ground, h - 2); y < h; y++) {
                    level.setBlock(new BlockPos(x, y, z), tuff, 2);
                }
                level.setBlock(new BlockPos(x, h, z), moss, 2);
            }
        }
    }

    /** Terrain relief under a plot (plus margin) — decides which stone CLASS fits. */
    private static int plotRelief(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = o.getX() - 2; x < o.getX() + PLOT + 2; x++) {
            for (int z = o.getZ() - 2; z < o.getZ() + PLOT + 2; z++) {
                int h = GraveyardTerrain.groundHeight(x, z);
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }
        return max - min;
    }

    /**
     * Terrain picks the stone class: flat ground gets the full mix (60%
     * standard / 25% small / 15% large), moderate slopes drop the monuments,
     * rough spots take only small markers that hug the terrain.
     */
    private static String pickStoneClass(GraveManager.Grave grave, int relief) {
        int roll = Math.floorMod(grave.id.getLeastSignificantBits() * 31 + 7, 100);
        if (relief <= 1) {
            if (roll < 15) return "headstone_large";
            if (roll < 75) return "headstone";
            return "headstone_small";
        }
        if (relief <= 3) {
            return roll < 70 ? "headstone" : "headstone_small";
        }
        return "headstone_small";
    }

    private static final String[][] CLASS_FALLBACK = {
            {"headstone_large", "headstone", "headstone_small"},
            {"headstone", "headstone_small", "headstone_large"},
            {"headstone_small", "headstone", "headstone_large"},
    };

    private static String[] fallbackOrder(String cls) {
        for (String[] order : CLASS_FALLBACK) {
            if (order[0].equals(cls)) return order;
        }
        return CLASS_FALLBACK[1];
    }

    /**
     * The headstone: terrain chooses the size class, the grave id chooses the
     * variant (remembered forever). The stone centers in its 6×6 plot and only
     * ITS footprint (+1 ring) is terraced — small stones perch on rough
     * ground, monuments claim the flats.
     */
    static void placeHeadstone(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);

        var manager = level.getServer().getStructureManager();
        if (grave.stoneName.isEmpty()) {
            String set = StudioSets.setForRegion(o.getX(), o.getZ());
            String cls = pickStoneClass(grave, plotRelief(grave.plotIndex));
            for (String c : fallbackOrder(cls)) {
                var variants = StudioMode.approvedTemplates(c, manager, set);
                if (!variants.isEmpty()) {
                    grave.stoneName = variants.get(Math.floorMod(grave.id.hashCode(), variants.size()));
                    break;
                }
            }
        }
        if (!grave.stoneName.isEmpty()) {
            var template = manager.get(net.minecraft.resources.Identifier
                    .fromNamespaceAndPath(CharonsEcho.MOD_ID, grave.stoneName));
            if (template.isPresent()) {
                String cat = StudioMode.categoryOfTemplate(grave.stoneName);
                int w = StudioMode.widthOfCategory(cat);
                int off = (PLOT - w) / 2; // center the stone in its plot
                int sx = o.getX() + off, sz = o.getZ() + off;
                // Level only the stone's footprint + 1 ring, to ITS local max.
                int sy = areaMaxGround(sx - 1, sz - 1, w + 2);
                terraceArea(level, sx - 1, sz - 1, w + 2, sy);
                int below = StudioMode.belowGradeOf(template.get(), cat);
                BlockPos at = new BlockPos(sx, sy + 1 - below, sz);
                level.getChunk(sx >> 4, sz >> 4);
                // Stones are BUILT facing south (toward their studio label) but
                // are TURNED to face WEST on burial — the dead face the way
                // Charon carries them, beyond the setting sun.
                var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                        .setRotation(net.minecraft.world.level.block.Rotation.CLOCKWISE_90)
                        .setRotationPivot(new BlockPos(w / 2, 0, w / 2));
                template.get().placeInWorld(level, at, at, settings,
                        net.minecraft.util.RandomSource.create(grave.id.hashCode()), 3);
                writeEpitaphOnAnySign(level, grave, o, sy);
                return;
            }
        }
        terracePlot(level, grave.plotIndex);
        placePlaceholderStone(level, grave, o, plotSurfaceY(grave.plotIndex));
    }

    /** Highest natural column in a square area. */
    private static int areaMaxGround(int x0, int z0, int size) {
        int max = Integer.MIN_VALUE;
        for (int x = x0; x < x0 + size; x++) {
            for (int z = z0; z < z0 + size; z++) {
                max = Math.max(max, GraveyardTerrain.groundHeight(x, z));
            }
        }
        return max;
    }

    /** Fill-up terrace of an arbitrary square to height h (never digs). */
    private static void terraceArea(ServerLevel level, int x0, int z0, int size, int h) {
        BlockState moss = Blocks.PALE_MOSS_BLOCK.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = x0; x < x0 + size; x++) {
            for (int z = z0; z < z0 + size; z++) {
                level.getChunk(x >> 4, z >> 4);
                for (int y = h + 1; y <= h + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), air, 2);
                }
                int ground = GraveyardTerrain.groundHeight(x, z);
                for (int y = Math.min(ground, h - 2); y < h; y++) {
                    level.setBlock(new BlockPos(x, y, z), tuff, 2);
                }
                level.setBlock(new BlockPos(x, h, z), moss, 2);
            }
        }
    }

    /** The template's sign (wherever the builder put it) gets the epitaph. */
    private static void writeEpitaphOnAnySign(ServerLevel level, GraveManager.Grave grave, BlockPos o, int y) {
        int depth = StudioMode.depthOfCategory("headstone");
        for (int x = o.getX(); x < o.getX() + PLOT; x++) {
            for (int z = o.getZ(); z < o.getZ() + PLOT; z++) {
                // Full template span: below-grade coffin depth up to build height.
                for (int dy = 1 - depth; dy <= 5; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                        sign.setText(epitaphText(grave), true);
                        sign.setChanged();
                        var state = level.getBlockState(pos);
                        level.sendBlockUpdated(pos, state, state, 3);
                        return;
                    }
                }
            }
        }
        System.out.println("[CharonsEcho] no sign found in headstone '" + grave.stoneName
                + "' at plot " + grave.plotIndex + " — epitaph not written");
    }

    /** Generated fallback headstone: mound, stone, and the epitaph sign —
     *  facing WEST, the way of the dead. */
    private static void placePlaceholderStone(ServerLevel level, GraveManager.Grave grave, BlockPos o, int y) {
        int sx = o.getX() + 4, sz = o.getZ() + 2; // stone near plot's east edge

        level.getChunk(sx >> 4, sz >> 4);
        // Grave mound (coarse-textured): 1×2 of tuff stretching west.
        level.setBlock(new BlockPos(sx - 1, y, sz), Blocks.TUFF.defaultBlockState(), 2);
        level.setBlock(new BlockPos(sx - 2, y, sz), Blocks.TUFF.defaultBlockState(), 2);
        // The stone: chiseled deepslate with a slab cap.
        level.setBlock(new BlockPos(sx, y + 1, sz), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
        level.setBlock(new BlockPos(sx, y + 2, sz),
                Blocks.DEEPSLATE_BRICK_SLAB.defaultBlockState(), 2);
        // Epitaph sign on the west face.
        BlockPos signPos = new BlockPos(sx - 1, y + 1, sz);
        level.setBlock(signPos, Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST), 2);
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
            placeHeadstone(graveyard, g); // terraces its own footprint
            count++;
        }
        GraveManager.save();
        return count;
    }

    /** A reclaimed grave's sign glows softly — the soul is at rest. */
    public static void markAtRest(ServerLevel level, GraveManager.Grave grave) {
        BlockPos o = plotOrigin(grave.plotIndex);
        int y = plotSurfaceY(grave.plotIndex);
        int depth = StudioMode.depthOfCategory("headstone");
        // The sign lives wherever the builder put it — scan the whole stone.
        for (int x = o.getX(); x < o.getX() + PLOT; x++) {
            for (int z = o.getZ(); z < o.getZ() + PLOT; z++) {
                for (int dy = 1 - depth; dy <= 5; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                        sign.setText(sign.getFrontText().setHasGlowingText(true), true);
                        sign.setChanged();
                        var state = level.getBlockState(pos);
                        level.sendBlockUpdated(pos, state, state, 3);
                        return;
                    }
                }
            }
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

    /** Where a ghost arrives / stands to mourn: WEST of the headstone,
     *  looking back east at their own epitaph. */
    public static BlockPos arrivalPos(int plotIndex) {
        BlockPos o = plotOrigin(plotIndex);
        return new BlockPos(o.getX() + 1, plotSurfaceY(plotIndex) + 1, o.getZ() + 2);
    }
}
