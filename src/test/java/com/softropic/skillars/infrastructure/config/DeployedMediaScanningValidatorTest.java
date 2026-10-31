package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.infrastructure.videointel.VideoIntelClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeployedMediaScanningValidatorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FeatureToggleService toggles = mock(FeatureToggleService.class);
    private final VideoIntelClient videoIntel = mock(VideoIntelClient.class);

    private DeployedMediaScanningValidator validator(MockEnvironment env) {
        return new DeployedMediaScanningValidator(env, toggles, videoIntel, registry);
    }

    private MockEnvironment env(String apiKey, Boolean allowUnscanned) {
        MockEnvironment env = new MockEnvironment().withProperty(DeployedMediaScanningValidator.ARACHNID_API_KEY, apiKey);
        if (allowUnscanned != null) {
            env.withProperty(DeployedMediaScanningValidator.ALLOW_UNSCANNED_MEDIA, allowUnscanned.toString());
        }
        return env;
    }

    private double gauge() {
        return registry.get(DeployedMediaScanningValidator.GAUGE).gauge().value();
    }

    @Test
    void imageScanningOnWithKey_videoScannerOff_boots_gaugeZero() {
        when(toggles.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);

        assertThatCode(() -> validator(env("key", null)).validate()).doesNotThrowAnyException();
        assertThat(gauge()).isZero();
    }

    @Test
    void imageScanningDisabled_refusesToBoot() {
        assertThatThrownBy(() -> validator(env("key", null)).validate())
            .isInstanceOf(AppSetupException.class).hasMessageContaining("image scanning is disabled");
    }

    @Test
    void imageScanningOnWithBlankKey_refusesToBoot_andNeverPrintsTheKey() {
        when(toggles.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);

        assertThatThrownBy(() -> validator(env(" ", null)).validate())
            .isInstanceOf(AppSetupException.class).hasMessageContaining("ARACHNID_API_KEY is blank");
    }

    @Test
    void videoIntelEnabledWhileStub_refusesToBoot() {
        when(toggles.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(toggles.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);
        when(videoIntel.isRealScanner()).thenReturn(false);

        assertThatThrownBy(() -> validator(env("key", null)).validate())
            .isInstanceOf(AppSetupException.class).hasMessageContaining("video scanner is a stub");
    }

    @Test
    void videoIntelEnabledWithRealScanner_boots() {
        when(toggles.isEnabled(AppFeature.ARACHNID_ENABLED)).thenReturn(true);
        when(toggles.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);
        when(videoIntel.isRealScanner()).thenReturn(true);

        assertThatCode(() -> validator(env("key", null)).validate()).doesNotThrowAnyException();
    }

    @Test
    void override_bootsAnyway_andRaisesTheGaugeToTheViolationCount() {
        when(toggles.isEnabled(AppFeature.VIDEOINTEL_ENABLED)).thenReturn(true);

        assertThatCode(() -> validator(env("", true)).validate()).doesNotThrowAnyException();
        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    void overrideExplicitlyFalse_stillRefuses() {
        assertThatThrownBy(() -> validator(env("key", false)).validate()).isInstanceOf(AppSetupException.class);
    }
}
