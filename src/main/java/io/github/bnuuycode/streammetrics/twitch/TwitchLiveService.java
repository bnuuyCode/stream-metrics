package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.FinishedSession;
import io.github.bnuuycode.streammetrics.db.StreamRepository.LiveStats;
import io.github.bnuuycode.streammetrics.db.StreamRepository.OpenSession;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable.LiveSnapshot;
import io.github.bnuuycode.streammetrics.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(TwitchLiveService.class);

    /** Same window as the other live reads, for the same reason: rate limits. */
    private static final Duration FRESH_FOR = Duration.ofSeconds(30);

    private static final Duration STALE_LIMIT = Duration.ofMinutes(10);

    private final TwitchSession session;
    private final StreamRepository streams;

    private volatile Cached cached;

    public TwitchLiveService(TwitchSession session, StreamRepository streams) {
        this.session = session;
        this.streams = streams;
    }

    public ApiResponse<LiveInfo> current() {
        Instant now = Instant.now();

        Optional<StoredAccount> account = session.account();
        if (account.isEmpty()) {
            return ApiResponse.error("No Twitch account connected", now);
        }

        Cached snapshot = cached;
        if (snapshot != null && age(snapshot, now).compareTo(FRESH_FOR) < 0) {
            return ApiResponse.ok(describe(account.get(), snapshot.stream()), snapshot.fetchedAt());
        }

        try {
            String token = session.accessToken(account.get());
            Optional<LiveSnapshot> stream = session.client()
                    .currentStream(account.get().externalId(), token);

            cached = new Cached(stream, now);
            return ApiResponse.ok(describe(account.get(), stream), now);

        } catch (RuntimeException e) {
            log.warn("Could not read live status", e);

            if (snapshot != null && age(snapshot, now).compareTo(STALE_LIMIT) < 0) {
                return ApiResponse.stale(describe(account.get(), snapshot.stream()), snapshot.fetchedAt());
            }

            String reason = e instanceof TwitchApiException twitch ? twitch.explain() : String.valueOf(e.getMessage());
            return ApiResponse.error(reason, now);
        }
    }

    private LiveInfo describe(StoredAccount account, Optional<LiveSnapshot> stream) {
        if (stream.isPresent()) {
            LiveSnapshot live = stream.get();

            // Peak comes from our own samples, not from Twitch — Twitch does not
            // report it. It only exists because the sampler has been writing it
            // down. Zero samples so far is normal: the sampler polls every five
            // minutes while off air, so it can take a few minutes to notice.
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
    public ApiResponse<List<Previous>> recent(int limit) {
        Instant now = Instant.now();

        return session.account()
                .map(account -> ApiResponse.ok(
                        streams.findRecentSessions(account.id(), limit).stream()
                                .map(this::toPrevious)
                                .toList(),
                        now))
                .orElseGet(() -> ApiResponse.error("No Twitch account connected", now));
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

    private static Duration age(Cached cached, Instant now) {
        return Duration.between(cached.fetchedAt(), now);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private record Cached(Optional<LiveSnapshot> stream, Instant fetchedAt) {
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
