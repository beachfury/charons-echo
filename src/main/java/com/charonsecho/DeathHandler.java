package com.charonsecho;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The moment of death: Charon takes everything. The real death is cancelled
 * (no death screen, no drops, no respawn) — the player becomes a ghost on the
 * spot, and a grave record holds their items + XP until reclaimed.
 */
public final class DeathHandler {

    private static final EquipmentSlot[] TAKEN_EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private DeathHandler() {}

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            ServerLevel level = (ServerLevel) player.level();

            // Vanilla death in our own dimensions (shouldn't happen once world
            // rules land) and under keepInventory.
            if (level.dimension() == CharonsEcho.GRAVEYARD_DIM
                    || level.dimension() == CharonsEcho.STUDIO_DIM) return true;
            if (level.getGameRules().get(GameRules.KEEP_INVENTORY)) return true;
            if (GhostState.isGhost(player.getUUID())) return true; // safety: ghosts are invulnerable anyway

            // The world still learns of the death.
            Component deathMessage = source.getLocalizedDeathMessage(player);
            level.getServer().getPlayerList().broadcastSystemMessage(deathMessage, false);

            // Charon takes the goods — except obols, which are soul-bound and
            // stay with the ghost to pay the Ferryman.
            List<ItemStack> taken = new ArrayList<>();
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && !CharonObol.isObol(stack)) {
                    taken.add(stack.copy());
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
            for (EquipmentSlot slot : TAKEN_EQUIPMENT) {
                ItemStack stack = player.getItemBySlot(slot);
                if (!stack.isEmpty() && !CharonObol.isObol(stack)) {
                    taken.add(stack.copy());
                    player.setItemSlot(slot, ItemStack.EMPTY);
                }
            }

            int xpLevels = player.experienceLevel;
            float xpProgress = player.experienceProgress;
            player.setExperienceLevels(0);
            player.setExperiencePoints(0);

            BlockPos deathPos = player.blockPosition();
            GraveManager.Grave grave = new GraveManager.Grave(
                    UUID.randomUUID(), player.getUUID(), player.getName().getString(),
                    level.dimension().identifier().toString(), deathPos,
                    deathMessage.getString(),
                    level.getServer().overworld().getGameTime(),
                    xpLevels, xpProgress, taken, false);
            grave.epochMillis = System.currentTimeMillis();
            GraveManager.add(grave);

            // Death is refused; the body lies in state (the wake), then the
            // ghost rises where it fell.
            player.setHealth(player.getMaxHealth());
            player.setRemainingFireTicks(0);
            player.removeAllEffects();
            DeathWake.begin(player, grave);

            level.playSound(null, deathPos, SoundEvents.BELL_RESONATE, SoundSource.AMBIENT, 1.0f, 0.6f);
            player.sendSystemMessage(Component.literal(
                    "Death is not the end. Charon has carried your possessions to the graveyard.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            return false;
        });
    }

    /** Restore the oldest unclaimed grave to its owner and lift the ghost state. */
    public static boolean revive(ServerPlayer player) {
        var grave = GraveManager.oldestUnclaimed(player.getUUID());
        if (grave.isEmpty() && !GhostState.isGhost(player.getUUID())) return false;
        grave.ifPresent(g -> {
            for (ItemStack stack : g.items) {
                player.getInventory().placeItemBackInInventory(stack.copy());
            }
            player.setExperienceLevels(g.xpLevels);
            player.experienceProgress = g.xpProgress;
            g.claimed = true;
            GraveManager.save();
        });
        if (GhostState.isGhost(player.getUUID())) {
            GhostState.remove(player);
        }
        player.sendSystemMessage(Component.literal("Your echo rejoins the living.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }
}
