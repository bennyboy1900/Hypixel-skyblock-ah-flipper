package dev.skytrade.gui.page;

import dev.skytrade.auction.AuctionOpportunity;
import dev.skytrade.auction.DecodedAuction;
import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.*;
import dev.skytrade.util.Fmt;
import dev.skytrade.valuation.SaleRecord;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Valuation breakdown for one auction listing with comparable sales and competing listings. */
public final class AuctionDetailPage extends Page {
    private final AuctionOpportunity o;
    private double scroll;
    private Rect explainRect = new Rect(0, 0, 0, 0);
    private String status = "";

    public AuctionDetailPage(SkyTradeScreen screen, AuctionOpportunity o) {
        super(screen);
        this.o = o;
    }

    @Override
    public int minHeight() {
        return 300;
    }

    @Override
    public String title() {
        return "Auction House  >  " + o.name();
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        int x = button(ctx, a.x(), a.y(), "< Back", Button.Style.GHOST, true, screen::back).right() + 6;
        Ui.item(ctx, ItemIcons.forId(o.itemId(), core().market().catalog()), x, a.y(), 1f);
        Ui.text(ctx, o.name(), x + 20, a.y() + 1, Theme.TEXT);
        Ui.text(ctx, o.itemId() + "  " + o.attrs().rarity(), x + 20, a.y() + 10, Theme.FAINT);
        int bx = a.right();
        String cmd = o.navigationCommand();
        bx -= Button.width("Copy /viewauction");
        button(ctx, bx, a.y(), "Copy /viewauction", Button.Style.SECONDARY, true, () -> screen.copyToClipboard(cmd));
        bx -= Button.width("Paper trade") + 4;
        button(ctx, bx, a.y(), "Paper trade", Button.Style.PRIMARY, true, () -> core().paperTrade(o).thenAccept(s -> status = s));
        if (!status.isEmpty()) Ui.textRight(ctx, Ui.fit(status, a.w() / 2), a.right(), a.y() + 20, Theme.MUTED);

        Rect body = new Rect(a.x(), a.y() + 32, a.w(), a.h() - 32);
        int gap = 6;
        int leftW = (int) (body.w() * 0.55);
        Rect stats = new Rect(body.x(), body.y(), leftW, 70);
        Rect comps = new Rect(body.x(), body.y() + 70 + gap, leftW, body.h() - 70 - gap);
        Rect explain = new Rect(body.x() + leftW + gap, body.y(), body.w() - leftW - gap, body.h());

        Rect[] cells = stats.columns(4, gap);
        var v = o.valuation();
        Ui.stat(ctx, cells[0], "Listing", Fmt.coins(o.listingPrice()), Theme.TEXT, "BIN", Theme.MUTED);
        Ui.stat(ctx, cells[1], "Fair value", Fmt.coins(v.fairValue()), Theme.TEXT, v.matchLevel().toLowerCase() + ", " + v.comparables() + " comps", Theme.MUTED);
        Ui.stat(ctx, cells[2], "Net profit", Fmt.signedCoins(o.netProfit()), Theme.signed(o.netProfit()), "after " + Fmt.coins(o.fees()) + " fees", Theme.MUTED);
        Ui.stat(ctx, cells[3], "Sale time", Fmt.duration(v.saleHours() * 3.6e6), Theme.TEXT, "conf " + Fmt.score(o.confidence()) + "/100", Theme.quality(o.confidence()));

        Ui.card(ctx, comps);
        Ui.heading(ctx, comps, "Comparable sales vs. this listing");
        ItemAttributes at = o.attrs();
        List<SaleRecord> sales = v.matchLevel().equals("EXACT") ? core().market().sales().strict(Fingerprint.strict(at)) : core().market().sales().relaxed(Fingerprint.relaxed(at));
        List<double[]> pts = new ArrayList<>();
        for (SaleRecord s : sales) pts.add(new double[]{s.timestamp(), s.price()});
        long now = System.currentTimeMillis();
        List<double[]> listing = List.of(new double[]{pts.isEmpty() ? now - 3_600_000 : pts.get(0)[0], o.listingPrice()}, new double[]{now, o.listingPrice()});
        List<double[]> fair = List.of(new double[]{listing.get(0)[0], v.fairValue()}, new double[]{now, v.fairValue()});
        int chartH = Math.max(50, comps.h() / 2 - 10);
        Charts.line(ctx, new Rect(comps.x() + 6, comps.y() + 20, comps.w() - 12, chartH),
                List.of(new Charts.Series(pts, Theme.BLUE, "Sale"), new Charts.Series(listing, Theme.GREEN, "Listing"), new Charts.Series(fair, Theme.YELLOW, "Fair")),
                Fmt::coins, false, mx, my);
        int y = comps.y() + 26 + chartH;
        Ui.text(ctx, "Cheapest identical BIN listings", comps.x() + 8, y, Theme.MUTED);
        y += 11;
        List<DecodedAuction> group = core().auctionIndex().group(Fingerprint.strict(at));
        int shown = 0;
        for (DecodedAuction d : group) {
            if (y + 9 > comps.bottom() - 2 || shown++ >= 8) break;
            boolean self = d.auction().uuid().equals(o.uuid());
            Ui.text(ctx, (self ? "> " : "  ") + Fmt.coins(d.auction().startingBid()), comps.x() + 8, y, self ? Theme.GREEN : Theme.TEXT);
            Ui.textRight(ctx, Fmt.duration(Math.max(0, d.auction().end() - now)) + " left", comps.right() - 8, y, Theme.FAINT);
            y += 10;
        }
        if (group.isEmpty()) Ui.text(ctx, "Listing no longer present in the latest scan.", comps.x() + 8, y, Theme.YELLOW);

        Ui.card(ctx, explain);
        Ui.heading(ctx, explain, "Valuation explanation");
        explainRect = explain;
        List<String> lines = new ArrayList<>();
        lines.add("Attributes:");
        lines.add("  rarity " + at.rarity() + (at.recombobulated() ? " (recombobulated)" : "") + (at.reforge() != null ? ", reforge " + at.reforge() : ""));
        if (at.dungeonStars() + at.masterStars() > 0) lines.add("  stars " + at.dungeonStars() + " + master " + at.masterStars());
        if (at.hotPotatoBooks() + at.fumingPotatoBooks() > 0) lines.add("  potato books " + at.hotPotatoBooks() + " hot, " + at.fumingPotatoBooks() + " fuming");
        if (at.ultimateEnchant() != null) lines.add("  ultimate " + at.ultimateEnchant() + " " + at.ultimateLevel());
        if (!at.enchantments().isEmpty()) lines.add("  " + at.enchantments().size() + " enchantments");
        if (!at.gems().isEmpty()) lines.add("  gems " + String.join(", ", at.gems().values()));
        if (!at.abilityScrolls().isEmpty()) lines.add("  scrolls " + String.join(", ", at.abilityScrolls()));
        if (at.isPet()) lines.add("  pet " + at.petType() + " lvl " + at.petLevel() + (at.petHeldItem() != null ? ", item " + at.petHeldItem() : ""));
        for (Map.Entry<String, Integer> e : at.attributes().entrySet()) lines.add("  attribute " + e.getKey() + " " + e.getValue());
        lines.add("");
        lines.addAll(o.explanation());
        List<String> wrapped = new ArrayList<>();
        for (String l : lines) wrapped.addAll(l.isEmpty() ? List.of("") : Ui.wrap(l, explain.w() - 16));
        Rect clip = new Rect(explain.x() + 1, explain.y() + 20, explain.w() - 2, explain.h() - 22);
        scroll = Math.max(0, Math.min(scroll, wrapped.size() * 10 - clip.h() + 4));
        ctx.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
        int yy = clip.y() + 2 - (int) scroll;
        for (String l : wrapped) {
            Ui.text(ctx, l, explain.x() + 8, yy, l.startsWith("Warning") ? Theme.YELLOW : l.startsWith("  ") ? Theme.MUTED : Theme.TEXT);
            yy += 10;
        }
        ctx.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (explainRect.contains(mx, my)) {
            scroll -= amount * 20;
            return true;
        }
        return false;
    }
}
