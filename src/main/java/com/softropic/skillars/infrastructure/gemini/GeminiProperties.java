package com.softropic.skillars.infrastructure.gemini;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "infrastructure.gemini")
public class GeminiProperties {
    private String apiKey = "";
    private String apiBaseUrl = "https://generativelanguage.googleapis.com";
    private String model = "gemini-2.0-flash";
    private int timeoutSeconds = 30;
}
