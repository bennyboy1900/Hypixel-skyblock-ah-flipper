package dev.skytrade.market;

import java.util.Map;

/** Immutable bazaar dataset. {@code lastUpdated} is Hypixel's server timestamp; never assume real time. */
public record BazaarSnapshot(long lastUpdated, long fetchedAt, Map<String, BazaarProduct> products) {
    public BazaarProduct get(String id) {
        return products.get(id);
    }

    public long ageMs(long now) {
        return Math.max(0, now - lastUpdated);
    }
}
