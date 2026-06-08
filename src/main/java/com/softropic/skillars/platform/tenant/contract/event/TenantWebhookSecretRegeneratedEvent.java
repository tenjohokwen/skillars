package com.softropic.skillars.platform.tenant.contract.event;

/**
 * Domain event for webhook secret regeneration.
 *
 * <p>Security constraint: no secret value in the event. The new secret is retrievable only
 * through the admin portal.
 *
 * <p>This is a plain record (POJO event) — Spring 4.2+ supports non-ApplicationEvent events.
 *
 * @param tenantName  display name of the tenant
 * @param tenantEmail email address of the tenant (nullable — listener handles null)
 * @param occurredAt  when the regeneration occurred
 */
public record TenantWebhookSecretRegeneratedEvent(
        String tenantName,
        String tenantEmail,
        java.time.Instant occurredAt
) {}
