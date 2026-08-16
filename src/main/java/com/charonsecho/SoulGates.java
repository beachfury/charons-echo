package com.charonsecho;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
    static final class Gate {
        private final ResourceKey<Level> dim;
        private final List<BlockPos> cells;
        private final Set<BlockPos> cellSet;
        private final boolean lateralX;
        private final AABB bounds;
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        private final int lowY;
        private final List<Long> chunks;

        Gate(ResourceKey<Level> dim, List<BlockPos> cells, boolean lateralX) {
            this.dim = dim;
            this.cells = List.copyOf(cells);
            this.cellSet = Set.copyOf(cells);
            this.lateralX = lateralX;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos c : cells) {
                minX = Math.min(minX, c.getX()); maxX = Math.max(maxX, c.getX());
                minY = Math.min(minY, c.getY()); maxY = Math.max(maxY, c.getY());
                minZ = Math.min(minZ, c.getZ()); maxZ = Math.max(maxZ, c.getZ());
            }
            double thin = 0.75;
            this.bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)
                    .inflate(lateralX ? 0 : thin, 0, lateralX ? thin : 0);
            this.centerX = bounds.getCenter().x;
            this.centerY = bounds.getCenter().y;
            this.centerZ = bounds.getCenter().z;
            this.lowY = minY;
            List<Long> covered = new ArrayList<>();
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    covered.add(new net.minecraft.world.level.ChunkPos(cx, cz).pack());
                }
            }
            this.chunks = List.copyOf(covered);
        }

        ResourceKey<Level> dim() { return dim; }
        List<BlockPos> cells() { return cells; }
        Set<BlockPos> cellSet() { return cellSet; }
        boolean lateralX() { return lateralX; }
        BlockPos anchor() { return cells.get(0); }
        AABB bounds() { return bounds; }
        double centerX() { return centerX; }
        double centerY() { return centerY; }
        double centerZ() { return centerZ; }
        int lowY() { return lowY; }
        List<Long> chunks() { return chunks; }
    }

    /** A remembered crossing: the gate, and WHICH SIDE the walker entered
     *  from (sign along the plane normal) — the return drops them on that
     *  side, facing away, mid-stride. */
    private record Crossing(Gate gate, int sign) {}

    private static final List<Gate> GATES = new ArrayList<>();
    private static final Map<ResourceKey<Level>, Map<Long, List<Gate>>> GATES_BY_CHUNK =
            new HashMap<>();
    private static final Map<UUID, Crossing> LAST_GATE = new HashMap<>();
    /** Like the death portals: a gate that carried you stays DISARMED until
     *  you are wholly clear of every aperture — no ping-pong, ever. */
    private static final Set<UUID> DISARMED = new HashSet<>();
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            LAST_GATE.remove(id);
            DISARMED.remove(id);
        });
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
        addGate(gate);
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
        Set<Gate> active = activeGates(server);
        for (Gate gate : active) {
            ServerLevel level = server.getLevel(gate.dim());
            if (level == null || !level.isLoaded(gate.anchor())) continue;

            // The frame keeps its shape or the door closes.
            if (Math.floorMod(time / 2 + gate.anchor().asLong(), 50) == 0
                    && !frameIntact(level, gate)) {
                removeGate(gate);
                save();
                level.sendParticles(ParticleTypes.SOUL, gate.centerX(), gate.centerY(),
                        gate.centerZ(), 30, 0.8, 1.0, 0.8, 0.05);
                level.playSound(null, gate.anchor(), SoundEvents.SOUL_ESCAPE.value(),
                        SoundSource.AMBIENT, 0.8f, 0.5f);
                continue;
            }

            // The breath drifts from the aperture itself, whatever its shape.
            if (time % 5 == 0) {
                RandomSource rand = level.getRandom();
                for (int i = 0; i < 2; i++) {
                    BlockPos cell = gate.cells().get(rand.nextInt(gate.cells().size()));
                    level.sendParticles(ParticleTypes.SOUL,
                            cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                            1, 0.3, 0.3, 0.3, 0.012);
                }
            }
            if (time % 20 == 0) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        gate.centerX(), gate.lowY() + 0.15, gate.centerZ(), 2,
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
            Iterator<UUID> walkers = DISARMED.iterator();
            while (walkers.hasNext()) {
                UUID id = walkers.next();
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p == null || !inAnyAperture(p)) {
                    walkers.remove();
                }
            }
        }
    }

    /** Only gates in or near a player's current chunk breathe and test crossings. */
    private static Set<Gate> activeGates(MinecraftServer server) {
        Set<Gate> active = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<Long, List<Gate>> dimension = GATES_BY_CHUNK.get(player.level().dimension());
            if (dimension == null) continue;
            int pcx = player.blockPosition().getX() >> 4;
            int pcz = player.blockPosition().getZ() >> 4;
            for (int cx = pcx - 3; cx <= pcx + 3; cx++) {
                for (int cz = pcz - 3; cz <= pcz + 3; cz++) {
                    List<Gate> candidates = dimension.get(
                            new net.minecraft.world.level.ChunkPos(cx, cz).pack());
                    if (candidates != null) active.addAll(candidates);
                }
            }
        }
        return active;
    }

    private static boolean inAnyAperture(ServerPlayer p) {
        Map<Long, List<Gate>> dimension = GATES_BY_CHUNK.get(p.level().dimension());
        if (dimension == null) return false;
        BlockPos at = p.blockPosition();
        List<Gate> candidates = dimension.get(new net.minecraft.world.level.ChunkPos(
                at.getX() >> 4, at.getZ() >> 4).pack());
        if (candidates == null) return false;
        for (Gate gate : candidates) {
            if (inAperture(gate, p)) return true;
        }
        return false;
    }

    /** The bounding box overshoots odd shapes — confirm a real cell. */
    private static boolean inAperture(Gate gate, ServerPlayer p) {
        BlockPos feet = p.blockPosition();
        return gate.cellSet().contains(feet) || gate.cellSet().contains(feet.above());
    }

    private static boolean frameIntact(ServerLevel level, Gate gate) {
        for (BlockPos c : gate.cells()) {
            if (!isAir(level, c)) return false; // something filled the door
            for (BlockPos n : new BlockPos[] {
                    off(c, 1, gate.lateralX()), off(c, -1, gate.lateralX()),
                    c.above(), c.below() }) {
                if (!gate.cellSet().contains(n) && !isFrame(level, n)) return false;
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
            // Arrive facing the church — it stands at the graveyard's origin,
            // and the dead should see where they are going.
            double dx = 0.5 - (at.getX() + 0.5);
            double dz = 0.5 - (at.getZ() + 0.5);
            float churchward = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.teleportTo(graveyard, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                    Set.<Relative>of(), churchward, 0f, false);
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
        GATES_BY_CHUNK.clear();
        LAST_GATE.clear();
        file = server.getWorldPath(LevelResource.ROOT)
                .resolve("charons_echo").resolve("gates.dat");
        if (!CharonStorage.hasData(file)) return;
        try {
            CompoundTag root = CharonStorage.read(file);
            for (Tag t : root.getListOrEmpty("gates")) {
                if (!(t instanceof CompoundTag c)) continue;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                        Identifier.parse(c.getStringOr("dim", "minecraft:overworld")));
                List<BlockPos> cells = new ArrayList<>();
                for (long l : c.getLongArray("cells").orElse(new long[0])) {
                    cells.add(BlockPos.of(l));
                }
                if (cells.isEmpty()) continue;
                addGate(new Gate(dim, cells, c.getBooleanOr("lx", true)));
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load gates.dat: " + e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
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
            CharonStorage.write(file, root);
        } catch (RuntimeException e) {
            System.out.println("[CharonsEcho] failed to snapshot gates.dat: " + e);
        }
    }

    private static void addGate(Gate gate) {
        GATES.add(gate);
        Map<Long, List<Gate>> dimension = GATES_BY_CHUNK.computeIfAbsent(
                gate.dim(), ignored -> new HashMap<>());
        for (long chunk : gate.chunks()) {
            dimension.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(gate);
        }
    }

    private static void removeGate(Gate gate) {
        GATES.remove(gate);
        Map<Long, List<Gate>> dimension = GATES_BY_CHUNK.get(gate.dim());
        if (dimension == null) return;
        for (long chunk : gate.chunks()) {
            List<Gate> gates = dimension.get(chunk);
            if (gates == null) continue;
            gates.remove(gate);
            if (gates.isEmpty()) dimension.remove(chunk);
        }
        if (dimension.isEmpty()) GATES_BY_CHUNK.remove(gate.dim());
    }
}
