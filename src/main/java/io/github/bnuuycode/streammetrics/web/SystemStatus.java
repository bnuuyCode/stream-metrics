package io.github.bnuuycode.streammetrics.web;

import io.github.bnuuycode.streammetrics.db.Database;
import io.github.bnuuycode.streammetrics.metrics.ClockSkew;
import io.github.bnuuycode.streammetrics.twitch.RateLimitGate;

/**
 * The conditions the numbers were collected under.
 *
 * <p>Kept beside the health check rather than buried in a log, because both of
 * these change what the stored data means. A clock that has drifted files
 * metrics under the wrong day; a rate limit that is holding requests means
 * samples are being missed right now. Neither shows up as an error on any card,
 * so without somewhere to say it, both are invisible until the damage is in the
 * history.
 *
 * @param clock     null until a response has been seen
 * @param rateLimit null while nothing about the quota has been observed
 */
public record SystemStatus(
        Database.DatabaseStatus database,
        ClockSkew.Observation clock,
        RateLimitGate.Status rateLimit) {
}
