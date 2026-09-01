package io.github.bnuuycode.streammetrics.twitch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive duration is the only authority on when a broadcast really ended,
 * so reading it wrongly would replace one bad end time with another — and this
 * one would carry the label saying it came from the platform.
 */
class TwitchClientTest {

    @Test
    @DisplayName("reads the full form")
    void fullDuration() {
        assertEquals(Duration.ofHours(3).plusMinutes(20).plusSeconds(15),
                TwitchClient.parseDuration("3h20m15s").orElseThrow());
    }

    @Test
    @DisplayName("parts that are zero are simply absent")
    void omittedParts() {
        // Twitch writes 2h0m30s as 2h30s, and 45m as 45m.
        assertEquals(Duration.ofHours(2).plusSeconds(30), TwitchClient.parseDuration("2h30s").orElseThrow());
        assertEquals(Duration.ofMinutes(45), TwitchClient.parseDuration("45m").orElseThrow());
        assertEquals(Duration.ofSeconds(8), TwitchClient.parseDuration("8s").orElseThrow());
    }

    @Test
    @DisplayName("anything unreadable yields nothing rather than a wrong number")
    void refusesGarbage() {
        // Empty is the right answer here: the session then settles on the
        // evidence collected locally and says so. Guessing a duration would
        // produce a figure labelled as coming from the archive.
        assertTrue(TwitchClient.parseDuration(null).isEmpty());
        assertTrue(TwitchClient.parseDuration("").isEmpty());
        assertTrue(TwitchClient.parseDuration("about three hours").isEmpty());
    }

    @Test
    @DisplayName("a zero-length archive is not a duration")
    void zeroIsNotADuration() {
        // An archive of nothing says nothing about how long the broadcast ran,
        // and adopting it would collapse the session to an instant.
        assertTrue(TwitchClient.parseDuration("0s").isEmpty());
    }
}
