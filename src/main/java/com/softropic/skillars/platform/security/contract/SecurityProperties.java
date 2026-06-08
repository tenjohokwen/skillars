package com.softropic.skillars.platform.security.contract;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for security-related settings.
 * Externalizes hardcoded values from service classes.
 */
@ConfigurationProperties(prefix = "app.security")
@Data
public class SecurityProperties {

    /**
     * Password reset token expiration duration.
     * Default: 24 hours
     */
    private Duration passwordResetExpiration = Duration.ofHours(24);

    /**
     * Number of days after which non-activated user accounts are deleted.
     * Default: 3 days
     */
    private int accountActivationExpirationDays = 3;

    /**
     * Batch size for user cleanup operations.
     * Default: 100 users per batch
     */
    private int userCleanupBatchSize = 100;

    /**
     * Activation key expiration duration.
     * Default: 7 days
     */
    private Duration activationKeyExpiration = Duration.ofDays(7);

    /**
     * Maximum login attempts before account lockout.
     * Default: 5 attempts
     */
    private int maxLoginAttempts = 5;

    /**
     * Duration for which an account remains locked after max failed attempts.
     * Default: 15 minutes
     */
    private Duration accountLockoutDuration = Duration.ofMinutes(15);
}
