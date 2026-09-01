package io.github.bnuuycode.streammetrics.collector;

import io.github.bnuuycode.streammetrics.db.CollectionLog;
import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.ExistingSession;
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
 * <p><strong>One poll a minute, always.</strong> Slowing down while off air used
 * to look thrifty and cost up to five minutes of every broadcast, because a
 * stream that starts between two polls is unsampled until the next one — and
 * nobody can measure the past. See {@link #POLL_EVERY}.
 *
 * <p><strong>One session is one Twitch stream id.</strong> Twitch issues a new
 * id every time a broadcast drops and returns, so an evening interrupted by a
 * dead connection arrives here as several ids. This class no longer decides
 * whether those belong together: a dropped connection and a deliberate restart
 * are identical through the API, and only the person knows which happened. When
 * two broadcasts sit close together it records a suggestion and moves on
 * (DECISIONS.md § 17).
 *
 * <p><strong>The grace period still exists</strong>, for a narrower job: a poll
 * that finds nothing does not end a session immediately, because Twitch can drop
 * a stream from its listing briefly and put it back. Only continuous silence
 * ends a broadcast, and its {@code ended_at} is the last sample actually taken —
 * the numbers stopped when they stopped, not when this loop noticed.
 */
public final class LiveSampler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(LiveSampler.class);

    /**
     * One poll a minute, on air or not.
     *
     * <p>This used to slow to five minutes while off air, to be polite about
     * the API. That politeness cost up to five minutes of every broadcast: the
     * gap between starting a stream and the collector noticing is time nobody
     * can ever go back and measure, because no platform keeps per-minute viewer
     * counts for anyone to query later. That is the whole reason this
     * application exists.
     *
     * <p>And the saving was imaginary. Twitch's limit is per minute, not per
     * day, so one call a minute spends about a tenth of one percent of it. A
     * real cost in data quality was traded for a rounding error.
     *
     * <p>The gap is now at most one minute, and it is still counted: coverage
     * measures samples taken against the broadcast's real length, so whatever is
     * missed at the start shows up rather than being quietly rounded away.
     */
    static final Duration POLL_EVERY = Duration.ofMinutes(1);

    /**
     * How long a broadcast may vanish from the listing before it counts as over.
     *
     * <p>Two minutes, from Twitch's own behaviour: it tolerates ninety seconds
     * of lost connection before treating the return as a new broadcast. Waiting
     * much past that is waiting for something that will not happen.
     *
     * <p>It used to be ten, from a time when this number decided whether an
     * evening stayed in one piece. It no longer does. Merging is a person's
     * decision now, and a broadcast that returns under the same id is reopened
     * whether or not its session had already closed — so closing early costs
     * nothing but a row that briefly reads as finished.
     *
     * <p>What it did cost was ten minutes of staring at a dashboard after
     * every broadcast, waiting for it to appear.
     */
    private static final Duration GRACE = Duration.ofMinutes(2);

    /**
     * How close two broadcasts must be for the collector to ask whether they are
     * one.
     *
     * <p>Ten minutes, and deliberately <em>not</em> tied to {@link #GRACE} any
     * more. The two used to share a number and answer different questions, which
     * meant shortening one would silently narrow the other: broadcasts five
     * minutes apart would stop being offered at all, and nobody would notice
     * until a night went unmerged.
     *
     * <p>They measure different things. Grace asks how long Twitch might still
     * resume the same broadcast — a fact about Twitch, ninety seconds. This asks
     * how far apart two broadcasts can be and still plausibly be one evening — a
     * judgement about broadcasting, which the streamer set at ten minutes.
     */
    private static final Duration SUGGEST_WITHIN = Duration.ofMinutes(10);

    private final List<Tracked> tracked;
    private final StreamRepository streams;
    private final MergeSuggestionRepository suggestions;
    private final CollectionLog runs;

    public LiveSampler(List<Tracked> tracked,
                       StreamRepository streams,
                       MergeSuggestionRepository suggestions,
                       CollectionLog runs) {
        this.tracked = tracked;
        this.streams = streams;
        this.suggestions = suggestions;
        this.runs = runs;
    }

    /** How long to wait before the next poll. */
    public Duration nextDelay() {
        return POLL_EVERY;
    }

    @Override
    public void run() {
        for (Tracked entry : tracked) {
            try {
                poll(entry);
            } catch (RuntimeException e) {
                // Never let one platform's failure stop the loop or kill the
                // scheduled task.
                log.error("Live sampling failed for {}", entry.provider().platform(), e);
            }
        }
    }

    /**
     * One poll of one platform, recorded in {@link CollectionLog} whatever the
     * outcome.
     *
     * <p>Yes, that is a row every minute of every day — around fourteen hundred,
     * a few megabytes before the ninety-day prune. Worth every byte: without it,
     * an empty stretch of samples is indistinguishable from a collector that
     * never woke up, and "the data is missing and I cannot tell you why" is the
     * one answer this project is not allowed to give.
     */
    private void poll(Tracked entry) {
        Optional<Long> accountId = entry.provider().accountId();
        if (accountId.isEmpty()) {
            // Nobody connected this platform, so there is no account to
            // attribute an attempt to.
            return;
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
            return;
        }

        try {
            if (stream.isPresent()) {
                recordLive(accountId.get(), stream.get());
            } else {
                closeIfSilentTooLong(accountId.get());
            }

            runs.succeeded(runId);

        } catch (RuntimeException e) {
            runs.failed(runId, CollectionException.ErrorKind.UNKNOWN, String.valueOf(e));
            throw e;
        }
    }

    private void recordLive(long accountId, LiveSnapshot snapshot) {
        Optional<OpenSession> open = streams.findOpenSession(accountId);

        // Same broadcast as the last poll. The ordinary case, and the only one
        // that needs no decision at all.
        if (open.isPresent() && open.get().streamId().equals(snapshot.streamId())) {
            record(open.get().id(), snapshot);
            return;
        }

        // A different id is on air, so whatever was open has ended — we simply
        // never saw it leave the listing.
        Long previousId = null;
        Instant previousEnd = null;

        if (open.isPresent()) {
            OpenSession previous = open.get();
            previousId = previous.id();
            previousEnd = streams.lastSampleAt(previousId).orElse(previous.startedAt());
            streams.closeSession(previousId, previousEnd);
            log.info("Session {} closed; a different broadcast is on air", previous.streamId());
        }

        long sessionId = openOrReopen(accountId, snapshot);

        // Nothing was open, so look at whatever finished last instead. A
        // broadcast that ended fifteen minutes ago and one starting now may
        // still be the same evening.
        if (previousId == null) {
            Optional<ExistingSession> last = streams.findMostRecentFinished(accountId);
            if (last.isPresent() && last.get().id() != sessionId) {
                previousId = last.get().id();
                previousEnd = last.get().endedAt();
            }
        }

        maybeSuggestMerge(accountId, sessionId, previousId, previousEnd, snapshot.startedAt());

        record(sessionId, snapshot);
    }

    /**
     * Opens a session for this stream id, or puts an existing one back on air.
     *
     * <p>A session can be closed while its broadcast is in fact still running —
     * the application being shut down for a while is enough. When the same id
     * turns up again it must be recognised as the session it is, not duplicated
     * into a second one that discards the samples already collected.
     */
    private long openOrReopen(long accountId, LiveSnapshot snapshot) {
        Optional<ExistingSession> existing =
                streams.findSessionByStreamId(accountId, snapshot.streamId());

        if (existing.isPresent()) {
            if (existing.get().isClosed()) {
                streams.reopenSession(existing.get().id());
                log.info("Session {} was closed but is live again — reopened", snapshot.streamId());
            }
            return existing.get().id();
        }

        log.info("Live detected: \"{}\" ({})", snapshot.title(), snapshot.category());
        return streams.openSession(
                accountId, snapshot.streamId(), snapshot.startedAt(),
                snapshot.title(), snapshot.category());
    }

    /**
     * Records that two broadcasts might be one, and stops there.
     *
     * <p>Deliberately does not merge. The gap between them is the only evidence
     * available, and a gap does not distinguish a dropped connection from
     * someone ending a stream to start another. The suggestion carries the gap
     * so the person can judge it with the context the collector does not have.
     */
    private void maybeSuggestMerge(long accountId, long sessionId,
                                   Long previousId, Instant previousEnd, Instant startedAt) {
        if (previousId == null || previousEnd == null || previousId == sessionId) {
            return;
        }

        long gap = Duration.between(previousEnd, startedAt).getSeconds();

        // A negative gap means the new broadcast reportedly began before the
        // previous one ended — overlapping ids, which should not happen and is
        // not something to guess about.
        if (gap < 0 || gap > SUGGEST_WITHIN.toSeconds()) {
            return;
        }

        suggestions.suggest(accountId, sessionId, previousId, gap);
        log.info("Two broadcasts {}s apart — merge suggested, not applied", gap);
    }

    private void record(long sessionId, LiveSnapshot snapshot) {
        streams.addSample(sessionId, Instant.now(), snapshot.viewers());
        streams.updateSessionInfo(sessionId, snapshot.title(), snapshot.category());
    }

    private void closeIfSilentTooLong(long accountId) {
        Optional<OpenSession> open = streams.findOpenSession(accountId);
        if (open.isEmpty()) {
            return;
        }

        Instant lastSeen = streams.lastSampleAt(open.get().id()).orElse(open.get().startedAt());

        if (Duration.between(lastSeen, Instant.now()).compareTo(GRACE) < 0) {
            // Still inside the grace period. Twitch drops a stream from its
            // listing briefly and puts it back, and ending a broadcast on the
            // strength of one such blink would be wrong permanently.
            log.debug("Stream missing but within grace period");
            return;
        }

        // ended_at is the last sample, not now: the broadcast stopped when the
        // numbers stopped, not when this loop happened to notice.
        streams.closeSession(open.get().id(), lastSeen);
        log.info("Session {} closed", open.get().streamId());
    }

    /**
     * Closes sessions abandoned by an application that died mid-broadcast,
     * so nothing shows as live forever (DECISIONS.md § 11).
     */
    public void closeAbandonedSessions() {
        Instant cutoff = Instant.now().minus(GRACE);

        for (Tracked entry : tracked) {
            Optional<Long> accountId = entry.provider().accountId();
            if (accountId.isEmpty()) {
                continue;
            }

            List<OpenSession> stale = streams.findStaleOpenSessions(accountId.get(), cutoff);
            if (stale.isEmpty()) {
                continue;
            }

            // Ask the platform instead of trusting the clock. A session can look
            // abandoned simply because the application was closed for a while,
            // and the broadcast may still be running.
            Optional<LiveSnapshot> current;
            try {
                current = entry.live().currentStream();
            } catch (CollectionException e) {
                // Could not ask. Leaving a session open is recoverable — the
                // sampler will sort it out on the next poll. Closing a running
                // broadcast is not: its duration and summary would be wrong
                // permanently. When in doubt, do nothing.
                log.warn("Could not verify live status at startup for {} [{}] — leaving {} session(s) open",
                        entry.provider().platform(), e.kind(), stale.size());
                continue;
            }

            String liveStreamId = current.map(LiveSnapshot::streamId).orElse(null);

            for (OpenSession session : stale) {
                if (session.streamId().equals(liveStreamId)) {
                    log.info("Session {} looked abandoned but is still on air — left open",
                            session.streamId());
                    continue;
                }

                Instant endedAt = streams.lastSampleAt(session.id()).orElse(session.startedAt());
                streams.closeSession(session.id(), endedAt);
                log.info("Closed abandoned session {} (ended {})", session.streamId(), endedAt);
            }
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
