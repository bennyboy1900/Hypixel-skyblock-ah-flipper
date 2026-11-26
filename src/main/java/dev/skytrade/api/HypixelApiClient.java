package dev.skytrade.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * Thin, defensive client for the Hypixel public API. Every call:
 * <ul>
 *   <li>goes through the token bucket rate limiter,</li>
 *   <li>respects per-endpoint exponential backoff,</li>
 *   <li>has connect and request timeouts,</li>
 *   <li>validates {@code success == true} and the presence of {@code lastUpdated}.</li>
 * </ul>
 * The optional API key is only ever placed in the {@code API-Key} header and never logged.
 */
public final class HypixelApiClient {
    private final HttpClient http;
    private final Supplier<SkyTradeConfig.Api> config;
    private final RateLimiter limiter;
    private final ApiStatus status;
    private final TimeSource time;
    private final Map<Endpoint, Backoff> backoffs = new EnumMap<>(Endpoint.class);

    public HypixelApiClient(Supplier<SkyTradeConfig.Api> config, ApiStatus status, TimeSource time, Executor executor) {
        this.config = config;
        this.status = status;
        this.time = time;
        this.limiter = new RateLimiter(config.get().maxRequestsPerMinute, time);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
        for (Endpoint e : Endpoint.values()) backoffs.put(e, new Backoff(2_000, 5 * 60_000));
    }

    public ApiStatus status() {
        return status;
    }

    public boolean ready(Endpoint e) {
        return backoffs.get(e).ready(time.now());
    }

    public long nextAttemptAt(Endpoint e) {
        return backoffs.get(e).nextAttemptAt();
    }

    /** Asynchronous fetch; the rate limiter wait is scheduled without blocking any thread. */
    public CompletableFuture<JsonObject> fetchAsync(Endpoint endpoint, String query) {
        long wait = limiter.tryAcquire();
        if (wait > 0) {
            Executor delayed = CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS);
            return CompletableFuture.supplyAsync(() -> null, delayed).thenCompose(v -> fetchAsync(endpoint, query));
        }
        SkyTradeConfig.Api cfg = config.get();
        limiter.setRate(cfg.maxRequestsPerMinute);
        HttpRequest req = buildRequest(endpoint, query, cfg);
        ApiStatus.EndpointState st = status.get(endpoint);
        long started = time.now();
        st.lastAttemptAt = started;
        st.totalRequests++;
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(resp -> {
                    try {
                        JsonObject json = handle(endpoint, resp);
                        onSuccess(endpoint, json, time.now() - started);
                        return json;
                    } catch (ApiException e) {
                        onFailure(endpoint, e);
                        throw new java.util.concurrent.CompletionException(e);
                    }
                })
                .exceptionally(t -> {
                    Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
                    if (!(cause instanceof ApiException)) {
                        onFailure(endpoint, new ApiException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause));
                    }
                    throw new java.util.concurrent.CompletionException(cause);
                });
    }

    /** Blocking fetch for use on background threads only. */
    public JsonObject fetch(Endpoint endpoint, String query) throws ApiException {
        try {
            return fetchAsync(endpoint, query).get(config.get().requestTimeoutMs + 15_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof ApiException ae) throw ae;
            throw new ApiException(String.valueOf(e.getCause()), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            ApiException ae = new ApiException("timeout", e);
            onFailure(endpoint, ae);
            throw ae;
        }
    }

    private HttpRequest buildRequest(Endpoint endpoint, String query, SkyTradeConfig.Api cfg) {
        String url = cfg.baseUrl + endpoint.path + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "SkyTrade-Mod/1.0")
                .GET();
        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) b.header("API-Key", cfg.apiKey.trim());
        return b.build();
    }

    private JsonObject handle(Endpoint endpoint, HttpResponse<InputStream> resp) throws ApiException {
        int code = resp.statusCode();
        resp.headers().firstValue("RateLimit-Remaining").ifPresent(v -> {
            try {
                status.setRateLimitRemaining(Integer.parseInt(v.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        if (code == 429) {
            long retry = resp.headers().firstValue("Retry-After").map(HypixelApiClient::parseLongOr60).orElse(60L);
            limiter.blockUntil(time.now() + retry * 1000);
            drain(resp);
            throw new ApiException("rate limited (429), retry after " + retry + "s", code, true);
        }
        if (code == 404 && endpoint == Endpoint.AUCTIONS) {
            // Page out of range: a valid outcome during pagination when the total shrinks.
            drain(resp);
            throw new ApiException("page not found", code, false);
        }
        if (code < 200 || code >= 300) {
            drain(resp);
            throw new ApiException("HTTP " + code, code, code >= 500 || code == 408);
        }
        JsonElement el;
        try (InputStream raw = resp.body();
             InputStream in = isGzip(resp) ? new GZIPInputStream(raw, 65536) : raw;
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            el = JsonParser.parseReader(reader);
        } catch (IOException | JsonParseException e) {
            throw new ApiException("malformed JSON: " + e.getMessage(), code, true);
        }
        if (el == null || !el.isJsonObject()) throw new ApiException("response is not a JSON object", code, true);
        JsonObject obj = el.getAsJsonObject();
        if (!obj.has("success") || !obj.get("success").isJsonPrimitive() || !obj.get("success").getAsBoolean()) {
            String cause = obj.has("cause") ? obj.get("cause").getAsString() : "success=false";
            throw new ApiException("API error: " + cause, code, true);
        }
        if (endpoint != Endpoint.AUCTIONS_ENDED && endpoint != Endpoint.ITEMS && !obj.has("lastUpdated")) {
            throw new ApiException("missing lastUpdated", code, true);
        }
        return obj;
    }

    private static boolean isGzip(HttpResponse<?> resp) {
        return resp.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase().contains("gzip")).orElse(false);
    }

    private static void drain(HttpResponse<InputStream> resp) {
        try (InputStream in = resp.body()) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private static long parseLongOr60(String s) {
        try {
            return Math.max(1, Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private void onSuccess(Endpoint e, JsonObject json, long latency) {
        backoffs.get(e).success();
        ApiStatus.EndpointState st = status.get(e);
        st.lastSuccessAt = time.now();
        st.consecutiveFailures = 0;
        st.lastLatencyMs = latency;
        if (json.has("lastUpdated") && json.get("lastUpdated").isJsonPrimitive()) {
            try {
                st.dataTimestamp = json.get("lastUpdated").getAsLong();
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void onFailure(Endpoint e, ApiException ex) {
        if (ex.status == 404 && e == Endpoint.AUCTIONS) return; // not an outage
        ApiStatus.EndpointState st = status.get(e);
        st.lastErrorAt = time.now();
        st.lastError = ex.getMessage() == null ? "error" : ex.getMessage();
        st.consecutiveFailures++;
        st.totalFailures++;
        long delay = backoffs.get(e).failure(time.now());
        Log.warn("api.request_failed", "endpoint", e, "status", ex.status, "error", st.lastError, "backoffMs", delay);
    }
}
