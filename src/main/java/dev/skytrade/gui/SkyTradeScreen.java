package dev.skytrade.gui;

import dev.skytrade.SkyTradeCore;
import dev.skytrade.api.ApiStatus;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.gui.page.*;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.market.DataFreshness;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/** The SkyTrade terminal: persistent left sidebar, header with live status, and the active page. */
public final class SkyTradeScreen extends Screen {
    public enum Nav {
        DASHBOARD("Dashboard"), BAZAAR("Bazaar"), AUCTIONS("Auction House"), CRAFTS("Craft Flips"), ORDERS("Orders"),
        PORTFOLIO("Portfolio"), ANALYTICS("Analytics"), SETTINGS("Settings");

        public final String label;

        Nav(String label) {
            this.label = label;
        }
    }

    private final SkyTradeCore core;
    private final Map<Nav, Page> pages = new EnumMap<>(Nav.class);
    private final Deque<Page> detail = new ArrayDeque<>();
    private Nav nav;
    private Rect pauseButton = new Rect(0, 0, 0, 0);
    private long openedAt;
    private double pageScroll;
    private boolean pageScrolls;
    private Rect contentArea = new Rect(0, 0, 0, 0);

    public SkyTradeScreen(SkyTradeCore core, Nav initial) {
        super(Text.literal("SkyTrade"));
        this.core = core;
        this.nav = initial;
        pages.put(Nav.DASHBOARD, new DashboardPage(this));
        pages.put(Nav.BAZAAR, new BazaarPage(this));
        pages.put(Nav.AUCTIONS, new AuctionPage(this));
        pages.put(Nav.CRAFTS, new CraftPage(this));
        pages.put(Nav.ORDERS, new OrdersPage(this));
        pages.put(Nav.PORTFOLIO, new PortfolioPage(this));
        pages.put(Nav.ANALYTICS, new AnalyticsPage(this));
        pages.put(Nav.SETTINGS, new SettingsPage(this));
    }

    public SkyTradeCore core() {
        return core;
    }

    public void navigate(Nav n) {
        nav = n;
        pageScroll = 0;
        detail.clear();
        pages.get(n).onOpen();
    }

    public void push(Page p) {
        pageScroll = 0;
        detail.push(p);
        p.onOpen();
    }

    public void back() {
        pageScroll = 0;
        if (!detail.isEmpty()) detail.pop();
    }

    private Page current() {
        return detail.isEmpty() ? pages.get(nav) : detail.peek();
    }

    public void playClick() {
        if (client != null) client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    public void copyToClipboard(String s) {
        if (client != null) client.keyboard.setClipboard(s);
        core.notifications().post(dev.skytrade.notifications.Notification.Level.INFO, "Copied", s);
    }

    @Override
    protected void init() {
        openedAt = System.currentTimeMillis();
        current().onOpen();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, Theme.BG);
    }

    private int sidebarWidth() {
        int labels = Ui.width("SkyTrade") + 22;
        for (Nav n : Nav.values()) labels = Math.max(labels, Ui.width(n.label) + 26);
        return Math.max(70, Math.min(Math.max(labels, width / 7), width / 4));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int sw = sidebarWidth();
        renderSidebar(ctx, sw, mx, my);
        Rect header = new Rect(sw, 0, width - sw, 28);
        renderHeader(ctx, header, mx, my);
        Rect area = new Rect(sw + 8, 32, width - sw - 16, height - 40);
        float alpha = core.config().appearance.animations ? Math.min(1f, (System.currentTimeMillis() - openedAt) / 150f) : 1f;
        if (alpha < 1f) ctx.fill(area.x(), area.y(), area.right(), area.bottom(), Theme.withAlpha(Theme.BG, 1 - alpha));
        contentArea = area;
        Page page = current();
        int minH = page.minHeight();
        pageScrolls = minH > area.h();
        if (!pageScrolls) {
            pageScroll = 0;
            page.renderPage(ctx, area, mx, my, delta);
            return;
        }
        // Window too short for this page: render it at its minimum height inside a scrolling viewport.
        pageScroll = Math.max(0, Math.min(pageScroll, minH - area.h()));
        ctx.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(0, (float) -pageScroll);
        int pageMy = area.contains(mx, my) ? my + (int) pageScroll : -10_000;
        page.renderPage(ctx, new Rect(area.x(), area.y(), area.w(), minH), mx, pageMy, delta);
        ctx.getMatrices().popMatrix();
        ctx.disableScissor();
        int trackH = area.h();
        int thumbH = Math.max(10, trackH * area.h() / minH);
        int thumbY = area.y() + (int) ((trackH - thumbH) * (pageScroll / (minH - area.h())));
        Ui.round(ctx, area.right() + 3, thumbY, 3, thumbH, 1, Theme.BORDER_STRONG);
    }

    /** Converts a screen-space mouse y into the current page's coordinate space. */
    private double pageY(double my) {
        return pageScrolls ? my + pageScroll : my;
    }

    private void renderSidebar(DrawContext ctx, int sw, int mx, int my) {
        ctx.fill(0, 0, sw, height, Theme.SIDEBAR);
        ctx.fill(sw - 1, 0, sw, height, Theme.BORDER);
        Ui.round(ctx, 8, 9, 10, 10, 2, Theme.BLUE);
        Ui.text(ctx, "SkyTrade", 22, 10, Theme.TEXT);
        int y = 34;
        for (Nav n : Nav.values()) {
            Rect r = new Rect(6, y, sw - 12, 18);
            boolean sel = n == nav;
            boolean hover = r.contains(mx, my);
            if (sel) {
                Ui.round(ctx, r, 3, Theme.BLUE_DIM);
                ctx.fill(r.x(), r.y() + 3, r.x() + 2, r.bottom() - 3, Theme.BLUE);
            } else if (hover) Ui.round(ctx, r, 3, Theme.CARD_HOVER);
            Ui.text(ctx, n.label, r.x() + 8, r.y() + 5, r.w() - 10, sel ? Theme.TEXT : Theme.MUTED);
            y += 20;
        }
        var pv = core.portfolio();
        int by = height - 34;
        Ui.hline(ctx, 6, sw - 6, by - 6, Theme.BORDER);
        Ui.text(ctx, "Paper value", 8, by, sw - 14, Theme.FAINT);
        Ui.text(ctx, Fmt.coins(pv.totalValue()), 8, by + 11, sw - 14, Theme.TEXT);
        double pnl = pv.totalValue() - pv.startingCapital();
        Ui.text(ctx, Fmt.signedCoins(pnl), 8 + Ui.width(Fmt.coins(pv.totalValue())) + 5, by + 11, sw - 14, Theme.signed(pnl));
    }

    private void renderHeader(DrawContext ctx, Rect h, int mx, int my) {
        Ui.text(ctx, current().title(), h.x() + 10, 10, Theme.TEXT);
        int x = h.right() - 8;
        SkyTradeConfig cfg = core.config();
        boolean paused = core.risk().breaker().manuallyPaused() || cfg.risk.emergencyStop;
        String pl = paused ? "Resume" : "Pause";
        int bw = Button.width(pl);
        pauseButton = new Rect(x - bw, 6, bw, 16);
        Button.draw(ctx, pauseButton, pl, paused ? Button.Style.PRIMARY : Button.Style.SECONDARY, true, mx, my);
        x -= bw + 6;

        boolean sandbox = cfg.mode == SkyTradeConfig.TradingMode.SANDBOX_AUTO;
        String mode = sandbox ? "SANDBOX" : "LIVE ANALYSIS";
        x -= Ui.pillWidth(mode);
        Ui.pill(ctx, x, 9, mode, sandbox ? Theme.YELLOW : Theme.BLUE);
        x -= 6;

        long now = System.currentTimeMillis();
        var ah = core.market().auctions();
        String ahText = "AH " + (ah.lastUpdated() > 0 ? Fmt.duration(now - ah.lastUpdated()) : "-");
        x -= Ui.pillWidth(ahText);
        Ui.pill(ctx, x, 9, ahText, ah.lastUpdated() == 0 ? Theme.MUTED : DataFreshness.of(ah.lastUpdated(), now, cfg.api.staleAfterSeconds * 2000L) == DataFreshness.STALE ? Theme.RED : Theme.MUTED);
        x -= 4;

        var bz = core.market().bazaar();
        DataFreshness f = core.market().bazaarFreshness();
        String bzText = "BZ " + (bz != null ? Fmt.duration(bz.ageMs(now)) : "-");
        x -= Ui.pillWidth(bzText);
        Ui.pill(ctx, x, 9, bzText, f == DataFreshness.FRESH ? Theme.GREEN : f == DataFreshness.AGING ? Theme.YELLOW : f == DataFreshness.STALE ? Theme.RED : Theme.MUTED);
        x -= 4;

        ApiStatus.Health health = core.market().status().overall();
        String api = "API " + health.name();
        x -= Ui.pillWidth(api);
        if (x > h.x() + Ui.width(current().title()) + 20) {
            Ui.pill(ctx, x, 9, api, switch (health) {
                case ONLINE -> Theme.GREEN;
                case DEGRADED -> Theme.YELLOW;
                case OFFLINE -> Theme.RED;
                default -> Theme.MUTED;
            });
        }
        Ui.hline(ctx, h.x(), h.right(), h.bottom(), Theme.BORDER);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int sw = sidebarWidth();
        if (click.button() == 0 && mx < sw) {
            int y = 34;
            for (Nav n : Nav.values()) {
                if (new Rect(6, y, sw - 12, 18).contains(mx, my)) {
                    playClick();
                    navigate(n);
                    return true;
                }
                y += 20;
            }
            return true;
        }
        if (click.button() == 0 && pauseButton.contains(mx, my)) {
            playClick();
            if (core.risk().breaker().manuallyPaused() || core.config().risk.emergencyStop) core.resume();
            else core.pause();
            return true;
        }
        if (pageScrolls && !contentArea.contains(mx, my)) return super.mouseClicked(click, doubled);
        if (current().mouseClicked(mx, pageY(my), click.button())) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (current().mouseScrolled(mx, pageY(my), v)) return true;
        if (pageScrolls) {
            pageScroll -= v * 24;
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (current().mouseDragged(click.x(), pageY(click.y()), dx, dy)) return true;
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (current().keyPressed(input.key(), input.modifiers())) return true;
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !detail.isEmpty()) {
            back();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (current().charTyped(input.asString())) return true;
        return super.charTyped(input);
    }

    @Override
    public void removed() {
        core.configManager().save();
        super.removed();
    }
}
