package dev.skytrade.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skytrade.market.*;

import java.util.*;

/**
 * Converts raw JSON into immutable market models. Every accessor tolerates missing, null or
 * wrongly-typed fields; invalid records are skipped instead of failing the whole dataset.
 */
public final class ApiParsers {
    private ApiParsers() {}

    public static BazaarSnapshot bazaar(JsonObject root, long fetchedAt) throws ApiException {
        JsonObject products = obj(root, "products");
        if (products == null) throw new ApiException("bazaar: missing products", 200, true);
        Map<String, BazaarProduct> out = new HashMap<>(products.size() * 2);
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject p = e.getValue().getAsJsonObject();
            String id = str(p, "product_id", e.getKey());
            List<OrderLevel> bids = levels(arr(p, "sell_summary"));
            List<OrderLevel> asks = levels(arr(p, "buy_summary"));
            bids.sort((a, b) -> Double.compare(b.price(), a.price()));
            asks.sort(Comparator.comparingDouble(OrderLevel::price));
            JsonObject q = obj(p, "quick_status");
            QuickStatus qs = q == null ? QuickStatus.EMPTY : new QuickStatus(
                    num(q, "sellPrice"), lng(q, "sellVolume"), lng(q, "sellMovingWeek"), (int) lng(q, "sellOrders"),
                    num(q, "buyPrice"), lng(q, "buyVolume"), lng(q, "buyMovingWeek"), (int) lng(q, "buyOrders"));
            out.put(id, new BazaarProduct(id, List.copyOf(bids), List.copyOf(asks), qs));
        }
        return new BazaarSnapshot(lng(root, "lastUpdated"), fetchedAt, Collections.unmodifiableMap(out));
    }

    private static List<OrderLevel> levels(JsonArray a) {
        List<OrderLevel> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement el : a) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            double price = num(o, "pricePerUnit");
            long amount = lng(o, "amount");
            if (!(price > 0) || amount <= 0 || Double.isInfinite(price)) continue;
            out.add(new OrderLevel(price, amount, (int) Math.max(1, lng(o, "orders"))));
        }
        return out;
    }

    public record AuctionPage(int page, int totalPages, int totalAuctions, long lastUpdated, List<ActiveAuction> auctions) {}

    public static AuctionPage auctionPage(JsonObject root) {
        List<ActiveAuction> list = new ArrayList<>();
        JsonArray arr = arr(root, "auctions");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject a = el.getAsJsonObject();
                String uuid = str(a, "uuid", null);
                String bytes = str(a, "item_bytes", null);
                if (uuid == null || uuid.isEmpty() || bytes == null) continue;
                if (bool(a, "claimed")) continue;
                list.add(new ActiveAuction(uuid, str(a, "auctioneer", ""), str(a, "item_name", "?"),
                        str(a, "tier", "COMMON"), str(a, "category", ""), lng(a, "starting_bid"),
                        lng(a, "highest_bid_amount"), bool(a, "bin"), lng(a, "start"), lng(a, "end"),
                        bytes, ""));
            }
        }
        return new AuctionPage((int) lng(root, "page"), (int) lng(root, "totalPages"), (int) lng(root, "totalAuctions"),
                lng(root, "lastUpdated"), list);
    }

    public static List<EndedAuction> ended(JsonObject root) {
        List<EndedAuction> out = new ArrayList<>();
        JsonArray arr = arr(root, "auctions");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String id = str(a, "auction_id", null);
            long price = lng(a, "price");
            String bytes = str(a, "item_bytes", null);
            if (id == null || price <= 0 || bytes == null) continue;
            out.add(new EndedAuction(id, price, bool(a, "bin"), lng(a, "timestamp"), bytes, str(a, "seller", null), str(a, "buyer", null)));
        }
        return out;
    }

    public static ItemCatalog items(JsonObject root, long fetchedAt) throws ApiException {
        JsonArray arr = arr(root, "items");
        if (arr == null) throw new ApiException("items: missing items", 200, true);
        Map<String, SkyblockItem> out = new HashMap<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject i = el.getAsJsonObject();
            String id = str(i, "id", null);
            if (id == null) continue;
            JsonArray gems = arr(i, "gemstone_slots");
            out.put(id, new SkyblockItem(id, str(i, "name", id), str(i, "material", "PAPER"), str(i, "tier", "COMMON"),
                    str(i, "category", ""), num(i, "npc_sell_price"), bool(i, "dungeon_item"),
                    gems == null ? 0 : gems.size(), str(i, "skin", ""), gemSlots(gems)));
        }
        return new ItemCatalog(lng(root, "lastUpdated"), fetchedAt, Collections.unmodifiableMap(out));
    }

    /** Parses {@code gemstone_slots[].costs} (COINS or ITEM entries). Malformed entries are skipped. */
    static List<SkyblockItem.GemSlot> gemSlots(JsonArray gems) {
        if (gems == null) return List.of();
        List<SkyblockItem.GemSlot> out = new ArrayList<>();
        for (JsonElement g : gems) {
            if (!g.isJsonObject()) continue;
            JsonObject slot = g.getAsJsonObject();
            Map<String, Integer> costs = new LinkedHashMap<>();
            JsonArray c = arr(slot, "costs");
            if (c != null) {
                for (JsonElement ce : c) {
                    if (!ce.isJsonObject()) continue;
                    JsonObject co = ce.getAsJsonObject();
                    String type = str(co, "type", "");
                    if ("COINS".equals(type)) costs.merge("COINS", (int) lng(co, "coins"), Integer::sum);
                    else if ("ITEM".equals(type) && str(co, "item_id", null) != null)
                        costs.merge(str(co, "item_id", null), (int) Math.max(1, lng(co, "amount")), Integer::sum);
                }
            }
            out.add(new SkyblockItem.GemSlot(str(slot, "slot_type", "UNKNOWN"), Map.copyOf(costs)));
        }
        return List.copyOf(out);
    }

    // ---- tolerant accessors ----
    static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return def;
        return e.getAsString();
    }

    static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            double d = e.getAsDouble();
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsLong();
        } catch (NumberFormatException ex) {
            try {
                return (long) e.getAsDouble();
            } catch (NumberFormatException ex2) {
                return 0;
            }
        }
    }

    static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return false;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
