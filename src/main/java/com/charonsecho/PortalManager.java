package com.charonsecho;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Charon's portals — pure particles and proximity, no blocks placed anywhere.
 *
 * Death portal: rises beside the death anchor while the ghost is in the living
 * world. Walking into it pays Charon (an Echo Shard if carried, otherwise half
 * the XP the grave holds) and carries the ghost to their grave in Charon's
 * Echo, allocating the plot + headstone on first crossing.
 *
 * Return portal: appears beside the headstone once the grave is reclaimed.
 * Walking into it returns the player — alive — to a safe spot at the death
 * site, and the ghost state ends.
 */
public final class PortalManager {

    /** A waiting way home: where the portal stands, and where it leads. */
    record ReturnPortal(BlockPos portalPos, String targetDim, BlockPos target) {}

    /** Players (living, post-reclaim) with a return portal waiting. */
    private static final Map<UUID, ReturnPortal> RETURN_PORTALS = new ConcurrentHashMap<>();

    private PortalManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PortalManager::tick);
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Ghosts: the death portal in the living world.
            GhostState.GhostData data = GhostState.get(player.getUUID());
            if (data != null) {
                String dim = player.level().dimension().identifier().toString();
                if (dim.equals(data.dimension())) {
                    // Offset from where the body fell — walking in is a
                    // deliberate act, never an accident of standing still.
                    BlockPos portal = data.portal();
                    portalParticles((ServerLevel) player.level(), portal, player.tickCount);
                    if (player.tickCount % 2 == 0
                            && player.position().distanceTo(Vec3.atCenterOf(portal)) < 1.4) {
                        crossToGraveyard(server, player);
                    }
                    continue;
                }
            }

            // The living (post-reclaim): the return portal in the graveyard.
            if (player.level().dimension() != CharonsEcho.GRAVEYARD_DIM) continue;
            ReturnPortal ret = RETURN_PORTALS.get(player.getUUID());
            if (ret == null && data == null) {
                // Relogged (or wandered in) after reclaiming: rebuild the way
                // home from the latest claimed grave — nobody is ever stranded.
                ret = GraveManager.all().stream()
                        .filter(g -> g.owner.equals(player.getUUID()) && g.claimed && g.plotIndex >= 0)
                        .reduce((a, b) -> b)
                        .map(g -> new ReturnPortal(
                                GraveyardPlots.arrivalPos(g.plotIndex).offset(2, 0, 0),
                                g.dimension, g.pos))
                        .orElse(null);
                if (ret != null) RETURN_PORTALS.put(player.getUUID(), ret);
            }
            if (ret != null) {
                portalParticles((ServerLevel) player.level(), ret.portalPos(), player.tickCount);
                if (player.tickCount % 2 == 0
                        && player.position().distanceTo(Vec3.atCenterOf(ret.portalPos())) < 1.4) {
                    returnHome(server, player, ret);
                }
            }
        }
    }

    /** Soul-flame spiral, broadcast to anyone nearby (no blocks). */
    private static void portalParticles(ServerLevel level, BlockPos pos, int tick) {
        if (tick % 3 != 0) return;
        double angle = (tick % 40) / 40.0 * Math.PI * 2;
        double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
        for (int i = 0; i < 3; i++) {
            double a = angle + i * (Math.PI * 2 / 3);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    cx + Math.cos(a) * 0.8, pos.getY() + 0.2 + (tick % 40) / 40.0 * 2.4,
                    cz + Math.sin(a) * 0.8, 1, 0, 0.02, 0, 0.0);
        }
    }

    private static void crossToGraveyard(MinecraftServer server, ServerPlayer player) {
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        var graveOpt = GraveManager.oldestUnclaimed(player.getUUID());
        if (graveyard == null || graveOpt.isEmpty()) {
            // Nothing to reclaim (edge case) — just end the ghost state.
            GhostState.remove(player);
            return;
        }
        GraveManager.Grave grave = graveOpt.get();

        // Charon's fare.
        if (grave.farePaid) {
            player.sendSystemMessage(Component.literal("Your fare was paid at the wake. Charon nods.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        } else if (consumeObol(player)) {
            player.sendSystemMessage(Component.literal("Charon accepts your fare.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        } else if (grave.xpLevels > 0) {
            int before = grave.xpLevels;
            grave.xpLevels = grave.xpLevels / 2;
            GraveManager.save();
            player.sendSystemMessage(Component.literal(
                    "No coin for the Ferryman. Charon takes half the memory of your deeds ("
                    + before + " → " + grave.xpLevels + " levels).")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        } else {
            player.sendSystemMessage(Component.literal(
                    "No coin, and nothing worth taking. Charon ferries you out of pity.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }

        if (grave.plotIndex < 0) {
            GraveyardPlots.allocate(graveyard, grave);
        }
        BlockPos arrive = GraveyardPlots.arrivalPos(grave.plotIndex);
        graveyard.getChunk(arrive.getX() >> 4, arrive.getZ() >> 4);
        player.teleportTo(graveyard, arrive.getX() + 0.5, arrive.getY(), arrive.getZ() + 0.5,
                Set.<Relative>of(), 180f, 0f, false);
        graveyard.playSound(null, arrive, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 0.7f, 0.5f);
        player.sendSystemMessage(Component.literal(
                "You stand in Charon's Echo. Your grave lies before you — touch the stone to reclaim what you lost.")
                .withStyle(ChatFormatting.GRAY));
    }

    private static boolean consumeObol(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (CharonObol.isObol(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Called from GhostState's use-block hook. True if the click reclaimed a grave. */
    public static boolean tryReclaim(ServerPlayer player, BlockPos clicked) {
        if (player.level().dimension() != CharonsEcho.GRAVEYARD_DIM) return false;
        var graveOpt = GraveManager.oldestUnclaimed(player.getUUID());
        if (graveOpt.isEmpty()) return false;
        GraveManager.Grave grave = graveOpt.get();
        if (!GraveyardPlots.isOnPlot(grave, clicked)) return false;

        ServerLevel graveyard = (ServerLevel) player.level();
        for (ItemStack stack : grave.items) {
            player.getInventory().placeItemBackInInventory(stack.copy());
        }
        player.setExperienceLevels(grave.xpLevels);
        player.experienceProgress = grave.xpProgress;
        grave.claimed = true;
        GraveManager.save();
        GraveyardPlots.markAtRest(graveyard, grave);

        // Touching the stone IS the resurrection: the ghost ends here, alive
        // among the graves. The portal is just the way home.
        RETURN_PORTALS.put(player.getUUID(), new ReturnPortal(
                GraveyardPlots.arrivalPos(grave.plotIndex).offset(2, 0, 0),
                grave.dimension, grave.pos));
        GhostState.remove(player);
        graveyard.sendParticles(ParticleTypes.SOUL,
                player.getX(), player.getY() + 1, player.getZ(), 30, 0.4, 0.8, 0.4, 0.03);
        graveyard.playSound(null, clicked, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 1.0f, 0.8f);
        player.sendSystemMessage(Component.literal(
                "What was yours is yours again — your echo rejoins the living. The portal beside your grave leads home.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    private static void returnHome(MinecraftServer server, ServerPlayer player, ReturnPortal ret) {
        RETURN_PORTALS.remove(player.getUUID());
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(ret.targetDim()));
        ServerLevel target = server.getLevel(dimKey);
        if (target == null) target = server.overworld();
        BlockPos safe = findSafe(target, ret.target());
        player.teleportTo(target, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5,
                Set.<Relative>of(), player.getYRot(), 0f, false);
        if (GhostState.isGhost(player.getUUID())) {
            GhostState.remove(player); // safety net — normally ended at the stone
        }
        target.playSound(null, safe, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 1.0f, 1.2f);
        player.sendSystemMessage(Component.literal("The mists part; the living world takes you back.")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Nearest safe stand spot: solid floor, two air blocks, no fluid. */
    static BlockPos findSafe(ServerLevel level, BlockPos near) {
        level.getChunk(near.getX() >> 4, near.getZ() >> 4);
        int minY = level.getMinY() + 1;
        int startY = Math.max(near.getY(), minY);
        // Try the anchor column first, then a small spiral.
        for (int ring = 0; ring <= 4; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos col = new BlockPos(near.getX() + dx, startY, near.getZ() + dz);
                    BlockPos safe = safeInColumn(level, col);
                    if (safe != null) return safe;
                }
            }
        }
        // Fallback: the world surface at the anchor.
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                near.getX(), near.getZ());
        return new BlockPos(near.getX(), Math.max(y, minY), near.getZ());
    }

    private static BlockPos safeInColumn(ServerLevel level, BlockPos start) {
        for (int dy = 0; dy <= 8; dy++) {
            for (int sign : new int[]{1, -1}) {
                int y = start.getY() + dy * sign;
                if (y <= level.getMinY() || y >= level.getMinY() + level.getHeight() - 2) continue;
                BlockPos feet = new BlockPos(start.getX(), y, start.getZ());
                BlockPos floor = feet.below();
                if (level.getBlockState(floor).isSolid()
                        && level.getBlockState(feet).isAir()
                        && level.getBlockState(feet.above()).isAir()
                        && level.getFluidState(feet).isEmpty()) {
                    return feet;
                }
            }
        }
        return null;
    }
}
