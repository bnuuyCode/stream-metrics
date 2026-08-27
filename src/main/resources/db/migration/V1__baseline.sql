-- Baseline schema for the metrics aggregator.
-- The reasoning behind every decision here lives in docs/DECISIONS.md, § 6.
--
-- Type convention: SQLite has no native DATE or TIMESTAMP. Dates and instants
-- are stored as ISO-8601 TEXT — it sorts correctly in plain alphabetical order,
-- is readable by eye, and works with date() and datetime().

-- ---------------------------------------------------------------------------
-- 1. Accounts
--
-- The token belongs to the account, not the platform: once my partner uses the
-- app there will be two Twitch accounts with distinct tokens.
-- ---------------------------------------------------------------------------
CREATE TABLE account (
    id           INTEGER PRIMARY KEY,
    platform     TEXT    NOT NULL,   -- 'twitch','youtube','instagram','bluesky','discord'
    external_id  TEXT    NOT NULL,   -- broadcaster_id, channel_id, ig_user_id...
    handle       TEXT    NOT NULL,   -- displayed on the card
    display_name TEXT,
    enabled      INTEGER NOT NULL DEFAULT 1,  -- switch off without deleting history
    created_at   TEXT    NOT NULL,
    UNIQUE (platform, external_id)
);

-- ---------------------------------------------------------------------------
-- 2. OAuth tokens
--
-- Two expiry columns, because they are two different problems:
--   access_expires_at -> routine. Twitch, ~4h. Renews itself, nobody notices.
--   hard_expires_at   -> final deadline. Instagram, 60 days. Past that, no code
--                        can save it: a manual re-login is required. Warn on the
--                        dashboard when fewer than 15 days remain.
--
-- WARNING: tokens are stored in plain text. The .db file must NEVER enter Git.
-- ---------------------------------------------------------------------------
CREATE TABLE oauth_token (
    account_id        INTEGER PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE,
    access_token      TEXT NOT NULL,
    refresh_token     TEXT,          -- NULL on Instagram: no classic refresh token
    scopes            TEXT NOT NULL, -- lets the app say "log in again" instead of a cryptic 403
    access_expires_at TEXT,
    hard_expires_at   TEXT,
    last_refreshed_at TEXT,
    updated_at        TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 3. Daily snapshots
--
-- The project's asset: the history platforms do not give away and that cannot
-- be recovered later. Retention: forever.
--
-- value is NOT NULL and there is deliberately no status column. A gap is
-- represented by the ABSENCE of the row. The chart walks the date range and
-- whatever is missing becomes a visible hole. Never interpolate, never repeat
-- the last known value.
--
-- The UNIQUE constraint makes the job idempotent:
--   INSERT INTO metric_snapshot (...) VALUES (...)
--   ON CONFLICT (account_id, metric, snapshot_date)
--   DO UPDATE SET value = excluded.value, captured_at = excluded.captured_at;
-- ---------------------------------------------------------------------------
CREATE TABLE metric_snapshot (
    id            INTEGER PRIMARY KEY,
    account_id    INTEGER NOT NULL REFERENCES account(id),
    metric        TEXT    NOT NULL,  -- 'followers','subscribers','total_views','content_count'
    snapshot_date TEXT    NOT NULL,  -- 'YYYY-MM-DD' in America/Sao_Paulo
    value         INTEGER NOT NULL,
    captured_at   TEXT    NOT NULL,  -- ISO-8601 UTC, the real moment of collection
    UNIQUE (account_id, metric, snapshot_date)
);

CREATE INDEX idx_snapshot_series ON metric_snapshot (account_id, metric, snapshot_date);

-- ---------------------------------------------------------------------------
-- 4. Collection log
--
-- Answers the question you ask while staring at a gap in the chart:
--   gap + a run with status='ERROR' -> it tried and failed, with the reason
--   gap + no run at all             -> machine was off, nothing is broken
--
-- error_kind is categorized because the handling differs: RATE_LIMIT and
-- NETWORK are transient (retry); AUTH is an alarm on screen, the token is dead.
--
-- Retention: 90 days. This is the noisiest table.
-- ---------------------------------------------------------------------------
CREATE TABLE collection_run (
    id           INTEGER PRIMARY KEY,
    account_id   INTEGER NOT NULL REFERENCES account(id),
    kind         TEXT NOT NULL,  -- 'daily_snapshot' | 'live_sample' | 'token_refresh'
    started_at   TEXT NOT NULL,
    finished_at  TEXT,
    status       TEXT NOT NULL,  -- 'OK' | 'ERROR' | 'SKIPPED'
    error_kind   TEXT,           -- 'AUTH' | 'RATE_LIMIT' | 'NETWORK' | 'PARSE' | 'UNKNOWN'
    error_detail TEXT
);

CREATE INDEX idx_run_account_time ON collection_run (account_id, started_at);

-- ---------------------------------------------------------------------------
-- 5. Live sessions
--
-- peak_viewers and avg_viewers are derived values — the single deliberate
-- exception to the "never store what you computed" rule (DECISIONS.md § 6.1).
-- The samples they come from are deleted after 90 days by retention policy;
-- when the ingredient is destroyed on purpose, the summary becomes primary data.
--
-- samples_pruned distinguishes "summarized on purpose" from "never monitored".
-- Without that marker the UI lies by omission.
--
-- ended_at NULL = live right now. If the app dies mid-stream the session is
-- orphaned: on startup, close open sessions whose last sample is old, using
-- that sample's timestamp as ended_at.
-- ---------------------------------------------------------------------------
CREATE TABLE stream_session (
    id                 INTEGER PRIMARY KEY,
    account_id         INTEGER NOT NULL REFERENCES account(id),
    external_stream_id TEXT,
    started_at         TEXT NOT NULL,
    ended_at           TEXT,
    title              TEXT,
    category           TEXT,
    peak_viewers       INTEGER,
    avg_viewers        REAL,
    sample_count       INTEGER,
    samples_pruned     INTEGER NOT NULL DEFAULT 0,
    UNIQUE (account_id, external_stream_id)
);

CREATE INDEX idx_session_account_start ON stream_session (account_id, started_at);

-- ---------------------------------------------------------------------------
-- 6. Viewer samples
--
-- The only table that really grows, and the only one that gets pruned (90 days).
--
-- WITHOUT ROWID: in a table with a composite primary key that nothing else
-- references, SQLite stores the rows directly in the PK tree instead of keeping
-- an index pointing at a rowid. Saves space and one indirection on every read.
--
-- ON DELETE CASCADE only works with PRAGMA foreign_keys = ON. In SQLite,
-- foreign keys are OFF by default.
-- ---------------------------------------------------------------------------
CREATE TABLE viewer_sample (
    session_id INTEGER NOT NULL REFERENCES stream_session(id) ON DELETE CASCADE,
    sampled_at TEXT    NOT NULL,
    viewers    INTEGER NOT NULL,
    PRIMARY KEY (session_id, sampled_at)
) WITHOUT ROWID;
