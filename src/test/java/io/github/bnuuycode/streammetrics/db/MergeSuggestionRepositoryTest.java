package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.db.MergeSuggestionRepository.PendingSuggestion;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the open questions the collector leaves behind.
 *
 * <p>A suggestion is a question, not an instruction, and the endpoints that act
 * on one trust this class to say which questions belong to whom. That trust is
 * the reason to check it: an endpoint filtering against the wrong list would
 * accept an id it should refuse, and nothing about the response would look
 * unusual.
 */
class MergeSuggestionRepositoryTest {

    private static final Instant EIGHT_PM = Instant.parse("2026-09-01T20:00:00Z");

    @TempDir
    Path folder;

    private StreamRepository streams;
    private MergeSuggestionRepository suggestions;
    private long accountId;
    private long otherAccountId;

    @BeforeEach
    void setUp() {
        Jdbi jdbi = TestDatabase.freshIn(folder);
        streams = new StreamRepository(jdbi);
        suggestions = new MergeSuggestionRepository(jdbi);

        AccountRepository accounts = new AccountRepository(jdbi);
        accountId = accounts.saveAccount("twitch", "111", "someone", "Someone");
        otherAccountId = accounts.saveAccount("instagram", "222", "other", "Other");
    }

    @Test
    @DisplayName("a question carries both broadcasts and the silence between them")
    void pendingDescribesThePair() {
        long first = finished("1", 0, 60);
        long second = finished("2", 63, 120);

        suggestions.suggest(accountId, second, first, 180);

        PendingSuggestion pending = onlyPending();
        assertEquals(second, pending.sessionId());
        assertEquals(first, pending.intoSessionId());
        assertEquals(180, pending.gapSeconds());

        // Both titles, so the question can be asked in terms of what was on
        // screen rather than two row numbers.
        assertEquals("A stream", pending.title());
        assertEquals("A stream", pending.intoTitle());
    }

    @Test
    @DisplayName("questions belong to one account and are never offered to another")
    void pendingIsScopedToItsAccount() {
        long first = finished("1", 0, 60);
        long second = finished("2", 63, 120);

        suggestions.suggest(accountId, second, first, 180);

        // The filter the merge endpoints rely on to refuse an id that is not
        // this account's to decide.
        assertEquals(1, suggestions.pending(accountId).size());
        assertTrue(suggestions.pending(otherAccountId).isEmpty());
    }

    @Test
    @DisplayName("the same pair is only ever asked about once")
    void suggestingTwiceAsksOnce() {
        long first = finished("1", 0, 60);
        long second = finished("2", 63, 120);

        // The collector polls every minute and would otherwise re-ask on every
        // pass until someone answered.
        suggestions.suggest(accountId, second, first, 180);
        suggestions.suggest(accountId, second, first, 180);

        assertEquals(1, suggestions.pending(accountId).size());
    }

    @Test
    @DisplayName("an answered question stops being asked")
    void decidingClosesTheQuestion() {
        long first = finished("1", 0, 60);
        long second = finished("2", 63, 120);

        suggestions.suggest(accountId, second, first, 180);
        suggestions.decide(onlyPending().id(), MergeSuggestionRepository.DISMISSED);

        assertTrue(suggestions.pending(accountId).isEmpty());
    }

    @Test
    @DisplayName("a question can be looked up only through its own account")
    void findIsScopedToo() {
        long first = finished("1", 0, 60);
        long second = finished("2", 63, 120);

        suggestions.suggest(accountId, second, first, 180);
        long id = onlyPending().id();

        assertTrue(suggestions.find(accountId, id).isPresent());
        assertTrue(suggestions.find(otherAccountId, id).isEmpty());
    }

    private PendingSuggestion onlyPending() {
        List<PendingSuggestion> pending = suggestions.pending(accountId);
        assertEquals(1, pending.size(), "expected exactly one open question");
        return pending.get(0);
    }

    private long finished(String streamId, int startMinute, int endMinute) {
        long id = streams.openSession(
                accountId, streamId, minutes(startMinute), "A stream", "Just Chatting");
        streams.addSample(id, minutes(startMinute), 5);
        streams.closeSession(id, minutes(endMinute));
        return id;
    }

    private static Instant minutes(int offset) {
        return EIGHT_PM.plusSeconds(offset * 60L);
    }
}
