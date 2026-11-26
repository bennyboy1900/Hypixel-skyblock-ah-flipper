package dev.skytrade.market;

import java.util.List;

/**
 * Immutable bazaar product.
 *
 * @param bids buy orders, best (highest) first — Hypixel's {@code sell_summary}
 * @param asks sell offers, best (lowest) first — Hypixel's {@code buy_summary}
 */
public record BazaarProduct(String id, List<OrderLevel> bids, List<OrderLevel> asks, QuickStatus quick) {

    public double bestBid() {
        return bids.isEmpty() ? quick.sellPrice() : bids.get(0).price();
    }

    public double bestAsk() {
        return asks.isEmpty() ? quick.buyPrice() : asks.get(0).price();
    }

    public long bidDepth() {
        long s = 0;
        for (OrderLevel l : bids) s += l.amount();
        return s;
    }

    public long askDepth() {
        long s = 0;
        for (OrderLevel l : asks) s += l.amount();
        return s;
    }

    /** Units instantly sold per hour (fills resting buy orders), from the weekly moving volume. */
    public double instantSellsPerHour() {
        return quick.sellMovingWeek() / (7 * 24.0);
    }

    /** Units instantly bought per hour (fills resting sell offers). */
    public double instantBuysPerHour() {
        return quick.buyMovingWeek() / (7 * 24.0);
    }

    public boolean hasBook() {
        return !bids.isEmpty() && !asks.isEmpty();
    }
}
