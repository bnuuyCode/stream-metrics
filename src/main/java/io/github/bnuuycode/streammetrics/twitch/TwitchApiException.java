package io.github.bnuuycode.streammetrics.twitch;

/**
 * A call to the Twitch API that did not come back with 200.
 *
 * <p>Carries the HTTP status rather than only a message, because the caller
 * has to react differently to each one: 401 means reconnect, 429 means wait,
 * 500 means Twitch is having a bad day. Flattening all of them into "something
 * went wrong" would make it impossible to tell the user anything useful.
 */
public final class TwitchApiException extends RuntimeException {

    private final int status;

    public TwitchApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public TwitchApiException(String message, Throwable cause) {
        super(message, cause);
        // 0 stands for "never reached Twitch at all" — a network failure rather
        // than a refusal.
        this.status = 0;
    }

    public int status() {
        return status;
    }

    /** A human-readable reason, suited to being shown on the dashboard. */
    public String explain() {
        return switch (status) {
            case 0 -> "Could not reach Twitch";
            case 400 -> "Twitch rejected the request. Affiliate or partner status may be required.";
            case 401 -> "Twitch rejected the token. Reconnect the account.";
            case 403 -> "The token lacks the required permission. Reconnect to grant it.";
            case 429 -> "Rate limited by Twitch. It will recover on its own.";
            default -> "Twitch replied " + status;
        };
    }
}
