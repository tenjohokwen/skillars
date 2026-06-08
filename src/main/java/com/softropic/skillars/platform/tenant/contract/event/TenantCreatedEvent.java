package com.softropic.skillars.platform.tenant.contract.event;

/**
 * Domain event fired when a new tenant is created.
 *
 * <p>Sent only to {@code skillars.platform.notification-email} — the tenant's email
 * is not included in the recipient list since the tenant does not yet have a
 * confirmed email address at creation time.
 *
 * @param tenantName  display name of the new tenant
 * @param tenantEmail email provided at creation (nullable)
 * @param environment the initial API key environment (e.g. {@code "PROD"})
 * @param occurredAt  when the tenant was created
 */
public record TenantCreatedEvent(
        String tenantName,
        String tenantEmail,
        String environment,
        java.time.Instant occurredAt
) {}
