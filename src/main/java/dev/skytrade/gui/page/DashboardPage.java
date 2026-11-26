package dev.skytrade.gui.page;

import dev.skytrade.SkyTradeCore;
import dev.skytrade.api.ApiStatus;
import dev.skytrade.api.Endpoint;
import dev.skytrade.database.Repository;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Charts;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.portfolio.CompletedTrade;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DashboardPage extends Page {
    private volatile List<Repository.ValuePoint> history = List.of();
    private long lastLoad;

    public DashboardPage(SkyTradeScreen screen) {
        super(screen);
    }

    @Override
    public int minHeight() {
        return 330;
    }

    @Override
    public String title() {
        return "Dashboard";
    }

    @Override
    public void onOpen() {
        lastLoad = 0;
    }

    private void maybeLoad() {
        long now = System.currentTimeMillis();
        if (now - lastLoad < 60_000) return;
        lastLoad = now;
        core().tasks().supply("dash.history", () -> core().repository().portfolioHistory(now - 7 * 86_400_000L))
                .thenAccept(h -> history = h == null ? List.of() : h);
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        maybeLoad();
        PortfolioView pv = core().portfolio();
        int gap = 6;
        int cols = a.w() >= 560 ? 6 : 3;
        int statH = 46;
        int rows = cols == 6 ? 1 : 2;
        Rect statArea = a.top(rows * statH + (rows - 1) * gap);
        Rect[] r1 = statArea.top(statH).columns(cols, gap);
        Rect[] cells = new Rect[6];
        for (int i = 0; i < cols; i++) cells[i] = r1[i];
        if (rows == 2) {
            Rect[] r2 = new Rect(a.x(), a.y() + statH + gap, a.w(), statH).columns(3, gap);
            System.arraycopy(r2, 0, cells, 3, 3);
        }
        double change = pv.startingCapital() > 0 ? (pv.totalValue() - pv.startingCapital()) / pv.startingCapital() : 0;
        Ui.stat(ctx, cells[0], "Portfolio", Fmt.coins(pv.totalValue()), Theme.TEXT, Fmt.signedPct(change) + " since start", Theme.signed(change));
        Ui.stat(ctx, cells[1], "Available", Fmt.coins(pv.cash()), Theme.TEXT, Fmt.pct(pv.totalValue() > 0 ? pv.cash() / pv.totalValue() : 0) + " of value", Theme.MUTED);
        Ui.stat(ctx, cells[2], "Invested", Fmt.coins(pv.invested()), Theme.TEXT, pv.activeTrades().size() + " positions", Theme.MUTED);
        Ui.stat(ctx, cells[3], "Daily profit", Fmt.signedCoins(pv.dailyProfit()), Theme.signed(pv.dailyProfit()), "realized, last 24h", Theme.MUTED);
        Ui.stat(ctx, cells[4], "Weekly profit", Fmt.signedCoins(pv.weeklyProfit()), Theme.signed(pv.weeklyProfit()), "realized, last 7d", Theme.MUTED);
        Ui.stat(ctx, cells[5], "Profit / hour", Fmt.coins(pv.estimatedProfitPerHour()), Theme.signed(pv.estimatedProfitPerHour()), "open positions", Theme.MUTED);

        Rect rest = a.belowTop(statArea.h(), gap);
        int midH = Math.max(90, (rest.h() - gap) / 2);
        Rect mid = rest.top(midH);
        Rect bottom = rest.belowTop(midH, gap);
        int leftW = (int) (mid.w() * 0.62);
        Rect chart = mid.left(leftW);
        Rect status = mid.rightOf(leftW, gap);

        Ui.card(ctx, chart);
        Ui.heading(ctx, chart, "Portfolio performance (7d)");
        List<double[]> pts = new ArrayList<>();
        for (Repository.ValuePoint p : history) pts.add(new double[]{p.ts(), p.value()});
        if (pv.timestamp() > 0) pts.add(new double[]{pv.timestamp(), pv.totalValue()});
        Charts.line(ctx, new Rect(chart.x() + 6, chart.y() + 20, chart.w() - 12, chart.h() - 26),
                List.of(new Charts.Series(pts, Theme.BLUE, "Value")), Fmt::coins, true, mx, my);

        renderStatus(ctx, status);

        Rect opp = bottom.left(leftW);
        Rect recent = bottom.rightOf(leftW, gap);
        renderOpportunities(ctx, opp);
        renderRecent(ctx, recent, pv);
    }

    private void renderStatus(DrawContext ctx, Rect r) {
        Ui.card(ctx, r);
        Ui.heading(ctx, r, "Data & system status");
        long now = System.currentTimeMillis();
        ApiStatus st = core().market().status();
        int y = r.y() + 22;
        for (Endpoint e : Endpoint.values()) {
            ApiStatus.EndpointState s = st.get(e);
            String name = switch (e) {
                case BAZAAR -> "Bazaar";
                case AUCTIONS -> "Auctions";
                case AUCTIONS_ENDED -> "Ended";
                case ITEMS -> "Items";
            };
            int dot = s.lastSuccessAt == 0 ? Theme.FAINT : s.consecutiveFailures > 0 ? Theme.RED : Theme.GREEN;
            Ui.round(ctx, r.x() + 8, y + 2, 5, 5, 2, dot);
            Ui.text(ctx, name, r.x() + 17, y, Theme.TEXT);
            String age = s.dataTimestamp > 0 ? "data " + Fmt.duration(now - s.dataTimestamp) : s.lastSuccessAt > 0 ? "ok " + Fmt.age(s.lastSuccessAt, now) : "waiting";
            if (s.consecutiveFailures > 0) age = Ui.fit(s.lastError, r.w() / 2);
            Ui.textRight(ctx, age, r.right() - 8, y, s.consecutiveFailures > 0 ? Theme.RED : Theme.MUTED);
            y += 11;
        }
        y += 3;
        Ui.hline(ctx, r.x() + 8, r.right() - 8, y, Theme.BORDER);
        y += 5;
        var tr = core().traderStatus();
        var verdict = core().sandboxVerdict();
        line(ctx, r, y, "Sandbox", tr.active() ? "active" : "idle", tr.active() ? Theme.GREEN : Theme.MUTED);
        y += 11;
        if (y + 9 < r.bottom()) {
            Ui.text(ctx, tr.reason(), r.x() + 8, y, r.w() - 16, Theme.FAINT);
            y += 11;
        }
        if (y + 9 < r.bottom()) {
            line(ctx, r, y, "Environment", verdict.allowed() ? "private" : "blocked", verdict.allowed() ? Theme.GREEN : Theme.YELLOW);
            y += 11;
        }
        if (y + 9 < r.bottom()) {
            var sales = core().market().sales();
            line(ctx, r, y, "Sales tracked", Fmt.num(sales.totalSales()) + " / " + Fmt.num(sales.keyCount()) + " items", Theme.MUTED);
            y += 11;
        }
        if (y + 9 < r.bottom()) line(ctx, r, y, "Recipes", core().recipes().size() + " outputs", Theme.MUTED);
    }

    private void line(DrawContext ctx, Rect r, int y, String k, String v, int color) {
        Ui.text(ctx, k, r.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Ui.fit(v, r.w() / 2), r.right() - 8, y, color);
    }

    private void renderOpportunities(DrawContext ctx, Rect r) {
        Ui.card(ctx, r);
        Ui.heading(ctx, r, "Top opportunities (risk-adjusted profit/h)");
        SkyTradeCore.Results res = core().results();
        List<Opportunity> all = new ArrayList<>();
        all.addAll(res.bazaar());
        all.addAll(res.auctions());
        all.addAll(res.crafts());
        all.removeIf(o -> o.quantity() <= 0 || o.netProfit() <= 0);
        all.sort(Comparator.comparingDouble(Opportunity::adjustedProfitPerHour).reversed());
        int y = r.y() + 22;
        if (all.isEmpty()) {
            Ui.text(ctx, core().market().bazaar() == null ? "Waiting for market data..." : "No opportunities pass the current filters.", r.x() + 8, y, Theme.MUTED);
            return;
        }
        int rowH = 16;
        for (Opportunity o : all) {
            if (y + rowH > r.bottom() - 4) break;
            Rect row = new Rect(r.x() + 4, y - 2, r.w() - 8, rowH);
            if (row.contains(mouseX, mouseY)) Ui.round(ctx, row, 2, Theme.CARD_HOVER);
            Ui.item(ctx, ItemIcons.forId(o.itemId(), core().market().catalog()), r.x() + 8, y - 1, 0.75f);
            String kind = switch (o.kind()) {
                case BAZAAR_FLIP -> "BZ";
                case AUCTION_FLIP -> "AH";
                case CRAFT_FLIP -> "CR";
            };
            Ui.text(ctx, kind, r.x() + 24, y + 2, Theme.BLUE);
            // Right-aligned columns from the right edge; profit/h is dropped first when space is short.
            int right = r.right() - 8;
            Ui.textRight(ctx, "C" + Fmt.score(o.confidence()), right, y + 2, Theme.quality(o.confidence()));
            right -= 26;
            Ui.textRight(ctx, "R" + Fmt.score(o.risk()), right, y + 2, Theme.risk(o.risk()));
            right -= 28;
            Ui.textRight(ctx, Fmt.signedCoins(o.netProfit()), right, y + 2, Theme.signed(o.netProfit()));
            right -= 50;
            int nameX = r.x() + 40;
            if (right - nameX > 120) {
                Ui.textRight(ctx, Fmt.coins(o.adjustedProfitPerHour()) + "/h", right, y + 2, Theme.GREEN);
                right -= 56;
            }
            Ui.text(ctx, o.name(), nameX, y + 2, Math.max(20, right - nameX - 6), Theme.TEXT);
            final Opportunity target = o;
            clickable(row, () -> openDetail(target));
            y += rowH;
        }
    }

    private void openDetail(Opportunity o) {
        if (o instanceof dev.skytrade.bazaar.BazaarOpportunity b) screen.push(new BazaarDetailPage(screen, b.itemId()));
        else if (o instanceof dev.skytrade.auction.AuctionOpportunity a) screen.push(new AuctionDetailPage(screen, a));
        else screen.navigate(SkyTradeScreen.Nav.CRAFTS);
    }

    private void renderRecent(DrawContext ctx, Rect r, PortfolioView pv) {
        Ui.card(ctx, r);
        Ui.heading(ctx, r, "Recent transactions");
        int y = r.y() + 22;
        long now = System.currentTimeMillis();
        if (pv.recent().isEmpty()) {
            Ui.text(ctx, "No closed trades yet.", r.x() + 8, y, Theme.MUTED);
            y += 12;
        }
        for (CompletedTrade c : pv.recent()) {
            if (y + 10 > r.bottom() - 4) break;
            Ui.text(ctx, c.name(), r.x() + 8, y, r.w() - 110, Theme.TEXT);
            Ui.textRight(ctx, c.cancelled() ? "cancelled" : Fmt.signedCoins(c.realizedProfit()), r.right() - 56, y, c.cancelled() ? Theme.FAINT : Theme.signed(c.realizedProfit()));
            Ui.textRight(ctx, Fmt.duration(now - c.closedAt()), r.right() - 8, y, Theme.FAINT);
            y += 11;
        }
        for (var n : core().notifications().recent()) {
            if (y + 10 > r.bottom() - 4) break;
            Ui.text(ctx, n.title() + ": " + n.message(), r.x() + 8, y, r.w() - 16, Theme.FAINT);
            y += 11;
        }
    }
}
