package com.softropic.skillars.infrastructure.ses;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration
@EnableConfigurationProperties(SesProperties.class)
public class SesConfig {

    @Bean
    @ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true", matchIfMissing = false)
    public SesV2Client sesV2Client(SesProperties props) {
        return SesV2Client.builder()
            .region(Region.of(props.getRegion()))
            .build();
    }
}
