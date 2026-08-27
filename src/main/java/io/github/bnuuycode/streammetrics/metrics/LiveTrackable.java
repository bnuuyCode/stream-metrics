package io.github.bnuuycode.streammetrics.metrics;

import java.time.Instant;
import java.util.Optional;

/**
 * A platform that broadcasts live, and can be sampled while it does.
 *
 * <p>Separate from {@link MetricsProvider} on purpose. This is a capability
 * interface: a platform implements it only if the capability genuinely applies.
 * Twitch implements both; Bluesky will implement only {@code MetricsProvider},
 * and nobody has to invent a meaningless "is live" answer for a platform with
 * no such concept.
 *
 * <p>The alternative — one fat interface where half the methods return null for
 * half the platforms — is how an abstraction starts lying about what it covers.
 */
public interface LiveTrackable {

    /**
     * The broadcast happening right now, or empty when off air.
     *
     * @throws CollectionException when the platform could not be reached. Note
     *                             this is different from returning empty: empty
     *                             means "asked, and the answer is no". An
     *                             exception means "could not ask". Collapsing
     *                             those two would end a live session every time
     *                             the network hiccuped.
     */
    Optional<LiveSnapshot> currentStream();

    /** One observation of an ongoing broadcast. */
    record LiveSnapshot(
            String streamId,
            Instant startedAt,
            String title,
            String category,
            int viewers) {
    }
}
