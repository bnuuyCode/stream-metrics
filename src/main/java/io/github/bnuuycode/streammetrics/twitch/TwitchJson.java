package io.github.bnuuycode.streammetrics.twitch;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reads fields out of a Twitch response, refusing to invent values.
 *
 * <p>This class exists because of a specific defect. Jackson's {@code path()}
 * returns an empty node for a field that is not there, and {@code asLong()} on
 * an empty node returns <strong>zero</strong> without complaint. So a reply that
 * arrived with HTTP 200 but no {@code total} field was being read as "you have
 * 0 followers" — a plausible-looking number, written into permanent history,
 * displayed with a green OK badge.
 *
 * <p>That is the exact failure this project exists to prevent, hiding in the one
 * layer nobody thought to audit: the parser.
 *
 * <p>The rule here is simple. If a field the application depends on is absent or
 * has the wrong type, that is a broken response, not a zero. It throws, gets
 * recorded as a PARSE failure, and shows on screen as an error. Nothing is
 * guessed.
 *
 * <p>Error messages deliberately name the missing field and list which fields
 * <em>were</em> present, but never quote the values. Some of these responses
 * carry access tokens, and an exception message ends up in logs.
 */
final class TwitchJson {

    private TwitchJson() {
    }

    static long requiredLong(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isNumber()) {
            throw missing(parent, field, "a number");
        }
        return node.asLong();
    }

    static int requiredInt(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isNumber()) {
            throw missing(parent, field, "a number");
        }
        return node.asInt();
    }

    static String requiredText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw missing(parent, field, "a non-empty string");
        }
        return node.asText();
    }

    /**
     * A field that may legitimately be absent — a stream with no title, for
     * instance. Returns null rather than throwing, because absence here is
     * information, not breakage.
     */
    static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /**
     * An array field that must exist, though it may be empty.
     *
     * <p>The distinction matters more than it looks. In {@code /streams}, an
     * empty {@code data} array means "the channel is offline", which is a real
     * answer that ends a live session. A <em>missing</em> {@code data} field
     * means the response is broken — and treating that as "offline" would close
     * a broadcast that is still running, on the strength of a malformed reply.
     */
    static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isArray()) {
            throw missing(parent, field, "an array");
        }
        return node;
    }

    private static TwitchApiException missing(JsonNode parent, String field, String expected) {
        return TwitchApiException.malformed(
                "Twitch response is missing '" + field + "' (expected " + expected + "). "
                        + "Fields present: " + fieldNames(parent));
    }

    /** Field names only — never their values. */
    private static String fieldNames(JsonNode node) {
        if (!node.isObject()) {
            return "<not an object>";
        }
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names.isEmpty() ? "<none>" : String.join(", ", names);
    }
}
