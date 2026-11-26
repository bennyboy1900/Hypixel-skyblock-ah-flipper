package dev.skytrade.gui.page;

import dev.skytrade.crafting.CostBreakdown;
import dev.skytrade.crafting.CraftOpportunity;
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

public final class CraftPage extends Page {
    private final Table<CraftOpportunity> table = new Table<>();
    private CraftOpportunity selected;
    private String status = "";

    public CraftPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<CraftOpportunity>("Item", 90, 3f, LEFT, CraftOpportunity::name, o -> Theme.TEXT,
                        Comparator.comparing(CraftOpportunity::name)).icon(o -> ItemIcons.forId(o.itemId(), core().market().catalog())))
                .column(new Table.Column<CraftOpportunity>("Type", 36, .5f, LEFT, CraftOpportunity::type, o -> Theme.BLUE, Comparator.comparing(CraftOpportunity::type)).priority(3))
                .column(new Table.Column<CraftOpportunity>("Cost", 44, 1f, RIGHT, o -> Fmt.coins(o.unitCost()), null, Comparator.comparingDouble(CraftOpportunity::unitCost)).priority(7))
                .column(new Table.Column<CraftOpportunity>("Sale value", 50, 1f, RIGHT, o -> Fmt.coins(o.unitSaleValue()), null, Comparator.comparingDouble(CraftOpportunity::unitSaleValue)).priority(7))
                .column(new Table.Column<CraftOpportunity>("Qty", 30, .5f, RIGHT, o -> Fmt.num(o.quantity()), null, Comparator.comparingLong(CraftOpportunity::quantity)).priority(4))
                .column(new Table.Column<CraftOpportunity>("Profit", 44, 1f, RIGHT, o -> Fmt.signedCoins(o.netProfit()), o -> Theme.signed(o.netProfit()), Comparator.comparingDouble(CraftOpportunity::netProfit)).priority(8))
                .column(new Table.Column<CraftOpportunity>("Profit/h", 46, 1f, RIGHT, o -> Fmt.coins(o.profitPerHour()), o -> Theme.GREEN, Comparator.comparingDouble(CraftOpportunity::profitPerHour)).priority(9))
                .column(new Table.Column<CraftOpportunity>("Venue", 44, .6f, LEFT, CraftOpportunity::saleVenue, o -> Theme.MUTED, null).priority(2))
                .column(new Table.Column<CraftOpportunity>("Conf", 28, .5f, RIGHT, o -> Fmt.score(o.confidence()), o -> Theme.quality(o.confidence()), Comparator.comparingDouble(CraftOpportunity::confidence)).priority(5))
                .sortBy(6, true)
                .emptyText("No profitable verified recipes yet (recipes load in the background).")
                .onSelect(o -> selected = o);
    }

    @Override
    public int minHeight() {
        return 190;
    }

    @Override
    public String title() {
        return "Craft & Upgrade Flips";
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        var res = core().results();
        Ui.text(ctx, core().recipes().size() + " items with verified recipes  |  analysed " + (res.craftComputedAt() > 0 ? Fmt.age(res.craftComputedAt(), System.currentTimeMillis()) : "-"),
                a.x(), a.y() + 4, Theme.MUTED);
        table.setRows(res.crafts());
        table.rowHeight(core().config().appearance.compactTables ? 13 : 16);
        int sideW = selected != null ? Math.max(160, a.w() / 3) : 0;
        table.render(ctx, new Rect(a.x(), a.y() + 18, a.w() - (sideW > 0 ? sideW + 6 : 0), a.h() - 18), mx, my);
        if (selected == null) return;
        Rect side = new Rect(a.right() - sideW, a.y() + 18, sideW, a.h() - 18);
        Ui.card(ctx, side);
        Ui.heading(ctx, side, selected.name());
        int y = side.y() + 20;
        Ui.text(ctx, "Materials (per unit)", side.x() + 8, y, Theme.MUTED);
        y += 11;
        for (CostBreakdown c : selected.materials()) {
            if (y + 18 > side.bottom() - 40) break;
            Ui.item(ctx, ItemIcons.forId(c.itemId(), core().market().catalog()), side.x() + 8, y, 0.75f);
            Ui.text(ctx, Fmt.num(c.quantity()) + "x " + core().market().catalog().displayName(c.itemId()), side.x() + 24, y + 1, side.w() - 80, Theme.TEXT);
            Ui.textRight(ctx, Fmt.coins(c.total()), side.right() - 8, y + 1, Theme.TEXT);
            Ui.text(ctx, Fmt.coins(c.unitCost()) + " each, " + c.method(), side.x() + 24, y + 10, side.w() - 32, Theme.FAINT);
            y += 21;
        }
        Ui.hline(ctx, side.x() + 8, side.right() - 8, y, Theme.BORDER);
        y += 4;
        Ui.text(ctx, "Total cost", side.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Fmt.coins(selected.unitCost()), side.right() - 8, y, Theme.TEXT);
        y += 11;
        Ui.text(ctx, "Conservative sale", side.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Fmt.coins(selected.unitSaleValue()), side.right() - 8, y, Theme.TEXT);
        y += 14;
        for (String l : selected.explanation()) {
            for (String w : Ui.wrap(l, side.w() - 16)) {
                if (y + 9 > side.bottom() - 22) break;
                Ui.text(ctx, w, side.x() + 8, y, Theme.FAINT);
                y += 10;
            }
        }
        button(ctx, side.x() + 8, side.bottom() - 20, "Paper trade", Button.Style.PRIMARY, selected.quantity() > 0,
                () -> core().paperTrade(selected).thenAccept(s -> status = s));
        if (!status.isEmpty()) Ui.text(ctx, status, side.x() + 90, side.bottom() - 16, side.w() - 96, Theme.MUTED);
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
