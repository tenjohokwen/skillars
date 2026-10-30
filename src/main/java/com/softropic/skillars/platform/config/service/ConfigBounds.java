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
 * <p>Every {@link #HAS_CODE_DEFAULT} key is read through {@code ConfigService.getBoundedLong(BoundedKey)} /
 * {@code getBoundedInt(BoundedKey)} (#325, call sites migrated by C5), so key, default and range live
 * only here — change a bound here and every reader follows. The remaining literal call sites are the
 * no-code-default (3-arg, clamping) reads, which that overload deliberately rejects; their
 * {@code [min, max]} is still kept identical to the entry here by hand.
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
     * @param key          the {@code platform_config} key
     * @param min          smallest value the code can act on sanely (inclusive)
     * @param max          largest value that is not obviously a fat-finger (inclusive)
     * @param failFast     whether {@link ConfigStartupAssertion} blocks startup on an out-of-range value
     * @param note         one line: what a bad value actually does (surfaced in the startup ERROR log)
     * @param defaultValue skillars-deferred-133 AC2: for a key in {@link #HAS_CODE_DEFAULT}, the same
     *                     literal its call site actually falls back to on an absent/out-of-range
     *                     value — all 18 via the 4-arg {@code getBoundedLong(key, default, min,
     *                     max)}/{@code getBoundedInt} overload (which does NOT clamp: out-of-range
     *                     falls back to this default, unlike the 3-arg overload's clamp-to-bound
     *                     behavior). {@link #TIMELINE_COACH_ACCESS_EXPIRY_DAYS} was the one 3-arg
     *                     exception until skillars-12-10 moved it onto the 4-arg overload via
     *                     {@code CoachAccessWindow}. For every key NOT in {@code HAS_CODE_DEFAULT} (a 1-arg,
     *                     no-code-default call site — absent/blank there is an {@code
     *                     IllegalStateException}, not a fallback), this is the not-applicable sentinel
     *                     {@code 0L} — not a real default, since none exists to record.
     *                     <p><strong>Since C5 this IS the value that runs:</strong> every call site
     *                     of a {@code HAS_CODE_DEFAULT} key reads it through the {@code BoundedKey}
     *                     overload, which passes this field as the fallback (it used to be
     *                     documentation only, with each call site's own literal the source of truth).
     */
    public record BoundedKey(String key, long min, long max, boolean failFast, String note, long defaultValue) {
    }

    // ── Long day/hour/minute/count windows (2-arg getBoundedLong call sites) ──────────────────────

    /** {@code PackSessionService.pausePack} — 0/neg/absurd blocks every pack pause. AC1. */
    public static final BoundedKey PACK_PAUSE_MAX_DAYS =
        new BoundedKey("pack.pause.maxDays", 1L, 3650L, true,
            "0/neg → every pack pause rejected as booking.pauseDurationInvalid", 90L);

    /**
     * fix/pack-expiry-size-tiers: {@code SessionPackValidity} — a session pack's validity at purchase,
     * by its session count (1 / 2-5 / 6-10 / 11+). Seeded by V139 at exactly these defaults, so a
     * fresh DB and an absent row behave identically. Read via the {@code BoundedKey} overload: an
     * absent, non-numeric or out-of-range value falls back to the default — never fails a purchase
     * the payer has already been charged for. The floor is 14 because both the expiry-warning email
     * ({@code SessionPackExpiryNotifier}) and the coach "Extend" window
     * ({@code SessionPackPaymentService.EXTENSION_WINDOW}) open 14 days before expiry; a shorter
     * validity would warn on the day of purchase. Applies only to packs bought after the value
     * changes — an existing pack keeps the {@code expires_at} stamped at its purchase. Nothing
     * cross-checks that the tiers ascend.
     */
    public static final BoundedKey PACK_EXPIRY_DAYS_TIER1 =
        new BoundedKey("pack.expiry.days.tier1", 14L, 3650L, false,
            "too low → 1-session packs warn/expire almost at once; out of range → falls back to 90", 90L);

    /** 2-5 session packs — see {@link #PACK_EXPIRY_DAYS_TIER1}. */
    public static final BoundedKey PACK_EXPIRY_DAYS_TIER2 =
        new BoundedKey("pack.expiry.days.tier2", 14L, 3650L, false,
            "too low → 2-5 session packs warn/expire almost at once; out of range → falls back to 180", 180L);

    /** 6-10 session packs — see {@link #PACK_EXPIRY_DAYS_TIER1}. */
    public static final BoundedKey PACK_EXPIRY_DAYS_TIER3 =
        new BoundedKey("pack.expiry.days.tier3", 14L, 3650L, false,
            "too low → 6-10 session packs warn/expire almost at once; out of range → falls back to 365", 365L);

    /** 11+ session packs — see {@link #PACK_EXPIRY_DAYS_TIER1}. */
    public static final BoundedKey PACK_EXPIRY_DAYS_TIER4 =
        new BoundedKey("pack.expiry.days.tier4", 14L, 3650L, false,
            "too low → 11+ session packs warn/expire almost at once; out of range → falls back to 548", 548L);

    /**
     * fix/audit3-completion-dispute-integrity: {@code DisputeService.raiseDispute} — how many disputes one raising
     * side (the coach, or the family: parent and player logins together) may raise on one booking in total (open,
     * resolved or dismissed). Each raise re-freezes the coach payout,
     * so without a cap a dismissed raiser could refile for the whole submission window and keep the coach's
     * money frozen. Appeals ({@code AppealService}) are the route past a decision.
     */
    public static final BoundedKey DISPUTES_MAX_PER_BOOKING_PER_RAISER =
        new BoundedKey("disputes.maxPerBookingPerRaiser", 1L, 100L, false,
            "out-of-range falls back to the coded default (2); huge → a raiser can re-freeze the payout repeatedly", 2L);

    /** {@code DisputeService} — 0/neg → no dispute can ever be filed. */
    public static final BoundedKey DISPUTES_SUBMISSION_WINDOW_DAYS =
        new BoundedKey("disputes.submissionWindowDays", 1L, 365L, true,
            "0/neg → no dispute can ever be filed", 14L);

    /** {@code QuickCompleteTimeoutService} — 0 → instant timeout; neg → nonsense. */
    public static final BoundedKey BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS =
        new BoundedKey("booking.quick_complete_timeout_hours", 1L, 168L, false,
            "0 → Quick Complete auto-confirms instantly; neg → nonsense cutoff", 0L);

    /**
     * {@code StuckCompletionSweeperService} — skillars-deferred-153 AC1: registered in
     * {@link #HAS_CODE_DEFAULT} and read via the 4-arg {@code getBoundedLong(key, defaultValue, min,
     * max)}, same shape as {@link #DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS} — deliberately NOT
     * {@link #BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS}'s shape (a {@code 0L} not-applicable sentinel,
     * excluded from {@code HAS_CODE_DEFAULT}, that only works because V139 seeds a real value
     * separately). The two keys answer different questions — row exists and is awaiting parent
     * confirmation (that key) vs. row never existed at all (this key) — so a separate key is
     * correct.
     *
     * <p>Code review (2026-10-17), OWNER DECISION — default raised 24h → 72h and the floor raised
     * 1h → 6h. Once this sweeper completes a booking the transition is one-way and there is no
     * update path for the placeholder row, so a coach who finishes the wrap-up wizard after the
     * cutoff permanently loses their recap, ratings, voice note and homework, and the session is
     * recorded as not attended. At the old 1h floor that was a routine outcome rather than an edge
     * case. This sweeper is a terminal catch-all for a booking nobody is waiting on, so a longer
     * window costs nothing and removes almost all of the collision window; the floor exists so the
     * window can never be retuned back down into the middle of a live wizard session.
     */
    public static final BoundedKey BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS =
        new BoundedKey("booking.stuck_completion_timeout_hours", 6L, 720L, false,
            "0/neg → every unconfirmed, row-less Quick Complete is force-completed near-instantly", 72L);

    /**
     * skillars-12-7 AC3: how long after a booking's {@code requestedEndTime} the owning coach may still
     * add or correct the session recap. Registered in {@link #HAS_CODE_DEFAULT} and {@link #ALL}, read
     * via the 4-arg {@code getBoundedLong} (out of range falls back to the default). The floor is 7, not
     * 1, because of {@link #BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS}: the stuck-completion sweeper can
     * create the placeholder recap row up to that many hours after the booking was last updated (default
     * 72h), and a window shorter than that would make a swept session born un-editable and un-nudged.
     * The floor only covers the DEFAULT timeout: that key's own max is 720h (30 days), and the window is
     * anchored on {@code requestedEndTime} rather than the sweep time, so an operator raising the timeout
     * must keep this window comfortably above it (code review, skillars-12-7) -- the two bounds are
     * independent and nothing cross-checks them at startup.
     */
    public static final BoundedKey BOOKING_RECAP_EDIT_WINDOW_DAYS =
        new BoundedKey("booking.recap.editWindowDays", 7L, 365L, false,
            "below the stuck-completion timeout → swept sessions are born un-editable; absurd → recaps editable forever", 30L);

    /**
     * fix/booking-cancel-policy-loopholes: {@code BookingService.cancelBookingAsParent} — how long
     * after a booking's start a still-unstarted (CONFIRMED/UPCOMING) session must sit before a
     * parent cancel becomes refund-eligible. Before this, start+1s was enough, so any parent inside
     * the 24h forfeit window could wait out the start time and cancel for 100% back. The floor is 5,
     * not 0: 0 reopens exactly that loophole.
     */
    public static final BoundedKey BOOKING_LATE_CANCEL_GRACE_MINUTES =
        new BoundedKey("booking.lateCancel.graceMinutes", 5L, 1440L, false,
            "out-of-range falls back to the coded default (30); tiny → parents cancel at start for a full refund", 30L);

    /**
     * fix/booking-completion-integrity: {@code BookingService.recordNoShowPlayer} — how long after a
     * booking's start the coach must wait before reporting a player no-show (a forfeit that pays the
     * coach). Before this, start+0s was enough, so a family a minute late lost the session before they
     * could do anything. Owner decision 2026-10-29: 15. The floor is 5, not 0: 0 reopens exactly that.
     */
    public static final BoundedKey BOOKING_PLAYER_NO_SHOW_GRACE_MINUTES =
        new BoundedKey("booking.playerNoShow.graceMinutes", 5L, 1440L, false,
            "out-of-range falls back to the coded default (15); tiny → coaches forfeit late families at start", 15L);

    /**
     * fix/booking-completion-integrity: {@code UnresolvedSessionSweeperService} — how long after a
     * booking's scheduled end a session still IN_PROGRESS / PAUSED is ended automatically (moved to
     * COMPLETED_PENDING_CONFIRMATION, so the parent's confirm / dispute window applies), and a session
     * still UPCOMING (never started) raises a SESSION_NEVER_STARTED admin alert. The floor keeps a
     * session that merely overran from being cut off.
     */
    public static final BoundedKey BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES =
        new BoundedKey("booking.unresolvedSession.graceMinutes", 30L, 10080L, false,
            "out-of-range falls back to the coded default (120); tiny → overrunning live sessions are cut off", 120L);

    /**
     * fix/audit3-wallet-cashout-integrity: {@code CoachPayoutOutboxSupport.enqueuePayout} — a coach with fewer
     * than this many payouts already released (transferred) is "new": their payouts are held
     * {@link #PAYOUT_NEW_COACH_HOLD_HOURS} instead of {@code payment.payout.hold_hours}, so a chargeback inside
     * the card network's window lands while the money is still on the platform. 0 = no new-coach hold.
     */
    public static final BoundedKey PAYOUT_NEW_COACH_RELEASED_COUNT =
        new BoundedKey("payment.payout.newCoach.releasedCount", 0L, 100L, false,
            "out-of-range falls back to the coded default (5); 0 → new coaches get the normal hold", 5L);

    /**
     * fix/audit3-wallet-cashout-integrity: the smallest released payout (net the coach kept, in whole units of the
     * platform currency — {@code platform.payment.currency}; every payout is in it) that counts toward
     * {@link #PAYOUT_NEW_COACH_RELEASED_COUNT}, so a few tiny sock-puppet sessions do not end the longer hold.
     * 0 = every released payout counts.
     */
    public static final BoundedKey PAYOUT_NEW_COACH_MIN_RELEASED_NET =
        new BoundedKey("payment.payout.newCoach.minReleasedNet", 0L, 10000L, false,
            "out-of-range falls back to the coded default (20); 0 → tiny payouts end the new-coach hold", 20L);

    /**
     * fix/audit3-wallet-cashout-integrity: the payout hold (hours) for a new coach (see above). Same ceiling as
     * {@code payment.payout.hold_hours} (336 = 14 days).
     */
    public static final BoundedKey PAYOUT_NEW_COACH_HOLD_HOURS =
        new BoundedKey("payment.payout.newCoach.holdHours", 0L, 336L, false,
            "out-of-range falls back to the coded default (168); tiny → a new coach is paid before chargebacks land",
            168L);

    /**
     * fix/audit3-completion-dispute-integrity: {@code BookingCompletionService.endSession} — a live session
     * (Start pressed) ended in under this percentage of its booked length is not a LIVE completion: it waits
     * for the family's confirmation, which they may dispute, like a Quick Complete. Before this a coach could
     * press Start then End a minute later and be paid at once with no parent confirmation at all. Pauses are
     * not tracked, so a paused stretch counts as live time. 0 = never (every ended live session is LIVE);
     * 100 = only a session run for its full booked length.
     */
    public static final BoundedKey BOOKING_LIVE_MIN_DURATION_PERCENT =
        new BoundedKey("booking.live.minDurationPercent", 0L, 100L, false,
            "out-of-range falls back to the coded default (50); 0 → a one-minute live session completes and pays at once", 50L);

    /**
     * fix/booking-cancel-policy-loopholes: {@code CancellationRefundService.onBookingCancelledByCoach}
     * — excused (self-declared) coach cancellations allowed per rolling window before an excused one
     * also issues a {@code COACH_CANCELLATION_EXCUSED_LIMIT} strike. 0 = every excused cancel strikes.
     */
    public static final BoundedKey COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW =
        new BoundedKey("coach.excusedCancellations.maxPerWindow", 0L, 100L, false,
            "out-of-range falls back to the coded default (3); huge → excused reasons never strike", 3L);

    /** fix/booking-cancel-policy-loopholes: the rolling window (days) for the key above. */
    public static final BoundedKey COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS =
        new BoundedKey("coach.excusedCancellations.windowDays", 1L, 3650L, false,
            "out-of-range falls back to the coded default (90); tiny → the excused-cancel cap never bites", 90L);

    /**
     * fix/booking-request-consent (M2): {@code BookingService.requireRequestQuota} — how many of a parent's
     * own requests may await one coach's answer at once (single and batch creation; coach-proposed bookings
     * do not count). Requests are free and hold their slot, so without a cap one account could hide a
     * coach's whole calendar. A single batch larger than this is refused too.
     */
    public static final BoundedKey BOOKING_REQUEST_MAX_PENDING_PER_COACH =
        new BoundedKey("booking.request.maxPendingPerCoach", 1L, 100L, false,
            "out-of-range falls back to the coded default (10); huge → one account can block a coach's calendar", 10L);

    /**
     * fix/booking-request-consent (M2): {@code BookingService.requireRequestQuota} — how many days ahead a
     * session may be requested. Also bounds how far ahead a price can be locked ({@code agreed_price}).
     */
    public static final BoundedKey BOOKING_REQUEST_MAX_HORIZON_DAYS =
        new BoundedKey("booking.request.maxHorizonDays", 1L, 730L, false,
            "out-of-range falls back to the coded default (120); tiny → families cannot book next week", 120L);

    /** {@code ModerationSlaMonitorService} — 0/neg → everything instantly SLA-breached. */
    public static final BoundedKey MODERATION_SLA_MINUTES =
        new BoundedKey("platform.moderation_sla_minutes", 1L, 10080L, true,
            "0/neg → every SCANNING video is instantly SLA-breached and re-queued", 0L);

    /** {@code ModerationSlaMonitorService} — neg breaks the retry-count comparison (0 = "no retries"). */
    public static final BoundedKey MODERATION_MAX_RETRIES =
        new BoundedKey("platform.moderation_max_retries", 0L, 100L, false,
            "neg → retry-count comparison inverts; huge → videos never fail out", 0L);

    /** {@code ModerationOrchestrationService} — 0 → lock instantly stale; huge → stuck rows. */
    public static final BoundedKey MODERATION_LOCK_TIMEOUT_MINUTES =
        new BoundedKey("platform.moderation_lock_timeout_minutes", 1L, 1440L, true,
            "0 → moderation lock is stale on creation (TOCTOU reopens); huge → permanently stuck rows", 0L);

    /** {@code VideoLifecycleScheduler} — 0/neg → archives immediately or never. */
    public static final BoundedKey VIDEO_LIFECYCLE_BLOCKED_TO_ARCHIVED_DAYS =
        new BoundedKey("platform.video.lifecycle.blocked_to_archived_days", 1L, 3650L, false,
            "0/neg → BLOCKED videos archived immediately; huge → never archived", 30L);

    /**
     * {@code VideoLifecycleScheduler} — 0/neg → deletes immediately. Ceiling is deliberately wide
     * (100y): this drives <em>physical asset deletion</em>, and the 4-arg {@code getBoundedLong}
     * falls back to the 90-day default (not the ceiling) on an out-of-range value, so a too-low
     * ceiling would silently delete assets years before a deliberate long-retention operator setting
     * intended. skillars-deferred-107 code review.
     */
    public static final BoundedKey VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS =
        new BoundedKey("platform.video.lifecycle.archived_to_deleted_days", 1L, 36500L, false,
            "0/neg → ARCHIVED videos physically deleted immediately; huge → never deleted", 90L);

    /** {@code PlaybackService} — 0 → signed URL dead on arrival (all playback broken). */
    public static final BoundedKey VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES =
        new BoundedKey("platform.video.playback.signed_url_ttl_minutes", 1L, 1440L, true,
            "0 → every signed HLS URL is expired on issue; all playback breaks", 120L);

    /** {@code VideoAccessGuard} — 0/neg → coach sees nothing. Business cap, kept < Integer.MAX_VALUE (Math.toIntExact). */
    public static final BoundedKey VIDEO_ACCESS_COACH_WINDOW_DAYS =
        new BoundedKey("platform.video.access.coach_window_days", 1L, 3650L, false,
            "0/neg → a coach with a recent completed booking can no longer view player videos", 90L);

    /** {@code QuotaConfigService} — 0 → reservations expire instantly. */
    public static final BoundedKey VIDEO_RESERVATION_TIMEOUT_MINUTES =
        new BoundedKey("platform.video_reservation_timeout_minutes", 1L, 1440L, false,
            "0 → every upload reservation expires instantly", 0L);

    /** {@code CoachAccessWindow} (skillars-12-10) — out-of-range → falls back to the 90-day default. */
    public static final BoundedKey TIMELINE_COACH_ACCESS_EXPIRY_DAYS =
        new BoundedKey("development.timeline.coachAccessExpiryDays", 1L, 3650L, false,
            "out-of-range → default 90 (skillars-12-10: the single coach-access window for every per-player development read)", 90L);

    /**
     * {@code CoachRosterService} — skillars-12-1 AC4/Task 2.4: registered in
     * {@link #HAS_CODE_DEFAULT} and read via the 4-arg {@code getBoundedLong(key, defaultValue, min,
     * max)}, same as {@link #TIMELINE_COACH_ACCESS_EXPIRY_DAYS} immediately above — code review
     * (2026-10-15) Patch: an earlier version of this Javadoc wrongly claimed that key is
     * "deliberately NOT in {@code HAS_CODE_DEFAULT}"; it is, two lines below in that set. 30 days is
     * deliberately shorter than the unrelated 90-day {@code coachAccessExpiryDays} above — "flag as
     * dormant" is an earlier, softer signal than "revoke timeline access".
     */
    public static final BoundedKey DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS =
        new BoundedKey("development.roster.dormantAfterDays", 1L, 3650L, false,
            "0/neg/absurd → every roster player reads as dormant, or none ever do", 30L);

    /**
     * {@code CoachRosterService} — skillars-deferred-153 AC3: caps the roster endpoint's candidate
     * player set BEFORE the per-player fan-out runs (not response-only pagination after scoring).
     * Registered in {@link #HAS_CODE_DEFAULT}, read via the 4-arg {@code getBoundedLong}, same shape
     * as the adjacent {@link #DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS}. Default 150: margin above the
     * ~100-player revisit trigger this story's own ledger citation used.
     */
    public static final BoundedKey DEVELOPMENT_ROSTER_MAX_PLAYERS =
        new BoundedKey("development.roster.maxPlayers", 1L, 10000L, false,
            "0/neg/absurd → the roster endpoint returns an empty or unreasonably tiny candidate set", 150L);

    /** {@code DevelopmentCorrelationService} — neg → every session qualifies (0 = legitimate). */
    public static final BoundedKey DEVELOPMENT_CORRELATION_MIN_SESSION_COUNT =
        new BoundedKey("development.correlation.minSessionCount", 0L, 10000L, false,
            "neg → correlation gate never blocks; huge → correlation never runs", 0L);

    /** {@code NeglectedSkillDetectionService} — neg → predicate inverts (0 = legitimate). */
    public static final BoundedKey DEVELOPMENT_NEGLECTED_SKILL_WARMUP_SESSION_COUNT =
        new BoundedKey("development.neglectedSkill.warmupSessionCount", 0L, 10000L, false,
            "neg → neglected-skill warmup predicate inverts", 0L);

    /** {@code SubscriptionService} — neg → grace math breaks (0 = "no grace" is legitimate). */
    public static final BoundedKey SUBSCRIPTION_PAST_DUE_GRACE_PERIOD_DAYS =
        new BoundedKey("subscription.pastDue.gracePeriodDays", 0L, 365L, false,
            "neg → PAST_DUE grace cutoff moves into the future, downgrading nobody / everybody", 0L);

    /** {@code GdprExportService} — 0 → a legally-required export link is dead on arrival. */
    public static final BoundedKey GDPR_EXPORT_URL_EXPIRY_HOURS =
        new BoundedKey("gdpr.export.urlExpiryHours", 1L, 720L, true,
            "0 → GDPR export download link is expired on issue; huge → compliance exposure", 48L);

    // ── Int retry / batch / window counts (getBoundedInt + (int)getBoundedLong call sites) ───────

    /** {@code MessageRetentionScheduler} — 0/neg → the retention query deletes every message. */
    public static final BoundedKey MESSAGE_RETENTION_MONTHS =
        new BoundedKey("platform.message_retention_months", 1L, 600L, true,
            "0/neg → retention deletes EVERY message on the next run (data-destructive)", 24L);

    /** {@code BookingBatchService} — 0/neg → every batch booking rejected; huge → unbounded batch. */
    public static final BoundedKey BOOKING_BATCH_MAX_SIZE =
        new BoundedKey("booking.batch.maxSize", 1L, 100L, true,
            "0/neg → every batch booking rejected as booking.batchSizeExceeded", 0L);

    /**
     * skillars-deferred-149 AC5: the review-eligibility cross-field invariant —
     * {@code reviews.updateCooldownDays - reviews.minSessionAgeDays} must be at least this many days.
     * The single definition read by both guards that enforce it ({@code ConfigService}'s PUT-time
     * check and {@code ConfigStartupAssertion}'s boot check), which previously each declared their
     * own copy "kept in sync" by comment.
     */
    public static final long MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS = 7L;

    /** Upper bound of {@link #REVIEWS_UPDATE_COOLDOWN_DAYS}; also derives the other key's max. */
    private static final long REVIEWS_UPDATE_COOLDOWN_MAX_DAYS = 365L;

    /**
     * {@code ReviewSubmissionService} — direction of harm is inverted from the key this replaces
     * (REVIEWS_SUBMISSION_WINDOW_DAYS, an upper-bound window whose bad value made submission
     * IMPOSSIBLE). This is a lower-bound floor: an out-of-range value (0 or neg) falls back to the
     * coded default (7) via the 4-arg getBoundedInt — it is caught and corrected, never silently
     * honored as zero. min=1 is deliberate so that a stored 0 IS flagged as out-of-range (see Dev
     * Notes, F6) instead of being read as a legitimate "no cooling-off period" value.
     */
    // skillars-deferred-149 AC3 code review (2026-10-08): max narrowed from 365 to 358
    // (REVIEWS_UPDATE_COOLDOWN_MAX_DAYS - MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS). updateCooldownDays
    // caps at 365, so any minSessionAgeDays above 358 can never satisfy updateCooldownDays -
    // minSessionAgeDays >= 7 regardless of the partner's value -- rejectOutOfRange must catch it
    // here, with a message naming THIS key, rather than letting the cross-field guard reject it later
    // with a confusing error pointing at updateCooldownDays, a key the operator never touched.
    public static final BoundedKey REVIEWS_MIN_SESSION_AGE_DAYS =
        new BoundedKey("reviews.minSessionAgeDays", 1L,
            REVIEWS_UPDATE_COOLDOWN_MAX_DAYS - MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS, false,
            "out-of-range (incl. 0, or above "
                + (REVIEWS_UPDATE_COOLDOWN_MAX_DAYS - MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS)
                + " -- unreachable under the minimum-" + MIN_REVIEW_ELIGIBILITY_WINDOW_GAP_DAYS
                + "-day cross-field gap since updateCooldownDays caps at " + REVIEWS_UPDATE_COOLDOWN_MAX_DAYS
                + ") falls back to the coded default (7)", 7L);

    /** {@code ReviewSubmissionService} — same reasoning as REVIEWS_MIN_SESSION_AGE_DAYS above. */
    public static final BoundedKey REVIEWS_UPDATE_COOLDOWN_DAYS =
        new BoundedKey("reviews.updateCooldownDays", 1L, REVIEWS_UPDATE_COOLDOWN_MAX_DAYS, false,
            "out-of-range (incl. 0) falls back to the coded default (30), never to an unbounded cooldown", 30L);

    /** {@code ReviewFlagService} — 0 → every review auto-held. */
    public static final BoundedKey REVIEWS_AUTO_HOLD_FLAG_THRESHOLD =
        new BoundedKey("reviews.autoHoldFlagThreshold", 1L, 1000L, false,
            "0 → the first flag on any review auto-holds it", 3L);

    /**
     * {@code ReviewModerationSweeper} — minutes a review may sit in PENDING before it is held for a
     * human. Too small would hold reviews whose Gemini verdict is merely slow; out-of-range falls back
     * to the coded default (30). Mirrors messaging's moderation_orphan_grace_minutes bounds.
     */
    public static final BoundedKey REVIEWS_MODERATION_ORPHAN_GRACE_MINUTES =
        new BoundedKey("reviews.moderationOrphanGraceMinutes", 5L, 1440L, false,
            "out-of-range falls back to the coded default (30); too small would hold slow-but-live verdicts", 30L);

    /** {@code VideoLifecycleScheduler} + {@code VideoSubscriptionLifecycleListener} — 0 → no progress. */
    public static final BoundedKey VIDEO_LIFECYCLE_BATCH_SIZE =
        new BoundedKey("platform.video.lifecycle.batch_size", 1L, 10000L, false,
            "0 → lifecycle scheduler makes no progress; huge → load spike", 100L);

    /**
     * {@code ModerationSlaMonitorService} — skillars-deferred-115 AC1: was a hardcoded literal (50)
     * at the call site, externalized to config. Same code-default/low-severity shape as
     * {@link #VIDEO_LIFECYCLE_BATCH_SIZE}, not registered until now.
     */
    public static final BoundedKey MODERATION_SLA_BATCH_SIZE =
        new BoundedKey("platform.moderation_sla_batch_size", 1L, 500L, false,
            "0 → SLA monitor makes no progress on stuck videos; huge → batch lock-contention spike", 50L);

    /** {@code VideoSubscriptionLifecycleListener} — 0/neg → outbox never drains or loop underflows. */
    public static final BoundedKey VIDEO_LIFECYCLE_OUTBOX_MAX_ATTEMPTS =
        new BoundedKey("platform.video.lifecycle.outbox_max_attempts", 1L, 100L, false,
            "0/neg → subscription-lifecycle outbox never drains", 0L);

    /** {@code VideoDeletionOutboxProcessor} — 0/neg → deletion outbox never drains / dead-letters everything. */
    public static final BoundedKey VIDEO_DELETION_MAX_ATTEMPTS =
        new BoundedKey("platform.video.deletion.max_attempts", 1L, 100L, false,
            "0/neg → Bunny.net deletion outbox dead-letters on the first attempt (or never)", 5L);

    /** {@code RadarCompositeDlqProcessor} — 0/neg → DLQ dead-letters on first attempt (or never). */
    public static final BoundedKey RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS =
        new BoundedKey("platform.development.radar_composite_dlq.max_attempts", 1L, 100L, false,
            "0/neg → radar-composite DLQ dead-letters on the first attempt (or never)", 5L);

    /**
     * {@code RateLimitingService} — skillars-deferred-117 AC4: how long an idle in-process rate-limit
     * bucket survives before the eviction sweep removes it. Every {@code Bucket4j} bucket fully
     * refills after its own configured {@code duration} elapses (the longest in use anywhere in this
     * codebase today is 60 minutes), so evicting an idle bucket and letting it be recreated fresh on
     * next use is behaviorally identical to keeping it around idle, as long as the TTL comfortably
     * exceeds any in-use duration — the 24h default does, with wide margin.
     */
    public static final BoundedKey RATE_LIMIT_BUCKET_TTL_HOURS =
        new BoundedKey("security.rate_limiting.bucket_ttl_hours", 1L, 8760L, false,
            "too low → an actively-cycled bucket could be evicted mid-window (harmless — a fresh "
                + "bucket behaves identically — but defeats the point of keeping it); too high → the "
                + "eviction sweep provides less protection against the unbounded-map growth it exists "
                + "to fix", 24L);

    /**
     * {@code RadarCompositeCalculationService.recalculateComposite} — skillars-deferred-126 AC2:
     * bounds the previously-unbounded {@code upsertComposite}/{@code insertBaselineIfAbsent} native
     * {@code INSERT ... ON CONFLICT} wait via a transaction-scoped ({@code SELECT set_config(
     * 'lock_timeout', ..., true)}) bound, the narrowest-blast-radius mechanism that actually applies
     * to a native DML statement's implicit row lock (unlike {@code @Lock}/{@code @QueryHints}, which
     * only affects JPA-level pessimistic-lock acquisition on a {@code SELECT}-shaped query, the
     * mechanism {@link #MODERATION_LOCK_TIMEOUT_MINUTES} mirrors).
     *
     * <p>Read via the 4-arg {@code getBoundedLong(key, default, min, max)} route and registered in
     * {@link #HAS_CODE_DEFAULT} — deliberately NOT the {@code failFast}/no-code-default shape
     * {@link #MODERATION_LOCK_TIMEOUT_MINUTES} uses, which would require a seeded Flyway value
     * (this story's own Dev Notes rule out a new migration) and would also widen {@code
     * RadarCompositeDlqProcessor}'s own documented {@code getBoundedLong}-failure residual onto the
     * live {@code AFTER_COMMIT} path, not just the DLQ-retry path.
     *
     * <p>The DEFAULT (5s, see the call site) is kept in the single-digit seconds so a genuinely stuck
     * upsert fails fast and becomes visible under normal operation, per this AC's own "fail fast, do
     * not retry-in-place" decision (the existing DLQ/{@code AFTER_COMMIT}-catch recovery on both
     * callers is the intended recovery path, not a second in-place retry mechanism here). The stored
     * value's ceiling ({@code max}) is deliberately wider than the default — 120s, comfortably below
     * both {@code MAX_RUN_DURATION} (8 minutes) and the AC1 stale-claim margin (5 minutes) — so an
     * operator can widen it for an unusually contended deployment without the boot-time clamp fighting
     * them; it is not itself the recommended operating value. The floor must stay ABOVE Postgres's own
     * {@code deadlock_timeout} (default 1s) so the deadlock detector gets a chance to fire before this
     * timeout would otherwise misreport a genuine deadlock ({@code 40P01}) as an ordinary lock timeout
     * ({@code 55P03}) — see {@code recalculateComposite}'s own Javadoc for the write-order deadlock
     * hazard this guards against ({@code GdprErasureService.deletePlayerDevelopmentData} still takes
     * the same two tables in the opposite order; skillars-deferred-127 AC1 closed the concrete
     * GDPR-erasure conflict by serializing the two paths upstream on a shared {@code player_profiles}
     * lock, but this bound's own purpose — keeping a per-statement lock wait above Postgres's
     * deadlock detector's own timeout for ANY conflicting writer on these two tables, not only that
     * now-closed one — is unchanged). {@code min = 2L}, not {@code 1L}: a stored {@code 1}
     * would sit AT, not above, Postgres's own ~1s {@code deadlock_timeout} default — coin-flip-close
     * enough that a genuine deadlock could still misreport as an ordinary lock timeout depending on
     * which fires first, which is exactly the invariant this paragraph states the floor exists to
     * preserve (/bmad-code-review fix, 2026-09-21).
     */
    public static final BoundedKey RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS =
        new BoundedKey("platform.radar_composite_lock_timeout_seconds", 2L, 120L, false,
            "too low (near Postgres's ~1s deadlock_timeout) → a genuine deadlock could misreport as "
                + "an ordinary lock timeout; too high → the upsert wait this AC bounds stops failing fast", 5L);

    /**
     * {@code GdprErasureService.deletePlayerDevelopmentData} — skillars-deferred-129 AC1: bounds
     * each individual bulk-delete/scan/enqueue statement inside the GDPR erasure inner transaction's
     * {@code player_profiles} lock hold via the same transaction-scoped {@code SELECT set_config(
     * 'lock_timeout', ..., true)} mechanism {@link #RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS} already
     * uses — mirrors that key's bounds exactly ({@code min = 2L}, {@code max = 120L}) and the same
     * reasoning: the floor must stay ABOVE Postgres's own {@code deadlock_timeout} (default ~1s) so
     * the deadlock detector gets a chance to fire before this timeout would otherwise misreport a
     * genuine deadlock as an ordinary lock timeout.
     *
     * <p><strong>This bounds each STATEMENT, not the method's total wait</strong> (Postgres {@code
     * lock_timeout} is per-statement, not per-transaction — the same trap {@link
     * #RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS}'s own Javadoc already documents for its own method).
     * {@code deletePlayerDevelopmentData} issues this bound once, before its ~12 independently-
     * timeout-able statements, so the method's real worst case is {@code N ×} this value, not a
     * method-level ceiling — see that method's own Javadoc for the honest accounting. Read via the
     * 4-arg {@code getBoundedLong} route and registered in {@link #HAS_CODE_DEFAULT}, exactly like
     * {@link #RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS}'s own call site — a new migration is out of scope
     * for this story.
     */
    public static final BoundedKey GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS =
        new BoundedKey("platform.gdpr_erase_statement_lock_timeout_seconds", 2L, 120L, false,
            "too low (near Postgres's ~1s deadlock_timeout) → a genuine deadlock could misreport as "
                + "an ordinary lock timeout; too high → a blocked erasure statement stops failing fast, "
                + "extending how long the player_profiles lock is held", 5L);

    // ── Templated per-enum key segments — DELIBERATELY hand-listed ──────────────────────────────
    // skillars-deferred-108 AC9 (owner decision 2026-09-10, was deferred-107 code review): these
    // segments are hand-listed on purpose. Deriving them by iterating CoachSubscriptionTier
    // (marketplace.contract) / VideoType (video.contract) would give this `config` module a compile
    // dependency on two business modules it otherwise never touches. ConfigBoundsEnumCoverageTest
    // is the drift guard: it iterates enum.values() and fails the build when a NEW enum constant
    // lands (or is renamed) without a matching bound here.
    //
    // What the guard does NOT catch (both harmless / handled elsewhere):
    //   - a hand-list that is INCOMPLETE — a key seeded in the DB whose segment is simply not in
    //     the list below is unbounded, not flagged (this is exactly how video.quota.semiPro.* /
    //     .pro.* — seeded live by V53 — went unchecked until skillars-deferred-109 AC10 added them);
    //   - a REMOVED enum constant leaving a stale segment below — a bound that nothing reads.
    // There is no third enum in the runtime key derivation. `semiPro` / `pro` correspond to
    // PlayerSubscriptionTierBilling values, but QuotaConfigService.resolveTierKey does not (yet)
    // map any player to them — it falls through to "athlete" for every player. That entitlement gap
    // is filed as its own ledger bullet (skillars-deferred-109 AC10.6); the bounds below are
    // deliberately one release ahead of that mapping.
    //
    // Segments mirror QuotaConfigService.resolveTierKey (a hand-written exhaustive switch over
    // CoachSubscriptionTier, plus the "athlete" player fallback) and VideoTypeConstraints.configKey
    // (likewise a hand-written switch over VideoType), plus the two seeded-but-unmapped player
    // tiers. skillars-deferred-109 code review: NEITHER runtime derivation is mechanical — both are
    // literal switches — so ConfigBoundsEnumCoverageTest mirrors them with exhaustive switches of
    // its own rather than deriving segments from enum.name(). Adding an enum constant therefore
    // breaks compilation in that test, which forces whoever adds it to copy the literal the runtime
    // actually uses. Do not re-introduce a mechanical name-transform here or there: the previous
    // camelCase helper assumed a convention the runtime does not enforce, which would have let a
    // multi-word constant ship a bound nothing reads beside a key nothing bounds.

    static final List<String> VIDEO_QUOTA_TIER_SEGMENTS =
        List.of("scout", "instructor", "academy", "athlete", "semiPro", "pro");
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
        MODERATION_SLA_BATCH_SIZE.key(),
        VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES.key(),
        VIDEO_ACCESS_COACH_WINDOW_DAYS.key(),
        VIDEO_DELETION_MAX_ATTEMPTS.key(),
        RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS.key(),
        GDPR_EXPORT_URL_EXPIRY_HOURS.key(),
        MESSAGE_RETENTION_MONTHS.key(),
        REVIEWS_MIN_SESSION_AGE_DAYS.key(),
        REVIEWS_UPDATE_COOLDOWN_DAYS.key(),
        REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key(),
        REVIEWS_MODERATION_ORPHAN_GRACE_MINUTES.key(),
        TIMELINE_COACH_ACCESS_EXPIRY_DAYS.key(),
        DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.key(),
        RATE_LIMIT_BUCKET_TTL_HOURS.key(),
        RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS.key(),
        GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(),
        BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS.key(),
        DEVELOPMENT_ROSTER_MAX_PLAYERS.key(),
        BOOKING_RECAP_EDIT_WINDOW_DAYS.key(),
        BOOKING_LATE_CANCEL_GRACE_MINUTES.key(),
        COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW.key(),
        COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS.key(),
        BOOKING_REQUEST_MAX_PENDING_PER_COACH.key(),
        BOOKING_REQUEST_MAX_HORIZON_DAYS.key(),
        BOOKING_PLAYER_NO_SHOW_GRACE_MINUTES.key(),
        BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES.key(),
        BOOKING_LIVE_MIN_DURATION_PERCENT.key(),
        DISPUTES_MAX_PER_BOOKING_PER_RAISER.key(),
        PACK_EXPIRY_DAYS_TIER1.key(),
        PACK_EXPIRY_DAYS_TIER2.key(),
        PACK_EXPIRY_DAYS_TIER3.key(),
        PACK_EXPIRY_DAYS_TIER4.key(),
        PAYOUT_NEW_COACH_RELEASED_COUNT.key(),
        PAYOUT_NEW_COACH_MIN_RELEASED_NET.key(),
        PAYOUT_NEW_COACH_HOLD_HOURS.key());

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
            DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS,
            DEVELOPMENT_CORRELATION_MIN_SESSION_COUNT,
            DEVELOPMENT_NEGLECTED_SKILL_WARMUP_SESSION_COUNT,
            SUBSCRIPTION_PAST_DUE_GRACE_PERIOD_DAYS,
            GDPR_EXPORT_URL_EXPIRY_HOURS,
            MESSAGE_RETENTION_MONTHS,
            BOOKING_BATCH_MAX_SIZE,
            REVIEWS_MIN_SESSION_AGE_DAYS,
            REVIEWS_UPDATE_COOLDOWN_DAYS,
            REVIEWS_AUTO_HOLD_FLAG_THRESHOLD,
            REVIEWS_MODERATION_ORPHAN_GRACE_MINUTES,
            VIDEO_LIFECYCLE_BATCH_SIZE,
            MODERATION_SLA_BATCH_SIZE,
            VIDEO_LIFECYCLE_OUTBOX_MAX_ATTEMPTS,
            VIDEO_DELETION_MAX_ATTEMPTS,
            RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS,
            RATE_LIMIT_BUCKET_TTL_HOURS,
            RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS,
            GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS,
            BOOKING_STUCK_COMPLETION_TIMEOUT_HOURS,
            DEVELOPMENT_ROSTER_MAX_PLAYERS,
            BOOKING_RECAP_EDIT_WINDOW_DAYS,
            BOOKING_LATE_CANCEL_GRACE_MINUTES,
            COACH_EXCUSED_CANCELLATIONS_MAX_PER_WINDOW,
            COACH_EXCUSED_CANCELLATIONS_WINDOW_DAYS,
            BOOKING_REQUEST_MAX_PENDING_PER_COACH,
            BOOKING_REQUEST_MAX_HORIZON_DAYS,
            BOOKING_PLAYER_NO_SHOW_GRACE_MINUTES,
            BOOKING_UNRESOLVED_SESSION_GRACE_MINUTES,
            BOOKING_LIVE_MIN_DURATION_PERCENT,
            DISPUTES_MAX_PER_BOOKING_PER_RAISER,
            PACK_EXPIRY_DAYS_TIER1,
            PACK_EXPIRY_DAYS_TIER2,
            PACK_EXPIRY_DAYS_TIER3,
            PACK_EXPIRY_DAYS_TIER4,
            PAYOUT_NEW_COACH_RELEASED_COUNT,
            PAYOUT_NEW_COACH_MIN_RELEASED_NET,
            PAYOUT_NEW_COACH_HOLD_HOURS));

        for (String tier : VIDEO_QUOTA_TIER_SEGMENTS) {
            // Scout is seeded storageBytes = 0 deliberately ("0 = no upload", V53), so the floor is
            // 0, not 1 — a negative quota is the only genuinely broken state. Max Long.MAX_VALUE:
            // only the lower bound is a real risk here.
            all.add(new BoundedKey("video.quota." + tier + ".storageBytes", 0L, Long.MAX_VALUE, false,
                "neg → storage-quota math breaks; 0 is a legitimate \"no upload\" sentinel", 0L));
            all.add(new BoundedKey("video.quota." + tier + ".bandwidthBytesMonthly", 0L, Long.MAX_VALUE, false,
                "neg → bandwidth-quota math breaks; 0 is a legitimate \"no streaming\" sentinel", 0L));
        }
        for (String seg : VIDEO_TYPE_SEGMENTS) {
            all.add(new BoundedKey("video." + seg + ".maxSizeBytes", 1L, Long.MAX_VALUE, false,
                "0 → every upload of this type rejected", 0L));
            all.add(new BoundedKey("video." + seg + ".maxDurationSeconds", 1L, 86400L, false,
                "0 → every upload of this type rejected; huge → no effective cap", 0L));
        }
        ALL = List.copyOf(all);
    }
}
