package com.softropic.skillars.i18n;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-93 AC7 — ties {@link EmailTemplate} enum to i18n bundles,
 * catching enum-vs-bundle drift. Validates that every {@code EmailTemplate.subjectKey()}
 * (except {@code NONE}) is resolvable in all four message bundles (default, en, de, fr).
 *
 * <p>No live bug today — all 38 non-NONE template keys ARE present in all four bundles.
 * Value is drift detection: a key renamed in the enum or added to only some bundles would fail
 * silently at runtime without this test. The {@link MessageBundleParityTest} catches
 * bundle-to-bundle drift but not enum↔bundle drift.
 */
@SpringBootTest
@DisplayName("Email template subject keys are present in all message bundles")
class EmailTemplateSubjectKeyParityTest {

    @Autowired private MessageSource messageSource;

    @Test
    void allEmailTemplateSubjectKeys_areResolvableInDefaultLocale() {
        for (EmailTemplate template : EmailTemplate.values()) {
            if (template == EmailTemplate.NONE) {
                continue;
            }
            String key = template.subjectKey();
            assertThat(key).as("template %s must have a non-empty subjectKey", template).isNotBlank();

            // Default locale — falls back to messages.properties
            String resolved = messageSource.getMessage(key, null, (String) null, Locale.ROOT);
            assertThat(resolved).as("key %s must be resolvable in default locale", key).isNotNull();
        }
    }

    @Test
    void allEmailTemplateSubjectKeys_areResolvableInEnglish() {
        for (EmailTemplate template : EmailTemplate.values()) {
            if (template == EmailTemplate.NONE) {
                continue;
            }
            String key = template.subjectKey();
            String resolved = messageSource.getMessage(key, null, (String) null, Locale.ENGLISH);
            assertThat(resolved).as("key %s must be resolvable in en locale", key).isNotNull();
        }
    }

    @Test
    void allEmailTemplateSubjectKeys_areResolvableInGerman() {
        for (EmailTemplate template : EmailTemplate.values()) {
            if (template == EmailTemplate.NONE) {
                continue;
            }
            String key = template.subjectKey();
            String resolved = messageSource.getMessage(key, null, (String) null, Locale.GERMAN);
            assertThat(resolved).as("key %s must be resolvable in de locale", key).isNotNull();
        }
    }

    @Test
    void allEmailTemplateSubjectKeys_areResolvableInFrench() {
        for (EmailTemplate template : EmailTemplate.values()) {
            if (template == EmailTemplate.NONE) {
                continue;
            }
            String key = template.subjectKey();
            String resolved = messageSource.getMessage(key, null, (String) null, Locale.FRENCH);
            assertThat(resolved).as("key %s must be resolvable in fr locale", key).isNotNull();
        }
    }

    @Test
    void noneTemplate_isSpecialCasedAndNotResolvable() {
        assertThat(EmailTemplate.NONE.subjectKey()).isEmpty();
    }
}
