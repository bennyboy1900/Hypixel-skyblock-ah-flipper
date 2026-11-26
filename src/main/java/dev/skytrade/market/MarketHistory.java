package dev.skytrade.market;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory recent history for all bazaar products (bounded per product). */
public final class MarketHistory {
    private final int capacity;
    private final long minIntervalMs;
    private final Map<String, PriceHistory> products = new ConcurrentHashMap<>();

    public MarketHistory(int capacity, long minIntervalMs) {
        this.capacity = capacity;
        this.minIntervalMs = minIntervalMs;
    }

    /** Records a point if at least {@code minIntervalMs} has passed since the previous one. */
    public boolean record(String id, PricePoint p) {
        PriceHistory h = products.computeIfAbsent(id, k -> new PriceHistory(capacity));
        if (p.timestamp() - h.lastTimestamp() < minIntervalMs) return false;
        h.add(p);
        return true;
    }

    /** Unconditional insert used when loading persisted history. */
    public void load(String id, PricePoint p) {
        products.computeIfAbsent(id, k -> new PriceHistory(capacity)).add(p);
    }

    public List<PricePoint> range(String id, long from, long to) {
        PriceHistory h = products.get(id);
        return h == null ? List.of() : h.range(from, to);
    }

    public int productCount() {
        return products.size();
    }
}
