package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The crypt — PLACEHOLDER architecture, carved in code beneath the church
 * from the lodestone marker: a stairwell descending below the rivers, the
 * WEEK ROOM with seven day-shelves (a rolling week of the dead — click a
 * shelf, read that day's ledger, open their books), and the sealed north
 * arch where the year's halls will one day open.
 *
 * The builder's crypt kit (crypt_stairwell / crypt_week / crypt_room /
 * crypt_corridor / crypt_seal) replaces this carving when it exists —
 * the shelves and their meaning stay.
 */
public final class Crypt {

    private static final int FLOOR_Y = 38; // below the rivers, as the dead prefer

    private static Path file;
    private static boolean built;
    private static final BlockPos[] SHELVES = new BlockPos[7];
    /** The spine: where the north corridor leaves the week room. */
    private static int spineX = Integer.MIN_VALUE;
    private static int spineZ;
    /** Month halls carved so far: "yyyy-MM" -> that hall's ledger shelf. */
    private static final java.util.Map<String, BlockPos> MONTHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Crypt() {}

    public static void register() {
        // The shelves keep their own census: refreshed every minute (which
        // also rolls the week over at midnight) and on every burial.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (built && server.getTickCount() % 1200 == 0) {
                refreshShelves(server);
            }
        });
        // Day-shelves: the ONE interactable in the crypt. Server decides;
        // client forwards (the house rule). SUCCESS also stops vanilla
        // chiseled-bookshelf slot fiddling.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM || !built) {
                return InteractionResult.PASS;
            }
            int idx = shelfIndex(hit.getBlockPos());
            String month = idx < 0 ? monthOfShelf(hit.getBlockPos()) : null;
            if (idx < 0 && month == null) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            if (idx >= 0) {
                openDay(sp, idx);
            } else {
                openMonth(sp, month);
            }
            return InteractionResult.SUCCESS;
        });
    }

    private static String monthOfShelf(BlockPos pos) {
        for (var e : MONTHS.entrySet()) {
            if (e.getValue().equals(pos)) return e.getKey();
        }
        return null;
    }

    private static int shelfIndex(BlockPos pos) {
        for (int i = 0; i < 7; i++) {
            if (SHELVES[i] != null && SHELVES[i].equals(pos)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- carving

    public static void ensure(MinecraftServer server) {
        if (built) return;
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        BlockPos marker = Church.cryptMarker();
        if (graveyard == null || marker == null) return;

        BlockState wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        BlockState floor = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int cx = marker.getX();

        // ---- the stairwell: north and down, one step per block ----
        int y = marker.getY() - 1; // first step below the church floor
        int z = marker.getZ();
        while (y > FLOOR_Y) {
            z--; // north
            y--;
            for (int dx = -1; dx <= 1; dx++) {
                graveyard.getChunk((cx + dx) >> 4, z >> 4);
                // step + headroom
                graveyard.setBlock(new BlockPos(cx + dx, y, z),
                        Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.state.properties
                                        .BlockStateProperties.HORIZONTAL_FACING,
                                        net.minecraft.core.Direction.SOUTH), 2);
                graveyard.setBlock(new BlockPos(cx + dx, y - 1, z), wall, 2);
                for (int dy = 1; dy <= 4; dy++) {
                    graveyard.setBlock(new BlockPos(cx + dx, y + dy, z), air, 2);
                }
            }
            // side walls
            graveyard.setBlock(new BlockPos(cx - 2, y, z), wall, 2);
            graveyard.setBlock(new BlockPos(cx + 2, y, z), wall, 2);
            for (int dy = 1; dy <= 4; dy++) {
                graveyard.setBlock(new BlockPos(cx - 2, y + dy, z), wall, 2);
                graveyard.setBlock(new BlockPos(cx + 2, y + dy, z), wall, 2);
            }
            if ((z & 3) == 0) {
                graveyard.setBlock(new BlockPos(cx - 2, y + 2, z),
                        Blocks.SOUL_LANTERN.defaultBlockState(), 2);
            }
        }

        // ---- the week room: 15x15, seven shelves on the north wall ----
        int rz = z - 8; // room center, further north
        int x0 = cx - 7, x1 = cx + 7, z0 = rz - 7, z1 = rz + 7;
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int zz = z0 - 1; zz <= z1 + 1; zz++) {
                graveyard.getChunk(x >> 4, zz >> 4);
                for (int yy = FLOOR_Y - 1; yy <= FLOOR_Y + 6; yy++) {
                    boolean shell = x < x0 || x > x1 || zz < z0 || zz > z1
                            || yy == FLOOR_Y - 1 || yy == FLOOR_Y + 6;
                    graveyard.setBlock(new BlockPos(x, yy, zz),
                            shell ? (yy == FLOOR_Y - 1 ? floor : wall) : air, 2);
                }
            }
        }
        // doorway from the stairwell (south wall center)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + dy, z1 + 1), air, 2);
            }
        }
        // connecting passage stairwell-end -> room
        for (int zz = z1 + 1; zz <= z; zz++) {
            for (int dx = -1; dx <= 1; dx++) {
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y - 1, zz), floor, 2);
                for (int dy = 0; dy <= 3; dy++) {
                    graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + dy, zz), air, 2);
                }
            }
        }
        // lanterns in the corners
        for (int[] c : new int[][]{{x0 + 1, z0 + 1}, {x1 - 1, z0 + 1}, {x0 + 1, z1 - 1}, {x1 - 1, z1 - 1}}) {
            graveyard.setBlock(new BlockPos(c[0], FLOOR_Y + 3, c[1]),
                    Blocks.SOUL_LANTERN.defaultBlockState(), 2);
        }
        // the seven day-shelves, west to east along the north wall
        for (int i = 0; i < 7; i++) {
            BlockPos shelf = new BlockPos(cx - 6 + i * 2, FLOOR_Y + 1, z0);
            graveyard.setBlock(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.HORIZONTAL_FACING,
                            net.minecraft.core.Direction.SOUTH), 2);
            SHELVES[i] = shelf.immutable();
        }

        // ---- the sealed north spine: the year's halls, not yet open ----
        for (int zz = z0 - 1; zz >= z0 - 6; zz--) {
            for (int dx = -1; dx <= 1; dx++) {
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y - 1, zz), floor, 2);
                for (int dy = 0; dy <= 3; dy++) {
                    graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + dy, zz),
                            zz == z0 - 6 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : air, 2);
                }
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + 4, zz), wall, 2);
            }
            graveyard.setBlock(new BlockPos(cx - 2, FLOOR_Y + 1, zz), wall, 2);
            graveyard.setBlock(new BlockPos(cx - 2, FLOOR_Y + 2, zz), wall, 2);
            graveyard.setBlock(new BlockPos(cx - 2, FLOOR_Y, zz), wall, 2);
            graveyard.setBlock(new BlockPos(cx + 2, FLOOR_Y, zz), wall, 2);
            graveyard.setBlock(new BlockPos(cx + 2, FLOOR_Y + 1, zz), wall, 2);
            graveyard.setBlock(new BlockPos(cx + 2, FLOOR_Y + 2, zz), wall, 2);
        }
        sealSign(graveyard, new BlockPos(cx, FLOOR_Y, z0 - 5));

        spineX = cx;
        spineZ = z0;
        built = true;
        save();
        refreshShelves(server);
        System.out.println("[CharonsEcho] the crypt is carved: week room at "
                + cx + "," + FLOOR_Y + "," + rz + " with 7 day-shelves");
    }

    /**
     * The shelves FILL as players die: one visible book per soul fallen on
     * that shelf's day, up to the six slots a shelf can hold. Refreshed on
     * every burial and every minute (the week rolls over at midnight).
     */
    private static void sealSign(ServerLevel graveyard, BlockPos signPos) {
        graveyard.setBlock(signPos, Blocks.PALE_OAK_SIGN.defaultBlockState(), 2);
        if (graveyard.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(1, Component.literal("The year's halls"))
                    .setMessage(2, Component.literal("lie sealed beyond."))
                    .setHasGlowingText(true);
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setChanged();
        }
    }

    // ---------------------------------------------------------------- month halls

    /**
     * THE CRYPT GROWS WITH THE DEAD: the first death of any month breaks the
     * seal, extends the spine north, and carves that month's hall — rooms
     * alternating east and west, each with its month sign and a shelf that
     * opens the whole month's ledger. The seal retreats ahead of history.
     */
    private static void ensureMonthRooms(MinecraftServer server) {
        if (!built || spineX == Integer.MIN_VALUE) return;
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        java.util.TreeSet<String> yms = new java.util.TreeSet<>();
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.epochMillis <= 0) continue;
            yms.add(Instant.ofEpochMilli(g.epochMillis).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM")));
        }
        for (String ym : yms) {
            if (!MONTHS.containsKey(ym)) {
                carveMonthHall(graveyard, ym, MONTHS.size());
                save();
            }
        }
        // Idempotent: existing halls get their corridor sign (and lose the
        // old inside one) — signs belong OUTSIDE the door.
        MONTHS.forEach((ym, shelf) -> hallSign(graveyard, ym, shelf));
    }

    /**
     * The hall's nameplate: a WALL SIGN in the spine corridor beside the
     * doorway — read the month before you enter, like a hallway of years.
     */
    private static void hallSign(ServerLevel graveyard, String ym, BlockPos shelf) {
        int side = shelf.getX() > spineX ? 1 : -1;
        int zRoom = shelf.getZ();
        // Sweep the legacy inside sign if one stands where the old carve put it.
        BlockPos oldSign = new BlockPos(spineX + side * 4, FLOOR_Y, zRoom);
        if (graveyard.getBlockState(oldSign).is(Blocks.PALE_OAK_SIGN)) {
            graveyard.setBlock(oldSign, Blocks.AIR.defaultBlockState(), 2);
        }
        BlockPos signPos = new BlockPos(spineX + side, FLOOR_Y + 2, zRoom + 2);
        if (!graveyard.getBlockState(signPos).is(Blocks.PALE_OAK_WALL_SIGN)) {
            graveyard.setBlock(signPos, Blocks.PALE_OAK_WALL_SIGN.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.HORIZONTAL_FACING,
                            side > 0 ? net.minecraft.core.Direction.WEST
                                     : net.minecraft.core.Direction.EAST), 2);
        }
        if (graveyard.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            String label = LocalDate.parse(ym + "-01")
                    .format(DateTimeFormatter.ofPattern("MMMM yyyy"));
            SignText text = new SignText()
                    .setMessage(1, Component.literal("The Hall of"))
                    .setMessage(2, Component.literal(label))
                    .setHasGlowingText(true);
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setChanged();
        }
    }

    private static void carveMonthHall(ServerLevel graveyard, String ym, int m) {
        BlockState wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        BlockState floor = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int cx = spineX;
        int side = (m % 2 == 0) ? 1 : -1;        // east first, then west
        int segment = m / 2;
        int zRoom = spineZ - 12 - segment * 12;  // this hall's spine junction

        // Extend the spine down past this hall (overwrites any old seal).
        for (int zz = spineZ - 1; zz >= zRoom - 5; zz--) {
            for (int dx = -2; dx <= 2; dx++) {
                graveyard.getChunk((cx + dx) >> 4, zz >> 4);
                boolean sideWall = dx == -2 || dx == 2;
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y - 1, zz), sideWall ? wall : floor, 2);
                for (int dy = 0; dy <= 3; dy++) {
                    graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + dy, zz),
                            sideWall ? wall : air, 2);
                }
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + 4, zz), wall, 2);
            }
            if ((zz & 3) == 0) {
                graveyard.setBlock(new BlockPos(cx - 1, FLOOR_Y + 3, zz),
                        Blocks.SOUL_LANTERN.defaultBlockState(), 2);
            }
        }
        // The seal, rebuilt at the new north end.
        int sealZ = zRoom - 6;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                graveyard.setBlock(new BlockPos(cx + dx, FLOOR_Y + dy, sealZ),
                        Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), 2);
            }
        }
        sealSign(graveyard, new BlockPos(cx, FLOOR_Y, sealZ + 1));

        // The hall itself: 11x11 interior off the spine.
        int rx0 = side > 0 ? cx + 3 : cx - 13;
        int rx1 = side > 0 ? cx + 13 : cx - 3;
        int rz0 = zRoom - 5, rz1 = zRoom + 5;
        for (int x = rx0 - 1; x <= rx1 + 1; x++) {
            for (int zz = rz0 - 1; zz <= rz1 + 1; zz++) {
                graveyard.getChunk(x >> 4, zz >> 4);
                for (int yy = FLOOR_Y - 1; yy <= FLOOR_Y + 5; yy++) {
                    boolean shell = x < rx0 || x > rx1 || zz < rz0 || zz > rz1
                            || yy == FLOOR_Y - 1 || yy == FLOOR_Y + 5;
                    graveyard.setBlock(new BlockPos(x, yy, zz),
                            shell ? (yy == FLOOR_Y - 1 ? floor : wall) : air, 2);
                }
            }
        }
        // Doorway from the spine.
        int doorX = side > 0 ? cx + 2 : cx - 2;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                graveyard.setBlock(new BlockPos(doorX, FLOOR_Y + dy, zRoom + dz), air, 2);
            }
        }
        // Lanterns, the month sign, and the hall's ledger shelf.
        for (int[] c : new int[][]{{rx0 + 1, rz0 + 1}, {rx1 - 1, rz0 + 1},
                {rx0 + 1, rz1 - 1}, {rx1 - 1, rz1 - 1}}) {
            graveyard.setBlock(new BlockPos(c[0], FLOOR_Y + 3, c[1]),
                    Blocks.SOUL_LANTERN.defaultBlockState(), 2);
        }
        BlockPos shelf = new BlockPos(side > 0 ? rx1 : rx0, FLOOR_Y + 1, zRoom);
        hallSign(graveyard, ym, shelf);
        graveyard.setBlock(shelf, Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.HORIZONTAL_FACING,
                        side > 0 ? net.minecraft.core.Direction.WEST
                                 : net.minecraft.core.Direction.EAST), 2);
        MONTHS.put(ym, shelf.immutable());
        System.out.println("[CharonsEcho] the crypt grows: the hall of " + ym + " opens");
    }

    /** The hall's shelf: the whole month's ledger, paged and searchable. */
    private static void openMonth(ServerPlayer player, String ym) {
        String label = LocalDate.parse(ym + "-01")
                .format(DateTimeFormatter.ofPattern("MMMM yyyy"));
        var fallen = new java.util.ArrayList<GraveManager.Grave>();
        for (GraveManager.Grave grave : GraveManager.all()) {
            if (grave.epochMillis <= 0) continue;
            String gm = Instant.ofEpochMilli(grave.epochMillis).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            if (!gm.equals(ym)) continue;
            fallen.add(grave);
        }
        fallen.sort((a, b) -> Long.compare(b.gameTime, a.gameTime));
        GraveUi.openList(player, "The Hall of " + label, fallen, null,
                "The hall waits.", 0, null);
    }

    public static void refreshShelves(MinecraftServer server) {
        if (!built) return;
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        ensureMonthRooms(server);
        var slotProps = java.util.List.of(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_0_OCCUPIED,
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_1_OCCUPIED,
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_2_OCCUPIED,
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_3_OCCUPIED,
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_4_OCCUPIED,
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLOT_5_OCCUPIED);
        // ONE walk of the graves buckets every shelf at once — the old way
        // walked the whole roll per day-shelf and again per month hall,
        // which is a heavy habit on a server with years of dead.
        LocalDate today = LocalDate.now();
        var dayCounts = new java.util.HashMap<LocalDate, Integer>();
        var monthCounts = new java.util.HashMap<String, Integer>();
        for (GraveManager.Grave g : GraveManager.all()) {
            if (g.epochMillis <= 0) continue;
            LocalDate day = Instant.ofEpochMilli(g.epochMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            dayCounts.merge(day, 1, Integer::sum);
            monthCounts.merge(day.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    1, Integer::sum);
        }
        for (int i = 0; i < 7; i++) {
            if (SHELVES[i] == null) continue;
            graveyard.getChunk(SHELVES[i].getX() >> 4, SHELVES[i].getZ() >> 4);
            BlockState state = graveyard.getBlockState(SHELVES[i]);
            if (!state.is(Blocks.CHISELED_BOOKSHELF)) continue;
            int fallen = dayCounts.getOrDefault(today.minusDays(6 - i), 0);
            for (int s = 0; s < 6; s++) {
                state = state.setValue(slotProps.get(s), s < Math.min(fallen, 6));
            }
            graveyard.setBlock(SHELVES[i], state, 3);
        }
        // The month halls' shelves fill the same way — a month's weight in books.
        MONTHS.forEach((ym, shelfPos) -> {
            graveyard.getChunk(shelfPos.getX() >> 4, shelfPos.getZ() >> 4);
            BlockState state = graveyard.getBlockState(shelfPos);
            if (!state.is(Blocks.CHISELED_BOOKSHELF)) return;
            int fallen = monthCounts.getOrDefault(ym, 0);
            for (int s = 0; s < 6; s++) {
                state = state.setValue(slotProps.get(s), s < Math.min(fallen, 6));
            }
            graveyard.setBlock(shelfPos, state, 3);
        });
    }

    // ---------------------------------------------------------------- the shelves

    /** Shelf i (west..east) holds the dead of (6-i) days ago; east is today. */
    private static void openDay(ServerPlayer player, int idx) {
        LocalDate day = LocalDate.now().minusDays(6 - idx);
        String label = day.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        var fallen = new java.util.ArrayList<GraveManager.Grave>();
        for (GraveManager.Grave grave : GraveManager.all()) {
            if (grave.epochMillis <= 0) continue;
            LocalDate graveDay = Instant.ofEpochMilli(grave.epochMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (!graveDay.equals(day)) continue;
            fallen.add(grave);
        }
        fallen.sort((a, b) -> Long.compare(b.gameTime, a.gameTime));
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 0.8f, 0.7f);
        GraveUi.openList(player, "Fallen " + label, fallen, null,
                "No one fell this day.", 0, null);
    }

    // ---------------------------------------------------------------- persistence

    public static void load(MinecraftServer server) {
        built = false;
        spineX = Integer.MIN_VALUE;
        MONTHS.clear();
        for (int i = 0; i < 7; i++) SHELVES[i] = null;
        file = server.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("crypt.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            built = root.getBooleanOr("built", false);
            long[] shelves = root.getLongArray("shelves").orElse(new long[0]);
            for (int i = 0; i < Math.min(7, shelves.length); i++) {
                SHELVES[i] = BlockPos.of(shelves[i]);
            }
            spineX = root.getIntOr("spineX", Integer.MIN_VALUE);
            spineZ = root.getIntOr("spineZ", 0);
            for (net.minecraft.nbt.Tag t : root.getListOrEmpty("months")) {
                if (t instanceof CompoundTag c) {
                    MONTHS.put(c.getStringOr("ym", "?"), BlockPos.of(c.getLongOr("shelf", 0)));
                }
            }
            // Migration: crypts carved before the halls existed derive their
            // spine from the center day-shelf (it sits ON the spine axis).
            if (built && spineX == Integer.MIN_VALUE && SHELVES[3] != null) {
                spineX = SHELVES[3].getX();
                spineZ = SHELVES[3].getZ();
                save();
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load crypt.dat: " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.putBoolean("built", built);
            long[] shelves = new long[7];
            for (int i = 0; i < 7; i++) {
                shelves[i] = SHELVES[i] == null ? 0 : SHELVES[i].asLong();
            }
            root.putLongArray("shelves", shelves);
            root.putInt("spineX", spineX);
            root.putInt("spineZ", spineZ);
            net.minecraft.nbt.ListTag months = new net.minecraft.nbt.ListTag();
            MONTHS.forEach((ym, shelf) -> {
                CompoundTag c = new CompoundTag();
                c.putString("ym", ym);
                c.putLong("shelf", shelf.asLong());
                months.add(c);
            });
            root.put("months", months);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save crypt.dat: " + e);
        }
    }
}
