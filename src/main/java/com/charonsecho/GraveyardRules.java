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
                    if (world.dimension() != CharonsEcho.GRAVEYARD_DIM
                            && world.dimension() != CharonsEcho.STUDIO_DIM) return true;
                    return player instanceof net.minecraft.server.level.ServerPlayer sp
                            && Gravekeepers.canBuild(sp);
                });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM
                    && world.dimension() != CharonsEcho.STUDIO_DIM) {
                return net.minecraft.world.InteractionResult.PASS;
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
            return Gravekeepers.canBuild(sp)
                    ? net.minecraft.world.InteractionResult.PASS
                    : net.minecraft.world.InteractionResult.FAIL;
        });

        // Pacify the Gravekeepers a few times a second.
        ServerTickEvents.END_SERVER_TICK.register(GraveyardRules::tick);
    }

    static boolean isGamemaster(net.minecraft.server.level.ServerPlayer player) {
        return player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    private static final String KEEPERS_TEAM = "charon_keepers";

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
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
                // Wardens rebuild anger at players from vibrations — wipe it so
                // they never escalate to hunting a visitor.
                if (mob instanceof net.minecraft.world.entity.monster.warden.Warden warden) {
                    for (var p : players) {
                        warden.clearAnger(p);
                    }
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
