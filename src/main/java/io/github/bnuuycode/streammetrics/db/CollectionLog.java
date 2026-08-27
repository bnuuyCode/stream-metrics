package io.github.bnuuycode.streammetrics.db;

import io.github.bnuuycode.streammetrics.metrics.CollectionException.ErrorKind;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * The record of every attempt to read a platform, successful or not.
 *
 * <p>Exists so that a hole in the data can be explained rather than merely
 * noticed. Staring at a gap months later, the question is always the same: did
 * it fail, or was the machine simply off? A row here means it tried. No row
 * means nothing was running.
 *
 * <p>Both the daily snapshot and the live sampler write here. The sampler did
 * not, originally — and the very first time a collection looked broken, that
 * omission made it impossible to tell "has not run yet" from "is failing". The
 * gap this table exists to close had been left open in the code that needed it
 * most.
 */
public final class CollectionLog {

    /** One row per daily snapshot attempt, per account. */
    public static final String DAILY_SNAPSHOT = "daily_snapshot";

    /** One row per live poll, per account. */
    public static final String LIVE_SAMPLE = "live_sample";

    /** One row per token refresh. */
    public static final String TOKEN_REFRESH = "token_refresh";

    private final Jdbi jdbi;

    public CollectionLog(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Opens a row and returns its id.
     *
     * <p>Written before the attempt rather than after, so a crash midway still
     * leaves evidence that something was tried. A log that only records
     * completed work cannot explain a gap.
     */
    public long start(long accountId, String kind) {
        return jdbi.withHandle(h -> {
            h.createUpdate("""
                            INSERT INTO collection_run (account_id, kind, started_at, status)
                            VALUES (:accountId, :kind, :startedAt, 'RUNNING')
                            """)
                    .bind("accountId", accountId)
                    .bind("kind", kind)
                    .bind("startedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    .execute();

            // Same handle means the same connection, which is what makes
            // last_insert_rowid() safe here.
            return h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        });
    }

    public void succeeded(long runId) {
        finish(runId, "OK", null, null);
    }

    /** Nothing to do — for instance, a platform nobody has connected. */
    public void skipped(long runId, String why) {
        finish(runId, "SKIPPED", null, why);
    }

    public void failed(long runId, ErrorKind kind, String detail) {
        finish(runId, "ERROR", kind, detail);
    }

    private void finish(long runId, String status, ErrorKind errorKind, String detail) {
        jdbi.useHandle(h -> h
                .createUpdate("""
                        UPDATE collection_run
                        SET finished_at = :finishedAt,
                            status = :status,
                            error_kind = :errorKind,
                            error_detail = :detail
                        WHERE id = :id
                        """)
                .bind("id", runId)
                .bind("finishedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .bind("status", status)
                .bind("errorKind", errorKind == null ? null : errorKind.name())
                // Truncated because some platforms answer failures with an
                // entire HTML page, and the log is meant to stay readable.
                .bind("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)))
                .execute());
    }
}
