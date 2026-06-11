CREATE TABLE IF NOT EXISTS main.platform_config (
    id           BIGINT        NOT NULL,
    key          VARCHAR(255)  NOT NULL,
    value        TEXT          NOT NULL,
    value_type   VARCHAR(20)   NOT NULL DEFAULT 'STRING',
    description  VARCHAR(500),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT uq_platform_config_key UNIQUE (key),
    CONSTRAINT chk_platform_config_type CHECK (value_type IN ('STRING', 'LONG'))
);

CREATE INDEX idx_platform_config_key ON main.platform_config(key);

INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
-- Coach tier feature gates
(1,  'coach.tier.scout.drill_library',            'true',  'STRING', 'Scout: drill library access'),
(2,  'coach.tier.scout.session_builder',          'true',  'STRING', 'Scout: session builder access'),
(3,  'coach.tier.scout.video_upload',             'false', 'STRING', 'Scout: video upload capability'),
(4,  'coach.tier.instructor.drill_library',       'true',  'STRING', 'Instructor: drill library access'),
(5,  'coach.tier.instructor.session_builder',     'true',  'STRING', 'Instructor: session builder access'),
(6,  'coach.tier.instructor.video_upload',        'true',  'STRING', 'Instructor: video upload capability'),
(7,  'coach.tier.academy.drill_library',          'true',  'STRING', 'Academy: drill library access'),
(8,  'coach.tier.academy.session_builder',        'true',  'STRING', 'Academy: session builder access'),
(9,  'coach.tier.academy.video_upload',           'true',  'STRING', 'Academy: video upload capability'),
-- Player tier feature gates
(10, 'player.tier.athlete.video_access',          'false', 'STRING', 'Athlete: video access'),
(11, 'player.tier.athlete.skills_radar',          'false', 'STRING', 'Athlete: skills radar access'),
(12, 'player.tier.semi_pro.video_access',         'true',  'STRING', 'Semi-Pro: video access'),
(13, 'player.tier.semi_pro.skills_radar',         'true',  'STRING', 'Semi-Pro: skills radar access'),
(14, 'player.tier.pro.video_access',              'true',  'STRING', 'Pro: video access'),
(15, 'player.tier.pro.skills_radar',              'true',  'STRING', 'Pro: skills radar access'),
-- Storage quotas (GB)
(16, 'coach.tier.scout.storage_quota_gb',         '5',     'LONG',   'Scout storage quota in GB'),
(17, 'coach.tier.instructor.storage_quota_gb',    '20',    'LONG',   'Instructor storage quota in GB'),
(18, 'coach.tier.academy.storage_quota_gb',       '50',    'LONG',   'Academy storage quota in GB'),
(19, 'player.tier.athlete.storage_quota_gb',      '2',     'LONG',   'Athlete storage quota in GB'),
(20, 'player.tier.semi_pro.storage_quota_gb',     '5',     'LONG',   'Semi-Pro storage quota in GB'),
(21, 'player.tier.pro.storage_quota_gb',          '10',    'LONG',   'Pro storage quota in GB'),
-- Bandwidth quotas (GB/month)
(22, 'coach.tier.scout.bandwidth_quota_gb',       '20',    'LONG',   'Scout bandwidth quota GB/month'),
(23, 'coach.tier.instructor.bandwidth_quota_gb',  '80',    'LONG',   'Instructor bandwidth quota GB/month'),
(24, 'coach.tier.academy.bandwidth_quota_gb',     '200',   'LONG',   'Academy bandwidth quota GB/month'),
(25, 'player.tier.athlete.bandwidth_quota_gb',    '10',    'LONG',   'Athlete bandwidth quota GB/month'),
(26, 'player.tier.semi_pro.bandwidth_quota_gb',   '20',    'LONG',   'Semi-Pro bandwidth quota GB/month'),
(27, 'player.tier.pro.bandwidth_quota_gb',        '40',    'LONG',   'Pro bandwidth quota GB/month'),
-- Platform parameters
(28, 'platform.commission_rate',                  '0.08',  'STRING', 'Platform commission rate (decimal)'),
(29, 'platform.reliability_strike_expiry_days',   '90',    'LONG',   'Days before reliability strike expires'),
(30, 'platform.strike_threshold.admin_alert',     '3',     'LONG',   'Strikes before admin alert is triggered'),
(31, 'platform.strike_threshold.auto_suspension', '5',     'LONG',   'Strikes before automatic suspension'),
(32, 'platform.timeline_access_expiry_days',      '30',    'LONG',   'Days of timeline access after relationship ends'),
(33, 'platform.video_signed_url_ttl_seconds',     '7200',  'LONG',   'Video signed URL TTL in seconds (2 hours)'),
(34, 'platform.video_reservation_timeout_minutes','60',    'LONG',   'Video upload reservation timeout in minutes'),
(35, 'platform.reminder_interval_primary_hours',  '24',    'LONG',   'Primary reminder interval before session (hours)'),
(36, 'platform.reminder_interval_secondary_hours','2',     'LONG',   'Secondary reminder interval before session (hours)'),
(37, 'platform.message_retention_months',         '24',    'LONG',   'Message retention period in months');
