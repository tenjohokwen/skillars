package com.softropic.skillars.infrastructure.gemini;

/**
 * Which Google endpoint serves the moderation calls. Flipped by {@code infrastructure.gemini.mode}
 * ({@code GEMINI_MODE}); the request and response bodies are identical in both modes, only the URL
 * and the credential differ.
 */
public enum GeminiMode {
    /**
     * Google AI developer API ({@code generativelanguage.googleapis.com}, API key). Its terms offer no
     * GDPR Art. 28 data processing agreement and no EU data-residency commitment.
     */
    DEVELOPER,
    /** Vertex AI in a configurable (EU) region, covered by the Google Cloud DPA. */
    VERTEX
}
