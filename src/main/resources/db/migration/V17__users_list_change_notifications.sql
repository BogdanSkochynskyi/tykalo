ALTER TABLE users
    ADD COLUMN list_change_notifications VARCHAR NOT NULL DEFAULT 'BATCHED';
