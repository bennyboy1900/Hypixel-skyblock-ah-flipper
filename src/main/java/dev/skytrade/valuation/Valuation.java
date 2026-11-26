package dev.skytrade.valuation;

import java.util.List;

/**
 * A transparent fair value estimate.
 *
 * @param fairValue      estimated price the item sells for
 * @param salePrice      recommended listing price after considering BIN competition
 * @param confidence     0..1
 * @param comparables    number of comparable completed sales used
 * @param salesPerDay    observed sale frequency of comparable items
 * @param saleHours      estimated time to sell at {@code salePrice}
 * @param volatility     robust dispersion of comparable prices (MAD / median)
 * @param explanation    human-readable reasoning, one line per step
 */
public record Valuation(boolean valid, String rejectReason, double fairValue, double salePrice, double confidence,
                        int comparables, String matchLevel, double salesPerDay, double saleHours, double volatility,
                        double upgradeCredit, List<String> explanation, List<Long> comparablePrices) {

    public static Valuation reject(String reason, List<String> explanation) {
        return new Valuation(false, reason, 0, 0, 0, 0, "NONE", 0, Double.POSITIVE_INFINITY, 0, 0, explanation, List.of());
    }
}
