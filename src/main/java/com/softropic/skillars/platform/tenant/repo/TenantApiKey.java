package com.softropic.skillars.platform.tenant.repo;

import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;

import org.hibernate.envers.Audited;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.experimental.SuperBuilder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;


@Audited
@Entity
@Table(name = "tenant_api_key", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TenantApiKey extends AbstractAuditingEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 8, updatable = false)
    private String keyPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_status", nullable = false)
    @Builder.Default
    private ApiKeyStatus keyStatus = ApiKeyStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 10)
    @Builder.Default
    private ApiKeyEnvironment environment = ApiKeyEnvironment.PROD;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Version
    private long version;

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public ApiKeyStatus getKeyStatus() {
        return keyStatus;
    }

    public void setKeyStatus(ApiKeyStatus keyStatus) {
        this.keyStatus = keyStatus;
    }

    public ApiKeyEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(ApiKeyEnvironment environment) {
        this.environment = environment;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public void setRotatedAt(Instant rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
