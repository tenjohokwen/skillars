CREATE TABLE messaging.message_reports (
    id           BIGINT PRIMARY KEY,
    message_id   BIGINT NOT NULL REFERENCES messaging.messages(id) ON DELETE CASCADE,
    reported_by  BIGINT NOT NULL,
    reason       VARCHAR(30) NOT NULL,
    details      VARCHAR(500),
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,
    resolved_by  BIGINT,
    CONSTRAINT uq_message_reports_message_reporter UNIQUE (message_id, reported_by)
);

CREATE TABLE messaging.conversation_reports (
    id               BIGINT PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES messaging.conversations(id) ON DELETE CASCADE,
    reported_by      BIGINT NOT NULL,
    reason           VARCHAR(30) NOT NULL,
    details          VARCHAR(500),
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      BIGINT,
    CONSTRAINT uq_conversation_reports_conv_reporter UNIQUE (conversation_id, reported_by)
);

CREATE INDEX idx_message_reports_message_id ON messaging.message_reports(message_id);
CREATE INDEX idx_conversation_reports_conversation_id ON messaging.conversation_reports(conversation_id);
