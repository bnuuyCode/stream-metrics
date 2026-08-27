package io.github.bnuuycode.streammetrics.collector;

import io.github.bnuuycode.streammetrics.db.CollectionLog;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.OpenSession;
import io.github.bnuuycode.streammetrics.metrics.CollectionException;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable.LiveSnapshot;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Samples viewer counts while a broadcast is on air.
 *
 * <p>This is the part that builds numbers Twitch keeps to itself. Average and
 * peak viewers, stream duration and history do not exist in any API — they only
 * exist if something writes them down minute by minute. That something is this.
 *
 * <p><strong>Adaptive cadence.</strong> Every minute while live, every five
 * minutes while off air. High resolution where the chart needs it, quiet the
 * rest of the day.
 *
 * <p><strong>Grace period.</strong> Twitch issues a <em>new stream id</em> every
 * time a broadcast drops and comes back — it treats the two halves of an evening
 * as unrelated streams. Following that lead would shatter one session into
 * several rows and make every average and duration computed from them wrong.
 *
 * <p>So the grace period governs both ways a broadcast can disappear:
 *
 * <ul>
 *   <li>a poll finding nothing does not end the session
 *   <li>a poll finding an <em>unfamiliar stream id</em> while a session is open
 *       is treated as a reconnection, not a new broadcast
 * </ul>
 *
 * <p>Either way the decision comes from the same question: how long since the
 * last sample? Inside {@link #GRACE}, same session. Beyond it, a new one.
 *
 * <p>The session's {@code ended_at} is always the last sample actually taken,
 * never the moment the absence was noticed — the broadcast stopped when the
 * numbers stopped. The samples keep a hole where the outage was, which is the
 * honest record: absence of a sample is absence of a sample, exactly as it is
 * for daily snapshots.
 */
public final class LiveSampler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(LiveSampler.class);

    static final Duration WHILE_LIVE = Duration.ofMinutes(1);
    static final Duration WHILE_OFFLINE = Duration.ofMinutes(5);

    /** How long a broadcast may vanish before it counts as over. */
    private static final Duration GRACE = Duration.ofMinutes(10);

    private final List<Tracked> tracked;
    private final StreamRepository streams;
    private final CollectionLog runs;

    private volatile boolean anyLive = false;

    public LiveSampler(List<Tracked> tracked, StreamRepository streams, CollectionLog runs) {
        this.tracked = tracked;
        this.streams = streams;
        this.runs = runs;
    }

    /** How long to wait before the next poll. */
    public Duration nextDelay() {
        return anyLive ? WHILE_LIVE : WHILE_OFFLINE;
    }

    @Override
    public void run() {
        boolean live = false;

        for (Tracked entry : tracked) {
            try {
                live |= poll(entry);
            } catch (RuntimeException e) {
                // Never let one platform's failure stop the loop or kill the
                // scheduled task.
                log.error("Live sampling failed for {}", entry.provider().platform(), e);
            }
        }

        anyLive = live;
    }

    /**
     * One poll of one platform, recorded in {@link CollectionLog} whatever the
     * outcome.
     *
     * <p>Yes, that is a row per minute while live and one every five minutes
     * otherwise — a few hundred a day, a handful of kilobytes, pruned at ninety
     * days. Worth every byte: without it, an empty stretch of samples is
     * indistinguishable from a collector that never woke up, and "the data is
     * missing and I cannot tell you why" is the one answer this project is not
     * allowed to give.
     */
    private boolean poll(Tracked entry) {
        Optional<Long> accountId = entry.provider().accountId();
        if (accountId.isEmpty()) {
            // Nobody connected this platform, so there is no account to
            // attribute an attempt to.
            return false;
        }

        long runId = runs.start(accountId.get(), CollectionLog.LIVE_SAMPLE);

        Optional<LiveSnapshot> stream;
        try {
            stream = entry.live().currentStream();
        } catch (CollectionException e) {
            // Could not ask, which is not the same as "offline". Leaving the
            // session open here is the whole reason currentStream throws instead
            // of returning empty when the network fails.
            runs.failed(runId, e.kind(), e.getMessage());
            log.warn("Could not check live status for {} [{}]", entry.provider().platform(), e.kind());
            return anyLive;
        }

        try {
            boolean live = stream.isPresent()
                    ? recordLive(accountId.get(), stream.get())
                    : closeIfSilentTooLong(accountId.get());

            runs.succeeded(runId);
            return live;

        } catch (RuntimeException e) {
            runs.failed(runId, CollectionException.ErrorKind.UNKNOWN, String.valueOf(e));
            throw e;
        }
    }

    private boolean recordLive(long accountId, LiveSnapshot snapshot) {
        Optional<OpenSession> open = streams.findOpenSession(accountId);
        long sessionId;

        if (open.isEmpty()) {
            log.info("Live detected: \"{}\" ({})", snapshot.title(), snapshot.category());
            sessionId = streams.openSession(
                    accountId, snapshot.streamId(), snapshot.startedAt(),
                    snapshot.title(), snapshot.category());

        } else if (streams.hasSegment(open.get().id(), snapshot.streamId())) {
            // Same segment as last poll. The ordinary case.
            sessionId = open.get().id();

        } else {
            // A stream id we have not seen, while a session is still open.
            //
            // Twitch issues a new id every time a broadcast drops and returns,
            // so this is ambiguous: either the connection blinked, or a genuinely
            // new broadcast started. The last sample decides.
            sessionId = open.get().id();
            Instant lastSeen = streams.lastSampleAt(sessionId).orElse(open.get().startedAt());

            if (Duration.between(lastSeen, Instant.now()).compareTo(GRACE) < 0) {
                // Back inside the grace window: same evening, new segment. The
                // previous segment is closed at its last sample, so the outage
                // is recorded rather than smoothed away.
                streams.closeOpenSegments(sessionId, lastSeen);
                streams.addSegment(sessionId, snapshot.streamId(), snapshot.startedAt());
                log.info("Stream reconnected as {} — continuing the same session", snapshot.streamId());

            } else {
                // Gone long enough to count as a different broadcast.
                streams.closeSession(sessionId, lastSeen);
                log.info("Session {} closed; new broadcast {} starting",
                        open.get().streamId(), snapshot.streamId());

                sessionId = streams.openSession(
                        accountId, snapshot.streamId(), snapshot.startedAt(),
                        snapshot.title(), snapshot.category());
            }
        }

        streams.addSample(sessionId, Instant.now(), snapshot.viewers());
        streams.updateSessionInfo(sessionId, snapshot.title(), snapshot.category());
        return true;
    }

    private boolean closeIfSilentTooLong(long accountId) {
        Optional<OpenSession> open = streams.findOpenSession(accountId);
        if (open.isEmpty()) {
            return false;
        }

        Instant lastSeen = streams.lastSampleAt(open.get().id()).orElse(open.get().startedAt());

        if (Duration.between(lastSeen, Instant.now()).compareTo(GRACE) < 0) {
            // Still inside the grace period. Report as live so the poller keeps
            // its fast cadence and catches the return quickly.
            log.debug("Stream missing but within grace period");
            return true;
        }

        // ended_at is the last sample, not now: the broadcast stopped when the
        // numbers stopped, not when this loop happened to notice.
        streams.closeSession(open.get().id(), lastSeen);
        log.info("Session {} closed", open.get().streamId());
        return false;
    }

    /**
     * Closes sessions abandoned by an application that died mid-broadcast,
     * so nothing shows as live forever (DECISIONS.md § 11).
     */
    public void closeAbandonedSessions() {
        Instant cutoff = Instant.now().minus(GRACE);

        for (OpenSession session : streams.findStaleOpenSessions(cutoff)) {
            Instant endedAt = streams.lastSampleAt(session.id()).orElse(session.startedAt());
            streams.closeSession(session.id(), endedAt);
            log.info("Closed abandoned session {} (ended {})", session.streamId(), endedAt);
        }
    }

    /**
     * A provider that can do both jobs.
     *
     * <p>Pairing the two interfaces here rather than demanding one type that
     * implements both keeps platforms without broadcasts free of a capability
     * they do not have.
     */
    public record Tracked(MetricsProvider provider, LiveTrackable live) {
    }
}
