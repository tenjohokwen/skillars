-- Story 7.3: Cancellation, Refund & Reliability Strikes

-- Add cancel_reason to bookings table (nullable; null means no reason set)
ALTER TABLE booking.bookings ADD COLUMN cancel_reason VARCHAR(50);

-- Extend coach_reliability_strikes with booking_id and acknowledged flag
ALTER TABLE marketplace.coach_reliability_strikes
    ADD COLUMN booking_id UUID,
    ADD COLUMN acknowledged BOOLEAN NOT NULL DEFAULT false;

-- Update status constraint to include REDUCED and PENDING_REVIEW
ALTER TABLE marketplace.coach_profiles
    DROP CONSTRAINT chk_coach_profile_status,
    ADD CONSTRAINT chk_coach_profile_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'REDUCED', 'PENDING_REVIEW'));

-- Coach cancellation history (admin visibility — all reasons recorded)
CREATE TABLE payment.coach_cancellation_history (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id      UUID        NOT NULL,
    booking_id    UUID        NOT NULL,
    cancel_reason VARCHAR(50) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reliability thresholds in platform config (after existing entries at 502)
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (503, 'reliability.strike.visibilityThreshold', '3', 'STRING',
        'Rolling 30-day strike count at which coach visibility is reduced. Default 3.', NOW()),
    (504, 'reliability.strike.suspensionThreshold', '5', 'STRING',
        'Rolling 30-day strike count triggering PENDING_REVIEW. Default 5.', NOW())
ON CONFLICT (key) DO NOTHING;
