package com.softropic.skillars.platform.notification.service;

import com.google.common.base.CaseFormat;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import io.micrometer.observation.annotation.Observed;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class MailService {

    private static final String RECIPIENT = "recipient";

    private final SenderProvider senderProvider;
    private final MessageSource messageSource;
    private final SpringTemplateEngine templateEngine;

    public MailService(final SenderProvider senderProvider,
                       final SpringTemplateEngine templateEngine,
                       final MessageSource messageSource) {
        this.senderProvider = senderProvider;
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    public void sendEmail(String to, String subject, String content, boolean isMultipart, boolean isHtml) throws MessagingException {
        JavaMailSenderImpl javaMailSender = senderProvider.nextSender();
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper message = new MimeMessageHelper(mimeMessage,
                                                          isMultipart,
                                                          String.valueOf(StandardCharsets.UTF_8));
        message.setTo(to);
        message.setFrom(javaMailSender.getUsername());
        message.setSubject(subject);
        message.setText(content, isHtml);

        // Any MessagingException (quota, network, SMTP auth) propagates to MailManager, which
        // catches it, marks the envelope FAILED with retry=true, and persists it. The
        // EmailRetryScheduler then picks it up via SELECT FOR UPDATE SKIP LOCKED.
        javaMailSender.send(mimeMessage);
    }

    @Observed(name = "mail.send_from_template")
    public void sendEmailFromTemplate(final Recipient recipient,
                                      final EmailTemplate emailTemplate,
                                      final Map<String, Object> values) throws MessagingException {
        final Locale locale = Locale.forLanguageTag(recipient.getLangKey());

        if (EmailTemplate.NONE.equals(emailTemplate)) {
            final String subject = (String) values.get("subject");
            final String content = (String) values.get("body");
            sendEmail(recipient.getEmail(), subject, content, false, false);
            return;
        }

        final Context context = new Context(locale);
        context.setVariable(RECIPIENT, recipient);
        context.setVariable("map", values);
        final String content = templateEngine.process(CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, emailTemplate.name()), context);
        final String subject = messageSource.getMessage(emailTemplate.subjectKey(), null, locale);
        sendEmail(recipient.getEmail(), subject, content, false, true);
    }
}
