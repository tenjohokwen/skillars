package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.email.EmailTransportException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.event.ParentOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.ParentVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParentRegistrationEmailListener {

    private final OutboundEmailSender outboundEmailSender;
    private final SpringTemplateEngine templateEngine;
    private final MessageSource messageSource;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmail(ParentVerificationEmailEvent event) {
        Locale locale = Locale.forLanguageTag(event.langKey());

        Recipient recipient = new Recipient();
        recipient.setFirstname(event.firstName());
        recipient.setLangKey(event.langKey());

        Context context = new Context(locale);
        context.setVariable("recipient", recipient);
        context.setVariable("map", Map.of("verifyUrl", event.verifyUrl()));

        String html = templateEngine.process("parentEmailVerify", context);
        String subject = messageSource.getMessage(EmailTemplate.PARENT_EMAIL_VERIFY.subjectKey(), null, locale);
        // Generated outside the try so the catch can name it. The request construction itself
        // stays inside, per AC11, so the record's IllegalArgumentException is still caught.
        String correlationId = UUID.randomUUID().toString();
        try {
            OutboundEmailRequest request = new OutboundEmailRequest(
                event.toAddress(), subject, html, null, correlationId);
            outboundEmailSender.send(request);
        } catch (EmailTransportException | IllegalArgumentException ex) {
            log.error("Failed to send parent verification email — registration may be orphaned. userId lookup required. correlationId={}", correlationId, ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOtpEmail(ParentOtpEmailEvent event) {
        Locale locale = Locale.forLanguageTag(event.langKey());

        Recipient recipient = new Recipient();
        recipient.setFirstname(event.firstName());
        recipient.setLangKey(event.langKey());

        Context context = new Context(locale);
        context.setVariable("recipient", recipient);
        context.setVariable("map", Map.of("otpCode", event.otp()));

        String html = templateEngine.process("parentOtp", context);
        String subject = messageSource.getMessage(EmailTemplate.PARENT_OTP.subjectKey(), null, locale);
        // Generated outside the try so the catch can name it. The request construction itself
        // stays inside, per AC11, so the record's IllegalArgumentException is still caught.
        String correlationId = UUID.randomUUID().toString();
        try {
            OutboundEmailRequest request = new OutboundEmailRequest(
                event.toAddress(), subject, html, null, correlationId);
            outboundEmailSender.send(request);
        } catch (EmailTransportException | IllegalArgumentException ex) {
            log.error("Failed to send parent OTP email — user is EMAIL_VERIFIED but OTP unreachable; resend-OTP endpoint required. correlationId={}", correlationId, ex);
        }
    }
}
