package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.infrastructure.util.ClockProvider;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "login_attempts", schema = "main")
public class LoginAttempt extends BaseEntity {

    @Version
    private Long version;

    @NotNull
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String identifier;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    /**
     * V171: SHA-256 of the lower-cased login email alone (no IP). {@link #identifier} is
     * SHA-256(email|ip) and deliberately not reversible to an email, so without this column an admin
     * could never find (and clear) one account's attempts across every IP —
     * {@code AuthService.clearLoginRateLimit}. Nullable: rows written before V171 carry none.
     */
    @Size(max = 64)
    @Column(name = "subject_hash", length = 64)
    private String subjectHash;

    public LoginAttempt() {}

    public LoginAttempt(String identifier, Instant attemptedAt) {
        this.identifier = identifier;
        this.attemptedAt = attemptedAt;
    }

    public LoginAttempt(String identifier, String subjectHash, Instant attemptedAt) {
        this(identifier, attemptedAt);
        this.subjectHash = subjectHash;
    }

    @PrePersist
    void onPrePersist() {
        if (attemptedAt == null) {
            attemptedAt = Instant.now(ClockProvider.getClock());
        }
    }

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    public String getSubjectHash() { return subjectHash; }
    public void setSubjectHash(String subjectHash) { this.subjectHash = subjectHash; }

    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
}
