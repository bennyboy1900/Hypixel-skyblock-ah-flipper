package dev.skytrade.gui.page;

import dev.skytrade.external.ExternalDeal;
import dev.skytrade.gui.ItemIcons;
import dev.skytrade.gui.SkyTradeScreen;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Button;
import dev.skytrade.gui.widget.Rect;
import dev.skytrade.gui.widget.Table;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.util.Fmt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;

import java.util.Comparator;

import static dev.skytrade.gui.widget.Table.Align.LEFT;
import static dev.skytrade.gui.widget.Table.Align.RIGHT;

/** Coflnet's bazaar, craft and Kat flip lists, after the mod re-checked them against live data. */
public final class DealsPage extends Page {
    private final Table<ExternalDeal> table = new Table<>();
    private ExternalDeal selected;

    public DealsPage(SkyTradeScreen screen) {
        super(screen);
        table.column(new Table.Column<ExternalDeal>("Item", 100, 3f, LEFT, ExternalDeal::name, o -> Theme.TEXT,
                        Comparator.comparing(ExternalDeal::name)).icon(o -> ItemIcons.forId(o.itemId() == null ? "" : o.itemId(), core().market().catalog())))
                .column(new Table.Column<ExternalDeal>("Type", 60, .8f, LEFT, ExternalDeal::source, o -> Theme.BLUE, Comparator.comparing(ExternalDeal::source)).priority(6))
                .column(new Table.Column<ExternalDeal>("Cost", 46, 1f, RIGHT, o -> Fmt.coins(o.cost()), null, Comparator.comparingDouble(ExternalDeal::cost)).priority(7))
                .column(new Table.Column<ExternalDeal>("Net profit", 50, 1f, RIGHT, o -> Fmt.signedCoins(o.netProfit()), o -> Theme.signed(o.netProfit()), Comparator.comparingDouble(ExternalDeal::netProfit)).priority(9))
                .column(new Table.Column<ExternalDeal>("ROI", 36, .6f, RIGHT, o -> Fmt.pct(o.roi()), o -> Theme.signed(o.roi()), Comparator.comparingDouble(ExternalDeal::roi)).priority(5))
                .column(new Table.Column<ExternalDeal>("Time", 44, .7f, RIGHT, o -> Double.isFinite(o.hours()) ? Fmt.duration(o.hours() * 3.6e6) : "-", null, Comparator.comparingDouble(o -> Double.isFinite(o.hours()) ? o.hours() : 1e9)).priority(4))
                .column(new Table.Column<ExternalDeal>("Volume", 40, .6f, RIGHT, o -> Fmt.num(o.volume()), null, Comparator.comparingDouble(ExternalDeal::volume)).priority(3))
                .sortBy(3, true)
                .emptyText("No verified deals yet. Coflnet lists load in the background (see /skytrade status).")
                .onSelect(o -> selected = o);
    }

    @Override
    public int minHeight() {
        return 190;
    }

    @Override
    public String title() {
        return "Deals (Coflnet, re-checked)";
    }

    @Override
    protected void render(DrawContext ctx, Rect a, int mx, int my, float delta) {
        var d = core().deals();
        Ui.text(ctx, d.deals().size() + " deals still profitable with live prices  |  checked "
                + (d.computedAt() > 0 ? Fmt.age(d.computedAt(), System.currentTimeMillis()) : "-"), a.x(), a.y() + 4, Theme.MUTED);
        table.setRows(d.deals());
        table.rowHeight(core().config().appearance.compactTables ? 13 : 16);
        int sideW = selected != null ? Math.max(170, a.w() / 3) : 0;
        table.render(ctx, new Rect(a.x(), a.y() + 18, a.w() - (sideW > 0 ? sideW + 6 : 0), a.h() - 18), mx, my);
        if (selected == null) return;
        Rect side = new Rect(a.right() - sideW, a.y() + 18, sideW, a.h() - 18);
        Ui.card(ctx, side);
        Ui.heading(ctx, side, selected.name());
        int y = side.y() + 20;
        Ui.text(ctx, "Cost", side.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Fmt.coins(selected.cost()), side.right() - 8, y, Theme.TEXT);
        y += 11;
        Ui.text(ctx, "Sale after fees", side.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Fmt.coins(selected.sale()), side.right() - 8, y, Theme.TEXT);
        y += 11;
        Ui.text(ctx, "Net profit", side.x() + 8, y, Theme.MUTED);
        Ui.textRight(ctx, Fmt.signedCoins(selected.netProfit()), side.right() - 8, y, Theme.signed(selected.netProfit()));
        y += 14;
        for (String l : selected.explanation()) {
            for (String w : Ui.wrap(l, side.w() - 16)) {
                if (y + 9 > side.bottom() - 22) break;
                Ui.text(ctx, w, side.x() + 8, y, Theme.FAINT);
                y += 10;
            }
        }
        String cmd = selected.command();
        if (cmd != null) {
            // Types the command into the chat box; the player presses Enter themselves.
            button(ctx, side.x() + 8, side.bottom() - 20, "Type in chat", Button.Style.PRIMARY, true,
                    () -> MinecraftClient.getInstance().setScreen(new ChatScreen(cmd, false)));
            button(ctx, side.x() + 90, side.bottom() - 20, "Copy", Button.Style.SECONDARY, true, () -> screen.copyToClipboard(cmd));
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
