-- Story 3.10: Session pack expiry and pause management

-- Step 1: Add columns (nullable first — required for non-empty tables)
ALTER TABLE booking.session_packs_purchased
    ADD COLUMN expires_at          TIMESTAMPTZ,
    ADD COLUMN paused_until        TIMESTAMPTZ,
    ADD COLUMN warning_30d_sent_at TIMESTAMPTZ,
    ADD COLUMN warning_7d_sent_at  TIMESTAMPTZ;

-- Step 2: Backfill expires_at based on session count tier thresholds
UPDATE booking.session_packs_purchased
SET expires_at = CASE
    WHEN session_count = 1              THEN purchased_at + INTERVAL '90 days'
    WHEN session_count BETWEEN 2 AND 5  THEN purchased_at + INTERVAL '180 days'
    WHEN session_count BETWEEN 6 AND 10 THEN purchased_at + INTERVAL '365 days'
    ELSE                                     purchased_at + INTERVAL '548 days'
END;

-- Step 3: Enforce NOT NULL after backfill (ADD COLUMN NOT NULL without default fails on non-empty tables)
ALTER TABLE booking.session_packs_purchased
    ALTER COLUMN expires_at SET NOT NULL;

-- Step 4: Add CANCELLED to bookings status constraint (pack-pause-triggered cancellation)
ALTER TABLE booking.bookings DROP CONSTRAINT chk_bkg_status;

ALTER TABLE booking.bookings ADD CONSTRAINT chk_bkg_status CHECK (status IN (
    'REQUESTED', 'ACCEPTED', 'PAYMENT_PENDING', 'CONFIRMED', 'UPCOMING',
    'IN_PROGRESS', 'PAUSED', 'COMPLETED_PENDING_CONFIRMATION', 'COMPLETED',
    'DECLINED', 'CANCELLED', 'CANCELLED_PARENT', 'CANCELLED_COACH',
    'NO_SHOW_PLAYER', 'NO_SHOW_COACH', 'DISPUTED', 'REFUND_PENDING', 'REFUNDED'
));

-- Step 5: Insert ConfigService entries for tier thresholds and scheduler config
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
  (51, 'pack.expiry.days.tier1', '90',  'LONG', '1-session pack validity in days',    NOW()),
  (52, 'pack.expiry.days.tier2', '180', 'LONG', '2-5 session pack validity in days',  NOW()),
  (53, 'pack.expiry.days.tier3', '365', 'LONG', '6-10 session pack validity in days', NOW()),
  (54, 'pack.expiry.days.tier4', '548', 'LONG', '11+ session pack validity in days',  NOW()),
  (55, 'pack.pause.maxDays',     '90',  'LONG', 'Maximum days a pack can be paused',  NOW())
ON CONFLICT DO NOTHING;
