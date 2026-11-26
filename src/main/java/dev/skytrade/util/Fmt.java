package dev.skytrade.util;

import java.util.Locale;

/** Number / time formatting for display. */
public final class Fmt {
    private Fmt() {}

    public static String coins(double v) {
        if (!Stats.finite(v)) return "-";
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) return sign + String.format(Locale.ROOT, "%.2fB", a / 1e9);
        if (a >= 1e6) return sign + String.format(Locale.ROOT, "%.2fM", a / 1e6);
        if (a >= 1e4) return sign + String.format(Locale.ROOT, "%.1fK", a / 1e3);
        if (a >= 100) return sign + String.format(Locale.ROOT, "%.0f", a);
        return sign + String.format(Locale.ROOT, "%.1f", a);
    }

    public static String signedCoins(double v) {
        return (v > 0 ? "+" : "") + coins(v);
    }

    public static String pct(double ratio) {
        if (!Stats.finite(ratio)) return "-";
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    public static String signedPct(double ratio) {
        return (ratio > 0 ? "+" : "") + pct(ratio);
    }

    public static String num(double v) {
        if (!Stats.finite(v)) return "-";
        double a = Math.abs(v);
        if (a >= 1e6) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (a >= 1e4) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    public static String score(double v) {
        if (!Stats.finite(v)) return "-";
        return String.format(Locale.ROOT, "%.0f", v * 100);
    }

    /** Duration in milliseconds to a compact human form, e.g. 3h 12m. */
    public static String duration(double millis) {
        if (!Stats.finite(millis) || millis < 0) return "-";
        long s = (long) (millis / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s";
        long h = m / 60;
        if (h < 48) return h + "h " + (m % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    public static String age(long timestampMillis, long now) {
        if (timestampMillis <= 0) return "never";
        return duration(Math.max(0, now - timestampMillis)) + " ago";
    }

    /** Converts SCREAMING_CASE ids to Title Case for fallback display names. */
    public static String prettyId(String id) {
        if (id == null || id.isEmpty()) return "?";
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Strips Minecraft legacy formatting codes (section sign + char). */
    public static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "");
    }
}
