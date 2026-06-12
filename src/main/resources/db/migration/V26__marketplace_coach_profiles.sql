CREATE SCHEMA IF NOT EXISTS marketplace;

CREATE TABLE marketplace.coach_profiles (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT      NOT NULL UNIQUE REFERENCES main."user"(id),
    display_name     VARCHAR(120) NOT NULL,
    bio              TEXT,
    city             VARCHAR(100),
    district         VARCHAR(100),
    languages        VARCHAR[]   NOT NULL DEFAULT '{}',
    canonical_timezone VARCHAR(64) NOT NULL,
    photo_url        VARCHAR(512),
    status           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_coach_profile_status CHECK (status IN ('DRAFT','ACTIVE'))
);

CREATE TABLE marketplace.coach_specialties (
    id       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
    skill    VARCHAR(100) NOT NULL,
    CONSTRAINT uq_coach_specialty UNIQUE (coach_id, skill)
);

CREATE TABLE marketplace.coach_age_groups (
    id       UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
    age_tier VARCHAR(20) NOT NULL,
    CONSTRAINT uq_coach_age_group UNIQUE (coach_id, age_tier),
    CONSTRAINT chk_age_tier CHECK (age_tier IN ('U10','AGE_10_12','AGE_13_17','ADULT'))
);

CREATE TABLE marketplace.coach_pricing (
    coach_id          UUID        PRIMARY KEY REFERENCES marketplace.coach_profiles(id),
    per_session_price NUMERIC(10,2) NOT NULL,
    currency          VARCHAR(3)  NOT NULL DEFAULT 'EUR',
    CONSTRAINT chk_coach_pricing_currency CHECK (currency = 'EUR')
);

CREATE TABLE marketplace.session_packs (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id      UUID        NOT NULL REFERENCES marketplace.coach_profiles(id),
    session_count INT         NOT NULL CHECK (session_count > 0),
    total_price   NUMERIC(10,2) NOT NULL,
    label         VARCHAR(100),
    CONSTRAINT uq_session_pack UNIQUE (coach_id, session_count)
);

CREATE TABLE marketplace.coach_availability_windows (
    id                 UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id           UUID     NOT NULL REFERENCES marketplace.coach_profiles(id),
    day_of_week        SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time         TIME     NOT NULL,
    end_time           TIME     NOT NULL,
    canonical_timezone VARCHAR(64) NOT NULL,
    CONSTRAINT chk_availability_time_order CHECK (end_time > start_time)
);

CREATE TABLE marketplace.coach_subscriptions (
    coach_id     UUID        PRIMARY KEY REFERENCES marketplace.coach_profiles(id),
    tier         VARCHAR(20) NOT NULL DEFAULT 'SCOUT',
    active_since TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_coach_subscription_tier CHECK (tier IN ('SCOUT','INSTRUCTOR','ACADEMY'))
);

CREATE INDEX idx_coach_profiles_user_id  ON marketplace.coach_profiles(user_id);
CREATE INDEX idx_coach_profiles_status   ON marketplace.coach_profiles(status);
CREATE INDEX idx_coach_specialties_coach ON marketplace.coach_specialties(coach_id);
CREATE INDEX idx_coach_age_groups_coach  ON marketplace.coach_age_groups(coach_id);
CREATE INDEX idx_availability_coach      ON marketplace.coach_availability_windows(coach_id);
