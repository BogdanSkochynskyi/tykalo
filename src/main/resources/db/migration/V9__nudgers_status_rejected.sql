-- TK-153: an invitee can now decline a nudger invite, so status may be REJECTED. Widen the CHECK
-- constraint V8 created on nudgers.status (Postgres auto-names a single-column check
-- <table>_<column>_check). 'REJECTED' is 8 chars, so the existing VARCHAR(8) still fits.
ALTER TABLE nudgers DROP CONSTRAINT nudgers_status_check;
ALTER TABLE nudgers ADD CONSTRAINT nudgers_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'PAUSED', 'REJECTED'));
