-- New table: review_flags
CREATE TABLE reviews.review_flags (
    flag_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id   UUID         NOT NULL REFERENCES reviews.coach_reviews(review_id),
    flagged_by  BIGINT       NOT NULL,
    reason      VARCHAR(30)  NOT NULL,
    details     VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX review_flags_unique_flagger ON reviews.review_flags(review_id, flagged_by);

-- New table: review_moderation_log (audit trail of admin decisions)
CREATE TABLE reviews.review_moderation_log (
    log_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id  UUID         NOT NULL,
    admin_id   BIGINT       NOT NULL,
    action     VARCHAR(10)  NOT NULL CHECK (action IN ('APPROVED', 'BLOCKED')),
    reason     VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Add held_reason to coach_reviews (nullable — existing UNDER_REVIEW rows pre-date this column)
ALTER TABLE reviews.coach_reviews
    ADD COLUMN held_reason VARCHAR(20);

-- Seed auto-hold threshold config
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (514, 'reviews.autoHoldFlagThreshold', '3', 'LONG',
        'Number of open flags required to auto-hold a review for admin moderation', NOW())
ON CONFLICT (key) DO NOTHING;
