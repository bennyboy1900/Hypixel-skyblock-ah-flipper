package dev.skytrade.execution.sandbox;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.execution.Trade;
import dev.skytrade.execution.TradeEngine;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.portfolio.PortfolioService;
import dev.skytrade.risk.RiskDecision;
import dev.skytrade.risk.RiskManager;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.strategy.StrategySelector;
import dev.skytrade.util.Log;

import java.util.List;
import java.util.function.Supplier;

/**
 * Autonomous position selection for the private sandbox. Each decision cycle:
 * <ol>
 *   <li>verifies the environment with {@link SandboxGuard} (fail closed),</li>
 *   <li>evaluates the circuit breaker,</li>
 *   <li>frees capital from stale buy orders when much better opportunities exist,</li>
 *   <li>opens new positions from the strategy-weighted ranking until limits are reached.</li>
 * </ol>
 * Profits are reinvested automatically because sizing uses current portfolio value (if enabled).
 */
public final class AutoTrader {
    public record Status(boolean active, String reason, String strategyWeights, long lastDecisionAt) {}

    private final Supplier<SkyTradeConfig> config;
    private final TradeEngine engine;
    private final PortfolioService portfolio;
    private final RiskManager risk;
    private final EnvironmentProbe probe;
    private volatile Status status = new Status(false, "not started", "", 0);

    public AutoTrader(Supplier<SkyTradeConfig> config, TradeEngine engine, PortfolioService portfolio, RiskManager risk, EnvironmentProbe probe) {
        this.config = config;
        this.engine = engine;
        this.portfolio = portfolio;
        this.risk = risk;
        this.probe = probe;
    }

    public Status status() {
        return status;
    }

    /**
     * @param ranked candidate lists (already computed on the trader's — possibly delayed — view of the market)
     */
    public void decide(List<? extends Opportunity> bazaar, List<? extends Opportunity> auctions, List<? extends Opportunity> crafts,
                       ItemCatalog catalog, long dataAgeMs, long now) {
        SkyTradeConfig cfg = config.get();
        if (cfg.mode != SkyTradeConfig.TradingMode.SANDBOX_AUTO || !cfg.sandbox.autoTrade) {
            status = new Status(false, cfg.mode == SkyTradeConfig.TradingMode.SANDBOX_AUTO ? "auto trading disabled in settings" : "live analysis mode (no automation)", "", now);
            return;
        }
        SandboxGuard.Verdict v = SandboxGuard.verify(probe, cfg.sandbox.privateServerAllowlist);
        if (!v.allowed()) {
            status = new Status(false, "blocked: " + v.reason(), "", now);
            return;
        }
        var br = risk.breaker().evaluate(cfg.risk, dataAgeMs, cfg.api.staleAfterSeconds * 1000L, portfolio.realizedLast24h(now));
        if (!br.tradingAllowed()) {
            status = new Status(false, "paused: " + br.reason(), "", now);
            return;
        }
        StrategySelector.Weights w = StrategySelector.weights(cfg.strategy, portfolio.completedTrades(), now);
        @SuppressWarnings("unchecked")
        List<Opportunity> ranked = StrategySelector.rank(w, bazaar, auctions, crafts);

        redistribute(ranked, cfg, now);

        int opened = 0;
        for (Opportunity o : ranked) {
            if (engine.activeCount() >= cfg.risk.maxSimultaneousTrades) break;
            if (opened >= 3) break; // pace entries; the next cycle continues
            String key = o instanceof dev.skytrade.auction.AuctionOpportunity a ? a.uuid() : o.itemId();
            if (engine.hasActive(key)) continue;
            int freeSlots = cfg.risk.orderSlots - engine.usedSlots();
            SizingContext ctx = portfolio.sizingContext(freeSlots, cfg.sandbox.reinvestProfits);
            if (o.kind() == Opportunity.Kind.BAZAAR_FLIP && freeSlots < 2) continue;
            RiskDecision d = risk.validate(o, ctx, cfg.risk, catalog, dataAgeMs, cfg.api.staleAfterSeconds * 1000L);
            if (!d.approved()) continue;
            Trade t = engine.open(o, o.quantity(), o.kind().name(), false);
            engine.commit(t);
            opened++;
            Log.info("sandbox.open", "trade", t.id, "item", o.itemId(), "qty", o.quantity(), "kind", o.kind());
        }
        status = new Status(true, opened > 0 ? "opened " + opened + " position(s)" : "watching", w.explanation(), now);
    }

    /** Cancels unfilled buy orders whose item has dropped far below the current best opportunities. */
    private void redistribute(List<Opportunity> ranked, SkyTradeConfig cfg, long now) {
        if (ranked.isEmpty()) return;
        double best = ranked.get(0).score();
        for (Trade t : List.copyOf(engine.trades())) {
            if (t.state != dev.skytrade.execution.TradeState.BUY_ORDER_ACTIVE || t.filledBuyQty > 0 || t.manual) continue;
            if (now - t.stateSince < cfg.sandbox.repriceAfterMinutes * 60_000 * 2) continue;
            Opportunity current = null;
            for (Opportunity o : ranked) if (o.itemId().equals(t.itemId) && o.kind() == t.kind) { current = o; break; }
            double score = current == null ? 0 : current.score();
            if (score < best * 0.2) {
                engine.cancel(t.id);
                Log.info("sandbox.redistribute", "trade", t.id, "item", t.itemId);
            }
        }
    }
}
