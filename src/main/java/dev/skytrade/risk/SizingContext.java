package dev.skytrade.risk;

import java.util.Map;

/** Snapshot of portfolio state needed to size a new position. */
public record SizingContext(double portfolioValue, double availableCash, Map<String, Double> exposureByItem,
                            Map<String, Double> exposureByGroup, int freeOrderSlots, int openTrades) {
    public static SizingContext of(double cash) {
        return new SizingContext(cash, cash, Map.of(), Map.of(), 14, 0);
    }
}
