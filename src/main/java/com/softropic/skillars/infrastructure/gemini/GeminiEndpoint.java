package com.softropic.skillars.infrastructure.gemini;

import org.springframework.http.HttpHeaders;

/**
 * Transport strategy for {@link GeminiClientImpl}: where {@code generateContent} lives and how the call
 * authenticates. Everything else (body shape, verdict parsing) is shared between the developer API and
 * Vertex AI.
 */
public interface GeminiEndpoint {

    GeminiMode mode();

    String generateContentUrl(String model);

    /** Adds the credential header(s); may perform a token exchange. */
    void authenticate(HttpHeaders headers);
}
