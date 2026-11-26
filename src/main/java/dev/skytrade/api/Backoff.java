package dev.skytrade.api;

import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with jitter: base * 2^failures, capped. */
public final class Backoff {
    private final long baseMs;
    private final long maxMs;
    private int failures;
    private long nextAttemptAt;

    public Backoff(long baseMs, long maxMs) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    public synchronized void success() {
        failures = 0;
        nextAttemptAt = 0;
    }

    public synchronized long failure(long now) {
        failures++;
        long delay = delayFor(failures);
        nextAttemptAt = now + delay;
        return delay;
    }

    long delayFor(int n) {
        long exp = baseMs * (1L << Math.min(16, Math.max(0, n - 1)));
        long capped = Math.min(maxMs, exp);
        long jitter = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble());
        return capped + jitter;
    }

    public synchronized boolean ready(long now) {
        return now >= nextAttemptAt;
    }

    public synchronized long nextAttemptAt() {
        return nextAttemptAt;
    }

    public synchronized int failures() {
        return failures;
    }
}
