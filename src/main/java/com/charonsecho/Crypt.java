package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
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

    private Crypt() {}

    public static void register() {
        // Day-shelves: the ONE interactable in the crypt. Server decides;
        // client forwards (the house rule). SUCCESS also stops vanilla
        // chiseled-bookshelf slot fiddling.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM || !built) {
                return InteractionResult.PASS;
            }
            int idx = shelfIndex(hit.getBlockPos());
            if (idx < 0) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            openDay(sp, idx);
            return InteractionResult.SUCCESS;
        });
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
        BlockPos signPos = new BlockPos(cx, FLOOR_Y + 1, z0 - 5);
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

        built = true;
        save();
        System.out.println("[CharonsEcho] the crypt is carved: week room at "
                + cx + "," + FLOOR_Y + "," + rz + " with 7 day-shelves");
    }

    // ---------------------------------------------------------------- the shelves

    /** Shelf i (west..east) holds the dead of (6-i) days ago; east is today. */
    private static void openDay(ServerPlayer player, int idx) {
        LocalDate day = LocalDate.now().minusDays(6 - idx);
        String label = day.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Fallen " + label));
        int slot = 0;
        for (GraveManager.Grave grave : GraveManager.all()) {
            if (grave.epochMillis <= 0 || slot >= 27) continue;
            LocalDate graveDay = Instant.ofEpochMilli(grave.epochMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (!graveDay.equals(day)) continue;
            final GraveManager.Grave g = grave;
            GuiElementBuilder entry = new GuiElementBuilder(
                    g.book != null ? Items.WRITTEN_BOOK : Items.SKELETON_SKULL)
                    .setName(Component.literal(g.ownerName).withStyle(ChatFormatting.WHITE))
                    .addLoreLine(Component.literal(g.causeLine == null ? "" : g.causeLine)
                            .withStyle(ChatFormatting.GRAY))
                    .addLoreLine(Component.literal(g.claimed ? "at rest" : "unclaimed")
                            .withStyle(g.claimed ? ChatFormatting.DARK_GRAY : ChatFormatting.DARK_PURPLE));
            if (g.book != null) {
                entry.glow().addLoreLine(Component.literal("Their story — click to read.")
                        .withStyle(ChatFormatting.DARK_AQUA))
                        .setCallback((i, t, a, gg) -> GraveBooks.open(player, g));
            }
            gui.setSlot(slot++, entry);
        }
        if (slot == 0) {
            gui.setSlot(13, new GuiElementBuilder(Items.CANDLE)
                    .setName(Component.literal("No one fell this day.")
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 0.8f, 0.7f);
        gui.open();
    }

    // ---------------------------------------------------------------- persistence

    public static void load(MinecraftServer server) {
        built = false;
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
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save crypt.dat: " + e);
        }
    }
}
