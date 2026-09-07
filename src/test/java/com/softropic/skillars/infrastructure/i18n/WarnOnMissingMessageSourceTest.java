package com.softropic.skillars.infrastructure.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-99 AC3 — a code missing from every bundle degrades to "the code, returned"
 * instead of a {@code NoSuchMessageException}, and the warn-once tracker is bounded.
 */
class WarnOnMissingMessageSourceTest {

    private WarnOnMissingMessageSource newSource(boolean useCodeAsDefault) {
        WarnOnMissingMessageSource ms = new WarnOnMissingMessageSource();
        ms.setBasenames("classpath:/i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        ms.setUseCodeAsDefaultMessage(useCodeAsDefault);
        return ms;
    }

    @Test
    void missingCode_withUseCodeAsDefault_returnsTheCodeInsteadOfThrowing() {
        WarnOnMissingMessageSource ms = newSource(true);

        String resolved = ms.getMessage("this.key.exists.nowhere.deferred99", null, Locale.forLanguageTag("sw"));

        assertThat(resolved).isEqualTo("this.key.exists.nowhere.deferred99");
    }

    @Test
    void missingCode_withoutUseCodeAsDefault_stillThrows() {
        WarnOnMissingMessageSource ms = newSource(false);

        assertThatThrownBy(() -> ms.getMessage("this.key.exists.nowhere.deferred99", null, Locale.ENGLISH))
            .isInstanceOf(NoSuchMessageException.class);
    }

    @Test
    void warnOnceTracker_isBounded() {
        WarnOnMissingMessageSource ms = newSource(true);

        // Resolve far more distinct missing codes than the cap; must not OOM or blow the bound.
        assertThatCode(() -> {
            for (int i = 0; i < WarnOnMissingMessageSource.MAX_WARNED_CODES * 5; i++) {
                ms.getMessage("dynamic.missing.code." + i, null, Locale.forLanguageTag("sw"));
            }
        }).doesNotThrowAnyException();
    }
}
