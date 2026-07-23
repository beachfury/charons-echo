package com.charonsecho;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/** /charon — admin + authoring commands. Player-facing gameplay needs no commands. */
public final class CharonCommands {

    private CharonCommands() {}

    private static int giveObols(ServerPlayer player, int count) {
        player.getInventory().placeItemBackInInventory(CharonObol.create(count));
        player.sendSystemMessage(Component.literal(
                count == 1 ? "An obol settles into your palm — the Ferryman's fare."
                           : count + " obols settle into your palm — the Ferryman's fare.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return count;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("charon")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "Charon's Echo — /charon studio | export [name] | place <name> | visit | back | obol [n] | revive [player]")
                            .withStyle(ChatFormatting.GRAY));
                    return 1;
                })
                .then(Commands.literal("studio").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    StudioMode.enter(player);
                    return 1;
                }))
                .then(Commands.literal("export")
                        .executes(ctx -> {
                            StudioMode.export(ctx.getSource().getPlayerOrException(), "");
                            return 1;
                        })
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            StudioMode.export(ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            StudioMode.place(ctx.getSource().getPlayerOrException(),
                                    StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("visit").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    ServerLevel graveyard = player.level().getServer().getLevel(CharonsEcho.GRAVEYARD_DIM);
                    if (graveyard == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("Graveyard dimension is missing.")
                                .withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    graveyard.getChunk(0, 0);
                    int y = graveyard.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8);
                    player.teleportTo(graveyard, 8.5, y, 8.5, Set.<Relative>of(), 0f, 0f, false);
                    player.sendSystemMessage(Component.literal("You stand on hallowed ground.")
                            .withStyle(ChatFormatting.DARK_PURPLE));
                    return 1;
                }))
                .then(Commands.literal("obol")
                        .executes(ctx -> giveObols(ctx.getSource().getPlayerOrException(), 1))
                        .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> giveObols(ctx.getSource().getPlayerOrException(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("revive")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!DeathHandler.revive(player)) {
                                ctx.getSource().sendSystemMessage(Component.literal(
                                        "No ghost state or unclaimed grave.").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })
                        .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = net.minecraft.commands.arguments.EntityArgument
                                            .getPlayer(ctx, "player");
                                    if (!DeathHandler.revive(target)) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "No ghost state or unclaimed grave for "
                                                + target.getName().getString() + ".")
                                                .withStyle(ChatFormatting.RED));
                                        return 0;
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("back").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    StudioMode.restoreMode(player);
                    ServerLevel overworld = player.level().getServer().overworld();
                    var spawn = overworld.getRespawnData().pos();
                    player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, false);
                    return 1;
                })));
    }
}
