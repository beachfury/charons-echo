package com.charonsecho.npc;

import com.charonsecho.CharonConfig;
import com.charonsecho.CharonStorage;
import com.charonsecho.CharonsEcho;
import com.charonsecho.death.GraveManager;
import com.charonsecho.graveyard.GraveyardRules;
import com.charonsecho.item.CharonObol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Charon's Vault — where payment in kind ends up. The Vault Keeper (an evoker
 * who audits rather than casts) stands on a gilded blackstone plinth in the
 * graveyard; the wall behind him displays the newest tolled items in fixed
 * frames — trophies of the unprepared. Owners ransom their goods back from
 * the Keeper for obols or XP levels; unclaimed goods are forfeited after a
 * config window.
 *
 * The plinth is placed like the other staff posts: a gamemaster SNEAKS and
 * touches a placed gilded blackstone block with a Charon's Obol (the coin is
 * spent). Breaking the plinth dismisses the Keeper and closes the trade —
 * the ledger keeps its contents for when he is re-posted.
 */
public final class Vault {

    /** Entity tag carried by the Keeper and his display frames. */
    public static final String TAG = "charons_echo_vault";

    public static final class Entry {
        final UUID id;
        final UUID owner;
        final String ownerName;
        final ItemStack item;
        final long epochMillis;

        Entry(UUID id, UUID owner, String ownerName, ItemStack item, long epochMillis) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.item = item;
            this.epochMillis = epochMillis;
        }
    }

    /** Newest first — the wall and the ledger both read in this order. */
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static BlockPos stand;
    private static UUID keeperId;
    private static boolean ensurePending;
    private static MinecraftServer server;

    private Vault() {}

    public static void register() {
        // Posting the Keeper: a SNEAKING gamemaster touches gilded blackstone
        // with an obol. (An upright obol-touch is a Soul Gate consecration —
        // the crouch is what separates an altar from a door.)
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp) || !(world instanceof ServerLevel level)) {
                return InteractionResult.PASS;
            }
            if (!sp.isShiftKeyDown()) return InteractionResult.PASS;
            if (level.dimension() != CharonsEcho.GRAVEYARD_DIM) return InteractionResult.PASS;
            if (!level.getBlockState(hit.getBlockPos()).is(Blocks.GILDED_BLACKSTONE)) {
                return InteractionResult.PASS;
            }
            if (!CharonObol.isObol(sp.getItemInHand(hand))) return InteractionResult.PASS;
            if (!GraveyardRules.isGamemaster(sp)) {
                sp.sendSystemMessage(Component.literal("Only the Ferryman's masters may post the Keeper.")
                        .withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            sp.getItemInHand(hand).shrink(1);
            stand = hit.getBlockPos().above();
            ensurePending = true;
            save();
            level.playSound(null, stand, SoundEvents.EVOKER_CELEBRATE, SoundSource.AMBIENT, 0.8f, 0.7f);
            sp.sendSystemMessage(Component.literal(
                    "The coin is spent. The Vault Keeper accepts his post.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            return InteractionResult.SUCCESS;
        });

        // Any evoker in the land of the dead IS the Keeper (Broker doctrine:
        // no natural spawns, no ambiguity, stale clones answer correctly).
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM) return InteractionResult.PASS;
            if (entity instanceof ItemFrame frame && frame.entityTags().contains(TAG)) {
                // The trophies are Charon's until ransomed — nobody fiddles
                // with the frames, gamemasters excepted.
                return player instanceof ServerPlayer sp && GraveyardRules.isGamemaster(sp)
                        ? InteractionResult.PASS : InteractionResult.FAIL;
            }
            if (entity instanceof Mob mob && mob.getType() == EntityTypes.EVOKER) {
                if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
                openVault(sp);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.dimension() != CharonsEcho.GRAVEYARD_DIM) return InteractionResult.PASS;
            if (entity instanceof ItemFrame frame && frame.entityTags().contains(TAG)
                    && !(player instanceof ServerPlayer sp && GraveyardRules.isGamemaster(sp))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            if (srv.getTickCount() < 100) return;
            // Forfeiture is a slow clock — one sweep a minute is generous.
            if (srv.getTickCount() % 1200 == 0) expireSweep(srv);
            if (!ensurePending && srv.getTickCount() % 600 != 0) return;
            ensurePending = false;
            doEnsure(srv);
        });
    }

    /** A Vault stands (the plinth is set): payment in kind is on the table. */
    public static boolean active() {
        return stand != null;
    }

    // ---------------------------------------------------------------- the toll

    /**
     * Charon's eye for worth: material tier, then rarity, then the shine of
     * enchantment, then the weight of the stack. Deterministic, so players
     * can reason about what he will reach for.
     */
    public static int value(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        String id = stack.getItem().toString();
        int tier = id.contains("netherite") ? 60
                : id.contains("diamond") ? 40
                : id.contains("emerald") ? 35
                : id.contains("golden") || id.contains("gold_") ? 25
                : id.contains("chainmail") ? 22
                : id.contains("iron") ? 20
                : stack.getRarity().ordinal() * 15 + 5;
        int shine = stack.getEnchantments().size() * 12
                + (id.contains("enchanted_book") ? 30 : 0);
        return tier + shine + Math.min(stack.getCount(), 16);
    }

    /** The stack Charon would take from these goods — null when they hold nothing. */
    public static ItemStack mostValuable(List<ItemStack> items) {
        ItemStack best = null;
        int bestV = 0;
        for (ItemStack s : items) {
            int v = value(s);
            if (v > bestV) {
                bestV = v;
                best = s;
            }
        }
        return best;
    }

    /**
     * Payment in kind: the chosen stack leaves the grave for the Vault.
     * Returns false if the goods changed under the click (double-click race).
     */
    public static boolean toll(ServerPlayer player, GraveManager.Grave grave, ItemStack taken) {
        if (!grave.items.remove(taken)) return false;
        GraveManager.itemsChanged(grave);
        ENTRIES.add(0, new Entry(UUID.randomUUID(), player.getUUID(),
                player.getName().getString(), taken, System.currentTimeMillis()));
        save();
        if (player.level() instanceof ServerLevel level
                && level.dimension() == CharonsEcho.GRAVEYARD_DIM) {
            refreshWall(level);
        }
        player.sendSystemMessage(Component.literal(
                "Charon takes " + taken.getHoverName().getString()
                + " to his Vault. The Keeper will hear a ransom.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    // ---------------------------------------------------------------- the keeper

    private static void doEnsure(MinecraftServer srv) {
        ServerLevel graveyard = srv.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        if (stand != null) {
            graveyard.getChunk(stand.getX() >> 4, stand.getZ() >> 4);
            if (!graveyard.areEntitiesLoaded(ChunkPos.pack(stand.getX() >> 4, stand.getZ() >> 4))) {
                return;
            }
            // A broken plinth dismisses the Keeper; the ledger holds its goods.
            if (!graveyard.getBlockState(stand.below()).is(Blocks.GILDED_BLACKSTONE)) {
                stand = null;
                save();
            }
        }
        var keepers = graveyard.getEntitiesOfClass(Mob.class,
                AABB.ofSize(Vec3.atCenterOf(stand == null ? BlockPos.ZERO : stand),
                        stand == null ? 512 : 128, stand == null ? 128 : 96,
                        stand == null ? 512 : 128),
                m -> m.getType() == EntityTypes.EVOKER);
        if (stand == null) {
            for (Mob m : keepers) m.discard();
            for (ItemFrame f : graveyard.getEntitiesOfClass(ItemFrame.class,
                    AABB.ofSize(Vec3.atCenterOf(BlockPos.ZERO), 512, 128, 512))) {
                if (f.entityTags().contains(TAG)) f.discard();
            }
            return;
        }

        // One Keeper only: the recorded one if he lives, else the nearest.
        Mob keeper = null;
        for (Mob m : keepers) {
            if (keeperId != null && m.getUUID().equals(keeperId)) {
                keeper = m;
                break;
            }
        }
        if (keeper == null) {
            for (Mob m : keepers) {
                if (keeper == null || m.blockPosition().distSqr(stand)
                        < keeper.blockPosition().distSqr(stand)) {
                    keeper = m;
                }
            }
        }
        for (Mob m : keepers) {
            if (m != keeper) m.discard();
        }
        if (keeper == null) {
            keeper = (Mob) EntityTypes.EVOKER.create(graveyard, EntitySpawnReason.COMMAND);
            if (keeper == null) return;
            keeper.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            keeper.setCustomName(Component.literal("the Vault Keeper").withStyle(ChatFormatting.GOLD));
            keeper.setCustomNameVisible(true);
            keeper.setNoAi(true);
            keeper.setPermanentlyInvulnerable(true);
            keeper.setSilent(true);
            keeper.setPersistenceRequired();
            keeper.addTag(TAG);
            graveyard.addFreshEntity(keeper);
            System.out.println("[CharonsEcho] the Vault Keeper waits at " + stand.toShortString());
        } else if (keeper.blockPosition().distSqr(stand) > 2) {
            keeper.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        }
        if (keeperId == null || !keeper.getUUID().equals(keeperId)) {
            keeperId = keeper.getUUID();
            save();
        }
        face(graveyard, keeper);
        refreshWall(graveyard);
    }

    /** The Keeper faces the open side of his plinth, the wall at his back. */
    private static Direction openSide(ServerLevel level, BlockPos at) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = at.relative(dir);
            if (level.getBlockState(side).isAir() && level.getBlockState(side.above()).isAir()) {
                return dir;
            }
        }
        return Direction.SOUTH;
    }

    private static void face(ServerLevel level, Mob keeper) {
        float yaw = openSide(level, stand).toYRot();
        keeper.setYRot(yaw);
        keeper.setYBodyRot(yaw);
        keeper.setYHeadRot(yaw);
    }

    // ---------------------------------------------------------------- the wall

    /** Hang the newest goods on the wall behind the Keeper, fixed and untouchable. */
    private static void refreshWall(ServerLevel level) {
        if (stand == null) return;
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class,
                AABB.ofSize(Vec3.atCenterOf(stand), 33, 17, 33))) {
            if (f.entityTags().contains(TAG)) f.discard();
        }
        int limit = Math.min(CharonConfig.vaultDisplayLimit, ENTRIES.size());
        if (limit <= 0) return;
        Direction facing = openSide(level, stand);
        Direction wallDir = facing.getOpposite();
        Direction across = wallDir.getClockWise();

        int hung = 0;
        // Sweep the wall behind the plinth: nearest surface first, center-out,
        // eye-height rows first — the wall shapes the display, not a fixed grid.
        for (int depth = 1; depth <= 4 && hung < limit; depth++) {
            for (int dy : new int[]{1, 2, 0, 3}) {
                for (int lat : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4}) {
                    if (hung >= limit) break;
                    BlockPos support = stand.relative(wallDir, depth)
                            .relative(across, lat).above(dy);
                    BlockPos hang = support.relative(facing);
                    if (!level.getBlockState(support).isFaceSturdy(level, support, facing)) continue;
                    if (!level.getBlockState(hang).isAir()) continue;
                    if (!level.getEntitiesOfClass(ItemFrame.class,
                            new AABB(hang), f -> true).isEmpty()) continue;
                    ItemFrame frame = new ItemFrame(level, hang, facing);
                    frame.setItem(ENTRIES.get(hung).item.copy());
                    frame.setPermanentlyInvulnerable(true);
                    frame.setSilent(true);
                    frame.addTag(TAG);
                    level.addFreshEntity(frame);
                    hung++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the ransom

    private static void openVault(ServerPlayer viewer) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, viewer, false);
        gui.setTitle(Component.literal("Charon's Vault"));
        if (ENTRIES.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.GLASS_BOTTLE)
                    .setName(Component.literal("The Vault stands empty.")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
        }
        int slot = 0;
        for (Entry e : ENTRIES) {
            if (slot >= 54) break;
            boolean mine = e.owner.equals(viewer.getUUID());
            long daysLeft = CharonConfig.vaultExpiryDays
                    - (System.currentTimeMillis() - e.epochMillis) / 86_400_000L;
            GuiElementBuilder b = GuiElementBuilder.from(e.item.copy())
                    .addLoreLine(Component.empty())
                    .addLoreLine(Component.literal("Taken from " + e.ownerName + ", " + date(e.epochMillis))
                            .withStyle(ChatFormatting.GRAY))
                    .addLoreLine(Component.literal("Forfeit in " + Math.max(daysLeft, 0) + " day"
                            + (daysLeft == 1 ? "" : "s") + ".")
                            .withStyle(ChatFormatting.DARK_GRAY));
            if (mine) {
                b.addLoreLine(Component.literal("Ransom: " + CharonConfig.vaultRansomObols
                                + " obols or " + CharonConfig.vaultRansomLevels + " levels.")
                        .withStyle(ChatFormatting.DARK_AQUA))
                        .addLoreLine(Component.literal("Click to bargain.").withStyle(ChatFormatting.GRAY))
                        .glow()
                        .setCallback((i, t, a, g) -> {
                            g.close();
                            openRansom(viewer, e);
                        });
            } else {
                b.addLoreLine(Component.literal("Not yours to claim.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            }
            gui.setSlot(slot++, b);
        }
        gui.open();
    }

    private static void openRansom(ServerPlayer player, Entry e) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("The Keeper names his price"));
        gui.setSlot(4, GuiElementBuilder.from(e.item.copy()));
        gui.setSlot(11, new GuiElementBuilder(Items.ECHO_SHARD)
                .setName(Component.literal("Pay " + CharonConfig.vaultRansomObols + " obols")
                        .withStyle(ChatFormatting.DARK_AQUA))
                .setCallback((i, t, a, g) -> {
                    if (!consumeObols(player, CharonConfig.vaultRansomObols)) {
                        player.sendSystemMessage(Component.literal("The Keeper counts your coins and sneers.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    g.close();
                    release(player, e);
                }));
        gui.setSlot(15, new GuiElementBuilder(Items.EXPERIENCE_BOTTLE)
                .setName(Component.literal("Pay " + CharonConfig.vaultRansomLevels + " levels")
                        .withStyle(ChatFormatting.GREEN))
                .setCallback((i, t, a, g) -> {
                    if (player.experienceLevel < CharonConfig.vaultRansomLevels) {
                        player.sendSystemMessage(Component.literal("You have not lived enough to pay it.")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    player.setExperienceLevels(player.experienceLevel - CharonConfig.vaultRansomLevels);
                    g.close();
                    release(player, e);
                }));
        gui.open();
    }

    private static void release(ServerPlayer player, Entry e) {
        if (!ENTRIES.remove(e)) return; // already ransomed or forfeited
        save();
        player.getInventory().placeItemBackInInventory(e.item.copy(), net.minecraft.util.Prediction.SERVER_ONLY);
        if (player.level() instanceof ServerLevel level) {
            if (level.dimension() == CharonsEcho.GRAVEYARD_DIM) refreshWall(level);
            level.playSound(null, player.blockPosition(),
                    SoundEvents.EVOKER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 1.2f);
        }
        player.sendSystemMessage(Component.literal(
                "The Keeper releases " + e.item.getHoverName().getString() + ". Paid in full.")
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static boolean consumeObols(ServerPlayer player, int n) {
        var inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (CharonObol.isObol(inv.getItem(i))) have += inv.getItem(i).getCount();
        }
        if (have < n) return false;
        int left = n;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!CharonObol.isObol(s)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
        return true;
    }

    private static void expireSweep(MinecraftServer srv) {
        long cutoff = System.currentTimeMillis()
                - CharonConfig.vaultExpiryDays * 86_400_000L;
        if (ENTRIES.removeIf(e -> e.epochMillis < cutoff)) {
            save();
            ServerLevel graveyard = srv.getLevel(CharonsEcho.GRAVEYARD_DIM);
            if (graveyard != null && !graveyard.players().isEmpty()) refreshWall(graveyard);
        }
    }

    private static String date(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                .format(java.time.Instant.ofEpochMilli(epochMillis)
                        .atZone(java.time.ZoneId.systemDefault()));
    }

    // ---------------------------------------------------------------- persistence

    public static void load(MinecraftServer srv) {
        server = srv;
        ENTRIES.clear();
        stand = null;
        keeperId = null;
        ensurePending = true;
        Path file = dataFile(srv);
        if (!CharonStorage.hasData(file)) return;
        try {
            CompoundTag root = CharonStorage.read(file);
            var ops = RegistryOps.create(NbtOps.INSTANCE, srv.registryAccess());
            if (root.getInt("sx").isPresent()) {
                stand = new BlockPos(root.getIntOr("sx", 0), root.getIntOr("sy", 64), root.getIntOr("sz", 0));
            }
            String kid = root.getStringOr("keeper", "");
            if (!kid.isEmpty()) keeperId = UUID.fromString(kid);
            for (Tag t : root.getListOrEmpty("entries")) {
                if (!(t instanceof CompoundTag e)) continue;
                ItemStack.OPTIONAL_CODEC.parse(ops, e.get("item")).result().ifPresent(item ->
                        ENTRIES.add(new Entry(
                                UUID.fromString(e.getStringOr("id", UUID.randomUUID().toString())),
                                UUID.fromString(e.getStringOr("owner", new UUID(0, 0).toString())),
                                e.getStringOr("ownerName", "someone"),
                                item,
                                e.getLongOr("epoch", System.currentTimeMillis()))));
            }
            ENTRIES.sort((a, b) -> Long.compare(b.epochMillis, a.epochMillis));
        } catch (IOException e) {
            System.out.println("[CharonsEcho] failed to load vault.dat: " + e);
        }
    }

    private static void save() {
        if (server == null) return;
        CompoundTag root = new CompoundTag();
        var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        if (stand != null) {
            root.putInt("sx", stand.getX());
            root.putInt("sy", stand.getY());
            root.putInt("sz", stand.getZ());
        }
        if (keeperId != null) root.putString("keeper", keeperId.toString());
        ListTag list = new ListTag();
        for (Entry e : ENTRIES) {
            CompoundTag t = new CompoundTag();
            t.putString("id", e.id.toString());
            t.putString("owner", e.owner.toString());
            t.putString("ownerName", e.ownerName);
            t.putLong("epoch", e.epochMillis);
            ItemStack.OPTIONAL_CODEC.encodeStart(ops, e.item).result()
                    .ifPresent(enc -> t.put("item", enc));
            list.add(t);
        }
        root.put("entries", list);
        CharonStorage.write(dataFile(server), root);
    }

    private static Path dataFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("charons_echo").resolve("vault.dat");
    }
}
