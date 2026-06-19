-- Story 5.5: PDF Performance Reports & Unified Player Timeline

-- Performance reports — one row per generated report
CREATE TABLE development.performance_reports (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_id        UUID            NOT NULL,
    player_id       BIGINT          NOT NULL,   -- TSID Long; NOT UUID
    generated_at    TIMESTAMPTZ     NOT NULL,
    storage_key     VARCHAR(500)    NOT NULL,   -- filestorage key (not a URL); used for signed URL generation
    next_steps      VARCHAR(500)    NOT NULL,
    version         INT             NOT NULL DEFAULT 1
);
CREATE INDEX idx_performance_reports_player_id
    ON development.performance_reports (player_id, generated_at DESC);

-- Coach branding (Academy only) — one row per coach
CREATE TABLE development.coach_branding (
    coach_id        UUID            PRIMARY KEY,
    logo_key        VARCHAR(500)    NULL,       -- storage key for logo image; null = no logo uploaded
    brand_colour    VARCHAR(7)      NULL,       -- hex colour e.g. '#FF5733'; null = use Skillars default
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Player timeline events — append-only, never UPDATE or DELETE except GDPR erasure
CREATE TABLE development.player_timeline_events (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    player_id       BIGINT          NOT NULL,   -- TSID Long; NOT UUID
    event_type      VARCHAR(50)     NOT NULL,   -- PlayerTimelineEventType enum value
    reference_id    UUID            NULL,       -- FK to the originating record (bookingId, reportId, etc.)
    reference_module VARCHAR(50)    NULL,       -- e.g. 'booking', 'development'
    occurred_at     TIMESTAMPTZ     NOT NULL,
    metadata        JSONB           NULL        -- event-specific context (coach name, skill codes, etc.)
);
CREATE INDEX idx_player_timeline_events_player_id
    ON development.player_timeline_events (player_id, occurred_at DESC);

-- ConfigService key for coach timeline access expiry
INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at) VALUES
    (116, 'development.timeline.coachAccessExpiryDays', '90', 'LONG',
     'Days of inactivity after which a coach loses access to a player timeline', NOW())
ON CONFLICT (key) DO NOTHING;
