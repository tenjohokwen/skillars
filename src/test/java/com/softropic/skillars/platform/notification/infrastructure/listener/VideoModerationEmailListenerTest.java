package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @DisplayName("a SENT envelope logs delivery and does not throw")
    void sentEnvelope_logsDelivered() {
        when(envelopeEntityRepository.findBySendId(anyString()))
            .thenReturn(persisted(EmailDeliveryStatus.SENT, false));

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        assertThat(loggedAt(Level.INFO, "[VIDEO_MODERATION_ADMIN_ALERT]")).isTrue();
        assertThat(loggedAt(Level.ERROR, "[VIDEO_MODERATION_ADMIN_ALERT_UNDELIVERABLE]")).isFalse();
    }

    @Test
    @DisplayName("no persisted envelope (send id not found) is treated as delivered, not as a failure")
    void noPersistedEnvelope_doesNotThrow() {
        when(envelopeEntityRepository.findBySendId(anyString())).thenReturn(null);

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        assertThat(loggedAt(Level.INFO, "[VIDEO_MODERATION_ADMIN_ALERT]")).isTrue();
    }

    @Test
    @DisplayName("a blank admin_alert_email short-circuits before any send and does not throw")
    void blankRecipient_returnsWithoutSending() {
        when(configService.find(ADMIN_EMAIL_KEY)).thenReturn(Optional.of(""));

        assertThatCode(() -> listener.sendAdminAlertSync(event())).doesNotThrowAnyException();

        verify(mailManager, never()).sendEmailSync(any());
        verify(envelopeEntityRepository, never()).findBySendId(anyString());
    }
}
