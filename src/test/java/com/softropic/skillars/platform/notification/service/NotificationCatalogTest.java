package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification centre (UI-gap chunk C9): pins the opt-out classification. Changing which emails a
 * user may turn off is a product/legal decision — this test makes it a deliberate, reviewed edit.
 */
class NotificationCatalogTest {

    @Test
    void exactlyTheseEmailsAreOptOutable() {
        Set<EmailTemplate> optOutable = Arrays.stream(EmailTemplate.values())
            .filter(t -> NotificationCatalog.of(t).category().optOutable())
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(EmailTemplate.class)));

        assertThat(optOutable).containsExactlyInAnyOrder(
            EmailTemplate.BOOKING_REMINDER,
            EmailTemplate.SESSION_PACK_EXPIRY_WARNING,
            EmailTemplate.PERFORMANCE_REPORT_SHARED);
    }

    /** S-07: the coach's "press Start" reminder is safety-relevant — transactional (never opt-out-able), with an inbox row. */
    @Test
    void coachStartReminder_isTransactionalBookingCategory_withACoachInboxRow() {
        NotificationCatalog.Entry entry = NotificationCatalog.of(EmailTemplate.COACH_START_REMINDER);
        assertThat(entry.category()).isEqualTo(NotificationCategory.BOOKING);
        assertThat(entry.category().optOutable()).isFalse();
        assertThat(entry.inApp()).isTrue();
        assertThat(entry.deepLinkFor(true)).isEqualTo("/coach/booking-requests");
    }

    @ParameterizedTest
    @EnumSource(value = EmailTemplate.class, names = {
        "ACTIVATION", "CREATION_DUP", "PASSWORD_RESET", "SEND_OTP", "COACH_EMAIL_VERIFY", "COACH_OTP",
        "PARENT_EMAIL_VERIFY", "PARENT_OTP", "PLAYER_EMAIL_VERIFY", "PLAYER_OTP", "EMAIL_CHANGE", "PROFILE_CHANGE"})
    void accountAndSecurityEmails_areTransactionalAndNeverInApp(EmailTemplate template) {
        NotificationCatalog.Entry entry = NotificationCatalog.of(template);
        assertThat(entry.category()).isEqualTo(NotificationCategory.ACCOUNT);
        assertThat(entry.category().optOutable()).isFalse();
        // OTPs / verification links must never be copied into a persisted inbox row.
        assertThat(entry.inApp()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EmailTemplate.class, names = {"NONE", "SES_CUTOVER_PREFLIGHT", "VIDEO_MODERATION_ADMIN_ALERT"})
    void operatorEmails_areSystemAndNeverInApp(EmailTemplate template) {
        assertThat(NotificationCatalog.of(template).category()).isEqualTo(NotificationCategory.SYSTEM);
        assertThat(NotificationCatalog.of(template).inApp()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EmailTemplate.class)
    void everyInAppEntry_hasAnInternalHashRouterDeepLink(EmailTemplate template) {
        NotificationCatalog.Entry entry = NotificationCatalog.of(template);
        if (!entry.inApp()) {
            return;
        }
        for (boolean coach : new boolean[] {true, false}) {
            assertThat(entry.deepLinkFor(coach)).startsWith("/").doesNotStartWith("//").doesNotContain("#");
        }
    }

    @Test
    void paramWhitelist_neverContainsUrlsTokensOrFreeText() {
        assertThat(NotificationCatalog.PARAM_WHITELIST)
            .noneMatch(k -> k.toLowerCase().contains("url") || k.toLowerCase().contains("token")
                || k.toLowerCase().contains("otp") || k.equals("notes") || k.equals("message")
                || k.equals("body"));
    }
}
