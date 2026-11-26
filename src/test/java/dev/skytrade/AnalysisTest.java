package dev.skytrade;

import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.config.StrategyPreset;
import dev.skytrade.crafting.CraftCostCalculator;
import dev.skytrade.crafting.Recipe;
import dev.skytrade.crafting.RecipeRepository;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.market.MarketHistory;
import dev.skytrade.market.PricePoint;
import dev.skytrade.risk.PositionSizer;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.valuation.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @Test
    void bazaarFlipMetrics() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        var p = TestData.product("ITEM", 100, 110, 70_000);
        MarketHistory h = new MarketHistory(100, 0);
        for (int i = 0; i < 30; i++) h.record("ITEM", new PricePoint(i * 300_000L, 100, 110, 75000, 75000, 70000, 70000));
        BazaarOpportunity o = new BazaarAnalyzer(cfg).analyzeProduct(p, h.range("ITEM", 0, Long.MAX_VALUE), ItemCatalog.EMPTY,
                SizingContext.of(100_000_000), 10_000);
        assertNotNull(o);
        assertEquals(100.1, o.entryPrice(), 1e-9);
        assertEquals(109.9, o.exitPrice(), 1e-9);
        double perUnit = 109.9 * (1 - cfg.fees.bazaarTax) - 100.1;
        assertEquals(perUnit * o.quantity(), o.netProfit(), 1e-6);
        assertTrue(o.quantity() > 0);
        assertTrue(o.quantity() <= 70_000 / 7 * 0.10 + 1, "never more than 10% of daily volume");
        assertTrue(o.holdHours() > 0);
        assertTrue(o.risk() >= 0 && o.risk() <= 1 && o.confidence() > 0 && o.confidence() <= 1);
        assertTrue(o.flags().isEmpty(), o.flags().toString());
    }

    @Test
    void noFlipWhenSpreadDoesNotCoverTax() {
        var p = TestData.product("ITEM", 100, 100.5, 70_000);
        assertNull(new BazaarAnalyzer(new SkyTradeConfig()).analyzeProduct(p, List.of(), ItemCatalog.EMPTY, SizingContext.of(1e8), 0));
    }

    @Test
    void manipulationIsFlagged() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        List<PricePoint> hist = new ArrayList<>();
        for (int i = 0; i < 30; i++) hist.add(new PricePoint(i, 100 + (i % 3), 110 + (i % 3), 75000, 75000, 70000, 70000));
        var spiked = TestData.product("ITEM", 100, 400, 70_000);
        BazaarOpportunity o = new BazaarAnalyzer(cfg).analyzeProduct(spiked, hist, ItemCatalog.EMPTY, SizingContext.of(1e8), 0);
        assertNotNull(o);
        assertFalse(o.flags().isEmpty());
        assertTrue(o.risk() > 0.3);
    }

    @Test
    void sizingRespectsReserveAndExposure() {
        SkyTradeConfig.Risk r = new SkyTradeConfig().risk;
        StrategyPreset.BALANCED.apply(r);
        var in = new PositionSizer.Input("X", "G", 1000, 1e9, 1e9, Long.MAX_VALUE, Long.MAX_VALUE);
        var res = PositionSizer.size(in, new SizingContext(10_000_000, 1_500_000, Map.of(), Map.of(), 14, 0), r);
        assertEquals(500, res.quantity(), "cash 1.5M minus 10% reserve of 10M = 0.5M");
        res = PositionSizer.size(in, new SizingContext(10_000_000, 10_000_000, Map.of("X", 2_400_000.0), Map.of(), 14, 0), r);
        assertEquals(100, res.quantity());
        assertEquals("max exposure per item", res.bindingConstraint());
        // Slot limits are execution rules: the sizer still sizes, the risk manager rejects.
        SizingContext noSlots = new SizingContext(10_000_000, 10_000_000, Map.of(), Map.of(), 1, 0);
        res = PositionSizer.size(in, noSlots, r);
        assertTrue(res.quantity() > 0);
        var opp = new BazaarAnalyzer(new SkyTradeConfig()).analyzeProduct(TestData.product("X", 100, 110, 700_000), List.of(), ItemCatalog.EMPTY, noSlots, 0);
        var d = new dev.skytrade.risk.RiskManager().validate(opp, noSlots, r, ItemCatalog.EMPTY, 0, 300_000);
        assertFalse(d.approved());
        assertTrue(d.reasons().stream().anyMatch(x -> x.contains("order slots")), d.reasons().toString());
    }

    private static ItemAttributes item(String id, boolean recomb, int hpb) {
        return new ItemAttributes(id, "LEGENDARY", null, Map.of(), null, 0, recomb, 0, 0, Map.of(), hpb, 0, List.of(), false,
                null, 0, null, 0, null, Map.of(), 1, id);
    }

    @Test
    void fairValueFromExactComparables() {
        SalesHistory h = new SalesHistory(100, 100);
        ItemAttributes a = item("SWORD", false, 0);
        String s = dev.skytrade.auction.Fingerprint.strict(a), r = dev.skytrade.auction.Fingerprint.relaxed(a), b = dev.skytrade.auction.Fingerprint.base(a);
        long now = 10 * 86_400_000L;
        long[] prices = {1_000_000, 1_050_000, 980_000, 1_020_000, 1_010_000, 50_000_000};
        for (int i = 0; i < prices.length; i++) h.add(new SaleRecord("u" + i, now - i * 3_600_000L, prices[i], true, "SWORD", b, r, s, List.of()));
        // A future sale must never influence the valuation (look-ahead protection).
        h.add(new SaleRecord("future", now + 3_600_000L, 9_000_000, true, "SWORD", b, r, s, List.of()));
        FairValueEstimator est = new FairValueEstimator(h, id -> Double.NaN);
        var params = new FairValueEstimator.Params(5, 0.5, 0.01, 7 * 86_400_000L, 2 * 86_400_000L);
        Valuation v = est.estimate(a, List.of(), params, now);
        assertTrue(v.valid(), String.join("\n", v.explanation()));
        assertEquals("EXACT", v.matchLevel());
        assertEquals(5, v.comparables(), "the 50M outlier is dropped");
        assertTrue(v.fairValue() > 980_000 && v.fairValue() < 1_060_000, "fair " + v.fairValue());
        Valuation capped = est.estimate(a, List.of(900_000L), params, now);
        assertEquals(900_000 * 0.99, capped.salePrice(), 1e-6, "cheaper competing BIN caps the sale price");
        assertFalse(est.estimate(item("OTHER", false, 0), List.of(), params, now).valid(), "rejected without comparables");
    }

    @Test
    void upgradesCreditedAtRecoveryRateNotFullCost() {
        SalesHistory h = new SalesHistory(100, 100);
        long now = 10 * 86_400_000L;
        ItemAttributes clean = item("SWORD", false, 0);
        for (int i = 0; i < 6; i++) {
            h.add(new SaleRecord("c" + i, now - i * 3_600_000L, 1_000_000, true, "SWORD", dev.skytrade.auction.Fingerprint.base(clean),
                    dev.skytrade.auction.Fingerprint.relaxed(clean), dev.skytrade.auction.Fingerprint.strict(clean), List.of()));
        }
        ItemAttributes upgraded = item("SWORD", false, 10);
        PriceSource prices = id -> id.equals("HOT_POTATO_BOOK") ? 100_000 : Double.NaN;
        Valuation v = new FairValueEstimator(h, prices).estimate(upgraded, List.of(), new FairValueEstimator.Params(5, 0.5, 0.01, 7 * 86_400_000L, 86_400_000L), now);
        assertTrue(v.valid());
        assertEquals("SIMILAR", v.matchLevel());
        assertEquals(1_000_000 + 10 * 100_000 * 0.5, v.fairValue(), 1e-6);
    }

    @Test
    void recipesParseAndCostRecursively() throws Exception {
        var json = com.google.gson.JsonParser.parseString("""
                {"internalname":"ENCHANTED_DIAMOND_BLOCK","recipe":{"A1":"ENCHANTED_DIAMOND:32","A2":"ENCHANTED_DIAMOND:32","A3":"ENCHANTED_DIAMOND:32",
                 "B1":"ENCHANTED_DIAMOND:32","B2":"ENCHANTED_DIAMOND:32","B3":"","C1":"","C2":"","C3":""}}""").getAsJsonObject();
        Method m = RecipeRepository.class.getDeclaredMethod("parseNeuItem", com.google.gson.JsonObject.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") List<Recipe> rs = (List<Recipe>) m.invoke(null, json);
        assertEquals(1, rs.size());
        assertEquals(160, rs.get(0).inputs().get("ENCHANTED_DIAMOND"));

        RecipeRepository repo = new RecipeRepository();
        repo.replaceAll(Map.of(
                "B", List.of(new Recipe("B", 1, Map.of("A", 10), "test")),
                "C", List.of(new Recipe("C", 2, Map.of("B", 2, "A", 1), "test")),
                "A", List.of(new Recipe("A", 1, Map.of("C", 1), "test")))); // cycle back to A
        CraftCostCalculator calc = new CraftCostCalculator(repo, new CraftCostCalculator.Market() {
            public double buyPrice(String id) { return id.equals("A") ? 5 : id.equals("B") ? 100 : Double.NaN; }
            public String buyMethod(String id) { return "test"; }
        }, 5);
        assertEquals(50, calc.unitCost("B").unitCost(), 1e-9, "crafting B from 10 A (50) beats buying (100)");
        assertEquals((2 * 50 + 5) / 2.0, calc.craftCost(repo.forOutput("C").get(0)).unitCost(), 1e-9);
        assertEquals(5, calc.unitCost("A").unitCost(), 1e-9, "cycle does not break pricing");
    }
}
