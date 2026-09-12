package com.softropic.skillars.platform.notification.service;

import com.google.common.base.CaseFormat;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;
import java.util.Map;

/**
 * Renders an email's subject/body from a {@code (Recipient, EmailTemplate, values)} triple.
 *
 * <p>Story ses-1.2 AC1: extracted <strong>verbatim</strong> out of the pre-extraction
 * {@code MailService.sendEmailFromTemplate} (locale resolution, {@code Context} construction, the
 * Thymeleaf {@code process(...)} call, and the {@code messageSource.getMessage(subjectKey, ...)}
 * subject lookup) — a faithful move that {@code EmailRenderingCharacterizationTest} pins as
 * byte-identical for the same inputs.
 *
 * <p>{@link EmailTemplate#NONE} is special-cased exactly as {@code MailService} always special-cased
 * it: subject/body come straight out of the {@code values} map, {@code htmlBody} is left {@code
 * null}, and {@code textBody} is set to the map's {@code "body"} entry. This is what lets {@code
 * AlertNotificationListener}'s plaintext ops alerts (§6.2) flow through {@code OutboundEmailSender}
 * — SESv2's {@code simple} content models {@code html}/{@code text} as separate, independently
 * optional parts, and this shape is what a caller building an {@code OutboundEmailRequest} needs.
 */
@Component
public class EmailContentRenderer {

    private static final String RECIPIENT = "recipient";

    private final SpringTemplateEngine templateEngine;
    private final MessageSource messageSource;

    public EmailContentRenderer(final SpringTemplateEngine templateEngine, final MessageSource messageSource) {
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    /**
     * @param subject the rendered subject line
     * @param htmlBody the rendered HTML body, or {@code null} for the {@link EmailTemplate#NONE} case
     * @param textBody the plain-text body — only ever populated for the {@link EmailTemplate#NONE}
     *     case, {@code null} otherwise
     */
    public record Rendered(String subject, String htmlBody, String textBody) {
    }

    public Rendered render(final Recipient recipient, final EmailTemplate emailTemplate, final Map<String, Object> values) {
        final Locale locale = Locale.forLanguageTag(recipient.getLangKey());

        if (EmailTemplate.NONE.equals(emailTemplate)) {
            final String subject = (String) values.get("subject");
            final String body = (String) values.get("body");
            return new Rendered(subject, null, body);
        }

        final Context context = new Context(locale);
        context.setVariable(RECIPIENT, recipient);
        context.setVariable("map", values);
        final String content = templateEngine.process(CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, emailTemplate.name()), context);
        final String subject = messageSource.getMessage(emailTemplate.subjectKey(), null, locale);
        return new Rendered(subject, content, null);
    }
}
