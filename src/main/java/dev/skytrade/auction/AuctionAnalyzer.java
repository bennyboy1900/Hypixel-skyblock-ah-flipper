package dev.skytrade.auction;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.ExternalPriceService;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.market.ItemCatalog;
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
 *
 * <p>Every candidate then goes through {@link MarketGuard}: the local valuation is checked against the item's
 * 7-day external price history. The best candidates of each scan request history they do not have yet, so the
 * next scan can confirm them.</p>
 */
public final class AuctionAnalyzer {
    /** History lookup; {@code request} asks the source to fetch data it does not have. */
    @FunctionalInterface
    public interface HistoryLookup {
        PriceHistoryStats get(ItemAttributes item, boolean request);

        HistoryLookup NONE = (a, r) -> null;
    }

    private final SkyTradeConfig config;
    private final HistoryLookup history;
    private final ItemCatalog catalog;

    public AuctionAnalyzer(SkyTradeConfig config) {
        this(config, HistoryLookup.NONE, ItemCatalog.EMPTY);
    }

    public AuctionAnalyzer(SkyTradeConfig config, ExternalPriceService external, ItemCatalog catalog) {
        this(config, external == null ? HistoryLookup.NONE : external::get, catalog);
    }

    public AuctionAnalyzer(SkyTradeConfig config, HistoryLookup history, ItemCatalog catalog) {
        this.config = config;
        this.history = history == null ? HistoryLookup.NONE : history;
        this.catalog = catalog == null ? ItemCatalog.EMPTY : catalog;
    }

    public List<AuctionOpportunity> analyze(AuctionIndex index, FairValueEstimator estimator, double maxCapital, long now) {
        SkyTradeConfig.Strategy s = config.strategy;
        FairValueEstimator.Params params = new FairValueEstimator.Params((int) Math.max(3, s.ahMinComparables),
                s.upgradeRecoveryRate, s.ahUndercut, 7L * 86_400_000L, 2L * 86_400_000L);
        List<AuctionOpportunity> local = new ArrayList<>();
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
            if (o != null) local.add(o);
        }
        local.sort(Comparator.comparingDouble(AuctionOpportunity::score).reversed());

        List<AuctionOpportunity> out = new ArrayList<>(local.size());
        int requestBudget = Math.max(0, config.external.candidatesPerScan);
        for (AuctionOpportunity o : local) {
            boolean request = requestBudget-- > 0;
            AuctionOpportunity g = guard(o, history.get(o.attrs(), request));
            if (g != null) out.add(g);
        }
        out.sort(Comparator.comparingDouble(AuctionOpportunity::score).reversed());
        int top = s.auctionTopN;
        return out.size() > top ? new ArrayList<>(out.subList(0, top)) : out;
    }

    AuctionOpportunity evaluate(DecodedAuction d, List<Long> competing, FairValueEstimator estimator,
                                FairValueEstimator.Params params, long now) {
        Valuation v = estimator.estimate(d.attrs(), competing, params, now, d.auction().auctioneer());
        if (!v.valid()) return null;
        return build(d.auction().uuid(), d.attrs(), d.auction().startingBid(), v, List.of(), true, d.auction().auctioneer());
    }

    /** Applies {@link MarketGuard} to a locally valued opportunity. Null when rejected or no longer profitable. */
    AuctionOpportunity guard(AuctionOpportunity o, PriceHistoryStats h) {
        MarketGuard.Result g = MarketGuard.check(o.valuation(), o.listingPrice(), h, config.external);
        if (g.rejected()) return null;
        Valuation v = o.valuation();
        if (g.fairMultiplier() != 1 || g.confidenceMultiplier() != 1) {
            double fair = v.fairValue() * g.fairMultiplier();
            v = new Valuation(true, "", fair, Math.min(v.salePrice(), fair), Stats.clamp(v.confidence() * g.confidenceMultiplier(), 0, 1),
                    v.comparables(), v.matchLevel(), v.salesPerDay(), v.saleHours(), v.volatility(), v.upgradeCredit(),
                    v.explanation(), v.comparablePrices());
        }
        return build(o.uuid(), o.attrs(), o.listingPrice(), v, g.lines(), g.confirmed(), o.seller());
    }

    private AuctionOpportunity build(String uuid, ItemAttributes a, long price, Valuation v, List<String> guardLines,
                                     boolean confirmed, String seller) {
        double sale = v.salePrice();
        double fees = AuctionFees.listingFee(sale, config.fees) + AuctionFees.claimTax(sale, config.fees);
        double gross = sale - price;
        double net = sale - fees - price;
        if (net <= 0) return null;
        if (v.saleHours() > config.strategy.ahMaxSaleHours) return null;

        double discount = v.fairValue() > 0 ? 1 - price / v.fairValue() : 0;
        List<String> why = new ArrayList<>(v.explanation());
        why.addAll(guardLines);
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
        if (!confirmed) risk += 0.1;
        risk = Stats.clamp(risk, 0, 1);
        double liquidity = Stats.saturate(v.salesPerDay(), 4);

        List<String> ups = new ArrayList<>();
        for (UpgradeComponent c : UpgradeValuer.components(a, catalog)) {
            if (UpgradeValuer.kind(c.itemId()) == UpgradeValuer.Kind.ENCHANT && !c.itemId().matches(".*_([6-9]|10)$")) continue;
            String label = c.label().equals("Enchant book") || c.label().equals("Upgrade") ? Fmt.prettyId(c.itemId().replace("ENCHANTMENT_", "")) : c.label();
            ups.add(label + (c.quantity() > 1 ? " x" + c.quantity() : ""));
        }
        if (a.dungeonStars() > 0) ups.add(a.dungeonStars() + " dungeon stars");
        if (a.reforge() != null) ups.add("Reforge: " + a.reforge());
        if (a.isPet()) ups.add("Level " + a.petLevel());

        return new AuctionOpportunity(uuid, a.skyblockId(), a.displayName(), a, price, v, gross, net, fees, risk,
                v.confidence(), liquidity, ups, why, confirmed, seller);
    }
}
