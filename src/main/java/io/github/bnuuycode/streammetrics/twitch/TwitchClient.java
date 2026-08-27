package io.github.bnuuycode.streammetrics.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads metrics from the Twitch Helix API.
 *
 * <p>Knows nothing about caching, tokens or freshness — it makes a call and
 * returns a number, or throws. Deciding what to show when it throws belongs one
 * layer up.
 */
public final class TwitchClient {

    private static final String BASE = "https://api.twitch.tv/helix";

    private final String clientId;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public TwitchClient(String clientId) {
        this.clientId = clientId;
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
        return body.path("total").asLong();
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
        return body.path("total").asLong();
    }

    /**
     * The broadcast happening right now, or empty when off air.
     *
     * <p>Works with any valid token — no special scope. Empty here means
     * "asked, and the channel is offline", which is a real answer. Being unable
     * to ask throws instead, and the two must never be confused: treating a
     * network blip as "offline" would end a live session by mistake.
     */
    public Optional<LiveTrackable.LiveSnapshot> currentStream(String broadcasterId, String accessToken) {
        JsonNode body = get("/streams?user_id=" + broadcasterId, accessToken);
        JsonNode stream = body.path("data").path(0);

        if (stream.isMissingNode() || stream.isNull()) {
            return Optional.empty();
        }

        return Optional.of(new LiveTrackable.LiveSnapshot(
                stream.path("id").asText(),
                Instant.parse(stream.path("started_at").asText()),
                stream.path("title").asText(null),
                stream.path("game_name").asText(null),
                stream.path("viewer_count").asInt()));
    }

    private JsonNode get(String path, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", clientId)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

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
}
