package com.charonsecho;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Broker — a wandering trader who stopped wandering a very long time ago.
 * He stands on the church plateau in Charon's Echo and sells exactly one
 * thing: Stygian Seeds, for emeralds. Right-click opens a small sgui shop
 * (vanilla + Bedrock friendly) instead of the vanilla merchant screen.
 */
public final class Broker {

    /** Where he waits, on the plateau where the church will one day stand. */
    private static final BlockPos STAND = new BlockPos(8, 0, 8);

    private Broker() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (Orchard.brokerId == null || !entity.getUUID().equals(Orchard.brokerId)) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            openShop(sp);
            return InteractionResult.SUCCESS;
        });
    }

    /** Ensure the Broker stands at his post (server start; also /charon orchard broker). */
    public static void ensure(MinecraftServer server) {
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        int y = GraveyardTerrain.groundHeight(STAND.getX(), STAND.getZ()) + 1;
        BlockPos at = new BlockPos(STAND.getX(), y, STAND.getZ());
        graveyard.getChunk(at.getX() >> 4, at.getZ() >> 4);

        if (Orchard.brokerId != null) {
            Entity existing = graveyard.getEntity(Orchard.brokerId);
            if (existing != null && existing.isAlive()) return;
        }
        WanderingTrader trader = EntityTypes.WANDERING_TRADER.create(graveyard, EntitySpawnReason.COMMAND);
        if (trader == null) return;
        trader.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        trader.setCustomName(Component.literal("the Broker").withStyle(ChatFormatting.GOLD));
        trader.setCustomNameVisible(true);
        trader.setNoAi(true);
        trader.setInvulnerable(true);
        trader.setSilent(true);
        trader.setPersistenceRequired();
        graveyard.addFreshEntity(trader);
        Orchard.brokerId = trader.getUUID();
        Orchard.save();
        System.out.println("[CharonsEcho] the Broker waits at " + at.toShortString());
    }

    /** Move the Broker to a new post (admin nicety while the church is unbuilt). */
    public static void moveTo(ServerPlayer admin) {
        ServerLevel level = (ServerLevel) admin.level();
        Entity existing = Orchard.brokerId == null ? null : level.getEntity(Orchard.brokerId);
        if (existing instanceof WanderingTrader trader) {
            trader.setPos(admin.getX(), admin.getY(), admin.getZ());
        } else {
            WanderingTrader trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.COMMAND);
            if (trader == null) return;
            trader.setPos(admin.getX(), admin.getY(), admin.getZ());
            trader.setCustomName(Component.literal("the Broker").withStyle(ChatFormatting.GOLD));
            trader.setCustomNameVisible(true);
            trader.setNoAi(true);
            trader.setInvulnerable(true);
            trader.setSilent(true);
            trader.setPersistenceRequired();
            level.addFreshEntity(trader);
            Orchard.brokerId = trader.getUUID();
            Orchard.save();
        }
    }

    private static void openShop(ServerPlayer player) {
        int price = CharonConfig.orchardSeedPrice;
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false);
        gui.setTitle(Component.literal("the Broker"));

        ItemStack display = StygianItems.seed(1);
        gui.setSlot(13, GuiElementBuilder.from(display)
                .addLoreLine(Component.empty())
                .addLoreLine(Component.literal("Price: " + price + " emeralds")
                        .withStyle(ChatFormatting.GREEN))
                .addLoreLine(Component.literal("Click to buy.").withStyle(ChatFormatting.GRAY))
                .setCallback((index, type, action, g) -> {
                    if (buy(player, price)) {
                        g.close();
                    }
                }));
        gui.setSlot(4, new GuiElementBuilder(Items.EMERALD)
                .setName(Component.literal("He does not haggle.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
        gui.open();
    }

    private static boolean buy(ServerPlayer player, int price) {
        var inv = player.getInventory();
        int have = 0;
        for (ItemStack s : inv.getNonEquipmentItems()) {
            if (s.is(Items.EMERALD)) have += s.getCount();
        }
        if (have < price) {
            player.sendSystemMessage(Component.literal(
                    "The Broker looks through you. (" + price + " emeralds.)")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        int left = price;
        for (ItemStack s : inv.getNonEquipmentItems()) {
            if (left <= 0) break;
            if (!s.is(Items.EMERALD)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
        ItemStack seed = StygianItems.seed(1);
        if (!inv.add(seed)) {
            player.drop(seed, false);
        }
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 1f, 0.8f);
        player.sendSystemMessage(Component.literal("\"Plant it, and be patient.\"")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
        return true;
    }
}
