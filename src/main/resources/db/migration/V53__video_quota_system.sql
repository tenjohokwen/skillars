-- Video quota tables
CREATE TABLE main.video_quotas (
    user_id              VARCHAR(255) NOT NULL,
    storage_used_bytes   BIGINT       NOT NULL DEFAULT 0,
    bandwidth_used_bytes BIGINT       NOT NULL DEFAULT 0,
    bandwidth_period_start TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_video_quotas PRIMARY KEY (user_id),
    CONSTRAINT chk_video_quotas_storage  CHECK (storage_used_bytes   >= 0),
    CONSTRAINT chk_video_quotas_bandwidth CHECK (bandwidth_used_bytes >= 0)
);

CREATE TABLE main.video_quota_reservations (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id        VARCHAR(255) NOT NULL,
    video_type     VARCHAR(30)  NULL,     -- nullable: QuotaProvider.reserve() has no videoType param; populated in Story 6.2
    reserved_bytes BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_video_quota_reservations PRIMARY KEY (id),
    CONSTRAINT fk_vqr_quota FOREIGN KEY (user_id) REFERENCES main.video_quotas(user_id),
    CONSTRAINT chk_vqr_type   CHECK (video_type IS NULL OR video_type IN ('HOMEWORK', 'DRILL_DEMO', 'COACH_REVIEW')),
    CONSTRAINT chk_vqr_status CHECK (status IN ('ACTIVE', 'COMMITTED', 'RELEASED')),
    CONSTRAINT chk_vqr_bytes  CHECK (reserved_bytes > 0)
);

CREATE INDEX idx_vqr_user_status ON main.video_quota_reservations(user_id, status);
CREATE INDEX idx_vqr_expires     ON main.video_quota_reservations(expires_at) WHERE status = 'ACTIVE';

-- Video-specific storage quotas (BYTES) — separate from general storage quotas in V20
-- These are the Skillars video upload quotas per subscription tier.
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
(117, 'video.quota.scout.storageBytes',             '0',            'STRING', 'Scout: video storage quota (0 = no upload)'),
(118, 'video.quota.instructor.storageBytes',        '5368709120',   'STRING', 'Instructor: video storage quota (5 GiB)'),
(119, 'video.quota.academy.storageBytes',           '21474836480',  'STRING', 'Academy: video storage quota (20 GiB)'),
(120, 'video.quota.athlete.storageBytes',           '2147483648',   'STRING', 'Athlete: video storage quota (2 GiB)'),
(121, 'video.quota.semiPro.storageBytes',           '4294967296',   'STRING', 'Semi-Pro: video storage quota (4 GiB)'),
(122, 'video.quota.pro.storageBytes',               '7516192768',   'STRING', 'Pro: video storage quota (7 GiB)'),
-- Monthly bandwidth quotas (BYTES/month)
(123, 'video.quota.scout.bandwidthBytesMonthly',    '5368709120',   'STRING', 'Scout: monthly bandwidth (5 GiB)'),
(124, 'video.quota.instructor.bandwidthBytesMonthly','53687091200', 'STRING', 'Instructor: monthly bandwidth (50 GiB)'),
(125, 'video.quota.academy.bandwidthBytesMonthly',  '214748364800', 'STRING', 'Academy: monthly bandwidth (200 GiB)'),
(126, 'video.quota.athlete.bandwidthBytesMonthly',  '10737418240',  'STRING', 'Athlete: monthly bandwidth (10 GiB)'),
(127, 'video.quota.semiPro.bandwidthBytesMonthly',  '26843545600',  'STRING', 'Semi-Pro: monthly bandwidth (25 GiB)'),
(128, 'video.quota.pro.bandwidthBytesMonthly',      '53687091200',  'STRING', 'Pro: monthly bandwidth (50 GiB)'),
-- Video type constraints (homework + coach review — drill demo already in V42 IDs 66-67)
(129, 'video.homework.maxDurationSeconds',          '60',           'STRING', 'Homework: max duration (60s)'),
(130, 'video.homework.maxSizeBytes',                '262144000',    'STRING', 'Homework: max size (250 MiB)'),
(131, 'video.coachReview.maxDurationSeconds',       '300',          'STRING', 'Coach review: max duration (300s)'),
(132, 'video.coachReview.maxSizeBytes',             '1073741824',   'STRING', 'Coach review: max size (1 GiB)');
