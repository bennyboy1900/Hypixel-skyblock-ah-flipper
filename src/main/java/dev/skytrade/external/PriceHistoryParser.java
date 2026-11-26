package dev.skytrade.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.skytrade.util.Stats;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses price-history buckets in the format used by the Coflnet SkyBlock API
 * ({@code [{"min":..,"max":..,"avg":..,"volume":..,"time":"2025-01-01T12:00:00"}, ...]}) and reduces them to
 * {@link PriceHistoryStats}. Tolerant: unknown fields are ignored, malformed buckets are skipped, and both ISO
 * time strings and epoch milliseconds are accepted.
 */
public final class PriceHistoryParser {
    public record Bucket(long time, double min, double max, double avg, long volume) {}

    private PriceHistoryParser() {}

    public static List<Bucket> parse(JsonElement root) {
        List<Bucket> out = new ArrayList<>();
        JsonArray arr = root != null && root.isJsonArray() ? root.getAsJsonArray() : null;
        if (arr == null && root != null && root.isJsonObject()) {
            JsonElement inner = root.getAsJsonObject().get("history");
            if (inner != null && inner.isJsonArray()) arr = inner.getAsJsonArray();
        }
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            double avg = num(o, "avg", num(o, "median", Double.NaN));
            double min = num(o, "min", avg);
            double max = num(o, "max", avg);
            long vol = (long) num(o, "volume", num(o, "count", 1));
            long t = time(o.get("time"));
            if (!(avg > 0) || !(min > 0) || !(max > 0) || t <= 0 || vol <= 0) continue;
            out.add(new Bucket(t, Math.min(min, avg), Math.max(max, avg), avg, vol));
        }
        out.sort(Comparator.comparingLong(Bucket::time));
        return out;
    }

    public static PriceHistoryStats summarize(String key, List<Bucket> buckets, long fetchedAt) {
        int n = buckets.size();
        if (n == 0) return new PriceHistoryStats(key, 0, 0, 0, 0, 0, 0, 0, fetchedAt);
        double[] avg = new double[n], w = new double[n], mins = new double[n], maxs = new double[n];
        long volume = 0;
        for (int i = 0; i < n; i++) {
            Bucket b = buckets.get(i);
            avg[i] = b.avg();
            w[i] = b.volume();
            mins[i] = b.min();
            maxs[i] = b.max();
            volume += b.volume();
        }
        double median = Stats.weightedMedian(avg, w);
        long last = buckets.get(n - 1).time();
        double rs = 0, rw = 0;
        for (Bucket b : buckets) {
            if (last - b.time() <= 86_400_000L) {
                rs += b.avg() * b.volume();
                rw += b.volume();
            }
        }
        double recent = rw > 0 ? rs / rw : avg[n - 1];
        double trend = 0;
        if (n >= 6) {
            int third = n / 3;
            double[] first = new double[third], lastThird = new double[third];
            for (int i = 0; i < third; i++) {
                first[i] = avg[i];
                lastThird[i] = avg[n - third + i];
            }
            double f = Stats.median(first);
            if (f > 0) trend = Stats.median(lastThird) / f - 1;
        }
        return new PriceHistoryStats(key, median, recent, Stats.percentile(mins, 0.10), Stats.percentile(maxs, 0.90),
                volume, trend, n, fetchedAt);
    }

    private static double num(JsonObject o, String k, double def) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    static long time(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return 0;
        if (e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
        String s = e.getAsString();
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            // Coflnet times have no zone and are UTC; they may carry fractional seconds.
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0;
        }
    }
}
