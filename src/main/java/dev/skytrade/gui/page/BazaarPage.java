package dev.skytrade.gui.page;

import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Table;
import dev.skytrade.gui.widget.TextBox;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.util.Fmt;
import net.minecraft.client.gui.DrawContext;

import java.util.Comparator;
import java.util.Locale;

import static dev.skytrade.gui.widget.Table.Align.LEFT;
import static dev.skytrade.gui.widget.Table.Align.RIGHT;

public final class BazaarPage extends Page {
    private final TextBox search = new TextBox("Search items...", 40);
    private final Table<BazaarOpportunity> table = new Table<>();
    private boolean hideFlagged = false;
    private boolean affordableOnly = true;
    private String lastQuery = "";

    public BazaarPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<BazaarOpportunity>("Item", 90, 3f, LEFT, BazaarOpportunity::name, o -> Theme.TEXT,
                        Comparator.comparing(BazaarOpportunity::name)).icon(o -> ItemIcons.forId(o.itemId(), core().market().catalog())))
                .column(new Table.Column<BazaarOpportunity>("Entry", 44, 1f, RIGHT, o -> Fmt.coins(o.entryPrice()), null, Comparator.comparingDouble(BazaarOpportunity::entryPrice)).priority(7))
                .column(new Table.Column<BazaarOpportunity>("Exit", 44, 1f, RIGHT, o -> Fmt.coins(o.exitPrice()), null, Comparator.comparingDouble(BazaarOpportunity::exitPrice)).priority(7))
                .column(new Table.Column<BazaarOpportunity>("Margin", 40, .8f, RIGHT, o -> Fmt.pct(netMargin(o)), o -> Theme.signed(netMargin(o)), Comparator.comparingDouble(this::netMargin)).priority(5))
                .column(new Table.Column<BazaarOpportunity>("Vol/wk", 40, .8f, RIGHT, o -> Fmt.num(o.weeklyVolume()), null, Comparator.comparingDouble(BazaarOpportunity::weeklyVolume)).priority(3))
                .column(new Table.Column<BazaarOpportunity>("Hold", 42, .8f, RIGHT, o -> Fmt.duration(o.holdHours() * 3.6e6), null, Comparator.comparingDouble(BazaarOpportunity::holdHours)).priority(4))
                .column(new Table.Column<BazaarOpportunity>("Invest", 44, 1f, RIGHT, o -> Fmt.coins(o.capital()), null, Comparator.comparingDouble(BazaarOpportunity::capital)).priority(4))
                .column(new Table.Column<BazaarOpportunity>("Profit", 44, 1f, RIGHT, o -> Fmt.signedCoins(o.netProfit()), o -> Theme.signed(o.netProfit()), Comparator.comparingDouble(BazaarOpportunity::netProfit)).priority(8))
                .column(new Table.Column<BazaarOpportunity>("Profit/h", 46, 1f, RIGHT, o -> Fmt.coins(o.profitPerHour()), o -> Theme.GREEN, Comparator.comparingDouble(BazaarOpportunity::profitPerHour)).priority(9))
                .column(new Table.Column<BazaarOpportunity>("Risk", 28, .5f, RIGHT, o -> Fmt.score(o.risk()), o -> Theme.risk(o.risk()), Comparator.comparingDouble(BazaarOpportunity::risk)).priority(6))
                .column(new Table.Column<BazaarOpportunity>("Conf", 28, .5f, RIGHT, o -> Fmt.score(o.confidence()), o -> Theme.quality(o.confidence()), Comparator.comparingDouble(BazaarOpportunity::confidence)).priority(6))
                .column(new Table.Column<BazaarOpportunity>("Score", 34, .6f, RIGHT, o -> Fmt.num(o.score()), o -> Theme.BLUE, Comparator.comparingDouble(BazaarOpportunity::score)).priority(2))
                .sortBy(11, true)
                .emptyText("Waiting for bazaar data...")
                .onSelect(o -> screen.push(new BazaarDetailPage(screen, o.itemId())));
        updateFilter();
    }

    /** Net margin per unit after the configured bazaar tax. */
    double netMargin(BazaarOpportunity o) {
        double tax = core().config().fees.bazaarTax;
        return o.entryPrice() > 0 ? (o.exitPrice() * (1 - tax) - o.entryPrice()) / o.entryPrice() : 0;
    }

    @Override
    public int minHeight() {
        return 170;
    }

    @Override
    public String title() {
        return "Bazaar";
    }

    private void updateFilter() {
        String q = search.text().toLowerCase(Locale.ROOT).trim();
        table.setFilter(o -> (q.isEmpty() || o.name().toLowerCase(Locale.ROOT).contains(q) || o.itemId().toLowerCase(Locale.ROOT).contains(q))
                && (!hideFlagged || o.flags().isEmpty())
                && (!affordableOnly || o.quantity() > 0));
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        if (!search.text().equals(lastQuery)) {
            lastQuery = search.text();
            updateFilter();
        }
        int sw = Math.min(200, a.w() / 3);
        search.render(ctx, new Rect(a.x(), a.y(), sw, 16));
        int x = a.x() + sw + 6;
        x = button(ctx, x, a.y(), (affordableOnly ? "[x] " : "[ ] ") + "Sized only", Button.Style.SECONDARY, true, () -> { affordableOnly = !affordableOnly; updateFilter(); }).right() + 4;
        x = button(ctx, x, a.y(), (hideFlagged ? "[x] " : "[ ] ") + "Hide flagged", Button.Style.SECONDARY, true, () -> { hideFlagged = !hideFlagged; updateFilter(); }).right();
        var res = core().results();
        String info = table.view().size() + " of " + res.bazaar().size() + "  |  analysed " + (res.bazaarComputedAt() > 0 ? Fmt.age(res.bazaarComputedAt(), System.currentTimeMillis()) : "-");
        if (Ui.width(info) > a.right() - x - 8) info = table.view().size() + "/" + res.bazaar().size();
        if (Ui.width(info) <= a.right() - x - 8) Ui.textRight(ctx, info, a.right(), a.y() + 4, Theme.MUTED);
        table.setRows(res.bazaar());
        table.rowHeight(core().config().appearance.compactTables ? 13 : 16);
        table.render(ctx, new Rect(a.x(), a.y() + 22, a.w(), a.h() - 22), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (search.mouseClicked(mx, my)) return true;
        if (super.mouseClicked(mx, my, button)) return true;
        return table.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        return table.mouseScrolled(mx, my, amount);
    }

    @Override
    public boolean keyPressed(int key, int mods) {
        return search.keyPressed(key, mods);
    }

    @Override
    public boolean charTyped(String chars) {
        return search.charTyped(chars);
    }
}
