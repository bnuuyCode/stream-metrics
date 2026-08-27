package io.github.bnuuycode.streammetrics.twitch;

import java.time.Instant;

/**
 * What Twitch hands back after a successful OAuth exchange.
 *
 * @param accessToken  short-lived, roughly four hours. Sent with every API call.
 * @param refreshToken long-lived. Used to obtain a new access token without
 *                     asking the user to log in again. Twitch refresh tokens do
 *                     not expire on a timer — they die only if revoked or if
 *                     the account password changes. That is why
 *                     {@code hard_expires_at} stays NULL for Twitch, unlike
 *                     Instagram (DECISIONS.md § 11).
 * @param expiresAt    when {@code accessToken} stops working.
 * @param scopes       what this token is actually allowed to read. Stored so
 *                     that adding a metric requiring a new scope produces a
 *                     clear "log in again" instead of a cryptic 403.
 */
public record TwitchTokens(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String scopes) {
}
