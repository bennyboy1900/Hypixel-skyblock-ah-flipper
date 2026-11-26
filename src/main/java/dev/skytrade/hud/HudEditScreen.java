package dev.skytrade.hud;

import dev.skytrade.SkyTradeCore;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Drag to move the HUD, scroll to scale, [ and ] to change opacity, H to toggle. */
public final class HudEditScreen extends Screen {
    private final SkyTradeCore core;
    private final Screen parent;
    private boolean dragging;
    private double grabX, grabY;

    public HudEditScreen(SkyTradeCore core, Screen parent) {
        super(Text.literal("SkyTrade HUD"));
        this.core = core;
        this.parent = parent;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x60000000);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        var h = core.config().hud;
        HudOverlay.render(ctx, core, true);
        int[] s = HudOverlay.size(core);
        int w = Math.round(s[0] * h.scale), hh = Math.round(s[1] * h.scale);
        ctx.drawStrokedRectangle(h.x - 1, h.y - 1, w + 2, hh + 2, dragging ? Theme.BLUE : 0xFFFFFFFF);
        String[] help = {"Drag to move  |  Scroll to scale (" + String.format("%.2f", h.scale) + ")",
                "[ / ] opacity (" + Math.round(h.opacity * 100) + "%)  |  H toggle (" + (h.enabled ? "on" : "off") + ")  |  Esc to finish"};
        for (int i = 0; i < help.length; i++) Ui.textCenter(ctx, help[i], width / 2, height - 30 + i * 11, Theme.TEXT);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        var h = core.config().hud;
        int[] s = HudOverlay.size(core);
        if (click.x() >= h.x && click.x() <= h.x + s[0] * h.scale && click.y() >= h.y && click.y() <= h.y + s[1] * h.scale) {
            dragging = true;
            grabX = click.x() - h.x;
            grabY = click.y() - h.y;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (!dragging) return false;
        var h = core.config().hud;
        int[] s = HudOverlay.size(core);
        h.x = (int) Math.max(0, Math.min(width - s[0] * h.scale, click.x() - grabX));
        h.y = (int) Math.max(0, Math.min(height - s[1] * h.scale, click.y() - grabY));
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        var h = core.config().hud;
        h.scale = (float) Math.max(0.5, Math.min(3, Math.round((h.scale + vAmount * 0.1) * 10) / 10.0));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        var h = core.config().hud;
        switch (input.key()) {
            case GLFW.GLFW_KEY_LEFT_BRACKET -> h.opacity = Math.max(0.1f, h.opacity - 0.05f);
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> h.opacity = Math.min(1f, h.opacity + 0.05f);
            case GLFW.GLFW_KEY_H -> h.enabled = !h.enabled;
            default -> {
                return super.keyPressed(input);
            }
        }
        return true;
    }

    @Override
    public void close() {
        core.configManager().save();
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
