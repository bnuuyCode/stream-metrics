package io.github.bnuuycode.streammetrics.metrics;

/**
 * The shared vocabulary of metric names.
 *
 * <p>Closed on purpose. If Twitch stored {@code followers} and YouTube stored
 * {@code subs}, the two could never be drawn on the same chart — and comparing
 * platforms side by side is the reason this project exists. Each platform's
 * provider translates whatever its API calls a number into one of these.
 *
 * <p>Only metrics some provider actually emits belong here. A constant added
 * for a platform that does not exist yet is a guess, and a guess in an enum
 * looks exactly like a fact.
 */
public enum MetricKey {

    /** Total followers, or the platform's closest equivalent. */
    FOLLOWERS("followers"),

    /** Paying subscribers or members. */
    SUBSCRIBERS("subscribers");

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
