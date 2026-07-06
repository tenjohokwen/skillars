package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.infrastructure.ses.SesEmailService;
import com.softropic.skillars.infrastructure.ses.exception.SesException;
import com.softropic.skillars.platform.notification.service.MailService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-only stand-in for the real AWS SES adapter: routes SesEmailService.send()
 * through the notification module's own SMTP-based MailService instead, so
 * flipping app.ses.enabled=true locally sends real mail without needing AWS
 * credentials.
 */
@Slf4j
@Service
@Profile("dev")
@ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DevSesEmailService implements SesEmailService {

    private final MailService mailService;

    @Override
    public void send(String toAddress, String subject, String htmlBody) {
        try {
            log.atInfo()
               .setMessage("Using mail service to send email").log();
            mailService.sendEmail(toAddress, subject, htmlBody, false, true);
        } catch (MessagingException ex) {
            throw new SesException("Failed to send email", ex);
        }
    }
}
