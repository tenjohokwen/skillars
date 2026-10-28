package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;

import java.util.Set;

/**
 * The single classification of every {@link EmailTemplate} for the notification centre (UI-gap
 * chunk C9): its {@link NotificationCategory} (which decides email opt-out — see that enum's
 * Javadoc for the transactional-vs-opt-out-able rationale), whether it also produces an in-app
 * inbox row, and the frontend route that row deep-links to.
 *
 * <p>The {@code switch} is exhaustive with no {@code default}, so adding an {@link EmailTemplate}
 * without classifying it here is a compile error, not a silently-unclassified email.
 *
 * <p>Deep links are hash-router paths <em>without</em> the {@code #} (the frontend
 * {@code router.push}es them). Templates whose recipient may be either a coach or a parent/player
 * carry both; {@link Entry#deepLinkFor(boolean)} picks by the recipient's coach role.
 */
public final class NotificationCatalog {

    private static final String COACH_BOOKINGS = "/coach/booking-requests";
    private static final String PARENT_BOOKINGS = "/parent/bookings";
    private static final String DASHBOARD = "/dashboard";

    /**
     * Email {@code data} keys that may be copied into an inbox row's {@code params}. A whitelist, not
     * a blacklist: email data carries verification links, OTP codes, free-text notes and moderation
     * messages that must never be persisted outside the email pipeline.
     */
    public static final Set<String> PARAM_WHITELIST = Set.of(
        "coachDisplayName", "coachName", "playerName", "parentName",
        "requestedStartTime", "originalStartTime", "proposedStartTime", "newStartTime",
        "canonicalTimezone", "creditsRemaining", "expiresAt", "newExpiresAt",
        "requestedCount", "acceptedCount", "reportDate", "reminderType", "videoType");

    private NotificationCatalog() {
    }

    /**
     * @param category    the user-facing category (decides opt-out)
     * @param inApp       whether the email also produces an in-app inbox row
     * @param coachLink   deep link when the recipient is a coach (null = use {@code otherLink})
     * @param otherLink   deep link for any other recipient
     * @param linkDataKey optional email-data key holding an absolute frontend URL to derive the deep
     *                    link from instead (e.g. a report URL that embeds a player id)
     */
    public record Entry(NotificationCategory category, boolean inApp, String coachLink, String otherLink,
                        String linkDataKey) {

        public String deepLinkFor(final boolean recipientIsCoach) {
            return recipientIsCoach && coachLink != null ? coachLink : otherLink;
        }
    }

    private static Entry inApp(final NotificationCategory category, final String coachLink, final String otherLink) {
        return new Entry(category, true, coachLink, otherLink, null);
    }

    private static Entry emailOnly(final NotificationCategory category) {
        return new Entry(category, false, null, null, null);
    }

    public static Entry of(final EmailTemplate template) {
        return switch (template) {
            // ── Account / security: transactional, email only (the user may not even be logged in) ──
            case NONE, SES_CUTOVER_PREFLIGHT, VIDEO_MODERATION_ADMIN_ALERT -> emailOnly(NotificationCategory.SYSTEM);
            case ACTIVATION, CREATION_DUP, PASSWORD_RESET, SEND_OTP,
                 COACH_EMAIL_VERIFY, COACH_OTP, PARENT_EMAIL_VERIFY, PARENT_OTP,
                 PLAYER_EMAIL_VERIFY, PLAYER_OTP, EMAIL_CHANGE, PROFILE_CHANGE ->
                emailOnly(NotificationCategory.ACCOUNT);

            // ── Bookings: transactional, in-app ──
            // Recipient is always the coach.
            case BOOKING_REQUESTED, BOOKING_RESCHEDULE_REQUESTED, BOOKING_RESCHEDULE_DECLINED_BY_PARENT,
                 BOOKING_BATCH_REQUESTED, BOOKING_CANCELLED_DUE_TO_PAUSE, BOOKING_CANCELLED_BY_PARENT,
                 PLAYER_NO_SHOW ->
                inApp(NotificationCategory.BOOKING, COACH_BOOKINGS, COACH_BOOKINGS);
            // Recipient is always the parent (or a self-registered player booking for themselves).
            case BOOKING_CONFIRMED, BOOKING_DECLINED, BOOKING_PAYMENT_UNRESOLVED, BOOKING_EXPIRED,
                 BOOKING_QUICK_COMPLETE_CONFIRM, BOOKING_RESCHEDULE_DECLINED,
                 BOOKING_RESCHEDULE_REQUESTED_BY_COACH, BOOKING_DUPLICATE_PROPOSED, BOOKING_BATCH_ACCEPTED,
                 BOOKING_CANCELLED_BY_COACH, COACH_NO_SHOW ->
                inApp(NotificationCategory.BOOKING, null, PARENT_BOOKINGS);
            // Sent to both sides of the booking.
            case BOOKING_RESCHEDULE_ACCEPTED -> inApp(NotificationCategory.BOOKING, COACH_BOOKINGS, PARENT_BOOKINGS);

            // ── Reminders: opt-out-able ──
            case BOOKING_REMINDER -> inApp(NotificationCategory.REMINDER, COACH_BOOKINGS, PARENT_BOOKINGS);
            case SESSION_PACK_EXPIRY_WARNING -> inApp(NotificationCategory.REMINDER, null, DASHBOARD);

            // ── Session packs: transactional (paid credit changed) ──
            case SESSION_PACK_EXPIRED, PACK_PAUSED -> inApp(NotificationCategory.SESSION_PACK, null, DASHBOARD);

            // ── Development: opt-out-able; deep link derived from the report URL (embeds the player id) ──
            case PERFORMANCE_REPORT_SHARED ->
                new Entry(NotificationCategory.DEVELOPMENT, true, null, DASHBOARD, "reportsPageUrl");

            // ── Moderation: safety notice about the user's own upload ──
            case VIDEO_MODERATION_OWNER_FLAGGED -> inApp(NotificationCategory.MODERATION, DASHBOARD, "/player/videos");
            // Minor-safety approval gate: the parent must act, so never opt-out-able (MODERATION).
            case VIDEO_APPROVAL_REQUESTED -> inApp(NotificationCategory.MODERATION, null, "/parent/approvals");
            // Recipient is the player whose video the parent decided on.
            case VIDEO_APPROVAL_APPROVED, VIDEO_APPROVAL_REJECTED ->
                inApp(NotificationCategory.MODERATION, null, "/player/videos");

            // No producer sends this template today; classified so it is never accidentally opt-out-able.
            case COACH_VISIBILITY_REDUCED -> inApp(NotificationCategory.BOOKING, "/coach/reliability", DASHBOARD);
        };
    }
}
