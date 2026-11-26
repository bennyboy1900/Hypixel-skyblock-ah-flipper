package dev.skytrade.external;

import java.util.List;

/**
 * A deal suggested by an external flip list and re-checked against the mod's own live data.
 *
 * @param source      e.g. "Bazaar spread", "Craft", "Kat upgrade"
 * @param cost        coins needed per unit (recomputed from live prices where possible)
 * @param sale        conservative sale proceeds per unit, after fees and tax
 * @param netProfit   sale - cost
 * @param volume      sales volume reported by the source (per day where the source says so)
 * @param hours       expected time until the coins are back (fill/craft/upgrade/sale time), or NaN if unknown
 * @param command     command to type yourself in chat to get there, or null
 * @param explanation how the numbers were checked, one line per step
 */
public record ExternalDeal(String key, String source, String itemId, String name, double cost, double sale, double netProfit,
                           double volume, double hours, String command, List<String> explanation) {
    public double roi() {
        return cost > 0 ? netProfit / cost : 0;
    }

    public double profitPerHour() {
        return hours > 0 && Double.isFinite(hours) ? netProfit / hours : Double.NaN;
    }
}
