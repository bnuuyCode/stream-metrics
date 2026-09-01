package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.FinishedSession;
import io.github.bnuuycode.streammetrics.db.StreamRepository.LiveStats;
import io.github.bnuuycode.streammetrics.db.StreamRepository.OpenSession;
import io.github.bnuuycode.streammetrics.db.StreamRepository.SessionGroup;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable.LiveSnapshot;
import io.github.bnuuycode.streammetrics.metrics.MonthlySummary;
import io.github.bnuuycode.streammetrics.web.ApiResponse;
import io.github.bnuuycode.streammetrics.web.FreshnessCache;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * What the dashboard needs to answer "am I on air, and how is it going?".
 *
 * <p>Combines two sources that must not be confused: whether Twitch says the
 * channel is live <em>right now</em> (asked on demand), and the running totals
 * of the current session (read from the samples this application has been
 * collecting). The first is a live read; the second is history being written as
 * it happens.
 */
public final class TwitchLiveService {

    private final TwitchSession session;
    private final StreamRepository streams;

    /** Shorter window than the metrics cache: on air status changes faster. */
    private final FreshnessCache cache = new FreshnessCache(
            Duration.ofSeconds(30), Duration.ofMinutes(10), TwitchApiException::describe);

    public TwitchLiveService(TwitchSession session, StreamRepository streams) {
        this.session = session;
        this.streams = streams;
    }

    public ApiResponse<LiveInfo> current() {
        Optional<StoredAccount> account = session.account();
        if (account.isEmpty()) {
            return ApiResponse.error("No Twitch account connected", Instant.now());
        }

        StoredAccount stored = account.get();

        // The cache holds the raw reading from Twitch; map turns it into what
        // the dashboard needs while keeping the timestamp and status that
        // reading actually earned.
        return cache.<Optional<LiveSnapshot>>read("stream", () -> {
            String token = session.accessToken(stored);
            return session.client().currentStream(stored.externalId(), token);
        }).map(stream -> describe(stored, stream));
    }

    /**
     * The last few finished broadcasts.
     *
     * <p>This is the historical path (DECISIONS.md § 4): read from our own
     * database, which is the only place this information exists. Twitch keeps no
     * such record — every row here is one the sampler wrote.
     *
     * <p>Still wrapped in a freshness envelope, even though a local read cannot
     * really go stale. Consistency matters more than the exception: there is no
     * endpoint in this application that answers without saying when.
     */
    public ApiResponse<List<HistoryEntry>> recent(int limit) {
        Instant now = Instant.now();

        return session.account()
                .map(account -> ApiResponse.ok(
                        streams.findRecentGroups(account.id(), limit).stream()
                                .map(TwitchLiveService::toHistoryEntry)
                                .toList(),
                        now))
                .orElseGet(() -> ApiResponse.error("No Twitch account connected", now));
    }

    private static HistoryEntry toHistoryEntry(SessionGroup group) {
        return new HistoryEntry(
                group.id(),
                group.title(),
                group.category(),
                iso(group.startedAt()),
                iso(group.endedAt()),
                group.onAirSeconds(),
                group.peakViewers(),
                group.avgViewers(),
                group.parts(),
                group.status(),
                group.endSource(),
                MonthlySummary.Coverage.of(group.sampleCount(), group.expectedSamples()));
    }

    /**
     * One line of the history.
     *
     * <p>Carries its own coverage, which is the point: a total is only as
     * trustworthy as the sampling behind it, and that varies from one broadcast
     * to the next. Showing the figure without it invites reading an estimate as
     * a measurement.
     *
     * @param onAirSeconds time actually broadcasting, excluding any break
     *                     between merged parts
     * @param parts        more than one means these broadcasts were merged by
     *                     hand
     * @param status       SETTLING while the figures may still move, FINAL once
     *                     they will not
     * @param endSource    VOD when the platform's own archive supplied the end
     *                     time, SAMPLES when it came from our last reading
     */
    public record HistoryEntry(
            long id,
            String title,
            String category,
            String startedAt,
            String endedAt,
            long onAirSeconds,
            Long peakViewers,
            Double avgViewers,
            int parts,
            String status,
            String endSource,
            MonthlySummary.Coverage coverage) {
    }

    private LiveInfo describe(StoredAccount account, Optional<LiveSnapshot> stream) {
        if (stream.isPresent()) {
            LiveSnapshot live = stream.get();

            // Peak comes from our own samples, not from Twitch — Twitch does not
            // report it. It only exists because the sampler has been writing it
            // down. Zero samples so far is normal for the first minute or so: the
            // sampler polls once a minute, so a broadcast that just started has
            // not been measured yet.
            LiveStats stats = streams.findOpenSession(account.id())
                    .map(OpenSession::id)
                    .map(streams::currentStats)
                    .orElse(new LiveStats(0, 0));

            return new LiveInfo(
                    true,
                    new Current(
                            live.title(),
                            live.category(),
                            iso(live.startedAt()),
                            live.viewers(),
                            Math.max(stats.peakViewers(), live.viewers()),
                            stats.sampleCount()),
                    lastFinished(account));
        }

        return new LiveInfo(false, null, lastFinished(account));
    }

    private Previous lastFinished(StoredAccount account) {
        return streams.findLastFinishedSession(account.id())
                .map(this::toPrevious)
                .orElse(null);
    }

    private Previous toPrevious(FinishedSession finished) {
        return new Previous(
                finished.title(),
                finished.category(),
                iso(finished.startedAt()),
                iso(finished.endedAt()),
                finished.peakViewers(),
                finished.avgViewers());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /**
     * @param live     whether Twitch reports the channel on air right now
     * @param current  details of the ongoing broadcast, null when off air
     * @param previous the last finished broadcast, null if there has never been
     *                 one. Kept even while live, so the card can show what came
     *                 before.
     */
    public record LiveInfo(boolean live, Current current, Previous previous) {
    }

    /**
     * @param peakViewers highest value the sampler has recorded this session.
     *                    Never lower than the current count, so the card cannot
     *                    show a peak beneath the number right next to it during
     *                    the minutes before the first sample lands.
     */
    public record Current(
            String title,
            String category,
            String startedAt,
            long viewers,
            long peakViewers,
            int sampleCount) {
    }

    public record Previous(
            String title,
            String category,
            String startedAt,
            String endedAt,
            Long peakViewers,
            Double avgViewers) {
    }
}
