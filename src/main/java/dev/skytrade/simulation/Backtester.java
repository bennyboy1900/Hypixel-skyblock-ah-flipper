package dev.skytrade.simulation;

import com.google.gson.Gson;
import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.config.StrategyPreset;
import dev.skytrade.execution.TradeEngine;
import dev.skytrade.execution.TradeStore;
import dev.skytrade.execution.sandbox.AutoTrader;
import dev.skytrade.execution.sandbox.EnvironmentProbe;
import dev.skytrade.execution.sandbox.MockMarketAdapter;
import dev.skytrade.market.*;
import dev.skytrade.portfolio.CompletedTrade;
import dev.skytrade.portfolio.PortfolioService;
import dev.skytrade.portfolio.PortfolioView;
import dev.skytrade.risk.RiskManager;
import dev.skytrade.util.TimeSource;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Historical replay of the bazaar strategy. The replay walks forward in time; at each step the
 * analyzer only sees history with timestamps at or before the simulated clock, so there is no
 * look-ahead bias. Each preset is run on an identical, independent simulator with the same seed.
 */
public final class Backtester {
    public interface HistorySource {
        /** Points with ts in [from, to], ascending by time. */
        List<Map.Entry<String, PricePoint>> load(long from, long to);
    }

    public record Result(String name, double startValue, double endValue, double realized, int trades, int wins,
                         double predictedProfit, double maxDrawdown, List<double[]> equityCurve, String note) {
        public double returnPct() {
            return startValue > 0 ? (endValue - startValue) / startValue : 0;
        }
    }

    private static final long STEP_MS = 5 * 60_000L;
    private static final long DECIDE_EVERY_MS = 15 * 60_000L;
    private static final Gson GSON = new Gson();

    private Backtester() {}

    public static List<Result> compare(SkyTradeConfig base, HistorySource source, ItemCatalog catalog, long from, long to,
                                       List<StrategyPreset> presets, BooleanSupplier cancelled) {
        List<Result> out = new ArrayList<>();
        for (StrategyPreset preset : presets) {
            if (cancelled.getAsBoolean()) break;
            SkyTradeConfig cfg = GSON.fromJson(GSON.toJson(base), SkyTradeConfig.class);
            preset.apply(cfg.risk);
            cfg.mode = SkyTradeConfig.TradingMode.SANDBOX_AUTO;
            cfg.sandbox.autoTrade = true;
            cfg.strategy.auctionEnabled = false; // auction replay requires item-level listing history
            cfg.strategy.craftEnabled = false;
            cfg.risk.pauseOnStaleData = false;
            out.add(run(preset.name(), cfg, source, catalog, from, to, cancelled));
        }
        return out;
    }

    public static Result run(String name, SkyTradeConfig cfg, HistorySource source, ItemCatalog catalog, long from, long to,
                             BooleanSupplier cancelled) {
        TimeSource.Manual clock = new TimeSource.Manual(from);
        MarketSimulator sim = new MarketSimulator(clock, cfg.sandbox.rngSeed, cfg.sandbox.failureRate, null);
        PortfolioService portfolio = new PortfolioService(cfg.sandbox.startingCapital);
        RiskManager risk = new RiskManager();
        TradeEngine engine = new TradeEngine(() -> cfg, new MockMarketAdapter(sim), new TradeStore.InMemory(), portfolio, risk.breaker(), clock);
        EnvironmentProbe offline = new EnvironmentProbe() {
            public Location location() { return Location.MAIN_MENU; }
            public String serverAddress() { return null; }
            public String serverBrand() { return null; }
        };
        AutoTrader trader = new AutoTrader(() -> cfg, engine, portfolio, risk, offline);
        BazaarAnalyzer analyzer = new BazaarAnalyzer(cfg);
        MarketHistory history = new MarketHistory(288, 0);
        Map<String, PricePoint> latest = new HashMap<>();

        double peak = cfg.sandbox.startingCapital, maxDd = 0;
        List<double[]> curve = new ArrayList<>();
        long lastDecision = 0;
        long chunk = 3_600_000L;
        for (long cStart = from; cStart < to && !cancelled.getAsBoolean(); cStart += chunk) {
            List<Map.Entry<String, PricePoint>> pts = source.load(cStart, Math.min(to, cStart + chunk - 1));
            int idx = 0;
            for (long t = cStart; t < Math.min(to, cStart + chunk); t += STEP_MS) {
                // Reveal only data observed up to the simulated time t.
                while (idx < pts.size() && pts.get(idx).getValue().timestamp() <= t) {
                    Map.Entry<String, PricePoint> e = pts.get(idx++);
                    latest.put(e.getKey(), e.getValue());
                    history.load(e.getKey(), e.getValue());
                }
                if (latest.isEmpty()) continue;
                clock.set(t);
                BazaarSnapshot snap = synthesize(latest, t);
                sim.onBazaar(snap);
                engine.tick(snap);
                if (t - lastDecision >= DECIDE_EVERY_MS) {
                    lastDecision = t;
                    portfolio.publish(engine.trades(), tr -> mark(snap, tr.itemId, cfg), catalog, t);
                    List<BazaarOpportunity> opps = analyzer.analyze(snap, history, catalog,
                            portfolio.sizingContext(cfg.risk.orderSlots - engine.usedSlots(), cfg.sandbox.reinvestProfits), t);
                    trader.decide(opps, List.of(), List.of(), catalog, 0, t);
                }
                PortfolioView v = portfolio.publish(engine.trades(), tr -> mark(snap, tr.itemId, cfg), catalog, t);
                peak = Math.max(peak, v.totalValue());
                if (peak > 0) maxDd = Math.max(maxDd, (peak - v.totalValue()) / peak);
                if (curve.isEmpty() || t - (long) curve.get(curve.size() - 1)[0] >= 3_600_000L) curve.add(new double[]{t, v.totalValue()});
            }
        }
        PortfolioView end = portfolio.view();
        int trades = 0, wins = 0;
        double predicted = 0;
        for (CompletedTrade c : portfolio.completedTrades()) {
            if (c.cancelled()) continue;
            trades++;
            if (c.realizedProfit() > 0) wins++;
            predicted += c.predictedProfit();
        }
        String note = latest.isEmpty() ? "no history in range" : trades + " closed, " + engine.activeCount() + " still open (valued at bid)";
        return new Result(name, cfg.sandbox.startingCapital, end.totalValue(), end.realizedTotal(), trades, wins, predicted, maxDd, curve, note);
    }

    static Double mark(BazaarSnapshot snap, String id, SkyTradeConfig cfg) {
        BazaarProduct p = snap.get(id);
        return p == null ? null : p.bestBid() * (1 - cfg.fees.bazaarTax);
    }

    /** Rebuilds a bazaar snapshot from stored top-of-book history (single level per side, conservative competition). */
    static BazaarSnapshot synthesize(Map<String, PricePoint> latest, long t) {
        Map<String, BazaarProduct> products = new HashMap<>();
        for (Map.Entry<String, PricePoint> e : latest.entrySet()) {
            PricePoint p = e.getValue();
            if (t - p.timestamp() > 30 * 60_000L || !(p.bid() > 0) || !(p.ask() > 0)) continue;
            List<OrderLevel> bids = List.of(new OrderLevel(p.bid(), Math.max(1, (long) p.bidDepth()), 5));
            List<OrderLevel> asks = List.of(new OrderLevel(p.ask(), Math.max(1, (long) p.askDepth()), 5));
            QuickStatus q = new QuickStatus(p.bid(), (long) p.bidDepth(), (long) p.sellMovingWeek(), 5, p.ask(), (long) p.askDepth(), (long) p.buyMovingWeek(), 5);
            products.put(e.getKey(), new BazaarProduct(e.getKey(), bids, asks, q));
        }
        return new BazaarSnapshot(t, t, products);
    }
}
