package com.softropic.skillars.infrastructure.gemini;

import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
public class GeminiClientImpl implements GeminiClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiBaseUrl;
    private final String model;

    public GeminiClientImpl(RestTemplate restTemplate, String apiKey, String apiBaseUrl, String model) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl;
        this.model = model;
    }

    @Override
    public ModerationVerdict evaluate(String combinedPrompt) {
        String url = apiBaseUrl + "/v1beta/models/" + model + ":generateContent";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(Map.of("text", combinedPrompt)))
            ),
            "generationConfig", Map.of("maxOutputTokens", 10, "temperature", 0)
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            GeminiApiResponse response = restTemplate.postForObject(url, entity, GeminiApiResponse.class);
            if (response == null
                    || response.candidates() == null
                    || response.candidates().isEmpty()
                    || response.candidates().get(0).content() == null
                    || response.candidates().get(0).content().parts() == null
                    || response.candidates().get(0).content().parts().isEmpty()) {
                throw new GeminiException("Gemini returned empty or malformed response", null);
            }
            String text = response.candidates().get(0).content().parts().get(0).text();
            return parseVerdict(text);
        } catch (RestClientException e) {
            throw new GeminiException("Gemini API call failed: " + e.getMessage(), e);
        }
    }

    private ModerationVerdict parseVerdict(String text) {
        if (text == null) return ModerationVerdict.UNCERTAIN;
        String normalized = text.trim().toUpperCase();
        return switch (normalized) {
            case "SAFE" -> ModerationVerdict.SAFE;
            case "UNSAFE" -> ModerationVerdict.UNSAFE;
            default -> ModerationVerdict.UNCERTAIN;
        };
    }
}
