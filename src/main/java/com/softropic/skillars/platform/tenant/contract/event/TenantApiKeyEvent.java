package com.softropic.skillars.platform.tenant.contract.event;

/**
 * Domain event for API key lifecycle changes: generated, rotated, revoked, reactivated.
 *
 * <p>This is a plain record (POJO event) — Spring 4.2+ supports non-ApplicationEvent events.
 *
 * @param tenantName  display name of the tenant
 * @param tenantEmail email address of the tenant (nullable — listener handles null)
 * @param keyPrefix   the key prefix (e.g. {@code prod_abc123}), never the raw key
 * @param environment {@code "PROD"} or {@code "DEV"}
 * @param action      the lifecycle action that occurred
 * @param occurredAt  when the event occurred
 */
public record TenantApiKeyEvent(
        String tenantName,
        String tenantEmail,
        String keyPrefix,
        String environment,
        Action action,
        java.time.Instant occurredAt
) {
    public enum Action { GENERATED, ROTATED, REVOKED, REACTIVATED }
}
