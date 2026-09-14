package com.softropic.skillars.platform.notification.contract;

import java.time.Duration;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
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
    COACH_NO_SHOW("email.booking.coach_no_show.title"),
    PLAYER_NO_SHOW("email.booking.player_no_show.title"),
    COACH_VISIBILITY_REDUCED("email.reliability.visibility_reduced.title");

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
     * {@code ReportGenerationService}), and the login-2FA {@code SEND_OTP} flow
     * ({@code TwoFactorLoginService}) has its own independent hardcoded 10-minute deadline that this
     * enum value's own {@code deliveryDeadline()} advertises but that flow never reads.
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
