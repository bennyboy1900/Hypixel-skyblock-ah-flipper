package dev.skytrade.crafting;

import java.util.List;

/** How the cheapest acquisition of {@code quantity} units of an item was found. */
public record CostBreakdown(String itemId, double quantity, double unitCost, String method, List<CostBreakdown> children) {
    public double total() {
        return unitCost * quantity;
    }

    public boolean priced() {
        return Double.isFinite(unitCost) && unitCost > 0;
    }
}
