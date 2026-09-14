package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Story ses-1.4 AC1: moved from a direct, {@code AFTER_COMMIT}, fire-and-forget
 * {@code OutboundEmailSender.send(...)} call onto the same {@code NotificationOutboxSupport} →
 * {@code NotificationEmailOutboxHandler} → {@code MailManager} durable outbox pipeline that
 * {@code BookingEmailListener} already uses — mirrored here exactly, including its blank-address
 * guard. Rendering (Thymeleaf template + subject lookup) no longer happens in this listener at all;
 * it happens once, in {@code EmailContentRenderer}, when the outbox drains.
 *
 * <p><strong>AC1's new failure mode has two different outcomes, corrected per code review
 * 2026-09-12 (this javadoc previously stated only the first):</strong>
 * <ul>
 *   <li>A failed outbox {@code INSERT} (a DB error) marks the producing transaction (registration or
 *       resend-OTP) rollback-only, so {@code POST /register}/{@code POST /resend-otp} now fails with
 *       a 500 on an email-infrastructure problem, where before the account still committed and only
 *       the email was silently lost. {@code ApiAdvice}'s catch-all {@code Throwable} handler already
 *       maps the resulting {@code UnexpectedRollbackException} to a 500 with a logged help code —
 *       verified, no change needed there.</li>
 *   <li>A serialisation failure inside {@code enqueueEmail} (an {@code IllegalStateException} —
 *       see {@code NotificationOutboxSupport}'s "Failure semantics") is a different, deliberately
 *       <em>non</em>-atomic case: this listener's {@code catch (Exception)} swallows it, so
 *       registration/resend-OTP still commits with no outbox row and no email. That is the intended
 *       split documented on {@code NotificationOutboxSupport} — a malformed notification payload must
 *       not roll back the account it merely describes — not a regression of the silent-loss mode AC1
 *       otherwise removes.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachRegistrationEmailListener {

    private final NotificationOutboxSupport notificationOutboxSupport;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVerificationEmail(CoachVerificationEmailEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send coach verification email: address is blank");
            return;
        }
        String sendId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("verifyUrl", event.verifyUrl());

            Recipient recipient = new Recipient();
            recipient.setEmail(event.toAddress());
            recipient.setLangKey(event.langKey());
            recipient.setFirstname(event.firstName());

            notificationOutboxSupport.enqueueEmail(EmailTemplate.COACH_EMAIL_VERIFY, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.COACH_EMAIL_VERIFY),
                kv("sendId", sendId), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOtpEmail(CoachOtpEmailEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send coach OTP email: address is blank");
            return;
        }
        String sendId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("otpCode", event.otp());

            Recipient recipient = new Recipient();
            recipient.setEmail(event.toAddress());
            recipient.setLangKey(event.langKey());
            recipient.setFirstname(event.firstName());

            notificationOutboxSupport.enqueueEmail(EmailTemplate.COACH_OTP, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.COACH_OTP),
                kv("sendId", sendId), e);
        }
    }
}
