package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.metrics.MetricKey;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Writes the daily history.
 *
 * <p>The log of collection attempts lives in {@link CollectionLog}, separately:
 * one class records what was measured, the other records that measuring was
 * attempted. Both the snapshot job and the live sampler need the second, and
 * only one of them needs this.
 */
public final class SnapshotRepository {

    private final Jdbi jdbi;

    public SnapshotRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Records one metric for one day.
     *
     * <p>The upsert is what makes the collector safe to run as often as it
     * likes: a second reading on the same day replaces the first rather than
     * duplicating it, and the most recent value wins — which is also the most
     * accurate account of where that day ended up (DECISIONS.md § 6.6).
     */
    public void saveSnapshot(long accountId, MetricKey metric, LocalDate day, long value, Instant capturedAt) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO metric_snapshot (account_id, metric, snapshot_date, value, captured_at)
                        VALUES (:accountId, :metric, :day, :value, :capturedAt)
                        ON CONFLICT (account_id, metric, snapshot_date) DO UPDATE SET
                            value = excluded.value,
                            captured_at = excluded.captured_at
                        """)
                .bind("accountId", accountId)
                .bind("metric", metric.key())
                .bind("day", day.toString())
                .bind("value", value)
                .bind("capturedAt", DateTimeFormatter.ISO_INSTANT.format(capturedAt))
                .execute());
    }

}
