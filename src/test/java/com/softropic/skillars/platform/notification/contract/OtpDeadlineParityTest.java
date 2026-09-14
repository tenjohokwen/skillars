package com.softropic.skillars.platform.notification.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.4 AC3/AC6 (source doc #7.2 item 22).
 *
 * <p><strong>Scoped to outbox-routed templates only (S4).</strong> {@link EmailTemplate#SEND_OTP} is
 * the login-2FA flow — it is never passed to {@code NotificationOutboxSupport.enqueueEmail} and
 * carries its own independent, hardcoded 10-minute deadline at {@code TwoFactorLoginService.java:60},
 * entirely unrelated to this enum accessor. Asserting {@code SEND_OTP.deliveryDeadline() == 24h} here
 * would pin a value that accessor advertises but that flow never reads — misleading, not protective.
 *
 * <p><strong>Corrected per code review 2026-09-12:</strong> the first draft excluded only
 * {@code SEND_OTP} and otherwise iterated {@code EnumSet.allOf(EmailTemplate.class)}, which pinned
 * {@code deliveryDeadline() == 24h} for every direct-{@code Envelope} producer too (
 * {@code ACTIVATION}, {@code PASSWORD_RESET}, {@code EMAIL_CHANGE}, {@code PROFILE_CHANGE},
 * {@code CREATION_DUP}, {@code VIDEO_MODERATION_*}, {@code PERFORMANCE_REPORT_SHARED}, {@code NONE},
 * {@code COACH_VISIBILITY_REDUCED}) — exactly the category this javadoc's own S4 reasoning says must
 * not be pinned, since {@code enqueueEmail} never reads their deadline either. {@link #OUTBOX_ROUTED}
 * below is an explicit allow-list of every template actually reached via
 * {@code NotificationOutboxSupport.enqueueEmail} — {@code BookingEmailListener},
 * {@code SessionPackEmailListener}, and the three registration listeners this story added — so the
 * "keeps the 24h default" assertion only pins values {@code enqueueEmail} genuinely consults.
 */
@DisplayName("EmailTemplate.deliveryDeadline() parity for outbox-routed templates")
class OtpDeadlineParityTest {

    private static final Set<EmailTemplate> OTP_BEARING_OUTBOX_TEMPLATES =
        Set.of(EmailTemplate.COACH_OTP, EmailTemplate.PARENT_OTP, EmailTemplate.PLAYER_OTP);

    /**
     * Every {@code EmailTemplate} value actually reached via {@code NotificationOutboxSupport
     * .enqueueEmail} — verified by reading every {@code enqueueEmail(EmailTemplate.X, ...)} call site
     * in {@code BookingEmailListener}, {@code SessionPackEmailListener}, and the three registration
     * listeners. A template added to one of those listeners without being added here is a deliberate
     * gap this test will not catch — the point of an explicit allow-list is that growing it is a
     * decision, not silent drift (mirrors {@code IntegrationTestConventionTest}'s pinned-count
     * philosophy elsewhere in this suite).
     */
    private static final Set<EmailTemplate> OUTBOX_ROUTED = Set.of(
        // BookingEmailListener
        EmailTemplate.BOOKING_REQUESTED, EmailTemplate.BOOKING_CONFIRMED, EmailTemplate.BOOKING_DECLINED,
        EmailTemplate.BOOKING_PAYMENT_UNRESOLVED, EmailTemplate.BOOKING_EXPIRED, EmailTemplate.BOOKING_REMINDER,
        EmailTemplate.BOOKING_QUICK_COMPLETE_CONFIRM, EmailTemplate.BOOKING_RESCHEDULE_REQUESTED,
        EmailTemplate.BOOKING_RESCHEDULE_ACCEPTED, EmailTemplate.BOOKING_RESCHEDULE_DECLINED,
        EmailTemplate.BOOKING_RESCHEDULE_REQUESTED_BY_COACH, EmailTemplate.BOOKING_RESCHEDULE_DECLINED_BY_PARENT,
        EmailTemplate.BOOKING_DUPLICATE_PROPOSED, EmailTemplate.BOOKING_BATCH_REQUESTED,
        EmailTemplate.BOOKING_BATCH_ACCEPTED, EmailTemplate.BOOKING_CANCELLED_DUE_TO_PAUSE,
        EmailTemplate.BOOKING_CANCELLED_BY_PARENT, EmailTemplate.BOOKING_CANCELLED_BY_COACH,
        EmailTemplate.COACH_NO_SHOW, EmailTemplate.PLAYER_NO_SHOW,
        // SessionPackEmailListener
        EmailTemplate.SESSION_PACK_EXPIRY_WARNING, EmailTemplate.SESSION_PACK_EXPIRED, EmailTemplate.PACK_PAUSED,
        // Coach/Parent/PlayerRegistrationEmailListener (story ses-1.4)
        EmailTemplate.COACH_EMAIL_VERIFY, EmailTemplate.COACH_OTP,
        EmailTemplate.PARENT_EMAIL_VERIFY, EmailTemplate.PARENT_OTP,
        EmailTemplate.PLAYER_EMAIL_VERIFY, EmailTemplate.PLAYER_OTP);

    @Test
    @DisplayName("every OTP-bearing outbox-routed template's deadline is strictly less than the registration OTP's own 10-minute TTL")
    void otpBearingTemplates_haveDeadlineUnderTenMinutes() {
        for (EmailTemplate template : OTP_BEARING_OUTBOX_TEMPLATES) {
            assertThat(template.deliveryDeadline())
                .as("%s must leave the recipient meaningfully less than the full 10-minute OTP TTL "
                    + "to actually use the code", template)
                .isLessThan(Duration.ofMinutes(10));
        }
    }

    @Test
    @DisplayName("the OTP-bearing templates get exactly 8 minutes, clearing the 5-minute outbox sweep interval")
    void otpBearingTemplates_getExactlyEightMinutes() {
        // Code review 2026-09-12: the original 5-minute deadline equaled app.outbox.sweep-ms's
        // 300000 default — the documented safety net for a discarded inline drain — so a sweep-
        // recovered OTP could arrive at or after its own deadline and never be attempted at all.
        // 8 minutes clears the sweep and still leaves >=2 min of the 10-minute TTL in the worst case.
        for (EmailTemplate template : OTP_BEARING_OUTBOX_TEMPLATES) {
            assertThat(template.deliveryDeadline()).as(template.name()).isEqualTo(Duration.ofMinutes(8));
        }
    }

    @Test
    @DisplayName("the three *_EMAIL_VERIFY templates get exactly 12 hours, half the verification token's own 24h TTL")
    void verifyTemplates_getExactlyTwelveHours() {
        // Code review 2026-09-12: the original 24h deadline equaled the verification token's own
        // 24h TTL (CoachRegistrationService.java:250 and its Parent/Player equivalents) exactly, so
        // a verify mail delivered near its deadline could carry a token that expired seconds later.
        for (EmailTemplate template : Set.of(EmailTemplate.COACH_EMAIL_VERIFY, EmailTemplate.PARENT_EMAIL_VERIFY,
                EmailTemplate.PLAYER_EMAIL_VERIFY)) {
            assertThat(template.deliveryDeadline()).as(template.name()).isEqualTo(Duration.ofHours(12));
        }
    }

    @Test
    @DisplayName("every other outbox-routed template keeps the 24-hour default")
    void everyOtherOutboxRoutedTemplate_keepsTheDefaultDeadline() {
        for (EmailTemplate template : OUTBOX_ROUTED) {
            if (OTP_BEARING_OUTBOX_TEMPLATES.contains(template)
                    || template == EmailTemplate.COACH_EMAIL_VERIFY
                    || template == EmailTemplate.PARENT_EMAIL_VERIFY
                    || template == EmailTemplate.PLAYER_EMAIL_VERIFY) {
                continue;
            }
            assertThat(template.deliveryDeadline())
                .as("%s must keep the 24h default — only the six registration templates override it", template)
                .isEqualTo(Duration.ofDays(1));
        }
    }
}
