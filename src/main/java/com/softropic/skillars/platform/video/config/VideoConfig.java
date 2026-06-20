package com.softropic.skillars.platform.video.config;

import com.softropic.skillars.platform.video.contract.QuotaProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(VideoProperties.class)
public class VideoConfig {

    @Bean
    public QuotaProviderValidator quotaProviderValidator(ObjectProvider<QuotaProvider> quotaProviderProvider) {
        QuotaProvider quotaProvider = quotaProviderProvider.getIfAvailable();
        if (quotaProvider == null) {
            throw new IllegalStateException(
                "Video module requires a QuotaProvider bean. " +
                "Register an implementation in your application @Configuration. " +
                "To disable quota enforcement, use: @Bean QuotaProvider quotaProvider() { return new NoOpQuotaProvider(); }");
        }
        log.info("QuotaProvider active: {} — consistency guarantee: {}",
            quotaProvider.getClass().getSimpleName(), quotaProvider.getConsistencyGuarantee());
        return new QuotaProviderValidator(quotaProvider);
    }

    public record QuotaProviderValidator(QuotaProvider quotaProvider) {}
}
