package com.softropic.skillars.platform.security.contract;

import java.util.Optional;

/**
 * S-27: port to the identity provider (Stripe Identity), implemented in the payment module which owns the Stripe
 * client and keys. The security module never imports Stripe types.
 */
public interface AgeVerificationGateway {

    /** A started provider session: its id (stored) and the hosted URL the player is redirected to (not stored). */
    record Session(String sessionId, String url) {
    }

    /** True when the provider is configured (API key present). */
    boolean isConfigured();

    /**
     * Creates a document-check session. Only the internal user id is sent to the provider (as
     * {@code client_reference_id} / metadata) — no name, e-mail or declared date of birth.
     *
     * @param idempotencyKey replays the same session on a retry of the same attempt
     * @param returnPath     frontend route the provider returns the player to
     */
    Session createSession(long userId, String idempotencyKey, String returnPath);

    /** The hosted URL of a session that is still open, or empty if it is no longer resumable. */
    Optional<String> resumeUrl(String sessionId);
}
