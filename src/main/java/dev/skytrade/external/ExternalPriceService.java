package dev.skytrade.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.util.TimeSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * External price data from the Coflnet SkyBlock API, so the mod has prices from the first minute instead of
 * waiting hours to collect sales itself (Hypixel only exposes the last ~60 seconds of sales).
 *
 * <ul>
 *   <li><b>Bulk lowest BIN</b> ({@code /api/prices/neu}): one request for every AH item, refreshed every
 *       {@code bulkRefreshMinutes}. Used to price upgrades that are not on the bazaar.</li>
 *   <li><b>7-day history</b> ({@code /api/item/price/{tag}/history/week}): hourly min/max/avg/volume for the
 *       items the analyzer is actually considering. {@link #get} never blocks; it returns cached stats, or
 *       {@code null} and queues a fetch that {@link #drain} sends within the shared request budget.</li>
 * </ul>
 */
public final class ExternalPriceService {
    private static final int MAX_QUEUE = 300;
    private static final long NEGATIVE_TTL_MS = 10 * 60_000L;
    private static final String[] PET_RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};

    private final Supplier<SkyTradeConfig.External> config;
    private final TimeSource time;
    private final CoflnetClient client;
    private final Map<String, PriceHistoryStats> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private volatile Map<String, Double> lowestBins = Map.of();
    private volatile long lowestBinsAt;

    public ExternalPriceService(Supplier<SkyTradeConfig.External> config, TimeSource time, CoflnetClient client) {
        this.config = config;
        this.time = time;
        this.client = client;
    }

    /** Cache key: the item tag, and for pets also the rarity, which dominates their price. */
    public static String key(ItemAttributes a) {
        return a.isPet() ? a.skyblockId() + "|" + a.rarity() : a.skyblockId();
    }

    /**
     * Cached history for the item, or {@code null} if none is available yet.
     *
     * @param request queue a fetch when the cache has nothing fresh
     */
    public PriceHistoryStats get(ItemAttributes a, boolean request) {
        SkyTradeConfig.External cfg = config.get();
        if (!cfg.enabled) return null;
        String key = key(a);
        PriceHistoryStats s = cache.get(key);
        long now = time.now();
        boolean fresh = s != null && now - s.fetchedAt() < cfg.cacheMinutes * 60_000L;
        if (!fresh && request) enqueue(key);
        // Stale data is still better than none for sanity checks, up to 4x the cache time.
        if (s != null && now - s.fetchedAt() < 4L * cfg.cacheMinutes * 60_000L) return s;
        return null;
    }

    private void enqueue(String key) {
        Long failed = failedAt.get(key);
        if (failed != null && time.now() - failed < NEGATIVE_TTL_MS) return;
        synchronized (queue) {
            if (queue.size() < MAX_QUEUE) queue.add(key);
        }
    }

    public int queued() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** Sends queued work within the shared budget. Call from a background thread only. */
    public void drain() {
        SkyTradeConfig.External cfg = config.get();
        if (!cfg.enabled) return;
        if (time.now() - lowestBinsAt > cfg.bulkRefreshMinutes * 60_000L && client.tryAcquire()) {
            refreshLowestBins();
        }
        for (int i = 0; i < 10; i++) {
            synchronized (queue) {
                if (queue.isEmpty()) return;
            }
            if (!client.tryAcquire()) return;
            String key;
            synchronized (queue) {
                Iterator<String> it = queue.iterator();
                if (!it.hasNext()) return;
                key = it.next();
                it.remove();
            }
            fetchHistory(key);
        }
    }

    private void fetchHistory(String key) {
        try {
            JsonElement json = client.get(historyPath(key));
            if (json == null) {
                failedAt.put(key, time.now());
                return;
            }
            put(PriceHistoryParser.summarize(key, PriceHistoryParser.parse(json), time.now()));
        } catch (CoflnetClient.CoflnetException e) {
            failedAt.put(key, time.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void refreshLowestBins() {
        lowestBinsAt = time.now(); // also on failure, so a broken endpoint is not hammered
        try {
            JsonElement json = client.get("/api/prices/neu");
            if (json != null && json.isJsonObject()) {
                Map<String, Double> m = parseNeuPrices(json.getAsJsonObject());
                if (!m.isEmpty()) lowestBins = m;
            }
        } catch (CoflnetClient.CoflnetException ignored) {
            // status is tracked by the client
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Parses NEU-format prices ({@code {"HYPERION":522000000,"ENDER_DRAGON;4":403930731,...}}) into our keys:
     * pets {@code TYPE;rarityIndex} become {@code PET_TYPE|RARITY}, everything else keeps its item id.
     */
    public static Map<String, Double> parseNeuPrices(JsonObject o) {
        Map<String, Double> out = new HashMap<>(o.size() * 2);
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) continue;
            double price = e.getValue().getAsDouble();
            if (!(price > 0)) continue;
            String key = fromNeuKey(e.getKey());
            if (key.indexOf(';') >= 0) continue; // unknown rarity index (e.g. 7): do not guess
            out.put(key, price);
        }
        return out;
    }

    static String fromNeuKey(String k) {
        int semi = k.indexOf(';');
        if (semi < 0) return k;
        try {
            int r = Integer.parseInt(k.substring(semi + 1));
            if (r >= 0 && r < PET_RARITIES.length) return "PET_" + k.substring(0, semi) + "|" + PET_RARITIES[r];
        } catch (NumberFormatException ignored) {
        }
        return k;
    }

    /** Lowest BIN on the whole AH for an item id or pet key, or NaN. Older than 1 hour counts as unknown. */
    public double lowestBin(String key) {
        if (time.now() - lowestBinsAt > 3_600_000L) return Double.NaN;
        Double d = lowestBins.get(key);
        return d == null ? Double.NaN : d;
    }

    public int lowestBinCount() {
        return lowestBins.size();
    }

    /** {@code /api/item/price/PET_ENDER_DRAGON/history/week?Rarity=LEGENDARY} */
    public static String historyPath(String key) {
        int bar = key.indexOf('|');
        String tag = bar < 0 ? key : key.substring(0, bar);
        String path = "/api/item/price/" + URLEncoder.encode(tag, StandardCharsets.UTF_8) + "/history/week";
        if (bar >= 0) path += "?Rarity=" + URLEncoder.encode(key.substring(bar + 1), StandardCharsets.UTF_8);
        return path;
    }

    /** Stores stats directly (tests, and data restored from elsewhere). */
    public void put(PriceHistoryStats stats) {
        cache.put(stats.key(), stats);
        failedAt.remove(stats.key());
        if (cache.size() > 5_000) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (PriceHistoryStats s : cache.values()) {
                if (s.fetchedAt() < oldest) {
                    oldest = s.fetchedAt();
                    victim = s.key();
                }
            }
            if (victim != null) cache.remove(victim);
        }
    }

    public String status() {
        if (!config.get().enabled) return "disabled";
        return cache.size() + " histories, " + queued() + " queued, " + lowestBins.size() + " lowest BINs | Coflnet: " + client.status();
    }
}
