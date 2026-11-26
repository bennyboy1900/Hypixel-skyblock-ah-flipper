package dev.skytrade.gui.page;

import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.*;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.market.OrderLevel;
import dev.skytrade.market.PricePoint;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Detailed analysis of one bazaar product: history, order book, metrics and reasoning. */
public final class BazaarDetailPage extends Page {
    private final String itemId;
    private volatile List<PricePoint> history = List.of();
    private int rangeDays = 1;
    private double explainScroll;
    private Rect explainRect = new Rect(0, 0, 0, 0);
    private String status = "";

    public BazaarDetailPage(SkyTradeScreen screen, String itemId) {
        super(screen);
        this.itemId = itemId;
    }

    @Override
    public int minHeight() {
        return 330;
    }

    @Override
    public String title() {
        return "Bazaar  >  " + core().market().catalog().displayName(itemId);
    }

    @Override
    public void onOpen() {
        load();
    }

    private void load() {
        long now = System.currentTimeMillis();
        long from = now - rangeDays * 86_400_000L;
        core().tasks().supply("detail.history", () -> {
            List<PricePoint> db = core().repository().productHistory(itemId, from, now);
            return db == null || db.isEmpty() ? core().market().history().range(itemId, from, now) : db;
        }).thenAccept(h -> history = h);
    }

    private BazaarOpportunity opportunity() {
        for (BazaarOpportunity o : core().results().bazaar()) if (o.itemId().equals(itemId)) return o;
        // Not in the ranked list: analyse directly so the detail view always explains the product.
        BazaarSnapshot snap = core().market().bazaar();
        if (snap == null || snap.get(itemId) == null) return null;
        long now = System.currentTimeMillis();
        SkyTradeConfig cfg = core().config();
        return new BazaarAnalyzer(cfg).analyzeProduct(snap.get(itemId), core().market().history().range(itemId, now - 86_400_000L, now),
                core().market().catalog(), core().portfolioService().sizingContext(cfg.risk.orderSlots, cfg.sandbox.reinvestProfits), snap.ageMs(now));
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        BazaarSnapshot snap = core().market().bazaar();
        BazaarProduct p = snap == null ? null : snap.get(itemId);
        BazaarOpportunity o = opportunity();
        String name = core().market().catalog().displayName(itemId);

        // Header row
        int x = a.x();
        x = button(ctx, x, a.y(), "< Back", Button.Style.GHOST, true, screen::back).right() + 6;
        Ui.item(ctx, ItemIcons.forId(itemId, core().market().catalog()), x, a.y(), 1f);
        Ui.text(ctx, name, x + 20, a.y() + 1, Theme.TEXT);
        Ui.text(ctx, itemId, x + 20, a.y() + 10, Theme.FAINT);
        int bx = a.right();
        String cmd = "/bz " + name;
        bx -= Button.width("Copy " + cmd);
        button(ctx, bx, a.y(), "Copy " + cmd, Button.Style.SECONDARY, true, () -> screen.copyToClipboard(cmd));
        bx -= Button.width("Paper trade") + 4;
        button(ctx, bx, a.y(), "Paper trade", Button.Style.PRIMARY, o != null && o.quantity() > 0, () -> {
            status = "Submitting...";
            core().paperTrade(o).thenAccept(s -> status = s);
        });
        bx -= Button.width("Alert below bid") + 4;
        button(ctx, bx, a.y(), "Alert below bid", Button.Style.SECONDARY, p != null, () -> {
            var al = new SkyTradeConfig.PriceAlert();
            al.productId = itemId;
            al.direction = "BELOW";
            al.useBuyPrice = false;
            al.price = Math.round(p.bestBid() * 0.95 * 10) / 10.0;
            core().config().notifications.priceAlerts.add(al);
            core().configManager().save();
            status = "Alert set: buy orders below " + Fmt.coins(al.price);
        });
        if (!status.isEmpty()) Ui.textRight(ctx, Ui.fit(status, a.w() / 2), a.right(), a.y() + 20, Theme.MUTED);

        Rect body = new Rect(a.x(), a.y() + 32, a.w(), a.h() - 32);
        int gap = 6;
        int leftW = (int) (body.w() * 0.6);
        int topH = (int) (body.h() * 0.52);
        Rect chart = new Rect(body.x(), body.y(), leftW, topH);
        Rect book = new Rect(body.x() + leftW + gap, body.y(), body.w() - leftW - gap, topH);
        Rect metrics = new Rect(body.x(), body.y() + topH + gap, leftW, body.h() - topH - gap);
        Rect explain = new Rect(body.x() + leftW + gap, body.y() + topH + gap, body.w() - leftW - gap, body.h() - topH - gap);

        Ui.card(ctx, chart);
        Ui.heading(ctx, chart, "Price history");
        int rx = chart.right() - 6;
        for (int d : new int[]{7, 3, 1}) {
            String l = d + "d";
            rx -= Button.width(l);
            final int dd = d;
            Rect br = new Rect(rx, chart.y() + 3, Button.width(l), 14);
            button(ctx, br, l, d == rangeDays ? Button.Style.PRIMARY : Button.Style.GHOST, true, () -> { rangeDays = dd; load(); });
            rx -= 2;
        }
        List<double[]> bids = new ArrayList<>(), asks = new ArrayList<>();
        for (PricePoint pt : history) {
            bids.add(new double[]{pt.timestamp(), pt.bid()});
            asks.add(new double[]{pt.timestamp(), pt.ask()});
        }
        if (p != null && snap != null) {
            bids.add(new double[]{snap.lastUpdated(), p.bestBid()});
            asks.add(new double[]{snap.lastUpdated(), p.bestAsk()});
        }
        Charts.line(ctx, new Rect(chart.x() + 6, chart.y() + 22, chart.w() - 12, chart.h() - 36),
                List.of(new Charts.Series(asks, Theme.RED, "Sell offer"), new Charts.Series(bids, Theme.GREEN, "Buy order")), Fmt::coins, false, mx, my);
        Ui.text(ctx, history.size() + " points", chart.x() + 8, chart.bottom() - 11, Theme.FAINT);

        Ui.card(ctx, book);
        Ui.heading(ctx, book, "Order book");
        if (p != null) {
            int depthH = Math.max(40, book.h() / 2 - 14);
            Charts.depth(ctx, new Rect(book.x() + 8, book.y() + 20, book.w() - 16, depthH), p.bids(), p.asks());
            int ly = book.y() + 24 + depthH;
            int colW = (book.w() - 20) / 2;
            Ui.text(ctx, "Buy orders", book.x() + 8, ly, Theme.GREEN);
            Ui.text(ctx, "Sell offers", book.x() + 12 + colW, ly, Theme.RED);
            ly += 11;
            for (int i = 0; i < 12 && ly + 9 < book.bottom() - 2; i++, ly += 10) {
                if (i < p.bids().size()) level(ctx, p.bids().get(i), book.x() + 8, ly, colW);
                if (i < p.asks().size()) level(ctx, p.asks().get(i), book.x() + 12 + colW, ly, colW);
            }
        } else {
            Ui.text(ctx, "Product not in current snapshot.", book.x() + 8, book.y() + 24, Theme.MUTED);
        }

        Ui.card(ctx, metrics);
        Ui.heading(ctx, metrics, "Opportunity metrics");
        if (o != null) {
            String[][] kv = {
                    {"Entry (buy order)", Fmt.coins(o.entryPrice())}, {"Exit (sell offer)", Fmt.coins(o.exitPrice())},
                    {"Quantity", Fmt.num(o.quantity())}, {"Required capital", Fmt.coins(o.capital())},
                    {"Gross profit", Fmt.signedCoins(o.grossProfit())}, {"Net profit", Fmt.signedCoins(o.netProfit())},
                    {"ROI", Fmt.pct(o.roi())}, {"Profit / hour", Fmt.coins(o.profitPerHour())},
                    {"Expected hold", Fmt.duration(o.holdHours() * 3.6e6)}, {"Turnover / day", String.format("%.1fx", o.turnoverPerDay())},
                    {"Spread", Fmt.pct(o.spreadPct())}, {"Exit impact", Fmt.pct(o.priceImpact())},
                    {"Volatility (24h)", Fmt.pct(o.volatility())}, {"Weekly volume", Fmt.num(o.weeklyVolume())},
                    {"Risk score", Fmt.score(o.risk()) + "/100"}, {"Confidence", Fmt.score(o.confidence()) + "/100"},
                    {"Liquidity", Fmt.score(o.liquidity()) + "/100"}, {"Size limited by", o.sizingConstraint()},
            };
            int cols = metrics.w() >= 300 ? 2 : 1;
            int colW = (metrics.w() - 16 - (cols - 1) * 12) / cols;
            int rowsPer = (kv.length + cols - 1) / cols;
            for (int i = 0; i < kv.length; i++) {
                int c = i / rowsPer, r = i % rowsPer;
                int yy = metrics.y() + 22 + r * 11;
                if (yy + 9 > metrics.bottom() - 2) continue;
                int xx = metrics.x() + 8 + c * (colW + 12);
                Ui.text(ctx, kv[i][0], xx, yy, Theme.MUTED);
                int vc = kv[i][0].contains("profit") || kv[i][0].contains("Net") ? Theme.signed(o.netProfit())
                        : kv[i][0].startsWith("Risk") ? Theme.risk(o.risk()) : kv[i][0].startsWith("Conf") ? Theme.quality(o.confidence()) : Theme.TEXT;
                Ui.textRight(ctx, Ui.fit(kv[i][1], colW / 2), xx + colW, yy, vc);
            }
        } else {
            Ui.text(ctx, "No viable flip for this product right now (no spread after tax, or no volume).", metrics.x() + 8, metrics.y() + 22, metrics.w() - 16, Theme.MUTED);
        }

        Ui.card(ctx, explain);
        Ui.heading(ctx, explain, "Why this recommendation");
        explainRect = explain;
        if (o != null) {
            List<String> lines = new ArrayList<>();
            for (String s : o.explanation()) lines.addAll(Ui.wrap(s, explain.w() - 16));
            Rect clip = new Rect(explain.x() + 1, explain.y() + 20, explain.w() - 2, explain.h() - 22);
            explainScroll = Math.max(0, Math.min(explainScroll, lines.size() * 10 - clip.h() + 4));
            ctx.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
            int yy = clip.y() + 2 - (int) explainScroll;
            for (String l : lines) {
                Ui.text(ctx, l, explain.x() + 8, yy, l.startsWith("Warning") ? Theme.YELLOW : Theme.TEXT);
                yy += 10;
            }
            ctx.disableScissor();
        }
    }

    private void level(DrawContext ctx, OrderLevel l, int x, int y, int w) {
        Ui.text(ctx, Fmt.coins(l.price()), x, y, Theme.TEXT);
        Ui.textRight(ctx, Fmt.num(l.amount()) + " (" + l.orders() + ")", x + w, y, Theme.MUTED);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (explainRect.contains(mx, my)) {
            explainScroll -= amount * 20;
            return true;
        }
        return false;
    }
}
