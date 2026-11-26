package dev.skytrade.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.skytrade.api.Backoff;
import dev.skytrade.api.RateLimiter;
import dev.skytrade.config.SkyTradeConfig;
import dev.skytrade.util.Log;
import dev.skytrade.util.TimeSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * The only way the mod talks to the Coflnet SkyBlock API. Every caller shares one request budget, because
 * Coflnet blocks an IP that goes over its limit (about 50 requests; the block shows up as HTTP 403 with
 * {@code "error":"blocked"}). On a block, all requests stop for {@link #BLOCK_PAUSE_MS}.
 *
 * <p>Requests carry only public item tags and filters. No player, account or key data is sent.</p>
 */
public final class CoflnetClient {
    public static final long BLOCK_PAUSE_MS = 30 * 60_000L;

    /** A request that should not be retried right away. */
    public static final class CoflnetException extends Exception {
        public final int status;

        CoflnetException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    private final Supplier<SkyTradeConfig.External> config;
    private final TimeSource time;
    private final RateLimiter limiter;
    private final Backoff backoff = new Backoff(5_000, 10 * 60_000);
    private final HttpClient http;
    private volatile long blockedUntil;
    private volatile String lastError = "";
    private volatile long requests, failures, lastSuccessAt;

    public CoflnetClient(Supplier<SkyTradeConfig.External> config, TimeSource time) {
        this.config = config;
        this.time = time;
        this.limiter = new RateLimiter(Math.max(1, config.get().maxRequestsPerMinute), time);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** True if a request may be sent now (enabled, not blocked, not backing off, budget left). Consumes budget. */
    public boolean tryAcquire() {
        SkyTradeConfig.External cfg = config.get();
        if (!cfg.enabled) return false;
        long now = time.now();
        if (now < blockedUntil || !backoff.ready(now)) return false;
        limiter.setRate(Math.max(1, cfg.maxRequestsPerMinute));
        return limiter.tryAcquire() == 0;
    }

    /**
     * Blocking GET of {@code pathAndQuery} (e.g. {@code /api/prices/neu}). Call {@link #tryAcquire} first.
     *
     * @return parsed JSON, or {@code null} for 404/204 (unknown item: not an outage)
     */
    public JsonElement get(String pathAndQuery) throws CoflnetException, InterruptedException {
        SkyTradeConfig.External cfg = config.get();
        String base = cfg.baseUrl.endsWith("/") ? cfg.baseUrl.substring(0, cfg.baseUrl.length() - 1) : cfg.baseUrl;
        requests++;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("User-Agent", "SkyTrade-Mod/1.0")
                    .GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();
            if (code == 404 || code == 204) {
                drain(resp);
                return null;
            }
            JsonElement json = code >= 200 && code < 300 || code == 403 || code == 429 ? read(resp) : null;
            if (code == 403 && json != null && json.isJsonObject() && json.getAsJsonObject().has("error")) {
                blockedUntil = time.now() + BLOCK_PAUSE_MS;
                throw fail(new CoflnetException("Coflnet blocked this IP for exceeding its rate limit; paused for 30 min."
                        + " Lower external.maxRequestsPerMinute.", code));
            }
            if (code == 429) {
                long retry = resp.headers().firstValue("Retry-After").map(CoflnetClient::parseSeconds).orElse(60L);
                limiter.blockUntil(time.now() + retry * 1000);
                throw fail(new CoflnetException("rate limited, retry after " + retry + "s", code));
            }
            if (code < 200 || code >= 300) {
                if (json == null) drain(resp);
                throw fail(new CoflnetException("HTTP " + code, code));
            }
            if (json == null) throw fail(new CoflnetException("empty response", code));
            backoff.success();
            lastSuccessAt = time.now();
            lastError = "";
            return json;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            throw fail(new CoflnetException(e.getClass().getSimpleName() + ": " + e.getMessage(), 0));
        }
    }

    private CoflnetException fail(CoflnetException e) {
        failures++;
        lastError = e.getMessage();
        long delay = backoff.failure(time.now());
        Log.warn("coflnet.request_failed", "status", e.status, "error", e.getMessage(), "backoffMs", delay);
        return e;
    }

    private static JsonElement read(HttpResponse<InputStream> resp) throws IOException {
        boolean gzip = resp.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
        try (InputStream raw = resp.body(); InputStream in = gzip ? new GZIPInputStream(raw) : raw;
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r);
        }
    }

    private static void drain(HttpResponse<InputStream> resp) {
        try (InputStream in = resp.body()) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private static long parseSeconds(String s) {
        try {
            return Math.max(1, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public boolean blocked() {
        return time.now() < blockedUntil;
    }

    public String status() {
        if (!config.get().enabled) return "disabled";
        String s = requests + " requests, " + failures + " failed";
        if (blocked()) s += ", BLOCKED by Coflnet (rate limit) for " + (blockedUntil - time.now()) / 60_000 + " more min";
        else if (!lastError.isEmpty()) s += " (last error: " + lastError + ")";
        return s;
    }

    public long lastSuccessAt() {
        return lastSuccessAt;
    }
}
