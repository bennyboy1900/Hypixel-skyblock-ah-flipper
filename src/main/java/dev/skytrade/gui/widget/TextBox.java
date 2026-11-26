package dev.skytrade.gui.widget;

import dev.skytrade.gui.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** Minimal single-line text input styled for the terminal. */
public final class TextBox {
    private String text = "";
    private boolean focused;
    private final int maxLength;
    private final String placeholder;
    private Rect bounds = new Rect(0, 0, 0, 0);

    public TextBox(String placeholder, int maxLength) {
        this.placeholder = placeholder;
        this.maxLength = maxLength;
    }

    public String text() { return text; }
    public void setText(String t) { text = t == null ? "" : t; }
    public boolean focused() { return focused; }
    public void setFocused(boolean f) { focused = f; }

    public void render(DrawContext ctx, Rect r) {
        bounds = r;
        Ui.round(ctx, r, 3, focused ? Theme.BLUE : Theme.BORDER_STRONG);
        Ui.round(ctx, r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2, 2, Theme.CARD_ALT);
        int ty = r.y() + (r.h() - 8) / 2;
        if (text.isEmpty() && !focused) {
            Ui.text(ctx, placeholder, r.x() + 5, ty, r.w() - 10, Theme.FAINT);
        } else {
            String shown = text;
            while (Ui.width(shown) > r.w() - 12 && !shown.isEmpty()) shown = shown.substring(1);
            Ui.text(ctx, shown, r.x() + 5, ty, Theme.TEXT);
            if (focused && (Util.getMeasuringTimeMs() / 500) % 2 == 0) {
                int cx = r.x() + 5 + Ui.width(shown);
                ctx.fill(cx, ty - 1, cx + 1, ty + 9, Theme.TEXT);
            }
        }
    }

    public boolean mouseClicked(double mx, double my) {
        focused = bounds.contains(mx, my);
        return focused;
    }

    public boolean keyPressed(int key, int mods) {
        if (!focused) return false;
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!text.isEmpty()) {
                if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) text = "";
                else text = text.substring(0, text.length() - 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
            focused = false;
            return true;
        }
        return key != GLFW.GLFW_KEY_ESCAPE;
    }

    public boolean charTyped(String chars) {
        if (!focused) return false;
        for (char c : chars.toCharArray()) {
            if (c >= 32 && c != 127 && text.length() < maxLength) text += c;
        }
        return true;
    }
}
