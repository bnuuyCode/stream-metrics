package io.github.bnuuycode.streammetrics.db;

import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Live sessions, the segments they are made of, and the viewer samples taken
 * during them.
 *
 * <p>A <em>session</em> is the broadcast as the streamer experienced it. A
 * <em>segment</em> is one Twitch stream id. They are not the same thing: Twitch
 * issues a new id every time a connection drops and returns, so one evening can
 * be several segments. See {@code V2__stream_segments.sql}.
 */
public final class StreamRepository {

    private final Jdbi jdbi;

    public StreamRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** The session currently marked as on air, if any. */
    public Optional<OpenSession> findOpenSession(long accountId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id, external_stream_id, started_at
                        FROM stream_session
                        WHERE account_id = :accountId AND ended_at IS NULL
                        ORDER BY started_at DESC
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new OpenSession(
                        rs.getLong("id"),
                        rs.getString("external_stream_id"),
                        instant(rs, "started_at")))
                .findFirst());
    }

    /** Opens a session and records its first segment. */
    public long openSession(long accountId, String streamId, Instant startedAt, String title, String category) {
        long sessionId = jdbi.withHandle(h -> {
            h.createUpdate("""
                            INSERT INTO stream_session (
                                account_id, external_stream_id, started_at, title, category)
                            VALUES (:accountId, :streamId, :startedAt, :title, :category)
                            ON CONFLICT (account_id, external_stream_id) DO UPDATE SET
                                title = excluded.title,
                                category = excluded.category
                            """)
                    .bind("accountId", accountId)
                    .bind("streamId", streamId)
                    .bind("startedAt", text(startedAt))
                    .bind("title", title)
                    .bind("category", category)
                    .execute();

            return h.createQuery("""
                            SELECT id FROM stream_session
                            WHERE account_id = :accountId AND external_stream_id = :streamId
                            """)
                    .bind("accountId", accountId)
                    .bind("streamId", streamId)
                    .mapTo(Long.class)
                    .one();
        });

        addSegment(sessionId, streamId, startedAt);
        return sessionId;
    }

    /**
     * Attaches another Twitch stream id to an existing session.
     *
     * <p>Called when a broadcast comes back after a drop: same evening, new id.
     * The previous segment is closed at its last sample, so the gap is recorded
     * rather than smoothed over.
     */
    public void addSegment(long sessionId, String streamId, Instant startedAt) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO stream_segment (session_id, external_stream_id, started_at)
                        VALUES (:sessionId, :streamId, :startedAt)
                        ON CONFLICT (session_id, external_stream_id) DO NOTHING
                        """)
                .bind("sessionId", sessionId)
                .bind("streamId", streamId)
                .bind("startedAt", text(startedAt))
                .execute());
    }

    /** Whether this session already contains that Twitch stream id. */
    public boolean hasSegment(long sessionId, String streamId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT COUNT(*) FROM stream_segment
                        WHERE session_id = :sessionId AND external_stream_id = :streamId
                        """)
                .bind("sessionId", sessionId)
                .bind("streamId", streamId)
                .mapTo(Integer.class)
                .one()) > 0;
    }

    /** Closes any segment of this session still marked as running. */
    public void closeOpenSegments(long sessionId, Instant endedAt) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE stream_segment SET ended_at = :endedAt
                        WHERE session_id = :sessionId AND ended_at IS NULL
                        """)
                .bind("sessionId", sessionId)
                .bind("endedAt", text(endedAt))
                .execute());
    }

    /** Keeps title and category current — streamers change them mid-broadcast. */
    public void updateSessionInfo(long sessionId, String title, String category) {
        jdbi.useHandle(h -> h
                .createUpdate("UPDATE stream_session SET title = :title, category = :category WHERE id = :id")
                .bind("id", sessionId)
                .bind("title", title)
                .bind("category", category)
                .execute());
    }

    public void addSample(long sessionId, Instant at, int viewers) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        INSERT INTO viewer_sample (session_id, sampled_at, viewers)
                        VALUES (:sessionId, :at, :viewers)
                        ON CONFLICT (session_id, sampled_at) DO NOTHING
                        """)
                .bind("sessionId", sessionId)
                .bind("at", text(at))
                .bind("viewers", viewers)
                .execute());
    }

    /**
     * Closes a session and its segments, filling in the summary from its own
     * samples.
     *
     * <p>These are computed values being stored — the one deliberate exception
     * in the schema (DECISIONS.md § 6.1). It is allowed precisely because the
     * samples they come from get deleted at ninety days, which turns the summary
     * from a cache into the only surviving record.
     *
     * <p>The average is taken across every sample of the session, which spans
     * the segments. Averaging per segment and then averaging those would weigh a
     * two-minute reconnect the same as a four-hour stretch.
     */
    public void closeSession(long sessionId, Instant endedAt) {
        closeOpenSegments(sessionId, endedAt);

        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE stream_session SET
                            ended_at = :endedAt,
                            peak_viewers  = (SELECT MAX(viewers) FROM viewer_sample WHERE session_id = :id),
                            avg_viewers   = (SELECT AVG(viewers) FROM viewer_sample WHERE session_id = :id),
                            sample_count  = (SELECT COUNT(*)     FROM viewer_sample WHERE session_id = :id)
                        WHERE id = :id
                        """)
                .bind("id", sessionId)
                .bind("endedAt", text(endedAt))
                .execute());
    }

    /**
     * Peak and sample count so far for a session still in progress.
     *
     * <p>Computed on read rather than stored. While a session is open these
     * numbers change every minute, and a stored copy would be wrong between
     * updates — the exact failure mode DECISIONS.md § 6.1 exists to prevent.
     * They are only written down at close, when the samples behind them are
     * scheduled for deletion.
     */
    public LiveStats currentStats(long sessionId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT COALESCE(MAX(viewers), 0) AS peak, COUNT(*) AS samples
                        FROM viewer_sample WHERE session_id = :id
                        """)
                .bind("id", sessionId)
                .map((rs, ctx) -> new LiveStats(rs.getLong("peak"), rs.getInt("samples")))
                .one());
    }

    /** The most recent finished broadcast, for the off-air view. */
    public Optional<FinishedSession> findLastFinishedSession(long accountId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT title, category, started_at, ended_at, peak_viewers, avg_viewers
                        FROM stream_session
                        WHERE account_id = :accountId AND ended_at IS NOT NULL
                        ORDER BY ended_at DESC
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new FinishedSession(
                        rs.getString("title"),
                        rs.getString("category"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at"),
                        rs.getObject("peak_viewers") == null ? null : rs.getLong("peak_viewers"),
                        rs.getObject("avg_viewers") == null ? null : rs.getDouble("avg_viewers")))
                .findFirst());
    }

    /**
     * The most recent finished broadcasts, newest first.
     *
     * <p>Cheap by nature: a local read over a small table, no network involved.
     * The limit exists to keep the dashboard readable, not to save work.
     */
    public List<FinishedSession> findRecentSessions(long accountId, int limit) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT title, category, started_at, ended_at, peak_viewers, avg_viewers
                        FROM stream_session
                        WHERE account_id = :accountId AND ended_at IS NOT NULL
                        ORDER BY ended_at DESC
                        LIMIT :limit
                        """)
                .bind("accountId", accountId)
                .bind("limit", limit)
                .map((rs, ctx) -> new FinishedSession(
                        rs.getString("title"),
                        rs.getString("category"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at"),
                        rs.getObject("peak_viewers") == null ? null : rs.getLong("peak_viewers"),
                        rs.getObject("avg_viewers") == null ? null : rs.getDouble("avg_viewers")))
                .list());
    }

    /** When the last sample of a session was taken. */
    public Optional<Instant> lastSampleAt(long sessionId) {
        return jdbi.withHandle(h -> h
                .createQuery("SELECT MAX(sampled_at) FROM viewer_sample WHERE session_id = :id")
                .bind("id", sessionId)
                .mapTo(String.class)
                .findOne()
                .map(Instant::parse));
    }

    /**
     * Sessions left open by an application that died mid-broadcast.
     *
     * <p>Without this, {@code ended_at} stays NULL forever and the session shows
     * as live for eternity (DECISIONS.md § 11).
     */
    public List<OpenSession> findStaleOpenSessions(Instant cutoff) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT s.id, s.external_stream_id, s.started_at
                        FROM stream_session s
                        WHERE s.ended_at IS NULL
                          AND COALESCE(
                                (SELECT MAX(sampled_at) FROM viewer_sample WHERE session_id = s.id),
                                s.started_at) < :cutoff
                        """)
                .bind("cutoff", text(cutoff))
                .map((rs, ctx) -> new OpenSession(
                        rs.getLong("id"),
                        rs.getString("external_stream_id"),
                        instant(rs, "started_at")))
                .list());
    }

    private static String text(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        return raw == null ? null : Instant.parse(raw);
    }

    /**
     * @param streamId the FIRST Twitch stream id of this session. Later segments
     *                 live in {@code stream_segment}.
     */
    public record OpenSession(long id, String streamId, Instant startedAt) {
    }

    /** Running totals of a broadcast still in progress. */
    public record LiveStats(long peakViewers, int sampleCount) {
    }

    /** A broadcast that has ended, with its stored summary. */
    public record FinishedSession(
            String title,
            String category,
            Instant startedAt,
            Instant endedAt,
            Long peakViewers,
            Double avgViewers) {
    }
}
