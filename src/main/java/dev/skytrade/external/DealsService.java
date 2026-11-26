package dev.skytrade.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skytrade.auction.AuctionFees;
import dev.skytrade.auction.AuctionIndex;
import dev.skytrade.auction.DecodedAuction;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.market.BazaarProduct;
import dev.skytrade.market.BazaarSnapshot;
import dev.skytrade.market.ItemCatalog;
import dev.skytrade.util.Fmt;
import dev.skytrade.util.TimeSource;

import java.util.*;
import java.util.function.Supplier;

/**
 * Pulls Coflnet's free flip lists and keeps only deals that still hold up against the mod's own live data.
 * Coflnet's numbers are a lead, never trusted blindly:
 * <ul>
 *   <li><b>Bazaar spread</b> ({@code /api/flip/bazaar/spread}): dropped if Coflnet flags it as manipulated;
 *       entry and exit are recomputed from the live order book (one tick inside the spread, after tax).</li>
 *   <li><b>Craft</b> ({@code /api/craft/profit}): bazaar ingredients are re-priced at the live instant-buy
 *       price; the sale uses the lower of Coflnet's sell price and its median, after AH fees (or the live
 *       bazaar bid after tax for bazaar items).</li>
 *   <li><b>Kat upgrade</b> ({@code /api/kat/profit}): the pet's auction must still be active at that price in
 *       the mod's own AH scan; materials are re-priced from the bazaar; the sale uses the median after fees.</li>
 * </ul>
 */
public final class DealsService {
    private final Supplier<SkyTradeConfig> config;
    private final TimeSource time;
    private final CoflnetClient client;
    private final String[] feeds = {"/api/flip/bazaar/spread", "/api/craft/profit", "/api/kat/profit"};
    private final Map<String, JsonElement> raw = new HashMap<>();
    private int nextFeed;
    private long cycleStartedAt;
    private volatile List<ExternalDeal> deals = List.of();
    private volatile long computedAt;

    public DealsService(Supplier<SkyTradeConfig> config, TimeSource time, CoflnetClient client) {
        this.config = config;
        this.time = time;
        this.client = client;
    }

    /** Fetches at most one feed per call, cycling every {@code dealsRefreshMinutes}. Background thread only. */
    public synchronized boolean poll() {
        SkyTradeConfig.External cfg = config.get().external;
        if (!cfg.enabled || !cfg.dealsEnabled) return false;
        long now = time.now();
        if (nextFeed >= feeds.length) {
            if (now - cycleStartedAt < cfg.dealsRefreshMinutes * 60_000L) return false;
            nextFeed = 0;
        }
        if (nextFeed == 0) cycleStartedAt = now;
        if (!client.tryAcquire()) return false;
        String feed = feeds[nextFeed++];
        try {
            JsonElement json = client.get(feed);
            if (json != null) raw.put(feed, json);
        } catch (CoflnetClient.CoflnetException ignored) {
            // status is tracked by the client; keep the previous data for this feed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** Re-checks all fetched deals against the current market. Cheap; call after every bazaar/AH update. */
    public void recompute(BazaarSnapshot bz, AuctionIndex ah, ItemCatalog catalog) {
        Map<String, JsonElement> copy;
        synchronized (this) {
            copy = new HashMap<>(raw);
        }
        SkyTradeConfig cfg = config.get();
        List<ExternalDeal> out = new ArrayList<>();
        if (bz != null) out.addAll(bazaarSpread(copy.get(feeds[0]), bz, catalog, cfg));
        out.addAll(crafts(copy.get(feeds[1]), bz, catalog, cfg));
        out.addAll(kat(copy.get(feeds[2]), bz, ah, cfg));
        out.removeIf(d -> d.netProfit() < cfg.external.dealsMinProfit);
        out.sort(Comparator.comparingDouble(ExternalDeal::netProfit).reversed());
        deals = List.copyOf(out.size() > 300 ? out.subList(0, 300) : out);
        computedAt = time.now();
    }

    public List<ExternalDeal> deals() {
        return deals;
    }

    public long computedAt() {
        return computedAt;
    }

    // ------------------------------------------------------------------ bazaar spread

    static List<ExternalDeal> bazaarSpread(JsonElement json, BazaarSnapshot bz, ItemCatalog catalog, SkyTradeConfig cfg) {
        List<ExternalDeal> out = new ArrayList<>();
        for (JsonObject o : objects(json)) {
            if (bool(o, "isManipulated")) continue;
            JsonObject f = obj(o, "flip");
            if (f == null) continue;
            String tag = str(f, "itemTag");
            BazaarProduct p = tag == null ? null : bz.get(tag);
            if (p == null || p.bestBid() <= 0 || p.bestAsk() <= 0) continue;
            double entry = p.bestBid() + 0.1, exit = p.bestAsk() - 0.1;
            double net = exit * (1 - cfg.fees.bazaarTax) - entry;
            if (net <= 0) continue;
            double vol = num(f, "volume");
            // Coflnet estimates fill times in seconds for both legs.
            double hours = (num(f, "estimatedBuyFillSeconds") + num(f, "estimatedSellFillSeconds")) / 3600.0;
            List<String> why = new ArrayList<>();
            why.add("Coflnet bazaar spread flip, not flagged as manipulated.");
            why.add("Live order book: buy order at " + Fmt.coins(entry) + ", sell offer at " + Fmt.coins(exit)
                    + ", tax " + Fmt.pct(cfg.fees.bazaarTax) + " -> " + Fmt.signedCoins(net) + " per item.");
            if (hours > 0) why.add("Coflnet fill estimate: " + Fmt.duration(hours * 3_600_000) + " for both orders.");
            why.add("Per item shown. Use the Bazaar page for sizing and order-book depth.");
            out.add(new ExternalDeal("BZ:" + tag, "Bazaar spread", tag, catalog.displayName(tag), entry, exit * (1 - cfg.fees.bazaarTax),
                    net, vol, hours > 0 ? hours : Double.NaN, "/bz " + catalog.displayName(tag), why));
        }
        return out;
    }

    // ------------------------------------------------------------------ crafts

    static List<ExternalDeal> crafts(JsonElement json, BazaarSnapshot bz, ItemCatalog catalog, SkyTradeConfig cfg) {
        List<ExternalDeal> out = new ArrayList<>();
        for (JsonObject o : objects(json)) {
            String id = str(o, "itemId");
            JsonArray ing = arr(o, "ingredients");
            if (id == null || ing == null || ing.isEmpty()) continue;
            List<String> why = new ArrayList<>();
            double cost = 0;
            boolean subCraft = false;
            for (JsonElement e : ing) {
                if (!e.isJsonObject()) continue;
                JsonObject i = e.getAsJsonObject();
                String iid = str(i, "itemId");
                double count = num(i, "count");
                BazaarProduct p = bz == null || iid == null ? null : bz.get(iid);
                double c;
                if (p != null && p.bestAsk() > 0) {
                    c = p.bestAsk() * count;
                    why.add("  " + Fmt.num(count) + "x " + catalog.displayName(iid) + ": " + Fmt.coins(c) + " (live bazaar instant-buy)");
                } else {
                    c = num(i, "cost");
                    boolean crafted = "craft".equals(str(i, "type"));
                    subCraft |= crafted;
                    why.add("  " + Fmt.num(count) + "x " + catalog.displayName(iid) + ": " + Fmt.coins(c)
                            + (crafted ? " (craft it yourself first)" : " (Coflnet price)"));
                }
                if (!(c > 0)) {
                    cost = Double.NaN;
                    break;
                }
                cost += c;
            }
            if (!(cost > 0)) continue;
            BazaarProduct outP = bz == null ? null : bz.get(id);
            double sale;
            String saleLine;
            if (outP != null && outP.bestBid() > 0) {
                sale = outP.bestBid() * (1 - cfg.fees.bazaarTax);
                saleLine = "Sell to the bazaar at " + Fmt.coins(outP.bestBid()) + " minus tax.";
            } else {
                double sell = num(o, "sellPrice"), median = num(o, "median");
                double ref = median > 0 ? Math.min(sell, median) : sell;
                if (!(ref > 0)) continue;
                sale = AuctionFees.netProceeds(ref, cfg.fees);
                saleLine = "Sell on the AH at " + Fmt.coins(ref) + " (lower of Coflnet price " + Fmt.coins(sell)
                        + " and median " + Fmt.coins(median) + "), after fees.";
            }
            double vol = num(o, "volume");
            if (vol < 1) continue; // nobody buys it
            double net = sale - cost;
            if (net <= 0) continue;
            why.add(0, "Coflnet craft flip. Ingredients re-priced with live data:");
            why.add(saleLine);
            JsonObject req = obj(o, "reqCollection");
            if (req != null) why.add("Requires " + str(req, "name") + " collection " + (int) num(req, "level") + ".");
            if (subCraft) why.add("Some ingredients must be crafted first (multi-step).");
            why.add("Sales volume: " + Fmt.num(vol) + ".");
            out.add(new ExternalDeal("CRAFT:" + id, "Craft", id, catalog.displayName(id), cost, sale, net, vol, Double.NaN,
                    "/viewrecipe " + id, why));
        }
        return out;
    }

    // ------------------------------------------------------------------ kat

    static List<ExternalDeal> kat(JsonElement json, BazaarSnapshot bz, AuctionIndex ah, SkyTradeConfig cfg) {
        List<ExternalDeal> out = new ArrayList<>();
        if (ah == null || ah.isEmpty()) return out; // cannot verify listings yet
        for (JsonObject o : objects(json)) {
            String uuid = str(o, "originAuction");
            JsonObject core = obj(o, "coreData");
            if (uuid == null || core == null) continue;
            double purchase = num(o, "purchaseCost");
            DecodedAuction live = ah.byUuid(uuid);
            if (live == null || live.auction().startingBid() > purchase || purchase <= 0) continue; // sold or repriced
            double median = num(o, "median");
            if (!(median > 0) || num(o, "volume") < 2) continue;
            List<String> why = new ArrayList<>();
            why.add("Coflnet Kat flip: buy " + str(o, "originAuctionName") + ", upgrade to " + str(o, "targetRarity") + ".");
            why.add("Listing still active in the live AH scan at " + Fmt.coins(live.auction().startingBid()) + ".");
            double materials = 0;
            JsonObject mats = obj(core, "materials");
            boolean allBazaar = mats != null;
            if (mats != null) {
                for (Map.Entry<String, JsonElement> m : mats.entrySet()) {
                    BazaarProduct p = bz == null ? null : bz.get(m.getKey());
                    if (p == null || p.bestAsk() <= 0 || !m.getValue().isJsonPrimitive()) {
                        allBazaar = false;
                        break;
                    }
                    materials += p.bestAsk() * m.getValue().getAsDouble();
                }
            }
            if (!allBazaar) materials = num(o, "materialCost");
            why.add("Materials " + Fmt.coins(materials) + (allBazaar ? " (live bazaar)" : " (Coflnet price)") + ".");
            double coins = num(o, "upgradeCost") + num(core, "cost");
            if (coins > 0) why.add("Kat fee " + Fmt.coins(coins) + ".");
            double cost = live.auction().startingBid() + materials + coins;
            double sale = AuctionFees.netProceeds(median, cfg.fees);
            double net = sale - cost;
            if (net <= 0) continue;
            double hours = num(core, "hours");
            why.add("Sell at the median " + Fmt.coins(median) + " after fees" + (hours > 0 ? "; Kat takes " + Fmt.duration(hours * 3_600_000) : "") + ".");
            String u = uuid.replace("-", "");
            String dashed = u.length() == 32 ? u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-"
                    + u.substring(16, 20) + "-" + u.substring(20) : uuid;
            String name = str(core, "name");
            out.add(new ExternalDeal("KAT:" + u, "Kat upgrade", str(core, "itemTag"), (name == null ? "Pet" : name) + " -> " + str(o, "targetRarity"),
                    cost, sale, net, num(o, "volume"), hours > 0 ? hours : Double.NaN, "/viewauction " + dashed, why));
        }
        return out;
    }

    // ------------------------------------------------------------------ tolerant JSON access

    private static List<JsonObject> objects(JsonElement json) {
        List<JsonObject> out = new ArrayList<>();
        if (json == null || !json.isJsonArray()) return out;
        for (JsonElement e : json.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
