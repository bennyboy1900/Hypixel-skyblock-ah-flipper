package dev.skytrade.bazaar;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.market.*;
import dev.skytrade.risk.AnomalyDetector;
import dev.skytrade.risk.PositionSizer;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;

import java.util.*;

/**
 * Bazaar flip analysis using the full order book, weekly moving volume, history and volatility.
 * Pure function of its inputs: safe to run on any background thread and in backtests.
 */
public final class BazaarAnalyzer {
    private final SkyTradeConfig config;

    public BazaarAnalyzer(SkyTradeConfig config) {
        this.config = config;
    }

    public List<BazaarOpportunity> analyze(BazaarSnapshot snap, MarketHistory history, ItemCatalog catalog,
                                           SizingContext ctx, long now) {
        List<BazaarOpportunity> out = new ArrayList<>();
        long dataAge = snap.ageMs(now);
        for (BazaarProduct p : snap.products().values()) {
            List<PricePoint> hist = history.range(p.id(), now - 24 * 3_600_000L, now);
            BazaarOpportunity o = analyzeProduct(p, hist, catalog, ctx, dataAge);
            if (o != null) out.add(o);
        }
        out.sort(Comparator.comparingDouble(BazaarOpportunity::score).reversed());
        int top = config.strategy.bazaarTopN;
        return out.size() > top ? new ArrayList<>(out.subList(0, top)) : out;
    }

    public BazaarOpportunity analyzeProduct(BazaarProduct p, List<PricePoint> hist, ItemCatalog catalog,
                                            SizingContext ctx, long dataAgeMs) {
        SkyTradeConfig.Fees fees = config.fees;
        SkyTradeConfig.Risk risk = config.risk;
        if (!p.hasBook()) return null;
        double bestBid = p.bestBid(), bestAsk = p.bestAsk();
        if (!Stats.plausibleCoins(bestBid) || !Stats.plausibleCoins(bestAsk) || !(bestAsk > bestBid)) return null;

        double tick = BazaarFees.tick();
        double entry = round1(bestBid + tick);
        double exit = round1(bestAsk - tick);
        if (exit <= entry) return null;
        double netPerUnit = exit * (1 - fees.bazaarTax) - entry;
        if (netPerUnit <= 0) return null;

        double sellsPerHour = p.instantSellsPerHour(); // fills our buy order
        double buysPerHour = p.instantBuysPerHour();   // fills our sell offer
        if (sellsPerHour <= 0 || buysPerHour <= 0) return null;

        int bidCompetitors = OrderBookCompetition.near(p.bids());
        int askCompetitors = OrderBookCompetition.near(p.asks());
        double buyShare = OrderBook.captureShare(bidCompetitors);
        double sellShare = OrderBook.captureShare(askCompetitors);
        double buyRate = sellsPerHour * buyShare;
        double sellRate = buysPerHour * sellShare;
        // If even a single unit cannot be bought and sold within 3x the holding limit, there is no real flip
        // here, however large the spread looks (typical for dead or baited books).
        if (!(buyRate > 0) || !(sellRate > 0) || 1 / buyRate + 1 / sellRate > 3 * Math.max(0.5, risk.maxHoldingHours)) return null;
        double dailyVolume = Math.min(p.quick().sellMovingWeek(), p.quick().buyMovingWeek()) / 7.0;

        // Largest quantity whose emergency exit into the bids stays within the impact limit.
        long maxByImpact = maxQtyWithinImpact(p, risk.maxPriceImpactPct);

        String group = catalog.group(p.id());
        PositionSizer.Result sized = PositionSizer.size(new PositionSizer.Input(p.id(), group, entry,
                Math.min(buyRate, sellRate), dailyVolume, maxByImpact, BazaarFees.MAX_ORDER_UNITS), ctx, risk);
        long qty = sized.quantity();
        if (qty <= 0) qty = 0;

        long effQty = Math.max(1, qty);
        double buyHours = effQty / buyRate;
        double sellHours = effQty / sellRate;
        double gross = (exit - entry) * effQty;
        double net = netPerUnit * effQty;

        // History-derived metrics.
        double[] mids = hist.stream().mapToDouble(PricePoint::mid).toArray();
        double vol = mids.length >= 6 ? Stats.logReturnVolatility(mids) * Math.sqrt(Math.max(1, mids.length)) : 0.10;
        AnomalyDetector.Result anomaly = AnomalyDetector.analyze(p, hist, risk.manipulationZ);
        double impact = OrderBook.exitImpact(p, effQty);
        double spreadPct = (bestAsk - bestBid) / bestBid;

        List<String> flags = new ArrayList<>(anomaly.flags());
        if (spreadPct > 1.0) flags.add("spread above 100% (thin or manipulated market)");
        if (p.quick().buyMovingWeek() > 0 && p.quick().sellMovingWeek() / (double) p.quick().buyMovingWeek() > 8)
            flags.add("weekly flow heavily one-sided");

        double holdHours = buyHours + sellHours;
        double coinFlowPerHour = Math.min(sellsPerHour * bestBid, buysPerHour * bestAsk);
        double liquidity = Stats.saturate(coinFlowPerHour, 3_000_000) * (1 - Stats.clamp(impact * 3, 0, 0.6));

        double riskScore = 0;
        riskScore += Stats.clamp(vol / Math.max(1e-6, risk.maxVolatility), 0, 1.5) * 0.30;
        riskScore += Stats.clamp(holdHours / Math.max(0.5, risk.maxHoldingHours), 0, 1.5) * 0.20;
        riskScore += (1 - liquidity) * 0.20;
        riskScore += anomaly.severity() * 0.25;
        riskScore += Stats.clamp(spreadPct / 2, 0, 1) * 0.05;
        // Spreads above 100% almost always mean an illiquid or baited book: exits rarely fill at the ask.
        if (spreadPct > 1.0) riskScore += 0.35;
        riskScore = Stats.clamp(riskScore, 0, 1);

        double confidence = 1.0;
        confidence *= hist.size() >= 24 ? 1.0 : hist.size() >= 6 ? 0.85 : 0.65;
        confidence *= 1 - anomaly.severity() * 0.6;
        confidence *= dataAgeMs < 120_000 ? 1.0 : dataAgeMs < 600_000 ? 0.85 : 0.5;
        confidence *= p.bids().size() >= 5 && p.asks().size() >= 5 ? 1.0 : 0.8;
        // Quick status should agree with the visible top of book; disagreement means a moving market.
        double qsDiff = Math.abs(p.quick().buyPrice() - bestAsk) / bestAsk;
        if (qsDiff > 0.05) confidence *= 0.85;
        if (spreadPct > 1.0) confidence *= 0.5;
        confidence = Stats.clamp(confidence, 0, 1);

        String name = catalog.displayName(p.id());
        List<String> why = new ArrayList<>();
        why.add("Entry: buy order at " + Fmt.coins(entry) + " (best buy order " + Fmt.coins(bestBid) + " + 0.1).");
        why.add("Exit: sell offer at " + Fmt.coins(exit) + " (best sell offer " + Fmt.coins(bestAsk) + " - 0.1).");
        why.add("Net per unit after " + Fmt.pct(fees.bazaarTax) + " tax: " + Fmt.coins(netPerUnit) + ".");
        why.add(String.format(Locale.ROOT, "Flow: %.0f instant-sells/h, %.0f instant-buys/h (weekly moving volume).", sellsPerHour, buysPerHour));
        why.add(String.format(Locale.ROOT, "Competition: %d bid / %d ask orders near top -> capture %.0f%% / %.0f%% of flow.",
                bidCompetitors, askCompetitors, buyShare * 100, sellShare * 100));
        why.add("Quantity " + qty + " limited by " + sized.bindingConstraint() + ".");
        why.add("Expected hold " + Fmt.duration(holdHours * 3_600_000) + " (buy " + Fmt.duration(buyHours * 3_600_000)
                + ", sell " + Fmt.duration(sellHours * 3_600_000) + ").");
        why.add("Emergency exit impact for this size: " + Fmt.pct(impact) + ".");
        why.add("Volatility (24h): " + Fmt.pct(vol) + (mids.length < 6 ? " (assumed, limited history)" : "") + ".");
        for (String f : flags) why.add("Warning: " + f);

        return new BazaarOpportunity(p.id(), name, entry, exit, qty, qty > 0 ? gross : 0, qty > 0 ? net : 0, buyHours,
                sellHours, riskScore, confidence, liquidity, vol, spreadPct, impact, sellsPerHour, buysPerHour,
                Math.min(p.quick().sellMovingWeek(), p.quick().buyMovingWeek()), 24 / Math.max(0.01, holdHours),
                sized.bindingConstraint(), flags, why);
    }

    static long maxQtyWithinImpact(BazaarProduct p, double maxImpact) {
        double best = p.bestBid();
        if (!(best > 0)) return 0;
        double floor = best * (1 - maxImpact);
        // Walk the bids; VWAP impact is always <= marginal impact, so a marginal-price walk is conservative.
        long q = 0;
        for (OrderLevel l : p.bids()) {
            if (l.price() < floor) break;
            q += l.amount();
        }
        return q;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Counts competing orders near the top of a side. */
    static final class OrderBookCompetition {
        static int near(List<OrderLevel> side) {
            return OrderBook.competitorsNear(side, 0.005);
        }
    }
}
