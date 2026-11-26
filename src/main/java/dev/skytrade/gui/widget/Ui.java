package dev.skytrade.gui.widget;

import dev.skytrade.gui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/** Low-level drawing primitives shared by all widgets. */
public final class Ui {
    private Ui() {}

    public static TextRenderer font() {
        return MinecraftClient.getInstance().textRenderer;
    }

    public static final int LINE = 9;

    /** Filled rectangle with rounded corners (radius in pixels, max 4). */
    public static void round(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        ctx.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    private static int cornerInset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    public static void round(DrawContext ctx, Rect b, int r, int color) {
        round(ctx, b.x(), b.y(), b.w(), b.h(), r, color);
    }

    /** Card: rounded fill with a 1px subtle border. */
    public static void card(DrawContext ctx, Rect b, int fill) {
        round(ctx, b, 4, Theme.BORDER);
        round(ctx, b.x() + 1, b.y() + 1, b.w() - 2, b.h() - 2, 3, fill);
    }

    public static void card(DrawContext ctx, Rect b) {
        card(ctx, b, Theme.CARD);
    }

    public static void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawText(font(), s, x, y, color, false);
    }

    /** Draws text trimmed with an ellipsis to fit {@code maxW}. */
    public static void text(DrawContext ctx, String s, int x, int y, int maxW, int color) {
        ctx.drawText(font(), fit(s, maxW), x, y, color, false);
    }

    public static void textRight(DrawContext ctx, String s, int right, int y, int color) {
        ctx.drawText(font(), s, right - font().getWidth(s), y, color, false);
    }

    public static void textCenter(DrawContext ctx, String s, int cx, int y, int color) {
        ctx.drawText(font(), s, cx - font().getWidth(s) / 2, y, color, false);
    }

    public static int width(String s) {
        return font().getWidth(s);
    }

    public static String fit(String s, int maxW) {
        if (s == null) return "";
        if (maxW <= 0) return "";
        TextRenderer f = font();
        if (f.getWidth(s) <= maxW) return s;
        int ell = f.getWidth("..");
        return f.trimToWidth(s, Math.max(0, maxW - ell)) + "..";
    }

    /** Small label above a value, used by stat cards. */
    public static void stat(DrawContext ctx, Rect b, String label, String value, int valueColor, String sub, int subColor) {
        card(ctx, b);
        text(ctx, label.toUpperCase(), b.x() + 8, b.y() + 7, b.w() - 16, Theme.MUTED);
        ctx.getMatrices().pushMatrix();
        float s = b.h() >= 44 && b.w() >= 90 ? 1.5f : 1f;
        ctx.getMatrices().translate(b.x() + 8, b.y() + 19);
        ctx.getMatrices().scale(s, s);
        ctx.drawText(font(), fit(value, (int) ((b.w() - 16) / s)), 0, 0, valueColor, false);
        ctx.getMatrices().popMatrix();
        if (sub != null && b.h() >= 44) text(ctx, sub, b.x() + 8, b.bottom() - 13, b.w() - 16, subColor);
    }

    public static void pill(DrawContext ctx, int x, int y, String label, int color) {
        int w = width(label) + 8;
        round(ctx, x, y, w, 11, 3, Theme.withAlpha(color, 0.18f));
        text(ctx, label, x + 4, y + 2, color);
    }

    public static int pillWidth(String label) {
        return width(label) + 8;
    }

    public static void progress(DrawContext ctx, int x, int y, int w, int h, double frac, int color) {
        round(ctx, x, y, w, h, 1, Theme.BORDER);
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, frac)));
        if (fw > 0) round(ctx, x, y, fw, h, 1, color);
    }

    public static void hline(DrawContext ctx, int x1, int x2, int y, int color) {
        ctx.fill(x1, y, x2, y + 1, color);
    }

    public static void item(DrawContext ctx, ItemStack stack, int x, int y, float scale) {
        if (stack == null || stack.isEmpty()) return;
        if (scale == 1f) {
            ctx.drawItem(stack, x, y);
            return;
        }
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawItem(stack, 0, 0);
        ctx.getMatrices().popMatrix();
    }

    /** Section heading inside a card. */
    public static void heading(DrawContext ctx, Rect card, String title) {
        text(ctx, title, card.x() + 8, card.y() + 7, card.w() - 16, Theme.TEXT);
    }

    /** Draws a 1px line between two points using small fills (no line primitive in the GUI pipeline). */
    public static void line(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        if (dx >= dy) {
            if (x1 > x2) { int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
            for (int x = x1; x <= x2; x++) {
                int y = dx == 0 ? y1 : y1 + (y2 - y1) * (x - x1) / dx;
                ctx.fill(x, y, x + 1, y + 1, color);
            }
        } else {
            if (y1 > y2) { int t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
            for (int y = y1; y <= y2; y++) {
                int x = x1 + (x2 - x1) * (y - y1) / dy;
                ctx.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    /** Greedy word wrap to {@code maxW} pixels. */
    public static java.util.List<String> wrap(String text, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        String indent = text.startsWith("  ") ? "    " : "";
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                if (line.length() == 0) line.append(' ');
                continue;
            }
            String cand = line.length() == 0 ? word : line + " " + word;
            if (width(cand) > maxW && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(indent + word);
            } else {
                line = new StringBuilder(cand);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
