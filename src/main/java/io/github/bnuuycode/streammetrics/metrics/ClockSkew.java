package io.github.bnuuycode.streammetrics.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * How far this machine's clock has drifted from the platform's.
 *
 * <p>Not a curiosity. Almost every timestamp in this application comes from the
 * local clock: when a sample was taken, which calendar day a snapshot belongs
 * to, how long a broadcast has been running. The platform's timestamps come from
 * theirs. When the two disagree, the disagreement lands in stored history.
 *
 * <p>The failure is worst at a day boundary. {@code snapshot_date} is computed
 * locally, so a clock running minutes behind near midnight files a metric under
 * the wrong day — and since the collector upserts, that wrong day is then
 * overwritten by the right one, quietly. Nobody would ever see it happen.
 *
 * <p>It has already been observed here at seven seconds, which was enough to
 * make a broadcast's uptime read as zero because the start looked like it was in
 * the future.
 *
 * <p>Measured rather than assumed: every HTTP response carries a {@code Date}
 * header with the server's own time, so the offset is an observation, not a
 * guess. Network transit inflates it slightly — the reading is taken after the
 * response has travelled — but by well under a second, and the thresholds here
 * start at thirty.
 */
public final class ClockSkew {

    /** Below this, the difference cannot affect anything that is stored. */
    private static final Duration TOLERABLE = Duration.ofSeconds(30);

    /** Above this, a day boundary can land on the wrong side. */
    private static final Duration SERIOUS = Duration.ofMinutes(5);

    private volatile Observation last;

    /**
     * @param serverTime what the platform said the time was
     * @param localTime  what this machine thought when the answer arrived
     */
    public void observe(Instant serverTime, Instant localTime) {
        if (serverTime == null || localTime == null) {
            return;
        }

        long offset = Duration.between(serverTime, localTime).getSeconds();
        last = new Observation(offset, localTime, level(Math.abs(offset)));
    }

    /** The most recent reading, absent until a response has been seen. */
    public Optional<Observation> current() {
        return Optional.ofNullable(last);
    }

    private static String level(long absoluteSeconds) {
        if (absoluteSeconds < TOLERABLE.getSeconds()) {
            return "OK";
        }
        return absoluteSeconds < SERIOUS.getSeconds() ? "DRIFTING" : "BROKEN";
    }

    /**
     * @param offsetSeconds positive when this machine is ahead of the platform,
     *                      negative when behind
     * @param level         OK below thirty seconds, DRIFTING below five minutes,
     *                      BROKEN beyond — the point at which a calendar day can
     *                      be recorded wrongly
     */
    public record Observation(long offsetSeconds, Instant observedAt, String level) {
    }
}
