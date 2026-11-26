package dev.skytrade.simulation;

import dev.skytrade.bazaar.OrderBook;
import dev.skytrade.market.BazaarProduct;

import java.util.SplittableRandom;

/**
 * Probabilistic fill model for resting bazaar orders.
 * <ul>
 *   <li>Incoming flow per hour comes from the weekly moving volume of the opposite side.</li>
 *   <li>At the top of the book we capture only a share of flow because of competing orders.</li>
 *   <li>Below the top, flow must first exhaust the visible depth ahead of us.</li>
 *   <li>If the market crosses our price, we fill against the crossing depth immediately.</li>
 *   <li>Flow is noisy (gamma-like multiplier) so identical orders do not fill identically.</li>
 * </ul>
 */
public final class FillModel {
    private FillModel() {}

    public static double fill(SimOrder o, BazaarProduct p, double hours, SplittableRandom rng) {
        long remaining = o.quantity - o.filled;
        if (remaining <= 0 || hours <= 0) return 0;
        double noise = 0.4 + rng.nextDouble() * 1.2; // mean 1.0
        if (o.type == SimOrder.Type.BAZAAR_BUY) {
            double bestAsk = p.bestAsk();
            if (bestAsk > 0 && bestAsk <= o.price) {
                // Market moved through our price: crossing sell offers fill us at our limit.
                return Math.min(remaining, OrderBook.askQueueAhead(p, o.price));
            }
            double flow = p.instantSellsPerHour() * hours * noise;
            double best = p.bestBid();
            if (o.price >= best) {
                return Math.min(remaining, flow * OrderBook.captureShare(OrderBook.competitorsNear(p.bids(), 0.005)));
            }
            long ahead = 0;
            for (var l : p.bids()) if (l.price() > o.price) ahead += l.amount();
            return Math.min(remaining, Math.max(0, flow - ahead) * 0.5);
        } else {
            double bestBid = p.bestBid();
            if (bestBid > 0 && bestBid >= o.price) {
                return Math.min(remaining, OrderBook.bidQueueAhead(p, o.price));
            }
            double flow = p.instantBuysPerHour() * hours * noise;
            double best = p.bestAsk();
            if (o.price <= best) {
                return Math.min(remaining, flow * OrderBook.captureShare(OrderBook.competitorsNear(p.asks(), 0.005)));
            }
            long ahead = 0;
            for (var l : p.asks()) if (l.price() < o.price) ahead += l.amount();
            return Math.min(remaining, Math.max(0, flow - ahead) * 0.5);
        }
    }

    /** Probability that an AH listing sells within {@code hours} given observed frequency and competition. */
    public static double auctionSaleProbability(double salesPerDay, int cheaperCompetitors, double hours) {
        double ratePerHour = salesPerDay / 24.0 / (1 + cheaperCompetitors);
        return 1 - Math.exp(-ratePerHour * hours);
    }
}
