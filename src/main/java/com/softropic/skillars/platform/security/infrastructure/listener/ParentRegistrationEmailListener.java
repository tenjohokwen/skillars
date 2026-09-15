package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.ParentOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.ParentVerificationEmailEvent;
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
 * Story ses-1.4 AC1 — see {@link CoachRegistrationEmailListener}'s class javadoc for the full
 * rationale (including the two-outcome failure-mode correction, code review 2026-09-12); this class
 * mirrors it exactly for the parent registration flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentRegistrationEmailListener {

    private final NotificationOutboxSupport notificationOutboxSupport;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVerificationEmail(ParentVerificationEmailEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send parent verification email: address is blank");
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

            notificationOutboxSupport.enqueueEmail(EmailTemplate.PARENT_EMAIL_VERIFY, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.PARENT_EMAIL_VERIFY),
                kv("sendId", sendId), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOtpEmail(ParentOtpEmailEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send parent OTP email: address is blank");
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

            notificationOutboxSupport.enqueueEmail(EmailTemplate.PARENT_OTP, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.PARENT_OTP),
                kv("sendId", sendId), e);
        }
    }
}
