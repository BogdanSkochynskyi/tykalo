CREATE TABLE list_messages (
    id               UUID PRIMARY KEY,
    list_id          UUID   NOT NULL REFERENCES lists (id),
    tg_chat_id       BIGINT NOT NULL,
    tg_message_id    BIGINT NOT NULL,
    last_rendered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_list_messages_list_id_chat_id ON list_messages (list_id, tg_chat_id);
