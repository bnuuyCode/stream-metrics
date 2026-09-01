package io.github.bnuuycode.streammetrics.collector;

import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.ExistingSession;
import io.github.bnuuycode.streammetrics.metrics.ArchiveVerifiable;
import io.github.bnuuycode.streammetrics.metrics.CollectionException;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a broadcast's provisional figures into final ones.
 *
 * <p>A session closes as SETTLING rather than finished, because the last samples
 * of a broadcast are unreliable: Twitch keeps listing a stream for some minutes
 * after it actually ends, and those trailing readings describe something that
 * was already over. They stretch the duration and drag the average down.
 *
 * <p>No amount of cleverness on this side can tell a ghost sample from a real
 * one. The platform's own archive can — it carries the duration of the broadcast
 * according to the platform itself. So this job waits, asks, and settles.
 *
 * <p>When there is no archive to ask, the session still settles, on the evidence
 * collected here, and says so. {@code end_source} records which of the two
 * produced the number, because a duration nobody can trace to how it was
 * obtained is a duration nobody can check.
 */
public final class SettleJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SettleJob.class);

    /**
     * How long to leave a broadcast alone before settling it.
     *
     * <p>Two things need this time. Twitch stops reporting the stream as live,
     * and the archive appears — which does not happen the instant a broadcast
     * ends. Settling too early would mean asking before there is anything to
     * ask about, and then never asking again.
     */
    static final Duration SETTLE_AFTER = Duration.ofMinutes(20);

    private final List<Archivable> archivable;
    private final StreamRepository streams;

    public SettleJob(List<Archivable> archivable, StreamRepository streams) {
        this.archivable = archivable;
        this.streams = streams;
    }

    @Override
    public void run() {
        Instant cutoff = Instant.now().minus(SETTLE_AFTER);

        for (Archivable entry : archivable) {
            Optional<Long> accountId = entry.provider().accountId();
            if (accountId.isEmpty()) {
                continue;
            }

            for (ExistingSession session : streams.findSettling(accountId.get(), cutoff)) {
                try {
                    settle(entry, session);
                } catch (RuntimeException e) {
                    // One session that cannot be settled must not stop the rest,
                    // and must not kill the scheduled task.
                    log.error("Could not settle session {}", session.streamId(), e);
                }
            }
        }
    }

    private void settle(Archivable entry, ExistingSession session) {
        Optional<Duration> archived;

        try {
            archived = entry.archive().archivedDuration(session.streamId());
        } catch (CollectionException e) {
            // Could not ask, which is not the same as there being no archive.
            // Left SETTLING so the next run tries again — settling now would
            // freeze a provisional figure and call it final.
            log.warn("Could not check the archive for {} [{}] — leaving it settling",
                    session.streamId(), e.kind());
            return;
        }

        if (archived.isEmpty()) {
            // No archive: the channel may not keep them. Settle on our own
            // evidence and record that this is what happened.
            streams.finalise(session.id(), session.endedAt(), "SAMPLES");
            log.info("Session {} settled from samples — no archive to check against",
                    session.streamId());
            return;
        }

        Instant realEnd = session.startedAt().plus(archived.get());
        long correctionSeconds = Duration.between(realEnd, session.endedAt()).getSeconds();

        streams.finalise(session.id(), realEnd, "VOD");

        if (correctionSeconds > 0) {
            log.info("Session {} settled from the archive — {}s of ghost tail discarded",
                    session.streamId(), correctionSeconds);
        } else {
            log.info("Session {} settled from the archive", session.streamId());
        }
    }

    /**
     * A provider that can also be checked against the platform's own archive.
     *
     * <p>Paired here rather than demanded as one type, so platforms that keep no
     * archives are not forced to pretend they do.
     */
    public record Archivable(MetricsProvider provider, ArchiveVerifiable archive) {
    }
}
