package dev.skytrade;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skytrade.market.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class TestData {
    private TestData() {}

    public static JsonObject json(String resource) {
        try (var in = TestData.class.getResourceAsStream("/" + resource)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A liquid synthetic product: bid 100, ask 110, deep book, 7000/week each side. */
    public static BazaarProduct product(String id, double bid, double ask, long weekly) {
        List<OrderLevel> bids = List.of(new OrderLevel(bid, 5000, 3), new OrderLevel(bid - 1, 20000, 10), new OrderLevel(bid - 3, 50000, 20));
        List<OrderLevel> asks = List.of(new OrderLevel(ask, 5000, 3), new OrderLevel(ask + 1, 20000, 10), new OrderLevel(ask + 3, 50000, 20));
        return new BazaarProduct(id, bids, asks, new QuickStatus(bid, 75000, weekly, 33, ask, 75000, weekly, 33));
    }

    public static BazaarSnapshot snapshot(long t, BazaarProduct... ps) {
        java.util.HashMap<String, BazaarProduct> m = new java.util.HashMap<>();
        for (BazaarProduct p : ps) m.put(p.id(), p);
        return new BazaarSnapshot(t, t, Map.copyOf(m));
    }
}
