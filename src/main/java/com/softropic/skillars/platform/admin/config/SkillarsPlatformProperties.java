package com.softropic.skillars.platform.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties bound from the {@code skillars.platform} YAML block.
 *
 * <p>Registered via {@link PlatformConfig#enablePlatformProperties()} which uses
 * {@code @EnableConfigurationProperties(SkillarsPlatformProperties.class)} — same pattern
 * as {@code OrangeMoneyConfig} registered by {@code OrangeConfig}.
 *
 * <p>YAML binding example:
 * <pre>
 * skillars:
 *   platform:
 *     notification-email: ${PLATFORM_NOTIFICATION_EMAIL:admin@example.com}
 *     pin-encryption-secret: ${PLATFORM_PIN_ENCRYPTION_SECRET:}
 * </pre>
 */
@ConfigurationProperties(prefix = "skillars.platform")
public class SkillarsPlatformProperties {

    /** Email address to notify when a platform MSISDN is updated by admin. */
    private String notificationEmail;

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }

    /**
     * AES256 encryption secret for provider PINs.
     * Bound from {@code skillars.platform.pin-encryption-secret}.
     * Set via {@code PLATFORM_PIN_ENCRYPTION_SECRET} environment variable.
     * Phase 42 validates non-blank before constructing the encryptor.
     */
    private String pinEncryptionSecret;

    public String getPinEncryptionSecret() {
        return pinEncryptionSecret;
    }

    public void setPinEncryptionSecret(String pinEncryptionSecret) {
        this.pinEncryptionSecret = pinEncryptionSecret;
    }

    /**
     * HMAC-SHA256 key for the short-lived phone-verification handle returned by
     * {@code /verify-email} and required by {@code /verify-phone} and {@code /resend-otp}
     * (skillars-deferred-93 AC8). Bound from {@code skillars.platform.registration-verification-secret},
     * set via {@code PLATFORM_REGISTRATION_VERIFICATION_SECRET}. Rotating it only invalidates
     * in-flight registration handles (24h TTL), nothing persistent.
     * {@code RegistrationVerificationTokenService} fails fast if this is blank.
     */
    private String registrationVerificationSecret;

    public String getRegistrationVerificationSecret() {
        return registrationVerificationSecret;
    }

    public void setRegistrationVerificationSecret(String registrationVerificationSecret) {
        this.registrationVerificationSecret = registrationVerificationSecret;
    }
}
