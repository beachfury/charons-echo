package com.charonsecho;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.sgui.api.gui.SimpleGui;
import eu.pb4.sgui.api.elements.GuiElementBuilder;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The wake: for up to 60 seconds after death the body lies in state. The dead
 * player sees the death screen (sgui) with a "Rise as a Ghost" button; anyone
 * may drop an Echo Shard onto the body to pay Charon's fare before the ghost
 * rises. Rising happens on button click, on closing the screen, on timeout,
 * or on disconnect — whichever comes first.
 */
public final class DeathWake {

    private static final int WAKE_TICKS = 20 * 60;

    private record Wake(UUID graveId, int startedTick) {}

    private static final Map<UUID, Wake> WAKES = new ConcurrentHashMap<>();

    private DeathWake() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(DeathWake::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Never leave a wake unresolved — the ghost must exist before the save.
            ServerPlayer player = handler.getPlayer();
            if (WAKES.containsKey(player.getUUID())) {
                rise(player);
            }
        });
    }

    public static boolean isLying(UUID uuid) {
        return WAKES.containsKey(uuid);
    }

    public static void begin(ServerPlayer player, GraveManager.Grave grave) {
        WAKES.put(player.getUUID(), new Wake(grave.id, player.tickCount));
        player.setInvulnerable(true);
        openGui(player, grave);
    }

    private static void openGui(ServerPlayer player, GraveManager.Grave grave) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, player, false) {
            @Override
            public void onManualClose() {
                rise(player);
            }

            @Override
            public void onPlayerClose(boolean screenIsClosing) {
                rise(player);
            }
        };
        gui.setTitle(Component.literal("You have died"));
        gui.setSlot(11, new GuiElementBuilder(Items.SKELETON_SKULL)
                .setName(Component.literal(grave.causeLine).withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("Charon has taken your possessions")
                        .withStyle(ChatFormatting.DARK_PURPLE))
                .addLoreLine(Component.literal("to the graveyard.")
                        .withStyle(ChatFormatting.DARK_PURPLE))
                .build());
        gui.setSlot(13, new GuiElementBuilder(Items.SOUL_LANTERN)
                .setName(Component.literal("Rise as a Ghost").withStyle(ChatFormatting.AQUA))
                .addLoreLine(Component.literal("A soul-fire portal waits beside")
                        .withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("your body. Step through it.")
                        .withStyle(ChatFormatting.GRAY))
                .glow()
                .setCallback((index, type, action, g) -> g.close())
                .build());
        gui.setSlot(15, new GuiElementBuilder(Items.ECHO_SHARD)
                .setName(Component.literal("Charon's Fare").withStyle(ChatFormatting.DARK_AQUA))
                .addLoreLine(Component.literal("While your body lies here, anyone")
                        .withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("may drop a Charon's Obol on it")
                        .withStyle(ChatFormatting.GRAY))
                .addLoreLine(Component.literal("to pay your passage.")
                        .withStyle(ChatFormatting.GRAY))
                .build());
        gui.open();
    }

    private static void tick(MinecraftServer server) {
        if (WAKES.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Wake wake = WAKES.get(player.getUUID());
            if (wake == null) continue;

            // A body lying in state, marked by gentle soul smoke.
            if (player.tickCount % 8 == 0 && player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SCULK_SOUL,
                        player.getX(), player.getY() + 0.4, player.getZ(),
                        2, 0.3, 0.1, 0.3, 0.01);
            }

            // Obol donation: one dropped within a few blocks pays the fare.
            GraveManager.byId(wake.graveId()).ifPresent(grave -> {
                if (!grave.farePaid && player.level() instanceof ServerLevel level) {
                    AABB box = AABB.ofSize(player.position().add(0, 0.5, 0), 7, 5, 7);
                    for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
                        if (CharonObol.isObol(item.getItem())) {
                            item.getItem().shrink(1);
                            if (item.getItem().isEmpty()) item.discard();
                            grave.farePaid = true;
                            GraveManager.save();
                            level.sendParticles(ParticleTypes.SOUL,
                                    player.getX(), player.getY() + 1, player.getZ(),
                                    20, 0.4, 0.6, 0.4, 0.02);
                            server.getPlayerList().broadcastSystemMessage(Component.literal(
                                    "An obol settles over " + grave.ownerName
                                    + "'s body — the Ferryman's fare is paid.")
                                    .withStyle(ChatFormatting.DARK_PURPLE), false);
                            break;
                        }
                    }
                }
            });

            if (player.tickCount - wake.startedTick() >= WAKE_TICKS) {
                rise(player);
            }
        }
    }

    /** End the wake: the ghost rises where the body fell. */
    static void rise(ServerPlayer player) {
        Wake wake = WAKES.remove(player.getUUID());
        if (wake == null) return;
        player.setInvulnerable(false);
        GraveManager.byId(wake.graveId()).ifPresent(grave ->
                GhostState.apply(player, grave.pos));
        player.sendSystemMessage(Component.literal(
                "Step into the soul-fire portal to follow your possessions.")
                .withStyle(ChatFormatting.GRAY));
    }
}
