package com.softropic.skillars.platform.security.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One hashed identifier observed for one user (V219). Written only by the native upsert in
 * {@link UserIdentitySignalRepository}; the entity exists for the JPA repository and for reads.
 */
@Entity
@Table(schema = "main", name = "user_identity_signals")
@Getter
@NoArgsConstructor
public class UserIdentitySignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signal_id", insertable = false, updatable = false)
    private Long signalId;

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private Long userId;

    @Column(name = "kind", nullable = false, length = 16, insertable = false, updatable = false)
    private String kind;

    @Column(name = "value_hash", nullable = false, length = 64, insertable = false, updatable = false)
    private String valueHash;

    @Column(name = "first_seen", nullable = false, insertable = false, updatable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false, insertable = false, updatable = false)
    private Instant lastSeen;
}
