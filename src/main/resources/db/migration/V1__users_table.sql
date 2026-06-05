CREATE TABLE users (
    id                UUID PRIMARY KEY,
    tg_chat_id        BIGINT      NOT NULL UNIQUE,
    tg_username       VARCHAR(32),
    timezone          VARCHAR(64),
    quiet_hours_start TIME        DEFAULT '22:00',
    quiet_hours_end   TIME        DEFAULT '07:00',
    locale            VARCHAR(10),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- The UNIQUE constraint on tg_chat_id already creates a unique index, which serves
-- as the lookup index for findByTgChatId (no separate index needed).
