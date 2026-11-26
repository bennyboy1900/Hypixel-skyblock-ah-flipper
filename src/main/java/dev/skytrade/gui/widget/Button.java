package dev.skytrade.gui.widget;

import dev.skytrade.gui.Theme;
import net.minecraft.client.gui.DrawContext;

/** Flat button. Created per-frame by pages (immediate-mode style) and hit-tested on click. */
public final class Button {
    public enum Style { PRIMARY, SECONDARY, DANGER, GHOST }

    private Button() {}

    public static int width(String label) {
        return Ui.width(label) + 14;
    }

    public static Rect draw(DrawContext ctx, int x, int y, String label, Style style, boolean enabled, double mx, double my) {
        Rect r = new Rect(x, y, width(label), 16);
        draw(ctx, r, label, style, enabled, mx, my);
        return r;
    }

    public static void draw(DrawContext ctx, Rect r, String label, Style style, boolean enabled, double mx, double my) {
        boolean hover = enabled && r.contains(mx, my);
        int fill, border, text;
        switch (style) {
            case PRIMARY -> { fill = hover ? 0xFF5B98FF : Theme.BLUE; border = fill; text = 0xFFFFFFFF; }
            case DANGER -> { fill = hover ? 0xFF3A1F22 : 0xFF2A1A1C; border = Theme.RED; text = Theme.RED; }
            case GHOST -> { fill = hover ? Theme.CARD_HOVER : 0x00000000; border = hover ? Theme.BORDER : 0x00000000; text = Theme.TEXT; }
            default -> { fill = hover ? Theme.CARD_HOVER : Theme.CARD_ALT; border = Theme.BORDER_STRONG; text = Theme.TEXT; }
        }
        if (!enabled) { fill = Theme.CARD_ALT; border = Theme.BORDER; text = Theme.FAINT; }
        if ((border >>> 24) != 0) Ui.round(ctx, r, 3, border);
        if ((fill >>> 24) != 0) Ui.round(ctx, r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2, 2, fill);
        Ui.textCenter(ctx, Ui.fit(label, r.w() - 6), r.x() + r.w() / 2, r.y() + (r.h() - 8) / 2, text);
    }
}
