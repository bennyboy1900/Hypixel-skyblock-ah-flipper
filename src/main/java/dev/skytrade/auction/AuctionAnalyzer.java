package dev.skytrade.auction;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.ExternalPriceService;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;
import dev.skytrade.valuation.FairValueEstimator;
import dev.skytrade.valuation.PriceSource;
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
    private PriceSource prices = id -> Double.NaN;

    /** A locally unvalued listing that is clearly below its identical competitors: a market-fallback candidate. */
    private record Pending(DecodedAuction cand, List<Long> competing, double gap) {}

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

    /** Prices for upgrade components (used by the market fallback). */
    public AuctionAnalyzer withPrices(PriceSource prices) {
        if (prices != null) this.prices = prices;
        return this;
    }

    public List<AuctionOpportunity> analyze(AuctionIndex index, FairValueEstimator estimator, double maxCapital, long now) {
        SkyTradeConfig.Strategy s = config.strategy;
        FairValueEstimator.Params params = new FairValueEstimator.Params((int) Math.max(3, s.ahMinComparables),
                s.upgradeRecoveryRate, s.ahUndercut, 7L * 86_400_000L, 2L * 86_400_000L, Math.max(0, s.minEnchantValue));
        List<AuctionOpportunity> local = new ArrayList<>();
        List<Pending> market = new ArrayList<>();
        SkyTradeConfig.External ext = config.external;
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
            Valuation v = estimator.estimate(cand.attrs(), competing, params, now, cand.auction().auctioneer());
            if (v.valid()) {
                AuctionOpportunity o = build(cand.auction().uuid(), cand.attrs(), price, v, List.of(), true, cand.auction().auctioneer());
                if (o != null) local.add(o);
            } else if (ext.enabled && ext.marketFallback && competing.size() >= Math.max(1, ext.minCompetingListings)) {
                double resale = competing.get(0) * (1 - s.ahUndercut);
                double gap = resale - AuctionFees.listingFee(resale, config.fees) - AuctionFees.claimTax(resale, config.fees) - price;
                if (gap >= config.risk.minNetProfit) market.add(new Pending(cand, competing, gap));
            }
        }
        local.sort(Comparator.comparingDouble(AuctionOpportunity::score).reversed());
        market.sort(Comparator.comparingDouble(Pending::gap).reversed());

        List<AuctionOpportunity> out = new ArrayList<>(local.size());
        int requestBudget = Math.max(0, config.external.candidatesPerScan);
        for (AuctionOpportunity o : local) {
            boolean request = requestBudget-- > 0;
            AuctionOpportunity g = guard(o, history.get(o.attrs(), request));
            if (g != null) out.add(g);
        }
        for (Pending pd : market) {
            boolean request = requestBudget-- > 0;
            PriceHistoryStats h = history.get(pd.cand().attrs(), request);
            Valuation v = marketValuation(pd.cand().attrs(), pd.competing(), h, params);
            if (v == null) continue;
            AuctionOpportunity o = build(pd.cand().auction().uuid(), pd.cand().attrs(), pd.cand().auction().startingBid(), v,
                    List.of(), true, pd.cand().auction().auctioneer());
            AuctionOpportunity g = o == null ? null : guard(o, h);
            if (g != null) out.add(g);
        }
        out.sort(Comparator.comparingDouble(AuctionOpportunity::score).reversed());
        int top = s.auctionTopN;
        return out.size() > top ? new ArrayList<>(out.subList(0, top)) : out;
    }

    /**
     * Values a listing without enough local sales: its resale price is the cheapest identical listing from
     * another seller (undercut), capped by what the item actually sold for in the last 7 days plus its upgrades.
     * Requires usable 7-day history with enough volume. Confidence is capped at 60%.
     */
    public Valuation marketValuation(ItemAttributes a, List<Long> competing, PriceHistoryStats h, FairValueEstimator.Params p) {
        SkyTradeConfig.External ext = config.external;
        if (h == null || !h.usable() || h.volume() < ext.minWeeklySales || competing.size() < Math.max(1, ext.minCompetingListings)) return null;
        List<String> why = new ArrayList<>();
        why.add("Item: " + a.skyblockId() + " [" + a.rarity() + "]" + (a.isPet() ? " lvl " + a.petLevel() : ""));
        why.add("Not enough local sales yet: valued from the market (Coflnet 7-day history + identical listings).");
        long lowest = competing.get(0);
        if (!Stats.plausibleCoins(lowest) || !MarketGuard.sane(h)) return null;
        double resale = lowest * (1 - p.undercut());
        why.add(competing.size() + " identical listings from other sellers; cheapest " + Fmt.coins(lowest)
                + ", next " + Fmt.coins(competing.get(Math.min(1, competing.size() - 1))) + ". Undercut to " + Fmt.coins(resale) + ".");
        UpgradeValuer.Result up = UpgradeValuer.value(UpgradeValuer.components(a, catalog), prices, p.recoveryRate(), p.minEnchantCost());
        double cap = Math.min(h.high(), Math.max(h.median(), h.recent()) * 1.25 + up.value());
        if (resale > cap) {
            why.add("Capped at " + Fmt.coins(cap) + ": 7-day median " + Fmt.coins(h.median()) + " x1.25 + upgrades "
                    + Fmt.coins(up.value()) + ", at most the 7-day high " + Fmt.coins(h.high()) + ".");
            resale = cap;
        }
        if (!(resale > 0)) return null;
        // History covers every variant of the item; assume this exact variant gets half of the sales.
        double salesPerDay = h.volume() / 7.0 * 0.5;
        double saleHours = salesPerDay > 0 ? 24.0 / salesPerDay : Double.POSITIVE_INFINITY;
        double volatility = h.median() > 0 ? Stats.clamp((h.high() - h.low()) / h.median() * 0.25, 0, 1) : 1;
        double conf = Stats.clamp(0.6 * Stats.saturate(h.volume(), 20) * 1.4, 0, 0.6);
        why.add(String.format(Locale.ROOT, "~%.1f sales/day for this item -> est. sale in %s.", salesPerDay, Fmt.duration(saleHours * 3_600_000)));
        why.add("Confidence " + Fmt.score(conf) + "/100 (market estimate, capped at 60).");
        List<Long> shown = competing.subList(0, Math.min(10, competing.size()));
        return new Valuation(true, "", resale, resale, conf, competing.size(), "MARKET", salesPerDay, saleHours, volatility,
                0, why, List.copyOf(shown));
    }

    /**
     * A flip must earn more than the item's normal price noise: the net margin (net / sale price) has to be at
     * least {@code k} times the robust price dispersion of the comparables. Walk-forward backtest on recorded
     * Hypixel sales: flips inside the noise band (e.g. a God Potion bought 5% under a median it wobbles 10%
     * around) mostly had to be resold below the purchase price.
     */
    public static boolean clearsNoise(double net, double sale, double dispersion, double k) {
        if (!(sale > 0) || !(net > 0)) return false;
        double d = Double.isFinite(dispersion) ? Math.max(0, dispersion) : 1;
        return net / sale >= k * d;
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
        if (!clearsNoise(net, sale, v.volatility(), config.strategy.ahNoiseMargin)) return null;

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
            if (UpgradeValuer.kind(c.itemId()) == UpgradeValuer.Kind.ENCHANT && !c.itemId().matches(".*_([5-9]|10)$")) continue;
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
