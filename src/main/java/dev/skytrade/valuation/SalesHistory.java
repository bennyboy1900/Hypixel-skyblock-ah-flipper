package dev.skytrade.valuation;

import dev.skytrade.util.RingBuffer;

import java.util.*;

/**
 * Bounded in-memory index of completed sales keyed by fingerprint at each strictness level.
 * Deduplicates on auction UUID.
 */
public final class SalesHistory {
    private final int perKey;
    private final int maxKeys;
    private final Map<String, RingBuffer<SaleRecord>> byStrict;
    private final Map<String, RingBuffer<SaleRecord>> byRelaxed;
    private final Map<String, RingBuffer<SaleRecord>> byBase;
    private final Set<String> seen;

    public SalesHistory(int perKey, int maxKeys) {
        this.perKey = perKey;
        this.maxKeys = maxKeys;
        this.byStrict = lru(maxKeys);
        this.byRelaxed = lru(maxKeys);
        this.byBase = lru(maxKeys);
        this.seen = Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > 200_000;
            }
        });
    }

    private static Map<String, RingBuffer<SaleRecord>> lru(int max) {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RingBuffer<SaleRecord>> e) {
                return size() > max;
            }
        };
    }

    /** @return true if the sale was new. */
    public synchronized boolean add(SaleRecord s) {
        if (!seen.add(s.uuid())) return false;
        byStrict.computeIfAbsent(s.strict(), k -> new RingBuffer<>(perKey)).add(s);
        byRelaxed.computeIfAbsent(s.relaxed(), k -> new RingBuffer<>(perKey)).add(s);
        byBase.computeIfAbsent(s.base(), k -> new RingBuffer<>(perKey * 2)).add(s);
        return true;
    }

    public synchronized List<SaleRecord> strict(String fp) {
        RingBuffer<SaleRecord> r = byStrict.get(fp);
        return r == null ? List.of() : r.toList();
    }

    public synchronized List<SaleRecord> relaxed(String fp) {
        RingBuffer<SaleRecord> r = byRelaxed.get(fp);
        return r == null ? List.of() : r.toList();
    }

    public synchronized List<SaleRecord> base(String fp) {
        RingBuffer<SaleRecord> r = byBase.get(fp);
        return r == null ? List.of() : r.toList();
    }

    public synchronized int keyCount() {
        return byBase.size();
    }

    public synchronized long totalSales() {
        return seen.size();
    }
}
