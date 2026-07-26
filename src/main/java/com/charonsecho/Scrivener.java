package com.charonsecho;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * THE SCRIVENER — a dead clerk who never stopped working, posted beside the
 * church lectern. He hands the living blank books, FREE: the mod wants
 * stories, and the fee was already paid in death. Any zombie villager in
 * Charon's Echo answers as the Scrivener (nothing else of his kind can
 * exist here), same doctrine as the Broker.
 */
public final class Scrivener {

    private Scrivener() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(entity instanceof ZombieVillager)
                    || world.dimension() != CharonsEcho.GRAVEYARD_DIM) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
            giveBook(sp);
            return InteractionResult.SUCCESS;
        });
        // Same async-entity doctrine as the Broker: ensure late, repeat, de-dupe.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() < 120) return;
            if (server.getTickCount() % 600 != 0) return;
            ensure(server);
        });
    }

    private static void giveBook(ServerPlayer player) {
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponents.ITEM_NAME, Component.literal("Your Story")
                .withStyle(ChatFormatting.GRAY));
        book.set(DataComponents.LORE, new ItemLore(java.util.List.of(
                Component.literal("Write how it ended — or how it truly was.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("Touch your headstone holding it,")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal("and the stone will keep it forever.")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))));
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1f, 0.7f);
        player.sendSystemMessage(Component.literal(
                "\"Every stone deserves more than a name.\"")
                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
    }

    private static void ensure(MinecraftServer server) {
        ServerLevel graveyard = server.getLevel(CharonsEcho.GRAVEYARD_DIM);
        if (graveyard == null) return;
        BlockPos at = post(graveyard);
        graveyard.getChunk(at.getX() >> 4, at.getZ() >> 4);
        var clerks = graveyard.getEntitiesOfClass(ZombieVillager.class,
                AABB.ofSize(Vec3.atCenterOf(at), 128, 96, 128));
        ZombieVillager keeper = null;
        for (ZombieVillager z : clerks) {
            if (keeper == null) keeper = z; else z.discard();
        }
        if (keeper != null) {
            if (keeper.blockPosition().distSqr(at) > 2) {
                keeper.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            }
            return;
        }
        ZombieVillager clerk = EntityTypes.ZOMBIE_VILLAGER.create(graveyard, EntitySpawnReason.COMMAND);
        if (clerk == null) return;
        clerk.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        clerk.setCustomName(Component.literal("the Scrivener").withStyle(ChatFormatting.DARK_AQUA));
        clerk.setCustomNameVisible(true);
        clerk.setNoAi(true);
        clerk.setInvulnerable(true);
        clerk.setSilent(true);
        clerk.setPersistenceRequired();
        graveyard.addFreshEntity(clerk);
        System.out.println("[CharonsEcho] the Scrivener takes his desk at " + at.toShortString());
    }

    /** His desk: beside the ledger lectern, in the first open floor space. */
    private static BlockPos post(ServerLevel graveyard) {
        BlockPos lectern = Church.ledgerMarker();
        if (lectern != null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = lectern.relative(dir);
                if (graveyard.getBlockState(side).isAir()
                        && graveyard.getBlockState(side.above()).isAir()) {
                    return side;
                }
            }
        }
        int z = 20;
        return new BlockPos(4, GraveyardTerrain.groundHeight(4, z) + 1, z);
    }
}
