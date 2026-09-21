package com.charonsecho;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignText;

/**
 * 26.3 sign-text shims: SignText lost its no-arg constructor and per-line
 * setters — a face is now built whole (messages, filtered messages, color,
 * glow). Every sign the mod writes goes through here.
 */
public final class Signs {

    private Signs() {}

    /** A black, unlit sign face from up to four lines (missing lines blank). */
    public static SignText text(Component... lines) {
        List<Component> msgs = List.of(
                lines.length > 0 ? lines[0] : Component.empty(),
                lines.length > 1 ? lines[1] : Component.empty(),
                lines.length > 2 ? lines[2] : Component.empty(),
                lines.length > 3 ? lines[3] : Component.empty());
        return new SignText(msgs, msgs, DyeColor.BLACK, false);
    }

    /** One line of a face, unfiltered — the old getMessage(i, false). */
    public static Component line(SignText text, int i) {
        return text.getMessages(false).get(i);
    }

    /** A copy of a face with one line replaced — the old setMessage(i, c). */
    public static SignText withLine(SignText text, int i, Component line) {
        Component[] lines = text.getMessages(false).toArray(new Component[0]);
        lines[i] = line;
        return new SignText(List.of(lines), List.of(lines),
                text.getColor(), text.hasGlowingText());
    }
}
