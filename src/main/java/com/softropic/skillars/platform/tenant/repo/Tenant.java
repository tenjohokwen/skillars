package com.softropic.skillars.platform.tenant.repo;

import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.platform.tenant.contract.TenantStatus;

import org.hibernate.envers.Audited;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.experimental.SuperBuilder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;


@Audited
@Entity
@Table(name = "tenant", schema = "main")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant extends AbstractAuditingEntity {

    @Column(name = "tenant_ref", unique = true, nullable = false, updatable = false)
    private String tenantRef;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_status", nullable = false)
    @Builder.Default
    private TenantStatus tenantStatus = TenantStatus.ACTIVE;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TenantApiKey> apiKeys = new ArrayList<>();

    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "key_prefix", nullable = false, updatable = false, length = 4)
    private String keyPrefix;

    @Column(name = "email")
    private String email;

    public String getTenantRef() {
        return tenantRef;
    }

    public void setTenantRef(String tenantRef) {
        this.tenantRef = tenantRef;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TenantStatus getTenantStatus() {
        return tenantStatus;
    }

    public void setTenantStatus(TenantStatus tenantStatus) {
        this.tenantStatus = tenantStatus;
    }

    public List<TenantApiKey> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<TenantApiKey> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getKeyPrefix() { return keyPrefix; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
