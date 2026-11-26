package dev.skytrade.market;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact fixed-size ring of price observations for one product, using primitive arrays to keep
 * memory bounded (~28 bytes/point) across thousands of products.
 */
public final class PriceHistory {
    private final long[] ts;
    private final float[] bid, ask, bidDepth, askDepth, sellWeek, buyWeek;
    private int head, size;

    public PriceHistory(int capacity) {
        ts = new long[capacity];
        bid = new float[capacity];
        ask = new float[capacity];
        bidDepth = new float[capacity];
        askDepth = new float[capacity];
        sellWeek = new float[capacity];
        buyWeek = new float[capacity];
    }

    public synchronized void add(PricePoint p) {
        if (size > 0 && p.timestamp() <= ts[(head - 1 + ts.length) % ts.length]) return; // monotonic only
        ts[head] = p.timestamp();
        bid[head] = (float) p.bid();
        ask[head] = (float) p.ask();
        bidDepth[head] = (float) p.bidDepth();
        askDepth[head] = (float) p.askDepth();
        sellWeek[head] = (float) p.sellMovingWeek();
        buyWeek[head] = (float) p.buyMovingWeek();
        head = (head + 1) % ts.length;
        if (size < ts.length) size++;
    }

    public synchronized long lastTimestamp() {
        return size == 0 ? 0 : ts[(head - 1 + ts.length) % ts.length];
    }

    /** Points with timestamp in [from, to], oldest first. Using {@code to} prevents look-ahead in replays. */
    public synchronized List<PricePoint> range(long from, long to) {
        List<PricePoint> out = new ArrayList<>();
        int start = (head - size + ts.length) % ts.length;
        for (int i = 0; i < size; i++) {
            int k = (start + i) % ts.length;
            if (ts[k] < from || ts[k] > to) continue;
            out.add(new PricePoint(ts[k], bid[k], ask[k], bidDepth[k], askDepth[k], sellWeek[k], buyWeek[k]));
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }
}
