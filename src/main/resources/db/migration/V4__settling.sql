-- A broadcast's figures are not final the moment it disappears from the listing.
--
-- Twitch keeps reporting a stream as live for some minutes after it actually
-- ends, so the last samples of a session can be ghosts: viewer counts for a
-- broadcast that was already over. They stretch the duration and drag the
-- average down, and nothing on screen says so.
--
-- Rather than guess which trailing samples are real, the session says it has not
-- settled yet. This is the freshness rule applied to history — the uncertainty
-- is labelled instead of hidden (DECISIONS.md § 18).

-- ---------------------------------------------------------------------------
-- Where a session is in its life
--
--   LIVE      on air now
--   SETTLING  off air, but the numbers may still move. Shown as provisional
--   FINAL     settled; these figures will not change again
--
-- Existing rows default to FINAL. They were closed under the old rules and
-- nothing is going to revisit them, so calling them provisional would be a
-- promise nobody intends to keep.
-- ---------------------------------------------------------------------------
ALTER TABLE stream_session ADD COLUMN status TEXT NOT NULL DEFAULT 'FINAL';

-- ---------------------------------------------------------------------------
-- How the end time was decided
--
--   SAMPLES  the last sample taken. Our own observation, off by however long
--            Twitch went on reporting the stream as live
--   VOD      the archive's own duration. Twitch's record of its own broadcast,
--            which is the only authority available on when it really ended
--
-- Recorded because a figure that cannot be traced to how it was obtained is a
-- figure nobody can check. Two sessions with the same duration mean different
-- things depending on which of these produced it.
-- ---------------------------------------------------------------------------
ALTER TABLE stream_session ADD COLUMN end_source TEXT;

UPDATE stream_session SET end_source = 'SAMPLES' WHERE ended_at IS NOT NULL;

-- Sessions currently on air are live, whatever the default said.
UPDATE stream_session SET status = 'LIVE' WHERE ended_at IS NULL;

CREATE INDEX idx_session_settling ON stream_session (account_id, status);
