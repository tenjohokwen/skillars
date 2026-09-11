package com.softropic.skillars.infrastructure.email.smtp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Story ses-1.2 AC2: renamed from {@code platform.notification.contract.EmailProperties} and rebound
 * from {@code email} to {@code app.email.smtp} — every SMTP property now lives under one YAML key,
 * which is what makes {@code NoStraySmtpConfigTest} assertable.
 *
 * <p>Registered as a bean via {@code @EnableConfigurationProperties} in this package's own {@code
 * @Configuration}; this class carries no {@code @Configuration} annotation so it remains a passive
 * value holder, per the provider-adapter pattern this package follows.
 */
@ConfigurationProperties(prefix = "app.email.smtp")
public class SmtpProperties {

    private List<ProviderConfig> providerConfigs = new ArrayList<>();

    public List<ProviderConfig> getProviderConfigs() {
        return providerConfigs;
    }

    public void setProviderConfigs(List<ProviderConfig> providerConfigs) {
        this.providerConfigs = providerConfigs;
    }
}
