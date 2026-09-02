package io.github.bnuuycode.streammetrics.db;

import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Questions the collector asked and has not had answered.
 *
 * <p>When a broadcast appears shortly after another went quiet, the two might be
 * one evening interrupted or two deliberate streams. The API cannot tell them
 * apart and neither can a timer, so the collector records the question and stops
 * (DECISIONS.md § 17).
 *
 * <p>These live in the database rather than in memory because the answer may
 * take days. Someone mid-broadcast cannot stop to decide whether two sessions
 * are one, and forcing it then produces an answer given without attention, which
 * is worse than no answer. Persistence is what makes "decide when you can"
 * possible.
 */
public final class MergeSuggestionRepository {

    public static final String MERGED = "MERGED";
    public static final String DISMISSED = "DISMISSED";

    private final Jdbi jdbi;

    public MergeSuggestionRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Records that two sessions might belong together.
     *
     * <p>Does nothing if the pair has been raised before, whatever the answer
     * was. Re-asking a question already answered "no" is how a helpful prompt
     * turns into nagging.
     */
    public void suggest(long accountId, long sessionId, long intoSessionId, long gapSeconds) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO merge_suggestion (
                            account_id, session_id, into_session_id, gap_seconds, status, created_at)
                        VALUES (:accountId, :sessionId, :intoSessionId, :gap, 'PENDING', :now)
                        ON CONFLICT (session_id, into_session_id) DO NOTHING
                        """)
                .bind("accountId", accountId)
                .bind("sessionId", sessionId)
                .bind("intoSessionId", intoSessionId)
                .bind("gap", gapSeconds)
                .bind("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .execute());
    }

    /**
     * Everything still waiting for an answer, newest first, with enough detail
     * for the dashboard to describe both broadcasts without a second query.
     */
    public List<PendingSuggestion> pending(long accountId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT g.id,
                               g.session_id,
                               g.into_session_id,
                               g.gap_seconds,
                               g.created_at,
                               a.title       AS into_title,
                               a.started_at  AS into_started_at,
                               a.ended_at    AS into_ended_at,
                               b.title       AS title,
                               b.started_at  AS started_at,
                               b.ended_at    AS ended_at
                        FROM merge_suggestion g
                        JOIN stream_session a ON a.id = g.into_session_id
                        JOIN stream_session b ON b.id = g.session_id
                        WHERE g.account_id = :accountId AND g.status = 'PENDING'
                        ORDER BY g.created_at DESC
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new PendingSuggestion(
                        rs.getLong("id"),
                        rs.getLong("session_id"),
                        rs.getLong("into_session_id"),
                        rs.getLong("gap_seconds"),
                        rs.getString("into_title"),
                        instant(rs, "into_started_at"),
                        instant(rs, "into_ended_at"),
                        rs.getString("title"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at")))
                .list());
    }

    /** One suggestion, for acting on it. */
    public Optional<PendingSuggestion> find(long accountId, long suggestionId) {
        return pending(accountId).stream()
                .filter(s -> s.id() == suggestionId)
                .findFirst();
    }

    /**
     * Writes down the answer.
     *
     * <p>Answered suggestions are kept rather than deleted. "I already said
     * these are separate" is worth remembering, or the same question comes back
     * forever.
     */
    public void decide(long suggestionId, String status) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE merge_suggestion
                        SET status = :status, decided_at = :now
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .bind("id", suggestionId)
                .bind("status", status)
                .bind("now", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .execute());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : Instant.parse(raw);
    }

    /**
     * @param intoSessionId the earlier broadcast, which would become the head
     * @param sessionId     the later one, which would join it
     * @param gapSeconds    silence between them — the only evidence there is,
     *                      and deliberately shown rather than interpreted
     */
    public record PendingSuggestion(
            long id,
            long sessionId,
            long intoSessionId,
            long gapSeconds,
            String intoTitle,
            Instant intoStartedAt,
            Instant intoEndedAt,
            String title,
            Instant startedAt,
            Instant endedAt) {
    }
}
