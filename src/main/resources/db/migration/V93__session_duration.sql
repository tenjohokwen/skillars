-- Story UAT.2 (AC1): session length becomes a first-class concept.
--
-- Per-coach override. NULL is meaningful: it means "inherit the platform default".
-- Deliberately NOT `NOT NULL DEFAULT 60` — with a default, every existing row would be
-- stamped at migration time, the platform key below would become decorative, and an admin
-- later changing booking.session.defaultDurationMinutes would change nothing for any coach
-- that already exists. NULL keeps the platform value authoritative for every coach who has
-- not made a deliberate choice.
ALTER TABLE marketplace.coach_pricing
    ADD COLUMN session_duration_minutes INT NULL,
    ADD CONSTRAINT chk_coach_pricing_session_duration
        CHECK (session_duration_minutes IS NULL
               OR (session_duration_minutes BETWEEN 15 AND 240));

-- Platform-wide default. 603 is the next free id: 601 is V90's
-- booking.payment_pending_sweep_grace_minutes and 602 is V91's
-- platform.messaging.moderation_orphan_grace_minutes.
--
-- The id MUST be free. main.platform_config.id is PRIMARY KEY with no sequence (V20:8) — ids are
-- hand-assigned — and the ON CONFLICT target below is `key`, a DIFFERENT unique constraint
-- (uq_platform_config_key, V20:9). An id collision therefore raises a PK violation the
-- ON CONFLICT (key) clause never sees, failing Flyway on every database that has run V91.
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
VALUES (603, 'booking.session.defaultDurationMinutes', '60', 'LONG',
        'Default coaching session length in minutes; a coach may override it on their pricing row. '
        || 'ConfigService caches with a 5-minute TTL, so a change here applies within 5 minutes, not instantly.',
        NOW())
ON CONFLICT (key) DO NOTHING;
