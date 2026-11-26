package dev.skytrade.api;

import dev.skytrade.util.TimeSource;

/**
 * Token bucket limiting outgoing requests. Also honours server-side hints (429 / Retry-After)
 * through {@link #blockUntil(long)}.
 */
public final class RateLimiter {
    private final TimeSource time;
    private double capacity;
    private double tokens;
    private double refillPerMs;
    private long lastRefill;
    private long blockedUntil;

    public RateLimiter(int perMinute, TimeSource time) {
        this.time = time;
        setRate(perMinute);
        this.tokens = capacity;
        this.lastRefill = time.now();
    }

    public synchronized void setRate(int perMinute) {
        this.capacity = Math.max(1, perMinute / 6.0); // allow bursts of ~10s worth
        this.refillPerMs = perMinute / 60_000.0;
        this.tokens = Math.min(tokens, capacity);
    }

    /** @return 0 if a permit was taken, otherwise the number of ms to wait before retrying. */
    public synchronized long tryAcquire() {
        long now = time.now();
        if (now < blockedUntil) return blockedUntil - now;
        tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMs);
        lastRefill = now;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / refillPerMs);
    }

    /** Blocks the calling (background) thread until a permit is available. */
    public void acquire() throws InterruptedException {
        long wait;
        while ((wait = tryAcquire()) > 0) {
            Thread.sleep(Math.min(wait, 5_000));
        }
    }

    public synchronized void blockUntil(long timestamp) {
        blockedUntil = Math.max(blockedUntil, timestamp);
    }
}
