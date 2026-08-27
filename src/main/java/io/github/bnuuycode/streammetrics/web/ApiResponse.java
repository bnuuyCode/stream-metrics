package io.github.bnuuycode.streammetrics.web;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * The envelope every single API response in this project travels in.
 *
 * <p>It exists so that the freshness rule (DECISIONS.md § 3) cannot be
 * forgotten. A future endpoint physically cannot return a bare number: the only
 * way to build one of these is through {@link #ok}, {@link #stale} or
 * {@link #error}, and all three demand a timestamp. A rule written only in a
 * document gets skipped on a tired evening; a rule encoded in a type does not.
 *
 * <p>The {@code <T>} is a generic: it lets the same envelope carry a follower
 * count, a list of stream sessions, or anything else, without giving up the
 * compiler's type checking.
 *
 * <p>Serialised shape:
 * <pre>
 * { "value": 1234, "fetchedAt": "2026-08-27T14:32:11Z", "status": "OK" }
 * </pre>
 */
public final class ApiResponse<T> {

    private final T value;
    private final Instant fetchedAt;
    private final Freshness status;
    private final String message;

    private ApiResponse(T value, Instant fetchedAt, Freshness status, String message) {
        this.value = value;
        this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt is mandatory");
        this.status = Objects.requireNonNull(status, "status is mandatory");
        this.message = message;
    }

    /** A value fetched just now. */
    public static <T> ApiResponse<T> ok(T value, Instant fetchedAt) {
        return new ApiResponse<>(value, fetchedAt, Freshness.OK, null);
    }

    /**
     * A value we know to be old. Allowed on screen, but the UI must render it
     * visibly marked — greyed out, timestamp shown.
     */
    public static <T> ApiResponse<T> stale(T value, Instant fetchedAt) {
        return new ApiResponse<>(value, fetchedAt, Freshness.STALE, null);
    }

    /**
     * A failure. Note the value is hard-wired to {@code null} and there is no
     * overload that accepts one: an error response is structurally incapable of
     * smuggling an old number onto the screen.
     *
     * @param fetchedAt when the attempt was made — still mandatory, because
     *                  "we tried at 14:32 and it failed" is itself information
     *                  the user needs.
     */
    public static <T> ApiResponse<T> error(String message, Instant fetchedAt) {
        return new ApiResponse<>(null, fetchedAt, Freshness.ERROR, message);
    }

    public T getValue() {
        return value;
    }

    /**
     * Returns the timestamp already formatted as ISO-8601 in UTC
     * ("2026-08-27T14:32:11Z").
     *
     * <p>Formatting it here rather than letting the JSON library decide keeps
     * the wire format explicit and stable: the front end always receives the
     * same shape, and nobody has to guess whether a timestamp arrived as text
     * or as a pile of numbers.
     */
    public String getFetchedAt() {
        return DateTimeFormatter.ISO_INSTANT.format(fetchedAt);
    }

    public Freshness getStatus() {
        return status;
    }

    /** Human-readable reason, present only on {@link Freshness#ERROR}. */
    public String getMessage() {
        return message;
    }
}
