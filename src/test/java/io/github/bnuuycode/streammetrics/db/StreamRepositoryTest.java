package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.db.StreamRepository.SessionGroup;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the sums behind the history of a broadcast.
 *
 * <p>Every figure checked here has the same dangerous property: when it goes
 * wrong it stays plausible. A duration that quietly includes a forty-minute
 * disconnection is still a number of hours, and an average computed the easy way
 * instead of the right way is still a number of viewers. Nothing on the dashboard
 * would look broken, and by the time the samples are pruned at ninety days the
 * stored summary is the only surviving record — wrong permanently.
 *
 * <p>Until now the only thing checking this code was someone noticing a strange
 * figure mid-broadcast. That worked twice, which is twice more than it should
 * have had to.
 */
class StreamRepositoryTest {

    private static final Instant EIGHT_PM = Instant.parse("2026-09-01T20:00:00Z");

    @TempDir
    Path folder;

    private StreamRepository streams;
    private long accountId;
    private long otherAccountId;

    @BeforeEach
    void setUp() {
        Jdbi jdbi = TestDatabase.freshIn(folder);
        streams = new StreamRepository(jdbi);

        AccountRepository accounts = new AccountRepository(jdbi);
        accountId = accounts.saveAccount("twitch", "111", "someone", "Someone");
        otherAccountId = accounts.saveAccount("instagram", "222", "other", "Other");
    }

    @Test
    @DisplayName("a merged group counts time on air, not the gap between parts")
    void mergeExcludesTheGap() {
        // An hour, dropped, back half an hour later for another half hour.
        long first = finished("1", minutes(0), minutes(60), 5);
        long second = finished("2", minutes(90), minutes(120), 5);

        streams.mergeAll(List.of(first, second));

        SessionGroup group = onlyGroup();

        // Ninety minutes on air. The span from the first start to the last end is
        // two hours, and reporting that would hand back thirty minutes of being
        // disconnected as though it were broadcasting.
        assertEquals(5400, group.onAirSeconds());
        assertEquals(2, group.parts());
    }

    @Test
    @DisplayName("the average is weighted by how long each part actually ran")
    void averageIsWeightedBySampleCount() {
        // A long quiet stretch and a brief busy one. Averaging the two averages
        // would let two minutes count as much as an hour.
        long first = open("1", minutes(0));
        for (int i = 0; i < 60; i++) {
            streams.addSample(first, minutes(i), 10);
        }
        streams.closeSession(first, minutes(60));

        long second = open("2", minutes(90));
        streams.addSample(second, minutes(90), 100);
        streams.addSample(second, minutes(91), 100);
        streams.closeSession(second, minutes(120));

        streams.mergeAll(List.of(first, second));

        // (10 x 60 + 100 x 2) / 62 = 12.9. The average of 10 and 100 is 55, which
        // is the answer this test exists to refuse.
        assertEquals(12.90, onlyGroup().avgViewers(), 0.01);
    }

    @Test
    @DisplayName("peak is the highest reading anywhere in the group")
    void peakSpansTheWholeGroup() {
        long first = finished("1", minutes(0), minutes(60), 4);
        long second = finished("2", minutes(90), minutes(120), 17);

        streams.mergeAll(List.of(first, second));

        assertEquals(17, onlyGroup().peakViewers());
    }

    @Test
    @DisplayName("readings taken after the broadcast ended do not count")
    void ghostTailIsExcluded() {
        long session = open("1", minutes(0));
        streams.addSample(session, minutes(10), 5);

        // Twitch keeps listing a stream for some minutes after it stops. Those
        // readings describe a broadcast that was already over.
        streams.addSample(session, minutes(65), 999);

        streams.closeSession(session, minutes(60));

        SessionGroup group = onlyGroup();
        assertEquals(5, group.peakViewers());
        assertEquals(1, group.sampleCount());
    }

    @Test
    @DisplayName("the earliest broadcast leads the group, whatever order was picked")
    void earliestBecomesTheHead() {
        long first = finished("1", minutes(0), minutes(60), 5);
        long second = finished("2", minutes(90), minutes(120), 5);

        // Ticked bottom-up in the interface, which must not change the outcome.
        streams.mergeAll(List.of(second, first));

        assertEquals(first, streams.groupHead(second));
        assertEquals(first, streams.groupHead(first));
    }

    @Test
    @DisplayName("merging into a merged broadcast joins the group, without chaining")
    void mergesDoNotChain() {
        long first = finished("1", minutes(0), minutes(60), 5);
        long second = finished("2", minutes(90), minutes(120), 5);
        long third = finished("3", minutes(150), minutes(180), 5);

        streams.merge(second, first);
        streams.merge(third, second);

        // Pointed at the head, not at the middle. A chain would make the group
        // depend on how many hops a reader is willing to follow.
        assertEquals(first, streams.groupHead(third));
        assertEquals(3, onlyGroup().parts());
    }

    @Test
    @DisplayName("unmerging leaves two separate broadcasts again")
    void unmergeRestoresBothParts() {
        long first = finished("1", minutes(0), minutes(60), 5);
        long second = finished("2", minutes(90), minutes(120), 5);

        streams.mergeAll(List.of(first, second));
        assertEquals(1, streams.findRecentGroups(accountId, 10).size());

        streams.unmerge(second);

        // Nothing was destroyed to build the group, so undoing it loses nothing.
        assertEquals(2, streams.findRecentGroups(accountId, 10).size());
        assertEquals(second, streams.groupHead(second));
    }

    @Test
    @DisplayName("reopening clears the summary written at the premature close")
    void reopeningRecomputesOverEverything() {
        // The application was shut down mid-broadcast, so the session was closed
        // on the strength of the last sample. It was still on air.
        long session = open("1", minutes(0));
        streams.addSample(session, minutes(10), 5);
        streams.closeSession(session, minutes(10));

        streams.reopenSession(session);
        assertTrue(streams.findOpenSession(accountId).isPresent());

        // Back on air, and busier than before.
        streams.addSample(session, minutes(40), 30);
        streams.closeSession(session, minutes(60));

        SessionGroup group = onlyGroup();

        // Both stretches, not just the one before the interruption. Leaving the
        // first summary in place would report a peak of five for a broadcast that
        // reached thirty.
        assertEquals(30, group.peakViewers());
        assertEquals(2, group.sampleCount());
        assertEquals(3600, group.onAirSeconds());
    }

    @Test
    @DisplayName("a broadcast still on air is reported as such before any merge")
    void stillLiveIsDetected() {
        long done = finished("1", minutes(0), minutes(60), 5);
        long live = open("2", minutes(90));

        assertTrue(streams.anyStillLive(List.of(done, live)));
        assertFalse(streams.anyStillLive(List.of(done)));
        assertFalse(streams.anyStillLive(List.of()));
    }

    @Test
    @DisplayName("a broadcast belongs only to the account that made it")
    void ownershipIsCheckable() {
        long session = finished("1", minutes(0), minutes(60), 5);

        assertTrue(streams.belongsTo(session, accountId));
        assertFalse(streams.belongsTo(session, otherAccountId));
        assertFalse(streams.belongsTo(999, accountId));
    }

    @Test
    @DisplayName("a broadcast with no samples reports no figures rather than zero")
    void noSamplesIsNotZero() {
        long session = open("1", minutes(0));
        streams.closeSession(session, minutes(60));

        SessionGroup group = onlyGroup();

        // Nobody watched and nothing was measured are different statements, and
        // only one of them is true here.
        assertNull(group.peakViewers());
        assertNull(group.avgViewers());
        assertEquals(0, group.sampleCount());
    }

    private long open(String streamId, Instant startedAt) {
        return streams.openSession(accountId, streamId, startedAt, "A stream", "Just Chatting");
    }

    /** A closed broadcast with one reading in it, so the summary has something to sum. */
    private long finished(String streamId, Instant startedAt, Instant endedAt, int viewers) {
        long id = open(streamId, startedAt);
        streams.addSample(id, startedAt, viewers);
        streams.closeSession(id, endedAt);
        return id;
    }

    private SessionGroup onlyGroup() {
        List<SessionGroup> groups = streams.findRecentGroups(accountId, 10);
        assertEquals(1, groups.size(), "expected exactly one group");
        return groups.get(0);
    }

    private static Instant minutes(int offset) {
        return EIGHT_PM.plusSeconds(offset * 60L);
    }
}
