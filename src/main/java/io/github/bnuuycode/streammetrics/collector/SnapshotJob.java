package io.github.bnuuycode.streammetrics.collector;

import io.github.bnuuycode.streammetrics.db.CollectionLog;
import io.github.bnuuycode.streammetrics.db.SnapshotRepository;
import io.github.bnuuycode.streammetrics.metrics.CollectionException;
import io.github.bnuuycode.streammetrics.metrics.MetricSample;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Writes the daily history that the platforms refuse to keep.
 *
 * <p>Knows nothing about any particular platform: it walks a list of
 * {@link MetricsProvider} and records whatever each one reports.
 *
 * <p><strong>Runs every hour, not once a day.</strong> The obvious design is to
 * fire at some fixed time — but this runs on a personal machine that is off at
 * night, closed at random, and rebooted mid-afternoon. A job scheduled for
 * 23:00 simply never runs, and the gap appears with no explanation.
 *
 * <p>Hourly with an upsert solves all of it at once: whenever the machine is on,
 * today's row exists; every later run of the same day overwrites it, so the
 * stored value converges on where the day actually ended up; and there is no
 * midnight edge case to get wrong. Twenty-four calls a day is nothing.
 */
public final class SnapshotJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);

    private final List<MetricsProvider> providers;
    private final SnapshotRepository snapshots;
    private final CollectionLog runs;
    private final ZoneId zone;

    public SnapshotJob(List<MetricsProvider> providers,
                       SnapshotRepository snapshots,
                       CollectionLog runs,
                       ZoneId zone) {
        this.providers = providers;
        this.snapshots = snapshots;
        this.runs = runs;
        this.zone = zone;
    }

    @Override
    public void run() {
        for (MetricsProvider provider : providers) {
            try {
                collect(provider);
            } catch (RuntimeException e) {
                // One broken platform must never stop the others. This job runs
                // unattended; an exception escaping here would kill the
                // scheduled task permanently and silently.
                log.error("Snapshot failed for {}", provider.platform(), e);
            }
        }
    }

    private void collect(MetricsProvider provider) {
        var accountId = provider.accountId();
        if (accountId.isEmpty()) {
            // Nobody connected this platform. Not a failure, and nothing to
            // attribute a run to.
            return;
        }

        long runId = runs.start(accountId.get(), CollectionLog.DAILY_SNAPSHOT);

        try {
            // One reading of the clock for the whole batch, so every metric
            // collected together shares the same captured_at.
            Instant capturedAt = Instant.now();
            LocalDate day = LocalDate.now(zone);

            List<MetricSample> samples = provider.snapshot();

            for (MetricSample sample : samples) {
                snapshots.saveSnapshot(accountId.get(), sample.key(), day, sample.value(), capturedAt);
            }

            runs.succeeded(runId);
            log.debug("Snapshot for {}: {} metric(s) on {}", provider.platform(), samples.size(), day);

        } catch (CollectionException e) {
            // The failure is written down with its category, so a gap in the
            // chart can later be explained rather than merely noticed
            // (DECISIONS.md § 6.3).
            runs.failed(runId, e.kind(), e.getMessage());
            log.warn("Snapshot for {} failed [{}]: {}", provider.platform(), e.kind(), e.getMessage());

        } catch (RuntimeException e) {
            runs.failed(runId, CollectionException.ErrorKind.UNKNOWN, String.valueOf(e));
            throw e;
        }
    }
}
