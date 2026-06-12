package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.ses.SesEmailService;
import com.softropic.skillars.infrastructure.ses.exception.SesException;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class ParentRegistrationEmailListener {

    private final SesEmailService sesEmailService;
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
        try {
            sesEmailService.send(event.toAddress(), subject, html);
        } catch (SesException ex) {
            log.error("Failed to send parent verification email — registration may be orphaned. userId lookup required.", ex);
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
        try {
            sesEmailService.send(event.toAddress(), subject, html);
        } catch (SesException ex) {
            log.error("Failed to send parent OTP email — user is EMAIL_VERIFIED but OTP unreachable; resend-OTP endpoint required.", ex);
        }
    }
}
