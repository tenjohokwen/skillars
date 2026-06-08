package com.softropic.skillars.platform.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    @ConfigurationProperties(prefix = "custom.cors")
    public CorsConfiguration corsConfiguration() {
        return new CorsConfiguration();
    }

    @Bean
    public CorsFilter corsFilter(CorsConfiguration config) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!CollectionUtils.isEmpty(config.getAllowedOrigins()) || !CollectionUtils.isEmpty(config.getAllowedOriginPatterns())) {
            source.registerCorsConfiguration("/api/**", config);
            source.registerCorsConfiguration("/authenticate", config);
            source.registerCorsConfiguration("/v1/api/**", config);
            source.registerCorsConfiguration("/v1/admin/**", config);
            source.registerCorsConfiguration("/actuator/**", config);
            source.registerCorsConfiguration("/manage/**", config);
        }
        return new CorsFilter(source);
    }

}
