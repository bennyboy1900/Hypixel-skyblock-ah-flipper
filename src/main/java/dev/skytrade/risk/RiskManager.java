package dev.skytrade.risk;

import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/** Pre-trade validation against every configured limit. Conservative by default. */
public final class RiskManager {
    private final CircuitBreaker breaker = new CircuitBreaker();

    public CircuitBreaker breaker() {
        return breaker;
    }

    public RiskDecision validate(Opportunity o, SizingContext ctx, SkyTradeConfig.Risk r, ItemCatalog catalog, long dataAgeMs, long staleAfterMs) {
        List<String> no = new ArrayList<>();
        if (r.emergencyStop) no.add("emergency stop");
        if (!r.whitelist.isEmpty() && !r.whitelist.contains(o.itemId())) no.add("not whitelisted");
        if (r.blacklist.contains(o.itemId())) no.add("blacklisted");
        if (o.quantity() <= 0) no.add("size 0");
        if (o.netProfit() < r.minNetProfit) no.add("net profit " + Fmt.coins(o.netProfit()) + " < " + Fmt.coins(r.minNetProfit));
        if (o.roi() < r.minRoi) no.add("ROI " + Fmt.pct(o.roi()) + " < " + Fmt.pct(r.minRoi));
        if (o.liquidity() < r.minLiquidityScore) no.add("liquidity " + Fmt.score(o.liquidity()) + " below minimum");
        if (o.holdHours() > r.maxHoldingHours) no.add("hold " + Fmt.duration(o.holdHours() * 3.6e6) + " > limit");
        if (o.confidence() < r.minConfidence) no.add("confidence " + Fmt.score(o.confidence()) + " below minimum");
        if (o instanceof BazaarOpportunity b) {
            if (b.volatility() > r.maxVolatility) no.add("volatility " + Fmt.pct(b.volatility()) + " > " + Fmt.pct(r.maxVolatility));
            if (b.priceImpact() > r.maxPriceImpactPct) no.add("price impact " + Fmt.pct(b.priceImpact()));
            if (!b.flags().isEmpty() && b.risk() > 0.6) no.add("suspicious market: " + b.flags().get(0));
        }
        if (ctx.openTrades() >= r.maxSimultaneousTrades) no.add("max simultaneous trades");
        if (o.kind() == Opportunity.Kind.BAZAAR_FLIP && ctx.freeOrderSlots() < 2) no.add("no free order slots (need buy + sell)");
        double reserve = ctx.portfolioValue() * r.emergencyReservePct;
        if (ctx.availableCash() - o.capital() < reserve) no.add("would breach emergency reserve");
        double itemExp = ctx.exposureByItem().getOrDefault(o.itemId(), 0.0) + o.capital();
        if (itemExp > ctx.portfolioValue() * r.maxExposurePerItemPct + 1) no.add("item exposure limit");
        String g = catalog.group(o.itemId());
        double groupExp = ctx.exposureByGroup().getOrDefault(g, 0.0) + o.capital();
        if (groupExp > ctx.portfolioValue() * r.maxCorrelatedExposurePct + 1) no.add("correlated exposure limit (" + g + ")");
        if (o.capital() > ctx.portfolioValue() * r.maxCapitalPerTradePct + 1) no.add("capital per trade limit");
        if (r.pauseOnStaleData && dataAgeMs > staleAfterMs) no.add("stale market data");
        return no.isEmpty() ? RiskDecision.ok() : new RiskDecision(false, no);
    }
}
