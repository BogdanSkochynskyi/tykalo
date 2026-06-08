-- Per-user anti-fatigue cap: the most escalation reminders any one of the user's Nudgers
-- may receive in a single day (TK-159). Default 3 backfills every existing and new user.
ALTER TABLE users ADD COLUMN nudger_daily_limit SMALLINT NOT NULL DEFAULT 3;

ALTER TABLE users
    ADD CONSTRAINT users_nudger_daily_limit_min
        CHECK (nudger_daily_limit >= 1);
