package com.softropic.skillars.platform.outbox;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.video.contract.event.VideoModerationRetryEvent;
import com.softropic.skillars.platform.video.service.ModerationOutboxSupport;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * skillars-deferred-92 AC5.3 — the enqueue → drain round trip for
 * {@code ModerationSlaMonitorService}'s two intents, against a real database and the real generic
 * outbox.
 *
 * <p>Before AC5 both were published straight from an {@code ApplicationEventPublisher}, so a crash
 * between {@code findScanningOlderThan()} and the publish lost that cycle's retry and alert intents.
 * They now commit atomically with the state change that justifies them — the retry-count increment,
 * and the {@code FAILED} transition respectively.
 *
 * <p>Under the test profile {@code app.outbox.drain-async=false}, so {@code drain()} runs on the
 * calling thread and the effect is observable as soon as the producing transaction commits.
 */
class ModerationOutboxIT extends AbstractIntegrationTest {

    private static final String ADMIN_ALERT_EMAIL_KEY = "platform.admin_alert_email";

    @Autowired private ModerationOutboxSupport moderationOutboxSupport;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConfigurableApplicationContext applicationContext;
    @Autowired private ConfigService configService;
    @Autowired private TestMailManager testMailManager;

    private final UUID videoId = UUID.randomUUID();
    private final String adminEmail = "ac5.admin." + UUID.randomUUID() + "@skillars-test.com";
    private final List<Object> captured = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        // Scoped to THIS test's video id, never an unscoped truncate: main.outbox_messages is shared
        // with every other IT in this context, and drain() would run their rows through real handlers.
        transactionTemplate.executeWithoutResult(s -> jdbcTemplate.update(
            "DELETE FROM main.outbox_messages WHERE aggregate_type IN (?, ?) AND payload->>'videoId' = ?",
            ModerationOutboxSupport.RETRY_AGGREGATE_TYPE,
            ModerationOutboxSupport.ADMIN_ALERT_AGGREGATE_TYPE, videoId.toString()));

        captured.clear();
        testMailManager.clear();
        // The alert handler now delivers through MailManager rather than re-publishing an event
        // (code review D1), and VideoModerationEmailListener suppresses a send when this key is
        // blank — which is its shipped default. Set it for the duration of the test, restored below.
        configService.updateConfig(ADMIN_ALERT_EMAIL_KEY, adminEmail);

        // Registered on the live context rather than mocked, so this exercises the same dispatch the
        // retry handler uses in production. Filtered by this test's own video id so a concurrently
        // running IT's events cannot leak in.
        applicationContext.addApplicationListener(
            (ApplicationListener<PayloadApplicationEvent<Object>>) event -> {
                Object payload = event.getPayload();
                if (payload instanceof VideoModerationRetryEvent r && videoId.equals(r.videoId())) {
                    captured.add(r);
                }
            });
    }

    @AfterEach
    void restoreAdminAlertEmail() {
        // Blank is the shipped default (V57). Other ITs share this context and its config cache.
        configService.updateConfig(ADMIN_ALERT_EMAIL_KEY, "");
    }

    private long myRows(String aggregateType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload->>'videoId' = ?",
            Long.class, aggregateType, videoId.toString());
    }

    /**
     * The assertion is on a <em>send</em>, not on a re-published event (code review D1). Dispatching
     * an event and returning is what let the drain delete the row before anything had been delivered;
     * the property worth pinning is that {@code handle()} does not return until the alert has actually
     * gone through {@code MailManager}.
     */
    @Test
    @DisplayName("an enqueued admin alert survives its transaction and is delivered by the drain")
    void adminAlert_roundTripsThroughTheOutbox() {
        transactionTemplate.executeWithoutResult(s -> moderationOutboxSupport.enqueueAdminAlert(
            videoId, "owner@example.com", "Moderation pipeline permanently failed",
            "videoId=" + videoId + " retries=5 — manual review required", true));

        List<Envelope> alerts = testMailManager.getEnvelopes().values().stream()
            .filter(e -> e.emailTemplate() == EmailTemplate.VIDEO_MODERATION_ADMIN_ALERT)
            .filter(e -> e.recipients().stream().anyMatch(r -> adminEmail.equals(r.getEmail())))
            .toList();

        assertThat(alerts)
            .as("the drain must have DELIVERED the alert, synchronously, before releasing the row")
            .hasSize(1);
        assertThat(alerts.get(0).data())
            .containsEntry("subject", "Moderation pipeline permanently failed")
            .containsEntry("ownerId", "owner@example.com")
            .containsEntry("urgent", true)
            .containsEntry("videoId", videoId.toString());
        assertThat(myRows(ModerationOutboxSupport.ADMIN_ALERT_AGGREGATE_TYPE))
            .as("a successfully handled row is deleted")
            .isZero();
    }

    /**
     * The hole D1 closed, pinned: with {@code platform.admin_alert_email} unset the alert cannot be
     * delivered, and that must still <em>complete</em> the row rather than leave an immortal one — no
     * number of re-drives fixes a missing config key.
     */
    @Test
    @DisplayName("an alert that cannot be delivered at all still releases its row")
    void adminAlertWithNoConfiguredRecipient_completesRatherThanSticking() {
        configService.updateConfig(ADMIN_ALERT_EMAIL_KEY, "");

        transactionTemplate.executeWithoutResult(s -> moderationOutboxSupport.enqueueAdminAlert(
            videoId, "owner@example.com", "Moderation pipeline permanently failed",
            "videoId=" + videoId, true));

        assertThat(testMailManager.getEnvelopes().values())
            .as("nothing may be sent when there is no recipient configured")
            .noneMatch(e -> e.emailTemplate() == EmailTemplate.VIDEO_MODERATION_ADMIN_ALERT);
        assertThat(myRows(ModerationOutboxSupport.ADMIN_ALERT_AGGREGATE_TYPE)).isZero();
    }

    /**
     * AC5.2's idempotence, end to end rather than against a mock: this test's video id does not exist
     * in {@code video.videos} at all, which is the "deleted since the SLA cycle" case. The row must
     * <strong>complete</strong> — not be retried forever — and nothing may be dispatched.
     */
    @Test
    @DisplayName("a retry for a video that no longer exists completes as a no-op, leaving no stuck row")
    void retryForAVanishedVideo_isANoOpAndTheRowCompletes() {
        transactionTemplate.executeWithoutResult(s ->
            moderationOutboxSupport.enqueueRetry(videoId, "owner@example.com"));

        assertThat(captured)
            .as("a video that no longer exists must not be re-dispatched to the moderation pipeline")
            .isEmpty();
        assertThat(myRows(ModerationOutboxSupport.RETRY_AGGREGATE_TYPE))
            .as("""
                The row must be DELETED, not retained. A no-op that throws would keep attempts++ and \
                eventually trip [OUTBOX_STUCK], summoning a human for a video whose moderation simply \
                concluded between the SLA cycle and the drain.""")
            .isZero();
    }

    /** The {@code MANDATORY} contract, matching {@code NotificationOutboxSupport.enqueueEmail}. */
    @Test
    @DisplayName("enqueueing outside a transaction is rejected rather than opening one of its own")
    void enqueueOutsideATransaction_isRejected() {
        assertThatThrownBy(() -> moderationOutboxSupport.enqueueRetry(videoId, "owner@example.com"))
            .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(myRows(ModerationOutboxSupport.RETRY_AGGREGATE_TYPE)).isZero();
    }

    @Test
    @DisplayName("a rolled-back producing transaction enqueues nothing")
    void rollback_enqueuesNothing() {
        try {
            transactionTemplate.executeWithoutResult(s -> {
                moderationOutboxSupport.enqueueRetry(videoId, "owner@example.com");
                throw new IllegalStateException("AC5: deliberate rollback");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("deliberate rollback");
        }

        assertThat(myRows(ModerationOutboxSupport.RETRY_AGGREGATE_TYPE)).isZero();
        // Guards against the row having been drained rather than rolled back: a drain would also
        // leave zero rows, but it would have dispatched an event, which `captured` would show.
        assertThat(captured).isEmpty();
    }
}
