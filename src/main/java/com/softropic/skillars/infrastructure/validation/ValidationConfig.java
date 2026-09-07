package com.softropic.skillars.infrastructure.validation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@code infrastructure.validation} configuration properties (skillars-deferred-99 AC16).
 */
@Configuration
@EnableConfigurationProperties(PhoneValidationProperties.class)
public class ValidationConfig {
}
