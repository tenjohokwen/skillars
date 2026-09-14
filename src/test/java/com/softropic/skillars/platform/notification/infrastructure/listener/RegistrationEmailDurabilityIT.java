package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.infrastructure.EmailRetryScheduler;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.repo.RecipientEntity;
import com.softropic.skillars.platform.notification.service.NotificationEmailOutboxHandler;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport.NotificationEmailPayload;
import com.softropic.skillars.platform.security.contract.event.CoachOtpEmailEvent;
import com.softropic.skillars.platform.security.contract.event.CoachVerificationEmailEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.4 AC1/AC2/AC3/AC6 — the registration/OTP durability regression guard, mirroring
 * {@code BookingReminderEmailWiringIT}'s role for booking mail.
 *
 * <h2>Why {@code enable.test.mail=false} (S1) — {@code BookingReminderEmailWiringIT} is the wrong model</h2>
 *
 * That class (and every other outbox IT in this package) runs with the shared default
 * {@code enable.test.mail=true}, which swaps the entire {@code MailManager} bean for
 * {@code TestMailManager} — it never produces an {@code EnvelopeEntity} row at all, so there is no
 * {@code FAILED}/{@code retry=true} state for anything to re-drive and no {@code SENT} row to assert
 * a recipient's {@code firstname} survived onto. This class needs the real {@code MailManager} bean
 * active, so it forks a second Spring context via {@code @TestPropertySource} — accepted per
 * {@code IntegrationTestConventionTest}'s governance (count bumped, this comment is the required
 * justification).
 *
 * <h2>Avoiding the dual-retry-driver race (S2)</h2>
 *
 * A {@code FAILED}/{@code retry=true} envelope can be re-driven by two independent mechanisms:
 * {@code EmailRetryScheduler}'s scheduled pass and the generic outbox's own per-row backoff. Racing
 * them would risk {@code ATTEMPTS_EXHAUSTED} firing before (or instead of) the assertion under test.
 * Every scheduler-facing case below therefore persists the {@code EnvelopeEntity} row directly with
 * the exact state the assertion needs, then calls {@code EmailRetryScheduler.retryFailedEmails()}
 * once — never publishing through the full pipeline to produce that state.
 */
@TestPropertySource(properties = "enable.test.mail=false")
// context-fork: needs the real MailManager bean (see class javadoc) — TestMailManager persists
// nothing, so it cannot produce the EnvelopeEntity rows this class asserts on.
class RegistrationEmailDurabilityIT extends AbstractIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EnvelopeEntityRepository envelopeEntityRepository;
    @Autowired private NotificationEmailOutboxHandler notificationEmailOutboxHandler;
    @Autowired private EmailRetryScheduler emailRetryScheduler;
    @Autowired private ObjectMapper objectMapper;

    private String freshEmail(String prefix) {
        return "ses14." + prefix + "." + UUID.randomUUID() + "@skillars-test.com";
    }

    private EnvelopeEntity committedRowFor(String email) {
        List<EnvelopeEntity> rows = transactionTemplate.execute(status ->
            envelopeEntityRepository.findAll().stream()
                .filter(e -> e.getRecipients() != null
                    && e.getRecipients().stream().anyMatch(r -> email.equals(r.getEmail())))
                .toList());
        assertThat(rows).as("exactly one committed envelope row for %s", email).hasSize(1);
        return rows.get(0);
    }

    // --- AC1/AC2: the listener actually enqueues BEFORE_COMMIT, and a real send preserves firstname ---

    private int outboxRowsFor(String email) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM main.outbox_messages WHERE aggregate_type = ? AND payload::text LIKE ?",
            Integer.class, NotificationOutboxSupport.AGGREGATE_TYPE, "%" + email + "%");
    }

    @Test
    @DisplayName("publishing CoachVerificationEmailEvent enqueues inside the producing transaction and a real send preserves firstname")
    void coachVerificationEmail_enqueuedBeforeCommit_andRealSendPreservesFirstname() {
        String email = freshEmail("verify");
        AtomicInteger rowsAtBeforeCommit = new AtomicInteger(-1);

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new CoachVerificationEmailEvent(email, "https://verify/token123", "en", "Ada"));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCompletion() {
                    rowsAtBeforeCommit.set(outboxRowsFor(email));
                }
            });
        });

        assertThat(rowsAtBeforeCommit.get())
            .as("AC1: the BEFORE_COMMIT listener must have written the outbox row INSIDE the "
                + "producing transaction, before it ever commits")
            .isEqualTo(1);

        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getEmailTemplate()).isEqualTo(EmailTemplate.COACH_EMAIL_VERIFY);
        assertThat(row.getStatus())
            .as("the log transport (app.email.transport=log under test) must have accepted the real send")
            .isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(row.getRecipients().get(0).getFirstname())
            .as("AC2: firstname must have survived enqueue -> outbox JSON round trip -> reconstructed Recipient")
            .isEqualTo("Ada");
        assertThat(row.getDeadline())
            .as("COACH_EMAIL_VERIFY gets 12h (code review 2026-09-12: half the verification token's "
                + "own 24h TTL), not the 24h default the OTP-deadline block comment once implied")
            .isBetween(Instant.now().plus(Duration.ofHours(11)), Instant.now().plus(Duration.ofHours(13)));
    }

    @Test
    @DisplayName("publishing CoachOtpEmailEvent enqueues with the 8-minute OTP deadline and a real send preserves firstname")
    void coachOtpEmail_enqueuedWithShortDeadline_andRealSendPreservesFirstname() {
        String email = freshEmail("otp");
        Instant beforeEnqueue = Instant.now();

        transactionTemplate.executeWithoutResult(status ->
            eventPublisher.publishEvent(new CoachOtpEmailEvent(email, "654321", "en", "Ada")));

        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getEmailTemplate()).isEqualTo(EmailTemplate.COACH_OTP);
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(row.getRecipients().get(0).getFirstname()).isEqualTo("Ada");
        assertThat(row.getDeadline())
            .as("AC3 (code review 2026-09-12): COACH_OTP must be stamped with the 8-minute deadline "
                + "— 5 minutes equaled app.outbox.sweep-ms's own 300000 default, so a sweep-recovered "
                + "OTP could arrive at or after its own deadline and never be attempted at all")
            .isBetween(beforeEnqueue.plus(Duration.ofMinutes(7)), beforeEnqueue.plus(Duration.ofMinutes(9)));
    }

    // --- AC3: the handler's first-attempt deadline guard, driven directly per S2 ---

    /**
     * Production only ever calls {@code handle(...)} from inside {@code OutboxRowProcessor}'s own
     * {@code @Transactional(REQUIRES_NEW)} wrapper — {@code findBySendId}'s pessimistic lock requires
     * an active transaction, so a direct call needs the same wrapping to be a faithful reproduction.
     */
    private void handle(NotificationEmailPayload payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        transactionTemplate.executeWithoutResult(status -> notificationEmailOutboxHandler.handle(json));
    }

    @Test
    @DisplayName("a payload already past its deadline on first attempt is released with no send, but a durable DEADLINE_EXPIRED record")
    void expiredDeadlinePayload_isReleasedWithNoDeliveryAttempt_butPersistsDeadlineExpired() throws Exception {
        String email = freshEmail("expired");
        handle(new NotificationEmailPayload(
            EmailTemplate.COACH_OTP.name(), email, "en", "Ada", UUID.randomUUID().toString(),
            Instant.now().minus(Duration.ofMinutes(1)), Map.of("otpCode", "111111")));

        // Corrected per code review 2026-09-12 (blind+edge+auditor, all three layers): the first
        // draft asserted no row at all, which this AC's own "so that" clause undercut — a dropped
        // registration OTP was invisible to every operational surface (no status row, no
        // [OUTBOX_STUCK], no metric), asymmetric with EmailRetryScheduler's identical DEADLINE_EXPIRED
        // precheck, which does persist. The fix persists a DEADLINE_EXPIRED/retry=false row instead.
        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getStatus())
            .as("AC3: the deadline must still be checked BEFORE the first send attempt — no "
                + "mailService.sendEmailFromTemplate call happens for an already-expired payload — "
                + "but the outcome must be durably recorded, not log-only")
            .isEqualTo(EmailDeliveryStatus.DEADLINE_EXPIRED);
        assertThat(row.isRetry()).isFalse();
        assertThat(row.getAttempts())
            .as("a deadline-expired precheck must not count as a delivery attempt")
            .isZero();
    }

    @Test
    @DisplayName("a payload still within its deadline on first attempt is sent normally")
    void freshDeadlinePayload_isSentNormally() throws Exception {
        String email = freshEmail("fresh");
        handle(new NotificationEmailPayload(
            EmailTemplate.COACH_OTP.name(), email, "en", "Ada", UUID.randomUUID().toString(),
            Instant.now().plus(Duration.ofMinutes(5)), Map.of("otpCode", "222222")));

        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
    }

    // --- Deterministic scheduler-level re-drive/expiry, per S2 (no full-pipeline race) ---

    private EnvelopeEntity persistedFailedEnvelope(String email, EmailTemplate template, Instant deadline, long attempts) {
        RecipientEntity recipient = new RecipientEntity();
        recipient.setEmail(email);
        recipient.setLangKey("en");
        recipient.setFirstname("Ada");

        EnvelopeEntity entity = new EnvelopeEntity();
        entity.setId(UUID.randomUUID());
        entity.setSendId(UUID.randomUUID().toString());
        entity.setEmailTemplate(template);
        entity.setDeadline(deadline);
        entity.setData(Map.of("otpCode", "999999"));
        entity.setRecipients(List.of(recipient));
        entity.setStatus(EmailDeliveryStatus.FAILED);
        entity.setRetry(true);
        entity.setAttempts(attempts);
        return transactionTemplate.execute(status -> envelopeEntityRepository.saveAndFlush(entity));
    }

    @Test
    @DisplayName("EmailRetryScheduler re-drives a FAILED/retryable registration OTP envelope to SENT")
    void retryScheduler_redrivesFailedRegistrationOtpEnvelope_toSent() {
        String email = freshEmail("redrive");
        persistedFailedEnvelope(email, EmailTemplate.COACH_OTP, Instant.now().plus(Duration.ofMinutes(5)), 1);

        emailRetryScheduler.retryFailedEmails();

        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
    }

    @Test
    @DisplayName("EmailRetryScheduler marks a past-deadline registration OTP envelope DEADLINE_EXPIRED without a send attempt")
    void retryScheduler_marksPastDeadlineRegistrationOtpEnvelope_deadlineExpired() {
        String email = freshEmail("deadline");
        persistedFailedEnvelope(email, EmailTemplate.PARENT_OTP, Instant.now().minus(Duration.ofHours(1)), 2);

        emailRetryScheduler.retryFailedEmails();

        EnvelopeEntity row = committedRowFor(email);
        assertThat(row.getStatus()).isEqualTo(EmailDeliveryStatus.DEADLINE_EXPIRED);
        assertThat(row.isRetry()).isFalse();
        assertThat(row.getAttempts())
            .as("a deadline-expired precheck must not count as a delivery attempt")
            .isEqualTo(2);
    }
}
