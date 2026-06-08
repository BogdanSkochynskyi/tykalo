-- Per-user morning-digest hour (local wall-clock, 0–23). NULL disables the digest.
-- Default 8 enables an 08:00 digest for every existing and new user (TK-144).
ALTER TABLE users ADD COLUMN digest_hour SMALLINT DEFAULT 8;

ALTER TABLE users
    ADD CONSTRAINT users_digest_hour_range
        CHECK (digest_hour IS NULL OR digest_hour BETWEEN 0 AND 23);
