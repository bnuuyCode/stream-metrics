package io.github.bnuuycode.streammetrics.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bnuuycode.streammetrics.metrics.ClockSkew;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads metrics from the Twitch Helix API.
 *
 * <p>Knows nothing about caching, tokens or freshness — it makes a call and
 * returns a number, or throws. Deciding what to show when it throws belongs one
 * layer up.
 *
 * <p>Every field this class reads goes through {@link TwitchJson}, which refuses
 * to substitute a default for a field that is not there. See that class for why
 * that matters more than it sounds.
 */
public final class TwitchClient {

    private static final String BASE = "https://api.twitch.tv/helix";

    /**
     * Twitch writes durations as 3h20m15s, omitting any part that is zero.
     *
     * <p>Written with {@code [0-9]} rather than {@code \d} so the pattern needs
     * no backslash escaping inside a Java string, where one missing backslash is
     * a compile error and two too many is a bug.
     */
    private static final Pattern DURATION =
            Pattern.compile("(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?");

    private final String clientId;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final RateLimitGate rateLimit;
    private final ClockSkew clockSkew;

    public TwitchClient(String clientId, RateLimitGate rateLimit, ClockSkew clockSkew) {
        this.clientId = clientId;
        this.rateLimit = rateLimit;
        this.clockSkew = clockSkew;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Total followers.
     *
     * <p>Requires the {@code moderator:read:followers} scope on a token
     * belonging to the broadcaster. The old endpoint that answered this without
     * any scope was removed by Twitch in 2023 — which is the whole reason this
     * project needs a full login flow instead of a simple app token.
     */
    public long followers(String broadcasterId, String accessToken) {
        // first=1 asks for the smallest possible page: we only want the total,
        // not the list of names behind it.
        JsonNode body = get("/channels/followers?first=1&broadcaster_id=" + broadcasterId, accessToken);
        return TwitchJson.requiredLong(body, "total");
    }

    /**
     * Total subscribers.
     *
     * <p>Requires {@code channel:read:subscriptions}. Only meaningful for
     * affiliates and partners; other channels get a 400 back, which is reported
     * as such rather than as a zero. A fabricated zero would be indistinguishable
     * from a real one.
     */
    public long subscribers(String broadcasterId, String accessToken) {
        JsonNode body = get("/subscriptions?first=1&broadcaster_id=" + broadcasterId, accessToken);
        return TwitchJson.requiredLong(body, "total");
    }

    /**
     * The broadcast happening right now, or empty when off air.
     *
     * <p>Works with any valid token — no special scope. Empty here means
     * "asked, and the channel is offline", which is a real answer. Being unable
     * to ask throws instead, and the two must never be confused: treating a
     * network blip as "offline" would end a live session by mistake.
     *
     * <p>The same care applies to the shape of the reply. An empty {@code data}
     * array is a genuine "offline". A <em>missing</em> {@code data} array is a
     * broken response, and closing a running broadcast on the strength of one
     * would be exactly the silent data loss this project refuses to accept.
     */
    public Optional<LiveTrackable.LiveSnapshot> currentStream(String broadcasterId, String accessToken) {
        JsonNode body = get("/streams?user_id=" + broadcasterId, accessToken);
        JsonNode data = TwitchJson.requiredArray(body, "data");

        if (data.isEmpty()) {
            return Optional.empty();
        }

        JsonNode stream = data.get(0);

        return Optional.of(new LiveTrackable.LiveSnapshot(
                TwitchJson.requiredText(stream, "id"),
                startedAt(stream),
                // Title and category can legitimately be blank.
                TwitchJson.optionalText(stream, "title"),
                TwitchJson.optionalText(stream, "game_name"),
                TwitchJson.requiredInt(stream, "viewer_count")));
    }

    /**
     * How long a finished broadcast actually lasted, according to Twitch.
     *
     * <p>The only authority on this that exists. Our own end time is the last
     * sample we managed to take, which is off by however long Twitch went on
     * listing the stream after it ended. The archive carries the real duration.
     *
     * <p>Matched on {@code stream_id} rather than on timing, so there is no
     * guessing about which recording belongs to which session.
     *
     * <p>Empty when there is no archive — the channel may not keep them, or it
     * may not have appeared yet. That is a normal answer, not a failure: the
     * session simply settles on the evidence we collected ourselves.
     */
    public Optional<Duration> archivedDuration(String broadcasterId, String streamId, String accessToken) {
        JsonNode body = get("/videos?type=archive&first=10&user_id=" + broadcasterId, accessToken);

        for (JsonNode video : TwitchJson.requiredArray(body, "data")) {
            if (streamId.equals(TwitchJson.optionalText(video, "stream_id"))) {
                return parseDuration(TwitchJson.optionalText(video, "duration"));
            }
        }

        return Optional.empty();
    }

    /**
     * Reads Twitch's duration format, which is written as {@code 3h20m15s} with
     * any part omitted when zero.
     */
    static Optional<Duration> parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = DURATION.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        long seconds = part(matcher.group(1)) * 3600
                + part(matcher.group(2)) * 60
                + part(matcher.group(3));

        // Zero would mean an archive of nothing, which is not a duration this
        // application should adopt as a broadcast's length.
        return seconds == 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    }

    private static long part(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private static Instant startedAt(JsonNode stream) {
        String raw = TwitchJson.requiredText(stream, "started_at");
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            // A timestamp we cannot read is not a timestamp. Falling back to
            // "now" would silently reset the duration of a broadcast already
            // in progress.
            throw TwitchApiException.malformed("Twitch sent an unreadable 'started_at'");
        }
    }

    private JsonNode get(String path, String accessToken) {
        // Refused locally while the quota is known to be spent. Sending anyway
        // would be refused by Twitch and count against the same limit.
        rateLimit.checkOpen();

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            readClock(response);
            rateLimit.observeRemaining(intHeader(response, "ratelimit-remaining"));

            if (response.statusCode() == 429) {
                rateLimit.blockUntil(resetAt(response));
                throw new TwitchApiException(429, "Rate limited by Twitch");
            }

            if (response.statusCode() != 200) {
                throw new TwitchApiException(response.statusCode(), response.body());
            }

            return json.readTree(response.body());

        } catch (IOException e) {
            throw new TwitchApiException("Could not reach Twitch", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwitchApiException("Interrupted while calling Twitch", e);
        }
    }

    /**
     * Compares Twitch's clock with ours, using the {@code Date} header every
     * HTTP response carries.
     *
     * <p>Free information: it arrives on every call whether or not anyone reads
     * it, and it is the only way to find out that this machine's clock has
     * drifted before the drift reaches stored history.
     */
    private void readClock(HttpResponse<String> response) {
        Instant local = Instant.now();

        response.headers().firstValue("date").ifPresent(raw -> {
            try {
                Instant serverTime = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                clockSkew.observe(serverTime, local);
            } catch (RuntimeException e) {
                // An unreadable Date header is not worth failing a request over.
                // The consequence is one missed observation, not wrong data.
            }
        });
    }

    /** When Twitch says it will accept requests again, if it said. */
    private static Instant resetAt(HttpResponse<String> response) {
        Integer epochSeconds = intHeader(response, "ratelimit-reset");
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static Integer intHeader(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .map(value -> {
                    try {
                        return Integer.valueOf(value.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
