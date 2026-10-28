package com.softropic.skillars.infrastructure.feature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesFeatureToggleServiceTest {

    private static PropertiesFeatureToggleService serviceWith(Map<String, Boolean> toggles) {
        FeatureProperties properties = new FeatureProperties();
        properties.setToggles(toggles);
        return new PropertiesFeatureToggleService(properties);
    }

    /**
     * Platform cleanup (B8): PAYMENTS and INVOICING were declared but never read anywhere and are gone
     * from {@link AppFeature}. A deployment may still set them, so they must not become a startup
     * failure — they are ignored with a WARN, and are not readable as features.
     */
    @ParameterizedTest
    @ValueSource(strings = {"payments", "invoicing"})
    void retiredToggleKeys_areToleratedNotFatal(String retiredKey) {
        PropertiesFeatureToggleService service = serviceWith(Map.of(retiredKey, true));

        assertThatCode(service::validate).doesNotThrowAnyException();
        assertThat(AppFeature.values()).extracting(AppFeature::key).doesNotContain(retiredKey);
    }

    @Test
    void unknownToggleKey_stillFailsStartup() {
        PropertiesFeatureToggleService service = serviceWith(Map.of("no-such-feature", true));

        assertThatThrownBy(service::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no-such-feature");
    }

    @Test
    void everyDeclaredFeature_isAcceptedAndReadable() {
        PropertiesFeatureToggleService service = serviceWith(Map.of(
            AppFeature.ARACHNID_ENABLED.key(), true,
            AppFeature.VIDEOINTEL_ENABLED.key(), false));

        assertThatCode(service::validate).doesNotThrowAnyException();
        assertThat(service.isEnabled(AppFeature.ARACHNID_ENABLED)).isTrue();
        assertThat(service.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).isFalse();
        assertThat(AppFeature.values()).containsExactly(AppFeature.ARACHNID_ENABLED, AppFeature.VIDEOINTEL_ENABLED);
    }
}
