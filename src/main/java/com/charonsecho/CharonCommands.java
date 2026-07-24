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

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> CATEGORY_SUGGESTIONS =
            (ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    StudioMode.CATEGORIES.stream().map(StudioMode.Category::name), b);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> PLOT_SUGGESTIONS =
            (ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    StudioMode.allPlots().stream().map(StudioMode.StudioPlot::name), b);
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SET_SUGGESTIONS =
            (ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    StudioSets.all().stream().map(s -> s.name), b);

    private static int err(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> PENDING_SUGGESTIONS =
            (ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    StudioMode.dynamicPlots().stream().filter(d -> !d.approved)
                            .map(d -> d.plot.name()), b);

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

    /** The Book of the Dead: every death, newest first; entries with interred
     *  books open them read-only. Open to ALL players. */
    private static void openLedger(ServerPlayer player) {
        var gui = new eu.pb4.sgui.api.gui.SimpleGui(
                net.minecraft.world.inventory.MenuType.GENERIC_9x6, player, false);
        gui.setTitle(Component.literal("The Book of the Dead"));
        var graves = new java.util.ArrayList<>(GraveManager.all());
        graves.removeIf(g -> g.plotIndex < 0);
        graves.sort((a, b) -> Long.compare(b.gameTime, a.gameTime));
        int slot = 0;
        for (GraveManager.Grave grave : graves) {
            if (slot >= 54) break;
            String date = grave.epochMillis > 0
                    ? new java.text.SimpleDateFormat("MMM d, yyyy").format(new java.util.Date(grave.epochMillis))
                    : "Day " + (grave.gameTime / 24000L);
            var builder = new eu.pb4.sgui.api.elements.GuiElementBuilder(
                    grave.book != null ? net.minecraft.world.item.Items.WRITTEN_BOOK
                                       : net.minecraft.world.item.Items.SKELETON_SKULL)
                    .setName(Component.literal(grave.ownerName)
                            .withStyle(grave.claimed ? ChatFormatting.GRAY : ChatFormatting.WHITE))
                    .addLoreLine(Component.literal(date).withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal(grave.causeLine).withStyle(ChatFormatting.GRAY));
            if (grave.book != null) {
                builder.addLoreLine(Component.literal("Click to read their last words")
                        .withStyle(ChatFormatting.DARK_PURPLE));
                builder.glow();
                builder.setCallback((i, t, a, g) -> {
                    g.close();
                    GraveBooks.open(player, grave);
                });
            }
            gui.setSlot(slot++, builder.build());
        }
        if (slot == 0) {
            gui.setSlot(22, new eu.pb4.sgui.api.elements.GuiElementBuilder(
                    net.minecraft.world.item.Items.BONE)
                    .setName(Component.literal("No one has died yet.").withStyle(ChatFormatting.GRAY))
                    .build());
        }
        gui.open();
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

                // ---- everyone ----
                .then(Commands.literal("ledger").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    openLedger(player);
                    return 1;
                }))

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
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(PLOT_SUGGESTIONS)
                                .executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            StudioMode.export(player, StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(PLOT_SUGGESTIONS)
                                .executes(ctx -> {
                            ServerPlayer player = builder(ctx);
                            if (player == null) return 0;
                            StudioMode.place(player, StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                .then(Commands.literal("plot")
                        .then(Commands.literal("new")
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .suggests(CATEGORY_SUGGESTIONS)
                                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                            ServerPlayer player = builder(ctx);
                                            if (player == null) return 0;
                                            return plotNew(player,
                                                    StringArgumentType.getString(ctx, "category"),
                                                    StringArgumentType.getString(ctx, "name"));
                                        }))))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(PENDING_SUGGESTIONS)
                                        .executes(ctx -> {
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

                .then(Commands.literal("set")
                        .then(Commands.literal("new")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer p = builder(ctx);
                                            return p == null ? 0 : setNew(p,
                                                    StringArgumentType.getString(ctx, "name"),
                                                    StudioSets.defaultSize());
                                        })
                                        .then(Commands.argument("size", IntegerArgumentType.integer(32, 1024))
                                                .executes(ctx -> {
                                                    ServerPlayer p = builder(ctx);
                                                    return p == null ? 0 : setNew(p,
                                                            StringArgumentType.getString(ctx, "name"),
                                                            IntegerArgumentType.getInteger(ctx, "size"));
                                                }))))
                        .then(Commands.literal("trust")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SET_SUGGESTIONS)
                                        .executes(ctx -> {
                                            ServerPlayer p = admin(ctx);
                                            if (p == null) return 0;
                                            StudioSets.SetInfo s = StudioSets.get(StringArgumentType.getString(ctx, "name"));
                                            if (s == null) return err(p, "No such set.");
                                            s.trusted = true;
                                            StudioSets.save();
                                            p.sendSystemMessage(Component.literal("Set '" + s.name
                                                    + "' is now trusted — only " + s.stewardName
                                                    + " and their invitees may build there.")
                                                    .withStyle(ChatFormatting.GREEN));
                                            return 1;
                                        })))
                        .then(Commands.literal("invite")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SET_SUGGESTIONS)
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .executes(ctx -> {
                                                    ServerPlayer p = builder(ctx);
                                                    if (p == null) return 0;
                                                    StudioSets.SetInfo s = StudioSets.get(StringArgumentType.getString(ctx, "name"));
                                                    if (s == null) return err(p, "No such set.");
                                                    if (!p.getUUID().equals(s.steward) && !GraveyardRules.isGamemaster(p)) {
                                                        return err(p, "Only the steward may invite.");
                                                    }
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                                    s.invited.add(target.getUUID());
                                                    StudioSets.save();
                                                    p.sendSystemMessage(Component.literal(target.getName().getString()
                                                            + " may now build in set '" + s.name + "'.")
                                                            .withStyle(ChatFormatting.GREEN));
                                                    return 1;
                                                }))))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SET_SUGGESTIONS)
                                        .executes(ctx -> {
                                            ServerPlayer p = admin(ctx);
                                            if (p == null) return 0;
                                            StudioSets.SetInfo s = StudioSets.get(StringArgumentType.getString(ctx, "name"));
                                            if (s == null) return err(p, "No such set.");
                                            int stamped = StudioMode.approveSetPieces(s.name,
                                                    p.level().getServer().getStructureManager());
                                            s.approved = true;
                                            s.trusted = true; // approval implies the lock
                                            s.dirty = false;
                                            StudioSets.save();
                                            p.sendSystemMessage(Component.literal("Set '" + s.name
                                                    + "' approved — " + stamped
                                                    + " new pieces enter generation. The set is locked to its trusted builders.")
                                                    .withStyle(ChatFormatting.GREEN));
                                            return 1;
                                        })))
                        .then(Commands.literal("reopen")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SET_SUGGESTIONS)
                                        .executes(ctx -> {
                                            ServerPlayer p = builder(ctx);
                                            if (p == null) return 0;
                                            StudioSets.SetInfo s = StudioSets.get(StringArgumentType.getString(ctx, "name"));
                                            if (s == null) return err(p, "No such set.");
                                            if (!s.isMember(p) && !GraveyardRules.isGamemaster(p)) {
                                                return err(p, "Only trusted builders may reopen this set.");
                                            }
                                            s.dirty = true;
                                            StudioSets.save();
                                            p.sendSystemMessage(Component.literal("Set '" + s.name
                                                    + "' reopened for additions — new pieces ship after the next approval.")
                                                    .withStyle(ChatFormatting.YELLOW));
                                            return 1;
                                        })))
                        .then(Commands.literal("list").executes(ctx -> {
                            ServerPlayer p = builder(ctx);
                            if (p == null) return 0;
                            p.sendSystemMessage(Component.literal("default — the shipped baseline (always generates)")
                                    .withStyle(ChatFormatting.GREEN));
                            for (StudioSets.SetInfo s : StudioSets.all()) {
                                String state = s.approved ? (s.dirty ? "APPROVED, changes pending" : "APPROVED")
                                        : s.trusted ? "trusted, building" : "open, building";
                                p.sendSystemMessage(Component.literal("  " + s.name + " (" + s.size + "x" + s.size
                                        + ") steward " + s.stewardName + " — " + state)
                                        .withStyle(s.approved ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
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
                .then(Commands.literal("testmode").executes(ctx -> {
                    ServerPlayer player = admin(ctx);
                    if (player == null) return 0;
                    boolean on;
                    if (Gravekeepers.TEST_MODE.remove(player.getUUID())) {
                        on = false;
                    } else {
                        Gravekeepers.TEST_MODE.add(player.getUUID());
                        on = true;
                    }
                    player.sendSystemMessage(Component.literal(on
                            ? "Test mode ON — protection now treats you as a regular player (the Studio will evict you!). Toggle again to restore."
                            : "Test mode OFF — your builder privileges are back.")
                            .withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
                    return 1;
                }))
                .then(Commands.literal("rebuild-decor").executes(ctx -> {
                    ServerPlayer player = admin(ctx);
                    if (player == null) return 0;
                    ServerLevel graveyard = player.level().getServer().getLevel(CharonsEcho.GRAVEYARD_DIM);
                    if (graveyard == null) return 0;
                    int placed = DecorScatter.rebuild(graveyard);
                    player.sendSystemMessage(Component.literal(
                            "The world rebuilds as it first did — " + placed
                            + " pieces re-scattered from the seed.")
                            .withStyle(ChatFormatting.GREEN));
                    return 1;
                }))
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
        // Names carry their category prefix — applied automatically if omitted.
        if (!name.startsWith(category + "_")) {
            name = category + "_" + name;
        }
        // Inside a custom set, the plot stakes out where the builder stands —
        // and only where they're allowed to build.
        StudioSets.SetInfo set = null;
        if (player.level().dimension() == CharonsEcho.STUDIO_DIM) {
            set = StudioSets.at(player.getBlockX(), player.getBlockZ());
            if (set != null && !StudioSets.canBuildAt(player, player.getBlockX(), player.getBlockZ())) {
                player.sendSystemMessage(Component.literal("This set is not yours to build in.")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
        }
        StudioMode.StudioPlot plot = StudioMode.createPlot(studio, category, name,
                player.getName().getString(), set, player.blockPosition());
        if (plot == null) {
            player.sendSystemMessage(Component.literal(
                    "Bad category, name taken, or plot doesn't fit here (overlap/border). Categories: "
                    + StudioMode.CATEGORIES.stream().map(StudioMode.Category::name)
                            .collect(Collectors.joining(", ")))
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int y = StudioMode.STUDIO_GROUND_Y + 1;
        player.teleportTo(studio, plot.x0() - 1.5, y, plot.z0() - 1.5, Set.<Relative>of(), 45f, 0f, false);
        player.sendSystemMessage(Component.literal(
                "Plot '" + name + "' staked out (" + plot.w() + "x" + plot.d() + ", max h " + plot.h()
                + (set == null ? ") in the default grid." : ") in set '" + set.name + "'.")
                + " Build, then /charon export.")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int setNew(ServerPlayer player, String name, int size) {
        ServerLevel studio = player.level().getServer().getLevel(CharonsEcho.STUDIO_DIM);
        if (studio == null) return 0;
        StudioSets.SetInfo set = StudioSets.create(studio, name, size, player);
        if (set == null) {
            player.sendSystemMessage(Component.literal("Set name taken or invalid.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        int y = StudioMode.STUDIO_GROUND_Y + 1;
        player.teleportTo(studio, set.originX + 2.5, y, 2.5, Set.<Relative>of(), 45f, 0f, false);
        player.sendSystemMessage(Component.literal(
                "Set '" + name + "' staked out (" + set.size + "x" + set.size + "), you are its steward. "
                + "It is OPEN — any gravekeeper may build here until an admin trusts or approves it. "
                + "Stake plots with /charon plot new <category> <name> wherever you stand.")
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
