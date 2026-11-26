package dev.skytrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skytrade.auction.AuctionAnalyzer;
import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.AuctionIndex;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.DealsService;
import dev.skytrade.external.ExternalDeal;
import dev.skytrade.external.ExternalPriceService;
import dev.skytrade.external.PriceHistoryParser;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.valuation.FairValueEstimator;
import dev.skytrade.valuation.Valuation;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real (trimmed) Coflnet API responses captured on 2026-11-26. */
class CoflnetTest {
    private static final JsonObject SAMPLES;

    static {
        try (var in = new InputStreamReader(Objects.requireNonNull(CoflnetTest.class.getResourceAsStream("/coflnet_samples.json")), StandardCharsets.UTF_8)) {
            SAMPLES = JsonParser.parseReader(in).getAsJsonObject();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void realHistoryParses() {
        var buckets = PriceHistoryParser.parse(SAMPLES.get("history_hyperion"));
        assertTrue(buckets.size() > 10, "hourly buckets: " + buckets.size());
        PriceHistoryStats s = PriceHistoryParser.summarize("HYPERION", buckets, 0);
        assertTrue(s.usable());
        assertTrue(s.volume() >= buckets.size());
        assertTrue(s.low() <= s.median() && s.median() <= s.high(), s.toString());
        assertTrue(s.median() > 100_000_000, "a Hyperion is worth hundreds of millions: " + s.median());
        assertTrue(PriceHistoryParser.summarize("PET", PriceHistoryParser.parse(SAMPLES.get("history_edrag")), 0).usable());
    }

    @Test
    void neuPricesMapPetsToRarity() {
        Map<String, Double> m = ExternalPriceService.parseNeuPrices(SAMPLES.getAsJsonObject("neu"));
        assertTrue(m.get("HYPERION") > 100_000_000);
        assertTrue(m.containsKey("PET_ENDER_DRAGON|LEGENDARY"));
        assertTrue(m.containsKey("PET_ENDER_DRAGON|EPIC"));
        assertFalse(m.keySet().stream().anyMatch(k -> k.contains(";")), "no raw NEU pet keys left");
    }

    private static List<ExternalDeal> call(String method, Object... args) throws Exception {
        for (Method m : DealsService.class.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                m.setAccessible(true);
                @SuppressWarnings("unchecked") List<ExternalDeal> r = (List<ExternalDeal>) m.invoke(null, args);
                return r;
            }
        }
        throw new NoSuchMethodException(method);
    }

    @Test
    void bazaarSpreadIsRecheckedAgainstLiveBook() throws Exception {
        JsonArray spread = SAMPLES.getAsJsonArray("spread");
        List<BazaarProduct> products = new ArrayList<>();
        String good = null, manipulated = null;
        for (JsonElement e : spread) {
            JsonObject o = e.getAsJsonObject();
            JsonObject f = o.getAsJsonObject("flip");
            String tag = f.get("itemTag").getAsString();
            // Live book with a wide spread: profitable for everything that is not flagged.
            products.add(TestData.product(tag, 1_000_000, 1_500_000, 700_000));
            double coflHours = (num(f, "estimatedBuyFillSeconds") + num(f, "estimatedSellFillSeconds")) / 3600;
            if (o.get("isManipulated").getAsBoolean()) manipulated = tag;
            else if (good == null && coflHours > 0 && coflHours < 12) good = tag; // fillable within the holding limit
        }
        assertNotNull(manipulated, "sample contains manipulated flips");
        var bz = TestData.snapshot(0, products.toArray(new BazaarProduct[0]));
        List<ExternalDeal> deals = call("bazaarSpread", spread, bz, ItemCatalog.EMPTY, new SkyTradeConfig());
        String m = manipulated;
        assertTrue(deals.stream().noneMatch(d -> d.itemId().equals(m)), "manipulated flips are dropped");
        String g = good;
        ExternalDeal d = deals.stream().filter(x -> x.itemId().equals(g)).findFirst().orElseThrow();
        double expected = (1_500_000 - 0.1) * (1 - new SkyTradeConfig().fees.bazaarTax) - (1_000_000 + 0.1);
        assertEquals(expected, d.netProfit(), 1e-6, "numbers come from the live book, not Coflnet");

        // A live book with no spread left: the deal disappears.
        var tight = TestData.snapshot(0, TestData.product(g, 1_000_000, 1_000_000.5, 700_000));
        assertTrue(call("bazaarSpread", spread, tight, ItemCatalog.EMPTY, new SkyTradeConfig()).isEmpty());

        // A dead book (huge spread, a few sales a week) is dropped however large the "profit" looks.
        var dead = TestData.snapshot(0, TestData.product(g, 2_500_000, 100_000_000, 4));
        assertTrue(call("bazaarSpread", spread, dead, ItemCatalog.EMPTY, new SkyTradeConfig()).isEmpty(), "dead books are bait");
        var slow = TestData.snapshot(0, TestData.product(g, 1_000_000, 1_500_000, 20));
        assertTrue(call("bazaarSpread", spread, slow, ItemCatalog.EMPTY, new SkyTradeConfig()).isEmpty(), "unfillable within 3x the holding limit");
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
    }

    @Test
    void craftsUseConservativeSaleAndSkipUnprofitable() throws Exception {
        SkyTradeConfig cfg = new SkyTradeConfig();
        List<ExternalDeal> deals = call("crafts", SAMPLES.getAsJsonArray("craft"), null, ItemCatalog.EMPTY, cfg);
        assertFalse(deals.isEmpty());
        for (ExternalDeal d : deals) {
            assertTrue(d.netProfit() > 0);
            assertTrue(d.command().startsWith("/viewrecipe "));
        }
        // Sale is the lower of sellPrice and median, after AH fees.
        JsonObject first = null;
        for (JsonElement e : SAMPLES.getAsJsonArray("craft")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("itemId").getAsString().equals(deals.get(0).itemId())) first = o;
        }
        assertNotNull(first);
        double ref = Math.min(first.get("sellPrice").getAsDouble(), first.get("median").getAsDouble());
        assertEquals(AuctionFees.netProceeds(ref, cfg.fees), deals.get(0).sale(), 1e-6);
    }

    @Test
    void katDealsNeedTheListingToBeLive() throws Exception {
        assertTrue(call("kat", SAMPLES.getAsJsonArray("kat"), null, AuctionIndex.EMPTY, new SkyTradeConfig()).isEmpty(),
                "without our own AH scan nothing can be verified");
    }

    private static ItemAttributes sword() {
        return new ItemAttributes("SWORD", "LEGENDARY", null, Map.of(), null, 0, false, 0, 0, Map.of(), 0, 0, List.of(), false,
                null, 0, null, 0, null, Map.of(), 1, "Sword");
    }

    @Test
    void marketFallbackValuesFromListingsCappedByHistory() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        AuctionAnalyzer an = new AuctionAnalyzer(cfg);
        var p = new FairValueEstimator.Params(5, 0.55, 0.01, 7 * 86_400_000L, 2 * 86_400_000L);
        PriceHistoryStats h = new PriceHistoryStats("SWORD", 9_000_000, 9_200_000, 8_000_000, 12_000_000, 60, 0, 40, 0);
        Valuation v = an.marketValuation(sword(), List.of(10_000_000L, 10_500_000L), h, p);
        assertNotNull(v);
        assertEquals("MARKET", v.matchLevel());
        assertEquals(10_000_000 * 0.99, v.salePrice(), 1e-6);
        assertTrue(v.confidence() <= 0.6);

        // Competing listings far above what the item sold for: capped by the 7-day median.
        PriceHistoryStats cheap = new PriceHistoryStats("SWORD", 5_000_000, 5_000_000, 4_000_000, 12_000_000, 60, 0, 40, 0);
        assertEquals(5_000_000 * 1.25, an.marketValuation(sword(), List.of(10_000_000L, 10_500_000L), cheap, p).salePrice(), 1e-6);

        assertNull(an.marketValuation(sword(), List.of(10_000_000L, 10_500_000L), null, p), "never without history");
        assertNull(an.marketValuation(sword(), List.of(10_000_000L), h, p), "needs two competing listings");
        PriceHistoryStats illiquid = new PriceHistoryStats("SWORD", 9_000_000, 9_000_000, 8_000_000, 12_000_000, 3, 0, 40, 0);
        assertNull(an.marketValuation(sword(), List.of(10_000_000L, 10_500_000L), illiquid, p));
    }
}
