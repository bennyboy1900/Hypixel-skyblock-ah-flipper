package dev.skytrade.gui.page;

import dev.skytrade.execution.Trade;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Table;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.util.*;

import static dev.skytrade.gui.widget.Table.Align.LEFT;
import static dev.skytrade.gui.widget.Table.Align.RIGHT;

/** Holdings, P&L and capital allocation of the paper/sandbox portfolio. */
public final class PortfolioPage extends Page {
    private static final int[] PALETTE = {0xFF4C8DFF, 0xFF3FC97A, 0xFFE3B341, 0xFFB37FEB, 0xFF36C5D6, 0xFFE5534B, 0xFFF08C4A, 0xFF8A919E};
    private final Table<Trade> table = new Table<>();

    public PortfolioPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<Trade>("Position", 90, 3f, LEFT, t -> t.name, t -> Theme.TEXT, Comparator.comparing(t -> t.name))
                        .icon(t -> ItemIcons.forId(t.itemId, core().market().catalog())))
                .column(new Table.Column<Trade>("Held", 34, .6f, RIGHT, t -> Fmt.num(Math.max(0, t.filledBuyQty - t.filledSellQty)), null, Comparator.comparingLong(Trade::heldQty)).priority(7))
                .column(new Table.Column<Trade>("Avg cost", 46, 1f, RIGHT, t -> Fmt.coins(t.filledBuyQty > 0 ? t.costBasis / t.filledBuyQty : t.buyPrice), null, Comparator.comparingDouble(t -> t.buyPrice)).priority(5))
                .column(new Table.Column<Trade>("Mark", 46, 1f, RIGHT, t -> Fmt.coins(t.markPrice), null, Comparator.comparingDouble(t -> t.markPrice)).priority(4))
                .column(new Table.Column<Trade>("Value", 46, 1f, RIGHT, t -> Fmt.coins(Math.max(0, t.filledBuyQty - t.filledSellQty) * t.markPrice), null,
                        Comparator.comparingDouble(t -> Math.max(0, t.filledBuyQty - t.filledSellQty) * t.markPrice)).priority(8))
                .column(new Table.Column<Trade>("Unrealized", 50, 1f, RIGHT, t -> Fmt.signedCoins(t.unrealizedProfit()), t -> Theme.signed(t.unrealizedProfit()), Comparator.comparingDouble(Trade::unrealizedProfit)).priority(9))
                .column(new Table.Column<Trade>("Realized", 50, 1f, RIGHT, t -> Fmt.signedCoins(t.realizedProfit()), t -> Theme.signed(t.realizedProfit()), Comparator.comparingDouble(Trade::realizedProfit)).priority(6))
                .column(new Table.Column<Trade>("State", 70, 1f, LEFT, t -> t.state.name(), t -> Theme.MUTED, null).priority(3))
                .sortBy(4, true)
                .emptyText("No open positions.");
    }

    @Override
    public int minHeight() {
        return 260;
    }

    @Override
    public String title() {
        return "Portfolio";
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        PortfolioView pv = core().portfolio();
        Rect[] cells = a.top(46).columns(5, 6);
        Ui.stat(ctx, cells[0], "Cash", Fmt.coins(pv.cash()), Theme.TEXT, "free to deploy", Theme.MUTED);
        Ui.stat(ctx, cells[1], "In orders", Fmt.coins(pv.escrow()), Theme.TEXT, "escrow in buy orders", Theme.MUTED);
        Ui.stat(ctx, cells[2], "Inventory", Fmt.coins(pv.inventoryValue()), Theme.TEXT, "marked at bid after tax", Theme.MUTED);
        Ui.stat(ctx, cells[3], "Unrealized", Fmt.signedCoins(pv.unrealized()), Theme.signed(pv.unrealized()), "open positions", Theme.MUTED);
        Ui.stat(ctx, cells[4], "Realized", Fmt.signedCoins(pv.realizedTotal()), Theme.signed(pv.realizedTotal()), "closed trades", Theme.MUTED);

        Rect alloc = new Rect(a.x(), a.y() + 52, a.w(), 52);
        Ui.card(ctx, alloc);
        Ui.heading(ctx, alloc, "Capital allocation");
        double total = Math.max(1, pv.totalValue());
        List<Map.Entry<String, Double>> items = new ArrayList<>(pv.exposureByItem().entrySet());
        items.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Rect bar = new Rect(alloc.x() + 8, alloc.y() + 20, alloc.w() - 16, 8);
        Ui.round(ctx, bar, 2, Theme.BORDER);
        int bx = bar.x();
        int legendX = alloc.x() + 8;
        int ly = alloc.y() + 34;
        int ci = 0;
        double other = 0;
        for (Map.Entry<String, Double> e : items) {
            if (ci >= PALETTE.length - 1) {
                other += e.getValue();
                continue;
            }
            int w = (int) Math.round(e.getValue() / total * bar.w());
            if (w > 0) ctx.fill(bx, bar.y(), bx + w, bar.bottom(), PALETTE[ci]);
            bx += w;
            String l = core().market().catalog().displayName(e.getKey()) + " " + Fmt.pct(e.getValue() / total);
            if (legendX + Ui.width(l) + 14 < alloc.right()) {
                Ui.round(ctx, legendX, ly + 1, 6, 6, 1, PALETTE[ci]);
                Ui.text(ctx, l, legendX + 9, ly, Theme.MUTED);
                legendX += Ui.width(l) + 18;
            }
            ci++;
        }
        if (other > 0) {
            int w = (int) Math.round(other / total * bar.w());
            ctx.fill(bx, bar.y(), bx + w, bar.bottom(), PALETTE[PALETTE.length - 1]);
            bx += w;
        }
        String cashL = "Cash " + Fmt.pct(pv.cash() / total);
        if (legendX + Ui.width(cashL) < alloc.right()) Ui.text(ctx, cashL, legendX, ly, Theme.FAINT);

        table.setRows(pv.activeTrades());
        table.render(ctx, new Rect(a.x(), a.y() + 110, a.w(), a.h() - 110), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return super.mouseClicked(mx, my, button) || table.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        return table.mouseScrolled(mx, my, amount);
    }
}
