package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neutral registration, phone match: the registrant's REGISTRATION_PHONE_IN_USE mail renders in every bundled
 * locale with the data RegistrationDuplicateAttemptListener sends, and without a first name (none is passed).
 */
class RegistrationPhoneInUseEmailRenderingTest {

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

    @Test
    void rendersInEveryBundledLocale_withLoginAndResetLinksAndTheHelpCode() {
        EmailContentRenderer renderer = buildRenderer();
        Map<String, String> expectedSubject = Map.of(
            "en", "We couldn't create your Skillars account",
            "de", "Wir konnten Ihr Skillars-Konto nicht erstellen",
            "fr", "Nous n'avons pas pu cr\u00e9er votre compte Skillars");
        for (String lang : List.of("en", "de", "fr")) {
            Recipient recipient = new Recipient();
            recipient.setEmail("newcomer@example.com");
            recipient.setLangKey(lang);
            EmailContentRenderer.Rendered rendered = renderer.render(recipient,
                EmailTemplate.REGISTRATION_PHONE_IN_USE,
                Map.of("baseUrl", "https://app.test", "helpCode", "HC42"));

            assertThat(rendered.subject()).as("%s subject", lang).isEqualTo(expectedSubject.get(lang));
            assertThat(rendered.htmlBody()).as("%s body", lang)
                .contains("https://app.test/#/login")
                .contains("https://app.test/#/forgot-password")
                .contains("HC42")
                .doesNotContain("email.registration_phone_in_use")
                .doesNotContain("null");
        }
    }
}
