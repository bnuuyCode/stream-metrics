package io.github.bnuuycode.streammetrics.twitch;

/**
 * A call to the Twitch API that did not produce a usable answer.
 *
 * <p>Carries the HTTP status rather than only a message, because the caller has
 * to react differently to each one: 401 means reconnect, 429 means wait, 500
 * means Twitch is having a bad day. Flattening all of them into "something went
 * wrong" would make it impossible to tell the user anything useful.
 */
public final class TwitchApiException extends RuntimeException {

    /** Never reached Twitch at all — a network failure rather than a refusal. */
    static final int UNREACHABLE = 0;

    /**
     * The reply arrived, and made no sense.
     *
     * <p>Its own category on purpose. A malformed 200 is more dangerous than an
     * honest 500: the HTTP layer reports success, so without this the parser
     * would fall back on defaults and hand back a number nobody measured.
     */
    static final int MALFORMED = -1;

    private final int status;

    public TwitchApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public TwitchApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = UNREACHABLE;
    }

    static TwitchApiException malformed(String detail) {
        return new TwitchApiException(MALFORMED, detail);
    }

    public int status() {
        return status;
    }

    public boolean isMalformed() {
        return status == MALFORMED;
    }

    /**
     * Turns any failure into something worth reading on the dashboard.
     *
     * <p>Static and public because every Twitch service needs it and none of
     * them should carry its own copy.
     */
    public static String describe(RuntimeException e) {
        if (e instanceof TwitchApiException twitch) {
            return twitch.explain();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** A human-readable reason, suited to being shown on the dashboard. */
    public String explain() {
        if (status == MALFORMED) {
            return "Twitch sent a reply this application could not read. "
                    + "No number is shown rather than a guessed one.";
        }
        return switch (status) {
            case UNREACHABLE -> "Could not reach Twitch";
            case 400 -> "Twitch rejected the request. Affiliate or partner status may be required.";
            case 401 -> "Twitch rejected the token. Reconnect the account.";
            case 403 -> "The token lacks the required permission. Reconnect to grant it.";
            case 429 -> "Rate limited by Twitch. It will recover on its own.";
            default -> "Twitch replied " + status;
        };
    }
}
