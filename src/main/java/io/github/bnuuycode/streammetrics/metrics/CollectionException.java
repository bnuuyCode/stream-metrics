package io.github.bnuuycode.streammetrics.metrics;

/**
 * A platform could not be read.
 *
 * <p>Carries a category rather than only a message, because the collector reacts
 * differently to each: a rate limit fixes itself, a dead token never will. Each
 * provider translates its own platform's failures into these shared categories,
 * so the collector stays free of platform-specific knowledge.
 */
public final class CollectionException extends RuntimeException {

    private final ErrorKind kind;

    public CollectionException(ErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CollectionException(ErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ErrorKind kind() {
        return kind;
    }

    /** Matches the {@code error_kind} column in {@code collection_run}. */
    public enum ErrorKind {
        /** Token rejected or missing a scope. Needs a human to reconnect. */
        AUTH,
        /** Too many requests. Recovers on its own. */
        RATE_LIMIT,
        /** Never reached the platform at all. */
        NETWORK,
        /** Reached it, but the answer made no sense. */
        PARSE,
        /** Anything else. */
        UNKNOWN
    }
}
