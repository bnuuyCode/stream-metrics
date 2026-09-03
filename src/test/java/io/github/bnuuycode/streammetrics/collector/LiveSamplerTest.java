package io.github.bnuuycode.streammetrics.collector;

import io.github.bnuuycode.streammetrics.db.AccountRepository;
import io.github.bnuuycode.streammetrics.db.CollectionLog;
import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository;
import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository.PendingSuggestion;
import io.github.bnuuycode.streammetrics.db.StreamRepository;
import io.github.bnuuycode.streammetrics.db.StreamRepository.OpenSession;
import io.github.bnuuycode.streammetrics.db.TestDatabase;
import io.github.bnuuycode.streammetrics.metrics.CollectionException;
import io.github.bnuuycode.streammetrics.metrics.CollectionException.ErrorKind;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable;
import io.github.bnuuycode.streammetrics.metrics.LiveTrackable.LiveSnapshot;
import io.github.bnuuycode.streammetrics.metrics.MetricSample;
import io.github.bnuuycode.streammetrics.metrics.MetricsProvider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards what the collector decides when a broadcast goes quiet.
 *
 * <p>These are the two decisions this project has already got wrong once each,
 * both of them found the hard way: by watching a live broadcast be recorded
 * incorrectly. They share a shape. Getting them wrong does not throw anything, it
 * writes a wrong number into permanent history and carries on.
 *
 * <p>Twitch is a stand-in here, and deliberately so — the point is the decision,
 * not the network. A fake that can be told to answer "on air", "off air", or "I
 * could not reach anyone" is enough to reproduce every case that matters, and it
 * runs in milliseconds without touching a single real account.
 */
class LiveSamplerTest {

    @TempDir
    Path folder;

    private Jdbi jdbi;
    private StreamRepository streams;
    private MergeSuggestionRepository suggestions;
    private CollectionLog runs;
    private long accountId;

    private FakeTwitch twitch;
    private LiveSampler sampler;

    @BeforeEach
    void setUp() {
        jdbi = TestDatabase.freshIn(folder);
        streams = new StreamRepository(jdbi);
        suggestions = new MergeSuggestionRepository(jdbi);
        runs = new CollectionLog(jdbi);

        accountId = new AccountRepository(jdbi).saveAccount("twitch", "111", "someone", "Someone");

        twitch = new FakeTwitch();
        sampler = new LiveSampler(
                List.of(new LiveSampler.Tracked(new FakeProvider(accountId), twitch)),
                streams, suggestions, runs);
    }

    @Test
    @DisplayName("a broadcast that comes back is reopened, not duplicated")
    void sameStreamIdReopensTheSession() {
        // The application was closed while the broadcast was still running, so
        // the session was closed on the strength of its last reading.
        long session = streams.openSession(accountId, "abc", ago(60), "A stream", "Just Chatting");
        streams.addSample(session, ago(50), 7);
        streams.closeSession(session, ago(50));

        // Started again, still the same broadcast as far as Twitch is concerned.
        twitch.onAir(new LiveSnapshot("abc", ago(60), "A stream", "Just Chatting", 9));
        sampler.run();

        // The same session, back on air. A second one would strand the readings
        // already collected in a stub that reads like a separate short broadcast.
        Optional<OpenSession> open = streams.findOpenSession(accountId);
        assertTrue(open.isPresent());
        assertEquals(session, open.get().id());
        assertEquals(1, sessionCount());
    }

    @Test
    @DisplayName("a genuinely different broadcast opens its own session")
    void differentStreamIdOpensASecondSession() {
        streams.openSession(accountId, "abc", ago(600), "Earlier", "Just Chatting");

        twitch.onAir(new LiveSnapshot("xyz", ago(60), "Later", "Just Chatting", 3));
        sampler.run();

        assertEquals(2, sessionCount());
    }

    @Test
    @DisplayName("two broadcasts minutes apart are offered for merging, never merged")
    void closeBroadcastsAreOnlySuggested() {
        long first = streams.openSession(accountId, "abc", ago(3600), "A stream", "Just Chatting");
        streams.addSample(first, ago(180), 7);

        // Back three minutes later under a new id, which is what a dropped
        // connection looks like from here.
        twitch.onAir(new LiveSnapshot("def", ago(60), "A stream", "Just Chatting", 6));
        sampler.run();

        List<PendingSuggestion> pending = suggestions.pending(accountId);
        assertEquals(1, pending.size());
        assertEquals(first, pending.get(0).intoSessionId());

        // Offered as a question. Applying it without being asked would be a
        // decision about someone else's evening, taken by a timer.
        assertEquals(first, streams.groupHead(first));
        assertEquals(2, sessionCount());
    }

    @Test
    @DisplayName("broadcasts far apart are left alone")
    void distantBroadcastsAreNotSuggested() {
        long first = streams.openSession(accountId, "abc", ago(7200), "Morning", "Just Chatting");
        streams.addSample(first, ago(3600), 7);

        twitch.onAir(new LiveSnapshot("def", ago(60), "Evening", "Just Chatting", 6));
        sampler.run();

        assertTrue(suggestions.pending(accountId).isEmpty());
    }

    @Test
    @DisplayName("a session left open by a crash is closed once Twitch confirms it ended")
    void abandonedSessionIsClosedWhenConfirmedOff() {
        long session = streams.openSession(accountId, "abc", ago(3600), "A stream", "Just Chatting");
        streams.addSample(session, ago(1800), 7);

        twitch.offAir();
        sampler.closeAbandonedSessions();

        assertTrue(streams.findOpenSession(accountId).isEmpty());
    }

    @Test
    @DisplayName("a session that only looks abandoned is left alone when still on air")
    void stillLiveSessionSurvivesStartup() {
        long session = streams.openSession(accountId, "abc", ago(3600), "A stream", "Just Chatting");
        streams.addSample(session, ago(1800), 7);

        // The application was closed for half an hour. The broadcast was not.
        twitch.onAir(new LiveSnapshot("abc", ago(3600), "A stream", "Just Chatting", 4));
        sampler.closeAbandonedSessions();

        assertTrue(streams.findOpenSession(accountId).isPresent());
    }

    @Test
    @DisplayName("when Twitch cannot be reached at startup, nothing is closed")
    void unreachableTwitchClosesNothing() {
        long session = streams.openSession(accountId, "abc", ago(3600), "A stream", "Just Chatting");
        streams.addSample(session, ago(1800), 7);

        // Leaving a session open is recoverable on the next poll. Closing a
        // running broadcast is not: its duration is wrong permanently.
        twitch.unreachable();
        sampler.closeAbandonedSessions();

        assertTrue(streams.findOpenSession(accountId).isPresent());
    }

    @Test
    @DisplayName("a poll Twitch could not answer is recorded as a failure, not a gap")
    void unreachableTwitchIsLogged() {
        twitch.unreachable();
        sampler.run();

        // An empty stretch of samples must never be indistinguishable from a
        // collector that never woke up.
        assertEquals(1, runCount());
        assertEquals(1, failedRunCount());
    }

    @Test
    @DisplayName("one platform failing does not stop the others")
    void oneFailingPlatformDoesNotStopTheLoop() {
        FakeTwitch healthy = new FakeTwitch();
        healthy.onAir(new LiveSnapshot("abc", ago(60), "A stream", "Just Chatting", 5));

        LiveSampler two = new LiveSampler(
                List.of(new LiveSampler.Tracked(new ExplodingProvider(), twitch),
                        new LiveSampler.Tracked(new FakeProvider(accountId), healthy)),
                streams, suggestions, runs);

        two.run();

        assertTrue(streams.findOpenSession(accountId).isPresent());
    }

    private int sessionCount() {
        return count("SELECT COUNT(*) FROM stream_session");
    }

    private int runCount() {
        return count("SELECT COUNT(*) FROM collection_run");
    }

    private int failedRunCount() {
        return count("SELECT COUNT(*) FROM collection_run WHERE status = 'ERROR'");
    }

    private int count(String sql) {
        return jdbi.withHandle(h -> h.createQuery(sql).mapTo(Integer.class).one());
    }

    private static Instant ago(int seconds) {
        return Instant.now().minus(Duration.ofSeconds(seconds));
    }

    /** Twitch, told in advance what to say. */
    private static final class FakeTwitch implements LiveTrackable {

        private Optional<LiveSnapshot> answer = Optional.empty();
        private CollectionException failure;

        void onAir(LiveSnapshot snapshot) {
            answer = Optional.of(snapshot);
            failure = null;
        }

        void offAir() {
            answer = Optional.empty();
            failure = null;
        }

        /** Not the same as off air, which is the distinction that matters most here. */
        void unreachable() {
            failure = new CollectionException(ErrorKind.NETWORK, "no route to host");
        }

        @Override
        public Optional<LiveSnapshot> currentStream() {
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /**
     * Deliberately not a record: a component named {@code accountId} would have
     * to expose a {@code long} accessor, and the interface asks for an
     * {@code Optional<Long>} under that same name.
     */
    private static final class FakeProvider implements MetricsProvider {

        private final long accountId;

        FakeProvider(long accountId) {
            this.accountId = accountId;
        }

        @Override
        public String platform() {
            return "twitch";
        }

        @Override
        public Optional<Long> accountId() {
            return Optional.of(accountId);
        }

        @Override
        public List<MetricSample> snapshot() {
            return List.of();
        }
    }

    /** A platform whose account lookup itself fails, before any polling can start. */
    private static final class ExplodingProvider implements MetricsProvider {

        @Override
        public String platform() {
            return "broken";
        }

        @Override
        public Optional<Long> accountId() {
            throw new IllegalStateException("provider is broken");
        }

        @Override
        public List<MetricSample> snapshot() {
            return List.of();
        }
    }
}
