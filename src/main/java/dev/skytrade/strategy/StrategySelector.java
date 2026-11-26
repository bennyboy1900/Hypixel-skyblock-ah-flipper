package dev.skytrade.strategy;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.portfolio.CompletedTrade;

import java.util.*;

/**
 * Chooses which strategies receive capital. In auto mode each strategy's weight is its smoothed
 * realized return per capital-hour over the last week, with a neutral prior so new strategies still get
 * explored. Weights are floored so no enabled strategy is starved completely.
 */
public final class StrategySelector {
    public record Weights(Map<Opportunity.Kind, Double> weights, String explanation) {}

    private StrategySelector() {}

    public static Weights weights(SkyTradeConfig.Strategy s, List<CompletedTrade> history, long now) {
        Map<Opportunity.Kind, Double> w = new EnumMap<>(Opportunity.Kind.class);
        Set<Opportunity.Kind> enabled = EnumSet.noneOf(Opportunity.Kind.class);
        if (s.bazaarEnabled) enabled.add(Opportunity.Kind.BAZAAR_FLIP);
        if (s.auctionEnabled) enabled.add(Opportunity.Kind.AUCTION_FLIP);
        if (s.craftEnabled) enabled.add(Opportunity.Kind.CRAFT_FLIP);
        if (enabled.isEmpty()) return new Weights(w, "no strategies enabled");
        if (!s.autoSelect) {
            for (Opportunity.Kind k : enabled) w.put(k, 1.0 / enabled.size());
            return new Weights(w, "equal weights (auto-select off)");
        }
        Map<Opportunity.Kind, double[]> agg = new EnumMap<>(Opportunity.Kind.class);
        for (CompletedTrade c : history) {
            if (now - c.closedAt() > 7 * 86_400_000L) continue;
            Opportunity.Kind k;
            try {
                k = Opportunity.Kind.valueOf(c.strategy());
            } catch (IllegalArgumentException e) {
                continue;
            }
            double[] a = agg.computeIfAbsent(k, x -> new double[2]);
            a[0] += c.realizedProfit();
            a[1] += c.capital() * Math.max(0.1, c.holdHours());
        }
        double priorReturn = 0.01; // 1% per capital-hour-equivalent, neutral
        double priorWeight = 50_000_000; // capital-hours of prior evidence
        double sum = 0;
        StringBuilder why = new StringBuilder("auto:");
        for (Opportunity.Kind k : enabled) {
            double[] a = agg.getOrDefault(k, new double[2]);
            double ret = (a[0] + priorReturn * priorWeight) / (a[1] + priorWeight);
            double weight = Math.max(0.1, ret / priorReturn);
            w.put(k, weight);
            sum += weight;
        }
        for (Opportunity.Kind k : enabled) {
            double v = w.get(k) / sum;
            w.put(k, v);
            why.append(' ').append(k.name(), 0, 2).append('=').append(Math.round(v * 100)).append('%');
        }
        return new Weights(w, why.toString());
    }

    /** Merges all opportunity lists into one ranking, scaling scores by strategy weight. */
    public static List<Opportunity> rank(Weights weights, List<? extends Opportunity>... lists) {
        List<Opportunity> all = new ArrayList<>();
        for (List<? extends Opportunity> l : lists) all.addAll(l);
        all.removeIf(o -> !weights.weights().containsKey(o.kind()) || o.quantity() <= 0 || o.netProfit() <= 0);
        all.sort(Comparator.comparingDouble((Opportunity o) -> o.score() * weights.weights().get(o.kind())).reversed());
        return all;
    }
}
