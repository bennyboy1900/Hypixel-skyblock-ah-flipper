package dev.skytrade.util;

import java.util.Arrays;
import java.util.Collection;

/** Robust statistics helpers. All methods tolerate empty input and never mutate arguments. */
public final class Stats {
    private Stats() {}

    public static double median(double[] values) {
        return percentile(values, 0.5);
    }

    public static double median(Collection<Double> values) {
        return median(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /** Linear-interpolated percentile, p in [0,1]. Returns NaN for empty input. */
    public static double percentile(double[] values, double p) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1) return sorted[0];
        double pos = clamp(p, 0, 1) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        double frac = pos - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    /** Median absolute deviation, scaled to be a consistent estimator of the standard deviation. */
    public static double mad(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double med = median(values);
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - med);
        return 1.4826 * median(dev);
    }

    /** Robust z-score of x relative to the sample: (x - median) / MAD. 0 when undefined. */
    public static double robustZ(double x, double[] sample) {
        if (sample == null || sample.length < 3) return 0;
        double m = median(sample);
        double s = mad(sample);
        if (!(s > 1e-12)) {
            // Degenerate distribution: fall back to relative deviation scaled heavily.
            if (m == 0) return 0;
            return (x - m) / (Math.abs(m) * 0.01);
        }
        return (x - m) / s;
    }

    /**
     * Weighted median: sorts by value and returns the first value at which the cumulative weight
     * reaches half of the total. Weights <= 0 are ignored.
     */
    public static double weightedMedian(double[] values, double[] weights) {
        if (values.length == 0 || values.length != weights.length) return Double.NaN;
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(values[a], values[b]));
        double total = 0;
        for (double w : weights) if (w > 0) total += w;
        if (total <= 0) return median(values);
        double acc = 0;
        for (int i : idx) {
            if (weights[i] <= 0) continue;
            acc += weights[i];
            if (acc >= total / 2) return values[i];
        }
        return values[idx[idx.length - 1]];
    }

    public static double mean(double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double s = 0;
        for (double v : values) s += v;
        return s / values.length;
    }

    public static double stddev(double[] values) {
        if (values == null || values.length < 2) return 0;
        double m = mean(values);
        double s = 0;
        for (double v : values) s += (v - m) * (v - m);
        return Math.sqrt(s / (values.length - 1));
    }

    /** Standard deviation of log returns between consecutive samples (a volatility proxy). */
    public static double logReturnVolatility(double[] series) {
        if (series == null || series.length < 3) return 0;
        double[] r = new double[series.length - 1];
        int n = 0;
        for (int i = 1; i < series.length; i++) {
            if (series[i] > 0 && series[i - 1] > 0) r[n++] = Math.log(series[i] / series[i - 1]);
        }
        return stddev(Arrays.copyOf(r, n));
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Maps a non-negative value onto [0,1) smoothly; x == half gives 0.5. */
    public static double saturate(double x, double half) {
        if (x <= 0 || half <= 0) return 0;
        return x / (x + half);
    }

    public static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
