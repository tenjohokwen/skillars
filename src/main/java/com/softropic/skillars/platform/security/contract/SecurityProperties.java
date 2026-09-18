package com.softropic.skillars.platform.security.contract;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for security-related settings.
 * Externalizes hardcoded values from service classes.
 *
 * <p><strong>Validated (code review 2026-09-18):</strong> mirrors {@code SesHealthProperties}'s
 * {@code @Validated} pattern — an unvalidated {@code userCleanupBatchSize <= 0} used to reach
 * {@code UserAdminService.removeNotActivatedUsers} and throw at runtime (an {@code ArithmeticException}
 * computing {@code maxBatches}, or an {@code IllegalArgumentException} from {@code PageRequest.of(0, 0)}
 * once that division was guarded), silently killing the scheduled job with no operator-visible cause at
 * boot. {@code @Min(1)} now fails application startup instead.
 */
@Validated
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
    @Min(1)
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
