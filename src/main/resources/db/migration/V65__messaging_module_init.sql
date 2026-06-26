CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE messaging.conversations (
    id           BIGINT PRIMARY KEY,
    coach_id     UUID NOT NULL,
    player_id    BIGINT NOT NULL,
    parent_id    BIGINT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_message_at      TIMESTAMPTZ,
    coach_last_read_at   TIMESTAMPTZ,
    parent_last_read_at  TIMESTAMPTZ,
    player_last_read_at  TIMESTAMPTZ,
    CONSTRAINT uq_conversations_coach_player UNIQUE (coach_id, player_id)
);

CREATE TABLE messaging.messages (
    id                  BIGINT PRIMARY KEY,
    conversation_id     BIGINT NOT NULL REFERENCES messaging.conversations(id),
    sender_id           BIGINT NOT NULL,
    sender_role         VARCHAR(15) NOT NULL,
    content             TEXT NOT NULL,
    moderation_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_messages_conversation_id ON messaging.messages(conversation_id);
CREATE INDEX idx_messages_created_at ON messaging.messages(conversation_id, created_at DESC);
