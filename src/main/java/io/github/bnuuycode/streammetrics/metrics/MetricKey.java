package io.github.bnuuycode.streammetrics.metrics;

/**
 * The shared vocabulary of metric names.
 *
 * <p>Closed on purpose. If Twitch stores {@code followers} and YouTube stores
 * {@code subs}, the two can never be drawn on the same chart — and comparing
 * platforms side by side is the reason this project exists. An enum makes the
 * compiler enforce the shared vocabulary that free text would let drift apart.
 *
 * <p>Each platform's provider is responsible for translating whatever its API
 * calls a number into one of these.
 */
public enum MetricKey {

    /** Total followers, or the platform's closest equivalent. */
    FOLLOWERS("followers"),

    /** Paying subscribers or members. */
    SUBSCRIBERS("subscribers"),

    /** Lifetime views across the channel. */
    TOTAL_VIEWS("total_views"),

    /** How many videos, posts or clips exist. */
    CONTENT_COUNT("content_count");

    private final String key;

    MetricKey(String key) {
        this.key = key;
    }

    /**
     * The value written to the {@code metric} column.
     *
     * <p>Stored as this string rather than as the enum name so that renaming a
     * constant in Java never silently orphans years of history.
     */
    public String key() {
        return key;
    }
}
