package com.charonsecho;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * THE WAR BELOW THE MOON — an eternal three-sided war fought in Charon's Echo,
 * invisible to the living, joinable by the dead, and the THIRD way to pay
 * Charon: coin (obol), blood (XP toll), or TIME (service).
 *
 * Factions: the KEEPERS (golems, creakings; allays as couriers) defend the
 * yards; the RESTLESS (parched, bogged, stray) rise from the active grave
 * field; the HOLLOW WIND (vex, breeze) is an NPC-only wildcard. The war is
 * fought at the FRONT — the field currently receiving burials; filled fields
 * are settled ground forever.
 */
public final class War {

    public enum Faction { KEEPERS, RESTLESS, WIND }

    private static final String PHANTOM_MARKER = "charons_echo_phantom";
    private static final String TEAM_RESTLESS = "charon_restless";
    private static final String TEAM_WIND = "charon_wind";
    private static final String TEAM_KEEPERS = "charon_keepers"; // shared with the staff

    static final class Enlistment {
        Faction faction;
        long remainingTicks;
        boolean active;
    }

    private static final Map<UUID, Enlistment> ENLISTED = new ConcurrentHashMap<>();
    private static Path file;

    private War() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(War::tick);
    }

    // ---------------------------------------------------------------- factions

    /** Faction of any entity — null for civilians, the living, and everything else. */
    public static Faction factionOf(Entity e) {
        if (e instanceof ServerPlayer p) {
            Enlistment en = ENLISTED.get(p.getUUID());
            return en != null && en.active ? en.faction : null;
        }
        if (e == null) return null;
        EntityType<?> t = e.getType();
        if (t == EntityTypes.IRON_GOLEM || t == EntityTypes.CREAKING) return Faction.KEEPERS;
        if (t == EntityTypes.PARCHED || t == EntityTypes.BOGGED || t == EntityTypes.STRAY) {
            return Faction.RESTLESS;
        }
        if (t == EntityTypes.VEX || t == EntityTypes.BREEZE) return Faction.WIND;
        return null; // allay + sniffer are civilians; the war does not see them
    }

    public static boolean isActive(UUID uuid) {
        Enlistment en = ENLISTED.get(uuid);
        return en != null && en.active;
    }

    /** Is this player a valid target for this mob? (Living players never are.) */
    public static boolean isActiveEnemy(Mob mob, ServerPlayer player) {
        Faction pf = factionOf(player);
        Faction mf = factionOf(mob);
        return pf != null && mf != null && pf != mf;
    }

    // ---------------------------------------------------------------- damage matrix

    /**
     * The graveyard's one law of violence: the war may harm only itself.
     * Living players untouchable and harmless; civilians sacred; enlisted dead
     * fight enemy factions only. A lethal blow to an enlisted player DOWNS
     * them (respawn at muster + service penalty) — the dead cannot die twice.
     */
    public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        Entity attacker = source.getEntity();
        if (victim instanceof ServerPlayer vp) {
            Faction vf = factionOf(vp);
            if (vf == null) return false; // the living cannot be touched
            Faction af = factionOf(attacker);
            if (af == null || af == vf) return false;
            if (amount >= vp.getHealth()) {
                downed(vp, attacker);
                return false; // the blow lands as defeat, not death
            }
            return true;
        }
        Faction mf = factionOf(victim);
        if (mf == null) return false; // civilians, the Broker, everything else
        Faction af = factionOf(attacker);
        if (af == null || af == mf) return false;
        if (amount >= victim.getHealth() && attacker instanceof ServerPlayer ap && isActive(ap.getUUID())) {
            credit(ap);
        }
        return true;
    }

    // ---------------------------------------------------------------- ticking

    private static void tick(MinecraftServer server) {
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;

        if (server.getTickCount() % 20 == 0) {
            serviceTick(server, graveyard);
        }
        int front = frontField();
        if (front < 0) return;
        if (server.getTickCount() % 100 == 0) {
            musterArmies(graveyard, front);
        }
        if (server.getTickCount() % 40 == 0) {
            assignTargets(graveyard, front);
        }
    }

    /** The front: the newest field with graves that is not yet full. */
    static int frontField() {
        for (int i = GraveyardPlots.fieldCount() - 1; i >= 0; i--) {
            boolean hasGrave = false;
            for (GraveManager.Grave g : GraveManager.all()) {
                if (g.plotIndex >= 0 && g.plotIndex / 36 == i) { hasGrave = true; break; }
            }
            if (hasGrave) {
                return GraveyardPlots.fieldFull(i) ? -1 : i;
            }
        }
        return -1;
    }

    private static void serviceTick(MinecraftServer server, ServerLevel graveyard) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Enlistment en = ENLISTED.get(p.getUUID());
            if (en == null || !en.active) continue;
            if (p.level() != graveyard) continue; // the clock runs only at the war
            en.remainingTicks -= 20;
            if (en.remainingTicks <= 0) {
                serveOut(p);
                continue;
            }
            if (p.tickCount % 60 < 20) {
                long secs = en.remainingTicks / 20;
                p.sendOverlayMessage(Component.literal(
                        String.format("Service: %d:%02d — %s", secs / 60, secs % 60,
                                en.faction == Faction.KEEPERS ? "hold the yard" : "tear it down"))
                        .withStyle(en.faction == Faction.KEEPERS
                                ? ChatFormatting.AQUA : ChatFormatting.RED));
            }
        }
    }

    /** Keep the armies fielded at the front (only while someone is near). */
    private static void musterArmies(ServerLevel graveyard, int front) {
        BlockPos c = GraveyardPlots.fieldCenter(front);
        int ground = GraveyardTerrain.groundHeight(c.getX(), c.getZ());
        if (graveyard.getNearestPlayer(c.getX() + 0.5, ground, c.getZ() + 0.5, 96, false) == null) {
            return;
        }
        AABB box = new AABB(c.getX() - 64, ground - 32, c.getZ() - 64,
                c.getX() + 64, ground + 48, c.getZ() + 64);
        int restless = 0, wind = 0, allays = 0, sniffers = 0;
        for (Mob mob : graveyard.getEntitiesOfClass(Mob.class, box)) {
            Faction f = factionOf(mob);
            if (f == Faction.RESTLESS) restless++;
            else if (f == Faction.WIND) wind++;
            else if (mob.getType() == EntityTypes.ALLAY) allays++;
            else if (mob.getType() == EntityTypes.SNIFFER) sniffers++;
        }
        var rand = graveyard.getRandom();
        if (restless < CharonConfig.warRestlessCap) {
            // The Restless rise at the field's edge — from the ground they own.
            EntityType<?>[] pool = { EntityTypes.PARCHED, EntityTypes.BOGGED, EntityTypes.STRAY };
            spawnSoldier(graveyard, pool[rand.nextInt(pool.length)],
                    c.getX() + rand.nextInt(45) - 22, c.getZ() - 22 - rand.nextInt(12));
        }
        if (wind < CharonConfig.warWindCap) {
            spawnSoldier(graveyard, rand.nextBoolean() ? EntityTypes.VEX : EntityTypes.BREEZE,
                    c.getX() + rand.nextInt(90) - 45, c.getZ() + rand.nextInt(90) - 45);
        }
        if (allays < 1) {
            spawnSoldier(graveyard, EntityTypes.ALLAY, c.getX() + rand.nextInt(20) - 10,
                    c.getZ() + rand.nextInt(20) - 10);
        }
        if (sniffers < 1 && rand.nextInt(4) == 0) {
            spawnSoldier(graveyard, EntityTypes.SNIFFER, c.getX() + rand.nextInt(80) - 40,
                    c.getZ() + 30 + rand.nextInt(20));
        }
    }

    private static void spawnSoldier(ServerLevel level, EntityType<?> type, int x, int z) {
        int h = GraveyardTerrain.groundHeight(x, z);
        if (h < GraveyardTerrain.WATER_TOP) return;
        level.getChunk(x >> 4, z >> 4);
        Entity e = type.create(level, EntitySpawnReason.NATURAL);
        if (!(e instanceof Mob mob)) return;
        double y = type == EntityTypes.VEX || type == EntityTypes.ALLAY ? h + 3 : h + 1;
        mob.setPos(x + 0.5, y, z + 0.5);
        // War mobs are NOT persistent — walk away and the battle fades.
        level.addFreshEntity(mob);
    }

    /** Hand every idle fighter the nearest enemy. Civilians are invisible to the war. */
    private static void assignTargets(ServerLevel graveyard, int front) {
        BlockPos c = GraveyardPlots.fieldCenter(front);
        int ground = GraveyardTerrain.groundHeight(c.getX(), c.getZ());
        AABB box = new AABB(c.getX() - 80, ground - 32, c.getZ() - 80,
                c.getX() + 80, ground + 48, c.getZ() + 80);
        List<Mob> fighters = graveyard.getEntitiesOfClass(Mob.class, box,
                m -> factionOf(m) != null);
        List<ServerPlayer> soldiers = graveyard.players().stream()
                .filter(p -> isActive(p.getUUID())).toList();

        for (Mob mob : fighters) {
            Faction mf = factionOf(mob);
            LivingEntity target = mob.getTarget();
            boolean valid = target != null && target.isAlive()
                    && (target instanceof ServerPlayer tp
                            ? isActiveEnemy(mob, tp)
                            : factionOf(target) != null && factionOf(target) != mf);
            if (valid) continue;
            LivingEntity best = null;
            double bestD = 48 * 48;
            for (Mob other : fighters) {
                Faction of = factionOf(other);
                if (of == mf || other == mob) continue;
                double d = mob.distanceToSqr(other);
                if (d < bestD) { bestD = d; best = other; }
            }
            for (ServerPlayer p : soldiers) {
                if (factionOf(p) == mf) continue;
                double d = mob.distanceToSqr(p);
                if (d < bestD) { bestD = d; best = p; }
            }
            mob.setTarget(best); // null clears a stale target — also correct
        }
    }

    // ---------------------------------------------------------------- the choice

    /**
     * The moment of choice, at the stone: pay the fare, pay the toll, or take
     * the oath. (A grave whose fare was already paid never reaches this.)
     */
    public static void openChoice(ServerPlayer player, GraveManager.Grave grave, BlockPos clicked) {
        boolean hasObol = false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (CharonObol.isObol(inv.getItem(i))) { hasObol = true; break; }
        }
        int tollTake = grave.xpLevels * CharonConfig.tollXpPercent / 100;

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("Charon waits"));
        GuiElementBuilder fare = new GuiElementBuilder(Items.ECHO_SHARD)
                .setName(Component.literal("Pay the fare").withStyle(ChatFormatting.DARK_AQUA))
                .addLoreLine(Component.literal(hasObol
                        ? "One Charon's Obol." : "You carry no obol.")
                        .withStyle(hasObol ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
        if (hasObol) {
            fare.glow().setCallback((i, t, a, g) -> {
                if (PortalManager.consumeObol(player)) {
                    g.close();
                    player.sendSystemMessage(Component.literal("Charon accepts your fare.")
                            .withStyle(ChatFormatting.DARK_PURPLE));
                    PortalManager.resurrect(player, grave, clicked);
                }
            });
        }
        gui.setSlot(10, fare);
        gui.setSlot(12, new GuiElementBuilder(Items.EXPERIENCE_BOTTLE)
                .setName(Component.literal("Pay the toll").withStyle(ChatFormatting.GREEN))
                .addLoreLine(Component.literal("Charon takes " + CharonConfig.tollXpPercent
                        + "% of your memory (" + tollTake + " of " + grave.xpLevels + " levels).")
                        .withStyle(ChatFormatting.GRAY))
                .setCallback((i, t, a, g) -> {
                    grave.xpLevels -= tollTake;
                    GraveManager.save();
                    g.close();
                    player.sendSystemMessage(Component.literal(
                            "Charon takes his share of the memory of your deeds.")
                            .withStyle(ChatFormatting.DARK_PURPLE));
                    PortalManager.resurrect(player, grave, clicked);
                }));
        gui.setSlot(14, new GuiElementBuilder(Items.IRON_SWORD)
                .setName(Component.literal("Take the oath: KEEPERS").withStyle(ChatFormatting.AQUA))
                .addLoreLine(Component.literal("Serve the yard. Hold the graves.")
                        .withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("Serve your time, and passage is free.")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .hideDefaultTooltip()
                .setCallback((i, t, a, g) -> { g.close(); enlist(player, Faction.KEEPERS); }));
        gui.setSlot(16, new GuiElementBuilder(Items.BOW)
                .setName(Component.literal("Take the oath: RESTLESS").withStyle(ChatFormatting.RED))
                .addLoreLine(Component.literal("The dead deserve better. Tear it down.")
                        .withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("Serve your time, and passage is free.")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .hideDefaultTooltip()
                .setCallback((i, t, a, g) -> { g.close(); enlist(player, Faction.RESTLESS); }));
        gui.open();
    }

    // ---------------------------------------------------------------- enlistment

    private static void enlist(ServerPlayer player, Faction faction) {
        Enlistment en = ENLISTED.computeIfAbsent(player.getUUID(), u -> {
            Enlistment fresh = new Enlistment();
            fresh.remainingTicks = CharonConfig.warServiceMinutes * 1200L;
            return fresh;
        });
        en.faction = faction; // a paused clock resumes, whatever the banner
        en.active = true;
        save();

        giveKit(player, faction);
        joinTeam(player, faction);
        BlockPos muster = musterPoint(faction);
        if (muster != null) {
            ServerLevel graveyard = (ServerLevel) player.level();
            graveyard.getChunk(muster.getX() >> 4, muster.getZ() >> 4);
            player.teleportTo(graveyard, muster.getX() + 0.5, muster.getY(), muster.getZ() + 0.5,
                    java.util.Set.<Relative>of(), player.getYRot(), 0f, false);
        }
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 0.4f, 1.6f);
        player.sendSystemMessage(Component.literal(faction == Faction.KEEPERS
                ? "The yard accepts your oath. Hold the line, soldier of the moss."
                : "The Restless accept your oath. No stone left standing.")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Abandon service: back to an ordinary ghost; the clock pauses. */
    public static void quit(ServerPlayer player) {
        Enlistment en = ENLISTED.get(player.getUUID());
        if (en == null || !en.active) return;
        en.active = false;
        save();
        stripKit(player);
        leaveTeams(player);
        player.sendSystemMessage(Component.literal(
                "You lay down your arms. The clock holds your place.")
                .withStyle(ChatFormatting.GRAY));
    }

    /** Service complete: resurrection, free of charge. */
    private static void serveOut(ServerPlayer player) {
        var graveOpt = GraveManager.oldestUnclaimed(player.getUUID());
        stripKit(player);
        leaveTeams(player);
        ENLISTED.remove(player.getUUID());
        save();
        player.sendSystemMessage(Component.literal(
                "Your service is done. Charon owes you passage.")
                .withStyle(ChatFormatting.GOLD));
        if (graveOpt.isPresent() && graveOpt.get().plotIndex >= 0) {
            GraveManager.Grave grave = graveOpt.get();
            BlockPos arrive = GraveyardPlots.arrivalPos(grave.plotIndex);
            ServerLevel graveyard = (ServerLevel) player.level();
            graveyard.getChunk(arrive.getX() >> 4, arrive.getZ() >> 4);
            player.teleportTo(graveyard, arrive.getX() + 0.5, arrive.getY(), arrive.getZ() + 0.5,
                    java.util.Set.<Relative>of(), -90f, 0f, false);
            PortalManager.resurrect(player, grave, arrive);
        } else {
            GhostState.remove(player);
        }
    }

    /** A lethal blow in the war: downed, not dead — muster + penalty. */
    private static void downed(ServerPlayer player, Entity attacker) {
        Enlistment en = ENLISTED.get(player.getUUID());
        if (en == null) return;
        en.remainingTicks += CharonConfig.warDownedPenaltySeconds * 20L;
        save();
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        BlockPos muster = musterPoint(en.faction);
        if (muster != null && player.level() instanceof ServerLevel graveyard) {
            graveyard.getChunk(muster.getX() >> 4, muster.getZ() >> 4);
            player.teleportTo(graveyard, muster.getX() + 0.5, muster.getY(), muster.getZ() + 0.5,
                    java.util.Set.<Relative>of(), player.getYRot(), 0f, false);
            graveyard.playSound(null, muster, SoundEvents.SCULK_SHRIEKER_SHRIEK,
                    SoundSource.AMBIENT, 0.5f, 0.6f);
        }
        player.sendSystemMessage(Component.literal(
                "Downed. The war is patient — +" + CharonConfig.warDownedPenaltySeconds
                + "s of service.").withStyle(ChatFormatting.RED));
        if (attacker instanceof ServerPlayer ap && isActive(ap.getUUID())) {
            credit(ap);
        }
    }

    private static void credit(ServerPlayer player) {
        Enlistment en = ENLISTED.get(player.getUUID());
        if (en == null) return;
        en.remainingTicks -= CharonConfig.warKillCreditSeconds * 20L;
        player.sendOverlayMessage(Component.literal(
                "-" + CharonConfig.warKillCreditSeconds + "s").withStyle(ChatFormatting.GOLD));
        if (en.remainingTicks <= 0) serveOut(player);
    }

    private static BlockPos musterPoint(Faction faction) {
        int front = frontField();
        if (front < 0) {
            // No front: muster at the plateau's edge.
            return new BlockPos(8, GraveyardTerrain.groundHeight(8, 8) + 1, 20);
        }
        BlockPos c = GraveyardPlots.fieldCenter(front);
        int x = c.getX();
        int z = faction == Faction.KEEPERS ? c.getZ() + 17 : c.getZ() - 26;
        return new BlockPos(x, GraveyardTerrain.groundHeight(x, z) + 1, z);
    }

    // ---------------------------------------------------------------- kit & teams

    private static void giveKit(ServerPlayer player, Faction faction) {
        stripKit(player);
        if (faction == Faction.KEEPERS) {
            player.getInventory().add(phantom(new ItemStack(Items.IRON_SWORD)));
            player.getInventory().add(phantom(new ItemStack(Items.SHIELD)));
        } else {
            player.getInventory().add(phantom(new ItemStack(Items.BOW)));
            player.getInventory().add(phantom(new ItemStack(Items.ARROW, 64)));
            player.getInventory().add(phantom(new ItemStack(Items.STONE_SWORD)));
        }
    }

    private static ItemStack phantom(ItemStack stack) {
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal("Loaned by the war. It will not follow you back.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))));
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean(PHANTOM_MARKER, true));
        return stack;
    }

    static void stripKit(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            CustomData data = s.get(DataComponents.CUSTOM_DATA);
            if (data != null && data.copyTag().getBooleanOr(PHANTOM_MARKER, false)) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static void joinTeam(ServerPlayer player, Faction faction) {
        Scoreboard sb = player.level().getServer().getScoreboard();
        String name = faction == Faction.KEEPERS ? TEAM_KEEPERS : TEAM_RESTLESS;
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            team.setAllowFriendlyFire(false);
        }
        sb.addPlayerToTeam(player.getScoreboardName(), team);
    }

    private static void leaveTeams(ServerPlayer player) {
        Scoreboard sb = player.level().getServer().getScoreboard();
        for (String name : new String[]{TEAM_KEEPERS, TEAM_RESTLESS, TEAM_WIND}) {
            PlayerTeam team = sb.getPlayerTeam(name);
            if (team != null && team.getPlayers().contains(player.getScoreboardName())) {
                sb.removePlayerFromTeam(player.getScoreboardName(), team);
            }
        }
        // Back to the gray ranks of the ordinary dead (re-applied by GhostState).
    }

    /** Faction team for a war mob (used by the keeper sweep's enrollment). */
    static String teamFor(Mob mob) {
        Faction f = factionOf(mob);
        if (f == null) return null;
        return switch (f) {
            case KEEPERS -> TEAM_KEEPERS;
            case RESTLESS -> TEAM_RESTLESS;
            case WIND -> TEAM_WIND;
        };
    }

    /** Ghost state ended (resurrection or revive): all war state dissolves. */
    public static void onGhostEnd(ServerPlayer player) {
        if (ENLISTED.remove(player.getUUID()) != null) {
            save();
        }
        stripKit(player);
        leaveTeams(player);
    }

    public static String status(ServerPlayer player) {
        Enlistment en = ENLISTED.get(player.getUUID());
        if (en == null) return "You have taken no oath.";
        long secs = Math.max(0, en.remainingTicks) / 20;
        return (en.active ? "Enlisted with the " : "Oath paused with the ")
                + (en.faction == Faction.KEEPERS ? "KEEPERS" : "RESTLESS")
                + String.format(" — %d:%02d of service remain.", secs / 60, secs % 60);
    }

    // ---------------------------------------------------------------- persistence

    public static void load(MinecraftServer server) {
        ENLISTED.clear();
        file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("charons_echo").resolve("war.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            for (Tag t : root.getListOrEmpty("enlisted")) {
                if (!(t instanceof CompoundTag c)) continue;
                Enlistment en = new Enlistment();
                en.faction = "RESTLESS".equals(c.getStringOr("faction", ""))
                        ? Faction.RESTLESS : Faction.KEEPERS;
                en.remainingTicks = c.getLongOr("remaining", 0);
                en.active = c.getBooleanOr("active", false);
                ENLISTED.put(UUID.fromString(c.getStringOr("uuid", new UUID(0, 0).toString())), en);
            }
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load war.dat: " + e);
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            ListTag list = new ListTag();
            ENLISTED.forEach((uuid, en) -> {
                CompoundTag c = new CompoundTag();
                c.putString("uuid", uuid.toString());
                c.putString("faction", en.faction == Faction.RESTLESS ? "RESTLESS" : "KEEPERS");
                c.putLong("remaining", en.remainingTicks);
                c.putBoolean("active", en.active);
                list.add(c);
            });
            CompoundTag root = new CompoundTag();
            root.put("enlisted", list);
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to save war.dat: " + e);
        }
    }
}
