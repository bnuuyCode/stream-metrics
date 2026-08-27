package io.github.bnuuycode.streammetrics.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The single implementation of the freshness rule.
 *
 * <p>Reads a value, remembers when it was read, and decides which of the three
 * honest labels it deserves:
 *
 * <ul>
 *   <li>within {@code freshFor} — served as {@code OK}
 *   <li>older than that, and the refresh failed — served as {@code STALE}: the
 *       number is shown, dimmed and timestamped, never dressed up as current
 *   <li>older than {@code staleLimit}, or never read at all — {@code ERROR},
 *       with no number, because past some age "old" stops informing and starts
 *       misleading
 * </ul>
 *
 * <p>This class exists because the rule was implemented twice, once per Twitch
 * service, with the constants drifting apart. The freshness rule is the whole
 * point of the project; having two copies of it meant a change had to be
 * remembered in two places, and the second one would eventually be forgotten.
 *
 * <p>Note the cache is not here to be fast. It exists so that opening the
 * dashboard in three tabs does not fire three rounds of API calls and walk into
 * a rate limit.
 */
public final class FreshnessCache {

    private final Duration freshFor;
    private final Duration staleLimit;
    private final Function<RuntimeException, String> describe;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * @param freshFor   how long a value counts as current
     * @param staleLimit how old it may get before it stops being shown at all
     * @param describe   turns a failure into something worth reading on screen
     */
    public FreshnessCache(Duration freshFor, Duration staleLimit, Function<RuntimeException, String> describe) {
        this.freshFor = freshFor;
        this.staleLimit = staleLimit;
        this.describe = describe;
    }

    /**
     * Returns the value under {@code key}, refreshing it if due, wrapped in the
     * strongest honest label it qualifies for.
     *
     * <p>Falling back to the cache here is not the forbidden fallback: serving a
     * three-minute-old number clearly marked STALE is the design. Quietly
     * serving last night's database snapshot as if it were current is what
     * DECISIONS.md § 4 rules out.
     */
    public <T> ApiResponse<T> read(String key, Supplier<T> fetch) {
        Instant now = Instant.now();
        Entry cached = entries.get(key);

        if (cached != null && cached.age(now).compareTo(freshFor) < 0) {
            return cached.fresh();
        }

        try {
            T value = fetch.get();
            entries.put(key, new Entry(value, now));
            return ApiResponse.ok(value, now);

        } catch (RuntimeException e) {
            if (cached != null && cached.age(now).compareTo(staleLimit) < 0) {
                return cached.stale();
            }
            return ApiResponse.error(describe.apply(e), now);
        }
    }

    /**
     * One reading.
     *
     * <p>The value is held as {@code Object} because a single cache serves keys
     * of different types. The casts below are safe by construction: a given key
     * is always written by the same call site, so it always holds the same type.
     */
    private record Entry(Object value, Instant fetchedAt) {

        Duration age(Instant now) {
            return Duration.between(fetchedAt, now);
        }

        @SuppressWarnings("unchecked")
        <T> ApiResponse<T> fresh() {
            return ApiResponse.ok((T) value, fetchedAt);
        }

        @SuppressWarnings("unchecked")
        <T> ApiResponse<T> stale() {
            return ApiResponse.stale((T) value, fetchedAt);
        }
    }
}
