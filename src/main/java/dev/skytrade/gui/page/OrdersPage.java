package dev.skytrade.gui.page;

import dev.skytrade.execution.Trade;
import dev.skytrade.execution.TradeState;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Table;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.util.Comparator;

import static dev.skytrade.gui.widget.Table.Align.LEFT;
import static dev.skytrade.gui.widget.Table.Align.RIGHT;

/** All simulated active orders with live progress. */
public final class OrdersPage extends Page {
    private final Table<Trade> table = new Table<>();
    private Trade selected;

    public OrdersPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<Trade>("Type", 34, .5f, LEFT, OrdersPage::type, t -> isSell(t) ? Theme.RED : Theme.GREEN, Comparator.comparing(OrdersPage::type)).priority(9))
                .column(new Table.Column<Trade>("Item", 90, 3f, LEFT, t -> t.name, t -> Theme.TEXT, Comparator.comparing(t -> t.name))
                        .icon(t -> ItemIcons.forId(t.itemId, core().market().catalog())))
                .column(new Table.Column<Trade>("Qty", 34, .5f, RIGHT, t -> Fmt.num(isSell(t) ? t.sellListedQty : t.targetQty), null, Comparator.comparingLong(t -> t.targetQty)).priority(6))
                .column(new Table.Column<Trade>("Price", 44, 1f, RIGHT, t -> Fmt.coins(isSell(t) ? t.sellPrice : t.buyPrice), null, Comparator.comparingDouble(t -> t.buyPrice)).priority(7))
                .column(new Table.Column<Trade>("Progress", 60, 1f, RIGHT, t -> Fmt.pct(t.progress()), null, Comparator.comparingDouble(Trade::progress)).priority(8))
                .column(new Table.Column<Trade>("Remaining", 44, .7f, RIGHT, t -> Fmt.num(isSell(t) ? t.heldQty() : t.remainingBuyQty()), null, Comparator.comparingLong(Trade::remainingBuyQty)).priority(3))
                .column(new Table.Column<Trade>("Age", 44, .7f, RIGHT, t -> Fmt.duration(System.currentTimeMillis() - t.createdAt), null, Comparator.comparingLong(t -> -t.createdAt)).priority(4))
                .column(new Table.Column<Trade>("Exp. profit", 50, 1f, RIGHT, t -> Fmt.signedCoins(t.predictedProfit), t -> Theme.signed(t.predictedProfit), Comparator.comparingDouble(t -> t.predictedProfit)).priority(5))
                .column(new Table.Column<Trade>("State", 70, 1.2f, LEFT, t -> t.state.name(), OrdersPage::stateColor, Comparator.comparing(t -> t.state)).priority(8))
                .column(new Table.Column<Trade>("Source", 40, .6f, LEFT, t -> t.manual ? "paper" : "auto", t -> Theme.MUTED, null).priority(1))
                .sortBy(6, false)
                .emptyText("No active simulated orders. Open a paper trade from a detail page, or enable sandbox auto trading.")
                .onSelect(t -> selected = t);
    }

    static boolean isSell(Trade t) {
        return t.state == TradeState.SELL_ORDER_ACTIVE || (t.state == TradeState.BUY_COMPLETED);
    }

    static String type(Trade t) {
        String side = isSell(t) ? "SELL" : "BUY";
        return switch (t.kind) {
            case BAZAAR_FLIP -> side;
            case AUCTION_FLIP -> isSell(t) ? "LIST" : "BIN";
            case CRAFT_FLIP -> isSell(t) ? "SELL" : "CRAFT";
        };
    }

    static int stateColor(Trade t) {
        return switch (t.state) {
            case ERROR, RECOVERY -> Theme.RED;
            case PAUSED -> Theme.YELLOW;
            case PARTIALLY_FILLED, SELL_ORDER_ACTIVE -> Theme.BLUE;
            default -> Theme.MUTED;
        };
    }

    @Override
    public int minHeight() {
        return 180;
    }

    @Override
    public String title() {
        return "Orders";
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        var pv = core().portfolio();
        int x = a.x();
        x = button(ctx, x, a.y(), "Cancel selected", Button.Style.SECONDARY, selected != null && !isSell(selected), () -> {
            core().cancelTrade(selected.id);
            selected = null;
        }).right() + 4;
        x = button(ctx, x, a.y(), "Pause all", Button.Style.SECONDARY, true, core()::pause).right() + 4;
        x = button(ctx, x, a.y(), "Resume", Button.Style.SECONDARY, true, core()::resume).right() + 4;
        button(ctx, x, a.y(), "EMERGENCY STOP", Button.Style.DANGER, !core().config().risk.emergencyStop, core()::emergencyStop);
        var br = core().risk().breaker().last();
        String info = pv.activeTrades().size() + " active  |  escrow " + Fmt.coins(pv.escrow()) + (br.tradingAllowed() ? "" : "  |  " + br.reason());
        Ui.textRight(ctx, Ui.fit(info, a.w() / 3), a.right(), a.y() + 4, br.tradingAllowed() ? Theme.MUTED : Theme.YELLOW);
        table.setRows(pv.activeTrades());
        Rect tr = new Rect(a.x(), a.y() + 22, a.w(), a.h() - 22 - (selected != null ? 40 : 0));
        table.render(ctx, tr, mx, my);
        if (selected != null) {
            Trade live = null;
            for (Trade t : pv.activeTrades()) if (t.id.equals(selected.id)) live = t;
            Rect info2 = new Rect(a.x(), a.bottom() - 34, a.w(), 34);
            Ui.card(ctx, info2);
            if (live == null) {
                Ui.text(ctx, "Trade closed.", info2.x() + 8, info2.y() + 7, Theme.MUTED);
                return;
            }
            Ui.text(ctx, live.name + "  " + live.state + "  " + live.note, info2.x() + 8, info2.y() + 7, info2.w() - 16, Theme.TEXT);
            String d = "bought " + live.filledBuyQty + "/" + live.targetQty + "  sold " + live.filledSellQty + "  cost " + Fmt.coins(live.costBasis)
                    + "  proceeds " + Fmt.coins(live.proceeds) + "  fees " + Fmt.coins(live.fees) + "  reprices " + live.repriceCount
                    + (live.lastError.isEmpty() ? "" : "  last error: " + live.lastError);
            Ui.text(ctx, d, info2.x() + 8, info2.y() + 19, info2.w() - 120, Theme.MUTED);
            Ui.progress(ctx, info2.right() - 108, info2.y() + 21, 100, 5, live.progress(), Theme.BLUE);
        }
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
