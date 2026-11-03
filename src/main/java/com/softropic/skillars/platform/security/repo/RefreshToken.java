package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.infrastructure.util.ClockProvider;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "refresh_tokens", schema = "main")
public class RefreshToken extends BaseEntity {

    @Version
    private Long version;

    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Size(min = 64, max = 64)
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now(ClockProvider.getClock());
        }
    }

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /**
     * skillars-deferred-166 (RS01/RS02): the id of the row this token was rotated into, written by
     * {@code AuthService.refresh} in the same transaction as the rotation. {@code NULL} when the token was
     * never rotated, or was rotated before V224 added the column.
     *
     * <p>A plain {@code Long}, not a {@code @ManyToOne} — deliberately, matching {@link #userId}'s style in
     * this entity: every reader walks it explicitly by primary key under a row lock, and a mapped
     * association would invite a lazy load on a detached row (the service builds everything it needs
     * inside its transaction; {@code spring.jpa.open-in-view} is {@code false}).
     */
    @Column(name = "replaced_by_id")
    private Long replacedById;

    /**
     * skillars-deferred-166: when this session was deliberately ENDED (logout, forced logout, a refresh
     * denial), as opposed to merely rotated. {@link #used} is set by both rotation and revocation, so it
     * cannot tell them apart on its own; this column is what does.
     *
     * <p>{@code used == true && revokedAt == null} is a rotated token: the refresh grace window may
     * redirect a stale replay of it to its successor. {@code revokedAt != null} is an ended session:
     * refresh answers 401 and, deliberately, does <em>not</em> run account-wide theft revocation, so a
     * stale tab replaying a logged-out token cannot sign the user out of their other devices.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;


    /**
     * skillars-deferred-167 (AC2): identity of the rotation CHAIN this row belongs to. Minted by
     * {@code AuthService.login}, copied onto every successor by {@code AuthService.decideAndRotate}, and
     * carried in the access token as a claim so {@code JWTAuthorizationFilter}'s DB-reauth branch can
     * refuse a JWT whose chain has no live row left — per-session revocation that, unlike
     * {@code security_session_invalidated_at}, does not reach the account's other devices (which
     * skillars-deferred-166 AC3 forbids).
     *
     * <p>{@code NULL} on every row written before V225, which added the column with no backfill. A null id
     * means "unknown" and must never match: readers pass it as a query argument only when it is non-null,
     * because {@code session_id = NULL} matches no row and would turn AC5's deliberate fail-open skip into
     * a rejection. A rotation of a row whose id is null mints a FRESH id for the successor rather than
     * copying the null, so a pre-V225 chain acquires one on its next rotation instead of staying unmatched
     * for the rest of its life.
     *
     * <p><strong>Not a credential.</strong> It identifies a chain, it is readable in any JWT that carries
     * it, and nothing is ever authorised by possessing it: the only question ever asked of it is whether
     * the chain still has a live token.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }

    public Long getReplacedById() { return replacedById; }
    public void setReplacedById(Long replacedById) { this.replacedById = replacedById; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    /**
     * skillars-deferred-166: true when this row was rotated into a successor and not since revoked — the
     * one state in which the refresh grace window may redirect a stale replay. See {@link #revokedAt}.
     */
    public boolean isRotatedNotRevoked() {
        return used && revokedAt == null && rotatedAt != null;
    }
}
