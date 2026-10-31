package com.softropic.skillars.infrastructure.gemini;

import org.springframework.http.HttpHeaders;

/** Google AI developer API, authenticated with an API key. */
public class DeveloperGeminiEndpoint implements GeminiEndpoint {

    private final String apiBaseUrl;
    private final String apiKey;

    public DeveloperGeminiEndpoint(String apiBaseUrl, String apiKey) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public GeminiMode mode() {
        return GeminiMode.DEVELOPER;
    }

    @Override
    public String generateContentUrl(String model) {
        return apiBaseUrl + "/v1beta/models/" + model + ":generateContent";
    }

    @Override
    public void authenticate(HttpHeaders headers) {
        headers.set("x-goog-api-key", apiKey);
    }
}
