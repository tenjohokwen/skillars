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
 * CREATION_DUP has two wordings. A phone-only duplicate registration ({@code phoneMatch = true}, sent
 * by RegistrationDuplicateAttemptListener) says another address used the account's phone number. An
 * email match, and EmailRegistrationStrategy's call, which sends no {@code phoneMatch} key, keep the
 * original "using this address" text.
 */
class CreationDupEmailRenderingTest {

    private static final List<String> LANGS = List.of("en", "de", "fr");

    private static final Map<String, String> PHONE_TEXT = Map.of(
        "en", "with the phone number on your account",
        "de", "mit der Telefonnummer Ihres Kontos",
        "fr", "avec le numéro de téléphone de votre compte");

    private static final Map<String, String> ADDRESS_TEXT = Map.of(
        "en", "using this address",
        "de", "mit dieser Adresse",
        "fr", "utilisant cette adresse");

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

    private static String render(EmailContentRenderer renderer, String lang, Map<String, Object> data) {
        Recipient recipient = new Recipient();
        recipient.setEmail("owner@example.com");
        recipient.setFirstname("Grace");
        recipient.setLangKey(lang);
        return renderer.render(recipient, EmailTemplate.CREATION_DUP, data).htmlBody();
    }

    @Test
    void phoneMatch_saysTheAccountsPhoneNumberWasUsed_notThisAddress() {
        EmailContentRenderer renderer = buildRenderer();
        for (String lang : LANGS) {
            String body = render(renderer, lang,
                Map.of("baseUrl", "https://app.test", "helpCode", "HC42", "phoneMatch", true));

            assertThat(body).as("%s body", lang)
                .contains(PHONE_TEXT.get(lang))
                .doesNotContain(ADDRESS_TEXT.get(lang))
                .contains("https://app.test/#/forgot-password")
                .contains("HC42")
                .doesNotContain("email.creation_dup");
        }
    }

    @Test
    void emailMatch_andTheCallWithoutTheKey_keepTheOriginalWording() {
        EmailContentRenderer renderer = buildRenderer();
        for (String lang : LANGS) {
            for (Map<String, Object> data : List.<Map<String, Object>>of(
                    Map.of("baseUrl", "https://app.test", "helpCode", "HC42", "phoneMatch", false),
                    Map.of("baseUrl", "https://app.test", "helpCode", "HC42"))) {
                String body = render(renderer, lang, data);

                assertThat(body).as("%s body, data %s", lang, data.keySet())
                    .contains(ADDRESS_TEXT.get(lang))
                    .doesNotContain(PHONE_TEXT.get(lang))
                    .doesNotContain("email.creation_dup");
            }
        }
    }
}
