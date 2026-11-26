package dev.skytrade.util;

import java.util.ArrayList;
import java.util.List;

/** Fixed-capacity, thread-safe ring buffer that keeps the newest elements. */
public final class RingBuffer<T> {
    private final Object[] data;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        this.data = new Object[Math.max(1, capacity)];
    }

    public synchronized void add(T value) {
        data[head] = value;
        head = (head + 1) % data.length;
        if (size < data.length) size++;
    }

    @SuppressWarnings("unchecked")
    public synchronized T last() {
        if (size == 0) return null;
        return (T) data[(head - 1 + data.length) % data.length];
    }

    /** Oldest-first snapshot copy. */
    @SuppressWarnings("unchecked")
    public synchronized List<T> toList() {
        List<T> out = new ArrayList<>(size);
        int start = (head - size + data.length) % data.length;
        for (int i = 0; i < size; i++) out.add((T) data[(start + i) % data.length]);
        return out;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(data, null);
        head = 0;
        size = 0;
    }
}
