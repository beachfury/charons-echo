package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * SOUL GATES — the living raise doors to the world of the dead.
 *
 * Build a closed rectangle of GILDED BLACKSTONE (interior 2-5 wide, 3-5
 * tall, standing in any vertical plane), then touch the frame with a
 * Charon's Obol. The coin is spent, and the aperture draws breath — the
 * same SOUL breath the ghosts wear. Walk through: gates in the living
 * world carry you to the graveyard's arrival; gates in Charon's Echo
 * carry you back through the gate you last used (or to the world spawn).
 * Break the frame and the door closes.
 *
 * Config soul-gates: 0 = off, 1 = gamemasters raise gates, 2 = anyone
 * (default) — the obol is the price either way.
 */
public final class SoulGates {

    /** min = interior corner (lowest y, lowest lateral); lateralX = the
     *  frame spans the X axis (crossing travels along Z). */
    record Gate(ResourceKey<Level> dim, BlockPos min, int width, int height, boolean lateralX) {

        AABB interior() {
            double thin = 0.75;
            return lateralX
                    ? new AABB(min.getX(), min.getY(), min.getZ() + 0.5 - thin,
                            min.getX() + width, min.getY() + height, min.getZ() + 0.5 + thin)
                    : new AABB(min.getX() + 0.5 - thin, min.getY(), min.getZ(),
                            min.getX() + 0.5 + thin, min.getY() + height, min.getZ() + width);
        }

        double centerX() { return lateralX ? min.getX() + width / 2.0 : min.getX() + 0.5; }
        double centerY() { return min.getY() + height / 2.0; }
        double centerZ() { return lateralX ? min.getZ() + 0.5 : min.getZ() + width / 2.0; }
    }

    private static final List<Gate> GATES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Gate> LAST_GATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
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
        for (Gate g : GATES) {
            if (g.dim() == level.dimension() && clicked.distSqr(g.min()) < 12 * 12) {
                player.sendSystemMessage(Component.literal("This gate already breathes.")
                        .withStyle(ChatFormatting.DARK_PURPLE));
                return InteractionResult.FAIL;
            }
        }
        Gate gate = detect(level, clicked);
        if (gate == null) {
            player.sendSystemMessage(Component.literal(
                    "The frame is not whole. A closed door of gilded blackstone —"
                    + " two to five wide within, three to five tall.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        if (!player.getAbilities().instabuild) {
            player.getItemInHand(hand).shrink(1);
        }
        GATES.add(gate);
        save();
        level.sendParticles(ParticleTypes.SOUL, gate.centerX(), gate.centerY(), gate.centerZ(),
                40, gate.lateralX() ? gate.width() / 2.0 : 0.2, gate.height() / 2.0,
                gate.lateralX() ? 0.2 : gate.width() / 2.0, 0.04);
        level.playSound(null, gate.min(), SoundEvents.SOUL_ESCAPE.value(),
                SoundSource.AMBIENT, 1.0f, 0.7f);
        player.sendSystemMessage(Component.literal(
                "The coin is spent. The gate draws breath.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return InteractionResult.SUCCESS;
    }

    /** Nether-style frame walk from any clicked frame block. */
    private static Gate detect(ServerLevel level, BlockPos clicked) {
        for (boolean lateralX : new boolean[] { true, false }) {
            for (BlockPos seed : new BlockPos[] {
                    off(clicked, 1, lateralX), off(clicked, -1, lateralX),
                    clicked.above(), clicked.below() }) {
                if (!isAir(level, seed)) continue;
                Gate g = trace(level, seed, lateralX);
                if (g != null) return g;
            }
        }
        return null;
    }

    private static Gate trace(ServerLevel level, BlockPos inside, boolean lateralX) {
        BlockPos p = inside;
        int guard = 0;
        while (isAir(level, p.below()) && guard++ < 6) p = p.below();
        if (!isFrame(level, p.below())) return null;
        guard = 0;
        while (isAir(level, off(p, -1, lateralX)) && isFrame(level, off(p, -1, lateralX).below())
                && guard++ < 6) {
            p = off(p, -1, lateralX);
        }
        if (!isFrame(level, off(p, -1, lateralX))) return null;
        int width = 0;
        BlockPos scan = p;
        while (isAir(level, scan) && width <= 5) {
            if (!isFrame(level, scan.below())) return null;
            width++;
            scan = off(scan, 1, lateralX);
        }
        if (width < 2 || width > 5 || !isFrame(level, scan)) return null;
        int height = 0;
        boolean capped = false;
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos row = p.above(dy);
            boolean allAir = true, allFrame = true;
            for (int l = 0; l < width; l++) {
                BlockPos c = off(row, l, lateralX);
                if (!isAir(level, c)) allAir = false;
                if (!isFrame(level, c)) allFrame = false;
            }
            if (allAir) {
                if (!isFrame(level, off(row, -1, lateralX))
                        || !isFrame(level, off(row, width, lateralX))) return null;
                height++;
            } else if (allFrame) {
                capped = true;
                break;
            } else {
                return null;
            }
        }
        if (!capped || height < 3 || height > 5) return null;
        return new Gate(level.dimension(), p.immutable(), width, height, lateralX);
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
            if (level == null || !level.isLoaded(gate.min())) continue;

            // The frame keeps its shape or the door closes.
            if (time % 100 == 0 && !frameIntact(level, gate)) {
                GATES.remove(gate);
                save();
                level.sendParticles(ParticleTypes.SOUL, gate.centerX(), gate.centerY(),
                        gate.centerZ(), 30, 0.8, 1.0, 0.8, 0.05);
                level.playSound(null, gate.min(), SoundEvents.SOUL_ESCAPE.value(),
                        SoundSource.AMBIENT, 0.8f, 0.5f);
                continue;
            }

            level.sendParticles(ParticleTypes.SOUL,
                    gate.centerX(), gate.centerY() + 0.3, gate.centerZ(), 3,
                    gate.lateralX() ? gate.width() / 2.4 : 0.1, gate.height() / 2.6,
                    gate.lateralX() ? 0.1 : gate.width() / 2.4, 0.012);
            if (time % 20 == 0) {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        gate.centerX(), gate.min().getY() + 0.15, gate.centerZ(), 2,
                        gate.lateralX() ? gate.width() / 2.2 : 0.1, 0.05,
                        gate.lateralX() ? 0.1 : gate.width() / 2.2, 0.004);
            }

            for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, gate.interior())) {
                if (GhostState.isGhost(p.getUUID())) continue;
                Long until = COOLDOWN.get(p.getUUID());
                if (until != null && time < until) continue;
                COOLDOWN.put(p.getUUID(), time + 60);
                cross(server, level, p, gate);
            }
        }
    }

    private static boolean frameIntact(ServerLevel level, Gate gate) {
        BlockPos p = gate.min();
        for (int l = 0; l < gate.width(); l++) {
            if (!isFrame(level, off(p, l, gate.lateralX()).below())) return false;
            if (!isFrame(level, off(p.above(gate.height()), l, gate.lateralX()))) return false;
        }
        for (int dy = 0; dy < gate.height(); dy++) {
            if (!isFrame(level, off(p.above(dy), -1, gate.lateralX()))) return false;
            if (!isFrame(level, off(p.above(dy), gate.width(), gate.lateralX()))) return false;
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
            LAST_GATE.put(player.getUUID(), gate);
            graveyard.getChunk(at.getX() >> 4, at.getZ() >> 4);
            player.teleportTo(graveyard, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                    Set.<Relative>of(), player.getYRot(), 0f, false);
            arrive(graveyard, at, player, "You step through the veil.");
        } else {
            Gate back = LAST_GATE.get(player.getUUID());
            if (back != null && GATES.contains(back) && back.dim() != CharonsEcho.GRAVEYARD_DIM) {
                ServerLevel dest = server.getLevel(back.dim());
                if (dest != null) {
                    double depth = 1.6;
                    double tx = back.lateralX() ? back.centerX() : back.min().getX() + 0.5 + depth;
                    double tz = back.lateralX() ? back.min().getZ() + 0.5 + depth : back.centerZ();
                    dest.getChunk(back.min().getX() >> 4, back.min().getZ() >> 4);
                    player.teleportTo(dest, tx, back.min().getY(), tz,
                            Set.<Relative>of(), back.lateralX() ? 0f : -90f, 0f, false);
                    arrive(dest, back.min(), player, "The living world takes you back.");
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
                GATES.add(new Gate(dim, BlockPos.of(c.getLongOr("min", 0)),
                        c.getIntOr("w", 2), c.getIntOr("h", 3),
                        c.getBooleanOr("lx", true)));
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
                c.putLong("min", g.min().asLong());
                c.putInt("w", g.width());
                c.putInt("h", g.height());
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
