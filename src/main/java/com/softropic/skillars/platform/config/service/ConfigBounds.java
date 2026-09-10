package com.softropic.skillars.platform.config.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code [min, max]} range for every numeric {@code platform_config} key <strong>migrated by
 * skillars-deferred-107 AC2</strong> (the codebase-wide {@code getLong}/{@code getInt} sweep), and
 * whether a bad value is severe enough to block application startup.
 *
 * <p><strong>Scope.</strong> This is <em>not</em> a registry of every bounded config key. The
 * pre-existing {@code ConfigService.getBoundedLong(...)} call sites that already predated this story
 * (in {@code BookingExpiryScheduler}, {@code BookingReminderScheduler}, {@code PaymentPendingSweeper},
 * {@code ReliabilityStrikeService}, {@code CoachPayoutOutboxSupport}, {@code SessionDurationResolver},
 * {@code MessageModerationSweeper}, {@code AdminCoachEnforcementService}) are deliberately not here —
 * folding them into {@link ConfigStartupAssertion} would be a scope extension. Add them if/when a
 * follow-up wants boot-time coverage for them too.
 *
 * <p>Two consumers read this registry (skillars-deferred-107 AC2 + AC3):
 * <ul>
 *   <li>the {@code ConfigService.getBoundedLong(...)} / {@code getBoundedInt(...)} call sites, which
 *       clamp an out-of-range stored value to a safe number at <em>read</em> time (keeps the flow
 *       alive), and</li>
 *   <li>{@link ConfigStartupAssertion}, which on {@code ApplicationReadyEvent} reads the actual
 *       stored values and, for {@link BoundedKey#failFast() failFast} keys, refuses to boot until an
 *       operator corrects them (makes the bad value <em>visible</em>).</li>
 * </ul>
 * {@code ConfigService.updateConfig} also consults it — a {@code PUT /api/config} write of a
 * non-numeric or out-of-range value for a bounded key is rejected with 400 (skillars-deferred-107
 * code review) so the boot assertion is not the only guard.
 *
 * <p>Each call site still passes its {@code [min, max]} literally (Mockito {@code verify(...)} in the
 * per-site unit tests pins the exact numbers), but the numbers here and at the call site are kept
 * identical and cross-referenced. If you change a bound, change it in both places.
 *
 * <h2>Fail-fast principle</h2>
 * <ul>
 *   <li>{@code failFast = true} — a bad value causes <strong>data loss / permanent corruption</strong>,
 *       or <strong>halts a core user flow entirely</strong> (no disputes filed, no reviews submitted,
 *       every pack pause rejected, every batch rejected, all messages deleted, all playback broken,
 *       a legally-required GDPR export link dead on arrival).</li>
 *   <li>{@code failFast = false} — a bad value <strong>degrades but does not break</strong> (a
 *       smaller-than-intended lifecycle batch, a shorter neglected-skill warmup, a wider auto-hold
 *       threshold). The read-time clamp already contains the blast radius; ERROR + metric is enough.</li>
 * </ul>
 */
public final class ConfigBounds {

    private ConfigBounds() {
    }

    /**
     * @param key      the {@code platform_config} key
     * @param min      smallest value the code can act on sanely (inclusive)
     * @param max      largest value that is not obviously a fat-finger (inclusive)
     * @param failFast whether {@link ConfigStartupAssertion} blocks startup on an out-of-range value
     * @param note     one line: what a bad value actually does (surfaced in the startup ERROR log)
     */
    public record BoundedKey(String key, long min, long max, boolean failFast, String note) {
    }

    // ── Long day/hour/minute/count windows (2-arg getBoundedLong call sites) ──────────────────────

    /** {@code PackSessionService.pausePack} — 0/neg/absurd blocks every pack pause. AC1. */
    public static final BoundedKey PACK_PAUSE_MAX_DAYS =
        new BoundedKey("pack.pause.maxDays", 1L, 3650L, true,
            "0/neg → every pack pause rejected as booking.pauseDurationInvalid");

    /** {@code DisputeService} — 0/neg → no dispute can ever be filed. */
    public static final BoundedKey DISPUTES_SUBMISSION_WINDOW_DAYS =
        new BoundedKey("disputes.submissionWindowDays", 1L, 365L, true,
            "0/neg → no dispute can ever be filed");

    /** {@code QuickCompleteTimeoutService} — 0 → instant timeout; neg → nonsense. */
    public static final BoundedKey BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS =
        new BoundedKey("booking.quick_complete_timeout_hours", 1L, 168L, false,
            "0 → Quick Complete auto-confirms instantly; neg → nonsense cutoff");

    /** {@code ModerationSlaMonitorService} — 0/neg → everything instantly SLA-breached. */
    public static final BoundedKey MODERATION_SLA_MINUTES =
        new BoundedKey("platform.moderation_sla_minutes", 1L, 10080L, true,
            "0/neg → every SCANNING video is instantly SLA-breached and re-queued");

    /** {@code ModerationSlaMonitorService} — neg breaks the retry-count comparison (0 = "no retries"). */
    public static final BoundedKey MODERATION_MAX_RETRIES =
        new BoundedKey("platform.moderation_max_retries", 0L, 100L, false,
            "neg → retry-count comparison inverts; huge → videos never fail out");

    /** {@code ModerationOrchestrationService} — 0 → lock instantly stale; huge → stuck rows. */
    public static final BoundedKey MODERATION_LOCK_TIMEOUT_MINUTES =
        new BoundedKey("platform.moderation_lock_timeout_minutes", 1L, 1440L, true,
            "0 → moderation lock is stale on creation (TOCTOU reopens); huge → permanently stuck rows");

    /** {@code VideoLifecycleScheduler} — 0/neg → archives immediately or never. */
    public static final BoundedKey VIDEO_LIFECYCLE_BLOCKED_TO_ARCHIVED_DAYS =
        new BoundedKey("platform.video.lifecycle.blocked_to_archived_days", 1L, 3650L, false,
            "0/neg → BLOCKED videos archived immediately; huge → never archived");

    /**
     * {@code VideoLifecycleScheduler} — 0/neg → deletes immediately. Ceiling is deliberately wide
     * (100y): this drives <em>physical asset deletion</em>, and the 4-arg {@code getBoundedLong}
     * falls back to the 90-day default (not the ceiling) on an out-of-range value, so a too-low
     * ceiling would silently delete assets years before a deliberate long-retention operator setting
     * intended. skillars-deferred-107 code review.
     */
    public static final BoundedKey VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS =
        new BoundedKey("platform.video.lifecycle.archived_to_deleted_days", 1L, 36500L, false,
            "0/neg → ARCHIVED videos physically deleted immediately; huge → never deleted");

    /** {@code PlaybackService} — 0 → signed URL dead on arrival (all playback broken). */
    public static final BoundedKey VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES =
        new BoundedKey("platform.video.playback.signed_url_ttl_minutes", 1L, 1440L, true,
            "0 → every signed HLS URL is expired on issue; all playback breaks");

    /** {@code VideoAccessGuard} — 0/neg → coach sees nothing. Business cap, kept < Integer.MAX_VALUE (Math.toIntExact). */
    public static final BoundedKey VIDEO_ACCESS_COACH_WINDOW_DAYS =
        new BoundedKey("platform.video.access.coach_window_days", 1L, 3650L, false,
            "0/neg → a coach with a recent completed booking can no longer view player videos");

    /** {@code QuotaConfigService} — 0 → reservations expire instantly. */
    public static final BoundedKey VIDEO_RESERVATION_TIMEOUT_MINUTES =
        new BoundedKey("platform.video_reservation_timeout_minutes", 1L, 1440L, false,
            "0 → every upload reservation expires instantly");

    /** {@code TimelineQueryService} — 0/neg → coach timeline access always expired. */
    public static final BoundedKey TIMELINE_COACH_ACCESS_EXPIRY_DAYS =
        new BoundedKey("development.timeline.coachAccessExpiryDays", 1L, 3650L, false,
            "0/neg → coach development-timeline access reads as always expired");

    /** {@code DevelopmentCorrelationService} — neg → every session qualifies (0 = legitimate). */
    public static final BoundedKey DEVELOPMENT_CORRELATION_MIN_SESSION_COUNT =
        new BoundedKey("development.correlation.minSessionCount", 0L, 10000L, false,
            "neg → correlation gate never blocks; huge → correlation never runs");

    /** {@code NeglectedSkillDetectionService} — neg → predicate inverts (0 = legitimate). */
    public static final BoundedKey DEVELOPMENT_NEGLECTED_SKILL_WARMUP_SESSION_COUNT =
        new BoundedKey("development.neglectedSkill.warmupSessionCount", 0L, 10000L, false,
            "neg → neglected-skill warmup predicate inverts");

    /** {@code SubscriptionService} — neg → grace math breaks (0 = "no grace" is legitimate). */
    public static final BoundedKey SUBSCRIPTION_PAST_DUE_GRACE_PERIOD_DAYS =
        new BoundedKey("subscription.pastDue.gracePeriodDays", 0L, 365L, false,
            "neg → PAST_DUE grace cutoff moves into the future, downgrading nobody / everybody");

    /** {@code GdprExportService} — 0 → a legally-required export link is dead on arrival. */
    public static final BoundedKey GDPR_EXPORT_URL_EXPIRY_HOURS =
        new BoundedKey("gdpr.export.urlExpiryHours", 1L, 720L, true,
            "0 → GDPR export download link is expired on issue; huge → compliance exposure");

    // ── Int retry / batch / window counts (getBoundedInt + (int)getBoundedLong call sites) ───────

    /** {@code MessageRetentionScheduler} — 0/neg → the retention query deletes every message. */
    public static final BoundedKey MESSAGE_RETENTION_MONTHS =
        new BoundedKey("platform.message_retention_months", 1L, 600L, true,
            "0/neg → retention deletes EVERY message on the next run (data-destructive)");

    /** {@code BookingBatchService} — 0/neg → every batch booking rejected; huge → unbounded batch. */
    public static final BoundedKey BOOKING_BATCH_MAX_SIZE =
        new BoundedKey("booking.batch.maxSize", 1L, 100L, true,
            "0/neg → every batch booking rejected as booking.batchSizeExceeded");

    /** {@code ReviewSubmissionService} — 0/neg → no review can ever be submitted. */
    public static final BoundedKey REVIEWS_SUBMISSION_WINDOW_DAYS =
        new BoundedKey("reviews.submissionWindowDays", 1L, 365L, true,
            "0/neg → no review can ever be submitted (no recent session found)");

    /** {@code ReviewFlagService} — 0 → every review auto-held. */
    public static final BoundedKey REVIEWS_AUTO_HOLD_FLAG_THRESHOLD =
        new BoundedKey("reviews.autoHoldFlagThreshold", 1L, 1000L, false,
            "0 → the first flag on any review auto-holds it");

    /** {@code VideoLifecycleScheduler} + {@code VideoSubscriptionLifecycleListener} — 0 → no progress. */
    public static final BoundedKey VIDEO_LIFECYCLE_BATCH_SIZE =
        new BoundedKey("platform.video.lifecycle.batch_size", 1L, 10000L, false,
            "0 → lifecycle scheduler makes no progress; huge → load spike");

    /** {@code VideoSubscriptionLifecycleListener} — 0/neg → outbox never drains or loop underflows. */
    public static final BoundedKey VIDEO_LIFECYCLE_OUTBOX_MAX_ATTEMPTS =
        new BoundedKey("platform.video.lifecycle.outbox_max_attempts", 1L, 100L, false,
            "0/neg → subscription-lifecycle outbox never drains");

    /** {@code VideoDeletionOutboxProcessor} — 0/neg → deletion outbox never drains / dead-letters everything. */
    public static final BoundedKey VIDEO_DELETION_MAX_ATTEMPTS =
        new BoundedKey("platform.video.deletion.max_attempts", 1L, 100L, false,
            "0/neg → Bunny.net deletion outbox dead-letters on the first attempt (or never)");

    /** {@code RadarCompositeDlqProcessor} — 0/neg → DLQ dead-letters on first attempt (or never). */
    public static final BoundedKey RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS =
        new BoundedKey("platform.development.radar_composite_dlq.max_attempts", 1L, 100L, false,
            "0/neg → radar-composite DLQ dead-letters on the first attempt (or never)");

    // ── Templated per-enum keys — generated, never hand-listed (AC3) ─────────────────────────────
    // Segments mirror QuotaConfigService.resolveTierKey (CoachSubscriptionTier lower-cased + the
    // "athlete" player fallback) and VideoTypeConstraints.configKey (VideoType camel-cased). Kept in
    // sync with those enums by ConfigBoundsEnumCoverageTest, which fails if a new constant is added
    // without a matching bound here.

    static final List<String> VIDEO_QUOTA_TIER_SEGMENTS = List.of("scout", "instructor", "academy", "athlete");
    static final List<String> VIDEO_TYPE_SEGMENTS = List.of("homework", "drillDemo", "coachReview");

    /**
     * Keys whose call site passes a code default (2-arg {@code getLong}/{@code getInt}) or catches
     * the missing-key {@code IllegalStateException} — an <em>absent</em> or <em>blank</em> value is
     * survivable there (the code default applies), so {@link ConfigStartupAssertion} logs it at DEBUG
     * and never blocks boot on it. Every other bounded key is a 1-arg call site: an absent/blank
     * value throws {@code IllegalStateException} at runtime, so it is an ERROR (and a boot blocker
     * for a {@code failFast} key). A <em>present but non-numeric</em> value is always an ERROR
     * regardless — a 2-arg site silently swallowing {@code "abc"} to its default is masking operator
     * error, not tolerating it.
     */
    public static final Set<String> HAS_CODE_DEFAULT = Set.of(
        PACK_PAUSE_MAX_DAYS.key(),
        DISPUTES_SUBMISSION_WINDOW_DAYS.key(),
        VIDEO_LIFECYCLE_BLOCKED_TO_ARCHIVED_DAYS.key(),
        VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS.key(),
        VIDEO_LIFECYCLE_BATCH_SIZE.key(),
        VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES.key(),
        VIDEO_ACCESS_COACH_WINDOW_DAYS.key(),
        VIDEO_DELETION_MAX_ATTEMPTS.key(),
        RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS.key(),
        GDPR_EXPORT_URL_EXPIRY_HOURS.key(),
        MESSAGE_RETENTION_MONTHS.key(),
        REVIEWS_SUBMISSION_WINDOW_DAYS.key(),
        REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key(),
        TIMELINE_COACH_ACCESS_EXPIRY_DAYS.key());

    /** Every bound above, plus the generated per-tier / per-type keys. */
    public static final List<BoundedKey> ALL;

    static {
        List<BoundedKey> all = new ArrayList<>(List.of(
            PACK_PAUSE_MAX_DAYS,
            DISPUTES_SUBMISSION_WINDOW_DAYS,
            BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS,
            MODERATION_SLA_MINUTES,
            MODERATION_MAX_RETRIES,
            MODERATION_LOCK_TIMEOUT_MINUTES,
            VIDEO_LIFECYCLE_BLOCKED_TO_ARCHIVED_DAYS,
            VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS,
            VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES,
            VIDEO_ACCESS_COACH_WINDOW_DAYS,
            VIDEO_RESERVATION_TIMEOUT_MINUTES,
            TIMELINE_COACH_ACCESS_EXPIRY_DAYS,
            DEVELOPMENT_CORRELATION_MIN_SESSION_COUNT,
            DEVELOPMENT_NEGLECTED_SKILL_WARMUP_SESSION_COUNT,
            SUBSCRIPTION_PAST_DUE_GRACE_PERIOD_DAYS,
            GDPR_EXPORT_URL_EXPIRY_HOURS,
            MESSAGE_RETENTION_MONTHS,
            BOOKING_BATCH_MAX_SIZE,
            REVIEWS_SUBMISSION_WINDOW_DAYS,
            REVIEWS_AUTO_HOLD_FLAG_THRESHOLD,
            VIDEO_LIFECYCLE_BATCH_SIZE,
            VIDEO_LIFECYCLE_OUTBOX_MAX_ATTEMPTS,
            VIDEO_DELETION_MAX_ATTEMPTS,
            RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS));

        for (String tier : VIDEO_QUOTA_TIER_SEGMENTS) {
            // Scout is seeded storageBytes = 0 deliberately ("0 = no upload", V53), so the floor is
            // 0, not 1 — a negative quota is the only genuinely broken state. Max Long.MAX_VALUE:
            // only the lower bound is a real risk here.
            all.add(new BoundedKey("video.quota." + tier + ".storageBytes", 0L, Long.MAX_VALUE, false,
                "neg → storage-quota math breaks; 0 is a legitimate \"no upload\" sentinel"));
            all.add(new BoundedKey("video.quota." + tier + ".bandwidthBytesMonthly", 0L, Long.MAX_VALUE, false,
                "neg → bandwidth-quota math breaks; 0 is a legitimate \"no streaming\" sentinel"));
        }
        for (String seg : VIDEO_TYPE_SEGMENTS) {
            all.add(new BoundedKey("video." + seg + ".maxSizeBytes", 1L, Long.MAX_VALUE, false,
                "0 → every upload of this type rejected"));
            all.add(new BoundedKey("video." + seg + ".maxDurationSeconds", 1L, 86400L, false,
                "0 → every upload of this type rejected; huge → no effective cap"));
        }
        ALL = List.copyOf(all);
    }
}
