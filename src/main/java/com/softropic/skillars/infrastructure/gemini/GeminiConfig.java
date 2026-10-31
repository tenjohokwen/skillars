package com.softropic.skillars.infrastructure.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiConfig {

    @Bean
    public GeminiClient geminiClient(GeminiProperties props, RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder
            .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .readTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
            .build();
        return new GeminiClientImpl(restTemplate, endpoint(props, restTemplate), props.getModel());
    }

    static GeminiEndpoint endpoint(GeminiProperties props, RestTemplate restTemplate) {
        return switch (props.getMode()) {
            case DEVELOPER -> {
                if (props.getApiKey() == null || props.getApiKey().isBlank()) {
                    throw new IllegalStateException(
                        "GEMINI_API_KEY environment variable must be set (infrastructure.gemini.api-key is blank)");
                }
                yield new DeveloperGeminiEndpoint(props.getApiBaseUrl(), props.getApiKey());
            }
            case VERTEX -> vertexEndpoint(props.getVertex(), restTemplate);
        };
    }

    private static GeminiEndpoint vertexEndpoint(GeminiProperties.Vertex vertex, RestTemplate restTemplate) {
        if (isBlank(vertex.getProjectId()) || isBlank(vertex.getLocation()) || isBlank(vertex.getCredentialsPath())) {
            throw new IllegalStateException("GEMINI_MODE=vertex requires GEMINI_VERTEX_PROJECT, "
                + "GEMINI_VERTEX_LOCATION and GEMINI_VERTEX_CREDENTIALS_PATH "
                + "(infrastructure.gemini.vertex.project-id / location / credentials-path)");
        }
        JsonNode credentials;
        try {
            credentials = new ObjectMapper().readTree(Files.readString(Path.of(vertex.getCredentialsPath())));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot read the Vertex service-account file at infrastructure.gemini.vertex.credentials-path", e);
        }
        String baseUrl = isBlank(vertex.getApiBaseUrl())
            ? "https://" + vertex.getLocation() + "-aiplatform.googleapis.com"
            : vertex.getApiBaseUrl();
        return new VertexGeminiEndpoint(baseUrl, vertex.getProjectId(), vertex.getLocation(),
            new GoogleAccessTokenProvider(restTemplate, credentials, Clock.systemUTC()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
