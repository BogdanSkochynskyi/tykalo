-- Outgoing messages the rate-limit worker gave up on (TK-173). A row is written when a send exhausts
-- its 429 retry budget, or fails with a permanent Telegram error, so the failure can be reviewed
-- rather than silently lost. attempts is how many delivery tries had already failed; reason captures
-- why it was dropped.
CREATE TABLE dropped_messages (
    id         UUID PRIMARY KEY,
    chat_id    BIGINT NOT NULL,
    text       TEXT   NOT NULL,
    attempts   INT    NOT NULL,
    reason     TEXT,
    dropped_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_dropped_messages_dropped_at ON dropped_messages (dropped_at);
