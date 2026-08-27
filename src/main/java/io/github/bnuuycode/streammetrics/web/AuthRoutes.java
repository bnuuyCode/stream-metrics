package io.github.bnuuycode.streammetrics.web;

import io.github.bnuuycode.streammetrics.config.AppConfig;
import io.github.bnuuycode.streammetrics.db.AccountRepository;
import io.github.bnuuycode.streammetrics.twitch.TwitchOAuth;
import io.github.bnuuycode.streammetrics.twitch.TwitchTokens;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The three routes that connect a Twitch account to this application.
 *
 * <ul>
 *   <li>{@code GET /auth/twitch} — sends the browser to Twitch to ask permission
 *   <li>{@code GET /auth/twitch/callback} — where Twitch sends it back
 *   <li>{@code GET /api/twitch/status} — what the dashboard asks to know where
 *       things stand
 * </ul>
 */
public final class AuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private static final String PLATFORM = "twitch";

    /** How long a login attempt may sit unfinished before its state is dropped. */
    private static final long STATE_TTL_SECONDS = 600;

    private final AppConfig config;
    private final AccountRepository accounts;
    private final SecureRandom random = new SecureRandom();

    /**
     * Login attempts started but not yet completed.
     *
     * <p>Deliberately in memory rather than in the database: an interrupted
     * login is worthless a minute later, and restarting the app should forget
     * it. Nothing here needs to survive.
     */
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();

    public AuthRoutes(AppConfig config, AccountRepository accounts) {
        this.config = config;
        this.accounts = accounts;
    }

    public void register(Javalin app) {
        app.get("/auth/twitch", this::startLogin);
        app.get("/auth/twitch/callback", this::completeLogin);
        app.get("/api/twitch/status", this::status);
    }

    private void startLogin(Context ctx) {
        Optional<AppConfig.TwitchConfig> twitch = config.twitch();
        if (twitch.isEmpty()) {
            ctx.status(503).result("""
                    Twitch is not configured.

                    Copy config.properties.example to config.properties, fill in
                    twitch.clientId and twitch.clientSecret, and restart.
                    """);
            return;
        }

        String state = newState();
        pendingStates.put(state, Instant.now());
        forgetExpiredStates();

        ctx.redirect(new TwitchOAuth(twitch.get()).authorizeUrl(state));
    }

    private void completeLogin(Context ctx) {
        Optional<AppConfig.TwitchConfig> twitch = config.twitch();
        if (twitch.isEmpty()) {
            ctx.status(503).result("Twitch is not configured.");
            return;
        }

        // Twitch reports a refused consent screen here rather than by failing.
        String error = ctx.queryParam("error");
        if (error != null) {
            ctx.status(400).result("Twitch refused the login: "
                    + error + " — " + ctx.queryParam("error_description"));
            return;
        }

        // The state must be one we handed out ourselves. Without this check a
        // crafted callback link could attach somebody else's Twitch account.
        String state = ctx.queryParam("state");
        if (state == null || pendingStates.remove(state) == null) {
            ctx.status(400).result("Unknown or expired login attempt. Start again from /auth/twitch.");
            return;
        }

        String code = ctx.queryParam("code");
        if (code == null) {
            ctx.status(400).result("Twitch did not send an authorisation code.");
            return;
        }

        TwitchOAuth oauth = new TwitchOAuth(twitch.get());

        try {
            TwitchTokens tokens = oauth.exchangeCode(code);
            TwitchOAuth.TwitchUser user = oauth.currentUser(tokens.accessToken());

            long accountId = accounts.saveAccount(
                    PLATFORM, user.id(), user.login(), user.displayName());

            accounts.saveToken(
                    accountId,
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    tokens.scopes(),
                    tokens.expiresAt(),
                    // Twitch refresh tokens have no expiry date, so there is no
                    // hard deadline to warn about here.
                    null);

            log.info("Connected Twitch account {} ({})", user.displayName(), user.id());
            ctx.redirect("/");

        } catch (RuntimeException e) {
            // Showing the message rather than a generic "something went wrong":
            // Twitch is specific about redirect mismatches and bad secrets, and
            // that text is the fastest route to the fix.
            log.error("Twitch login failed", e);
            ctx.status(502).result("Twitch login failed.\n\n" + e.getMessage());
        }
    }

    private void status(Context ctx) {
        Instant now = Instant.now();

        if (config.twitch().isEmpty()) {
            ctx.json(ApiResponse.ok(TwitchStatus.notConfigured(), now));
            return;
        }

        Optional<AccountRepository.StoredAccount> account = accounts.findAccount(PLATFORM);
        if (account.isEmpty()) {
            ctx.json(ApiResponse.ok(TwitchStatus.notConnected(), now));
            return;
        }

        AccountRepository.StoredAccount stored = account.get();
        Optional<AccountRepository.StoredToken> token = accounts.findToken(stored.id());

        ctx.json(ApiResponse.ok(new TwitchStatus(
                State.CONNECTED,
                stored.displayName(),
                stored.handle(),
                token.map(AccountRepository.StoredToken::scopes).orElse(null),
                token.map(AccountRepository.StoredToken::needsRefresh).orElse(true)
        ), now));
    }

    private String newState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void forgetExpiredStates() {
        Instant cutoff = Instant.now().minusSeconds(STATE_TTL_SECONDS);
        pendingStates.values().removeIf(started -> started.isBefore(cutoff));
    }

    /** Where the Twitch connection stands. */
    public enum State {
        /** No client id and secret in config.properties yet. */
        NOT_CONFIGURED,
        /** Credentials present, but nobody has logged in. */
        NOT_CONNECTED,
        /** An account is linked and its tokens are stored. */
        CONNECTED
    }

    /**
     * Note this rides inside a perfectly healthy {@link Freshness#OK} response
     * even when nothing is connected.
     *
     * <p>The distinction is worth holding on to: freshness answers "is this
     * information current?", not "is everything fine?". Knowing accurately, as
     * of one second ago, that Twitch is not connected is a fresh and correct
     * answer. Marking it {@code ERROR} would cry wolf and teach the eye to
     * ignore red.
     */
    public record TwitchStatus(
            State state,
            String displayName,
            String handle,
            String scopes,
            boolean needsRefresh) {

        static TwitchStatus notConfigured() {
            return new TwitchStatus(State.NOT_CONFIGURED, null, null, null, false);
        }

        static TwitchStatus notConnected() {
            return new TwitchStatus(State.NOT_CONNECTED, null, null, null, false);
        }
    }
}
