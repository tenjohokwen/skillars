package com.softropic.skillars.platform.video.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.video.BunnyVideoProviderAdapter;
import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class VideoProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "app.video.provider", havingValue = "bunny")
    public VideoProviderAdapter videoProviderAdapter(VideoProperties properties, ObjectMapper objectMapper) {
        VideoProperties.Bunny bunny = properties.getBunny();
        RestTemplate restTemplate = buildBunnyRestTemplate();
        return new BunnyVideoProviderAdapter(
            restTemplate,
            bunny.getApiKey(),
            bunny.getLibraryId(),
            bunny.getCdnHostname(),
            bunny.getApiBaseUrl(),
            objectMapper,
            (long) properties.getUpload().getSessionTtlMinutes() * 60L,
            bunny.getWebhookSigningSecret()
        );
    }

    private RestTemplate buildBunnyRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(new BufferingClientHttpRequestFactory(factory));
    }
}
