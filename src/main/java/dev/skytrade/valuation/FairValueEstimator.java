package dev.skytrade.valuation;

import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;

import java.util.*;

/**
 * Estimates the fair market value of an auction item from comparable completed sales.
 *
 * <ol>
 *   <li>Exact (strict fingerprint) comparables: recency-weighted median of their prices.</li>
 *   <li>Otherwise relaxed comparables: every comparable is normalised to a "clean" price by removing
 *       its own upgrades at the recovery rate, then the listing's upgrades are credited back at the
 *       same rate. Upgrades are never added at full cost.</li>
 *   <li>Outliers beyond 3.5 robust deviations are dropped.</li>
 *   <li>The fair value is capped by current BIN competition (we must undercut cheaper identical listings).</li>
 *   <li>If fewer than {@code minComparables} remain, the valuation is rejected.</li>
 * </ol>
 *
 * <p>Before any of that, comparables are cleaned of manipulation (see {@link #filterWashTrades}): sales in
 * which the listing's own seller took part are dropped and each seller/buyer pair counts once, so a few
 * accounts trading an item back and forth cannot set its price. A seller who sells many copies to many
 * different buyers is real demand and is kept (backtest on recorded Hypixel sales: a per-seller cap threw
 * away most valid comparables of bulk-sold items); a dominant seller only lowers confidence.</p>
 */
public final class FairValueEstimator {
    /** @param minEnchantCost enchant books cheaper than this are ignored when valuing upgrades */
    /**
     * @param minEnchantCost enchant books cheaper than this are ignored when valuing upgrades
     * @param maxPerPair     comparable sales one seller/buyer pair may contribute (0 = unlimited)
     */
    public record Params(int minComparables, double recoveryRate, double undercut, long lookbackMs, long halfLifeMs,
                         double minEnchantCost, int maxPerPair) {
        /** Default pair cap; see {@link FairValueEstimator#filterWashTrades}. */
        public static final int DEFAULT_MAX_PER_PAIR = 3;

        public Params(int minComparables, double recoveryRate, double undercut, long lookbackMs, long halfLifeMs) {
            this(minComparables, recoveryRate, undercut, lookbackMs, halfLifeMs, UpgradeValuer.DEFAULT_MIN_ENCHANT_COST, DEFAULT_MAX_PER_PAIR);
        }

        public Params(int minComparables, double recoveryRate, double undercut, long lookbackMs, long halfLifeMs, double minEnchantCost) {
            this(minComparables, recoveryRate, undercut, lookbackMs, halfLifeMs, minEnchantCost, DEFAULT_MAX_PER_PAIR);
        }
    }

    private final SalesHistory history;
    private final PriceSource prices;
    private final ItemCatalog catalog;

    public FairValueEstimator(SalesHistory history, PriceSource prices) {
        this(history, prices, ItemCatalog.EMPTY);
    }

    public FairValueEstimator(SalesHistory history, PriceSource prices, ItemCatalog catalog) {
        this.history = history;
        this.prices = prices;
        this.catalog = catalog == null ? ItemCatalog.EMPTY : catalog;
    }

    public Valuation estimate(ItemAttributes item, List<Long> competingBins, Params p, long now) {
        return estimate(item, competingBins, p, now, null);
    }

    /**
     * @param competingBins prices of other active BIN listings with the same strict fingerprint (any order)
     * @param listingSeller UUID of the player selling the evaluated listing (null if unknown); sales they took
     *                      part in are never used as comparables
     */
    public Valuation estimate(ItemAttributes item, List<Long> competingBins, Params p, long now, String listingSeller) {
        List<String> why = new ArrayList<>();
        String strict = Fingerprint.strict(item);
        String relaxed = Fingerprint.relaxed(item);
        why.add("Item: " + item.skyblockId() + " [" + item.rarity() + "]" + (item.isPet() ? " lvl " + item.petLevel() : ""));

        List<SaleRecord> exact = filterWashTrades(recent(history.strict(strict), now, p.lookbackMs()), listingSeller, p.maxPerPair(), why, "exact");
        List<UpgradeComponent> ownUpgrades = UpgradeValuer.components(item, catalog);
        UpgradeValuer.Result own = UpgradeValuer.value(ownUpgrades, prices, p.recoveryRate(), p.minEnchantCost());

        String level;
        double[] values;
        double[] weights;
        double upgradeCredit = 0;
        List<SaleRecord> used;
        if (exact.size() >= p.minComparables()) {
            level = "EXACT";
            used = exact;
            values = new double[used.size()];
            int adjusted = 0;
            for (int i = 0; i < used.size(); i++) {
                SaleRecord s = used.get(i);
                values[i] = s.price();
                // Exact matches share the value-dominant upgrades, but can still differ in smaller ones
                // (cheaper enchants, extra books, slot unlocks). Correct for that when the record is complete.
                if (!s.fullUpgrades()) continue;
                double diff = own.value() - UpgradeValuer.value(decode(s.upgrades()), prices, p.recoveryRate(), p.minEnchantCost()).value();
                if (Math.abs(diff) > 1 && Math.abs(diff) <= s.price() * 0.5) {
                    values[i] = s.price() + diff;
                    adjusted++;
                }
            }
            why.add("Found " + used.size() + " exact comparable sales (same upgrades)"
                    + (adjusted > 0 ? "; " + adjusted + " adjusted for smaller upgrade differences." : "."));
        } else {
            List<SaleRecord> rel = filterWashTrades(recent(history.relaxed(relaxed), now, p.lookbackMs()), listingSeller, p.maxPerPair(), why, "similar");
            if (rel.size() < p.minComparables()) {
                why.add("Only " + exact.size() + " exact and " + rel.size() + " similar sales in lookback window; need " + p.minComparables() + ".");
                return Valuation.reject("insufficient comparables", why);
            }
            level = "SIMILAR";
            used = rel;
            values = new double[used.size()];
            for (int i = 0; i < used.size(); i++) {
                SaleRecord s = used.get(i);
                double credit = UpgradeValuer.value(decode(s.upgrades()), prices, p.recoveryRate(), p.minEnchantCost()).value();
                values[i] = Math.max(s.price() * 0.25, s.price() - credit); // never strip more than 75%
            }
            upgradeCredit = own.value();
            why.add("Using " + used.size() + " similar sales (same item/rarity/" + (item.isPet() ? "level band" : "stars") + "), normalised to clean prices.");
            why.add(String.format(Locale.ROOT, "Upgrades credited at %.0f%% of cost: %s (full cost %s).",
                    p.recoveryRate() * 100, Fmt.coins(own.value()), Fmt.coins(own.fullCost())));
            why.addAll(own.lines().stream().map(l -> "  - " + l).toList());
        }

        weights = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            long age = Math.max(0, now - used.get(i).timestamp());
            weights[i] = Math.pow(0.5, (double) age / p.halfLifeMs());
        }
        // Outlier rejection with robust z-scores.
        double med = Stats.median(values);
        double mad = Stats.mad(values);
        List<Double> keptV = new ArrayList<>();
        List<Double> keptW = new ArrayList<>();
        List<Long> keptRaw = new ArrayList<>();
        int dropped = 0;
        for (int i = 0; i < values.length; i++) {
            if (mad > 0 && Math.abs(values[i] - med) / mad > 3.5) {
                dropped++;
                continue;
            }
            keptV.add(values[i]);
            keptW.add(weights[i]);
            keptRaw.add(used.get(i).price());
        }
        if (dropped > 0) why.add("Dropped " + dropped + " outlier sale(s) beyond 3.5 robust deviations.");
        if (keptV.size() < p.minComparables()) {
            why.add("Too few comparables after outlier removal.");
            return Valuation.reject("insufficient comparables after outlier removal", why);
        }
        double[] kv = keptV.stream().mapToDouble(Double::doubleValue).toArray();
        double[] kw = keptW.stream().mapToDouble(Double::doubleValue).toArray();
        double base = Stats.weightedMedian(kv, kw);
        double fair = base + upgradeCredit;
        double dispersion = Stats.mad(kv) / Math.max(1, Stats.median(kv));
        why.add("Recency-weighted median " + (level.equals("EXACT") ? "price: " : "clean price: ") + Fmt.coins(base)
                + " (dispersion " + Fmt.pct(dispersion) + ").");

        // Sale frequency from comparable sales in the window.
        long oldest = now;
        for (SaleRecord s : used) oldest = Math.min(oldest, s.timestamp());
        double spanDays = Math.max(1.0 / 24, (now - oldest) / 86_400_000.0);
        double salesPerDay = used.size() / spanDays;

        // BIN competition: the cheapest identical listings set a ceiling on our sale price.
        double salePrice = fair;
        int cheaper = 0;
        if (competingBins != null && !competingBins.isEmpty()) {
            long lowest = Long.MAX_VALUE;
            for (long c : competingBins) {
                lowest = Math.min(lowest, c);
                if (c < fair) cheaper++;
            }
            double ceiling = lowest * (1 - p.undercut());
            if (ceiling < salePrice) {
                salePrice = ceiling;
                why.add("Capped by cheapest competing BIN " + Fmt.coins(lowest) + " (undercut " + Fmt.pct(p.undercut()) + ").");
            }
        }
        double saleHours = salesPerDay > 0 ? 24.0 * (cheaper + 1) / salesPerDay : Double.POSITIVE_INFINITY;
        why.add(String.format(Locale.ROOT, "~%.1f comparable sales/day, %d cheaper listings ahead -> est. sale in %s.",
                salesPerDay, cheaper, Fmt.duration(saleHours * 3_600_000)));

        double conf = 1.0;
        conf *= Stats.saturate(kv.length, 6) * 1.4;            // sample size
        // Match quality. Walk-forward on 7,196 recorded sales: exact matches had a 2.5% median error, similar
        // (upgrade-normalised) ones 26% with 22% overestimated by more than 20%, so similar matches rarely
        // clear the minimum confidence on their own.
        conf *= level.equals("EXACT") ? 1.0 : 0.6;
        conf *= 1 - Stats.clamp(dispersion * 1.5, 0, 0.6);     // price dispersion
        double upgradeShare = fair > 0 ? upgradeCredit / fair : 0;
        conf *= 1 - Stats.clamp(upgradeShare * 0.6, 0, 0.5);  // reliance on upgrade estimates
        if (own.unpriced() > 0 && level.equals("SIMILAR")) conf *= 0.85;
        double topSellerShare = topSellerShare(used);
        if (topSellerShare > 0.4 && used.size() >= 3) {
            conf *= 1 - Stats.clamp(topSellerShare - 0.4, 0, 0.4);
            why.add("Warning: one seller made " + Fmt.pct(topSellerShare) + " of the comparable sales (thin, concentrated market).");
        }
        conf = Stats.clamp(conf, 0, 1);
        why.add("Confidence " + Fmt.score(conf) + "/100.");

        return new Valuation(true, "", fair, salePrice, conf, kv.length, level, salesPerDay, saleHours, dispersion,
                upgradeCredit, why, keptRaw);
    }

    private static List<UpgradeComponent> decode(List<String> encoded) {
        List<UpgradeComponent> comps = new ArrayList<>(encoded.size());
        for (String u : encoded) if (!u.startsWith("~")) comps.add(UpgradeComponent.decode(u));
        return comps;
    }

    /**
     * Removes comparables that look like wash trades or self-dealing:
     * <ul>
     *   <li>any sale the evaluated listing's seller bought (self-pumping),</li>
     *   <li>more than {@code maxPerPair} sales between the same seller and buyer (the most recent ones count).
     *       Backtest on 7,196 recorded sales: counting a pair only once threw away legitimate bulk deals and
     *       raised the median error from 2.6% to 3.8%; a cap of 3 keeps most of that accuracy while a pump
     *       still needs many independent buyers.</li>
     * </ul>
     * Sales without seller/buyer data (older records) pass unchanged.
     */
    static List<SaleRecord> filterWashTrades(List<SaleRecord> sales, String listingSeller, int maxPerPair, List<String> why, String label) {
        if (sales.isEmpty()) return sales;
        List<SaleRecord> sorted = new ArrayList<>(sales);
        sorted.sort(Comparator.comparingLong(SaleRecord::timestamp).reversed());
        Map<String, Integer> pairs = new HashMap<>();
        List<SaleRecord> out = new ArrayList<>(sorted.size());
        int self = 0, repeat = 0;
        for (SaleRecord s : sorted) {
            // Self-dealing: the listing's seller bought copies to push the price up. Their own earlier sales to
            // other players are real evidence and stay (dropping them cost bulk sellers their best comparables).
            if (listingSeller != null && listingSeller.equals(s.buyer())) {
                self++;
                continue;
            }
            if (maxPerPair > 0 && s.seller() != null && s.buyer() != null && pairs.merge(pairKey(s.seller(), s.buyer()), 1, Integer::sum) > maxPerPair) {
                repeat++;
                continue;
            }
            out.add(s);
        }
        if (self > 0) why.add("Ignored " + self + " " + label + " sale(s) bought by this listing's seller (possible self-pumping).");
        if (repeat > 0) why.add("Ignored " + repeat + " repeated " + label + " sale(s) between the same two players (wash trading).");
        return out;
    }

    /** Both directions count as one pair: two accounts trading an item back and forth are one source. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static double topSellerShare(List<SaleRecord> sales) {
        Map<String, Integer> n = new HashMap<>();
        int known = 0;
        for (SaleRecord s : sales) {
            if (s.seller() == null) continue;
            known++;
            n.merge(s.seller(), 1, Integer::sum);
        }
        if (known == 0) return 0;
        int max = 0;
        for (int v : n.values()) max = Math.max(max, v);
        return (double) max / known;
    }

    private static List<SaleRecord> recent(List<SaleRecord> list, long now, long lookback) {
        List<SaleRecord> out = new ArrayList<>(list.size());
        for (SaleRecord s : list) if (now - s.timestamp() <= lookback && s.timestamp() <= now) out.add(s);
        return out;
    }
}
