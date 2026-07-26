package com.charonsecho;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * World rules for Charon's Echo: no damage of any kind, and the Gravekeepers
 * (Wardens, Creakings) are passive caretakers — their targeting/anger is
 * cleared continuously so they never hunt each other or visitors.
 *
 * Known vanilla behavior to watch: a calm Warden eventually digs back into the
 * ground and despawns. Persistence is forced here, but if keepers still burrow
 * away, the spawn-budget system will re-seed them.
 */
public final class GraveyardRules {

    private GraveyardRules() {}

    public static void register() {
        // Nothing hurts anyone in the world of the dead.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                entity.level().dimension() != CharonsEcho.GRAVEYARD_DIM);

        // The living may visit, but not build or break — hallowed ground.
        // Gamemasters and rostered gravekeepers are exempt (permissions()
        // check works for the single-player host, unlike isOp()). The Studio
        // is builder-only ground too.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register(
                (world, player, pos, state, blockEntity) -> {
                    if (world.dimension() == CharonsEcho.GRAVEYARD_DIM) {
                        // The one thing the living may take from the dead's
                        // world: ripe Tollfruit (the Orchard handler collects it).
                        if (Orchard.isRipeFruit(world, pos)) return true;
                        return player instanceof net.minecraft.server.level.ServerPlayer sp
                                && Gravekeepers.canBuild(sp);
                    }
                    if (world.dimension() == CharonsEcho.STUDIO_DIM) {
                        return player instanceof net.minecraft.server.level.ServerPlayer sp
                                && StudioSets.canBuildAt(sp, pos.getX(), pos.getZ());
                    }
                    return true;
                });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM
                    && world.dimension() != CharonsEcho.STUDIO_DIM) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            // Grave stones speak: the owner inters a book, everyone else reads it.
            if (world.dimension() == CharonsEcho.GRAVEYARD_DIM
                    && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                var graveOpt = GraveManager.graveAt(hit.getBlockPos());
                if (graveOpt.isPresent()) {
                    GraveManager.Grave grave = graveOpt.get();
                    var held = sp.getItemInHand(hand);
                    if (grave.owner.equals(sp.getUUID()) && GraveBooks.isBook(held)) {
                        return GraveBooks.intern(sp, grave, held)
                                ? net.minecraft.world.InteractionResult.SUCCESS
                                : net.minecraft.world.InteractionResult.PASS;
                    }
                    if (grave.book != null && !GhostState.isGhost(sp.getUUID())) {
                        GraveBooks.open(sp, grave);
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    }
                }
            }
            if (!(player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            // Client side can't know who's a builder — always forward so the
            // SERVER decides (a client-side FAIL would swallow everyone's
            // placements, admins included).
            if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            boolean allowed = world.dimension() == CharonsEcho.STUDIO_DIM
                    ? StudioSets.canBuildAt(sp, hit.getBlockPos().getX(), hit.getBlockPos().getZ())
                    : Gravekeepers.canBuild(sp);
            return allowed ? net.minecraft.world.InteractionResult.PASS
                           : net.minecraft.world.InteractionResult.FAIL;
        });

        // Pacify the Gravekeepers a few times a second.
        ServerTickEvents.END_SERVER_TICK.register(GraveyardRules::tick);
    }

    static boolean isGamemaster(net.minecraft.server.level.ServerPlayer player) {
        return player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * The graveyard's staff: WARDENS own the fields, CREAKINGS own the trees.
     * Each field keeps one warden groundskeeper inside the fence and a pair of
     * creakings lurking at its edges. Called when a field opens, and by the
     * census tick whenever players are near an understaffed field. The keeper
     * sweep pacifies them all — they mind the yard, not each other.
     */
    static void censusField(ServerLevel graveyard, int fieldIndex) {
        net.minecraft.core.BlockPos c = GraveyardPlots.fieldCenter(fieldIndex);
        int ground = GraveyardTerrain.groundHeight(c.getX(), c.getZ());
        var box = new net.minecraft.world.phys.AABB(
                c.getX() - 21, ground - 24, c.getZ() - 21,
                c.getX() + 21, ground + 24, c.getZ() + 21);
        var wardens = graveyard.getEntitiesOfClass(
                net.minecraft.world.entity.monster.warden.Warden.class, box);
        if (wardens.isEmpty()) {
            spawnKeeper(graveyard, net.minecraft.world.entity.EntityTypes.WARDEN,
                    c.getX() + 3, c.getZ() + 3);
        }
        var creakings = graveyard.getEntitiesOfClass(
                net.minecraft.world.entity.monster.creaking.Creaking.class,
                box.inflate(16, 0, 16));
        for (int k = creakings.size(); k < 2; k++) {
            int dx = (k == 0 ? -25 : 25);
            int dz = (k == 0 ? -8 : 8);
            spawnKeeper(graveyard, net.minecraft.world.entity.EntityTypes.CREAKING,
                    c.getX() + dx, c.getZ() + dz);
        }
    }

    private static void spawnKeeper(ServerLevel level,
            net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> type,
            int x, int z) {
        int h = GraveyardTerrain.groundHeight(x, z);
        if (h < GraveyardTerrain.WATER_TOP) {
            x += 7;
            z += 7;
            h = GraveyardTerrain.groundHeight(x, z);
            if (h < GraveyardTerrain.WATER_TOP) return; // the dead do not swim
        }
        level.getChunk(x >> 4, z >> 4);
        var mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (mob == null) return;
        mob.setPos(x + 0.5, h + 1, z + 0.5);
        mob.setPersistenceRequired();
        // Keepers are POSTED: a home and a radius, so nobody can pied-piper
        // thirty creakings into one laggy ball across the map.
        mob.setHomeTo(new net.minecraft.core.BlockPos(x, h + 1, z), 24);
        level.addFreshEntity(mob);
    }

    private static final String KEEPERS_TEAM = "charon_keepers";

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;

        // The Studio is builders-only ground: anyone else who slips in (other
        // mods' teleports, stale logouts) is shown the door.
        ServerLevel studio = server.getLevel(CharonsEcho.STUDIO_DIM);
        if (studio != null) {
            for (net.minecraft.server.level.ServerPlayer p : studio.players()) {
                if (!Gravekeepers.canBuild(p)) {
                    ServerLevel overworld = server.overworld();
                    var spawn = overworld.getRespawnData().pos();
                    p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                            java.util.Set.<net.minecraft.world.entity.Relative>of(), p.getYRot(), 0f, false);
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "The Studio is for gravekeepers only.")
                            .withStyle(net.minecraft.ChatFormatting.RED));
                }
            }
        }
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;

        // Staff census: re-staff any understaffed field a player is near.
        if (server.getTickCount() % 600 == 0) {
            for (int i = 0; i < GraveyardPlots.fieldCount(); i++) {
                net.minecraft.core.BlockPos c = GraveyardPlots.fieldCenter(i);
                if (graveyard.getNearestPlayer(c.getX() + 0.5,
                        GraveyardTerrain.groundHeight(c.getX(), c.getZ()),
                        c.getZ() + 0.5, 96, false) != null) {
                    censusField(graveyard, i);
                }
            }
        }

        Scoreboard sb = server.getScoreboard();
        PlayerTeam keepers = sb.getPlayerTeam(KEEPERS_TEAM);
        if (keepers == null) {
            keepers = sb.addPlayerTeam(KEEPERS_TEAM);
            keepers.setAllowFriendlyFire(false);
        }
        var players = graveyard.players();
        for (Entity e : graveyard.getAllEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (mob.getType() == EntityTypes.WARDEN || mob.getType() == EntityTypes.CREAKING) {
                // Same team → isAlliedTo() → vanilla targeting (Warden included)
                // skips them. Target-clearing stays as a cheap backstop.
                if (mob.getTeam() == null) {
                    sb.addPlayerToTeam(mob.getScoreboardName(), keepers);
                }
                if (mob.getTarget() != null) {
                    mob.setTarget(null);
                }
                // Post enforcement: keepers spawned before homes existed adopt
                // their current spot; any keeper lured past its leash walks
                // back the hard way — a quiet snap to the post.
                if (!mob.hasHome()) {
                    mob.setHomeTo(mob.blockPosition(), 24);
                } else if (mob.blockPosition().distSqr(mob.getHomePosition()) > 40 * 40) {
                    net.minecraft.core.BlockPos home = mob.getHomePosition();
                    mob.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                    mob.setTarget(null);
                }
                // Wardens rebuild anger at players from vibrations — wipe it so
                // they never escalate to hunting a visitor. And a CALM warden
                // digs itself home after a quiet minute — keep its dig cooldown
                // permanently topped up so the groundskeeper never gets the urge.
                if (mob instanceof net.minecraft.world.entity.monster.warden.Warden warden) {
                    for (var p : players) {
                        warden.clearAnger(p);
                    }
                    net.minecraft.world.entity.monster.warden.WardenAi.setDigCooldown(warden);
                }
                mob.setPersistenceRequired();
            }
        }

        // The Warden's darkness pulse is unconditional — strip it (and any
        // blindness) from visitors so the gravekeepers never blind the living.
        for (var p : players) {
            if (p.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) {
                p.removeEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
            }
            if (p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) {
                p.removeEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
            }
        }
    }
}
