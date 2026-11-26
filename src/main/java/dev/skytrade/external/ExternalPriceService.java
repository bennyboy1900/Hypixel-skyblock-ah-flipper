package dev.skytrade.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.skytrade.api.Backoff;
import dev.skytrade.api.RateLimiter;
import dev.skytrade.auction.ItemAttributes;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.util.Log;
import dev.skytrade.util.TimeSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * 7-day price history from an external SkyBlock price-history API (Coflnet by default).
 *
 * <p>Hypixel itself only exposes the last ~60 seconds of completed sales, so a fresh install has no history.
 * This service fills that gap for the items the analyzer is actually considering:</p>
 * <ul>
 *   <li>{@link #get} never blocks. It returns cached stats, or {@code null} and queues a fetch.</li>
 *   <li>{@link #drain} (called on a background schedule) fetches queued items, at most
 *       {@code maxRequestsPerMinute} per minute, with exponential backoff on errors.</li>
 *   <li>Results are cached for {@code cacheMinutes}; failed lookups are cached briefly so they are not retried
 *       in a loop.</li>
 * </ul>
 * Only the item tag (and pet rarity) is sent. No player, account or API key data ever leaves the client.
 */
public final class ExternalPriceService {
    private static final int MAX_QUEUE = 300;
    private static final long NEGATIVE_TTL_MS = 10 * 60_000L;

    private final Supplier<SkyTradeConfig.External> config;
    private final TimeSource time;
    private final RateLimiter limiter;
    private final Backoff backoff = new Backoff(5_000, 10 * 60_000);
    private final Map<String, PriceHistoryStats> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> queue = new LinkedHashSet<>();
    private final HttpClient http;
    private volatile String lastError = "";
    private volatile long lastSuccessAt;
    private volatile long requests, failures;

    public ExternalPriceService(Supplier<SkyTradeConfig.External> config, TimeSource time) {
        this.config = config;
        this.time = time;
        this.limiter = new RateLimiter(Math.max(1, config.get().maxRequestsPerMinute), time);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Cache key and API path parameters for an item. Pets are split by rarity, which dominates their price. */
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

    /** Fetches queued items within the rate budget. Call from a background thread only. */
    public void drain() {
        SkyTradeConfig.External cfg = config.get();
        if (!cfg.enabled) return;
        limiter.setRate(Math.max(1, cfg.maxRequestsPerMinute));
        for (int i = 0; i < 10; i++) {
            if (!backoff.ready(time.now()) || limiter.tryAcquire() > 0) return;
            String key;
            synchronized (queue) {
                Iterator<String> it = queue.iterator();
                if (!it.hasNext()) return;
                key = it.next();
                it.remove();
            }
            fetch(key, cfg);
        }
    }

    void fetch(String key, SkyTradeConfig.External cfg) {
        requests++;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url(key, cfg)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("User-Agent", "SkyTrade-Mod/1.0")
                    .GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            if (code == 429) {
                long retry = resp.headers().firstValue("Retry-After").map(ExternalPriceService::parseSeconds).orElse(60L);
                limiter.blockUntil(time.now() + retry * 1000);
                resp.body().close();
                enqueue(key);
                throw new IllegalStateException("rate limited, retry after " + retry + "s");
            }
            if (code == 404 || code == 204) {
                resp.body().close();
                failedAt.put(key, time.now());
                return; // item unknown to the external API: not an outage
            }
            if (code < 200 || code >= 300) {
                resp.body().close();
                throw new IllegalStateException("HTTP " + code);
            }
            JsonElement json;
            boolean gzip = resp.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
            try (InputStream raw = resp.body(); InputStream in = gzip ? new GZIPInputStream(raw) : raw;
                 InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(r);
            }
            PriceHistoryStats stats = PriceHistoryParser.summarize(key, PriceHistoryParser.parse(json), time.now());
            put(stats);
            backoff.success();
            lastSuccessAt = time.now();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (JsonParseException e) {
            failures++;
            failedAt.put(key, time.now());
            lastError = "malformed JSON";
        } catch (Exception e) {
            failures++;
            failedAt.put(key, time.now());
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            long delay = backoff.failure(time.now());
            Log.warn("external.request_failed", "item", key, "error", lastError, "backoffMs", delay);
        }
    }

    /** Builds e.g. {@code https://sky.coflnet.com/api/item/price/PET_ENDER_DRAGON/history/week?Rarity=LEGENDARY}. */
    public static String url(String key, SkyTradeConfig.External cfg) {
        int bar = key.indexOf('|');
        String tag = bar < 0 ? key : key.substring(0, bar);
        String base = cfg.baseUrl.endsWith("/") ? cfg.baseUrl.substring(0, cfg.baseUrl.length() - 1) : cfg.baseUrl;
        String url = base + "/api/item/price/" + URLEncoder.encode(tag, StandardCharsets.UTF_8) + "/history/week";
        if (bar >= 0) url += "?Rarity=" + URLEncoder.encode(key.substring(bar + 1), StandardCharsets.UTF_8);
        return url;
    }

    /** Stores stats directly (tests, and data restored from elsewhere). */
    public void put(PriceHistoryStats stats) {
        cache.put(stats.key(), stats);
        failedAt.remove(stats.key());
        if (cache.size() > 5_000) {
            long oldest = Long.MAX_VALUE;
            String victim = null;
            for (PriceHistoryStats s : cache.values()) {
                if (s.fetchedAt() < oldest) {
                    oldest = s.fetchedAt();
                    victim = s.key();
                }
            }
            if (victim != null) cache.remove(victim);
        }
    }

    private static long parseSeconds(String s) {
        try {
            return Math.max(1, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public String status() {
        if (!config.get().enabled) return "disabled";
        String base = cache.size() + " items cached, " + queued() + " queued, " + requests + " requests, " + failures + " failed";
        return lastError.isEmpty() ? base : base + " (last error: " + lastError + ")";
    }

    public long lastSuccessAt() {
        return lastSuccessAt;
    }
}
