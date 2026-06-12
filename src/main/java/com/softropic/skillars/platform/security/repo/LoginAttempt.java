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

    public LoginAttempt() {}

    public LoginAttempt(String identifier, Instant attemptedAt) {
        this.identifier = identifier;
        this.attemptedAt = attemptedAt;
    }

    @PrePersist
    void onPrePersist() {
        if (attemptedAt == null) {
            attemptedAt = Instant.now(ClockProvider.getClock());
        }
    }

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    public Instant getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(Instant attemptedAt) { this.attemptedAt = attemptedAt; }
}
