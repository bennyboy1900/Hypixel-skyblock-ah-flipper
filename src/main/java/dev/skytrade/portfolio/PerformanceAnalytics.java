package dev.skytrade.portfolio;

import java.util.*;

/** Aggregate statistics over completed trades. */
public final class PerformanceAnalytics {
    public record Summary(int trades, int wins, double winRate, double totalProfit, double avgHoldHours,
                          double predictedProfit, double capitalTurnover, Map<String, Double> profitByItem,
                          Map<String, Double> profitByStrategy, Map<String, int[]> winsByStrategy,
                          List<CompletedTrade> largestLosses, double[] dailyProfit, long dailyStart) {}

    private PerformanceAnalytics() {}

    public static Summary summarize(List<CompletedTrade> all, double avgPortfolioValue, long now, int days) {
        int n = 0, wins = 0;
        double total = 0, hold = 0, predicted = 0, capital = 0;
        Map<String, Double> byItem = new HashMap<>();
        Map<String, Double> byStrategy = new TreeMap<>();
        Map<String, int[]> winsBy = new TreeMap<>();
        long dayMs = 86_400_000L;
        long start = (now / dayMs - (days - 1)) * dayMs;
        double[] daily = new double[days];
        List<CompletedTrade> losses = new ArrayList<>();
        for (CompletedTrade c : all) {
            if (c.cancelled() && c.realizedProfit() == 0) continue;
            n++;
            total += c.realizedProfit();
            hold += c.holdHours();
            predicted += c.predictedProfit();
            capital += c.capital();
            if (c.realizedProfit() > 0) wins++;
            byItem.merge(c.name(), c.realizedProfit(), Double::sum);
            byStrategy.merge(c.strategy(), c.realizedProfit(), Double::sum);
            int[] w = winsBy.computeIfAbsent(c.strategy(), k -> new int[2]);
            w[1]++;
            if (c.realizedProfit() > 0) w[0]++;
            if (c.realizedProfit() < 0) losses.add(c);
            int d = (int) ((c.closedAt() - start) / dayMs);
            if (d >= 0 && d < days) daily[d] += c.realizedProfit();
        }
        losses.sort(Comparator.comparingDouble(CompletedTrade::realizedProfit));
        if (losses.size() > 10) losses = losses.subList(0, 10);
        long spanMs = all.isEmpty() ? dayMs : Math.max(dayMs, now - all.get(0).openedAt());
        double turnover = avgPortfolioValue > 0 ? capital / avgPortfolioValue / (spanMs / (double) dayMs) : 0;
        return new Summary(n, wins, n == 0 ? 0 : (double) wins / n, total, n == 0 ? 0 : hold / n, predicted, turnover,
                byItem, byStrategy, winsBy, List.copyOf(losses), daily, start);
    }
}
