-- Nudgers feature foundation (TK-151): the trusted contacts who apply graduated social pressure on
-- overdue tasks, the per-target escalation ladder, and the ledger of escalations actually sent.

-- A trusted contact (nudger_user_id) invited by an owner (owner_id) to nudge them. status walks
-- PENDING → ACTIVE (on consent, TK-153) and ACTIVE ⇄ PAUSED (TK-154). karma_score rewards the
-- nudger when they acknowledge a reminder (TK-157).
CREATE TABLE nudgers (
    id             UUID PRIMARY KEY,
    owner_id       UUID       NOT NULL REFERENCES users (id),
    nudger_user_id UUID       NOT NULL REFERENCES users (id),
    status         VARCHAR(8) NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'ACTIVE', 'PAUSED')),
    karma_score    INTEGER    NOT NULL DEFAULT 0,
    added_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_nudgers_owner_id ON nudgers (owner_id);
CREATE INDEX idx_nudgers_nudger_user_id ON nudgers (nudger_user_id);

-- The escalation ladder for a single target (a TASK or a whole LIST). One row per level; each level
-- reveals progressively more of the task (NUMBER → TITLE → DESCRIPTION) after delay_minutes past due.
-- target_id is polymorphic, so it carries no FK.
CREATE TABLE escalation_policies (
    id            UUID        PRIMARY KEY,
    target_type   VARCHAR(4)  NOT NULL CHECK (target_type IN ('TASK', 'LIST')),
    target_id     UUID        NOT NULL,
    level         INTEGER     NOT NULL,
    delay_minutes INTEGER     NOT NULL,
    reveal_fields VARCHAR(11) NOT NULL
                      CHECK (reveal_fields IN ('NUMBER', 'TITLE', 'DESCRIPTION'))
);

CREATE INDEX idx_escalation_policies_target ON escalation_policies (target_type, target_id);

-- Ledger of escalations the cron actually delivered (TK-156): one row per (target, nudger, level)
-- sent. acknowledged_at is stamped when the nudger taps "I reminded" (TK-157). message_template
-- keeps the rendered text for audit. target_id is polymorphic, so it carries no FK.
CREATE TABLE nudge_log (
    id               UUID       PRIMARY KEY,
    target_type      VARCHAR(4) NOT NULL CHECK (target_type IN ('TASK', 'LIST')),
    target_id        UUID       NOT NULL,
    nudger_id        UUID       NOT NULL REFERENCES nudgers (id),
    level            INTEGER    NOT NULL,
    sent_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    acknowledged_at  TIMESTAMP WITH TIME ZONE,
    message_template TEXT
);

CREATE INDEX idx_nudge_log_target ON nudge_log (target_type, target_id);
CREATE INDEX idx_nudge_log_nudger_id ON nudge_log (nudger_id);
