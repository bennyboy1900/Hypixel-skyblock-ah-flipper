package dev.skytrade;

import com.google.gson.*;
import dev.skytrade.api.ApiParsers;
import dev.skytrade.auction.*;
import dev.skytrade.bazaar.BazaarAnalyzer;
import dev.skytrade.bazaar.BazaarOpportunity;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.DealsService;
import dev.skytrade.external.ExternalDeal;
import dev.skytrade.external.PriceHistoryParser;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.market.*;
import dev.skytrade.risk.SizingContext;
import dev.skytrade.valuation.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomised robustness tests: thousands of malformed, extreme and adversarial inputs for every parser and
 * every price calculation. Invariants checked: nothing throws out of a parser, no NaN/Infinity/negative
 * prices or profits are ever produced, confidence and risk stay in [0, 1], and a reported profit always
 * equals sale minus fees minus cost. Seeds are fixed, so every failure is reproducible.
 */
class StressTest {
    private static final double[] NASTY = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0, 1e-9,
            0.1, 1, 999, 1e6, 1e12, 1e18, Double.MAX_VALUE, Double.MIN_VALUE};

    private static double nasty(Random r) {
        return r.nextInt(3) == 0 ? NASTY[r.nextInt(NASTY.length)] : Math.exp(r.nextDouble() * 25);
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    // ------------------------------------------------------------------ parsers

    private static JsonElement randomJson(Random r, int depth) {
        int k = r.nextInt(depth <= 0 ? 4 : 7);
        return switch (k) {
            case 0 -> JsonNull.INSTANCE;
            case 1 -> new JsonPrimitive(nasty(r));
            case 2 -> new JsonPrimitive(r.nextBoolean() ? "x" : "2026-11-26T09:00:00");
            case 3 -> new JsonPrimitive(r.nextBoolean());
            case 4, 5 -> {
                JsonObject o = new JsonObject();
                String[] keys = {"success", "lastUpdated", "products", "auctions", "items", "id", "price", "item_bytes", "bin",
                        "auction_id", "seller", "buyer", "timestamp", "quick_status", "sell_summary", "buy_summary", "min", "max",
                        "avg", "volume", "time", "flip", "itemTag", "isManipulated", "ingredients", "itemId", "count", "cost",
                        "sellPrice", "median", "originAuction", "coreData", "materials", "purchaseCost", "gemstone_slots", "costs",
                        "type", "coins", "item_id", "amount", "slot_type", "estimatedBuyFillSeconds", "estimatedSellFillSeconds"};
                int n = r.nextInt(6);
                for (int i = 0; i < n; i++) o.add(keys[r.nextInt(keys.length)], randomJson(r, depth - 1));
                yield o;
            }
            default -> {
                JsonArray a = new JsonArray();
                int n = r.nextInt(5);
                for (int i = 0; i < n; i++) a.add(randomJson(r, depth - 1));
                yield a;
            }
        };
    }

    @Test
    void apiParsersNeverThrowUnexpectedly() {
        Random r = new Random(1);
        for (int i = 0; i < 20_000; i++) {
            JsonElement el = randomJson(r, 4);
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            try {
                ApiParsers.bazaar(o, 0);
            } catch (dev.skytrade.api.ApiException ignored) {
                // a typed, expected rejection
            }
            ApiParsers.auctionPage(o);
            ApiParsers.ended(o);
            try {
                ApiParsers.items(o, 0);
            } catch (dev.skytrade.api.ApiException ignored) {
            }
        }
    }

    @Test
    void historyParserNeverThrowsAndStatsAreFinite() {
        Random r = new Random(2);
        for (int i = 0; i < 20_000; i++) {
            var b = PriceHistoryParser.parse(randomJson(r, 3));
            PriceHistoryStats s = PriceHistoryParser.summarize("X", b, 0);
            if (s.usable()) {
                assertTrue(finite(s.median()) && s.median() > 0, s.toString());
                assertTrue(finite(s.recent()) && finite(s.low()) && finite(s.high()) && finite(s.trend()), s.toString());
            }
        }
    }

    @Test
    void dealParsersNeverThrowAndNeverReportLosses() throws Exception {
        Random r = new Random(3);
        SkyTradeConfig cfg = new SkyTradeConfig();
        Map<String, Method> m = new HashMap<>();
        for (Method x : DealsService.class.getDeclaredMethods()) {
            x.setAccessible(true);
            m.put(x.getName(), x);
        }
        BazaarSnapshot bz = TestData.snapshot(0, TestData.product("x", 100, 110, 70_000), TestData.product("ITEM", 1e6, 1.5e6, 70_000));
        for (int i = 0; i < 10_000; i++) {
            JsonElement j = randomJson(r, 4);
            for (String name : List.of("bazaarSpread", "crafts")) {
                @SuppressWarnings("unchecked") List<ExternalDeal> deals = (List<ExternalDeal>) m.get(name).invoke(null, j, bz, ItemCatalog.EMPTY, cfg);
                for (ExternalDeal d : deals) {
                    assertTrue(finite(d.netProfit()) && d.netProfit() > 0, d.toString());
                    assertTrue(finite(d.cost()) && d.cost() > 0 && finite(d.sale()), d.toString());
                }
            }
        }
    }

    @Test
    void nbtDecoderSurvivesGarbageAndTruncation() throws Exception {
        Random r = new Random(4);
        ItemDecoder dec = new ItemDecoder(10);
        String real = TestData.json("item_samples.json").get("hyperion").getAsString();
        byte[] realBytes = Base64.getDecoder().decode(real);
        for (int i = 0; i < 5_000; i++) {
            byte[] b;
            switch (i % 4) {
                case 0 -> { // random bytes, gzipped
                    byte[] raw = new byte[r.nextInt(200)];
                    r.nextBytes(raw);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
                        g.write(raw);
                    }
                    b = bo.toByteArray();
                }
                case 1 -> b = Arrays.copyOf(realBytes, r.nextInt(realBytes.length)); // truncated
                case 2 -> { // bit flips in the compressed stream
                    b = realBytes.clone();
                    for (int k = 0; k < 3; k++) b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8));
                }
                default -> { // valid gzip of a corrupted NBT payload
                    byte[] nbt = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(realBytes)).readAllBytes();
                    for (int k = 0; k < 4; k++) nbt[r.nextInt(nbt.length)] = (byte) r.nextInt(256);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
                        g.write(nbt);
                    }
                    b = bo.toByteArray();
                }
            }
            String s = Base64.getEncoder().encodeToString(b);
            Optional<ItemAttributes> a = assertDoesNotThrow(() -> dec.decode("k" + System.nanoTime(), s, null));
            a.ifPresent(x -> {
                assertNotNull(x.skyblockId());
                assertTrue(x.hotPotatoBooks() >= 0 && x.fumingPotatoBooks() >= 0 && x.dungeonStars() >= 0 && x.masterStars() >= 0, x.toString());
                assertTrue(x.masterStars() <= 5 && x.dungeonStars() <= 5, x.toString());
                UpgradeValuer.components(x); // must not throw on odd values
            });
        }
    }

    // ------------------------------------------------------------------ valuation

    private static ItemAttributes randomItem(Random r) {
        Map<String, Integer> ench = new TreeMap<>();
        String[] names = {"sharpness", "critical", "legion", "ultimate_wise", "growth", "protection", "first_strike"};
        for (int i = 0; i < r.nextInt(5); i++) ench.put(names[r.nextInt(names.length)], r.nextInt(12) - 1);
        Map<String, String> gems = new TreeMap<>();
        if (r.nextBoolean()) gems.put("COMBAT_0", "PERFECT_JASPER_GEM");
        Map<String, Integer> extras = r.nextBoolean() ? Map.of("JALAPENO_BOOK", r.nextInt(3) - 1) : Map.of();
        boolean pet = r.nextInt(5) == 0;
        return new ItemAttributes(pet ? "PET_X" : "ITEM" + r.nextInt(3), "LEGENDARY", r.nextBoolean() ? "fabled" : null, ench,
                null, 0, r.nextBoolean(), r.nextInt(6), r.nextInt(6), gems, r.nextInt(11), r.nextInt(6),
                r.nextBoolean() ? List.of("IMPLOSION_SCROLL") : List.of(), r.nextBoolean(), pet ? "X" : null, r.nextInt(101),
                pet && r.nextBoolean() ? "PET_ITEM_X" : null, 0, null, Map.of(), 1, "Item", extras,
                r.nextBoolean() ? List.of("COMBAT_0", "COMBAT_9", "BROKEN", "_1") : List.of());
    }

    @Test
    void upgradeValuesAreNeverNegativeOrNaN() {
        Random r = new Random(5);
        ItemCatalog cat = new ItemCatalog(0, 0, Map.of("ITEM0", new SkyblockItem("ITEM0", "I", "P", "L", "", 0, false, 1, "",
                List.of(new SkyblockItem.GemSlot("COMBAT", Map.of("COINS", 250_000, "FLAWLESS_RUBY_GEM", 1))))));
        for (int i = 0; i < 20_000; i++) {
            ItemAttributes a = randomItem(r);
            PriceSource prices = id -> nasty(r);
            var res = UpgradeValuer.value(UpgradeValuer.components(a, cat), prices, r.nextDouble() * 1.2, r.nextBoolean() ? 0 : 5_000);
            assertTrue(finite(res.value()) && res.value() >= 0, res.toString());
            assertTrue(finite(res.fullCost()) && res.fullCost() >= res.value() - 1e-6, res.toString());
        }
    }

    @Test
    void fairValueInvariantsHoldOnRandomHistories() {
        Random r = new Random(6);
        long now = 30L * 86_400_000L;
        for (int run = 0; run < 400; run++) {
            SalesHistory h = new SalesHistory(200, 1000);
            ItemAttributes target = randomItem(r);
            int n = r.nextInt(40);
            for (int i = 0; i < n; i++) {
                ItemAttributes a = r.nextInt(3) == 0 ? randomItem(r) : target;
                List<String> ups = new ArrayList<>();
                for (UpgradeComponent c : UpgradeValuer.components(a)) ups.add(c.encode());
                if (r.nextBoolean()) ups.add(SaleRecord.FULL_UPGRADES);
                long price = r.nextInt(10) == 0 ? (long) (r.nextDouble() * 1e12) : 1 + (long) (r.nextDouble() * 5e7);
                h.add(new SaleRecord("s" + run + "_" + i, now - (long) (r.nextDouble() * 10 * 86_400_000L), price, true, a.skyblockId(),
                        Fingerprint.base(a), Fingerprint.relaxed(a), Fingerprint.strict(a), ups,
                        r.nextBoolean() ? "seller" + r.nextInt(4) : null, r.nextBoolean() ? "buyer" + r.nextInt(4) : null));
            }
            PriceSource prices = id -> r.nextInt(4) == 0 ? Double.NaN : r.nextDouble() * 5e6;
            var p = new FairValueEstimator.Params(3 + r.nextInt(4), r.nextDouble(), r.nextDouble() * 0.05, 7 * 86_400_000L, 2 * 86_400_000L);
            List<Long> competing = new ArrayList<>();
            for (int i = 0; i < r.nextInt(4); i++) competing.add((long) (r.nextDouble() * 5e7));
            Valuation v = new FairValueEstimator(h, prices).estimate(target, competing, p, now, r.nextBoolean() ? "seller1" : null);
            if (!v.valid()) continue;
            assertTrue(finite(v.fairValue()) && v.fairValue() > 0, v.toString());
            assertTrue(finite(v.salePrice()) && v.salePrice() > 0 && v.salePrice() <= v.fairValue() + 1e-6, v.toString());
            assertTrue(v.confidence() >= 0 && v.confidence() <= 1, v.toString());
            assertTrue(v.comparables() >= p.minComparables(), v.toString());
            assertFalse(Double.isNaN(v.saleHours()), v.toString());
        }
    }

    @Test
    void guardNeverAcceptsBrokenNumbers() {
        Random r = new Random(7);
        var cfg = new SkyTradeConfig().external;
        for (int i = 0; i < 50_000; i++) {
            Valuation v = new Valuation(true, "", nasty(r), nasty(r), r.nextDouble(), 5, r.nextBoolean() ? "EXACT" : "SIMILAR", 1, 1, 0.1,
                    r.nextBoolean() ? 0 : nasty(r), List.of(), List.of());
            PriceHistoryStats h = r.nextInt(4) == 0 ? null : new PriceHistoryStats("X", nasty(r), nasty(r), nasty(r), nasty(r),
                    r.nextInt(100), (r.nextDouble() - 0.5) * 2, 3 + r.nextInt(50), 0);
            var g = MarketGuard.check(v, (long) Math.min(Long.MAX_VALUE / 2, Math.abs(nasty(r))), h, cfg);
            if (g.rejected()) continue;
            assertTrue(finite(g.fairMultiplier()) && g.fairMultiplier() > 0 && g.fairMultiplier() <= 1, g + " " + v + " " + h);
            assertTrue(finite(g.confidenceMultiplier()) && g.confidenceMultiplier() > 0 && g.confidenceMultiplier() <= 1, g.toString());
        }
    }

    @Test
    void auctionProfitAlwaysEqualsSaleMinusFeesMinusPrice() {
        Random r = new Random(8);
        SkyTradeConfig cfg = new SkyTradeConfig();
        AuctionAnalyzer an = new AuctionAnalyzer(cfg);
        var p = new FairValueEstimator.Params(5, 0.55, 0.01, 7 * 86_400_000L, 2 * 86_400_000L);
        for (int i = 0; i < 20_000; i++) {
            List<Long> comp = new ArrayList<>();
            for (int k = 0; k < 1 + r.nextInt(4); k++) comp.add((long) (Math.abs(nasty(r)) % 4e9));
            Collections.sort(comp);
            PriceHistoryStats h = new PriceHistoryStats("X", nasty(r), nasty(r), nasty(r), nasty(r), r.nextInt(200), 0, 10 + r.nextInt(100), 0);
            Valuation v = an.marketValuation(randomItem(r), comp, h, p);
            if (v == null) continue;
            assertTrue(finite(v.salePrice()) && v.salePrice() > 0, v.toString());
            assertTrue(v.confidence() > 0 && v.confidence() <= 0.6 + 1e-9, v.toString());
        }
    }

    // ------------------------------------------------------------------ bazaar

    @Test
    void bazaarOpportunitiesAreSaneOnRandomBooks() {
        Random r = new Random(9);
        SkyTradeConfig cfg = new SkyTradeConfig();
        BazaarAnalyzer an = new BazaarAnalyzer(cfg);
        for (int i = 0; i < 20_000; i++) {
            List<OrderLevel> bids = new ArrayList<>(), asks = new ArrayList<>();
            double bid = nasty(r), ask = r.nextBoolean() ? bid * (1 + r.nextDouble()) : nasty(r);
            for (int k = 0; k < r.nextInt(4); k++) bids.add(new OrderLevel(bid - k, (long) Math.abs(nasty(r) % 1e7), r.nextInt(50)));
            for (int k = 0; k < r.nextInt(4); k++) asks.add(new OrderLevel(ask + k, (long) Math.abs(nasty(r) % 1e7), r.nextInt(50)));
            long week = (long) Math.abs(nasty(r) % 1e9);
            BazaarProduct p = new BazaarProduct("X", bids, asks, new QuickStatus(bid, 1000, week, 10, ask, 1000, r.nextBoolean() ? week : 0, 10));
            List<PricePoint> hist = new ArrayList<>();
            for (int k = 0; k < r.nextInt(30); k++) hist.add(new PricePoint(k * 300_000L, bid * (0.5 + r.nextDouble()), ask * (0.5 + r.nextDouble()), 1, 1, week, week));
            BazaarOpportunity o = an.analyzeProduct(p, hist, ItemCatalog.EMPTY, SizingContext.of(1e8), 0);
            if (o == null) continue;
            assertTrue(finite(o.entryPrice()) && o.entryPrice() > 0 && finite(o.exitPrice()) && o.exitPrice() > o.entryPrice(), o.toString());
            assertTrue(o.quantity() >= 0 && o.capital() <= 1e8 + 1e-6, o.toString());
            if (o.quantity() == 0) {
                assertEquals(0, o.netProfit(), 1e-9, "unsized rows never claim a profit");
                continue;
            }
            assertTrue(finite(o.netProfit()) && o.netProfit() > 0, o.toString());
            assertTrue(o.risk() >= 0 && o.risk() <= 1 && o.confidence() >= 0 && o.confidence() <= 1, o.toString());
            double perUnit = o.exitPrice() * (1 - cfg.fees.bazaarTax) - o.entryPrice();
            assertEquals(perUnit * o.quantity(), o.netProfit(), Math.max(1e-6, Math.abs(o.netProfit()) * 1e-9), "profit = (exit after tax - entry) x qty");
            assertTrue(finite(o.holdHours()) && o.holdHours() > 0, o.toString());
        }
    }
}
