package io.github.bnuuycode.streammetrics.twitch;

import io.github.bnuuycode.streammetrics.db.AccountRepository.StoredAccount;
import io.github.bnuuycode.streammetrics.metrics.ArchiveVerifiable;
import io.github.bnuuycode.streammetrics.metrics.CollectionException;
import io.github.bnuuycode.streammetrics.metrics.CollectionException.ErrorKind;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable;
import io.github.bnuuycode.streammetrics.metrics.MetricKey;
import io.github.bnuuycode.streammetrics.metrics.MetricSample;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Twitch, seen through the interfaces the collector understands.
 *
 * <p>This class is the whole point of the seam: everything Twitch-specific
 * stops here. The scheduler above it deals only in {@link MetricsProvider} and
 * {@link LiveTrackable}, and never learns that Twitch exists.
 *
 * <p>Implements both interfaces because Twitch genuinely does both. A platform
 * without broadcasts implements only the first.
 */
public final class TwitchProvider implements MetricsProvider, LiveTrackable, ArchiveVerifiable {

    private static final Logger log = LoggerFactory.getLogger(TwitchProvider.class);

    private final TwitchSession session;

    public TwitchProvider(TwitchSession session) {
        this.session = session;
    }

    @Override
    public String platform() {
        return TwitchSession.PLATFORM;
    }

    @Override
    public Optional<Long> accountId() {
        return session.account().map(StoredAccount::id);
    }

    @Override
    public List<MetricSample> snapshot() {
        StoredAccount account = requireAccount();
        String token = token(account);
        String id = account.externalId();

        List<MetricSample> samples = new ArrayList<>();

        // Followers must succeed — if this fails the whole collection failed.
        samples.add(MetricSample.of(MetricKey.FOLLOWERS, call(() -> session.client().followers(id, token))));

        // Subscribers are allowed to be missing. A channel that is not an
        // affiliate gets a 400 here, and that is a fact about the channel, not
        // a failed collection. Recording a zero instead would be worse than
        // recording nothing: a fabricated zero is indistinguishable from a real
        // one, and it would sit in the history forever (DECISIONS.md § 6.2).
        try {
            samples.add(MetricSample.of(MetricKey.SUBSCRIBERS, session.client().subscribers(id, token)));
        } catch (TwitchApiException e) {
            log.debug("Subscriber count unavailable: {}", e.explain());
        }

        return samples;
    }

    @Override
    public Optional<LiveSnapshot> currentStream() {
        StoredAccount account = requireAccount();
        String token = token(account);
        return call(() -> session.client().currentStream(account.externalId(), token));
    }

    @Override
    public Optional<Duration> archivedDuration(String externalStreamId) {
        StoredAccount account = requireAccount();
        String token = token(account);
        return call(() -> session.client()
                .archivedDuration(account.externalId(), externalStreamId, token));
    }

    private StoredAccount requireAccount() {
        return session.account().orElseThrow(() ->
                new CollectionException(ErrorKind.AUTH, "No Twitch account connected"));
    }

    private String token(StoredAccount account) {
        try {
            return session.accessToken(account);
        } catch (TwitchApiException e) {
            throw translate(e);
        } catch (RuntimeException e) {
            throw new CollectionException(ErrorKind.AUTH, "Could not obtain a Twitch token: " + e.getMessage(), e);
        }
    }

    /**
     * Runs a Twitch call, translating its failures into the shared vocabulary.
     *
     * <p>This translation is the provider's real job. Above this line nobody
     * knows what an HTTP 429 is; they only know that some failures fix
     * themselves and others need a human.
     */
    private <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (TwitchApiException e) {
            throw translate(e);
        }
    }

    private static CollectionException translate(TwitchApiException e) {
        // A reply that arrived but could not be read is its own category. It is
        // the one failure that would otherwise look like success, so it must be
        // recorded distinctly in collection_run rather than lumped into UNKNOWN.
        if (e.isMalformed()) {
            return new CollectionException(ErrorKind.PARSE, e.getMessage(), e);
        }

        ErrorKind kind = switch (e.status()) {
            case 0 -> ErrorKind.NETWORK;
            case 401, 403 -> ErrorKind.AUTH;
            case 429 -> ErrorKind.RATE_LIMIT;
            default -> ErrorKind.UNKNOWN;
        };
        return new CollectionException(kind, e.explain(), e);
    }
}
