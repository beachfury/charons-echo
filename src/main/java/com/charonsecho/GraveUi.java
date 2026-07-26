package com.charonsecho;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * The one way the dead are shown, everywhere they are listed (the Book of the
 * Dead, the crypt's day-shelves, the month halls): THEIR OWN HEAD, their date,
 * their cause, their flowers, and where they lie. Clicking opens the memorial
 * — read their story, or (for the living, already inside Charon's Echo) walk
 * to their grave the short way.
 */
public final class GraveUi {

    private GraveUi() {}

    /** A memorial entry for any list gui. */
    public static GuiElementBuilder entry(ServerPlayer viewer, GraveManager.Grave grave) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(grave.owner));
        GuiElementBuilder b = GuiElementBuilder.from(head)
                .setName(Component.literal(grave.ownerName)
                        .withStyle(grave.claimed ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        if (grave.epochMillis > 0) {
            b.addLoreLine(Component.literal(
                    new SimpleDateFormat("MMM d, yyyy").format(new Date(grave.epochMillis)))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (grave.causeLine != null && !grave.causeLine.isEmpty()) {
            b.addLoreLine(Component.literal(grave.causeLine).withStyle(ChatFormatting.GRAY));
        }
        if (grave.plotIndex >= 0) {
            BlockPos at = GraveyardPlots.arrivalPos(grave.plotIndex);
            b.addLoreLine(Component.literal("Field " + (grave.plotIndex / 36 + 1)
                    + " — " + at.getX() + ", " + at.getY() + ", " + at.getZ())
                    .withStyle(ChatFormatting.DARK_AQUA));
        }
        if (grave.tributes > 0) {
            b.addLoreLine(Component.literal(grave.tributes
                    + (grave.tributes == 1 ? " flower laid" : " flowers laid"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (grave.book != null) {
            b.glow();
            b.addLoreLine(Component.literal("They left a story.")
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }
        b.addLoreLine(Component.literal("Click to visit their memorial.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        b.setCallback((i, t, a, g) -> openMemorial(viewer, grave));
        return b;
    }

    /** The memorial: read the story, or walk to the grave the short way. */
    private static void openMemorial(ServerPlayer viewer, GraveManager.Grave grave) {
        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x3, viewer, false);
        gui.setTitle(Component.literal(grave.ownerName));
        if (grave.book != null) {
            gui.setSlot(11, new GuiElementBuilder(Items.WRITTEN_BOOK)
                    .setName(Component.literal("Read their story").withStyle(ChatFormatting.DARK_AQUA))
                    .glow()
                    .setCallback((i, t, a, g) -> {
                        g.close();
                        GraveBooks.open(viewer, grave);
                    }));
        } else {
            gui.setSlot(11, new GuiElementBuilder(Items.BOOK)
                    .setName(Component.literal("They left no story.")
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }
        boolean mayVisit = grave.plotIndex >= 0
                && viewer.level().dimension() == CharonsEcho.GRAVEYARD_DIM
                && !GhostState.isGhost(viewer.getUUID());
        if (mayVisit) {
            gui.setSlot(15, new GuiElementBuilder(Items.RECOVERY_COMPASS)
                    .setName(Component.literal("Visit their grave").withStyle(ChatFormatting.GOLD))
                    .addLoreLine(Component.literal("Field " + (grave.plotIndex / 36 + 1))
                            .withStyle(ChatFormatting.GRAY))
                    .setCallback((i, t, a, g) -> {
                        g.close();
                        ServerLevel graveyard = (ServerLevel) viewer.level();
                        BlockPos at = GraveyardPlots.arrivalPos(grave.plotIndex);
                        graveyard.getChunk(at.getX() >> 4, at.getZ() >> 4);
                        viewer.teleportTo(graveyard, at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
                                Set.<Relative>of(), -90f, 0f, false);
                        graveyard.playSound(null, at, SoundEvents.SOUL_ESCAPE.value(),
                                SoundSource.AMBIENT, 0.7f, 0.9f);
                    }));
        } else if (grave.plotIndex >= 0) {
            gui.setSlot(15, new GuiElementBuilder(Items.COMPASS)
                    .setName(Component.literal("Their grave lies in Charon's Echo")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal("Only the living may walk there,")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .addLoreLine(Component.literal("and only from within.")
                            .withStyle(ChatFormatting.DARK_GRAY)));
        }
        gui.open();
    }
}
