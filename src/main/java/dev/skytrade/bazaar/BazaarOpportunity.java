package dev.skytrade.bazaar;

import dev.skytrade.strategy.Opportunity;

import java.util.List;

/** A bazaar buy-order/sell-offer flip with all derived metrics. */
public record BazaarOpportunity(
        String itemId, String name, double entryPrice, double exitPrice, long quantity,
        double grossProfit, double netProfit, double buyFillHours, double sellFillHours,
        double risk, double confidence, double liquidity, double volatility, double spreadPct,
        double priceImpact, double instantSellsPerHour, double instantBuysPerHour, double weeklyVolume,
        double turnoverPerDay, String sizingConstraint, List<String> flags, List<String> explanation
) implements Opportunity {
    @Override public Kind kind() { return Kind.BAZAAR_FLIP; }
    @Override public String key() { return "BZ:" + itemId; }
    @Override public double holdHours() { return buyFillHours + sellFillHours; }
    @Override public String navigationCommand() { return "/bz " + name; }
}
