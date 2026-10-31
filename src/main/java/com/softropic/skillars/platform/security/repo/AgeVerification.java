package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.platform.security.contract.AgeTier;
import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * S-27 (V222): outcome of one Stripe Identity session. Deliberately holds no document data and no date of birth —
 * only the status, the provider's session id, when it verified and which age tier the verified date of birth fell in.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "age_verifications", schema = "main")
public class AgeVerification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stripe_session_id", nullable = false, length = 255, updatable = false)
    private String stripeSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgeVerificationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "verified_tier", length = 15)
    private AgeTier verifiedTier;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
