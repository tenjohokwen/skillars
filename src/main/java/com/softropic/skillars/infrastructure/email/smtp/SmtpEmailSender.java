package com.softropic.skillars.infrastructure.email.smtp;

import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * The SMTP {@link OutboundEmailSender} (story ses-1.2 AC2), replacing {@code MailService.sendEmail}'s
 * old {@code MimeMessage}/{@code MimeMessageHelper} construction (lines 40-55 pre-story) and
 * {@code SenderProvider.nextSender()} call — folded in here verbatim, calling
 * {@link MailSenderProvider#nextSender()} directly rather than through the now-deleted
 * {@code platform.notification.service.SenderProvider} interface.
 *
 * <p>{@code from} is the picked provider's {@link JavaMailSenderImpl#getUsername()}, unchanged — SMTP
 * sender identity stays a side effect of round-robin (D1 is not fixed here, only for SES).
 *
 * <p>{@code isHtml} derives from which of {@code htmlBody}/{@code textBody} is present — SMTP's
 * {@code MimeMessageHelper.setText} takes one body plus an {@code isHtml} flag, so {@code htmlBody}
 * is picked when present, else {@code textBody}, matching {@code MailService.sendEmailFromTemplate}'s
 * pre-story behaviour exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.transport", havingValue = "smtp")
public class SmtpEmailSender implements OutboundEmailSender {

    private final MailSenderProvider mailSenderProvider;
    private final SmtpErrorClassifier errorClassifier;

    @Override
    public OutboundEmailResult send(OutboundEmailRequest request) {
        try {
            JavaMailSenderImpl javaMailSender = mailSenderProvider.nextSender();
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());

            boolean isHtml = isPresent(request.htmlBody());
            String content = isHtml ? request.htmlBody() : request.textBody();

            helper.setTo(request.toAddress());
            helper.setFrom(javaMailSender.getUsername());
            helper.setSubject(request.subject());
            helper.setText(content, isHtml);

            // Any MessagingException (quota, network, SMTP auth) or MailException (Spring's
            // unchecked wrapper) propagates via SmtpErrorClassifier, which decides
            // transient/permanent — MailManager then marks the envelope FAILED with retry=<that>,
            // and the EmailRetryScheduler picks it up via SELECT FOR UPDATE SKIP LOCKED.
            javaMailSender.send(mimeMessage);
            return new OutboundEmailResult("smtp:" + request.correlationId());
        } catch (MessagingException | MailException ex) {
            throw errorClassifier.classify(ex);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
