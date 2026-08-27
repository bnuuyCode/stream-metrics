package io.github.bnuuycode.streammetrics.metrics;

/**
 * One number read from a platform, before anything decides what to do with it.
 *
 * <p>No timestamp here: the collector stamps every sample from a single reading
 * of the clock, so that all of a day's metrics share one captured_at instead of
 * each carrying a slightly different one from whenever its API call returned.
 */
public record MetricSample(MetricKey key, long value) {

    public static MetricSample of(MetricKey key, long value) {
        return new MetricSample(key, value);
    }
}
