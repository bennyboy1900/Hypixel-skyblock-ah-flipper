package dev.skytrade.gui.page;

import dev.skytrade.SkyTradeCore;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A page inside the terminal. Buttons are immediate-mode: drawn during render and registered as hit
 * targets for the next click.
 */
public abstract class Page {
    protected final SkyTradeScreen screen;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> frame = new ArrayList<>();
    protected int mouseX, mouseY;

    private record Hit(Rect rect, Runnable action) {}

    protected Page(SkyTradeScreen screen) {
        this.screen = screen;
    }

    protected SkyTradeCore core() {
        return screen.core();
    }

    public abstract String title();

    /** Called every frame. {@code area} is the content region (right of the sidebar, below the header). */
    public final void renderPage(DrawContext ctx, Rect area, int mx, int my, float delta) {
        mouseX = mx;
        mouseY = my;
        frame.clear();
        render(ctx, area, mx, my, delta);
        hits.clear();
        hits.addAll(frame);
    }

    protected abstract void render(DrawContext ctx, Rect area, int mx, int my, float delta);

    public void onOpen() {}

    /** Minimum content height; when the window is shorter, the screen scrolls the page. 0 = never scroll. */
    public int minHeight() {
        return 0;
    }

    protected Rect button(DrawContext ctx, int x, int y, String label, Button.Style style, boolean enabled, Runnable action) {
        Rect r = Button.draw(ctx, x, y, label, style, enabled, mouseX, mouseY);
        if (enabled) frame.add(new Hit(r, action));
        return r;
    }

    protected void button(DrawContext ctx, Rect r, String label, Button.Style style, boolean enabled, Runnable action) {
        Button.draw(ctx, r, label, style, enabled, mouseX, mouseY);
        if (enabled) frame.add(new Hit(r, action));
    }

    /** Registers an invisible click target. */
    protected void clickable(Rect r, Runnable action) {
        frame.add(new Hit(r, action));
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        for (Hit h : List.copyOf(hits)) {
            if (h.rect().contains(mx, my)) {
                screen.playClick();
                h.action().run();
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        return false;
    }

    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        return false;
    }

    public boolean keyPressed(int key, int mods) {
        return false;
    }

    public boolean charTyped(String chars) {
        return false;
    }
}
