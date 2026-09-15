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
import java.util.Objects;
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
 *       see {@code NotificationOutboxSupport}'s "Failure semantics", corrected 2026-09-15 by code
 *       review H1) behaves <em>the same</em> as the outbox-{@code INSERT} case above, not
 *       differently: {@code enqueueEmail} is {@code Propagation.MANDATORY}, so it joins this
 *       listener's own transaction rather than opening its own, and Spring marks that shared
 *       transaction rollback-only as it unwinds from the throw — before this listener's {@code catch
 *       (Exception)} ever runs. The catch still logs the failure at ERROR and prevents the exception
 *       itself from propagating further, but it cannot undo the rollback-only flag Spring already
 *       set. Registration/resend-OTP rolls back with a 500 (surfaced as {@code
 *       UnexpectedRollbackException} at commit) either way — there is no non-atomic case here.</li>
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
        // skillars-deferred-111 AC6: restores the fail-fast guard the HashMap literal here (unlike
        // the Map.of(...) it replaced for ses-1.4) does not provide for free. Deliberately OUTSIDE
        // the try/catch below so it propagates rather than being logged and swallowed like a
        // serialisation failure — this method has no @Async, so the NPE rolls back the still-open
        // registration transaction instead of silently persisting a null token.
        Objects.requireNonNull(event.verifyUrl(), "verifyUrl must not be null");
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
        // skillars-deferred-111 AC6 — see onVerificationEmail's comment above for the full rationale.
        Objects.requireNonNull(event.otp(), "otp must not be null");
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
