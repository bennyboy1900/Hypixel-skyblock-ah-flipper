package dev.skytrade.risk;

import dev.skytrade.config.SkyTradeConfig;

/**
 * Dynamic order sizing. The final quantity is the minimum over every constraint, and the binding
 * constraint is reported so the UI can explain why a size was chosen.
 */
public final class PositionSizer {
    public record Input(String itemId, String group, double unitPrice, double fillRatePerHour, double dailyVolume,
                        long maxByDepthImpact, long hardCap) {}

    public record Result(long quantity, String bindingConstraint) {}

    private PositionSizer() {}

    public static Result size(Input in, SizingContext ctx, SkyTradeConfig.Risk r) {
        if (!(in.unitPrice() > 0)) return new Result(0, "no price");
        // Order slots and the simultaneous-trade cap are execution limits enforced by RiskManager /
        // AutoTrader, so analysis still shows correctly sized opportunities while positions are open.

        double reserve = ctx.portfolioValue() * r.emergencyReservePct;
        double deployable = Math.max(0, ctx.availableCash() - reserve);
        double perTrade = ctx.portfolioValue() * r.maxCapitalPerTradePct;
        double itemRoom = ctx.portfolioValue() * r.maxExposurePerItemPct - ctx.exposureByItem().getOrDefault(in.itemId(), 0.0);
        double groupRoom = ctx.portfolioValue() * r.maxCorrelatedExposurePct - ctx.exposureByGroup().getOrDefault(in.group(), 0.0);

        long best = in.hardCap();
        String why = "order size limit";
        long q;
        q = (long) Math.floor(deployable / in.unitPrice());
        if (q < best) { best = q; why = "available cash after " + Math.round(r.emergencyReservePct * 100) + "% reserve"; }
        q = (long) Math.floor(perTrade / in.unitPrice());
        if (q < best) { best = q; why = "max capital per trade"; }
        q = (long) Math.floor(Math.max(0, itemRoom) / in.unitPrice());
        if (q < best) { best = q; why = "max exposure per item"; }
        q = (long) Math.floor(Math.max(0, groupRoom) / in.unitPrice());
        if (q < best) { best = q; why = "correlated exposure (" + in.group() + ")"; }
        // Fill within half the max holding period, leaving the other half for the exit.
        q = (long) Math.floor(in.fillRatePerHour() * r.maxHoldingHours / 2);
        if (q < best) { best = q; why = "expected fill rate within holding limit"; }
        // Never exceed 10% of daily market volume.
        q = (long) Math.floor(in.dailyVolume() * 0.10);
        if (q < best) { best = q; why = "10% of daily volume"; }
        q = in.maxByDepthImpact();
        if (q < best) { best = q; why = "max price impact on exit"; }
        return new Result(Math.max(0, best), why);
    }
}
