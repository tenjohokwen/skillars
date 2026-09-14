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
