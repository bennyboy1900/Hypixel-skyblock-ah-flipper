package dev.skytrade.crafting;

import dev.skytrade.strategy.Opportunity;

import java.util.List;

/** Craft (or upgrade) an item from purchased materials and sell the result. */
public record CraftOpportunity(String itemId, String name, String type, long quantity, double unitCost,
                               double unitSaleValue, double grossProfit, double netProfit, double holdHours,
                               double risk, double confidence, double liquidity, String saleVenue,
                               List<CostBreakdown> materials, List<String> explanation) implements Opportunity {
    @Override public Kind kind() { return Kind.CRAFT_FLIP; }
    @Override public String key() { return "CR:" + type + ":" + itemId; }
    @Override public double entryPrice() { return unitCost; }
    @Override public double exitPrice() { return unitSaleValue; }
    @Override public String navigationCommand() { return saleVenue.equals("BAZAAR") ? "/bz " + name : "/ah"; }
}
