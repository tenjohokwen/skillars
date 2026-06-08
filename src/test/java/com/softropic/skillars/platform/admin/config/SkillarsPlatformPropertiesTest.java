package com.softropic.skillars.platform.admin.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for SkillarsPlatformProperties PIN-02 property binding.
 *
 * Uses a plain unit test (setter injection) to avoid loading the full Spring context,
 * consistent with PlatformConfigServiceTest which uses @ExtendWith(MockitoExtension.class).
 */
class SkillarsPlatformPropertiesTest {

    @Test
    void pinEncryptionSecret_boundFromProperty() {
        SkillarsPlatformProperties properties = new SkillarsPlatformProperties();
        properties.setPinEncryptionSecret("test-secret-for-unit-test");

        assertThat(properties.getPinEncryptionSecret())
            .isEqualTo("test-secret-for-unit-test");
    }

    @Test
    void pinEncryptionSecret_nullWhenNotSet() {
        SkillarsPlatformProperties properties = new SkillarsPlatformProperties();

        assertThat(properties.getPinEncryptionSecret()).isNull();
    }

    @Test
    void notificationEmail_stillBindsCorrectly() {
        SkillarsPlatformProperties properties = new SkillarsPlatformProperties();
        properties.setNotificationEmail("test@example.com");

        assertThat(properties.getNotificationEmail())
            .isEqualTo("test@example.com");
    }
}
