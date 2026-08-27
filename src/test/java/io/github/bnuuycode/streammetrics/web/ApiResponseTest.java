package io.github.bnuuycode.streammetrics.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * These tests guard the project's central rule rather than any clever logic.
 *
 * <p>They are worth writing precisely because the rule is easy to break by
 * accident later: someone adds a convenient {@code error(value, message)}
 * overload "just for one card", and six months of trustworthy dashboards quietly
 * become untrustworthy ones. The test fails the moment that happens.
 */
class ApiResponseTest {

    private static final Instant WHEN = Instant.parse("2026-08-27T14:32:11Z");

    @Test
    @DisplayName("an error carries no value at all")
    void errorNeverCarriesAValue() {
        ApiResponse<Integer> response = ApiResponse.error("twitch unreachable", WHEN);

        assertNull(response.getValue(),
                "an error response must not smuggle an old number onto the screen");
        assertEquals(Freshness.ERROR, response.getStatus());
        assertEquals("twitch unreachable", response.getMessage());
    }

    @Test
    @DisplayName("a failed attempt is still timestamped")
    void errorIsStillTimestamped() {
        ApiResponse<Integer> response = ApiResponse.error("rate limited", WHEN);

        // "we tried at 14:32 and it failed" is information the user needs.
        assertEquals("2026-08-27T14:32:11Z", response.getFetchedAt());
    }

    @Test
    @DisplayName("a stale value keeps its value but says so")
    void staleKeepsValueAndSaysSo() {
        ApiResponse<Integer> response = ApiResponse.stale(1234, WHEN);

        assertEquals(1234, response.getValue());
        assertEquals(Freshness.STALE, response.getStatus());
    }

    @Test
    @DisplayName("timestamps serialise as ISO-8601 in UTC")
    void timestampFormatIsStable() {
        ApiResponse<Integer> response = ApiResponse.ok(42, WHEN);

        // The front end parses this string. Pinning the format here means a
        // future refactor cannot change the wire contract unnoticed.
        assertEquals("2026-08-27T14:32:11Z", response.getFetchedAt());
    }

    @Test
    @DisplayName("no response can exist without a timestamp")
    void timestampIsMandatory() {
        assertThrows(NullPointerException.class,
                () -> ApiResponse.ok(42, null));
    }
}
