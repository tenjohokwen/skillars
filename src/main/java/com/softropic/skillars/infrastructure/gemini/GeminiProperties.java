package com.softropic.skillars.infrastructure.gemini;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "infrastructure.gemini")
public class GeminiProperties {
    /** Endpoint family: {@code developer} (API key) or {@code vertex} (Vertex AI, service account). */
    private GeminiMode mode = GeminiMode.DEVELOPER;
    private String apiKey = "";
    private String apiBaseUrl = "https://generativelanguage.googleapis.com";
    private String model = "gemini-2.0-flash";
    private int timeoutSeconds = 30;
    private Vertex vertex = new Vertex();

    @Getter
    @Setter
    public static class Vertex {
        private String projectId = "";
        /** EU region; the data stays in this location. */
        private String location = "europe-west4";
        /** Path of the service-account key JSON file. */
        private String credentialsPath = "";
        /** Blank means {@code https://{location}-aiplatform.googleapis.com}; overridable for tests. */
        private String apiBaseUrl = "";
    }
}
