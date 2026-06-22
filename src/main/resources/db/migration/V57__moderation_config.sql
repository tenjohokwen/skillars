-- Moderation SLA and retry configuration
-- Lock timeout (15 min) must be strictly less than SLA window (30 min)
-- so the SLA monitor can still detect genuinely stuck videos after the lock expires.
-- With 5 max retries and 30-min SLA, a video is permanently failed after ~150 min.
INSERT INTO main.platform_config (id, key, value, value_type, description) VALUES
(133, 'platform.moderation_sla_minutes',         '30', 'LONG',   'Moderation SLA window in minutes — videos stuck in SCANNING beyond this are re-queued'),
(134, 'platform.moderation_lock_timeout_minutes', '15', 'LONG',   'Moderation in-flight lock duration in minutes — SLA monitor skips videos with active lock'),
(135, 'platform.moderation_max_retries',          '5',  'LONG',   'Max SLA monitor retries before video is permanently failed'),
(136, 'platform.admin_alert_email',               '',   'STRING', 'Admin alert recipient email for moderation events (CSAM, service unavailability)')
ON CONFLICT (key) DO NOTHING;
