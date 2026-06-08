-- TK-154: /nudgers remove hard-deletes a pairing. nudge_log rows reference it (the V8 FK), so a
-- delete would otherwise violate that FK. Cascade the delete onto the audit ledger rather than
-- blocking the removal. Postgres auto-named the V8 FK <table>_<column>_fkey.
ALTER TABLE nudge_log DROP CONSTRAINT nudge_log_nudger_id_fkey;
ALTER TABLE nudge_log ADD CONSTRAINT nudge_log_nudger_id_fkey
    FOREIGN KEY (nudger_id) REFERENCES nudgers (id) ON DELETE CASCADE;
