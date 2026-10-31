package com.softropic.skillars.platform.security.contract.event;

/**
 * S-27: published inside the webhook transaction once a session reached an outcome the provider should be told
 * about after commit: a verified/underage session is redacted (the provider drops the document and its data — we
 * keep only the outcome) and a failed one is cancelled so a retry needs a new, counted, session.
 */
public record AgeVerificationConcludedEvent(String sessionId, Action action) {

    public enum Action { REDACT, CANCEL }
}
