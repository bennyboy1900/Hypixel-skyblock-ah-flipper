package dev.skytrade.execution.sandbox;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether autonomous sandbox trading may run. It fails closed: anything that is not positively
 * verified as private (singleplayer, main menu with the offline simulator, or a user-allowlisted
 * non-Hypixel server) is denied. Hypixel is always denied, even if allowlisted.
 */
public final class SandboxGuard {
    public record Verdict(boolean allowed, String reason) {}

    private static final String[] PUBLIC_MARKERS = {"hypixel.net", "hypixel.io", "hypixel"};

    private SandboxGuard() {}

    public static Verdict verify(EnvironmentProbe probe, List<String> allowlist) {
        EnvironmentProbe.Location loc;
        String address, brand;
        try {
            loc = probe.location();
            address = probe.serverAddress();
            brand = probe.serverBrand();
        } catch (RuntimeException e) {
            return new Verdict(false, "environment could not be verified");
        }
        if (loc == null || loc == EnvironmentProbe.Location.UNKNOWN) return new Verdict(false, "environment could not be verified");
        if (isPublicHypixel(address) || isPublicHypixel(brand)) return new Verdict(false, "connected to Hypixel: automated sandbox trading is disabled");
        switch (loc) {
            case MAIN_MENU:
                return new Verdict(true, "offline local simulation");
            case SINGLEPLAYER:
                return new Verdict(true, "singleplayer world (local simulation)");
            case MULTIPLAYER:
                if (address == null || address.isBlank()) return new Verdict(false, "unknown server");
                String norm = normalize(address);
                for (String allowed : allowlist) {
                    if (allowed != null && !allowed.isBlank() && normalize(allowed).equals(norm)) {
                        return new Verdict(true, "allowlisted private server " + norm);
                    }
                }
                return new Verdict(false, "server " + norm + " is not on the private test server allowlist");
            default:
                return new Verdict(false, "environment could not be verified");
        }
    }

    static boolean isPublicHypixel(String s) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.ROOT);
        for (String m : PUBLIC_MARKERS) if (l.contains(m)) return true;
        return false;
    }

    static String normalize(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return a;
    }
}
