package com.charonsecho;

import java.util.List;

import eu.pb4.sgui.api.gui.BookGui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * A death told in the dead player's own words: write a book (sign it for
 * title + formatting, or leave it a plain book-and-quill), then click YOUR
 * grave holding it — the book is interred with the dead. Anyone clicking the
 * stone afterwards READS the book (opened server-side, never copied). The
 * ledger (/charon ledger) lists every death; entries with books open them.
 */
public final class GraveBooks {

    private GraveBooks() {}

    public static boolean isBook(ItemStack stack) {
        return stack.has(DataComponents.WRITTEN_BOOK_CONTENT)
                || stack.has(DataComponents.WRITABLE_BOOK_CONTENT);
    }

    /** Owner clicks their grave holding a book: it is buried with the dead. */
    public static boolean intern(ServerPlayer player, GraveManager.Grave grave, ItemStack held) {
        WrittenBookContent content = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            WritableBookContent draft = held.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (draft == null) return false;
            List<Filterable<Component>> pages = draft.pages().stream()
                    .map(p -> Filterable.<Component>passThrough(Component.literal(p.raw())))
                    .toList();
            content = new WrittenBookContent(
                    Filterable.passThrough(grave.ownerName + "'s last words"),
                    player.getName().getString(), 0, pages, true);
        }
        grave.book = content;
        GraveManager.save();
        held.shrink(1);

        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(ParticleTypes.ENCHANT,
                player.getX(), player.getY() + 1.2, player.getZ(), 30, 0.4, 0.6, 0.4, 0.4);
        level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PUT, SoundSource.AMBIENT, 1.0f, 0.8f);
        player.sendSystemMessage(Component.literal(
                "Your words are buried with you. All who visit this stone may read them.")
                .withStyle(ChatFormatting.DARK_PURPLE));
        return true;
    }

    /** Open a grave's interred book — read-only, no copy ever leaves the stone. */
    public static void open(ServerPlayer player, GraveManager.Grave grave) {
        if (grave.book == null) return;
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, grave.book);
        new BookGui(player, book).open();
    }
}
