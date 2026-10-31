package com.softropic.skillars.platform.notification.contract;

import java.time.Duration;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
    // Retired (chore/auth-legacy-removal): its only producer, the legacy POST /authenticate -> /otp
    // login-2FA flow (TwoFactorLoginService), is gone. Kept so historical envelope_entity rows and
    // any envelope still queued for retry at deploy time keep resolving (envelope_entity's CHECK
    // constraint lists it too); nothing new is ever written with it. Remove in a later release.
    SEND_OTP("email.otp.title"),
    // Story ses-1.4 AC3/AC5, deadlines corrected by code review 2026-09-12:
    // - COACH_EMAIL_VERIFY/PARENT_EMAIL_VERIFY/PLAYER_EMAIL_VERIFY: 12h, half of the verification
    //   token's own 24h TTL (CoachRegistrationService.java:250 and its Parent/Player equivalents) —
    //   the original 24h deadline equaled the token's TTL exactly, so a verify mail delivered near
    //   its deadline carried a token that expired seconds later.
    // - COACH_OTP/PARENT_OTP/PLAYER_OTP: 8 min, not 5. `app.outbox.sweep-ms` defaults to 300000 (5
    //   min, OutboxService.java) and is the documented safety net when the inline AFTER_COMMIT drain
    //   is discarded (OutboxConfig's ThreadPoolExecutor.DiscardPolicy) — a 5-minute deadline could
    //   equal or precede the sweep that was supposed to recover the send, so
    //   NotificationEmailOutboxHandler's first-attempt guard would discard an OTP the sweep never
    //   got a chance to attempt. 8 minutes clears the sweep interval and still leaves >=2 min of the
    //   10-minute OTP TTL in the worst case.
    // All six also get an isolated circuit breaker name — a signup spike with bad addresses must not
    // open the breaker that gates booking confirmations (D-8).
    COACH_EMAIL_VERIFY("email.coach.verify.title", Duration.ofHours(12), "registrationEmailService"),
    COACH_OTP("email.coach.otp.title", Duration.ofMinutes(8), "registrationEmailService"),
    PARENT_EMAIL_VERIFY("email.parent.verify.title", Duration.ofHours(12), "registrationEmailService"),
    PARENT_OTP("email.parent.otp.title", Duration.ofMinutes(8), "registrationEmailService"),
    PLAYER_EMAIL_VERIFY("email.player.verify.title", Duration.ofHours(12), "registrationEmailService"),
    PLAYER_OTP("email.player.otp.title", Duration.ofMinutes(8), "registrationEmailService"),
    EMAIL_CHANGE("email.change.title"),
    PROFILE_CHANGE("email.profile_change.title"),
    BOOKING_REQUESTED("email.booking.requested.title"),
    BOOKING_CONFIRMED("email.booking.confirmed.title"),
    BOOKING_DECLINED("email.booking.declined.title"),
    /**
     * The booking was abandoned with the payment outcome UNKNOWN (CAPTURE_ABANDONED). Distinct from
     * BOOKING_DECLINED, which tells the parent their credits were not affected — a statement the
     * platform cannot make here. skillars-deferred-91 code review, decision D10.
     */
    BOOKING_PAYMENT_UNRESOLVED("email.booking.payment_unresolved.title"),
    BOOKING_EXPIRED("email.booking.expired.title"),
    BOOKING_REMINDER("email.booking.reminder.title"),
    BOOKING_QUICK_COMPLETE_CONFIRM("email.booking.quick_complete_confirm.title"),
    BOOKING_RESCHEDULE_REQUESTED("email.booking.reschedule_requested.title"),
    BOOKING_RESCHEDULE_ACCEPTED("email.booking.reschedule_accepted.title"),
    BOOKING_RESCHEDULE_DECLINED("email.booking.reschedule_declined.title"),
    BOOKING_RESCHEDULE_REQUESTED_BY_COACH("email.booking.reschedule_requested_by_coach.title"),
    BOOKING_RESCHEDULE_DECLINED_BY_PARENT("email.booking.reschedule_declined_by_parent.title"),
    BOOKING_DUPLICATE_PROPOSED("email.booking.duplicate_proposed.title"),
    BOOKING_BATCH_REQUESTED("email.booking.batch_requested.title"),
    BOOKING_BATCH_ACCEPTED("email.booking.batch_accepted.title"),
    SESSION_PACK_EXPIRY_WARNING("email.session_pack.expiry_warning.title"),
    SESSION_PACK_EXPIRED("email.session_pack.expired.title"),
    BOOKING_CANCELLED_DUE_TO_PAUSE("email.booking.cancelled_due_to_pause.title"),
    PACK_PAUSED("email.session_pack.paused.title"),
    PERFORMANCE_REPORT_SHARED("email.report.shared.title"),
    VIDEO_MODERATION_ADMIN_ALERT("email.video.moderation.admin_alert.title"),
    VIDEO_MODERATION_OWNER_FLAGGED("email.video.moderation.owner_flagged.title"),
    BOOKING_CANCELLED_BY_PARENT("email.booking.cancelled_by_parent.title"),
    BOOKING_CANCELLED_BY_COACH("email.booking.cancelled_by_coach.title"),
    /**
     * C2 (V185): the payer's booking was cancelled by a coach suspension / child-safety hold. Neutral by
     * design — "the coach is unavailable", never why — and states the refund (pack session restored,
     * credit refunded, or nothing was charged).
     */
    BOOKING_CANCELLED_COACH_UNAVAILABLE("email.booking.cancelled_coach_unavailable.title"),
    /**
     * C6 (V189): the coach's notice that an admin cancelled one of their bookings from the admin bookings
     * page (cancel reason ADMIN_SUPPORT). Not sent for a suspension sweep — the coach is told by
     * COACH_SUSPENDED there.
     */
    BOOKING_CANCELLED_BY_SKILLARS("email.booking.cancelled_by_skillars.title"),
    COACH_NO_SHOW("email.booking.coach_no_show.title"),
    PLAYER_NO_SHOW("email.booking.player_no_show.title"),
    /**
     * fix/forfeit-family-emails (V212): to the paying family (the booking's parent, or the adult player who
     * booked for themselves) when the coach reports a player no-show — the session is forfeited, no refund is
     * due, and how to dispute the report. {@link #PLAYER_NO_SHOW} is the coach's own confirmation.
     */
    SESSION_FORFEITED_NO_SHOW("email.booking.session_forfeited_no_show.title"),
    /**
     * fix/forfeit-family-emails (V212): to the paying family when their own cancellation was too late for a
     * refund (a forfeit: captured and not refund-eligible). The family's only email for that cancellation —
     * a refunded or pre-capture parent cancellation sends the family nothing; the coach gets
     * {@link #BOOKING_CANCELLED_BY_PARENT} either way.
     */
    SESSION_FORFEITED_LATE_CANCEL("email.booking.session_forfeited_late_cancel.title"),
    /**
     * S-07 (V219): to the COACH when a session is still UPCOMING at its scheduled start (reminderType START) and again
     * at start + 10 minutes (LATE): press Start, or the family can claim a no-show and the session can be refunded.
     * Safety-relevant, so category BOOKING (never opt-out-able), with an in-app inbox row.
     */
    COACH_START_REMINDER("email.booking.coach_start_reminder.title"),
    COACH_VISIBILITY_REDUCED("email.reliability.visibility_reduced.title"),
    /**
     * S-09 (V223): to the coach ~30 days before their safeguarding check expires and the public badge reverts to
     * "no check on record". Sent once per record by the safeguarding expiry job; renewal resets it.
     */
    COACH_SAFEGUARDING_EXPIRING("email.coach.safeguarding_expiring.title"),
    /**
     * RETIRED — no producer since the parental-approval flow was removed (ADR "Minors never upload",
     * 2026-10-28). Kept ONLY so envelope / outbox / inbox rows already stored with these values still
     * load (Hibernate {@code EnumType.STRING}) and still satisfy {@code envelope_entity_email_template_check},
     * which keeps them too (V184 header). Their Thymeleaf templates are deleted, so a stale retry of one
     * fails to render and is marked FAILED rather than sending an obsolete "approve this video" call to
     * action. Remove in a later release together with a CHECK rebuild once no stored row carries them.
     */
    @Deprecated
    VIDEO_APPROVAL_REQUESTED("email.video.approval.requested.title", Duration.ofDays(7), "emailService"),
    @Deprecated
    VIDEO_APPROVAL_APPROVED("email.video.approval.approved.title"),
    @Deprecated
    VIDEO_APPROVAL_REJECTED("email.video.approval.rejected.title"),
    /**
     * skillars-deferred-114 AC5: the SES cutover preflight endpoint's probe email — a one-line
     * "this is a test" body, not a user-facing template. Isolated circuit breaker name
     * ({@code "sesPreflightService"}, not the shared {@code "emailService"}): a preflight tool must
     * be able to report a failing probe as the point of testing an unverified config, and a failing
     * probe against the shared breaker would trip it for real production traffic during the exact
     * cutover window this tool exists to protect. Mirrors the six registration/OTP templates' own
     * isolated {@code "registrationEmailService"} breaker, above.
     */
    SES_CUTOVER_PREFLIGHT("email.ses.cutover_preflight.title", Duration.ofMinutes(10), "sesPreflightService"),
    // B6 (V183): admin-outcome notices, sent by AdminOutcomeEmailListener. All transactional (never
    // opt-out-able): enforcement, dispute, GDPR and moderation outcomes are things the user must hear.
    /** An admin suspended the coach's marketplace profile and chose to notify them. */
    COACH_SUSPENDED("email.admin.coach_suspended.title"),
    /** An admin lifted the coach's suspension (directly, or by overturning an appeal). */
    COACH_REINSTATED("email.admin.coach_reinstated.title"),
    /** A dispute against the coach was resolved with a formal warning. */
    COACH_WARNING_ISSUED("email.admin.coach_warning.title"),
    /** The raiser's dispute was resolved. */
    DISPUTE_RESOLVED("email.admin.dispute_resolved.title"),
    /** The raiser's dispute was dismissed. */
    DISPUTE_DISMISSED("email.admin.dispute_dismissed.title"),
    /**
     * feat/payment-forfeit-payouts-and-clawback (V198): a dispute was resolved in the coach's favour — to the
     * party that did not raise it (the payer whose refund was reversed, or the coach now paid).
     */
    DISPUTE_COACH_UPHELD("email.admin.dispute_coach_upheld.title"),
    /** The requester's GDPR data export is ready to download. */
    GDPR_EXPORT_READY("email.admin.gdpr_export_ready.title"),
    /** The author's review was approved and is now public. */
    REVIEW_APPROVED("email.admin.review_approved.title"),
    /** The author's review was blocked by a moderator. */
    REVIEW_BLOCKED("email.admin.review_blocked.title");

    private final String subjectKey;
    private final Duration deliveryDeadline;
    private final String circuitBreakerName;

    // Enum constants initialize before any static field on the same class, so a static constant
    // cannot be referenced from a constructor called during that initialization ("illegal forward
    // reference") — the defaults are inlined here instead.
    EmailTemplate(final String subjectKey) {
        this(subjectKey, Duration.ofDays(1), "emailService");
    }

    EmailTemplate(final String subjectKey, final Duration deliveryDeadline, final String circuitBreakerName) {
        this.subjectKey = subjectKey;
        this.deliveryDeadline = deliveryDeadline;
        this.circuitBreakerName = circuitBreakerName;
    }

    public String subjectKey() {
        return subjectKey;
    }

    /**
     * Story ses-1.4 AC3. Read by exactly one caller, {@code NotificationOutboxSupport.enqueueEmail}
     * — this is not a codebase-wide guarantee. Six producers build an {@code Envelope} directly with
     * their own caller-supplied deadline and never consult this accessor at all
     * ({@code AccountManagementFacade}, {@code EmailRegistrationStrategy}, {@code SendMailListener},
     * {@code AlertNotificationListener}, {@code VideoModerationEmailListener},
     * {@code ReportGenerationService}). {@code SEND_OTP} is retired and has no producer any more
     * (the legacy login-2FA flow that built it with its own hardcoded deadline was removed).
     */
    public Duration deliveryDeadline() {
        return deliveryDeadline;
    }

    /**
     * Story ses-1.4 AC5 (D-8): the id {@code MailManager.sendEmailSync} passes to
     * {@code CircuitBreakerFactory.create(...)}. Isolating registration/OTP mail onto its own breaker
     * means a signup spike of bad addresses cannot open the breaker that gates booking confirmations
     * and every other transactional email. {@code ComponentConfig.defaultCustomizer()} configures
     * every breaker id identically, so no config change is needed for a new id to get the same
     * resilience shape as {@code "emailService"}, tracked as an independent instance.
     */
    public String circuitBreakerName() {
        return circuitBreakerName;
    }
}
