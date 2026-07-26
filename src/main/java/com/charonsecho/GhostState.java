package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Ghost state: applied when Charon takes a player's possessions. Invisible but
 * for a soul-particle silhouette, flying, invulnerable, no interaction with the
 * world, tagged `charon.ghost`, gray name via the `charon_dead` team, tethered
 * to the death site until they cross the portal (portals: next phase).
 *
 * State is re-asserted EVERY TICK — other mods (FabricPlots, Dimensional
 * Inventories) set game modes/abilities on dimension changes, so a one-time
 * setup would get stomped.
 */
public final class GhostState {

    public static final String TAG = "charon.ghost";
    private static final String TEAM_NAME = "charon_dead";
    /** Leash radius around the death anchor in the living world (config). */
    private static double tetherR() {
        return CharonConfig.ghostTetherRadius;
    }

    /** anchor = where the body fell; portal = the soul-fire portal, offset so
     *  the risen ghost must deliberately walk into it. */
    public record GhostData(String dimension, BlockPos anchor, BlockPos portal) {}

    public static GhostData get(UUID uuid) {
        return GHOSTS.get(uuid);
    }

    private static final Map<UUID, GhostData> GHOSTS = new ConcurrentHashMap<>();
    private static MinecraftServer server;

    private GhostState() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GhostState::tick);

        // Ghosts touch nothing — except their own headstone. On the client side
        // (single-player) return SUCCESS so the click is FORWARDED to the
        // server, where the real decision happens — a client-side FAIL would
        // swallow the click before the server ever saw it.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!isGhost(player.getUUID())) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            return PortalManager.tryReclaim(sp, hit.getBlockPos())
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        });
        // Enlisted dead are SOLDIERS: their hands work again — for the war.
        UseItemCallback.EVENT.register((player, world, hand) ->
                isGhost(player.getUUID()) && !War.isActive(player.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
                isGhost(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                isGhost(player.getUUID()) && !War.isActive(player.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                isGhost(player.getUUID()) && !War.isActive(player.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);

        // Rejoining ghosts stay ghosts.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            if (isGhost(handler.getPlayer().getUUID())) {
                applyEffects(handler.getPlayer());
            }
        });
    }

    public static boolean isGhost(UUID uuid) {
        return GHOSTS.containsKey(uuid);
    }

    public static void apply(ServerPlayer player, BlockPos anchor) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos portal = PortalManager.findPortalSpot(level, anchor);
        PortalManager.resetArming(player.getUUID());
        GHOSTS.put(player.getUUID(),
                new GhostData(level.dimension().identifier().toString(), anchor, portal));
        applyEffects(player);
        save();
    }

    public static void remove(ServerPlayer player) {
        GHOSTS.remove(player.getUUID());
        War.onGhostEnd(player); // any oath dissolves with the ghost
        player.removeTag(TAG);
        player.removeEffect(MobEffects.INVISIBILITY);
        player.removeEffect(MobEffects.SLOW_FALLING);
        player.removeEffect(MobEffects.DARKNESS);
        player.getAbilities().invulnerable = false;
        boolean creative = player.gameMode().isCreative();
        player.getAbilities().mayfly = creative;
        player.getAbilities().flying = player.getAbilities().flying && creative;
        player.onUpdateAbilities();
        Scoreboard sb = player.level().getServer().getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(TEAM_NAME);
        if (team != null) {
            sb.removePlayerFromTeam(player.getScoreboardName(), team);
        }
        save();
    }

    private static void applyEffects(ServerPlayer player) {
        player.addTag(TAG);
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, true, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, -1, 0, true, false, false));
        player.getAbilities().mayfly = true;
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();
        Scoreboard sb = player.level().getServer().getScoreboard();
        PlayerTeam team = sb.getPlayerTeam(TEAM_NAME);
        if (team == null) {
            team = sb.addPlayerTeam(TEAM_NAME);
            team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.GRAY));
        }
        sb.addPlayerToTeam(player.getScoreboardName(), team);
    }

    private static void tick(MinecraftServer srv) {
        if (GHOSTS.isEmpty()) return;
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            GhostData data = GHOSTS.get(player.getUUID());
            if (data == null) continue;

            // Re-assert every tick — cheap, and other mods can't stomp it for long.
            // ENLISTED dead are soldiers: visible, vulnerable to the war, grounded.
            boolean soldier = War.isActive(player.getUUID());
            if (soldier) {
                if (player.getAbilities().mayfly || player.getAbilities().invulnerable) {
                    player.getAbilities().mayfly = false;
                    player.getAbilities().flying = false;
                    player.getAbilities().invulnerable = false;
                    player.onUpdateAbilities();
                }
                if (player.hasEffect(MobEffects.INVISIBILITY)) {
                    player.removeEffect(MobEffects.INVISIBILITY);
                }
            } else {
                if (!player.getAbilities().mayfly || !player.getAbilities().invulnerable) {
                    player.getAbilities().mayfly = true;
                    player.getAbilities().invulnerable = true;
                    player.onUpdateAbilities();
                }
                if (!player.hasEffect(MobEffects.INVISIBILITY)) {
                    player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, true, false, false));
                }
            }
            if (!player.hasEffect(MobEffects.SLOW_FALLING)) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, -1, 0, true, false, false));
            }
            player.addTag(TAG); // Set-add: no-op when already present

            // Soul silhouette, a few times a second.
            if (player.tickCount % 4 == 0 && player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SOUL,
                        player.getX(), player.getY() + 1.0, player.getZ(),
                        2, 0.15, 0.45, 0.15, 0.004);
            }

            // Tether to the death anchor while still in the living world.
            String dim = player.level().dimension().identifier().toString();
            if (dim.equals(data.dimension())) {
                Vec3 anchor = Vec3.atCenterOf(data.anchor());
                double dist = player.position().distanceTo(anchor);
                if (dist > tetherR() * 2) {
                    player.teleportTo((ServerLevel) player.level(),
                            anchor.x, anchor.y, anchor.z, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
                } else if (dist > tetherR()) {
                    Vec3 pull = anchor.subtract(player.position()).normalize().scale(0.35);
                    player.setDeltaMovement(player.getDeltaMovement().scale(0.4).add(pull));
                    player.hurtMarked = true;
                    // The world darkens at the edge of the leash.
                    player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, true, false, false));
                    if (player.tickCount % 40 == 0) {
                        player.sendOverlayMessage(net.minecraft.network.chat.Component
                                .literal("The dead may not wander.").withStyle(ChatFormatting.DARK_PURPLE));
                    }
                }
            }
        }
    }

    // ---- persistence (world/charons_echo/ghosts.dat) ----

    public static void load(MinecraftServer srv) {
        server = srv;
        GHOSTS.clear();
        Path file = dataFile(srv);
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (Tag t : root.getListOrEmpty("ghosts")) {
                if (!(t instanceof CompoundTag g)) continue;
                BlockPos anchor = new BlockPos(g.getIntOr("x", 0), g.getIntOr("y", 64), g.getIntOr("z", 0));
                BlockPos portal = new BlockPos(g.getIntOr("px", anchor.getX() + 3),
                        g.getIntOr("py", anchor.getY()), g.getIntOr("pz", anchor.getZ()));
                GHOSTS.put(UUID.fromString(g.getStringOr("uuid", new UUID(0, 0).toString())),
                        new GhostData(g.getStringOr("dimension", "minecraft:overworld"), anchor, portal));
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load ghosts.dat: " + e);
        }
    }

    public static void save() {
        if (server == null) return;
        try {
            Path file = dataFile(server);
            Files.createDirectories(file.getParent());
            ListTag list = new ListTag();
            GHOSTS.forEach((uuid, data) -> {
                CompoundTag t = new CompoundTag();
                t.putString("uuid", uuid.toString());
                t.putString("dimension", data.dimension());
                t.putInt("x", data.anchor().getX());
                t.putInt("y", data.anchor().getY());
                t.putInt("z", data.anchor().getZ());
                t.putInt("px", data.portal().getX());
                t.putInt("py", data.portal().getY());
                t.putInt("pz", data.portal().getZ());
                list.add(t);
            });
            CompoundTag root = new CompoundTag();
            root.put("ghosts", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save ghosts.dat: " + e);
        }
    }

    private static Path dataFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("ghosts.dat");
    }
}
