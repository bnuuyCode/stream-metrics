package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.config.AppConfig.TwitchConfig;
import io.github.bnuuycode.streammetrics.db.AccountRepository;
import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredToken;
import io.github.bnuuycode.streammetrics.metrics.ClockSkew;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Owns the connected Twitch account and its access token.
 *
 * <p>There is exactly one of these, shared by everything that talks to Twitch:
 * the dashboard's live reads, the daily snapshot job and the live sampler.
 *
 * <p>That is not tidiness for its own sake. Twitch invalidates a refresh token
 * once it is spent, so two components each holding their own copy of the refresh
 * logic would eventually spend it at the same moment and lock the account out.
 * One owner, one lock, one refresh.
 */
public final class TwitchSession {

    private static final Logger log = LoggerFactory.getLogger(TwitchSession.class);

    public static final String PLATFORM = "twitch";

    private final AccountRepository accounts;
    private final TwitchOAuth oauth;
    private final TwitchClient client;

    public TwitchSession(TwitchConfig config,
                         AccountRepository accounts,
                         RateLimitGate rateLimit,
                         ClockSkew clockSkew) {
        this.accounts = accounts;
        this.oauth = new TwitchOAuth(config);
        this.client = new TwitchClient(config.clientId(), rateLimit, clockSkew);
    }

    public TwitchClient client() {
        return client;
    }

    /** The connected account, or empty when nobody has logged in. */
    public Optional<StoredAccount> account() {
        return accounts.findAccount(PLATFORM);
    }

    /**
     * An access token that is actually usable, refreshed first if it is dead or
     * nearly so.
     *
     * <p>Synchronised: see the class comment. Two callers arriving together must
     * not both try to spend the refresh token.
     */
    public synchronized String accessToken(StoredAccount account) {
        StoredToken token = accounts.findToken(account.id())
                .orElseThrow(() -> new IllegalStateException("No stored token. Reconnect the account."));

        if (!token.needsRefresh()) {
            return token.accessToken();
        }

        log.info("Refreshing Twitch access token");
        TwitchTokens fresh = oauth.refresh(token.refreshToken());

        // Twitch usually returns a new refresh token, but not always. Keeping
        // the old one when none comes back avoids storing a null and locking the
        // account out until a manual reconnect.
        String refreshToken = fresh.refreshToken() != null
                ? fresh.refreshToken()
                : token.refreshToken();

        accounts.saveToken(
                account.id(),
                fresh.accessToken(),
                refreshToken,
                fresh.scopes(),
                fresh.expiresAt(),
                // Twitch refresh tokens have no expiry date, so there is no hard
                // deadline to warn about.
                null);

        return fresh.accessToken();
    }
}
