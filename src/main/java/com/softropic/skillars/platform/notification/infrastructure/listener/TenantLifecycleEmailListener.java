package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.platform.tenant.contract.event.TenantApiKeyEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantCreatedEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantStatusChangedEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantWebhookSecretRegeneratedEvent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
@Component
public class TenantLifecycleEmailListener {

    private final ApplicationEventPublisher publisher;
    private final String notificationEmail;

    public TenantLifecycleEmailListener(ApplicationEventPublisher publisher,
                                        @Value("${skillars.platform.notification-email}") String notificationEmail) {
        this.publisher = publisher;
        this.notificationEmail = notificationEmail;
    }

    @Transactional
    @EventListener
    public void onTenantCreated(TenantCreatedEvent event) {
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());

        final Map<String, Object> data = new HashMap<>();
        data.put("tenantName", event.tenantName());
        data.put("environment", event.environment());
        data.put("createdAt", event.occurredAt().toString());
        data.put("helpCode", helpCode);

        // Admin-only: sent only to notification-email, not to the tenant
        final List<Recipient> recipients = new ArrayList<>();
        final Recipient admin = new Recipient();
        admin.setEmail(notificationEmail);
        admin.setLangKey("en");
        recipients.add(admin);

        final Envelope envelope = new Envelope(
            recipients,
            EmailTemplate.TENANT_CREATED,
            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
            data,
            helpCode
        );

        publisher.publishEvent(envelope);

        log.info("Dispatching tenant created notification email",
            kv("tenantName", event.tenantName()),
            kv("event", "tenant_created_email_dispatched"));
    }

    @Transactional
    @EventListener
    public void onApiKeyEvent(TenantApiKeyEvent event) {
        final EmailTemplate template = mapActionToTemplate(event.action());
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        final String timestampKey = mapActionToTimestampKey(event.action());

        final Map<String, Object> data = new HashMap<>();
        data.put("tenantName", event.tenantName());
        data.put("keyPrefix", event.keyPrefix());
        data.put("environment", event.environment());
        data.put(timestampKey, event.occurredAt().toString());
        data.put("helpCode", helpCode);

        final List<Recipient> recipients = buildRecipients(event.tenantEmail());

        final Envelope envelope = new Envelope(
            recipients,
            template,
            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
            data,
            helpCode
        );

        publisher.publishEvent(envelope);

        log.info("Dispatching tenant API key notification email",
            kv("action", event.action()),
            kv("keyPrefix", event.keyPrefix()),
            kv("event", "tenant_api_key_email_dispatched"));
    }

    @Transactional
    @EventListener
    public void onStatusChanged(TenantStatusChangedEvent event) {
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());

        final Map<String, Object> data = new HashMap<>();
        data.put("tenantName", event.tenantName());
        data.put("eventType", event.eventType().name());
        data.put("changedAt", event.occurredAt().toString());
        data.put("helpCode", helpCode);
        if (event.oldValue() != null) {
            data.put("oldValue", event.oldValue());
        }
        if (event.newValue() != null) {
            data.put("newValue", event.newValue());
        }

        final List<Recipient> recipients;
        if (event.eventType() == TenantStatusChangedEvent.EventType.EMAIL_CHANGED) {
            // D-03: route to old address only + admin
            recipients = buildRecipients(event.oldValue());
        } else {
            recipients = buildRecipients(event.tenantEmail());
        }

        final Envelope envelope = new Envelope(
            recipients,
            EmailTemplate.TENANT_STATUS_CHANGED,
            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
            data,
            helpCode
        );

        publisher.publishEvent(envelope);

        log.info("Dispatching tenant status change notification email",
            kv("eventType", event.eventType()),
            kv("event", "tenant_status_change_email_dispatched"));
    }

    @Transactional
    @EventListener
    public void onWebhookSecretRegenerated(TenantWebhookSecretRegeneratedEvent event) {
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());

        final Map<String, Object> data = new HashMap<>();
        data.put("tenantName", event.tenantName());
        data.put("regeneratedAt", event.occurredAt().toString());
        data.put("helpCode", helpCode);
        // Security constraint: no secret value in email body

        final List<Recipient> recipients = buildRecipients(event.tenantEmail());

        final Envelope envelope = new Envelope(
            recipients,
            EmailTemplate.TENANT_WEBHOOK_SECRET_REGENERATED,
            Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
            data,
            helpCode
        );

        publisher.publishEvent(envelope);

        log.info("Dispatching webhook secret regenerated notification email",
            kv("event", "tenant_webhook_secret_email_dispatched"));
    }

    private List<Recipient> buildRecipients(String tenantEmail) {
        final List<Recipient> recipients = new ArrayList<>();

        final Recipient admin = new Recipient();
        admin.setEmail(notificationEmail);
        admin.setLangKey("en");
        recipients.add(admin);

        if (tenantEmail != null) {
            final Recipient tenant = new Recipient();
            tenant.setEmail(tenantEmail);
            tenant.setLangKey("en");
            recipients.add(tenant);
        }

        return recipients;
    }

    private EmailTemplate mapActionToTemplate(TenantApiKeyEvent.Action action) {
        return switch (action) {
            case GENERATED -> EmailTemplate.TENANT_API_KEY_GENERATED;
            case ROTATED -> EmailTemplate.TENANT_API_KEY_ROTATED;
            case REVOKED -> EmailTemplate.TENANT_API_KEY_REVOKED;
            case REACTIVATED -> EmailTemplate.TENANT_API_KEY_REACTIVATED;
        };
    }

    private String mapActionToTimestampKey(TenantApiKeyEvent.Action action) {
        return switch (action) {
            case GENERATED -> "generatedAt";
            case ROTATED -> "rotatedAt";
            case REVOKED -> "revokedAt";
            case REACTIVATED -> "reactivatedAt";
        };
    }
}
