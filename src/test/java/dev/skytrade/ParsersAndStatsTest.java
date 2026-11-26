package dev.skytrade;

import com.google.gson.JsonParser;
import dev.skytrade.api.ApiParsers;
import dev.skytrade.api.Backoff;
import dev.skytrade.api.RateLimiter;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.util.Stats;
import dev.skytrade.util.TimeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParsersAndStatsTest {
    @Test
    void parsesRealBazaarSample() throws Exception {
        BazaarSnapshot s = ApiParsers.bazaar(TestData.json("bazaar_sample.json"), 0);
        assertTrue(s.lastUpdated() > 0);
        BazaarProduct p = s.get("ENCHANTED_DIAMOND");
        assertNotNull(p);
        for (int i = 1; i < p.bids().size(); i++) assertTrue(p.bids().get(i - 1).price() >= p.bids().get(i).price(), "bids best-first");
        for (int i = 1; i < p.asks().size(); i++) assertTrue(p.asks().get(i - 1).price() <= p.asks().get(i).price(), "asks best-first");
        assertTrue(p.bestAsk() >= p.bestBid());
    }

    @Test
    void toleratesMalformedFields() throws Exception {
        var json = JsonParser.parseString("""
                {"success":true,"lastUpdated":"123","products":{
                  "A":{"product_id":"A","sell_summary":[{"amount":"x","pricePerUnit":5},{"amount":3,"pricePerUnit":4.5,"orders":1}],
                       "buy_summary":null,"quick_status":{"sellPrice":"NaN"}},
                  "B":"garbage"}}""").getAsJsonObject();
        BazaarSnapshot s = ApiParsers.bazaar(json, 0);
        assertEquals(123, s.lastUpdated());
        assertEquals(1, s.get("A").bids().size(), "invalid level skipped");
        assertTrue(s.get("A").asks().isEmpty());
        assertNull(s.get("B"));
    }

    @Test
    void robustStatistics() {
        double[] v = {10, 11, 12, 13, 1000};
        assertEquals(12, Stats.median(v));
        assertTrue(Stats.mad(v) < 5, "MAD ignores the outlier");
        assertTrue(Stats.robustZ(1000, v) > 50);
        assertEquals(2, Stats.weightedMedian(new double[]{1, 2, 3}, new double[]{1, 5, 1}));
        assertEquals(0, Stats.logReturnVolatility(new double[]{5, 5, 5, 5}));
    }

    @Test
    void rateLimiterAndBackoff() {
        TimeSource.Manual t = new TimeSource.Manual(0);
        RateLimiter rl = new RateLimiter(60, t); // 1 per second, burst 10
        int granted = 0;
        for (int i = 0; i < 20; i++) if (rl.tryAcquire() == 0) granted++;
        assertEquals(10, granted);
        assertTrue(rl.tryAcquire() > 0);
        t.advance(1000);
        assertEquals(0, rl.tryAcquire());
        rl.blockUntil(10_000);
        assertTrue(rl.tryAcquire() >= 8_000);

        Backoff b = new Backoff(1000, 60_000);
        long d1 = b.failure(0), d2 = b.failure(0), d3 = b.failure(0);
        assertTrue(d1 >= 1000 && d2 >= 2000 && d3 >= 4000);
        assertFalse(b.ready(100));
        b.success();
        assertTrue(b.ready(0));
    }
}
