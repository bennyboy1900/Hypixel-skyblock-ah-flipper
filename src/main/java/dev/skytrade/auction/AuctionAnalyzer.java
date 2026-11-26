package dev.skytrade.auction;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;
import dev.skytrade.valuation.FairValueEstimator;
import dev.skytrade.valuation.UpgradeComponent;
import dev.skytrade.valuation.UpgradeValuer;
import dev.skytrade.valuation.Valuation;

import java.util.*;

/**
 * Finds BIN listings priced below their estimated fair value. Only the cheapest listing of each
 * identical-item group is a candidate; the next cheapest identical listing caps the resale price.
 */
public final class AuctionAnalyzer {
    private final SkyTradeConfig config;

    public AuctionAnalyzer(SkyTradeConfig config) {
        this.config = config;
    }

    public List<AuctionOpportunity> analyze(AuctionIndex index, FairValueEstimator estimator, double maxCapital, long now) {
        SkyTradeConfig.Strategy s = config.strategy;
        FairValueEstimator.Params params = new FairValueEstimator.Params((int) Math.max(3, s.ahMinComparables),
                s.upgradeRecoveryRate, s.ahUndercut, 7L * 86_400_000L, 2L * 86_400_000L);
        List<AuctionOpportunity> out = new ArrayList<>();
        for (List<DecodedAuction> group : index.groups().values()) {
            if (group.isEmpty()) continue;
            DecodedAuction cand = group.get(0);
            long price = cand.auction().startingBid();
            if (price <= 0 || price > maxCapital) continue;
            List<Long> competing = new ArrayList<>(group.size());
            for (int i = 1; i < group.size(); i++) {
                DecodedAuction o = group.get(i);
                if (!o.auction().auctioneer().equals(cand.auction().auctioneer())) competing.add(o.auction().startingBid());
            }
            AuctionOpportunity o = evaluate(cand, competing, estimator, params, now);
            if (o != null) out.add(o);
        }
        out.sort(Comparator.comparingDouble(AuctionOpportunity::score).reversed());
        int top = s.auctionTopN;
        return out.size() > top ? new ArrayList<>(out.subList(0, top)) : out;
    }

    AuctionOpportunity evaluate(DecodedAuction d, List<Long> competing, FairValueEstimator estimator,
                                FairValueEstimator.Params params, long now) {
        Valuation v = estimator.estimate(d.attrs(), competing, params, now);
        if (!v.valid()) return null;
        long price = d.auction().startingBid();
        double sale = v.salePrice();
        double fees = AuctionFees.listingFee(sale, config.fees) + AuctionFees.claimTax(sale, config.fees);
        double gross = sale - price;
        double net = sale - fees - price;
        if (net <= 0) return null;
        if (v.saleHours() > config.strategy.ahMaxSaleHours) return null;

        double discount = v.fairValue() > 0 ? 1 - price / v.fairValue() : 0;
        List<String> why = new ArrayList<>(v.explanation());
        why.add("Listing " + Fmt.coins(price) + " vs fair " + Fmt.coins(v.fairValue()) + " (" + Fmt.pct(discount) + " below).");
        why.add("Relist at " + Fmt.coins(sale) + ", fees " + Fmt.coins(fees) + " -> net " + Fmt.signedCoins(net) + ".");

        double risk = 0;
        risk += Stats.clamp(v.volatility() * 2, 0, 1) * 0.35;
        risk += Stats.clamp(v.saleHours() / Math.max(1, config.risk.maxHoldingHours * 2), 0, 1) * 0.25;
        risk += (1 - v.confidence()) * 0.25;
        if (discount > 0.6) {
            // Extreme discounts usually mean the item differs from comparables in a way we cannot see.
            risk += 0.15;
            why.add("Warning: discount above 60% — verify the item manually before buying.");
        }
        risk = Stats.clamp(risk, 0, 1);
        double liquidity = Stats.saturate(v.salesPerDay(), 4);

        List<String> ups = new ArrayList<>();
        for (UpgradeComponent c : UpgradeValuer.components(d.attrs())) ups.add(c.label() + (c.quantity() > 1 ? " x" + c.quantity() : ""));
        if (d.attrs().dungeonStars() > 0) ups.add(d.attrs().dungeonStars() + " dungeon stars");
        if (d.attrs().reforge() != null) ups.add("Reforge: " + d.attrs().reforge());
        if (d.attrs().isPet()) ups.add("Level " + d.attrs().petLevel());

        return new AuctionOpportunity(d.auction().uuid(), d.attrs().skyblockId(), d.attrs().displayName(), d.attrs(),
                price, v, gross, net, fees, risk, v.confidence(), liquidity, ups, why);
    }
}
