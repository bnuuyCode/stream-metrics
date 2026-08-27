-- Splits "the broadcast Twitch numbered" from "the broadcast the streamer did".
--
-- Twitch issues a NEW stream id whenever a connection drops and comes back.
-- Modelling one stream_session per Twitch stream id therefore shatters a single
-- evening into several rows, and every average and duration computed from them
-- is wrong.
--
-- So stream_session now means the session as the streamer experienced it, and
-- each Twitch stream id it was made of becomes a segment.
--
-- viewer_sample deliberately stays attached to the SESSION, not the segment:
-- the chart should be one continuous line for the evening, with a visible hole
-- where the connection was down. Absence of a sample is absence of a sample —
-- the same rule the daily snapshots follow.

CREATE TABLE stream_segment (
    id                 INTEGER PRIMARY KEY,
    session_id         INTEGER NOT NULL REFERENCES stream_session(id) ON DELETE CASCADE,
    external_stream_id TEXT    NOT NULL,
    started_at         TEXT    NOT NULL,
    ended_at           TEXT,
    UNIQUE (session_id, external_stream_id)
);

-- Answers "do I already know this Twitch stream id?" on every poll.
CREATE INDEX idx_segment_stream ON stream_segment (external_stream_id);

-- Existing sessions each had exactly one Twitch stream id, so each becomes a
-- single segment. Written as a SELECT rather than as literal rows so it works
-- whether the table holds nothing or a year of history.
INSERT INTO stream_segment (session_id, external_stream_id, started_at, ended_at)
SELECT id, external_stream_id, started_at, ended_at
FROM stream_session
WHERE external_stream_id IS NOT NULL;

-- stream_session.external_stream_id is kept, and from here on means "the first
-- segment of this session". It stays unique per account, so the existing
-- constraint remains correct. Dropping a column in SQLite means rebuilding the
-- table, which is not worth doing for a field that is still useful.
