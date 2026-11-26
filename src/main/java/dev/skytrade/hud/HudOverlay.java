package dev.skytrade.hud;

import dev.skytrade.SkyTradeCore;
import dev.skytrade.api.ApiStatus;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.gui.Theme;
import dev.skytrade.gui.widget.Ui;
import dev.skytrade.market.DataFreshness;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.Fmt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Small, optional status overlay. Reads only published immutable state; does no work of its own. */
public final class HudOverlay {
    private record Line(String label, String value, int color) {}

    private HudOverlay() {}

    public static void render(DrawContext ctx, SkyTradeCore core, boolean force) {
        SkyTradeConfig.Hud h = core.config().hud;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!force && (!h.enabled || mc.options.hudHidden || mc.currentScreen instanceof dev.skytrade.gui.SkyTradeScreen)) return;
        List<Line> lines = lines(core, h);
        if (lines.isEmpty()) return;
        int[] size = size(lines);
        float alpha = h.opacity;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(h.x, h.y);
        ctx.getMatrices().scale(h.scale, h.scale);
        Ui.round(ctx, 0, 0, size[0], size[1], 3, Theme.withAlpha(0xFF0E1013, alpha));
        Ui.round(ctx, 0, 0, 2, size[1], 1, Theme.withAlpha(Theme.BLUE, Math.max(0.4f, alpha)));
        Ui.text(ctx, "SkyTrade", 6, 4, Theme.withAlpha(Theme.TEXT, Math.max(0.6f, alpha)));
        int y = 15;
        for (Line l : lines) {
            Ui.text(ctx, l.label(), 6, y, Theme.withAlpha(Theme.MUTED, Math.max(0.6f, alpha)));
            Ui.textRight(ctx, l.value(), size[0] - 5, y, Theme.withAlpha(l.color(), Math.max(0.6f, alpha)));
            y += 10;
        }
        ctx.getMatrices().popMatrix();
    }

    /** Unscaled panel size. */
    public static int[] size(SkyTradeCore core) {
        return size(lines(core, core.config().hud));
    }

    private static int[] size(List<Line> lines) {
        int w = 70;
        for (Line l : lines) w = Math.max(w, Ui.width(l.label()) + Ui.width(l.value()) + 18);
        return new int[]{Math.min(w, 220), 17 + lines.size() * 10};
    }

    private static List<Line> lines(SkyTradeCore core, SkyTradeConfig.Hud h) {
        List<Line> out = new ArrayList<>();
        PortfolioView pv = core.portfolio();
        if (h.showPortfolio) out.add(new Line("Portfolio", Fmt.coins(pv.totalValue()), Theme.TEXT));
        if (h.showDailyProfit) out.add(new Line("Today", Fmt.signedCoins(pv.dailyProfit()), Theme.signed(pv.dailyProfit())));
        if (h.showBestOpportunity) {
            Opportunity best = core.results().best();
            out.add(best == null ? new Line("Best", "-", Theme.MUTED)
                    : new Line(Ui.fit(best.name(), 90), Fmt.coins(best.adjustedProfitPerHour()) + "/h", Theme.GREEN));
        }
        if (h.showActiveOrders) out.add(new Line("Orders", String.valueOf(pv.activeTrades().size()), Theme.TEXT));
        if (h.showApiStatus) {
            ApiStatus.Health health = core.market().status().overall();
            DataFreshness f = core.market().bazaarFreshness();
            var bz = core.market().bazaar();
            String v = health.name().toLowerCase() + (bz != null ? " " + Fmt.duration(bz.ageMs(System.currentTimeMillis())) : "");
            int c = health == ApiStatus.Health.ONLINE && f != DataFreshness.STALE ? Theme.GREEN : health == ApiStatus.Health.OFFLINE || f == DataFreshness.STALE ? Theme.RED : Theme.YELLOW;
            out.add(new Line("API", v, c));
        }
        return out;
    }
}
