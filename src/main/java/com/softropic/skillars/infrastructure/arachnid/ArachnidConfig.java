package com.softropic.skillars.infrastructure.arachnid;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ArachnidProperties.class)
public class ArachnidConfig {

    @Bean
    public ArachnidClient arachnidClient(ArachnidProperties props, RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder
            .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .build();
        return new ArachnidClientImpl(restTemplate, props.getApiKey(), props.getApiBaseUrl());
    }
}
