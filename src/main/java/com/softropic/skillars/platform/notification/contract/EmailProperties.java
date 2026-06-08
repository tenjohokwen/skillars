package com.softropic.skillars.platform.notification.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the email module.
 * Registered as a bean via {@code @EnableConfigurationProperties} in
 * {@code email.config.ComponentConfig}; this class must carry no {@code @Configuration}
 * annotation so it remains a passive value holder as required by the contract layer rules.
 */
@ConfigurationProperties(prefix = "email")
public class EmailProperties {

    private List<ProviderConfig> providerConfigs = new ArrayList<>();

    public List<ProviderConfig> getProviderConfigs() {
        return providerConfigs;
    }

    public void setProviderConfigs(List<ProviderConfig> providerConfigs) {
        this.providerConfigs = providerConfigs;
    }
}
