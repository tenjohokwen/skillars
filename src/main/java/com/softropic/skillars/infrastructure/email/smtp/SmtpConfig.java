package com.softropic.skillars.infrastructure.email.smtp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Story ses-1.2 AC2: registers {@link SmtpProperties}/{@link SmtpHealthProperties} as beans. Moved
 * out of {@code platform.notification.config.ComponentConfig}, whose
 * {@code @EnableConfigurationProperties({EmailProperties.class, SmtpHealthProperties.class})} drops
 * entirely now that both classes live here — SMTP config binding is this package's own concern.
 */
@Configuration
@EnableConfigurationProperties({SmtpProperties.class, SmtpHealthProperties.class})
public class SmtpConfig {
}
