package dev.skytrade.api;

import java.util.EnumMap;
import java.util.Map;

/** Live health information for each endpoint, read by the GUI/HUD. */
public final class ApiStatus {
    public enum Health { ONLINE, DEGRADED, OFFLINE, UNKNOWN }

    public static final class EndpointState {
        public volatile long lastSuccessAt;
        public volatile long lastAttemptAt;
        public volatile long lastErrorAt;
        public volatile String lastError = "";
        public volatile int consecutiveFailures;
        public volatile long lastLatencyMs;
        /** Server-side lastUpdated of the most recent successful dataset. */
        public volatile long dataTimestamp;
        public volatile long totalRequests;
        public volatile long totalFailures;
    }

    private final Map<Endpoint, EndpointState> states = new EnumMap<>(Endpoint.class);
    private volatile int rateLimitRemaining = -1;

    public ApiStatus() {
        for (Endpoint e : Endpoint.values()) states.put(e, new EndpointState());
    }

    public EndpointState get(Endpoint e) {
        return states.get(e);
    }

    public void setRateLimitRemaining(int v) {
        rateLimitRemaining = v;
    }

    public int rateLimitRemaining() {
        return rateLimitRemaining;
    }

    /** Overall health based on the bazaar endpoint (the core dataset) and others. */
    public Health overall() {
        EndpointState bz = states.get(Endpoint.BAZAAR);
        if (bz.lastAttemptAt == 0) return Health.UNKNOWN;
        if (bz.consecutiveFailures >= 3 || bz.lastSuccessAt == 0) return Health.OFFLINE;
        for (EndpointState s : states.values()) {
            if (s.consecutiveFailures > 0) return Health.DEGRADED;
        }
        return Health.ONLINE;
    }
}
