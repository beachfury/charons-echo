package com.charonsecho;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * /charon — two access tiers:
 *   gamemaster: everything
 *   gravekeeper (builder roster): studio, export, place, plot new/list
 * Player-facing gameplay needs no commands at all.
 */
public final class CharonCommands {

    private CharonCommands() {}

    /** Gamemaster only; sends an error and returns null otherwise. */
    private static ServerPlayer admin(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!GraveyardRules.isGamemaster(player)) {
            ctx.getSource().sendSystemMessage(Component.literal("Only the Ferryman's masters may do that.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return player;
    }

    /** Gamemaster or gravekeeper; sends an error and returns null otherwise. */
    private static ServerPlayer builder(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!Gravekeepers.canBuild(player)) {
            ctx.getSource().sendSystemMessage(Component.literal("Only gravekeepers may do that.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return player;
    }

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
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal(
                            "Charon's Echo — studio | export [name] | place <name> | plot new/approve/list | "
                            + "visit | back | obol [n] | revive [player] | builder add/remove/list | rebuild-graves")
                            .withStyle(ChatFormatting.GRAY));
                    return 1;
                })

                // ---- builder tier ----
                .then(Commands.literal("studio").executes(ctx -> {
                    ServerPlayer player = builder(ctx);
                    if (player == null) return 0;
                    StudioMode.enter(player);
                    return 1;
                }))
                .then(Commands.literal("export")
                        .executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            StudioMode.export(player, "");
                            return 1;
                        })
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            StudioMode.export(player, StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            StudioMode.place(player, StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("plot")
                        .then(Commands.literal("new")
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            ServerPlayer player = builder(ctx);
                                            if (player == null) return 0;
                                            return plotNew(player,
                                                    StringArgumentType.getString(ctx, "category"),
                                                    StringArgumentType.getString(ctx, "name"));
                                        }))))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    ServerPlayer player = admin(ctx);
                                    if (player == null) return 0;
                                    return plotApprove(player, StringArgumentType.getString(ctx, "name"));
                                })))
                        .then(Commands.literal("list").executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            String cats = StudioMode.CATEGORIES.stream()
                                    .map(c -> c.name() + " (" + c.w() + "x" + c.d() + " h" + c.h() + ")")
                                    .collect(Collectors.joining(", "));
                            player.sendSystemMessage(Component.literal("Categories: " + cats)
                                    .withStyle(ChatFormatting.GRAY));
                            for (StudioMode.DynamicPlot d : StudioMode.dynamicPlots()) {
                                player.sendSystemMessage(Component.literal("  " + d.plot.name() + " ["
                                        + d.category + "] by " + d.author
                                        + (d.approved ? " — APPROVED" : " — pending"))
                                        .withStyle(d.approved ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                            }
                            return 1;
                        })))

                // ---- admin tier ----
                .then(Commands.literal("obol")
                        .executes(ctx -> {
                            ServerPlayer player = admin(ctx);
                            return player == null ? 0 : giveObols(player, 1);
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    ServerPlayer player = admin(ctx);
                                    return player == null ? 0
                                            : giveObols(player, IntegerArgumentType.getInteger(ctx, "count"));
                                })))
                .then(Commands.literal("revive")
                        .executes(ctx -> {
                            ServerPlayer player = admin(ctx);
                            if (player == null) return 0;
                            if (!DeathHandler.revive(player)) {
                                ctx.getSource().sendSystemMessage(Component.literal(
                                        "No ghost state or unclaimed grave.").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })
                        .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                            ServerPlayer self = admin(ctx);
                            if (self == null) return 0;
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            if (!DeathHandler.revive(target)) {
                                ctx.getSource().sendSystemMessage(Component.literal(
                                        "No ghost state or unclaimed grave for "
                                        + target.getName().getString() + ".").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            return 1;
                        })))
                .then(Commands.literal("builder")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                    ServerPlayer self = admin(ctx);
                                    if (self == null) return 0;
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    boolean added = Gravekeepers.add(target.getUUID());
                                    self.sendSystemMessage(Component.literal(added
                                            ? target.getName().getString() + " joins the gravekeepers."
                                            : "Already a gravekeeper.").withStyle(ChatFormatting.GRAY));
                                    return added ? 1 : 0;
                                })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                    ServerPlayer self = admin(ctx);
                                    if (self == null) return 0;
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    boolean removed = Gravekeepers.remove(target.getUUID());
                                    self.sendSystemMessage(Component.literal(removed
                                            ? target.getName().getString() + " leaves the gravekeepers."
                                            : "Not a gravekeeper.").withStyle(ChatFormatting.GRAY));
                                    return removed ? 1 : 0;
                                }))))
                .then(Commands.literal("rebuild-graves").executes(ctx -> {
                    ServerPlayer player = admin(ctx);
                    if (player == null) return 0;
                    ServerLevel graveyard = player.level().getServer().getLevel(CharonsEcho.GRAVEYARD_DIM);
                    if (graveyard == null) return 0;
                    int count = GraveyardPlots.rebuildAll(graveyard);
                    player.sendSystemMessage(Component.literal(
                            "Rebuilt " + count + " graves from the records.")
                            .withStyle(ChatFormatting.GREEN));
                    return count;
                }))
                .then(Commands.literal("visit").executes(ctx -> {
                    ServerPlayer player = admin(ctx);
                    if (player == null) return 0;
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
                .then(Commands.literal("back").executes(ctx -> {
                    ServerPlayer player = builder(ctx);
                    if (player == null) return 0;
                    StudioMode.restoreMode(player);
                    ServerLevel overworld = player.level().getServer().overworld();
                    var spawn = overworld.getRespawnData().pos();
                    player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                            Set.<Relative>of(), 0f, 0f, false);
                    return 1;
                })));
    }

    private static int plotNew(ServerPlayer player, String category, String name) {
        ServerLevel studio = player.level().getServer().getLevel(CharonsEcho.STUDIO_DIM);
        if (studio == null) return 0;
        StudioMode.StudioPlot plot = StudioMode.createPlot(studio, category, name,
                player.getName().getString());
        if (plot == null) {
            player.sendSystemMessage(Component.literal(
                    "Bad category or name taken. Categories: " + StudioMode.CATEGORIES.stream()
                            .map(StudioMode.Category::name).collect(Collectors.joining(", ")))
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int y = studio.getHeight(Heightmap.Types.MOTION_BLOCKING, plot.x0(), plot.z0());
        player.teleportTo(studio, plot.x0() - 1.5, y, plot.z0() - 1.5, Set.<Relative>of(), 45f, 0f, false);
        player.sendSystemMessage(Component.literal(
                "Plot '" + name + "' staked out (" + plot.w() + "x" + plot.d() + ", max h " + plot.h()
                + "). Build, then /charon export — an admin approves it with /charon plot approve " + name + ".")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int plotApprove(ServerPlayer admin, String name) {
        StudioMode.DynamicPlot d = StudioMode.findDynamic(name);
        if (d == null) {
            admin.sendSystemMessage(Component.literal("No builder plot named '" + name + "'.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        var manager = admin.level().getServer().getStructureManager();
        boolean exported = manager.get(net.minecraft.resources.Identifier
                .fromNamespaceAndPath(CharonsEcho.MOD_ID, name)).isPresent();
        StudioMode.markApproved(name);
        admin.sendSystemMessage(Component.literal("'" + name + "' approved for generation"
                + (exported ? "." : " — but no exported template found yet; it activates once exported."))
                .withStyle(exported ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return 1;
    }
}
