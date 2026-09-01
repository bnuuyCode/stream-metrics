-- Replaces automatic merging with a decision the person makes.
--
-- V2 modelled a session as a broadcast that could contain several Twitch stream
-- ids, and the sampler joined them on its own whenever a new id appeared within
-- ten minutes. That guesses intent from timing, and timing does not carry
-- intent: a connection dropping and a streamer deliberately ending one
-- broadcast to start another are identical through the API. Only the person
-- knows which happened. See DECISIONS.md § 17.
--
-- So the model inverts. A session is now exactly one Twitch stream id — the
-- smallest thing the platform actually tells us about. Grouping happens above
-- it, through merged_into_id, and only when someone says so.
--
-- Grouping by a nullable link rather than by rewriting rows keeps the merge
-- REVERSIBLE. A merge that cannot be undone is just another irreversible guess,
-- made by a human instead of a scheduler.

-- ---------------------------------------------------------------------------
-- Grouping
--
-- NULL means "this session stands alone, or is the head of a group". Any other
-- value points at the head. Chains are never created: merging into a session
-- that is itself merged resolves to the head first, so this is always one level
-- deep and every query can group by COALESCE(merged_into_id, id).
-- ---------------------------------------------------------------------------
ALTER TABLE stream_session ADD COLUMN merged_into_id INTEGER REFERENCES stream_session(id);

CREATE INDEX idx_session_merged ON stream_session (merged_into_id);

-- ---------------------------------------------------------------------------
-- Pending decisions
--
-- When a new stream id appears shortly after another session went quiet, the
-- sampler records that the two might belong together and stops there. The
-- suggestion waits in the database until it is answered.
--
-- It lives in a table rather than in memory precisely because the answer may
-- take days. Someone mid-broadcast cannot stop to decide whether two sessions
-- are one, and a prompt that appears and disappears forces the decision at the
-- worst possible moment. Persistence is what makes "decide when you can"
-- possible, so it is part of the design rather than an implementation detail.
--
-- status:  PENDING   nobody has answered yet
--          MERGED    answered yes; merged_into_id was set
--          DISMISSED answered no; these are separate broadcasts
--
-- Answered suggestions are kept. "I already said these are separate" is worth
-- remembering, otherwise the same question returns forever.
-- ---------------------------------------------------------------------------
CREATE TABLE merge_suggestion (
    id              INTEGER PRIMARY KEY,
    account_id      INTEGER NOT NULL REFERENCES account(id),
    session_id      INTEGER NOT NULL REFERENCES stream_session(id) ON DELETE CASCADE,
    into_session_id INTEGER NOT NULL REFERENCES stream_session(id) ON DELETE CASCADE,
    gap_seconds     INTEGER NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'PENDING',
    created_at      TEXT    NOT NULL,
    decided_at      TEXT,
    UNIQUE (session_id, into_session_id)
);

CREATE INDEX idx_suggestion_pending ON merge_suggestion (account_id, status);

-- ---------------------------------------------------------------------------
-- Retiring segments
--
-- A session is now one stream id, so stream_segment would hold exactly one row
-- per session and carry no information the session does not already have.
--
-- Sessions that were automatically merged under V2 stay as they are, as single
-- sessions. They cannot be split back apart: the samples were recorded against
-- the session, never against the segment, so which reading belonged to which
-- id was never written down. Undoing those merges would mean inventing that
-- attribution, and this project does not invent data it did not record.
--
-- In this database there are none, so nothing is lost. The note stands for any
-- copy where there were.
-- ---------------------------------------------------------------------------
DROP TABLE stream_segment;
