package io.github.bnuuycode.streammetrics.twitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bnuuycode.streammetrics.config.AppConfig.TwitchConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Twitch OAuth dance, and nothing else.
 *
 * <p>Three steps: send the user to Twitch, trade the returned code for tokens,
 * and later trade a refresh token for a fresh access token. Reading actual
 * metrics lives elsewhere — this class only deals in credentials.
 */
public final class TwitchOAuth {

    private static final String AUTHORIZE_URL = "https://id.twitch.tv/oauth2/authorize";
    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String USERS_URL = "https://api.twitch.tv/helix/users";

    /**
     * The permissions this application asks for.
     *
     * <p>Neither of these is optional, and neither is available with a plain
     * app token — both require the broadcaster to log in personally:
     *
     * <ul>
     *   <li>{@code moderator:read:followers} — total follower count. The old
     *       endpoint that worked without a scope was removed by Twitch in 2023.
     *   <li>{@code channel:read:subscriptions} — subscriber count.
     * </ul>
     */
    public static final List<String> SCOPES = List.of(
            "moderator:read:followers",
            "channel:read:subscriptions");

    private final TwitchConfig config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public TwitchOAuth(TwitchConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Step 1: the address the browser is sent to, where Twitch asks the user
     * whether to allow this application.
     *
     * @param state an unguessable value we generate and later verify. Without
     *              it, someone could hand the user a crafted callback link and
     *              attach a different Twitch account to this app. Cheap to add,
     *              painful to omit.
     */
    public String authorizeUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("redirect_uri", config.redirectUri());
        params.put("response_type", "code");
        params.put("scope", String.join(" ", SCOPES));
        params.put("state", state);

        return AUTHORIZE_URL + "?" + form(params);
    }

    /** Step 2: trade the one-time code from the callback for real tokens. */
    public TwitchTokens exchangeCode(String code) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("code", code);
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", config.redirectUri());

        return parseTokens(post(TOKEN_URL, params));
    }

    /** Step 3: swap a refresh token for a fresh access token. */
    public TwitchTokens refresh(String refreshToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("client_secret", config.clientSecret());
        params.put("refresh_token", refreshToken);
        params.put("grant_type", "refresh_token");

        return parseTokens(post(TOKEN_URL, params));
    }

    /**
     * Who the token belongs to. Called once right after login, to learn the
     * broadcaster id every later metrics call needs.
     */
    public TwitchUser currentUser(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(USERS_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", config.clientId())
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        JsonNode body = send(request);
        JsonNode user = body.path("data").path(0);

        if (user.isMissingNode()) {
            throw new IllegalStateException("Twitch returned no user for this token");
        }

        return new TwitchUser(
                user.path("id").asText(),
                user.path("login").asText(),
                user.path("display_name").asText());
    }

    private TwitchTokens parseTokens(JsonNode body) {
        // Twitch reports a lifetime in seconds; we store the absolute moment it
        // dies. Storing the duration instead would mean recomputing "expired?"
        // against whenever it happened to be saved — a derived value waiting to
        // go stale (DECISIONS.md § 6.1).
        long expiresIn = body.path("expires_in").asLong();

        // For user tokens "scope" is a JSON array; for app tokens it is absent.
        StringBuilder scopes = new StringBuilder();
        for (JsonNode scope : body.path("scope")) {
            if (scopes.length() > 0) {
                scopes.append(' ');
            }
            scopes.append(scope.asText());
        }

        return new TwitchTokens(
                body.path("access_token").asText(),
                body.path("refresh_token").asText(null),
                Instant.now().plusSeconds(expiresIn),
                scopes.toString());
    }

    private JsonNode post(String url, Map<String, String> params) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();

        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // The body is included because Twitch explains itself well
                // here: "invalid client secret", "redirect mismatch". Throwing
                // away that text would turn a five-second fix into an hour.
                throw new IllegalStateException(
                        "Twitch replied " + response.statusCode() + ": " + response.body());
            }

            return json.readTree(response.body());

        } catch (IOException e) {
            throw new IllegalStateException("Could not reach Twitch", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Twitch", e);
        }
    }

    /** URL-encodes a map into {@code a=1&b=2}. */
    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((key, value) -> {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
               .append('=')
               .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    /** The authenticated broadcaster. */
    public record TwitchUser(String id, String login, String displayName) {
    }
}
