package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * SOUL GATES — the living raise doors to the world of the dead.
 *
 * Build a closed frame of GILDED BLACKSTONE in a vertical plane — ANY
 * shape: an arch, a ring, a crooked door. Diagonal joints seal (souls do
 * not slip through corners). Touch the frame with a Charon's Obol: the
 * coin is spent, and the aperture draws breath — the same SOUL breath
 * the ghosts wear. Walk through: gates in the living world carry you to
 * the graveyard's arrival; gates in Charon's Echo carry you back through
 * the gate you last used (or to the world spawn). Break the frame and
 * the door closes.
 *
 * Config: soul-gates (0 off / 1 gamemasters / 2 anyone, default 2);
 * soul-gate-min-area and soul-gate-max-area bound the aperture size.
 */
public final class SoulGates {

    /** cells = interior air cells of the aperture; lateralX = the frame
     *  spans the X axis (crossing travels along Z). */
    record Gate(ResourceKey<Level> dim, List<BlockPos> cells, boolean lateralX) {

        BlockPos anchor() { return cells.get(0); }

        AABB bounds() {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos c : cells) {
                minX = Math.min(minX, c.getX()); maxX = Math.max(maxX, c.getX());
                minY = Math.min(minY, c.getY()); maxY = Math.max(maxY, c.getY());
                minZ = Math.min(minZ, c.getZ()); maxZ = Math.max(maxZ, c.getZ());
            }
            double thin = 0.75;
            return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)
                    .inflate(lateralX ? 0 : thin, 0, lateralX ? thin : 0);
        }

        double centerX() { return bounds().getCenter().x; }
        double centerY() { return bounds().getCenter().y; }
        double centerZ() { return bounds().getCenter().z; }
    }

    /** A remembered crossing: the gate, and WHICH SIDE the walker entered
     *  from (sign along the plane normal) — the return drops them on that
     *  side, facing away, mid-stride. */
    private record Crossing(Gate gate, int sign) {}

    private static final List<Gate> GATES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Crossing> LAST_GATE = new ConcurrentHashMap<>();
    /** Like the death portals: a gate that carried you stays DISARMED until
     *  you are wholly clear of every aperture — no ping-pong, ever. */
    private static final Set<UUID> DISARMED = ConcurrentHashMap.newKeySet();
    private static Path file;

    private SoulGates() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            if (!world.getBlockState(hit.getBlockPos()).is(Blocks.GILDED_BLACKSTONE)) {
                return InteractionResult.PASS;
            }
            if (!CharonObol.isObol(player.getItemInHand(hand))) return InteractionResult.PASS;
            return consecrate(sp, level, hit.getBlockPos(), hand);
        });
        ServerTickEvents.END_SERVER_TICK.register(SoulGates::tick);
    }

    // ------------------------------------------------------------ consecration

    private static InteractionResult consecrate(ServerPlayer player, ServerLevel level,
            BlockPos clicked, net.minecraft.world.InteractionHand hand) {
        if (CharonConfig.soulGates == 0) return InteractionResult.PASS;
        if (CharonConfig.soulGates == 1 && !player.permissions()
                .hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            player.sendSystemMessage(Component.literal(
                    "Only the Ferryman's masters may raise gates here.")
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
        Gate gate = detect(level, clicked);
        if (gate == null) {
            player.sendSystemMessage(Component.literal(
                    "The frame is not whole. Close the door of gilded blackstone —"
                    + " an opening of " + CharonConfig.soulGateMinArea + " to "
                    + CharonConfig.soulGateMaxArea + " blocks, sealed all around.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        for (Gate g : GATES) {
            if (g.dim() == level.dimension() && g.cells().contains(gate.anchor())) {
                player.sendSystemMessage(Component.literal("This gate already breathes.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
                return InteractionResult.FAIL;
            }
        }
        if (!player.getAbilities().instabuild) {
            player.getItemInHand(hand).shrink(1);
        }
        GATES.add(gate);
        save();
        level.sendParticles(ParticleTypes.SOUL, gate.centerX(), gate.centerY(), gate.centerZ(),
                40, gate.bounds().getXsize() / 2, gate.bounds().getYsize() / 2,
                gate.bounds().getZsize() / 2, 0.04);
        level.playSound(null, gate.anchor(), SoundEvents.SOUL_ESCAPE.value(),
                SoundSource.AMBIENT, 1.0f, 0.7f);
        player.sendSystemMessage(Component.literal(
                "The coin is spent. The gate draws breath.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS;
    }

    /**
     * ANY closed shape: flood-fill the air beside the clicked frame block,
     * 4-connected within the vertical plane. Diagonal frame joints seal —
     * air does not pass through corners. The fill must close entirely
     * against gilded blackstone within the configured size.
     */
    private static Gate detect(ServerLevel level, BlockPos clicked) {
        for (boolean lateralX : new boolean[] { true, false }) {
            for (BlockPos seed : new BlockPos[] {
                    off(clicked, 1, lateralX), off(clicked, -1, lateralX),
                    clicked.above(), clicked.below() }) {
                if (!isAir(level, seed)) continue;
                List<BlockPos> cells = fill(level, seed, lateralX);
                if (cells != null) return new Gate(level.dimension(), cells, lateralX);
            }
        }
        return null;
    }

    private static List<BlockPos> fill(ServerLevel level, BlockPos seed, boolean lateralX) {
        int max = CharonConfig.soulGateMaxArea;
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> cells = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        seen.add(seed.immutable());
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            if (!isAir(level, p)) {
                if (isFrame(level, p)) continue; // boundary — sealed here
                return null; // dirt, leaves, anything else: the frame is not whole
            }
            cells.add(p);
            if (cells.size() > max) return null; // the shape never closed
            for (BlockPos n : new BlockPos[] {
                    off(p, 1, lateralX), off(p, -1, lateralX), p.above(), p.below() }) {
                if (seen.add(n.immutable())) queue.add(n.immutable());
            }
        }
        if (cells.size() < CharonConfig.soulGateMinArea) return null;
        return cells;
    }

    private static BlockPos off(BlockPos pos, int l, boolean lateralX) {
        return lateralX ? pos.offset(l, 0, 0) : pos.offset(0, 0, l);
    }

    private static boolean isFrame(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.GILDED_BLACKSTONE);
    }

    private static boolean isAir(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    // ------------------------------------------------------------ the breath

    private static void tick(MinecraftServer server) {
        if (GATES.isEmpty()) return;
        long time = server.getTickCount();
        if (time % 2 != 0) return;
        for (Gate gate : GATES) {
            ServerLevel level = server.getLevel(gate.dim());
            if (level == null || !level.isLoaded(gate.anchor())) continue;

            // The frame keeps its shape or the door closes.
            if (time % 100 == 0 && !frameIntact(level, gate)) {
                GATES.remove(gate);
                save();
                level.sendParticles(ParticleTypes.SOUL, gate.centerX(), gate.centerY(),
                        gate.centerZ(), 30, 0.8, 1.0, 0.8, 0.05);
                level.playSound(null, gate.anchor(), SoundEvents.SOUL_ESCAPE.value(),
                        SoundSource.AMBIENT, 0.8f, 0.5f);
                continue;
            }

            // The breath drifts from the aperture itself, whatever its shape.
            RandomSource rand = level.getRandom();
            for (int i = 0; i < 3; i++) {
                BlockPos cell = gate.cells().get(rand.nextInt(gate.cells().size()));
                level.sendParticles(ParticleTypes.SOUL,
                        cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                        1, 0.3, 0.3, 0.3, 0.012);
            }
            if (time % 20 == 0) {
                BlockPos low = gate.cells().get(0);
                for (BlockPos c : gate.cells()) {
                    if (c.getY() < low.getY()) low = c;
                }
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        gate.centerX(), low.getY() + 0.15, gate.centerZ(), 2,
                        gate.lateralX() ? gate.bounds().getXsize() / 2.4 : 0.1, 0.05,
                        gate.lateralX() ? 0.1 : gate.bounds().getZsize() / 2.4, 0.004);
            }

            for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, gate.bounds())) {
                if (GhostState.isGhost(p.getUUID())) continue;
                if (!inAperture(gate, p)) continue;
                if (DISARMED.contains(p.getUUID())) continue;
                DISARMED.add(p.getUUID());
                cross(server, level, p, gate);
            }
        }

        // Re-arm the walkers who have stepped wholly clear of every gate.
        if (time % 10 == 0 && !DISARMED.isEmpty()) {
            for (UUID id : DISARMED) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null || !inAnyAperture(p)) {
                    DISARMED.remove(id);
                }
            }
        }
    }

    private static boolean inAnyAperture(ServerPlayer p) {
        for (Gate gate : GATES) {
            if (gate.dim() != p.level().dimension()) continue;
            if (inAperture(gate, p)) return true;
        }
        return false;
    }

    /** The bounding box overshoots odd shapes — confirm a real cell. */
    private static boolean inAperture(Gate gate, ServerPlayer p) {
        BlockPos feet = p.blockPosition();
        return gate.cells().contains(feet) || gate.cells().contains(feet.above());
    }

    private static boolean frameIntact(ServerLevel level, Gate gate) {
        Set<BlockPos> cellSet = new HashSet<>(gate.cells());
        for (BlockPos c : gate.cells()) {
            if (!isAir(level, c)) return false; // something filled the door
            for (BlockPos n : new BlockPos[] {
                    off(c, 1, gate.lateralX()), off(c, -1, gate.lateralX()),
                    c.above(), c.below() }) {
                if (!cellSet.contains(n) && !isFrame(level, n)) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ the crossing

    private static void cross(MinecraftServer server, ServerLevel from,
            ServerPlayer player, Gate gate) {
        from.sendParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1, player.getZ(),
                24, 0.4, 0.9, 0.4, 0.05);
        if (gate.dim() != CharonsEcho.GRAVEYARD_DIM) {
            ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
            BlockPos at = Church.arrivalPoint();
            if (graveyard == null || at == null) return;
            // Which side did the walker enter from? The sign of their look
            // along the plane normal — the return exits that same side.
            double look = gate.lateralX() ? player.getLookAngle().z : player.getLookAngle().x;
            LAST_GATE.put(player.getUUID(), new Crossing(gate, look >= 0 ? 1 : -1));
            graveyard.getChunk(at.getX() >> 4, at.getZ() >> 4);
            player.teleportTo(graveyard, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                    Set.<Relative>of(), player.getYRot(), 0f, false);
            arrive(graveyard, at, player, "You step through the veil.");
        } else {
            Crossing back = LAST_GATE.get(player.getUUID());
            if (back != null && GATES.contains(back.gate())
                    && back.gate().dim() != CharonsEcho.GRAVEYARD_DIM) {
                Gate home = back.gate();
                ServerLevel dest = server.getLevel(home.dim());
                if (dest != null) {
                    int lowY = Integer.MAX_VALUE;
                    for (BlockPos c : home.cells()) {
                        lowY = Math.min(lowY, c.getY());
                    }
                    // Exit the side you came IN from, facing away, mid-stride.
                    double out = -back.sign() * 1.6;
                    double tx = home.centerX() + (home.lateralX() ? 0 : out);
                    double tz = home.centerZ() + (home.lateralX() ? out : 0);
                    float yaw = home.lateralX()
                            ? (out >= 0 ? 0f : 180f)
                            : (out >= 0 ? -90f : 90f);
                    BlockPos anchor = home.anchor();
                    dest.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4);
                    player.teleportTo(dest, tx, lowY, tz,
                            Set.<Relative>of(), yaw, 0f, false);
                    arrive(dest, anchor, player, "The living world takes you back.");
                    return;
                }
            }
            ServerLevel overworld = server.overworld();
            BlockPos sp = overworld.getRespawnData().globalPos().pos();
            overworld.getChunk(sp.getX() >> 4, sp.getZ() >> 4);
            player.teleportTo(overworld, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5,
                    Set.<Relative>of(), player.getYRot(), 0f, false);
            arrive(overworld, sp, player, "The living world takes you back.");
        }
    }

    private static void arrive(ServerLevel level, BlockPos at, ServerPlayer player, String msg) {
        level.sendParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 1, player.getZ(),
                24, 0.4, 0.9, 0.4, 0.05);
        level.playSound(null, at, SoundEvents.SOUL_ESCAPE.value(),
                SoundSource.AMBIENT, 0.9f, 0.8f);
        player.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.DARK_PURPLE));
    }

    // ------------------------------------------------------------ persistence

    public static void load(MinecraftServer server) {
        GATES.clear();
        LAST_GATE.clear();
        file = server.getWorldPath(LevelResource.ROOT)
                .resolve("charons_echo").resolve("gates.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (Tag t : root.getListOrEmpty("gates")) {
                if (!(t instanceof CompoundTag c)) continue;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        Identifier.parse(c.getStringOr("dim", "minecraft:overworld")));
                List<BlockPos> cells = new ArrayList<>();
                for (long l : c.getLongArray("cells").orElse(new long[0])) {
                    cells.add(BlockPos.of(l));
                }
                if (cells.isEmpty()) continue;
                GATES.add(new Gate(dim, cells, c.getBooleanOr("lx", true)));
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load gates.dat: " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (Gate g : GATES) {
                CompoundTag c = new CompoundTag();
                c.putString("dim", g.dim().identifier().toString());
                long[] cells = new long[g.cells().size()];
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = g.cells().get(i).asLong();
                }
                c.putLongArray("cells", cells);
                c.putBoolean("lx", g.lateralX());
                list.add(c);
            }
            root.put("gates", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save gates.dat: " + e);
        }
    }
}
