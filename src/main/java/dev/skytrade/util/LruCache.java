package dev.skytrade.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Thread-safe bounded LRU cache. */
public final class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.capacity;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    /** Computes outside the lock so slow loaders do not block other readers. */
    public V computeIfAbsent(K key, Function<K, V> loader) {
        V v = get(key);
        if (v != null) return v;
        v = loader.apply(key);
        if (v != null) put(key, v);
        return v;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }
}
