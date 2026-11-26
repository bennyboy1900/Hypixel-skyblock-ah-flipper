package dev.skytrade.valuation;

import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 */
public final class FairValueEstimator {
    public record Params(int minComparables, double recoveryRate, double undercut, long lookbackMs, long halfLifeMs) {}

    private final SalesHistory history;
    private final PriceSource prices;

    public FairValueEstimator(SalesHistory history, PriceSource prices) {
        this.history = history;
        this.prices = prices;
    }

    /**
     * @param competingBins prices of other active BIN listings with the same strict fingerprint (any order)
     */
    public Valuation estimate(ItemAttributes item, List<Long> competingBins, Params p, long now) {
        List<String> why = new ArrayList<>();
        String strict = Fingerprint.strict(item);
        String relaxed = Fingerprint.relaxed(item);
        why.add("Item: " + item.skyblockId() + " [" + item.rarity() + "]" + (item.isPet() ? " lvl " + item.petLevel() : ""));

        List<SaleRecord> exact = recent(history.strict(strict), now, p.lookbackMs());
        List<UpgradeComponent> ownUpgrades = UpgradeValuer.components(item);
        UpgradeValuer.Result own = UpgradeValuer.value(ownUpgrades, prices, p.recoveryRate());

        String level;
        double[] values;
        double[] weights;
        double upgradeCredit = 0;
        List<SaleRecord> used;
        if (exact.size() >= p.minComparables()) {
            level = "EXACT";
            used = exact;
            values = new double[used.size()];
            for (int i = 0; i < used.size(); i++) values[i] = used.get(i).price();
            why.add("Found " + used.size() + " exact comparable sales (same upgrades).");
        } else {
            List<SaleRecord> rel = recent(history.relaxed(relaxed), now, p.lookbackMs());
            if (rel.size() < p.minComparables()) {
                why.add("Only " + exact.size() + " exact and " + rel.size() + " similar sales in lookback window; need " + p.minComparables() + ".");
                return Valuation.reject("insufficient comparables", why);
            }
            level = "SIMILAR";
            used = rel;
            values = new double[used.size()];
            for (int i = 0; i < used.size(); i++) {
                SaleRecord s = used.get(i);
                List<UpgradeComponent> comps = new ArrayList<>();
                for (String u : s.upgrades()) comps.add(UpgradeComponent.decode(u));
                double credit = UpgradeValuer.value(comps, prices, p.recoveryRate()).value();
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
        conf *= level.equals("EXACT") ? 1.0 : 0.8;            // match quality
        conf *= 1 - Stats.clamp(dispersion * 1.5, 0, 0.6);     // price dispersion
        double upgradeShare = fair > 0 ? upgradeCredit / fair : 0;
        conf *= 1 - Stats.clamp(upgradeShare * 0.6, 0, 0.5);  // reliance on upgrade estimates
        if (own.unpriced() > 0 && level.equals("SIMILAR")) conf *= 0.85;
        conf = Stats.clamp(conf, 0, 1);
        why.add("Confidence " + Fmt.score(conf) + "/100.");

        return new Valuation(true, "", fair, salePrice, conf, kv.length, level, salesPerDay, saleHours, dispersion,
                upgradeCredit, why, keptRaw);
    }

    private static List<SaleRecord> recent(List<SaleRecord> list, long now, long lookback) {
        List<SaleRecord> out = new ArrayList<>(list.size());
        for (SaleRecord s : list) if (now - s.timestamp() <= lookback && s.timestamp() <= now) out.add(s);
        return out;
    }
}
