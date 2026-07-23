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

        // Pacify the Gravekeepers a few times a second.
        ServerTickEvents.END_SERVER_TICK.register(GraveyardRules::tick);
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
                mob.setPersistenceRequired();
            }
        }
    }
}
