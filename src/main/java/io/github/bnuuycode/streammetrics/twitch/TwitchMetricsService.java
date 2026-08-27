package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.web.ApiResponse;
import io.github.bnuuycode.streammetrics.web.FreshnessCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The live path from DECISIONS.md § 4: current numbers, fetched on demand,
 * every one of them stamped with its own freshness.
 *
 * <p>Nothing here ever touches the snapshots table. Those exist to draw charts
 * of the past, never to fill a hole in the present.
 */
public final class TwitchMetricsService {

    private static final Logger log = LoggerFactory.getLogger(TwitchMetricsService.class);

    private final TwitchSession session;

    /** 60 seconds current, then shown as stale for up to 15 minutes. */
    private final FreshnessCache cache = new FreshnessCache(
            Duration.ofSeconds(60), Duration.ofMinutes(15), TwitchApiException::describe);

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
            return Metrics.unavailable(TwitchApiException.describe(e), now);
        }

        String broadcasterId = account.get().externalId();

        // Fetched independently on purpose: a channel that is not an affiliate
        // gets a 400 on subscribers, and that must not take the follower count
        // down with it.
        return new Metrics(
                cache.read("followers", () -> session.client().followers(broadcasterId, accessToken)),
                cache.read("subscribers", () -> session.client().subscribers(broadcasterId, accessToken)));
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
