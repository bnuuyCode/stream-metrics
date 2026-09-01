package io.github.bnuuycode.streammetrics.twitch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Stops the application from arguing with a rate limit.
 *
 * <p>Twitch answers a request that exceeds the quota with 429 and a
 * {@code Ratelimit-Reset} header saying when it will accept work again. Without
 * reading that, the sampler keeps polling every minute, every one of those calls
 * is refused, and the refusals themselves count — the fastest way to stay
 * blocked is to keep asking.
 *
 * <p>So the reset time is remembered and calls are refused locally until it
 * passes. The failure the caller sees is the same either way; the difference is
 * that this one does not dig the hole deeper.
 *
 * <p>Shared by everything that talks to Twitch, because the quota is per
 * application, not per component. Two callers each keeping their own view of it
 * would each discover the block separately.
 */
public final class RateLimitGate {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGate.class);

    /**
     * Used when Twitch returns 429 without a usable reset header. Long enough to
     * stop a tight loop, short enough not to lose a broadcast.
     */
    private static final Duration FALLBACK = Duration.ofMinutes(1);

    private volatile Instant openAgainAt;
    private volatile Integer lastRemaining;

    /**
     * Refuses the call when the quota is known to be exhausted.
     *
     * <p>Throws the same exception a real 429 would produce, so callers need no
     * special case: from where they stand, being blocked locally and being
     * blocked by Twitch are the same event.
     */
    public void checkOpen() {
        Instant until = openAgainAt;
        if (until != null && Instant.now().isBefore(until)) {
            throw new TwitchApiException(429,
                    "Rate limited until " + until + "; not sending the request");
        }
    }

    /** Remembers when Twitch said it would accept work again. */
    public void blockUntil(Instant resetAt) {
        Instant until = resetAt == null ? Instant.now().plus(FALLBACK) : resetAt;
        openAgainAt = until;
        log.warn("Rate limited by Twitch — holding requests until {}", until);
    }

    /**
     * Records how much quota is left, purely so it can be shown.
     *
     * <p>Not used to throttle: quota is spent by requests we chose to make, and
     * slowing down pre-emptively would mean skipping samples to avoid a limit
     * this application is nowhere near.
     */
    public void observeRemaining(Integer remaining) {
        if (remaining != null) {
            lastRemaining = remaining;
        }
    }

    public Optional<Status> status() {
        Instant until = openAgainAt;
        boolean blocked = until != null && Instant.now().isBefore(until);

        if (!blocked && lastRemaining == null) {
            return Optional.empty();
        }

        return Optional.of(new Status(blocked, blocked ? until.toString() : null, lastRemaining));
    }

    /**
     * @param blocked      whether requests are being held right now
     * @param openAgainAt  when they will resume, null when not blocked
     * @param remaining    quota left at the last response Twitch answered
     */
    public record Status(boolean blocked, String openAgainAt, Integer remaining) {
    }
}
