package com.softropic.skillars.platform.tenant.contract.event;

/**
 * Domain event for tenant status/profile changes: suspended, reactivated, email changed,
 * webhook URL changed.
 *
 * <p>This is a plain record (POJO event) — Spring 4.2+ supports non-ApplicationEvent events.
 *
 * @param tenantName  display name of the tenant
 * @param tenantEmail email address of the tenant (nullable — listener handles null)
 * @param eventType   the type of status or profile change
 * @param occurredAt  when the event occurred
 * @param oldValue    present for {@code EMAIL_CHANGED} (old email) and
 *                    {@code WEBHOOK_URL_CHANGED} (old URL); null otherwise
 * @param newValue    present for {@code WEBHOOK_URL_CHANGED} (new URL) only;
 *                    null for {@code EMAIL_CHANGED} (old address only per NOTIF-06)
 */
public record TenantStatusChangedEvent(
        String tenantName,
        String tenantEmail,
        EventType eventType,
        java.time.Instant occurredAt,
        String oldValue,
        String newValue
) {
    public enum EventType { SUSPENDED, REACTIVATED, EMAIL_CHANGED, WEBHOOK_URL_CHANGED }
}
