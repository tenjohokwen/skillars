CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE admin.admin_alerts (
    alert_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type           VARCHAR(25) NOT NULL CHECK (type IN ('MESSAGE_REPORT','CONVERSATION_REPORT','REVIEW_FLAG','STRIKE_THRESHOLD','DISPUTE_RAISED')),
    reference_id   VARCHAR(36) NOT NULL,
    reference_type VARCHAR(15) NOT NULL CHECK (reference_type IN ('MESSAGE','CONVERSATION','REVIEW','COACH','BOOKING')),
    status         VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,
    resolved_by    BIGINT
);
CREATE INDEX admin_alerts_status_created ON admin.admin_alerts(status, created_at);
CREATE UNIQUE INDEX admin_alerts_unique_open_per_ref
    ON admin.admin_alerts(reference_id, type)
    WHERE status = 'OPEN';

CREATE TABLE admin.admin_action_log (
    log_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id     BIGINT      NOT NULL,
    action_type  VARCHAR(25) NOT NULL CHECK (action_type IN ('MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK','REVIEW_APPROVE','REVIEW_BLOCK','COACH_SUSPEND','COACH_REINSTATE','DISPUTE_RESOLVE')),
    reference_id VARCHAR(36) NOT NULL,
    reason       VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
