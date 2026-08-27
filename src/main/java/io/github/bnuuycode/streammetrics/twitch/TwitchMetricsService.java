package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.web.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The live path from DECISIONS.md § 4: current numbers, fetched on demand,
 * every one of them stamped with its own freshness.
 *
 * <p>Nothing here ever touches the snapshots table. Those exist to draw charts
 * of the past, never to fill a hole in the present.
 */
public final class TwitchMetricsService {

    private static final Logger log = LoggerFactory.getLogger(TwitchMetricsService.class);

    /**
     * How long a fetched value counts as current.
     *
     * <p>This is not a performance cache. It exists so that opening the
     * dashboard in three tabs does not fire three rounds of API calls and walk
     * into Twitch's rate limit.
     */
    private static final Duration FRESH_FOR = Duration.ofSeconds(60);

    /**
     * How old a cached value may get before it stops being shown at all.
     *
     * <p>Between 60 seconds and 15 minutes a value is served marked STALE:
     * visible, dimmed, timestamped. Past 15 minutes it becomes an outright
     * error, because at some point "old" stops being informative and starts
     * being misleading.
     */
    private static final Duration STALE_LIMIT = Duration.ofMinutes(15);

    private final TwitchSession session;
    private final Map<String, Sample> cache = new ConcurrentHashMap<>();

    public TwitchMetricsService(TwitchSession session) {
        this.session = session;
    }

    public Metrics current() {
        Instant now = Instant.now();

        Optional<StoredAccount> account = session.account();
        if (account.isEmpty()) {
            return Metrics.unavailable("No Twitch account connected", now);
        }

        String accessToken;
        try {
            accessToken = session.accessToken(account.get());
        } catch (RuntimeException e) {
            // A dead token kills every metric at once, so it is reported once
            // here rather than repeated identically on each card.
            log.warn("Could not obtain a usable Twitch token", e);
            return Metrics.unavailable(explain(e), now);
        }

        String broadcasterId = account.get().externalId();

        // Fetched independently on purpose: a channel that is not an affiliate
        // gets a 400 on subscribers, and that must not take the follower count
        // down with it.
        return new Metrics(
                metric("followers", () -> session.client().followers(broadcasterId, accessToken)),
                metric("subscribers", () -> session.client().subscribers(broadcasterId, accessToken)));
    }

    /**
     * Returns a value with the strongest honest label it qualifies for.
     *
     * <p>Note the fallback to cache is <em>not</em> the forbidden one. Serving a
     * three-minute-old number clearly marked STALE is the design; quietly
     * serving last night's database snapshot dressed as current is what
     * DECISIONS.md § 4 rules out.
     */
    private ApiResponse<Long> metric(String key, LongSupplier fetch) {
        Instant now = Instant.now();
        Sample cached = cache.get(key);

        if (cached != null && cached.age(now).compareTo(FRESH_FOR) < 0) {
            return ApiResponse.ok(cached.value(), cached.fetchedAt());
        }

        try {
            long value = fetch.getAsLong();
            cache.put(key, new Sample(value, now));
            return ApiResponse.ok(value, now);

        } catch (RuntimeException e) {
            log.warn("Failed to read Twitch metric '{}'", key, e);

            if (cached != null && cached.age(now).compareTo(STALE_LIMIT) < 0) {
                return ApiResponse.stale(cached.value(), cached.fetchedAt());
            }

            return ApiResponse.error(explain(e), now);
        }
    }

    private static String explain(RuntimeException e) {
        if (e instanceof TwitchApiException twitch) {
            return twitch.explain();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** One reading, with the moment it was taken. */
    private record Sample(long value, Instant fetchedAt) {
        Duration age(Instant now) {
            return Duration.between(fetchedAt, now);
        }
    }

    /**
     * The Twitch card's payload.
     *
     * <p>Each metric carries its own envelope rather than sharing one for the
     * whole card. That way a partial failure shows exactly which number is
     * missing, instead of greying out everything and hiding which part broke.
     */
    public record Metrics(ApiResponse<Long> followers, ApiResponse<Long> subscribers) {

        public static Metrics unavailable(String reason, Instant now) {
            return new Metrics(
                    ApiResponse.error(reason, now),
                    ApiResponse.error(reason, now));
        }
    }
}
