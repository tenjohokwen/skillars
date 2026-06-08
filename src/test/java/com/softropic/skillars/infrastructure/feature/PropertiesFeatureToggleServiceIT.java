package com.softropic.skillars.infrastructure.feature;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {PropertiesFeatureToggleService.class}, properties = {
        "features.toggles.payments=true",
        "features.toggles.invoicing=false"
})
@EnableConfigurationProperties(FeatureProperties.class)
class PropertiesFeatureToggleServiceIT {

    @Autowired
    private FeatureToggleService featureToggleService;

    @Test
    void testEnabledFeature() {
        assertThat(featureToggleService.isEnabled(AppFeature.PAYMENTS)).isTrue();
    }

    @Test
    void testDisabledFeature() {
        assertThat(featureToggleService.isEnabled(AppFeature.INVOICING)).isFalse();
    }
}
