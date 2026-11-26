package dev.skytrade.crafting;

import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.AuctionIndex;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.market.SkyblockItem;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.Stats;
import dev.skytrade.valuation.SaleRecord;
import dev.skytrade.valuation.SalesHistory;

import java.util.*;

/**
 * Compares material cost against a conservative finished-goods value for every item that has a
 * verified recipe, plus recombobulation upgrade flips on the Auction House.
 */
public final class CraftFlipAnalyzer {
    private static final String[] RARITY_ORDER = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};
    private final SkyTradeConfig config;

    public CraftFlipAnalyzer(SkyTradeConfig config) {
        this.config = config;
    }

    public List<CraftOpportunity> analyze(RecipeRepository recipes, BazaarSnapshot bz, AuctionIndex ah, SalesHistory sales,
                                          ItemCatalog catalog, double maxCapital, long now) {
        CraftCostCalculator.Market market = new CraftCostCalculator.Market() {
            @Override
            public double buyPrice(String id) {
                BazaarProduct p = bz == null ? null : bz.get(id);
                if (p != null && p.bestAsk() > 0 && !p.asks().isEmpty()) return p.bestAsk();
                return ah.lowestCleanBin(id);
            }

            @Override
            public String buyMethod(String id) {
                BazaarProduct p = bz == null ? null : bz.get(id);
                return p != null && !p.asks().isEmpty() ? "bazaar instant-buy" : "lowest BIN";
            }
        };
        CraftCostCalculator calc = new CraftCostCalculator(recipes, market, 5);
        List<CraftOpportunity> out = new ArrayList<>();
        for (Map.Entry<String, List<Recipe>> e : recipes.all().entrySet()) {
            String id = e.getKey();
            for (Recipe r : e.getValue()) {
                CraftOpportunity o = evaluateCraft(id, r, calc, bz, ah, sales, catalog, maxCapital, now);
                if (o != null) out.add(o);
            }
        }
        out.addAll(recombUpgrades(bz, ah, sales, catalog, maxCapital, now));
        out.sort(Comparator.comparingDouble(CraftOpportunity::score).reversed());
        return out.size() > 150 ? new ArrayList<>(out.subList(0, 150)) : out;
    }

    private CraftOpportunity evaluateCraft(String id, Recipe r, CraftCostCalculator calc, BazaarSnapshot bz, AuctionIndex ah,
                                           SalesHistory sales, ItemCatalog catalog, double maxCapital, long now) {
        CostBreakdown cost = calc.craftCost(r);
        if (!cost.priced()) return null;
        double unitCost = cost.unitCost();
        double haircut = config.strategy.craftSaleHaircut;
        BazaarProduct p = bz == null ? null : bz.get(id);
        double saleValue, saleHours, liquidity;
        String venue;
        List<String> why = new ArrayList<>();
        why.add("Recipe source: " + r.source() + " (" + r.outputCount() + " per craft).");
        if (p != null && p.hasBook() && p.instantBuysPerHour() > 0) {
            double exit = p.bestAsk() - 0.1;
            saleValue = exit * (1 - config.fees.bazaarTax) * haircut;
            venue = "BAZAAR";
            long qty = Math.max(1, (long) Math.min(Math.floor(maxCapital * 0.1 / unitCost), p.instantBuysPerHour() * 2));
            saleHours = qty / Math.max(1e-6, p.instantBuysPerHour() * 0.4);
            liquidity = Stats.saturate(p.instantBuysPerHour() * exit, 3_000_000);
            why.add("Sell on bazaar at " + Fmt.coins(exit) + " less tax and " + Fmt.pct(1 - haircut) + " safety haircut.");
            return finish(id, r, catalog, "CRAFT", qty, unitCost, saleValue, saleHours, liquidity, venue, cost, why, 0.85);
        }
        // Auction House sale: require observed completed sales of clean copies.
        List<SaleRecord> hist = sales.base(id);
        List<Double> recent = new ArrayList<>();
        for (SaleRecord s : hist) if (now - s.timestamp() < 7 * 86_400_000L && s.upgrades().isEmpty() && s.timestamp() <= now) recent.add((double) s.price());
        if (recent.size() < config.strategy.ahMinComparables) return null;
        double med = Stats.median(recent);
        double lowest = ah.lowestCleanBin(id);
        double target = Double.isFinite(lowest) ? Math.min(med, lowest * (1 - config.strategy.ahUndercut)) : med;
        saleValue = AuctionFees.netProceeds(target * haircut, config.fees);
        venue = "AUCTION";
        double perDay = recent.size() / 7.0;
        saleHours = 24 / Math.max(0.05, perDay);
        liquidity = Stats.saturate(perDay, 4);
        why.add("Sell on AH: median of " + recent.size() + " clean sales " + Fmt.coins(med) + (Double.isFinite(lowest) ? ", lowest BIN " + Fmt.coins(lowest) : "") + ".");
        return finish(id, r, catalog, "CRAFT", 1, unitCost, saleValue, saleHours, liquidity, venue, cost, why,
                Stats.clamp(Stats.saturate(recent.size(), 6) * 1.3, 0, 0.9));
    }

    private CraftOpportunity finish(String id, Recipe r, ItemCatalog catalog, String type, long qty, double unitCost,
                                    double unitSale, double saleHours, double liquidity, String venue, CostBreakdown cost,
                                    List<String> why, double confidence) {
        double net = (unitSale - unitCost) * qty;
        if (net < config.risk.minNetProfit || unitSale <= unitCost) return null;
        why.add("Material cost " + Fmt.coins(unitCost) + " per unit, sale value " + Fmt.coins(unitSale) + ".");
        for (CostBreakdown c : cost.children()) {
            why.add("  - " + catalog.displayName(c.itemId()) + " x" + Fmt.num(c.quantity()) + " @ " + Fmt.coins(c.unitCost()) + " (" + c.method() + ")");
        }
        double risk = Stats.clamp(0.15 + (1 - liquidity) * 0.4 + Stats.clamp(saleHours / 48, 0, 1) * 0.3, 0, 1);
        double hold = Math.max(0.1, saleHours + 0.1);
        return new CraftOpportunity(id, catalog.displayName(id), type, qty, unitCost, unitSale, (unitSale - unitCost) * qty, net,
                hold, risk, confidence, liquidity, venue, cost.children(), why);
    }

    /** Buy the cheapest clean copy, apply a Recombobulator 3000, sell at the observed recombobulated price. */
    private List<CraftOpportunity> recombUpgrades(BazaarSnapshot bz, AuctionIndex ah, SalesHistory sales, ItemCatalog catalog,
                                                  double maxCapital, long now) {
        List<CraftOpportunity> out = new ArrayList<>();
        BazaarProduct recomb = bz == null ? null : bz.get("RECOMBOBULATOR_3000");
        if (recomb == null || !(recomb.bestAsk() > 0)) return out;
        double recombCost = recomb.bestAsk();
        for (SkyblockItem item : catalog.items().values()) {
            double clean = ah.lowestCleanBin(item.id());
            if (!Double.isFinite(clean) || clean + recombCost > maxCapital) continue;
            String next = nextRarity(item.tier());
            if (next == null) continue;
            String strictKey = item.id() + "|" + next + "|s0|R";
            List<Double> prices = new ArrayList<>();
            for (SaleRecord s : sales.strict(strictKey)) if (now - s.timestamp() < 7 * 86_400_000L && s.timestamp() <= now) prices.add((double) s.price());
            if (prices.size() < config.strategy.ahMinComparables) continue;
            double med = Stats.median(prices);
            double sale = AuctionFees.netProceeds(med * config.strategy.craftSaleHaircut, config.fees);
            double unitCost = clean + recombCost;
            List<String> why = new ArrayList<>();
            why.add("Upgrade flip: buy clean " + catalog.displayName(item.id()) + " at " + Fmt.coins(clean) + ", apply Recombobulator (" + Fmt.coins(recombCost) + ").");
            why.add("Recombobulated clean copies sold at median " + Fmt.coins(med) + " over " + prices.size() + " sales.");
            double perDay = prices.size() / 7.0;
            CostBreakdown base = new CostBreakdown(item.id(), 1, clean, "lowest BIN", List.of());
            CostBreakdown rc = new CostBreakdown("RECOMBOBULATOR_3000", 1, recombCost, "bazaar instant-buy", List.of());
            CostBreakdown total = new CostBreakdown(item.id(), 1, unitCost, "upgrade", List.of(base, rc));
            CraftOpportunity o = finish(item.id(), null, catalog, "RECOMB", 1, unitCost, sale, 24 / Math.max(0.05, perDay),
                    Stats.saturate(perDay, 4), "AUCTION", total, why, Stats.clamp(Stats.saturate(prices.size(), 6) * 1.2, 0, 0.85));
            if (o != null) out.add(o);
        }
        return out;
    }

    static String nextRarity(String tier) {
        for (int i = 0; i < RARITY_ORDER.length - 1; i++) if (RARITY_ORDER[i].equals(tier)) return RARITY_ORDER[i + 1];
        return null;
    }
}
