package io.github.bnuuycode.streammetrics.metrics;

import java.time.Duration;
import java.util.Optional;

/**
 * A platform that keeps its own record of how long a broadcast lasted.
 *
 * <p>A capability interface, like {@link LiveTrackable}: implemented only where
 * it genuinely applies. A platform without archives has no honest answer to give
 * here, and should not be made to invent one.
 *
 * <p>This is the only way out of a real problem. Our own end time is the last
 * sample we managed to take, which is off by however long the platform went on
 * reporting a stream that had already ended. Those trailing readings stretch the
 * duration and drag the average down, and no amount of cleverness on our side
 * can tell a ghost sample from a real one. The platform's own archive can.
 */
public interface ArchiveVerifiable {

    /**
     * How long the broadcast with this id really lasted.
     *
     * @return empty when there is no archive to check against — the channel may
     *         not keep them, or it may not have appeared yet. Empty is a normal
     *         answer meaning "settle on your own evidence", not a failure.
     * @throws CollectionException when the platform could not be asked at all,
     *                             which is different from it having nothing to
     *                             say
     */
    Optional<Duration> archivedDuration(String externalStreamId);
}
