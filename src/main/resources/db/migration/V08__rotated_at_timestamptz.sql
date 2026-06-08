-- Change rotated_at from TIMESTAMP (no tz) to TIMESTAMPTZ so that Hibernate Instant
-- comparisons (which use jdbc.time_zone=UTC) match the stored values correctly.
ALTER TABLE main.tenant_api_key
    ALTER COLUMN rotated_at TYPE TIMESTAMPTZ USING rotated_at AT TIME ZONE 'UTC';

ALTER TABLE main.tenant_api_key_aud
    ALTER COLUMN rotated_at TYPE TIMESTAMPTZ USING rotated_at AT TIME ZONE 'UTC';
