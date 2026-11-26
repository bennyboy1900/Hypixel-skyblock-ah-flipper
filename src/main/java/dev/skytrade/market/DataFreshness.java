package dev.skytrade.market;

/** Freshness classification for a dataset based on its server-side timestamp. */
public enum DataFreshness {
    FRESH, AGING, STALE, MISSING;

    public static DataFreshness of(long dataTimestamp, long now, long staleAfterMs) {
        if (dataTimestamp <= 0) return MISSING;
        long age = now - dataTimestamp;
        if (age > staleAfterMs) return STALE;
        if (age > staleAfterMs / 2) return AGING;
        return FRESH;
    }
}
