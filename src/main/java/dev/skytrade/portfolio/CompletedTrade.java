package dev.skytrade.portfolio;

/** Summary of a finished trade used for analytics. */
public record CompletedTrade(String tradeId, String itemId, String name, String strategy, long openedAt, long closedAt,
                             double capital, double realizedProfit, double predictedProfit, double holdHours,
                             double predictedHoldHours, boolean cancelled) {}
