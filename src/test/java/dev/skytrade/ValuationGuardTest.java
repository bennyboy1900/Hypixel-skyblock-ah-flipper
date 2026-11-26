package dev.skytrade;

import com.google.gson.JsonParser;
import dev.skytrade.api.ApiParsers;
import dev.skytrade.auction.Fingerprint;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.auction.MarketGuard;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.external.ExternalPriceService;
import dev.skytrade.external.PriceHistoryParser;
import dev.skytrade.external.PriceHistoryStats;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.market.SkyblockItem;
import dev.skytrade.notifications.NotificationService;
import dev.skytrade.strategy.Opportunity;
import dev.skytrade.valuation.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ValuationGuardTest {
    private static final long DAY = 86_400_000L;

    private static ItemAttributes item(String id, Map<String, Integer> ench, Map<String, Integer> extras, List<String> slots) {
        return new ItemAttributes(id, "LEGENDARY", null, ench, null, 0, false, 0, 0, Map.of(), 0, 0, List.of(), false,
                null, 0, null, 0, null, Map.of(), 1, id, extras, slots);
    }

    private static SaleRecord sale(String uuid, long ts, long price, ItemAttributes a, String seller, String buyer, List<String> ups) {
        return new SaleRecord(uuid, ts, price, true, a.skyblockId(), Fingerprint.base(a), Fingerprint.relaxed(a),
                Fingerprint.strict(a), ups, seller, buyer);
    }

    @Test
    void enchantsPricedByLevelDerivationAndCheapOnesIgnored() {
        ItemAttributes a = item("SWORD", Map.of("sharpness", 7, "telekinesis", 1), Map.of(), List.of());
        List<UpgradeComponent> comps = UpgradeValuer.components(a);
        PriceSource prices = id -> switch (id) {
            case "ENCHANTMENT_SHARPNESS_6" -> 2_000_000;
            case "ENCHANTMENT_TELEKINESIS_1" -> 100;
            default -> Double.NaN;
        };
        UpgradeValuer.Result r = UpgradeValuer.value(comps, prices, 0.55);
        assertEquals(1, r.priced(), r.lines().toString());
        // Sharpness 7 = two Sharpness 6 books, with the 20% derivation haircut, at the enchant recovery rate.
        double cost = 2_000_000 * 2 * 0.8;
        assertEquals(cost, r.fullCost(), 1e-6);
        assertEquals(cost * UpgradeValuer.recovery(UpgradeValuer.Kind.ENCHANT, 0.55), r.value(), 1e-6);
        assertEquals(0, r.unpriced(), "unknown cheap enchants are not reported as missing prices");
    }

    @Test
    void recoveryDependsOnUpgradeKind() {
        double base = 0.55;
        assertTrue(UpgradeValuer.recovery(UpgradeValuer.Kind.GEMSTONE, base) > UpgradeValuer.recovery(UpgradeValuer.Kind.HOT_POTATO, base));
        assertTrue(UpgradeValuer.recovery(UpgradeValuer.Kind.REFORGE_STONE, base) < base);
        assertTrue(UpgradeValuer.recovery(UpgradeValuer.Kind.SCROLL, 0.9) <= 0.95, "never above 95%");
        assertEquals(UpgradeValuer.Kind.SLOT_UNLOCK, UpgradeValuer.kind("UNLOCK:FLAWLESS_RUBY_GEM"));
        assertEquals(UpgradeValuer.Kind.GEMSTONE, UpgradeValuer.kind("FLAWLESS_RUBY_GEM"));
        assertEquals(UpgradeValuer.Kind.ULTIMATE_ENCHANT, UpgradeValuer.kind("ENCHANTMENT_ULTIMATE_WISE_5"));
        assertEquals(UpgradeValuer.Kind.MASTER_STAR, UpgradeValuer.kind("THIRD_MASTER_STAR"));
    }

    @Test
    void gemSlotUnlocksAndExtrasBecomeComponents() {
        var json = JsonParser.parseString("""
                {"success":true,"lastUpdated":1,"items":[{"id":"HYPERION","name":"Hyperion","gemstone_slots":[
                  {"slot_type":"SAPPHIRE","costs":[{"type":"COINS","coins":250000},{"type":"ITEM","item_id":"FLAWLESS_SAPPHIRE_GEM","amount":4}]},
                  {"slot_type":"COMBAT","costs":[{"type":"COINS","coins":250000},{"type":"ITEM","item_id":"FLAWLESS_JASPER_GEM","amount":1}]},
                  {"slot_type":"COMBAT","costs":"garbage"}]}]}""").getAsJsonObject();
        ItemCatalog cat;
        try {
            cat = ApiParsers.items(json, 0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        SkyblockItem info = cat.get("HYPERION");
        assertEquals(3, info.gemSlotCosts().size());
        assertTrue(info.gemSlotCosts().get(2).costs().isEmpty(), "malformed costs are tolerated");

        ItemAttributes a = item("HYPERION", Map.of(), Map.of("WOOD_SINGULARITY", 1), List.of("COMBAT_0"));
        List<UpgradeComponent> comps = UpgradeValuer.components(a, cat);
        assertTrue(comps.contains(new UpgradeComponent("UNLOCK:COINS", 250_000, "Gem slot unlock")), comps.toString());
        assertTrue(comps.contains(new UpgradeComponent("UNLOCK:FLAWLESS_JASPER_GEM", 1, "Gem slot unlock")), comps.toString());
        assertTrue(comps.stream().noneMatch(c -> c.itemId().contains("SAPPHIRE")), "only unlocked slots are counted");
        assertTrue(comps.stream().anyMatch(c -> c.itemId().equals("WOOD_SINGULARITY")));

        var r = UpgradeValuer.value(comps, id -> id.equals("FLAWLESS_JASPER_GEM") ? 2_000_000 : id.equals("WOOD_SINGULARITY") ? 5_000_000 : Double.NaN, 0.55);
        double unlockRate = UpgradeValuer.recovery(UpgradeValuer.Kind.SLOT_UNLOCK, 0.55);
        double otherRate = UpgradeValuer.recovery(UpgradeValuer.Kind.OTHER, 0.55);
        assertEquals((250_000 + 2_000_000) * unlockRate + 5_000_000 * otherRate, r.value(), 1e-6);
    }

    @Test
    void washTradesAndSelfDealingAreIgnored() {
        SalesHistory h = new SalesHistory(100, 100);
        ItemAttributes a = item("SWORD", Map.of(), Map.of(), List.of());
        long now = 10 * DAY;
        // Two accounts trade the sword back and forth at an inflated price.
        for (int i = 0; i < 8; i++) {
            boolean even = i % 2 == 0;
            h.add(sale("w" + i, now - i * 60_000L, 5_000_000, a, even ? "pumpA" : "pumpB", even ? "pumpB" : "pumpA", List.of()));
        }
        var params = new FairValueEstimator.Params(5, 0.55, 0.01, 7 * DAY, 2 * DAY);
        Valuation v = new FairValueEstimator(h, id -> Double.NaN).estimate(a, List.of(), params, now, "someoneElse");
        assertFalse(v.valid(), "only two real pairs remain: " + String.join("\n", v.explanation()));
        assertTrue(v.explanation().stream().anyMatch(l -> l.contains("wash trading")), v.explanation().toString());

        // Add real, independent sales: the valuation comes from those, not from the pumped ones.
        for (int i = 0; i < 6; i++) h.add(sale("r" + i, now - i * 3_600_000L, 1_000_000 + i * 10_000, a, "seller" + i, "buyer" + i, List.of()));
        v = new FairValueEstimator(h, id -> Double.NaN).estimate(a, List.of(), params, now, "someoneElse");
        assertTrue(v.valid(), String.join("\n", v.explanation()));
        assertTrue(v.fairValue() < 1_100_000, "pumped sales must not set the price: " + v.fairValue());

        // Sales the listing's own seller took part in never count.
        Valuation self = new FairValueEstimator(h, id -> Double.NaN).estimate(a, List.of(), params, now, "seller0");
        assertTrue(self.explanation().stream().anyMatch(l -> l.contains("this listing's seller")), self.explanation().toString());
    }

    @Test
    void exactComparablesAdjustedForSmallerUpgradeDifferences() {
        SalesHistory h = new SalesHistory(100, 100);
        ItemAttributes plain = item("SWORD", Map.of(), Map.of(), List.of());
        long now = 10 * DAY;
        List<String> withJalapeno = List.of("JALAPENO_BOOKx1", SaleRecord.FULL_UPGRADES);
        for (int i = 0; i < 6; i++) h.add(sale("e" + i, now - i * 3_600_000L, 3_000_000, plain, "s" + i, "b" + i, withJalapeno));
        PriceSource prices = id -> id.equals("JALAPENO_BOOK") ? 1_000_000 : Double.NaN;
        var params = new FairValueEstimator.Params(5, 0.55, 0.01, 7 * DAY, 2 * DAY);
        Valuation v = new FairValueEstimator(h, prices).estimate(plain, List.of(), params, now);
        assertTrue(v.valid());
        assertEquals("EXACT", v.matchLevel());
        double credit = 1_000_000 * UpgradeValuer.recovery(UpgradeValuer.Kind.OTHER, 0.55);
        assertEquals(3_000_000 - credit, v.fairValue(), 1e-6, "comparables had a Jalapeno Book this listing lacks");
    }

    private static List<PriceHistoryParser.Bucket> buckets(double... avgs) {
        List<PriceHistoryParser.Bucket> out = new ArrayList<>();
        for (int i = 0; i < avgs.length; i++) out.add(new PriceHistoryParser.Bucket(i * 6 * 3_600_000L, avgs[i] * 0.9, avgs[i] * 1.1, avgs[i], 5));
        return out;
    }

    @Test
    void historyParserIsTolerant() {
        var json = JsonParser.parseString("""
                [{"min":90,"max":110,"avg":100,"volume":4,"time":"2025-01-01T00:00:00"},
                 {"min":95,"max":120,"avg":105,"volume":6,"time":"2025-01-01T12:00:00.123"},
                 {"avg":"oops","time":"2025-01-02T00:00:00"},
                 {"min":1,"max":2,"avg":1.5,"volume":0,"time":"2025-01-02T00:00:00"},
                 {"min":80,"max":100,"avg":90,"volume":2,"time":1735776000000},
                 "garbage"]""");
        var b = PriceHistoryParser.parse(json);
        assertEquals(3, b.size(), "invalid buckets are skipped");
        assertTrue(b.get(0).time() < b.get(1).time());
        PriceHistoryStats s = PriceHistoryParser.summarize("X", b, 0);
        assertEquals(12, s.volume());
        assertTrue(s.median() >= 100 && s.median() <= 105, "volume-weighted median " + s.median());
        assertTrue(PriceHistoryParser.parse(JsonParser.parseString("{\"error\":true}")).isEmpty());

        PriceHistoryStats falling = PriceHistoryParser.summarize("X", buckets(100, 100, 100, 90, 80, 70, 70, 70, 70), 0);
        assertTrue(falling.trend() < -0.2, "trend " + falling.trend());
    }

    @Test
    void externalUrlSplitsPetRarity() {
        var cfg = new SkyTradeConfig().external;
        ItemAttributes pet = new ItemAttributes("PET_ENDER_DRAGON", "LEGENDARY", null, Map.of(), null, 0, false, 0, 0, Map.of(), 0, 0,
                List.of(), false, "ENDER_DRAGON", 100, null, 0, null, Map.of(), 1, "Ender Dragon");
        assertEquals("PET_ENDER_DRAGON|LEGENDARY", ExternalPriceService.key(pet));
        assertEquals("https://sky.coflnet.com/api/item/price/PET_ENDER_DRAGON/history/week?Rarity=LEGENDARY",
                ExternalPriceService.url("PET_ENDER_DRAGON|LEGENDARY", cfg));
        assertEquals("https://sky.coflnet.com/api/item/price/HYPERION/history/week", ExternalPriceService.url("HYPERION", cfg));
    }

    private static Valuation valuation(double fair, String level, double upgradeCredit) {
        return new Valuation(true, "", fair, fair, 0.8, 8, level, 5, 4, 0.05, upgradeCredit, List.of(), List.of());
    }

    private static PriceHistoryStats stats(double median, double recent, double low, double high, long volume, double trend) {
        return new PriceHistoryStats("X", median, recent, low, high, volume, trend, 20, 0);
    }

    @Test
    void guardCatchesManipulation() {
        var cfg = new SkyTradeConfig().external;
        // No history: allowed but unconfirmed and less confident.
        var r = MarketGuard.check(valuation(10_000_000, "EXACT", 0), 8_000_000, null, cfg);
        assertFalse(r.rejected());
        assertFalse(r.confirmed());
        assertTrue(r.confidenceMultiplier() < 1);
        // No history and a deep discount: rejected.
        assertTrue(MarketGuard.check(valuation(10_000_000, "EXACT", 0), 3_000_000, null, cfg).rejected());

        // Illiquid.
        assertTrue(MarketGuard.check(valuation(10_000_000, "EXACT", 0), 8_000_000, stats(10e6, 10e6, 9e6, 11e6, 3, 0), cfg).rejected());

        // Pump: last 24h at 1.6x the weekly median -> fair value scaled back to the median level.
        r = MarketGuard.check(valuation(16_000_000, "EXACT", 0), 12_000_000, stats(10e6, 16e6, 9e6, 17e6, 50, 0), cfg);
        assertFalse(r.rejected());
        assertEquals(10.0 / 16.0, r.fairMultiplier(), 1e-9);
        assertTrue(r.lines().stream().anyMatch(l -> l.contains("pumped")));

        // Comparables far above anything sold this week: rejected as manipulated.
        assertTrue(MarketGuard.check(valuation(40_000_000, "EXACT", 0), 30_000_000, stats(10e6, 10e6, 9e6, 12e6, 50, 0), cfg).rejected());

        // Too good to be true on a non-exact match.
        assertTrue(MarketGuard.check(valuation(10_000_000, "SIMILAR", 0), 4_000_000, stats(10e6, 10e6, 9e6, 11e6, 50, 0), cfg).rejected());
        // Exact match but below half of every recent sale: bait or different item.
        assertTrue(MarketGuard.check(valuation(10_000_000, "EXACT", 0), 3_000_000, stats(10e6, 10e6, 9e6, 11e6, 50, 0), cfg).rejected());

        // Normal confirmed flip.
        r = MarketGuard.check(valuation(10_000_000, "EXACT", 0), 8_500_000, stats(10e6, 10.2e6, 9e6, 11e6, 50, 0.02), cfg);
        assertFalse(r.rejected());
        assertTrue(r.confirmed());
        assertEquals(1, r.fairMultiplier(), 1e-9);

        // Falling market lowers fair value.
        r = MarketGuard.check(valuation(10_000_000, "EXACT", 0), 8_000_000, stats(10e6, 9e6, 8e6, 11e6, 50, -0.3), cfg);
        assertEquals(0.85, r.fairMultiplier(), 1e-9);
    }

    @Test
    void unconfirmedFlipsAreNotAlerted() {
        SkyTradeConfig cfg = new SkyTradeConfig();
        NotificationService n = new NotificationService(() -> cfg, () -> 1_000_000_000L);
        Opportunity unconfirmed = new FakeOpp("a", false);
        Opportunity confirmed = new FakeOpp("b", true);
        n.checkOpportunities(List.of(unconfirmed, confirmed));
        var first = n.poll();
        assertNotNull(first);
        assertTrue(first.message().startsWith("b"), first.message());
        assertEquals("/viewauction b", first.suggestCommand(), "alerts carry a click-to-type command");
        assertNull(n.poll());
    }

    private record FakeOpp(String id, boolean confirmed) implements Opportunity {
        @Override public Kind kind() { return Kind.AUCTION_FLIP; }
        @Override public String key() { return "AH:" + id; }
        @Override public String itemId() { return id; }
        @Override public String name() { return id; }
        @Override public double entryPrice() { return 5_000_000; }
        @Override public double exitPrice() { return 7_000_000; }
        @Override public long quantity() { return 1; }
        @Override public double grossProfit() { return 2_000_000; }
        @Override public double netProfit() { return 1_800_000; }
        @Override public double holdHours() { return 2; }
        @Override public double risk() { return 0.1; }
        @Override public double confidence() { return 0.9; }
        @Override public double liquidity() { return 1; }
        @Override public List<String> explanation() { return List.of(); }
        @Override public String navigationCommand() { return "/viewauction " + id; }
    }
}
