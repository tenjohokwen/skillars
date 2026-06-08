CREATE TABLE IF NOT EXISTS main.alert_rule (
    id                  BIGINT          NOT NULL PRIMARY KEY,
    created_by          VARCHAR(50),
    created_date        TIMESTAMP,
    last_modified_by    VARCHAR(50),
    last_modified_date  TIMESTAMP,
    request_id          VARCHAR(255),
    session_id          TEXT,
    status              VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    metric_name         VARCHAR(100)    NOT NULL,
    threshold           NUMERIC(10,4)   NOT NULL,
    window_seconds      INTEGER         NOT NULL DEFAULT 300,
    notification_channel VARCHAR(50)    NOT NULL DEFAULT 'LOG',
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

-- Initial alert rules (optional)
-- Domain-specific rules should be added here.

COMMENT ON TABLE main.alert_rule IS 'DB-configurable alert rules evaluated against Micrometer counters on a schedule';
COMMENT ON COLUMN main.alert_rule.metric_name IS 'Metric identifier to evaluate';
COMMENT ON COLUMN main.alert_rule.threshold IS 'Breach threshold as a ratio (0.0–1.0)';
COMMENT ON COLUMN main.alert_rule.window_seconds IS 'Evaluation window in seconds (informational; actual window is set by scheduling interval)';
COMMENT ON COLUMN main.alert_rule.notification_channel IS 'Notification delivery: LOG or EMAIL';
