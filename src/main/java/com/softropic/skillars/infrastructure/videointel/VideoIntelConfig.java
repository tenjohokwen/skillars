package com.softropic.skillars.infrastructure.videointel;

import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Slf4j
@Configuration
@EnableConfigurationProperties(VideoIntelProperties.class)
public class VideoIntelConfig {

    @Bean
    public VideoIntelClient videoIntelClient(VideoIntelProperties props, RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder
            .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .build();
        return new VideoIntelClientImpl(restTemplate, props.getProjectId(), props.getFlagThreshold());
    }

    // Misconfiguration guard: VideoIntelClientImpl is fail-open — it passes ALL videos.
    // If VIDEOINTEL_ENABLED=true in production while the stub is active, explicit content
    // passes Layer 2 silently. This bean fires at startup to make misconfiguration visible.
    @Bean
    ApplicationListener<ApplicationReadyEvent> videoIntelStartupGuard(FeatureToggleService featureToggleService) {
        return event -> {
            if (featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED)) {
                log.error("MISCONFIGURATION: VIDEOINTEL_ENABLED=true but VideoIntelClientImpl is a " +
                          "fail-open stub. ALL videos will pass Layer 2 with NO explicit content " +
                          "screening. Do NOT use this configuration in production.");
            }
        };
    }
}
