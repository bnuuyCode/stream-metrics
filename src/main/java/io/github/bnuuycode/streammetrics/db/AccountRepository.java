package io.github.bnuuycode.streammetrics.db;

import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Reads and writes the {@code account} and {@code oauth_token} tables.
 *
 * <p>Deliberately platform-agnostic: it knows nothing about Twitch. When
 * YouTube arrives it reuses this class untouched.
 *
 * <p>The SQL is written out in full rather than generated. For a schema this
 * size that is a feature — what runs against the database is exactly what is on
 * screen (DECISIONS.md § 1).
 */
public final class AccountRepository {

    private final Jdbi jdbi;

    public AccountRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Inserts the account, or updates the display fields if it already exists,
     * and returns its id.
     *
     * <p>Two statements instead of one clever {@code RETURNING} clause: reading
     * the id back with a plain SELECT is obvious to anyone who opens this file
     * later, and this runs once per login rather than in a hot loop.
     */
    public long saveAccount(String platform, String externalId, String handle, String displayName) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO account (platform, external_id, handle, display_name, created_at)
                        VALUES (:platform, :externalId, :handle, :displayName, :createdAt)
                        ON CONFLICT (platform, external_id) DO UPDATE SET
                            handle = excluded.handle,
                            display_name = excluded.display_name
                        """)
                .bind("platform", platform)
                .bind("externalId", externalId)
                .bind("handle", handle)
                .bind("displayName", displayName)
                .bind("createdAt", now())
                .execute());

        return jdbi.withHandle(h -> h
                .createQuery("SELECT id FROM account WHERE platform = :platform AND external_id = :externalId")
                .bind("platform", platform)
                .bind("externalId", externalId)
                .mapTo(Long.class)
                .one());
    }

    public Optional<StoredAccount> findAccount(String platform) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id, platform, external_id, handle, display_name
                        FROM account
                        WHERE platform = :platform AND enabled = 1
                        """)
                .bind("platform", platform)
                .map((rs, ctx) -> new StoredAccount(
                        rs.getLong("id"),
                        rs.getString("platform"),
                        rs.getString("external_id"),
                        rs.getString("handle"),
                        rs.getString("display_name")))
                .findOne());
    }

    /**
     * Stores or replaces the credentials for an account.
     *
     * @param hardExpiresAt the deadline past which no refresh can help and the
     *                      user must log in by hand. NULL for Twitch, whose
     *                      refresh tokens do not expire on a timer; set for
     *                      Instagram's 60-day sliding window
     *                      (DECISIONS.md § 11).
     */
    public void saveToken(long accountId,
                          String accessToken,
                          String refreshToken,
                          String scopes,
                          Instant accessExpiresAt,
                          Instant hardExpiresAt) {

        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO oauth_token (
                            account_id, access_token, refresh_token, scopes,
                            access_expires_at, hard_expires_at, last_refreshed_at, updated_at)
                        VALUES (
                            :accountId, :accessToken, :refreshToken, :scopes,
                            :accessExpiresAt, :hardExpiresAt, :now, :now)
                        ON CONFLICT (account_id) DO UPDATE SET
                            access_token = excluded.access_token,
                            refresh_token = excluded.refresh_token,
                            scopes = excluded.scopes,
                            access_expires_at = excluded.access_expires_at,
                            hard_expires_at = excluded.hard_expires_at,
                            last_refreshed_at = excluded.last_refreshed_at,
                            updated_at = excluded.updated_at
                        """)
                .bind("accountId", accountId)
                .bind("accessToken", accessToken)
                .bind("refreshToken", refreshToken)
                .bind("scopes", scopes)
                .bind("accessExpiresAt", text(accessExpiresAt))
                .bind("hardExpiresAt", text(hardExpiresAt))
                .bind("now", now())
                .execute());
    }

    public Optional<StoredToken> findToken(long accountId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT account_id, access_token, refresh_token, scopes,
                               access_expires_at, hard_expires_at
                        FROM oauth_token
                        WHERE account_id = :accountId
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new StoredToken(
                        rs.getLong("account_id"),
                        rs.getString("access_token"),
                        rs.getString("refresh_token"),
                        rs.getString("scopes"),
                        instant(rs, "access_expires_at"),
                        instant(rs, "hard_expires_at")))
                .findOne());
    }

    // --- timestamp helpers -------------------------------------------------
    //
    // SQLite has no timestamp type, so instants travel as ISO-8601 text
    // (DECISIONS.md § 6.5). Converting in exactly one place keeps the format
    // from drifting between tables.

    private static String now() {
        return text(Instant.now());
    }

    private static String text(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : Instant.parse(raw);
    }

    public record StoredAccount(
            long id,
            String platform,
            String externalId,
            String handle,
            String displayName) {
    }

    public record StoredToken(
            long accountId,
            String accessToken,
            String refreshToken,
            String scopes,
            Instant accessExpiresAt,
            Instant hardExpiresAt) {

        /** True when the access token is dead or about to be. */
        public boolean needsRefresh() {
            // One minute of slack: a token that expires in twenty seconds will
            // expire mid-request otherwise.
            return accessExpiresAt == null
                    || accessExpiresAt.isBefore(Instant.now().plusSeconds(60));
        }
    }
}
