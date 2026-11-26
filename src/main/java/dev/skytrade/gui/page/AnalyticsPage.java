package dev.skytrade.gui.page;

import dev.skytrade.database.Repository;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Charts;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.portfolio.CompletedTrade;
import dev.skytrade.portfolio.PerformanceAnalytics;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.simulation.Backtester;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/** Performance analytics, strategy comparison and backtesting. */
public final class AnalyticsPage extends Page {
    private volatile List<Repository.ValuePoint> history = List.of();
    private long lastLoad;
    private PerformanceAnalytics.Summary summary;
    private long lastSummary;
    private int backtestDays = 3;

    public AnalyticsPage(SkyTradeScreen screen) {
        super(screen);
    }

    @Override
    public int minHeight() {
        return 330;
    }

    @Override
    public String title() {
        return "Analytics";
    }

    @Override
    public void onOpen() {
        lastLoad = 0;
        lastSummary = 0;
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        long now = System.currentTimeMillis();
        PortfolioView pv = core().portfolio();
        if (now - lastLoad > 60_000) {
            lastLoad = now;
            core().tasks().supply("analytics.history", () -> core().repository().portfolioHistory(now - 30 * 86_400_000L))
                    .thenAccept(h -> history = h == null ? List.of() : h);
        }
        if (summary == null || now - lastSummary > 2_000) {
            lastSummary = now;
            double avg = history.isEmpty() ? pv.totalValue() : history.stream().mapToDouble(Repository.ValuePoint::value).average().orElse(pv.totalValue());
            summary = PerformanceAnalytics.summarize(core().portfolioService().completedTrades(), avg, now, 14);
        }
        PerformanceAnalytics.Summary s = summary;

        Rect[] cells = a.top(46).columns(6, 6);
        Ui.stat(ctx, cells[0], "Win rate", Fmt.pct(s.winRate()), Theme.quality(s.winRate()), s.wins() + " / " + s.trades() + " trades", Theme.MUTED);
        Ui.stat(ctx, cells[1], "Total profit", Fmt.signedCoins(s.totalProfit()), Theme.signed(s.totalProfit()), "realized", Theme.MUTED);
        Ui.stat(ctx, cells[2], "Predicted", Fmt.signedCoins(s.predictedProfit()), Theme.MUTED, "diff " + Fmt.signedCoins(s.totalProfit() - s.predictedProfit()), Theme.signed(s.totalProfit() - s.predictedProfit()));
        Ui.stat(ctx, cells[3], "Avg hold", Fmt.duration(s.avgHoldHours() * 3.6e6), Theme.TEXT, "per closed trade", Theme.MUTED);
        Ui.stat(ctx, cells[4], "Turnover", String.format("%.2fx/day", s.capitalTurnover()), Theme.TEXT, "capital deployed / value", Theme.MUTED);
        Ui.stat(ctx, cells[5], "Value", Fmt.coins(pv.totalValue()), Theme.TEXT, Fmt.signedCoins(pv.totalValue() - pv.startingCapital()), Theme.signed(pv.totalValue() - pv.startingCapital()));

        Rect rest = a.belowTop(46, 6);
        int rowH = (rest.h() - 6) / 2;
        Rect top = rest.top(rowH);
        Rect bottom = rest.belowTop(rowH, 6);
        Rect[] t = top.columns(2, 6);
        Rect[] b = bottom.columns(3, 6);

        Ui.card(ctx, t[0]);
        Ui.heading(ctx, t[0], "Portfolio performance (30d)");
        List<double[]> pts = new ArrayList<>();
        for (Repository.ValuePoint p : history) pts.add(new double[]{p.ts(), p.value()});
        if (pv.timestamp() > 0) pts.add(new double[]{pv.timestamp(), pv.totalValue()});
        Charts.line(ctx, new Rect(t[0].x() + 6, t[0].y() + 20, t[0].w() - 12, t[0].h() - 26), List.of(new Charts.Series(pts, Theme.BLUE, "Value")), Fmt::coins, true, mx, my);

        Ui.card(ctx, t[1]);
        Ui.heading(ctx, t[1], "Daily profit (14d)");
        String[] labels = new String[s.dailyProfit().length];
        for (int i = 0; i < labels.length; i++) {
            if (i % 2 == 0) labels[i] = String.valueOf(Instant.ofEpochMilli(s.dailyStart() + i * 86_400_000L).atZone(ZoneOffset.UTC).getDayOfMonth());
        }
        Charts.bars(ctx, new Rect(t[1].x() + 8, t[1].y() + 20, t[1].w() - 16, t[1].h() - 26), s.dailyProfit(), labels, Fmt::coins);

        // Strategy comparison
        Ui.card(ctx, b[0]);
        Ui.heading(ctx, b[0], "Profit by strategy");
        int y = b[0].y() + 22;
        for (Map.Entry<String, Double> e : s.profitByStrategy().entrySet()) {
            if (y + 9 > b[0].bottom() - 4) break;
            int[] w = s.winsByStrategy().getOrDefault(e.getKey(), new int[2]);
            Ui.text(ctx, e.getKey().replace("_FLIP", ""), b[0].x() + 8, y, Theme.TEXT);
            Ui.textRight(ctx, w[0] + "/" + w[1] + " won", b[0].right() - 70, y, Theme.MUTED);
            Ui.textRight(ctx, Fmt.signedCoins(e.getValue()), b[0].right() - 8, y, Theme.signed(e.getValue()));
            y += 11;
        }
        if (s.profitByStrategy().isEmpty()) Ui.text(ctx, "No closed trades yet.", b[0].x() + 8, y, Theme.MUTED);
        y += 6;
        if (y + 20 < b[0].bottom()) {
            Ui.text(ctx, "Profit by item", b[0].x() + 8, y, Theme.MUTED);
            y += 11;
            List<Map.Entry<String, Double>> items = new ArrayList<>(s.profitByItem().entrySet());
            items.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            for (Map.Entry<String, Double> e : items) {
                if (y + 9 > b[0].bottom() - 4) break;
                Ui.text(ctx, e.getKey(), b[0].x() + 8, y, b[0].w() - 80, Theme.TEXT);
                Ui.textRight(ctx, Fmt.signedCoins(e.getValue()), b[0].right() - 8, y, Theme.signed(e.getValue()));
                y += 10;
            }
        }

        Ui.card(ctx, b[1]);
        Ui.heading(ctx, b[1], "Largest simulated losses");
        y = b[1].y() + 22;
        for (CompletedTrade c : s.largestLosses()) {
            if (y + 9 > b[1].bottom() - 4) break;
            Ui.text(ctx, c.name(), b[1].x() + 8, y, b[1].w() - 70, Theme.TEXT);
            Ui.textRight(ctx, Fmt.signedCoins(c.realizedProfit()), b[1].right() - 8, y, Theme.RED);
            y += 10;
        }
        if (s.largestLosses().isEmpty()) Ui.text(ctx, "No losing trades.", b[1].x() + 8, y, Theme.MUTED);

        renderBacktest(ctx, b[2]);
    }

    private void renderBacktest(DrawContext ctx, Rect r) {
        Ui.card(ctx, r);
        Ui.heading(ctx, r, "Backtest: preset comparison");
        int x = r.x() + 8;
        int y = r.y() + 20;
        for (int d : new int[]{1, 3, 7}) {
            final int dd = d;
            x = button(ctx, x, y, d + "d", d == backtestDays ? Button.Style.PRIMARY : Button.Style.GHOST, true, () -> backtestDays = dd).right() + 2;
        }
        x = button(ctx, x + 4, y, "Run", Button.Style.SECONDARY, true, () -> core().runBacktest(backtestDays)).right() + 2;
        button(ctx, x, y, "Stop", Button.Style.GHOST, true, core()::cancelBacktest);
        y += 20;
        Ui.text(ctx, core().backtestStatus(), r.x() + 8, y, r.w() - 16, Theme.FAINT);
        y += 12;
        List<Backtester.Result> res = core().backtestResults();
        if (res.isEmpty()) {
            for (String l : Ui.wrap("Replays recorded bazaar history with no look-ahead. Needs collected history (the mod records it while running).", r.w() - 16)) {
                if (y + 9 > r.bottom() - 4) break;
                Ui.text(ctx, l, r.x() + 8, y, Theme.MUTED);
                y += 10;
            }
            return;
        }
        int c1 = r.x() + 8, c2 = r.right() - 110, c3 = r.right() - 56, c4 = r.right() - 8;
        Ui.text(ctx, "Preset", c1, y, Theme.MUTED);
        Ui.textRight(ctx, "Return", c2, y, Theme.MUTED);
        Ui.textRight(ctx, "Act/Pred", c3, y, Theme.MUTED);
        Ui.textRight(ctx, "MaxDD", c4, y, Theme.MUTED);
        y += 11;
        for (Backtester.Result b : res) {
            if (y + 9 > r.bottom() - 4) break;
            Ui.text(ctx, b.name().charAt(0) + b.name().substring(1).toLowerCase(), c1, y, Theme.TEXT);
            Ui.textRight(ctx, Fmt.signedPct(b.returnPct()), c2, y, Theme.signed(b.returnPct()));
            String ap = Fmt.coins(b.realized()) + "/" + Fmt.coins(b.predictedProfit());
            Ui.textRight(ctx, Ui.fit(ap, 60), c3, y, Theme.TEXT);
            Ui.textRight(ctx, Fmt.pct(b.maxDrawdown()), c4, y, Theme.RED);
            y += 10;
            if (y + 9 <= r.bottom() - 4) {
                Ui.text(ctx, "  " + b.trades() + " trades, " + b.wins() + " wins; " + b.note(), c1, y, r.w() - 16, Theme.FAINT);
                y += 11;
            }
        }
    }
}
