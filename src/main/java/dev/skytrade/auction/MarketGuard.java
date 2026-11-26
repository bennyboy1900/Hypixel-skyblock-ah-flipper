package dev.skytrade.auction;

import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;
import dev.skytrade.valuation.Valuation;

import java.util.ArrayList;
import java.util.List;

/**
 * Last line of defence before an AH flip is shown: checks the local valuation against the item's 7-day
 * external price history to catch market manipulation and listings that are too good to be true.
 *
 * <ul>
 *   <li><b>Illiquid:</b> too few sales in 7 days, so there is nobody to sell to. Rejected.</li>
 *   <li><b>Pump:</b> the last 24h trade far above the 7-day median (someone is buying up the supply or wash
 *       trading). Fair value is scaled back to the median level.</li>
 *   <li><b>Falling market:</b> a strong negative weekly trend. Fair value is reduced by half the trend.</li>
 *   <li><b>Inflated comparables:</b> the clean local value is far above anything that sold in 7 days. Rejected.</li>
 *   <li><b>Too good to be true:</b> a very deep discount is only accepted for an exact match with confirmed
 *       history and a price that is not absurdly far below every recent sale. These are usually items that
 *       differ in a way the comparables cannot show (wrong variant, bait listings).</li>
 * </ul>
 * Without history the flip is marked unconfirmed and loses confidence; it is never silently trusted.
 */
public final class MarketGuard {
    public record Result(boolean rejected, String reason, double fairMultiplier, double confidenceMultiplier,
                         boolean confirmed, List<String> lines) {}

    private MarketGuard() {}

    public static Result check(Valuation v, long listingPrice, PriceHistoryStats h, SkyTradeConfig.External cfg) {
        List<String> lines = new ArrayList<>();
        double fair = v.fairValue();
        if (!Stats.plausibleCoins(fair) || !Stats.plausibleCoins(listingPrice) || !Stats.plausibleCoins(v.salePrice())) {
            return reject("invalid valuation numbers", lines);
        }
        if (!cfg.enabled) return new Result(false, "", 1, 1, true, lines);
        double discount = fair > 0 ? 1 - listingPrice / fair : 0;

        if (h != null && h.usable() && !sane(h)) {
            return reject("7-day history is inconsistent (" + h + ")", lines);
        }
        if (h == null || !h.usable()) {
            lines.add("No 7-day price history yet (requested). Treat as unconfirmed.");
            if (discount > cfg.deepDiscount) {
                return reject("discount of " + Fmt.pct(discount) + " without price history to confirm it", lines);
            }
            return new Result(false, "", 1, cfg.requireConfirmation ? 0.75 : 0.9, false, lines);
        }
        lines.add("7-day history: median " + Fmt.coins(h.median()) + ", last 24h " + Fmt.coins(h.recent())
                + ", range " + Fmt.coins(h.low()) + " - " + Fmt.coins(h.high()) + ", " + h.volume() + " sales, trend "
                + Fmt.pct(h.trend()) + ".");

        if (h.volume() < cfg.minWeeklySales) {
            return reject("only " + h.volume() + " sales in 7 days (illiquid)", lines);
        }

        double mult = 1, conf = 1;
        if (h.recent() > cfg.pumpRatio * h.median()) {
            double m = h.median() / h.recent();
            mult *= m;
            conf *= 0.8;
            lines.add("Price pumped: last 24h is " + String.format(java.util.Locale.ROOT, "%.2fx", h.recent() / h.median())
                    + " the 7-day median. Fair value scaled by " + Fmt.pct(m) + ".");
        }
        if (h.trend() < cfg.crashTrend) {
            double m = 1 + h.trend() / 2;
            mult *= m;
            conf *= 0.85;
            lines.add("Falling market (" + Fmt.pct(h.trend()) + " this week). Fair value reduced to " + Fmt.pct(m) + ".");
        }

        double cleanLocal = fair - v.upgradeCredit();
        if (cleanLocal > cfg.maxOverWeeklyHigh * h.high()) {
            return reject("local comparables (" + Fmt.coins(cleanLocal) + ") far above anything sold in 7 days ("
                    + Fmt.coins(h.high()) + "): likely manipulated sales", lines);
        }

        if (mult < 0.2) return reject("history says the value is far below the estimate (x" + String.format(java.util.Locale.ROOT, "%.2f", mult) + ")", lines);
        double adjustedFair = fair * mult;
        double adjustedDiscount = adjustedFair > 0 ? 1 - listingPrice / adjustedFair : 0;
        if (adjustedDiscount > cfg.deepDiscount) {
            if (!"EXACT".equals(v.matchLevel())) {
                return reject("discount of " + Fmt.pct(adjustedDiscount) + " on a non-exact match (item probably differs)", lines);
            }
            if (listingPrice < 0.5 * h.low()) {
                return reject("listing is below half of the lowest sales in 7 days (bait or different item)", lines);
            }
            conf *= 0.8;
            lines.add("Deep discount accepted: exact match and consistent with 7-day history. Still check the item before buying.");
        }
        return new Result(false, "", mult, conf, true, lines);
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /** History numbers must be finite, at least one coin, and ordered low <= median <= high (within rounding). */
    static boolean sane(PriceHistoryStats h) {
        if (!finite(h.median()) || !finite(h.recent()) || !finite(h.low()) || !finite(h.high()) || !finite(h.trend())) return false;
        if (h.median() < 1 || h.recent() < 1 || h.low() < 1 || h.high() < 1) return false;
        if (h.high() > Stats.MAX_COINS) return false;
        return h.low() <= h.median() * 1.001 && h.median() <= h.high() * 1.001;
    }

    private static Result reject(String reason, List<String> lines) {
        lines.add("Rejected: " + reason + ".");
        return new Result(true, reason, 0, 0, false, lines);
    }
}
