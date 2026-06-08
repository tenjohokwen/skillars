package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.platform.notification.contract.EmailDeliveryStatus;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;
import com.softropic.skillars.platform.notification.service.EnvelopeMapper;
import com.softropic.skillars.platform.notification.service.MailManager;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled job that retries email deliveries that previously failed with a retryable error.
 *
 * <p>Each invocation issues a {@code SELECT FOR UPDATE SKIP LOCKED} query so that concurrent
 * application instances each claim a disjoint batch of rows and never race over the same
 * envelope. The transaction started by {@link Transactional} keeps those row-locks alive
 * until the pre-checks are evaluated and terminal statuses (DEADLINE_EXPIRED,
 * ATTEMPTS_EXHAUSTED) are committed atomically.
 *
 * <p><strong>Transaction / SMTP separation:</strong> SMTP calls are deliberately placed
 * <em>outside</em> the database transaction. Holding a DB connection open while waiting
 * for SMTP responses would tie up connection-pool slots for the entire send duration
 * (potentially hundreds of seconds). Instead, eligible envelopes are collected inside the
 * transaction and dispatched via an {@code afterCommit} hook so that the connection is
 * released before any network I/O begins. Each individual send runs in its own
 * {@code REQUIRES_NEW} transaction (see {@link MailManager#sendEmailSync}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "email.retry.enabled", havingValue = "true", matchIfMissing = true)
public class EmailRetryScheduler {

    private final EnvelopeEntityRepository envelopeEntityRepository;
    private final MailManager mailManager;

    /**
     * Maximum number of scheduler-level retries permitted per envelope.
     * When {@code attempts} reaches this value the envelope is marked
     * {@link EmailDeliveryStatus#ATTEMPTS_EXHAUSTED} and no further send is attempted.
     *
     * <p>Interpretation A: the initial send (via the event listener) is not a retry.
     * {@code attempts} is incremented once per {@code sendEmailSync} call, so a value of 6
     * means 1 initial + 5 scheduler retries have already occurred.
     */
    static final long MAX_RETRY_ATTEMPTS = 6;

    /**
     * Polls for failed, retryable emails and re-attempts delivery.
     *
     * <p>Runs with a fixed delay (default 60 s, overridable via {@code email.retry.interval-ms})
     * so a slow batch always completes before the next one starts.
     *
     * <p>The {@code SELECT FOR UPDATE SKIP LOCKED} in {@link EnvelopeEntityRepository#fetchFailedEmails()}
     * ensures that in a multi-instance deployment each pod works on a distinct set of rows.
     *
     * <p>Before each send attempt the following pre-checks are applied in order:
     * <ol>
     *   <li>If the envelope's deadline has passed it is marked
     *       {@link EmailDeliveryStatus#DEADLINE_EXPIRED} and skipped.</li>
     *   <li>If {@code attempts >= MAX_RETRY_ATTEMPTS} it is marked
     *       {@link EmailDeliveryStatus#ATTEMPTS_EXHAUSTED} and skipped.</li>
     * </ol>
     * In both cases {@code retry} is set to {@code false} so the row is never fetched again.
     *
     * <p>The {@code timeout = 600} bound prevents an indefinitely open transaction in edge
     * cases (e.g., a stuck REQUIRES_NEW sub-transaction). SMTP calls themselves happen
     * after the outer transaction commits (see class-level Javadoc).
     */
    @Observed(name = "scheduler.email-retry")
    @Scheduled(fixedDelayString = "${email.retry.interval-ms:60000}")
    @Transactional(timeout = 600)
    public void retryFailedEmails() {
        List<EnvelopeEntity> candidates = envelopeEntityRepository.fetchFailedEmails();
        if (candidates.isEmpty()) {
            return;
        }
        log.info("Retrying failed emails", kv("count", candidates.size()));

        List<Envelope> eligible = new ArrayList<>();
        for (EnvelopeEntity entity : candidates) {
            if (Instant.now().isAfter(entity.getDeadline())) {
                log.warn("Email deadline expired", kv("sendId", entity.getSendId()), kv("newStatus", "DEADLINE_EXPIRED"));
                entity.setStatus(EmailDeliveryStatus.DEADLINE_EXPIRED);
                entity.setRetry(false);
                continue;
            }
            if (entity.getAttempts() >= MAX_RETRY_ATTEMPTS) {
                log.warn("Email attempts exhausted", kv("sendId", entity.getSendId()), kv("attempts", entity.getAttempts()), kv("newStatus", "ATTEMPTS_EXHAUSTED"));
                entity.setStatus(EmailDeliveryStatus.ATTEMPTS_EXHAUSTED);
                entity.setRetry(false);
                continue;
            }
            eligible.add(EnvelopeMapper.toEnvelope(entity));
        }

        dispatchAfterCommit(eligible);
    }

    /**
     * Schedules SMTP dispatch to run after the current transaction commits so that the
     * database connection is not held open during network I/O.
     *
     * <p>When no transaction is active (e.g., in unit-test context without a real
     * {@code PlatformTransactionManager}) the envelopes are sent synchronously so that
     * tests remain straightforward without requiring a full Spring TX setup.
     */
    private void dispatchAfterCommit(List<Envelope> envelopes) {
        if (envelopes.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAll(envelopes);
                }
            });
        } else {
            sendAll(envelopes);
        }
    }

    private void sendAll(List<Envelope> envelopes) {
        for (Envelope envelope : envelopes) {
            try {
                mailManager.sendEmailSync(envelope);
            } catch (Exception e) {
                log.error("Email retry attempt failed", kv("sendId", envelope.sendId()), e);
            }
        }
    }
}
