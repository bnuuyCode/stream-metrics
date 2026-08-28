package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.metrics.MetricKey;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Writes and reads the daily history.
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

    /**
     * How much a metric moved across a date range.
     *
     * <p>Exact, unlike anything derived from sampling: both ends are numbers
     * Twitch itself reported, and the difference between them is arithmetic.
     * In a monthly summary this is the one figure that needs no caveat.
     *
     * <p>Empty when the range holds fewer than two readings. One point is not a
     * change, and reporting "+0" would state something nobody measured.
     */
    public Optional<Growth> growthBetween(long accountId, MetricKey metric, LocalDate from, LocalDate to) {
        List<Reading> readings = jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT snapshot_date, value
                        FROM metric_snapshot
                        WHERE account_id = :accountId
                          AND metric = :metric
                          AND snapshot_date >= :from
                          AND snapshot_date < :to
                        ORDER BY snapshot_date
                        """)
                .bind("accountId", accountId)
                .bind("metric", metric.key())
                .bind("from", from.toString())
                .bind("to", to.toString())
                .map((rs, ctx) -> new Reading(rs.getString("snapshot_date"), rs.getLong("value")))
                .list());

        if (readings.size() < 2) {
            return Optional.empty();
        }

        Reading first = readings.get(0);
        Reading last = readings.get(readings.size() - 1);

        return Optional.of(new Growth(first.date(), first.value(), last.date(), last.value()));
    }

    private record Reading(String date, long value) {
    }

    /**
     * @param fromDate the first day actually recorded, which may fall later than
     *                 the start of the range. Carried so the interface can say
     *                 "since the 27th" rather than implying a whole month was
     *                 measured when collection only began partway through.
     */
    public record Growth(String fromDate, long fromValue, String toDate, long toValue) {

        public long delta() {
            return toValue - fromValue;
        }
    }
}
