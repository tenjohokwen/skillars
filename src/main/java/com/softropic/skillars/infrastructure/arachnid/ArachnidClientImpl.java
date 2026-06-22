package com.softropic.skillars.infrastructure.arachnid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

// CRITICAL pre-deploy: The Arachnid API integration (apiBaseUrl, request format, response fields)
// MUST be verified against the actual Project Arachnid API documentation before deploying.
// Contact C3P (Canadian Centre for Child Protection) for API credentials and exact schema.
@Slf4j
public class ArachnidClientImpl implements ArachnidClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String apiBaseUrl;

    public ArachnidClientImpl(RestTemplate restTemplate, String apiKey, String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public ArachnidScanResult scan(String mediaUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of("url", mediaUrl), headers);
        try {
            ResponseEntity<ArachnidApiResponse> response = restTemplate.exchange(
                apiBaseUrl + "/v1/scan", HttpMethod.POST, entity, ArachnidApiResponse.class);
            ArachnidApiResponse body = response.getBody();
            if (body == null) throw new ArachnidException("Arachnid returned empty response", null);
            return new ArachnidScanResult(body.matched(), body.matchType());
        } catch (RestClientException e) {
            throw new ArachnidException("Arachnid scan failed: " + e.getMessage(), e);
        }
    }

    // Internal response type — exact field names TBD; verify with Arachnid API docs before deploying
    private record ArachnidApiResponse(boolean matched, String matchType) {}
}
