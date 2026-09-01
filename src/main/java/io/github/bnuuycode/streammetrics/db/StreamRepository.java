package io.github.bnuuycode.streammetrics.db;

import org.jdbi.v3.core.Jdbi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Live sessions and the viewer samples taken during them.
 *
 * <p>A session is exactly one Twitch stream id — the smallest thing the platform
 * actually tells us about. Twitch issues a new id every time a connection drops
 * and returns, so an evening interrupted by a dead connection arrives here as
 * several sessions.
 *
 * <p>Whether those belong together is not decided here. Grouping happens through
 * {@code merged_into_id}, and only when a person says so: a dropped connection
 * and a deliberate restart are identical through the API, and guessing between
 * them from timing produces errors nobody ever notices (DECISIONS.md § 17).
 *
 * <p>Because grouping is a link rather than a rewrite, it is reversible and
 * nothing is destroyed to create it.
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

    /** Opens a session for one Twitch stream id. */
    public long openSession(long accountId, String streamId, Instant startedAt, String title, String category) {
        return jdbi.withHandle(h -> {
            // status is set explicitly rather than left to the column default,
            // which is FINAL — right for rows that already existed when the
            // column was added, wrong for a broadcast that just started.
            h.createUpdate("""
                            INSERT INTO stream_session (
                                account_id, external_stream_id, started_at, title, category, status)
                            VALUES (:accountId, :streamId, :startedAt, :title, :category, 'LIVE')
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
     * Closes a session, filling in the summary from its own samples.
     *
     * <p>These are computed values being stored — the one deliberate exception
     * in the schema (DECISIONS.md § 6.1). It is allowed precisely because the
     * samples they come from get deleted at ninety days, which turns the summary
     * from a cache into the only surviving record.
     *
     * <p>Storing {@code sample_count} alongside the average is what lets merged
     * groups be combined correctly long after the samples are gone: a weighted
     * mean needs the weights, and an average of averages would let a two-minute
     * reconnection count for as much as a four-hour stretch.
     */
    public void closeSession(long sessionId, Instant endedAt) {
        summarise(sessionId, endedAt, "SETTLING", "SAMPLES");
    }

    /**
     * Settles a broadcast for good, on the strength of whatever evidence turned
     * out to be available.
     *
     * @param endedAt the corrected end when the archive supplied one, otherwise
     *                the last sample already recorded
     * @param source  {@code VOD} or {@code SAMPLES} — kept because a duration
     *                nobody can trace to how it was obtained is a duration
     *                nobody can check
     */
    public void finalise(long sessionId, Instant endedAt, String source) {
        summarise(sessionId, endedAt, "FINAL", source);
    }

    /**
     * Recomputes the stored summary over the samples that fall inside the
     * broadcast.
     *
     * <p>The {@code sampled_at <= endedAt} bound is what discards the ghost tail.
     * Twitch keeps listing a stream for some minutes after it ends, so those last
     * readings describe a broadcast that was already over; counting them
     * stretches the duration and drags the average down.
     *
     * <p>They are excluded rather than deleted. They are real observations of
     * what Twitch reported, and throwing away evidence to make a number tidier is
     * the opposite of what this project is for.
     */
    private void summarise(long sessionId, Instant endedAt, String status, String source) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE stream_session SET
                            ended_at = :endedAt,
                            status = :status,
                            end_source = :source,
                            peak_viewers = (SELECT MAX(viewers) FROM viewer_sample
                                            WHERE session_id = :id AND sampled_at <= :endedAt),
                            avg_viewers  = (SELECT AVG(viewers) FROM viewer_sample
                                            WHERE session_id = :id AND sampled_at <= :endedAt),
                            sample_count = (SELECT COUNT(*)     FROM viewer_sample
                                            WHERE session_id = :id AND sampled_at <= :endedAt)
                        WHERE id = :id
                        """)
                .bind("id", sessionId)
                .bind("endedAt", text(endedAt))
                .bind("status", status)
                .bind("source", source)
                .execute());
    }

    /** Broadcasts that have ended but whose figures may still move. */
    public List<ExistingSession> findSettling(long accountId, Instant endedBefore) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id, external_stream_id, started_at, ended_at
                        FROM stream_session
                        WHERE account_id = :accountId
                          AND status = 'SETTLING'
                          AND ended_at < :cutoff
                        ORDER BY ended_at
                        """)
                .bind("accountId", accountId)
                .bind("cutoff", text(endedBefore))
                .map((rs, ctx) -> new ExistingSession(
                        rs.getLong("id"),
                        rs.getString("external_stream_id"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at")))
                .list());
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
    public List<SessionGroup> findRecentGroups(long accountId, int limit) {
        // Two queries rather than one: the limit counts groups, and a single
        // query would have to limit rows, which is a different thing whenever
        // anything is merged.
        List<Long> heads = jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT COALESCE(merged_into_id, id) AS head
                        FROM stream_session
                        WHERE account_id = :accountId AND ended_at IS NOT NULL
                        GROUP BY head
                        ORDER BY MAX(ended_at) DESC
                        LIMIT :limit
                        """)
                .bind("accountId", accountId)
                .bind("limit", limit)
                .mapTo(Long.class)
                .list());

        if (heads.isEmpty()) {
            return List.of();
        }

        List<SessionPart> parts = jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT COALESCE(merged_into_id, id) AS head,
                               title, category, started_at, ended_at,
                               peak_viewers, avg_viewers, sample_count,
                               status, end_source
                        FROM stream_session
                        WHERE account_id = :accountId
                          AND ended_at IS NOT NULL
                          AND COALESCE(merged_into_id, id) IN (<heads>)
                        ORDER BY started_at
                        """)
                .bind("accountId", accountId)
                .bindList("heads", heads)
                .map((rs, ctx) -> new SessionPart(
                        rs.getLong("head"),
                        rs.getString("title"),
                        rs.getString("category"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at"),
                        rs.getObject("peak_viewers") == null ? null : rs.getLong("peak_viewers"),
                        rs.getObject("avg_viewers") == null ? null : rs.getDouble("avg_viewers"),
                        rs.getObject("sample_count") == null ? 0 : rs.getInt("sample_count"),
                        rs.getString("status"),
                        rs.getString("end_source")))
                .list());

        // Combined in Java on purpose. Duration is the sum of time actually on
        // air, never the span from first start to last end — a broadcast with a
        // forty-minute break in the middle did not last forty minutes longer.
        // Expressing that in SQL would mean date arithmetic over ISO text, which
        // is exactly the kind of cleverness that reads wrong later.
        return heads.stream()
                .map(head -> combine(head, parts.stream().filter(p -> p.head() == head).toList()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static SessionGroup combine(long head, List<SessionPart> parts) {
        if (parts.isEmpty()) {
            return null;
        }

        long onAirSeconds = 0;
        long expectedSamples = 0;
        int sampleCount = 0;
        double weightedViewerMinutes = 0;
        Long peak = null;

        for (SessionPart part : parts) {
            Duration onAir = Duration.between(part.startedAt(), part.endedAt());
            onAirSeconds += onAir.getSeconds();
            expectedSamples += Math.max(1, onAir.toMinutes());
            sampleCount += part.sampleCount();

            if (part.avgViewers() != null) {
                weightedViewerMinutes += part.avgViewers() * part.sampleCount();
            }
            if (part.peakViewers() != null && (peak == null || part.peakViewers() > peak)) {
                peak = part.peakViewers();
            }
        }

        SessionPart first = parts.get(0);
        SessionPart last = parts.get(parts.size() - 1);

        // A group is only as settled as its least settled part. One broadcast
        // still waiting on its archive means the combined figures can still
        // move, and saying otherwise would be the exact overstatement the
        // settling states exist to prevent.
        boolean settling = parts.stream().anyMatch(p -> "SETTLING".equals(p.status()));

        // When the parts were settled by different means, the group inherits the
        // weaker one. A duration that is part measured and part estimated is an
        // estimate.
        boolean allFromArchive = parts.stream().allMatch(p -> "VOD".equals(p.endSource()));

        return new SessionGroup(
                head,
                first.title(),
                first.category(),
                first.startedAt(),
                last.endedAt(),
                onAirSeconds,
                peak,
                sampleCount == 0 ? null : weightedViewerMinutes / sampleCount,
                sampleCount,
                expectedSamples,
                parts.size(),
                settling ? "SETTLING" : "FINAL",
                allFromArchive ? "VOD" : "SAMPLES");
    }

    /**
     * Any session for this Twitch stream id, open or already closed.
     *
     * <p>Needed because a session can be closed while the broadcast is in fact
     * still running — the application being shut down for a while is enough.
     * When the same stream id turns up again, it must be recognised as the
     * session it is rather than treated as unknown.
     */
    public Optional<ExistingSession> findSessionByStreamId(long accountId, String streamId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id, external_stream_id, started_at, ended_at
                        FROM stream_session
                        WHERE account_id = :accountId AND external_stream_id = :streamId
                        """)
                .bind("accountId", accountId)
                .bind("streamId", streamId)
                .map((rs, ctx) -> new ExistingSession(
                        rs.getLong("id"),
                        rs.getString("external_stream_id"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at")))
                .findOne());
    }

    /**
     * The broadcast that finished most recently.
     *
     * <p>Used when a new one starts with nothing currently open, to see whether
     * the two are close enough in time to be worth asking about.
     */
    public Optional<ExistingSession> findMostRecentFinished(long accountId) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id, external_stream_id, started_at, ended_at
                        FROM stream_session
                        WHERE account_id = :accountId AND ended_at IS NOT NULL
                        ORDER BY ended_at DESC
                        """)
                .bind("accountId", accountId)
                .map((rs, ctx) -> new ExistingSession(
                        rs.getLong("id"),
                        rs.getString("external_stream_id"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at")))
                .findFirst());
    }

    /**
     * Puts a session back on air.
     *
     * <p>The stored summary is cleared along with {@code ended_at}: peak and
     * average computed at the premature close describe only part of the
     * broadcast, and leaving them behind would be worse than having none — they
     * look like finished figures. They are recomputed when the session really
     * ends.
     */
    public void reopenSession(long sessionId) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE stream_session SET
                            ended_at = NULL,
                            status = 'LIVE',
                            end_source = NULL,
                            peak_viewers = NULL,
                            avg_viewers = NULL,
                            sample_count = NULL
                        WHERE id = :id
                        """)
                .bind("id", sessionId)
                .execute());
    }

    // -----------------------------------------------------------------------
    // Grouping
    //
    // A merge is a link, never a rewrite. Nothing is combined in storage, so
    // undoing it is one UPDATE and no data was destroyed to make it.
    // -----------------------------------------------------------------------

    /**
     * Groups one session into another.
     *
     * <p>Resolves the target to the head of its group first, so chains never
     * form and every query can group by {@code COALESCE(merged_into_id, id)}
     * without following links.
     *
     * <p>Any sessions already grouped under the moving session come along,
     * otherwise they would be orphaned pointing at a session that is no longer
     * a head.
     */
    public void merge(long sessionId, long intoSessionId) {
        jdbi.useHandle(h -> {
            long head = h.createQuery("SELECT COALESCE(merged_into_id, id) FROM stream_session WHERE id = :id")
                    .bind("id", intoSessionId)
                    .mapTo(Long.class)
                    .one();

            if (head == sessionId) {
                // The target already belongs to this session's group. Merging
                // would point a head at its own child.
                return;
            }

            h.createUpdate("UPDATE stream_session SET merged_into_id = :head WHERE merged_into_id = :id")
                    .bind("head", head)
                    .bind("id", sessionId)
                    .execute();

            h.createUpdate("UPDATE stream_session SET merged_into_id = :head WHERE id = :id")
                    .bind("head", head)
                    .bind("id", sessionId)
                    .execute();
        });
    }

    /**
     * Groups a chosen set of broadcasts together.
     *
     * <p>The earliest becomes the head, which is the only ordering that reads
     * naturally in a history sorted by time.
     *
     * <p>Selecting the set is the person's job, not the collector's. Two
     * broadcasts that dropped and resumed and a third that was deliberately
     * started afterwards look the same from here; only they know which is which,
     * so the interface offers the candidates and merges exactly what was ticked.
     */
    public void mergeAll(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.size() < 2) {
            return;
        }

        List<Long> ordered = jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT id FROM stream_session
                        WHERE id IN (<ids>)
                        ORDER BY started_at
                        """)
                .bindList("ids", sessionIds)
                .mapTo(Long.class)
                .list());

        if (ordered.size() < 2) {
            return;
        }

        long head = ordered.get(0);
        for (int i = 1; i < ordered.size(); i++) {
            merge(ordered.get(i), head);
        }
    }

    /**
     * Whether any of these broadcasts is still on air.
     *
     * <p>Merging one that has not finished would settle a question about figures
     * that do not exist yet — it has no duration, no average and no peak until it
     * ends.
     */
    public boolean anyStillLive(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return false;
        }

        return jdbi.withHandle(h -> h
                .createQuery("SELECT COUNT(*) FROM stream_session WHERE id IN (<ids>) AND ended_at IS NULL")
                .bindList("ids", sessionIds)
                .mapTo(Integer.class)
                .one()) > 0;
    }

    /** The head of the group a session belongs to, or itself when it stands alone. */
    public long groupHead(long sessionId) {
        return jdbi.withHandle(h -> h
                .createQuery("SELECT COALESCE(merged_into_id, id) FROM stream_session WHERE id = :id")
                .bind("id", sessionId)
                .mapTo(Long.class)
                .one());
    }

    /** Detaches a session from its group, leaving it standing alone again. */
    public void unmerge(long sessionId) {
        jdbi.useHandle(h -> h
                .createUpdate("UPDATE stream_session SET merged_into_id = NULL WHERE id = :id")
                .bind("id", sessionId)
                .execute());
    }

    /**
     * Finished broadcasts that started inside a window.
     *
     * <p>Compared as text rather than through SQLite's date functions. ISO-8601
     * sorts correctly in plain lexicographic order, which is exactly why the
     * schema stores timestamps that way — no parsing, no timezone surprises in
     * the query itself.
     *
     * <p>Returns the rows and lets the caller add them up in Java. A month holds
     * a few dozen broadcasts at most, and arithmetic that can be read beats
     * arithmetic hidden in SQL.
     */
    public List<SessionTotals> findSessionsBetween(long accountId, Instant from, Instant to) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT COALESCE(merged_into_id, id) AS head,
                               started_at, ended_at, peak_viewers, avg_viewers, sample_count
                        FROM stream_session
                        WHERE account_id = :accountId
                          AND ended_at IS NOT NULL
                          AND started_at >= :from
                          AND started_at < :to
                        ORDER BY started_at
                        """)
                .bind("accountId", accountId)
                .bind("from", text(from))
                .bind("to", text(to))
                .map((rs, ctx) -> new SessionTotals(
                        rs.getLong("head"),
                        instant(rs, "started_at"),
                        instant(rs, "ended_at"),
                        rs.getObject("peak_viewers") == null ? null : rs.getLong("peak_viewers"),
                        rs.getObject("avg_viewers") == null ? null : rs.getDouble("avg_viewers"),
                        rs.getObject("sample_count") == null ? 0 : rs.getInt("sample_count")))
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
    public List<OpenSession> findStaleOpenSessions(long accountId, Instant cutoff) {
        return jdbi.withHandle(h -> h
                .createQuery("""
                        SELECT s.id, s.external_stream_id, s.started_at
                        FROM stream_session s
                        WHERE s.account_id = :accountId
                          AND s.ended_at IS NULL
                          AND COALESCE(
                                (SELECT MAX(sampled_at) FROM viewer_sample WHERE session_id = s.id),
                                s.started_at) < :cutoff
                        """)
                .bind("accountId", accountId)
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

    /**
     * A session that exists, whether or not it has been closed.
     *
     * @param endedAt null while on air
     */
    public record ExistingSession(long id, String streamId, Instant startedAt, Instant endedAt) {

        public boolean isClosed() {
            return endedAt != null;
        }
    }

    /** Running totals of a broadcast still in progress. */
    public record LiveStats(long peakViewers, int sampleCount) {
    }

    /**
     * One finished broadcast reduced to the figures a monthly total needs.
     *
     * <p>Note it carries the stored summary rather than the raw samples. That is
     * deliberate: samples are deleted at ninety days, so a total computed from
     * them would quietly start shrinking as history ages. Built from the summary,
     * a month from last year adds up the same as it did the day it closed.
     */
    public record SessionTotals(
            long groupId,
            Instant startedAt,
            Instant endedAt,
            Long peakViewers,
            Double avgViewers,
            int sampleCount) {

        public Duration onAir() {
            return Duration.between(startedAt, endedAt);
        }

        /**
         * Viewers multiplied by minutes watched — the closest honest answer to
         * "how much audience did this broadcast get".
         *
         * <p>An estimate, and it says so. Each sample stands for roughly one
         * minute at that viewer count, so the product of the average and the
         * number of samples reconstructs the area under the curve. It is only as
         * complete as the sampling was, which is why coverage travels beside it.
         */
        public double viewerMinutes() {
            return avgViewers == null ? 0 : avgViewers * sampleCount;
        }

        /** How many samples a complete recording of this broadcast would hold. */
        public long expectedSamples() {
            return Math.max(1, onAir().toMinutes());
        }
    }

    /** One row of the group query, before the parts are combined. */
    private record SessionPart(
            long head,
            String title,
            String category,
            Instant startedAt,
            Instant endedAt,
            Long peakViewers,
            Double avgViewers,
            int sampleCount,
            String status,
            String endSource) {
    }

    /**
     * A broadcast as it is shown in the history: one session, or several that
     * someone decided were one.
     *
     * @param onAirSeconds    the sum of time actually broadcasting. A break
     *                        between two merged parts is not counted, because
     *                        nobody was on air during it
     * @param avgViewers      weighted by samples, so a short reconnection does
     *                        not weigh as much as a long stretch
     * @param expectedSamples what a complete recording would hold, for coverage
     * @param parts           how many broadcasts this group contains. More than
     *                        one means somebody merged them
     * @param status          SETTLING while any part may still change, FINAL
     *                        once every one of them is settled
     * @param endSource       VOD when the end time came from the platform's own
     *                        archive, SAMPLES when it came from the last reading
     *                        this application managed to take
     */
    public record SessionGroup(
            long id,
            String title,
            String category,
            Instant startedAt,
            Instant endedAt,
            long onAirSeconds,
            Long peakViewers,
            Double avgViewers,
            int sampleCount,
            long expectedSamples,
            int parts,
            String status,
            String endSource) {
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
