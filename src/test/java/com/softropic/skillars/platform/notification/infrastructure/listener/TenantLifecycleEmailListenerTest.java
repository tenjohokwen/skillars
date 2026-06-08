package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.tenant.contract.event.TenantApiKeyEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantStatusChangedEvent;
import com.softropic.skillars.platform.tenant.contract.event.TenantWebhookSecretRegeneratedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TenantLifecycleEmailListenerTest {

    @Mock
    private ApplicationEventPublisher publisher;

    @Captor
    private ArgumentCaptor<Envelope> envelopeCaptor;

    private TenantLifecycleEmailListener listener;

    private static final String ADMIN_EMAIL = "admin@skillars.test";
    private static final String TENANT_EMAIL = "tenant@acme.com";
    private static final String TENANT_NAME = "Acme Corp";
    private static final String KEY_PREFIX = "ACM";
    private static final String ENVIRONMENT = "PROD";

    @BeforeEach
    void setUp() {
        listener = new TenantLifecycleEmailListener(publisher, ADMIN_EMAIL);
    }

    @Test
    void onApiKeyEvent_generated_createsCorrectEnvelope() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, TENANT_EMAIL, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.GENERATED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_API_KEY_GENERATED);
        assertThat(envelope.recipients()).hasSize(2);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(envelope.recipients().get(1).getEmail()).isEqualTo(TENANT_EMAIL);
        assertThat(envelope.data()).containsKey("tenantName");
        assertThat(envelope.data()).containsKey("keyPrefix");
        assertThat(envelope.data()).containsKey("environment");
        assertThat(envelope.data()).containsKey("generatedAt");
        assertThat(envelope.data()).containsKey("helpCode");
        assertThat(envelope.data().get("tenantName")).isEqualTo(TENANT_NAME);
        assertThat(envelope.data().get("keyPrefix")).isEqualTo(KEY_PREFIX);
        assertThat(envelope.data().get("environment")).isEqualTo(ENVIRONMENT);
        assertThat(envelope.sendId()).isNotNull().isNotEmpty();
    }

    @Test
    void onApiKeyEvent_rotated_createsCorrectTemplate() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, TENANT_EMAIL, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.ROTATED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_API_KEY_ROTATED);
        assertThat(envelope.data()).containsKey("rotatedAt");
        assertThat(envelope.data()).doesNotContainKey("generatedAt");
    }

    @Test
    void onApiKeyEvent_revoked_createsCorrectTemplate() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, TENANT_EMAIL, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.REVOKED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_API_KEY_REVOKED);
        assertThat(envelope.data()).containsKey("revokedAt");
    }

    @Test
    void onApiKeyEvent_reactivated_createsCorrectTemplate() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, TENANT_EMAIL, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.REACTIVATED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_API_KEY_REACTIVATED);
        assertThat(envelope.data()).containsKey("reactivatedAt");
    }

    @Test
    void onApiKeyEvent_nullTenantEmail_adminOnlyNoException() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, null, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.GENERATED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.recipients()).hasSize(1);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void onStatusChanged_suspended_createsCorrectEnvelope() {
        TenantStatusChangedEvent event = new TenantStatusChangedEvent(
            TENANT_NAME, TENANT_EMAIL, TenantStatusChangedEvent.EventType.SUSPENDED,
            Instant.now(), null, null
        );

        listener.onStatusChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_STATUS_CHANGED);
        assertThat(envelope.recipients()).hasSize(2);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(envelope.recipients().get(1).getEmail()).isEqualTo(TENANT_EMAIL);
        assertThat(envelope.data().get("eventType")).isEqualTo("SUSPENDED");
        assertThat(envelope.data()).containsKey("changedAt");
        assertThat(envelope.data()).containsKey("helpCode");
    }

    @Test
    void onStatusChanged_reactivated_createsCorrectEnvelope() {
        TenantStatusChangedEvent event = new TenantStatusChangedEvent(
            TENANT_NAME, TENANT_EMAIL, TenantStatusChangedEvent.EventType.REACTIVATED,
            Instant.now(), null, null
        );

        listener.onStatusChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_STATUS_CHANGED);
        assertThat(envelope.data().get("eventType")).isEqualTo("REACTIVATED");
        assertThat(envelope.recipients()).hasSize(2);
    }

    @Test
    void onStatusChanged_emailChanged_routesToOldAddress() {
        final String oldEmail = "old@example.com";
        TenantStatusChangedEvent event = new TenantStatusChangedEvent(
            TENANT_NAME, oldEmail, TenantStatusChangedEvent.EventType.EMAIL_CHANGED,
            Instant.now(), oldEmail, null
        );

        listener.onStatusChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.recipients()).hasSize(2);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
        // Must route to old address, not any new address
        assertThat(envelope.recipients().get(1).getEmail()).isEqualTo(oldEmail);
        assertThat(envelope.data().get("eventType")).isEqualTo("EMAIL_CHANGED");
        assertThat(envelope.data()).containsKey("oldValue");
    }

    @Test
    void onStatusChanged_emailChanged_nullOldEmail_adminOnly() {
        TenantStatusChangedEvent event = new TenantStatusChangedEvent(
            TENANT_NAME, null, TenantStatusChangedEvent.EventType.EMAIL_CHANGED,
            Instant.now(), null, null
        );

        listener.onStatusChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        // When old email is null, only admin receives notification
        assertThat(envelope.recipients()).hasSize(1);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void onStatusChanged_webhookUrlChanged_includesOldAndNewValues() {
        final String oldUrl = "https://old.example.com/webhook";
        final String newUrl = "https://new.example.com/webhook";
        TenantStatusChangedEvent event = new TenantStatusChangedEvent(
            TENANT_NAME, TENANT_EMAIL, TenantStatusChangedEvent.EventType.WEBHOOK_URL_CHANGED,
            Instant.now(), oldUrl, newUrl
        );

        listener.onStatusChanged(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_STATUS_CHANGED);
        assertThat(envelope.data().get("eventType")).isEqualTo("WEBHOOK_URL_CHANGED");
        assertThat(envelope.data().get("oldValue")).isEqualTo(oldUrl);
        assertThat(envelope.data().get("newValue")).isEqualTo(newUrl);
        assertThat(envelope.recipients()).hasSize(2);
    }

    @Test
    void onWebhookSecretRegenerated_createsCorrectEnvelope_noSecretInData() {
        TenantWebhookSecretRegeneratedEvent event = new TenantWebhookSecretRegeneratedEvent(
            TENANT_NAME, TENANT_EMAIL, Instant.now()
        );

        listener.onWebhookSecretRegenerated(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.emailTemplate()).isEqualTo(EmailTemplate.TENANT_WEBHOOK_SECRET_REGENERATED);
        assertThat(envelope.recipients()).hasSize(2);
        assertThat(envelope.recipients().get(0).getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(envelope.recipients().get(1).getEmail()).isEqualTo(TENANT_EMAIL);
        assertThat(envelope.data()).containsKey("tenantName");
        assertThat(envelope.data()).containsKey("regeneratedAt");
        assertThat(envelope.data()).containsKey("helpCode");
        // Security constraint: no secret value in email data
        assertThat(envelope.data()).doesNotContainKey("secret");
        assertThat(envelope.data()).doesNotContainKey("webhookSecret");
        assertThat(envelope.data()).doesNotContainKey("newSecret");
        assertThat(envelope.sendId()).isNotNull().isNotEmpty();
    }

    @Test
    void allEnvelopes_sendIdIsHelpCode_nonNullAndNonEmpty() {
        TenantApiKeyEvent event = new TenantApiKeyEvent(
            TENANT_NAME, TENANT_EMAIL, KEY_PREFIX, ENVIRONMENT,
            TenantApiKeyEvent.Action.GENERATED, Instant.now()
        );

        listener.onApiKeyEvent(event);

        verify(publisher).publishEvent(envelopeCaptor.capture());
        Envelope envelope = envelopeCaptor.getValue();

        assertThat(envelope.sendId()).isNotNull().isNotEmpty();
        assertThat(envelope.data().get("helpCode")).isEqualTo(envelope.sendId());
    }
}
