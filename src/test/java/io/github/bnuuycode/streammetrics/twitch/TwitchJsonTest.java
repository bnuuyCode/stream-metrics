package io.github.bnuuycode.streammetrics.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the distinction between a measured zero and a missing field.
 *
 * <p>These two look identical once they reach the database, and only one of them
 * is true. The bug these tests exist to prevent shipped once already: a reply
 * with no {@code total} field was read as "0 followers", stored in permanent
 * history, and displayed with a green OK badge.
 */
class TwitchJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("a missing number is a broken response, not a zero")
    void missingNumberThrows() {
        JsonNode body = parse("{\"pagination\":{}}");

        TwitchApiException e = assertThrows(TwitchApiException.class,
                () -> TwitchJson.requiredLong(body, "total"));

        assertTrue(e.isMalformed(), "must be its own category, not lumped into UNKNOWN");
    }

    @Test
    @DisplayName("a real zero survives")
    void realZeroIsKept() {
        // The whole point. A stream with nobody watching is a fact worth
        // recording, and must not be mistaken for a broken payload.
        assertEquals(0L, TwitchJson.requiredLong(parse("{\"total\":0}"), "total"));
        assertEquals(0, TwitchJson.requiredInt(parse("{\"viewer_count\":0}"), "viewer_count"));
    }

    @Test
    @DisplayName("a number sent as text is refused")
    void wrongTypeThrows() {
        assertThrows(TwitchApiException.class,
                () -> TwitchJson.requiredLong(parse("{\"total\":\"1284\"}"), "total"));
    }

    @Test
    @DisplayName("an empty array means offline; a missing one means broken")
    void emptyArrayIsNotTheSameAsMissing() {
        // Empty data is a genuine answer: the channel is off air.
        assertTrue(TwitchJson.requiredArray(parse("{\"data\":[]}"), "data").isEmpty());

        // Missing data is not an answer. Treating it as "offline" would close a
        // broadcast that is still running.
        assertThrows(TwitchApiException.class,
                () -> TwitchJson.requiredArray(parse("{\"error\":\"oops\"}"), "data"));
    }

    @Test
    @DisplayName("blank text counts as missing")
    void blankTextThrows() {
        assertThrows(TwitchApiException.class,
                () -> TwitchJson.requiredText(parse("{\"access_token\":\"\"}"), "access_token"));
    }

    @Test
    @DisplayName("optional text is allowed to be absent")
    void optionalTextReturnsNull() {
        // A stream with no title set is normal, not an error.
        assertNull(TwitchJson.optionalText(parse("{}"), "title"));
    }

    @Test
    @DisplayName("error messages name fields but never quote their values")
    void errorMessageLeaksNoValues() {
        // These messages reach the logs, and some of these responses carry
        // access tokens.
        JsonNode body = parse("{\"access_token\":\"super-secret-value\",\"token_type\":\"bearer\"}");

        TwitchApiException e = assertThrows(TwitchApiException.class,
                () -> TwitchJson.requiredLong(body, "expires_in"));

        assertFalse(e.getMessage().contains("super-secret-value"),
                "a credential must never end up in an exception message");
        assertTrue(e.getMessage().contains("access_token"),
                "naming the fields present is what makes the failure diagnosable");
    }
}
