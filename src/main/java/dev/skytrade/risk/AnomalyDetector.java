package dev.skytrade.risk;

import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.PricePoint;
import dev.skytrade.util.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags suspicious market conditions with robust statistics: price deviation from historical medians
 * (robust z on MAD), sudden collapses/spikes in visible depth, and spreads far wider than usual.
 */
public final class AnomalyDetector {
    public record Result(double severity, List<String> flags) {
        public boolean suspicious() {
            return severity >= 0.5;
        }
    }

    private AnomalyDetector() {}

    public static Result analyze(BazaarProduct p, List<PricePoint> history, double zThreshold) {
        List<String> flags = new ArrayList<>();
        double sev = 0;
        if (history.size() < 6) {
            flags.add("limited history (" + history.size() + " points)");
            return new Result(0.2, flags);
        }
        int n = history.size();
        double[] bids = new double[n], asks = new double[n], bidD = new double[n], askD = new double[n], spreads = new double[n];
        for (int i = 0; i < n; i++) {
            PricePoint h = history.get(i);
            bids[i] = h.bid();
            asks[i] = h.ask();
            bidD[i] = h.bidDepth();
            askD[i] = h.askDepth();
            spreads[i] = h.bid() > 0 ? (h.ask() - h.bid()) / h.bid() : 0;
        }
        double zb = Stats.robustZ(p.bestBid(), bids);
        double za = Stats.robustZ(p.bestAsk(), asks);
        if (Math.abs(zb) > zThreshold) {
            flags.add(String.format("buy-order price %.1f robust deviations from median", zb));
            sev += Math.min(1, Math.abs(zb) / (zThreshold * 2));
        }
        if (Math.abs(za) > zThreshold) {
            flags.add(String.format("sell-offer price %.1f robust deviations from median", za));
            sev += Math.min(1, Math.abs(za) / (zThreshold * 2));
        }
        double medBidD = Stats.median(bidD), medAskD = Stats.median(askD);
        double curBidD = p.bidDepth(), curAskD = p.askDepth();
        if (medBidD > 0 && curBidD < medBidD * 0.2) {
            flags.add("buy-side depth collapsed to " + Math.round(curBidD / medBidD * 100) + "% of median");
            sev += 0.35;
        }
        if (medAskD > 0 && curAskD < medAskD * 0.2) {
            flags.add("sell-side depth collapsed to " + Math.round(curAskD / medAskD * 100) + "% of median");
            sev += 0.35;
        }
        double curSpread = p.bestBid() > 0 ? (p.bestAsk() - p.bestBid()) / p.bestBid() : 0;
        double medSpread = Stats.median(spreads);
        if (medSpread > 0 && curSpread > medSpread * 4 && curSpread > 0.1) {
            flags.add(String.format("spread %.0f%% vs usual %.0f%%", curSpread * 100, medSpread * 100));
            sev += 0.4;
        }
        // A tiny top-of-book sell offer far above the next level suggests a bait listing.
        if (p.asks().size() >= 2 && p.asks().get(0).amount() <= 2 && p.asks().get(1).price() > p.asks().get(0).price() * 1.5) {
            flags.add("isolated tiny sell offer at top of book");
            sev += 0.2;
        }
        return new Result(Stats.clamp(sev, 0, 1), flags);
    }
}
