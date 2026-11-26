package dev.skytrade.portfolio;

import dev.skytrade.execution.Trade;

import java.util.List;
import java.util.Map;

/** Immutable portfolio snapshot published to the UI. */
public record PortfolioView(long timestamp, double startingCapital, double cash, double escrow, double inventoryValue,
                            double totalValue, double realizedTotal, double unrealized, double dailyProfit,
                            double weeklyProfit, double estimatedProfitPerHour, List<Trade> activeTrades,
                            List<CompletedTrade> recent, Map<String, Double> exposureByItem,
                            Map<String, Double> exposureByGroup) {
    public double invested() {
        return escrow + inventoryValue;
    }

    public static final PortfolioView EMPTY = new PortfolioView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), Map.of(), Map.of());
}
