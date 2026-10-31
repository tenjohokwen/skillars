package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "phone_otp_tokens")
public class PhoneOtpToken extends BaseEntity {

    /** Wrong guesses after which the token is invalidated; the user must request a resend (a fresh token). */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    @Version
    private Long version;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "otp_hash", length = 64, nullable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used;

    /** Wrong guesses against this token; persisted so neither a restart nor a bucket eviction resets it. */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
}
