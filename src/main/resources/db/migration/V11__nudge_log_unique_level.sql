-- Escalation cron dedup backstop (TK-156): a given escalation level is delivered to a given nudger
-- for a given target at most once. The cron already checks nudge_log before sending, but ShedLock
-- only guards against concurrent ticks while its lease holds; this UNIQUE makes a double-send
-- impossible even if a lease expires mid-run, mirroring reminder_log's UNIQUE(task_id, level).
ALTER TABLE nudge_log
    ADD CONSTRAINT uq_nudge_log_target_nudger_level
        UNIQUE (target_type, target_id, nudger_id, level);
