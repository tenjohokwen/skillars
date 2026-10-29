package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6: every admin-outcome template exists, renders with the data AdminOutcomeEmailListener sends, and has a
 * real localized subject in each bundled locale (not the raw key, not the English text in de/fr).
 */
class AdminOutcomeEmailRenderingTest {

    private final EmailContentRenderer renderer = buildRenderer();

    private static EmailContentRenderer buildRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mails/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasenames("classpath:/i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(ms);
        return new EmailContentRenderer(engine, ms);
    }

    private static Map<String, Object> data() {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", "Coach Kim");
        data.put("reason", "Reason <b>text</b>");
        data.put("disputeReason", "SESSION_QUALITY");
        data.put("resolution", "PARTIAL_CREDIT");
        data.put("creditAmount", "12.50");
        data.put("resolutionNote", "Half refunded");
        data.put("requestedStartTime", "2026-10-01T10:00:00Z");
        data.put("expiresAt", "2026-10-30T10:00:00Z");
        data.put("profileUrl", "https://app.test/#/profile");
        data.put("dashboardUrl", "https://app.test/#/dashboard");
        data.put("reliabilityUrl", "https://app.test/#/coach/reliability");
        data.put("coachProfileUrl", "https://app.test/#/coaches/abc");
        data.put("audience", "PARENT");
        data.put("refundedAmount", "30.00");
        data.put("recoveredAmount", "12.00");
        data.put("absorbedAmount", "18.00");
        data.put("coachPayoutAmount", "27.00");
        data.put("packSession", "false");
        data.put("sessionRededucted", "false");
        return data;
    }

    @ParameterizedTest
    @EnumSource(value = EmailTemplate.class, names = {"COACH_SUSPENDED", "COACH_REINSTATED", "COACH_WARNING_ISSUED",
        "DISPUTE_RESOLVED", "DISPUTE_DISMISSED", "DISPUTE_COACH_UPHELD", "GDPR_EXPORT_READY", "REVIEW_APPROVED", "REVIEW_BLOCKED"})
    void rendersInEveryBundledLocale(EmailTemplate template) {
        String english = null;
        for (String lang : List.of("en", "de", "fr")) {
            Recipient recipient = new Recipient();
            recipient.setLangKey(lang);
            EmailContentRenderer.Rendered rendered = renderer.render(recipient, template, data());

            assertThat(rendered.subject()).as("%s/%s subject", template, lang)
                .isNotBlank().doesNotContain("email.admin.");
            assertThat(rendered.htmlBody()).as("%s/%s body", template, lang)
                .doesNotContain("<b>text</b>"); // free text is escaped, never injected as markup
            if (lang.equals("en")) {
                english = rendered.subject();
            } else {
                assertThat(rendered.subject()).as("%s/%s is translated", template, lang).isNotEqualTo(english);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void disputeResolved_statesThePartialCreditAmount() {
        Recipient recipient = new Recipient();
        recipient.setLangKey("en");
        assertThat(renderer.render(recipient, EmailTemplate.DISPUTE_RESOLVED, data()).htmlBody())
            .contains("12.50").contains("Coach Kim").contains("Half refunded");
    }

    // feat/payment-forfeit-payouts-and-clawback
    @org.junit.jupiter.api.Test
    void upholdCoach_tellsTheParentWhatWasRecoveredAndAbsorbed_inEveryLocale() {
        Map<String, Object> data = data();
        data.put("resolution", "UPHOLD_COACH");
        for (String lang : List.of("en", "de", "fr")) {
            Recipient recipient = new Recipient();
            recipient.setLangKey(lang);
            for (EmailTemplate template : List.of(EmailTemplate.DISPUTE_RESOLVED, EmailTemplate.DISPUTE_COACH_UPHELD)) {
                String body = renderer.render(recipient, template, data).htmlBody();
                assertThat(body).as("%s/%s", template, lang)
                    .contains("12.00").contains("30.00").contains("18.00")
                    .doesNotContain("email.admin.").doesNotContain("??");
            }
        }
        Recipient en = new Recipient();
        en.setLangKey("en");
        assertThat(renderer.render(en, EmailTemplate.DISPUTE_RESOLVED, data).htmlBody())
            .doesNotContain("No credit was issued");
    }

    @org.junit.jupiter.api.Test
    void upholdCoach_tellsTheCoachTheyArePaid() {
        Map<String, Object> data = data();
        data.put("resolution", "UPHOLD_COACH");
        data.put("audience", "COACH");
        Recipient en = new Recipient();
        en.setLangKey("en");
        assertThat(renderer.render(en, EmailTemplate.DISPUTE_COACH_UPHELD, data).htmlBody())
            .contains("27.00").contains("Coach Kim").doesNotContain("credit wallet");
    }
}
