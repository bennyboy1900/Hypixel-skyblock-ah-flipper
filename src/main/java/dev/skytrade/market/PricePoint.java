package dev.skytrade.market;

/** One historical observation of a bazaar product. */
public record PricePoint(long timestamp, double bid, double ask, double bidDepth, double askDepth,
                         double sellMovingWeek, double buyMovingWeek) {
    public double mid() {
        return (bid + ask) / 2;
    }
}
