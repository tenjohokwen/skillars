-- Add verification tier to coach profiles (admin-grantable, defaults to BASIC)
ALTER TABLE marketplace.coach_profiles
  ADD COLUMN verification_tier VARCHAR(20) NOT NULL DEFAULT 'BASIC',
  ADD CONSTRAINT chk_verification_tier
      CHECK (verification_tier IN ('BASIC','TRUSTED','FEATURED'));

-- Reliability strikes table (stub schema; Epic 7 populates data)
CREATE TABLE marketplace.coach_reliability_strikes (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id   UUID        NOT NULL REFERENCES marketplace.coach_profiles(id),
    reason     VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reliability_strikes_coach_date
    ON marketplace.coach_reliability_strikes(coach_id, created_at);

-- Search performance indexes
CREATE INDEX idx_coach_profiles_city      ON marketplace.coach_profiles(city);
CREATE INDEX idx_coach_profiles_district  ON marketplace.coach_profiles(district);
CREATE INDEX idx_coach_profiles_vtier     ON marketplace.coach_profiles(verification_tier);
CREATE INDEX idx_coach_pricing_price      ON marketplace.coach_pricing(per_session_price);
CREATE INDEX idx_coach_specialties_skill  ON marketplace.coach_specialties(skill);
CREATE INDEX idx_coach_age_groups_tier    ON marketplace.coach_age_groups(age_tier);
