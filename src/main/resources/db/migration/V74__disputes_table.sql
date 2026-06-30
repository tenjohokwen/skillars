CREATE TABLE admin.disputes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID         NOT NULL,
    raised_by       BIGINT       NOT NULL,
    raised_by_role  VARCHAR(10)  NOT NULL CHECK (raised_by_role IN ('PARENT', 'PLAYER')),
    reason          VARCHAR(30)  NOT NULL CHECK (reason IN ('COACH_NO_SHOW','SESSION_QUALITY','SAFETY_CONCERN','UNAUTHORISED_CHARGE','OTHER')),
    details         VARCHAR(2000) NOT NULL,
    status          VARCHAR(15)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','UNDER_REVIEW','RESOLVED','DISMISSED')),
    -- NOTE: 'UNDER_REVIEW' is reserved for a future story (admin acknowledges/claims a dispute).
    -- No endpoint transitions to it in this story. Do NOT remove it from the constraint.
    resolution      VARCHAR(20)  CHECK (resolution IN ('FULL_CREDIT','PARTIAL_CREDIT','NO_ACTION','COACH_WARNING')),
    resolution_note VARCHAR(1000),
    credit_amount   NUMERIC(10,2),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     BIGINT,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_disputes_booking_id ON admin.disputes(booking_id);
CREATE INDEX idx_disputes_raised_by  ON admin.disputes(raised_by);
CREATE INDEX idx_disputes_status     ON admin.disputes(status, created_at);
-- Prevents concurrent duplicate open disputes for the same booking (check-then-insert race)
CREATE UNIQUE INDEX idx_disputes_unique_open_per_booking ON admin.disputes(booking_id)
    WHERE status NOT IN ('RESOLVED', 'DISMISSED');

-- Platform config: dispute submission window
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (515, 'disputes.submissionWindowDays', '14', 'LONG',
        'Days after booking completion/cancellation within which a dispute can be raised', NOW())
ON CONFLICT DO NOTHING;
