package dev.skytrade.bazaar;

import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.OrderLevel;

import java.util.List;

/** Order book analytics: VWAP walks, price impact, queue depth and competition. */
public final class OrderBook {
    private OrderBook() {}

    /** Volume-weighted average price obtained by consuming {@code qty} from the given side. NaN if depth is insufficient. */
    public static double vwap(List<OrderLevel> side, long qty) {
        if (qty <= 0) return Double.NaN;
        long remaining = qty;
        double cost = 0;
        for (OrderLevel l : side) {
            long take = Math.min(remaining, l.amount());
            cost += take * l.price();
            remaining -= take;
            if (remaining == 0) return cost / qty;
        }
        return Double.NaN;
    }

    /**
     * Relative price impact of dumping {@code qty} into the bids (emergency exit).
     * Returns 1.0 (100%) when visible depth cannot absorb the quantity.
     */
    public static double exitImpact(BazaarProduct p, long qty) {
        double best = p.bestBid();
        double v = vwap(p.bids(), qty);
        if (!(best > 0) || Double.isNaN(v)) return 1.0;
        return Math.max(0, (best - v) / best);
    }

    /** Units resting at prices at least as good as {@code price} on the bid side (queue ahead of a new buy order). */
    public static long bidQueueAhead(BazaarProduct p, double price) {
        long s = 0;
        for (OrderLevel l : p.bids()) if (l.price() >= price) s += l.amount();
        return s;
    }

    public static long askQueueAhead(BazaarProduct p, double price) {
        long s = 0;
        for (OrderLevel l : p.asks()) if (l.price() <= price) s += l.amount();
        return s;
    }

    /** Number of competing orders within {@code pct} of the best price on a side. */
    public static int competitorsNear(List<OrderLevel> side, double pct) {
        if (side.isEmpty()) return 0;
        double best = side.get(0).price();
        int n = 0;
        for (OrderLevel l : side) {
            if (Math.abs(l.price() - best) / best <= pct) n += l.orders();
            else break;
        }
        return n;
    }

    /**
     * Fraction of incoming flow we expect to capture when sitting at the top of a side with
     * {@code competitors} other orders nearby that will re-undercut us.
     */
    public static double captureShare(int competitors) {
        return Math.max(0.08, 1.0 / (1.0 + 0.35 * competitors));
    }
}
