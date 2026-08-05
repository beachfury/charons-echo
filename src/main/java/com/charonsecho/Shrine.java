package com.charonsecho;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * THE SPAWN SHRINE — the hand-built waystone dais (spawn_shrine.nbt, authored
 * in the Studio and shipped with the mod). /charon shrine pastes it where the
 * gamemaster stands, rotated to face them (Studio convention: a build's front
 * faces south). Raise one at the world spawn and one at the graveyard
 * arrival, consecrate its frame with an obol, and the two ends of the
 * crossing look like they were always meant to meet.
 */
public final class Shrine {

    private Shrine() {}

    public static int place(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        var opt = level.getServer().getStructureManager().get(
                Identifier.fromNamespaceAndPath(CharonsEcho.MOD_ID, "spawn_shrine"));
        if (opt.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "The shrine template is missing from this build.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        var template = opt.get();
        var size = template.getSize();
        BlockPos center = new BlockPos(player.getBlockX(),
                player.getBlockY() - 1, player.getBlockZ());
        Direction facing = Direction.fromYRot(player.getYRot());
        Rotation rotation = switch (facing) {
            case SOUTH -> Rotation.NONE;
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };

        // Clear standing room above the ground; the template brings its floor.
        for (int dx = -(size.getX() / 2 + 1); dx <= size.getX() / 2 + 1; dx++) {
            for (int dz = -(size.getZ() / 2 + 1); dz <= size.getZ() / 2 + 1; dz++) {
                for (int dy = 1; dy <= size.getY() + 2; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        BlockPos at = center.offset(-size.getX() / 2, 0, -size.getZ() / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setRotationPivot(new BlockPos(size.getX() / 2, 0, size.getZ() / 2));
        template.placeInWorld(level, at, at, settings,
                RandomSource.create(center.asLong()), 2);
        player.sendSystemMessage(Component.literal(
                "The shrine rises. Touch its frame with an obol, and the gate will breathe.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return 1;
    }
}
