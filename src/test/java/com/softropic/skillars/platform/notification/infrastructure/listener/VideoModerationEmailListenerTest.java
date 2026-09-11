package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.EmailTransportTransientException;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.notification.service.MailService;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-93 AC6 — the FAILED-envelope branches of
 * {@link VideoModerationEmailListener#sendAdminAlertSync}, which {@code ModerationOutboxIT} does not
 * reach: it runs against {@code TestMailManager}, which caches the envelope and never writes an
 * {@code EnvelopeEntity}, so {@code status == FAILED} can never be observed there.
 *
 * <p>Producing a real FAILED row in an IT needs either a real {@code MailManager} wired to a
 * throwing {@code MailService}, or an {@code @MockitoBean MailService} — both fork the Spring
 * context and trip {@code IntegrationTestConventionTest}. {@code MailManager}'s own
 * failure-to-{@code EnvelopeEntity} mapping is already pinned by {@code MailManagerResilienceTest};
 * what is untested is the listener's decision <em>from</em> that recorded outcome: rethrow to keep
 * the outbox row when the failure is retryable, log-and-release when it is permanent. That decision
 * is pure branch logic over the persisted entity, so it is exercised here directly.
 */
class VideoModerationEmailListenerTest {

    private static final String ADMIN_EMAIL_KEY = "platform.admin_alert_email";

    private ApplicationEventPublisher publisher;
    private ConfigService configService;
    private FeatureToggleService featureToggleService;
    private MailManager mailManager;
    private EnvelopeEntityRepository envelopeEntityRepository;
    private VideoModerationEmailListener listener;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;
    private Level previousLevel;

    private final UUID videoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        configService = mock(ConfigService.class);
        featureToggleService = mock(FeatureToggleService.class);
        mailManager = mock(MailManager.class);
        envelopeEntityRepository = mock(EnvelopeEntityRepository.class);
        listener = new VideoModerationEmailListener(
            publisher, configService, featureToggleService, mailManager, envelopeEntityRepository);

        when(configService.find(ADMIN_EMAIL_KEY)).thenReturn(Optional.of("admin@skillars-test.com"));

        listenerLogger = (Logger) LoggerFactory.getLogger(VideoModerationEmailListener.class);
        previousLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logAppender);
        listenerLogger.setLevel(previousLevel);
    }

    private VideoModerationAdminAlertEvent event() {
        return new VideoModerationAdminAlertEvent(
            videoId, "owner@example.com", "Moderation pipeline permanently failed",
            "videoId=" + videoId + " retries=5 — manual review required", true);
    }

    private EnvelopeEntity persisted(EmailDeliveryStatus status, boolean retry) {
        EnvelopeEntity entity = new EnvelopeEntity();
        entity.setStatus(status);
        entity.setRetry(retry);
        return entity;
    }

    private boolean loggedAt(Level level, String marker) {
        return logAppender.list.stream()
            .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(marker));
    }

    @Test
    @DisplayName("a retryable FAILED send rethrows so the outbox row is kept for the next cycle")
    void retryableFailure_rethrows() {
        when(envelopeEntityRepository.findBySendId(anyString()))
            .thenReturn(persisted(EmailDeliveryStatus.FAILED, true));

        assertThatThrownBy(() -> listener.sendAdminAlertSync(event()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("retryable")
            .hasMessageContaining(videoId.toString());

        verify(mailManager).sendEmailSync(any(Envelope.class));
        assertThat(loggedAt(Level.ERROR, "[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]"))
            .as("a retryable failure is not the permanent-undeliverable path")
            .isFalse();
    }

    @Test
    @DisplayName("a permanent FAILED send logs UNDELIVERABLE and returns so the row is released")
    void permanentFailure_logsAndReturns() {
        when(envelopeEntityRepository.findBySendId(anyString()))
            .thenReturn(persisted(EmailDeliveryStatus.FAILED, false));

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        verify(mailManager).sendEmailSync(any(Envelope.class));
        assertThat(loggedAt(Level.ERROR, "[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]"))
            .as("the only observable of the permanent-failure path is this ERROR line")
            .isTrue();
    }

    @Test
    @DisplayName("a SENT envelope logs delivery at INFO and does NOT warn (deferred-109 AC12.1)")
    void sentEnvelope_logsDelivered() {
        when(envelopeEntityRepository.findBySendId(anyString()))
            .thenReturn(persisted(EmailDeliveryStatus.SENT, false));

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        assertThat(loggedAt(Level.INFO, "[VIDEO_MODERATION_ADMIN_ALERT] delivered")).isTrue();
        assertThat(loggedAt(Level.WARN, "[VIDEO_MODERATION_ADMIN_ALERT]"))
            .as("a genuinely SENT envelope must NOT be labelled 'not yet visible'")
            .isFalse();
        assertThat(loggedAt(Level.ERROR, "[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]")).isFalse();
        // Mutation: merge the null/SENT branches into one log.warn → this assertion goes RED.
    }

    @Test
    @DisplayName("no persisted envelope on read-back is WARNed as not-yet-visible, not logged as delivered (deferred-109 AC12.1)")
    void noPersistedEnvelope_warnsNotYetVisible() {
        when(envelopeEntityRepository.findBySendId(anyString())).thenReturn(null);

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        assertThat(loggedAt(Level.WARN, "[VIDEO_MODERATION_ADMIN_ALERT] send outcome not yet visible")).isTrue();
        assertThat(loggedAt(Level.INFO, "[VIDEO_MODERATION_ADMIN_ALERT] delivered"))
            .as("a missing row is not a delivery")
            .isFalse();
        // Mutation: merge the null/SENT branches into one log.info → this assertion goes RED.
    }

    @Test
    @DisplayName("a blank admin_alert_email short-circuits before any send and does not throw")
    void blankRecipient_returnsWithoutSending() {
        when(configService.find(ADMIN_EMAIL_KEY)).thenReturn(Optional.of(""));

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        verify(mailManager, never()).sendEmailSync(any());
        verify(envelopeEntityRepository, never()).findBySendId(anyString());
    }

    /**
     * skillars-deferred-109 AC12.2 — the real {@code Exception → EnvelopeEntity(FAILED, isRetry)}
     * mapping, driven end to end through a REAL {@link MailManager} (real Resilience4J circuit
     * breaker + real {@link RetryTemplate}), so the listener's decision is taken from a genuinely
     * produced FAILED row rather than a hand-stubbed entity.
     *
     * <p><strong>Seam.</strong> Story ses-1.2 moved {@code MailService} onto the {@code
     * OutboundEmailSender} port — a real {@code SmtpEmailSender}/{@code MailSenderProvider} would
     * need a real socket, and there is no Spring context here to mock the port bean into. So this
     * stubs {@link MailService} itself (the seam the AC allows) and wires a real {@code MailManager}
     * over it.
     *
     * <p><strong>Scope.</strong> This covers the <em>send-mapping + listener decision</em> seam:
     * retryable failure → {@code FAILED, isRetry=true} → {@code sendAdminAlertSync} rethrows;
     * permanent failure → {@code FAILED, isRetry=false} → it logs {@code UNDELIVERABLE} and returns.
     * It does <em>not</em> cover the retain-vs-delete of the durable outbox row — that lives with
     * {@code ModerationAdminAlertOutboxHandler} and is exercised by {@code ModerationOutboxIT}.
     *
     * <p>A fresh {@code Resilience4JCircuitBreakerFactory} is built per test method (via
     * {@code @BeforeEach}), so the {@code "emailService"} breaker never carries state between cases.
     */
    @Nested
    class RealMailManagerMappingAC122 {

        private MailService seamMailService;
        private EnvelopeEntityRepository repo;
        private MailManager realMailManager;
        private VideoModerationEmailListener listenerOverRealManager;
        private final AtomicReference<EnvelopeEntity> saved = new AtomicReference<>();

        @BeforeEach
        void wireRealManager() {
            seamMailService = mock(MailService.class);
            repo = mock(EnvelopeEntityRepository.class);
            saved.set(null);
            // MailManager.sendEmailSync: findBySendId==null → save(entity); the listener then
            // read-backs the same sendId and must see that saved entity.
            when(repo.save(any(EnvelopeEntity.class))).thenAnswer(inv -> {
                saved.set(inv.getArgument(0));
                return inv.getArgument(0);
            });
            when(repo.findBySendId(anyString())).thenAnswer(inv -> saved.get());

            Resilience4JCircuitBreakerFactory cbFactory = new Resilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                    .slidingWindowSize(10).minimumNumberOfCalls(10).build()),
                TimeLimiterRegistry.ofDefaults(), null);
            RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(2).fixedBackoff(Duration.ofMillis(1)).build();

            realMailManager = new MailManager(seamMailService, repo, cbFactory, retryTemplate);

            var cfg = mock(ConfigService.class);
            when(cfg.find(ADMIN_EMAIL_KEY)).thenReturn(Optional.of("admin@skillars-test.com"));
            listenerOverRealManager = new VideoModerationEmailListener(
                mock(ApplicationEventPublisher.class), cfg, mock(FeatureToggleService.class),
                realMailManager, repo);
        }

        @Test
        @DisplayName("a retryable send failure → FAILED,isRetry=true row → sendAdminAlertSync rethrows")
        void retryableFailure_producesRetryableRow_andListenerRethrows() {
            doThrow(new EmailTransportTransientException("Connection timed out"))
                .when(seamMailService).sendEmailFromTemplate(any(), any(), any());

            assertThatThrownBy(() -> listenerOverRealManager.sendAdminAlertSync(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retryable");

            assertThat(saved.get()).isNotNull();
            assertThat(saved.get().getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
            assertThat(saved.get().isRetry()).as("a transient SMTP error is retryable").isTrue();
        }

        @Test
        @DisplayName("a permanent send failure → FAILED,isRetry=false row → sendAdminAlertSync logs UNDELIVERABLE and returns")
        void permanentFailure_producesNonRetryableRow_andListenerReleases() {
            doThrow(new EmailTransportPermanentException("bad template"))
                .when(seamMailService).sendEmailFromTemplate(any(), any(), any());

            assertThatCode(() -> listenerOverRealManager.sendAdminAlertSync(event()))
                .doesNotThrowAnyException();

            assertThat(saved.get()).isNotNull();
            assertThat(saved.get().getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
            assertThat(saved.get().isRetry())
                .as("an EmailTransportPermanentException is NON_REPAIRABLE → not retryable")
                .isFalse();
            assertThat(loggedAt(Level.ERROR, "[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]")).isTrue();
        }

        @Test
        @DisplayName("a successful send → SENT row → sendAdminAlertSync logs delivered, no throw, no warn")
        void successfulSend_producesSentRow_andListenerLogsDelivered() {
            // seamMailService.sendEmailFromTemplate does nothing → success.
            assertThatCode(() -> listenerOverRealManager.sendAdminAlertSync(event()))
                .doesNotThrowAnyException();

            assertThat(saved.get()).isNotNull();
            assertThat(saved.get().getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
            assertThat(loggedAt(Level.INFO, "[VIDEO_MODERATION_ADMIN_ALERT] delivered")).isTrue();
            assertThat(loggedAt(Level.WARN, "[VIDEO_MODERATION_ADMIN_ALERT]")).isFalse();
        }
    }
}
