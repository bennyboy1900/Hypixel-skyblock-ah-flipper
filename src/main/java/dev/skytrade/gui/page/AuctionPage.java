package dev.skytrade.gui.page;

import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
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

public final class AuctionPage extends Page {
    private final TextBox search = new TextBox("Search items...", 40);
    private final Table<AuctionOpportunity> table = new Table<>();
    private String lastQuery = "";

    public AuctionPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<AuctionOpportunity>("Item", 100, 3f, LEFT, AuctionOpportunity::name, o -> Theme.TEXT,
                        Comparator.comparing(AuctionOpportunity::name)).icon(o -> ItemIcons.forId(o.itemId(), core().market().catalog())))
                .column(new Table.Column<AuctionOpportunity>("Price", 46, 1f, RIGHT, o -> Fmt.coins(o.listingPrice()), null, Comparator.comparingLong(AuctionOpportunity::listingPrice)).priority(8))
                .column(new Table.Column<AuctionOpportunity>("Fair value", 50, 1f, RIGHT, o -> Fmt.coins(o.valuation().fairValue()), null, Comparator.comparingDouble(o -> o.valuation().fairValue())).priority(7))
                .column(new Table.Column<AuctionOpportunity>("Net profit", 50, 1f, RIGHT, o -> Fmt.signedCoins(o.netProfit()), o -> Theme.signed(o.netProfit()), Comparator.comparingDouble(AuctionOpportunity::netProfit)).priority(9))
                .column(new Table.Column<AuctionOpportunity>("ROI", 36, .6f, RIGHT, o -> Fmt.pct(o.roi()), o -> Theme.signed(o.roi()), Comparator.comparingDouble(AuctionOpportunity::roi)).priority(5))
                .column(new Table.Column<AuctionOpportunity>("Sells in", 44, .7f, RIGHT, o -> Fmt.duration(o.valuation().saleHours() * 3.6e6), null, Comparator.comparingDouble(AuctionOpportunity::holdHours)).priority(6))
                .column(new Table.Column<AuctionOpportunity>("Comps", 32, .5f, RIGHT, o -> o.valuation().comparables() + (o.valuation().matchLevel().equals("EXACT") ? "" : "~"), null, Comparator.comparingInt(o -> o.valuation().comparables())).priority(3))
                .column(new Table.Column<AuctionOpportunity>("Conf", 28, .5f, RIGHT, o -> Fmt.score(o.confidence()), o -> Theme.quality(o.confidence()), Comparator.comparingDouble(AuctionOpportunity::confidence)).priority(6))
                .column(new Table.Column<AuctionOpportunity>("Upgrades", 70, 2f, LEFT, o -> String.join(", ", o.upgrades()), o -> Theme.MUTED, null).priority(2))
                .column(new Table.Column<AuctionOpportunity>("Score", 34, .6f, RIGHT, o -> Fmt.num(o.score()), o -> Theme.BLUE, Comparator.comparingDouble(AuctionOpportunity::score)).priority(1))
                .sortBy(9, true)
                .emptyText("Waiting for an auction scan and enough completed-sale history...")
                .onSelect(o -> screen.push(new AuctionDetailPage(screen, o)));
    }

    @Override
    public int minHeight() {
        return 170;
    }

    @Override
    public String title() {
        return "Auction House";
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        if (!search.text().equals(lastQuery)) {
            lastQuery = search.text();
            String q = lastQuery.toLowerCase(Locale.ROOT).trim();
            table.setFilter(o -> q.isEmpty() || o.name().toLowerCase(Locale.ROOT).contains(q) || o.itemId().toLowerCase(Locale.ROOT).contains(q));
        }
        search.render(ctx, new Rect(a.x(), a.y(), Math.min(200, a.w() / 3), 16));
        var idx = core().auctionIndex();
        var snap = core().market().auctions();
        String progress = core().market().auctionScanProgress();
        String info = snap.auctions().size() + " listings, " + idx.groups().size() + " item groups, "
                + Fmt.num(core().market().sales().totalSales()) + " sales tracked"
                + (progress.isEmpty() ? "" : "  |  scan " + progress);
        Ui.textRight(ctx, Ui.fit(info, a.w() - 210), a.right(), a.y() + 4, Theme.MUTED);
        table.setRows(core().results().auctions());
        table.rowHeight(core().config().appearance.compactTables ? 13 : 16);
        table.render(ctx, new Rect(a.x(), a.y() + 22, a.w(), a.h() - 22), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (search.mouseClicked(mx, my)) return true;
        return super.mouseClicked(mx, my, button) || table.mouseClicked(mx, my, button);
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
