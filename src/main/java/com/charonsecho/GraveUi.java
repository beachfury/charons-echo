package com.charonsecho;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
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
        return entry(viewer, grave, false);
    }

    /** A memorial entry; the crowned form is the Death of the Week. */
    public static GuiElementBuilder entry(ServerPlayer viewer, GraveManager.Grave grave,
            boolean crowned) {
        GuiElementBuilder b;
        if (crowned) {
            b = new GuiElementBuilder(Items.WITHER_ROSE)
                    .setName(Component.literal("Death of the Week: " + grave.ownerName)
                            .withStyle(ChatFormatting.GOLD))
                    .glow();
        } else {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(grave.owner));
            b = GuiElementBuilder.from(head)
                    .setName(Component.literal(grave.ownerName)
                            .withStyle(grave.claimed ? ChatFormatting.GRAY : ChatFormatting.WHITE));
        }
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

    // ------------------------------------------------------------ the rolls

    private static final int PAGE_SIZE = 45;

    /**
     * A roll of the dead: every ledger in the mod (the Book of the Dead, the
     * day-shelves, the month halls) opens through here — 45 souls a page,
     * arrows to turn it, a spyglass to seek a name. The crowned grave, if the
     * roll has one, heads page one.
     */
    public static void openList(ServerPlayer viewer, String title,
            List<GraveManager.Grave> graves, GraveManager.Grave crowned,
            String emptyText, int page, String search) {
        List<GraveManager.Grave> shown = new ArrayList<>();
        if (crowned != null && matches(crowned, search)) shown.add(crowned);
        for (GraveManager.Grave g : graves) {
            if (g == crowned) continue;
            if (matches(g, search)) shown.add(g);
        }
        int pages = Math.max(1, (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        final int p = Math.min(Math.max(page, 0), pages - 1);
        final String q = search == null || search.isBlank() ? null : search;

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x6, viewer, false);
        gui.setTitle(Component.literal(pages > 1
                ? title + " (" + (p + 1) + "/" + pages + ")" : title));
        int start = p * PAGE_SIZE;
        for (int i = start; i < Math.min(start + PAGE_SIZE, shown.size()); i++) {
            GraveManager.Grave g = shown.get(i);
            gui.setSlot(i - start, entry(viewer, g, g == crowned).build());
        }
        if (shown.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BONE)
                    .setName(Component.literal(q == null ? emptyText
                            : "No soul bears that name.").withStyle(ChatFormatting.GRAY))
                    .build());
        }

        if (p > 0) {
            gui.setSlot(45, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Previous page").withStyle(ChatFormatting.WHITE))
                    .setCallback((i, t, a, g) ->
                            openList(viewer, title, graves, crowned, emptyText, p - 1, q))
                    .build());
        }
        GuiElementBuilder seek = new GuiElementBuilder(Items.SPYGLASS)
                .setName(Component.literal("Seek a name").withStyle(ChatFormatting.AQUA));
        if (q != null) {
            seek.addLoreLine(Component.literal("Seeking: \"" + q + "\"")
                    .withStyle(ChatFormatting.GRAY));
        }
        seek.setCallback((i, t, a, g) ->
                openSearch(viewer, title, graves, crowned, emptyText));
        gui.setSlot(47, seek.build());
        if (q != null) {
            gui.setSlot(48, new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("Show every name").withStyle(ChatFormatting.RED))
                    .setCallback((i, t, a, g) ->
                            openList(viewer, title, graves, crowned, emptyText, 0, null))
                    .build());
        }
        gui.setSlot(49, new GuiElementBuilder(Items.PAPER)
                .setName(Component.literal("Page " + (p + 1) + " of " + pages)
                        .withStyle(ChatFormatting.WHITE))
                .addLoreLine(Component.literal(shown.size()
                        + (shown.size() == 1 ? " soul" : " souls"))
                        .withStyle(ChatFormatting.DARK_GRAY))
                .build());
        if (p < pages - 1) {
            gui.setSlot(53, new GuiElementBuilder(Items.ARROW)
                    .setName(Component.literal("Next page").withStyle(ChatFormatting.WHITE))
                    .setCallback((i, t, a, g) ->
                            openList(viewer, title, graves, crowned, emptyText, p + 1, q))
                    .build());
        }
        gui.open();
    }

    private static boolean matches(GraveManager.Grave grave, String search) {
        if (search == null || search.isBlank()) return true;
        return grave.ownerName != null
                && grave.ownerName.toLowerCase().contains(search.trim().toLowerCase());
    }

    /** The spyglass: an anvil to type a name into, then back to the roll. */
    private static void openSearch(ServerPlayer viewer, String title,
            List<GraveManager.Grave> graves, GraveManager.Grave crowned, String emptyText) {
        AnvilInputGui anvil = new AnvilInputGui(viewer, false);
        anvil.setTitle(Component.literal("Whom do you seek?"));
        anvil.setDefaultInputValue("");
        anvil.setSlot(2, new GuiElementBuilder(Items.SPYGLASS)
                .setName(Component.literal("Seek").withStyle(ChatFormatting.AQUA))
                .addLoreLine(Component.literal("Type a name, then click.")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .setCallback((i, t, a, g) -> {
                    String typed = anvil.getInput();
                    openList(viewer, title, graves, crowned, emptyText, 0,
                            typed == null ? null : typed.trim());
                })
                .build());
        anvil.open();
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
