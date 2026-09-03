package com.softropic.skillars.platform.notification.contract;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
    SEND_OTP("email.otp.title"),
    COACH_EMAIL_VERIFY("email.coach.verify.title"),
    COACH_OTP("email.coach.otp.title"),
    PARENT_EMAIL_VERIFY("email.parent.verify.title"),
    PARENT_OTP("email.parent.otp.title"),
    PLAYER_EMAIL_VERIFY("email.player.verify.title"),
    PLAYER_OTP("email.player.otp.title"),
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
    EmailTemplate(final String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String subjectKey() {
        return subjectKey;
    }
}
