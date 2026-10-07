package dbbench.util;

import java.util.Arrays;

/** Small descriptive-statistics helpers used when printing progress; the CSV keeps every raw value. */
public final class Stats {
    private Stats() {}

    /** Percentile with linear interpolation; {@code sorted} must be ascending and non-empty. */
    public static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double rank = p / 100.0 * (sorted.length - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
    }

    public static double median(double[] values) {
        double[] s = values.clone();
        Arrays.sort(s);
        return percentile(s, 50);
    }

    /** Percentile (nearest rank) of latencies stored as microseconds, returned in milliseconds. */
    public static double percentileMs(int[] sortedMicros, int n, double p) {
        if (n == 0) return Double.NaN;
        int idx = (int) Math.min(n - 1, Math.max(0, Math.ceil(p / 100.0 * n) - 1));
        return sortedMicros[idx] / 1000.0;
    }
}
