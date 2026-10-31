package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * fix/security-payout-oauth-session-revocation (V216): one outstanding Stripe Connect OAuth {@code state}
 * for a coach. Only the SHA-256 of the random nonce is stored, so a database read does not yield a usable
 * {@code state}. Consumed (deleted) by the callback, exactly once.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "stripe_oauth_states", schema = "payment")
public class StripeOAuthState {

    @Id
    @Column(name = "nonce_hash", nullable = false, length = 64)
    private String nonceHash;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public StripeOAuthState(String nonceHash, UUID coachId, Instant expiresAt, Instant createdAt) {
        this.nonceHash = nonceHash;
        this.coachId = coachId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }
}
