package dev.skytrade.risk;

import dev.skytrade.config.SkyTradeConfig;

/** Halts automated sandbox activity on emergency stop, stale data, repeated errors or daily loss. */
public final class CircuitBreaker {
    public record Status(boolean tradingAllowed, String reason) {}

    private volatile boolean manualPause;
    private volatile int consecutiveErrors;
    private volatile Status last = new Status(true, "");

    public void pause() {
        manualPause = true;
    }

    public void resume() {
        manualPause = false;
        consecutiveErrors = 0;
    }

    public boolean manuallyPaused() {
        return manualPause;
    }

    public void error() {
        consecutiveErrors++;
    }

    public void success() {
        consecutiveErrors = 0;
    }

    public int consecutiveErrors() {
        return consecutiveErrors;
    }

    public Status evaluate(SkyTradeConfig.Risk r, long bazaarAgeMs, long staleAfterMs, double realizedLast24h) {
        Status s;
        if (r.emergencyStop) s = new Status(false, "emergency stop engaged");
        else if (manualPause) s = new Status(false, "paused by user");
        else if (r.pauseOnStaleData && bazaarAgeMs > staleAfterMs) s = new Status(false, "market data stale (" + bazaarAgeMs / 1000 + "s old)");
        else if (consecutiveErrors >= r.maxConsecutiveErrors) s = new Status(false, consecutiveErrors + " consecutive errors");
        else if (realizedLast24h < -r.maxDailyLoss) s = new Status(false, "daily loss limit reached");
        else s = new Status(true, "");
        last = s;
        return s;
    }

    public Status last() {
        return last;
    }
}
