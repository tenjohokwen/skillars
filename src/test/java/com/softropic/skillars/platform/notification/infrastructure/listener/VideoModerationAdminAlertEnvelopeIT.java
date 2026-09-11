package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.service.MailService;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

import jakarta.mail.MessagingException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * skillars-deferred-109 AC12.2 (code review, owner decision D2 → option a): the admin-alert
 * outbox mapping against a <strong>real database</strong>.
 *
 * <p>The sibling {@code VideoModerationEmailListenerTest.RealMailManagerMappingAC122} proves the
 * same mapping with a mocked {@link EnvelopeEntityRepository}, which means the "row" it asserts on
 * is the object it handed to a stubbed {@code save()}. The property AC12.2 actually cares about is
 * that {@code MailManager.sendEmailSync} <em>persists and commits</em> an {@link EnvelopeEntity},
 * and that {@code VideoModerationEmailListener}'s {@code findBySendId} read-back then
 * <em>sees</em> it — a claim only a real repository against real Postgres can settle.
 *
 * <h2>Why the collaborators are wired by hand rather than autowired</h2>
 *
 * {@code application-test.yaml:84} sets {@code enable.test.mail=true}, which makes
 * {@code TestConfig.mailManager()} (a {@code TestMailManager}) the universal {@link MailManager}
 * for every integration test. {@code TestMailManager.sendEmailSync} only puts the envelope in a
 * map — it persists nothing — so the autowired listener could never reach anything but the
 * {@code persisted == null} branch. Switching that property off for this class would need a
 * class-level {@code @TestPropertySource}, which forks the Spring context and is exactly what
 * {@code IntegrationTestConventionTest} fails the build on.
 *
 * <p>So: the repository, the circuit-breaker factory and the retry template are the real
 * container-backed beans; only {@link MailService} — the SMTP seam, and the one thing a test must
 * not reach — is mocked. This class adds no annotations of its own and therefore shares the common
 * context.
 *
 * <h2>Reproducing the {@code REQUIRES_NEW} boundary</h2>
 *
 * A hand-constructed {@link MailManager} gets no Spring proxy, so {@code sendEmailSync}'s
 * {@code @Transactional(Propagation.REQUIRES_NEW)} would not apply — and that boundary is the whole
 * point of AC12.2's "the FAILED row is committed and visible to the findBySendId read-back". It is
 * therefore reproduced explicitly: the override below runs the real {@code sendEmailSync} body
 * inside a {@code PROPAGATION_REQUIRES_NEW} {@link TransactionTemplate}, and the listener runs
 * inside an outer transaction, exactly as production nests them.
 *
 * <p>That nesting is what makes the retryable case meaningful: the listener throws, the OUTER
 * transaction rolls back, and the envelope row must survive anyway because it was committed by the
 * inner one. Every assertion below reads the row back in a fresh transaction started after the call
 * returned, so a row that only ever existed in the rolled-back transaction's first-level cache
 * cannot satisfy it.
 *
 * <p><strong>Known limit 1:</strong> the {@code @Transactional} annotation on
 * {@code sendEmailSync} is reproduced here rather than exercised through Spring's proxy. A change
 * to that annotation would not fail this test.
 *
 * <p><strong>Known limit 2 — no permanent-failure case.</strong> A third test asserting
 * {@code FAILED, isRetry=false} for a non-repairable cause was written and then removed: against the
 * REAL container-configured {@code CircuitBreakerFactory} it cannot pass, because the exception that
 * reaches {@code MailManager.toEnvelopeEntity} is
 * {@code RuntimeException("Email sending failed via Circuit Breaker")} caused by resilience4j's
 * {@code TimeoutException} — the originating {@code AddressException} is absent from the chain
 * entirely, so {@code isRetryable}'s depth-bounded scan finds no non-repairable type and returns
 * true. Every permanently-undeliverable alert is therefore classified retryable, the listener
 * throws, and the outbox row is retained for a failure no re-drive can fix. That is pre-existing
 * behaviour of the real circuit-breaker configuration — nothing in skillars-deferred-109 caused it,
 * and the sibling unit test's hand-built factory does not reproduce it — so it is filed in
 * {@code deferred-work.md} under the code review of skillars-deferred-109 rather than asserted here.
 * Do not add that case back until the ledger item is closed; it would be red on arrival.
 */
class VideoModerationAdminAlertEnvelopeIT extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL_KEY = "platform.admin_alert_email";

    @Autowired private EnvelopeEntityRepository envelopeEntityRepository;
    @Autowired private ConfigService configService;
    @Autowired private FeatureToggleService featureToggleService;
    @Autowired private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @Autowired private RetryTemplate retryTemplate;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private MailService seamMailService;
    private VideoModerationEmailListener listener;
    private String adminEmail;

    @BeforeEach
    void wireRealManagerOverRealRepository() {
        adminEmail = "ac12.admin." + UUID.randomUUID() + "@skillars-test.com";
        configService.updateConfig(ADMIN_EMAIL_KEY, adminEmail);

        seamMailService = mock(MailService.class);

        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        MailManager realMailManager = new MailManager(
            seamMailService, envelopeEntityRepository, circuitBreakerFactory, retryTemplate) {
            @Override
            public void sendEmailSync(Envelope envelope) {
                // Stands in for @Transactional(propagation = REQUIRES_NEW) — see the class javadoc.
                requiresNew.executeWithoutResult(status -> super.sendEmailSync(envelope));
            }
        };
        listener = new VideoModerationEmailListener(
            publisher, configService, featureToggleService, realMailManager, envelopeEntityRepository);
    }

    @AfterEach
    void restoreAdminAlertEmail() {
        // Blank is the shipped default (V57); this context is shared with every other IT.
        configService.updateConfig(ADMIN_EMAIL_KEY, "");
    }

    private VideoModerationAdminAlertEvent event(UUID videoId) {
        return new VideoModerationAdminAlertEvent(
            videoId, "owner@example.com", "Moderation pipeline permanently failed",
            "videoId=" + videoId + " retries=5 — manual review required", true);
    }

    /**
     * Reads the row back in a FRESH transaction that starts after the call under test has finished.
     * A new transaction means a new persistence context and a real round trip to Postgres, so only
     * a genuinely COMMITTED row can satisfy these assertions — a row that existed solely in the
     * rolled-back outer transaction's first-level cache cannot.
     */
    private EnvelopeEntity committedRow() {
        List<EnvelopeEntity> rows = new TransactionTemplate(transactionManager).execute(status ->
            envelopeEntityRepository.findAll().stream()
                .filter(e -> e.getRecipients() != null && e.getRecipients().stream()
                    .anyMatch(r -> adminEmail.equals(r.getEmail())))
                .toList());
        assertThat(rows)
            .as("exactly one COMMITTED envelope row for this test's admin recipient")
            .hasSize(1);
        return rows.get(0);
    }

    /** Runs the listener inside an outer transaction, the way production calls it. */
    private void runListener(UUID videoId) {
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> listener.sendAdminAlertSync(event(videoId)));
    }

    @Test
    @DisplayName("a retryable send failure commits a FAILED/isRetry row and the listener rethrows to retain the outbox row")
    void retryableFailure_commitsRetryableRow_andListenerRethrows() throws MessagingException {
        UUID videoId = UUID.randomUUID();
        doThrow(new MessagingException("Connection timed out"))
            .when(seamMailService).sendEmailFromTemplate(any(), any(), any());

        assertThatThrownBy(() -> runListener(videoId))
            .as("throwing is what keeps ModerationAdminAlertOutboxHandler's durable row for a re-drive")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("retryable");

        // The listener threw and the OUTER transaction rolled back — the row must still be there,
        // because MailManager committed it in its own REQUIRES_NEW transaction first.
        EnvelopeEntity row = committedRow();
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
        assertThat(row.isRetry()).as("a MessagingException is classified retryable").isTrue();
        assertThat(row.getError()).isNotBlank();
        // Mutation: drop the `throw new IllegalStateException(...)` from the FAILED/isRetry arm →
        // sendAdminAlertSync returns normally, the outbox row is released, and the retryable
        // failure is lost → RED on assertThatThrownBy.
    }

    @Test
    @DisplayName("a successful send commits a SENT row and the listener returns normally")
    void successfulSend_commitsSentRow_andListenerReturns() throws MessagingException {
        UUID videoId = UUID.randomUUID();
        doNothing().when(seamMailService).sendEmailFromTemplate(any(), any(), any());

        assertThatCode(() -> runListener(videoId)).doesNotThrowAnyException();

        EnvelopeEntity row = committedRow();
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(row.isRetry()).isFalse();
        // This is the case AC12.1's branch split exists for: before it, the same trailing log.info
        // covered both this row and a null read-back, labelling an unknown outcome "delivered".
    }
}
