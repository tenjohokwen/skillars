package com.softropic.skillars.infrastructure.gemini;

import org.springframework.http.HttpHeaders;

/**
 * Vertex AI publisher-model endpoint in a fixed region, authenticated with an OAuth bearer token for a
 * service account. Region and project are configuration, so data stays in the chosen (EU) location.
 */
public class VertexGeminiEndpoint implements GeminiEndpoint {

    private final String apiBaseUrl;
    private final String projectId;
    private final String location;
    private final GoogleAccessTokenProvider tokens;

    public VertexGeminiEndpoint(String apiBaseUrl, String projectId, String location,
                                GoogleAccessTokenProvider tokens) {
        this.apiBaseUrl = apiBaseUrl;
        this.projectId = projectId;
        this.location = location;
        this.tokens = tokens;
    }

    @Override
    public GeminiMode mode() {
        return GeminiMode.VERTEX;
    }

    @Override
    public String generateContentUrl(String model) {
        return apiBaseUrl + "/v1/projects/" + projectId + "/locations/" + location
            + "/publishers/google/models/" + model + ":generateContent";
    }

    @Override
    public void authenticate(HttpHeaders headers) {
        headers.setBearerAuth(tokens.accessToken());
    }
}
