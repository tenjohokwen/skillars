package com.softropic.skillars.infrastructure.gemini;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfig {

    @Bean
    public GeminiClient geminiClient(GeminiProperties props, RestTemplateBuilder builder) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY environment variable must be set (infrastructure.gemini.api-key is blank)");
        }
        RestTemplate restTemplate = builder
            .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .build();
        return new GeminiClientImpl(restTemplate, props.getApiKey(), props.getApiBaseUrl(), props.getModel());
    }
}
