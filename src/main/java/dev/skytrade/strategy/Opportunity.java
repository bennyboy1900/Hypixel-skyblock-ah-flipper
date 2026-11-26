package dev.skytrade.strategy;

import java.util.List;

/** Common view over every opportunity type so ranking, risk and execution can treat them uniformly. */
public interface Opportunity {
    enum Kind { BAZAAR_FLIP, AUCTION_FLIP, CRAFT_FLIP }

    Kind kind();

    /** Stable identity (e.g. product id, auction uuid, recipe output). */
    String key();

    String itemId();

    String name();

    double entryPrice();

    double exitPrice();

    long quantity();

    default double capital() {
        return entryPrice() * quantity();
    }

    double grossProfit();

    double netProfit();

    default double roi() {
        double c = capital();
        return c > 0 ? netProfit() / c : 0;
    }

    /** Expected total holding duration (entry + exit), in hours. */
    double holdHours();

    default double profitPerHour() {
        return holdHours() > 0 ? netProfit() / holdHours() : 0;
    }

    /** 0 (safe) .. 1 (very risky). */
    double risk();

    /** 0 .. 1. */
    double confidence();

    /** 0 .. 1. */
    double liquidity();

    /**
     * Ranking score: risk- and confidence-adjusted profit per hour per million coins invested
     * (capital floored at 1M, profit below 100K scaled down). Deliberately not based on percentage margin.
     */
    default double score() {
        double c = capital();
        if (c <= 0 || netProfit() <= 0) return 0;
        // Floor the capital at 1M so tiny positions cannot dominate on return-on-capital alone, and scale
        // down trades whose absolute profit is too small to matter.
        double profitScale = Math.min(1.0, netProfit() / 100_000.0);
        return profitPerHour() * (1 - risk()) * confidence() / Math.max(1.0, c / 1_000_000.0) * profitScale;
    }

    /** Risk-adjusted profit per hour in coins. */
    default double adjustedProfitPerHour() {
        return profitPerHour() * (1 - risk()) * confidence();
    }

    List<String> explanation();

    /** False when an independent check (e.g. 7-day price history) has not confirmed the numbers yet. */
    default boolean confirmed() {
        return true;
    }

    /** Manual navigation reference (e.g. "/bz Enchanted Diamond"), never executed automatically. */
    String navigationCommand();
}
