package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.event.CashOutRefundResolvedEvent;
import com.softropic.skillars.platform.payment.contract.event.CashOutRefundUnresolvedEvent;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliation;
import com.softropic.skillars.platform.payment.repo.CashOutReconciliationRepository;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * feat/coach-payouts-pack-and-credit (C3 item 4): owns the {@code payment.cash_out_reconciliations}
 * state transitions, and re-checks open cash-out refunds.
 *
 * <p><strong>Transitions</strong> run in short, DB-only transactions under the row lock, and only from
 * an open status ({@code PENDING}/{@code UNKNOWN}) — so the cash-out request and the re-check job can
 * never both act on one cash-out (in particular, credit is restored at most once:
 * {@link #reverseAfterRejection} flips the row and writes the {@code CASH_OUT_REVERSAL} rows in the same
 * transaction). The Stripe call is never made while a transaction or lock is held.
 *
 * <p><strong>Re-check job</strong> ({@link #recheckOpenCashOuts}, ShedLock): retries the refund of an
 * {@code UNKNOWN} row, or of a {@code PENDING} row old enough that its request must have died between
 * the debit and the refund, under the SAME idempotency key {@code cashout-{id}} — Stripe executes the
 * refund at most once per key and replays the stored result of a request it already processed (a
 * success, a 4xx rejection, or a 5xx alike), so a retry can never refund twice. Stripe prunes keys
 * after ~24h, so retries stop at {@link #RETRY_WINDOW} (23h); a row still open then is escalated to
 * {@code UNKNOWN} for a human ({@code CASH_OUT_UNRESOLVED} admin alert) and never retried again.
 *
 * <p>The admin alert is raised/resolved by events the admin module handles AFTER_COMMIT, so the alert
 * never disagrees with a status write that rolled back.
 */
@Slf4j
@Service
public class CashOutReconciliationService {

    /** Stripe keeps idempotency keys for at least 24h; stay safely inside that. */
    static final Duration RETRY_WINDOW = Duration.ofHours(23);
    /** A PENDING row older than this has outlived its request (3 refund attempts finish in minutes). */
    static final Duration STALE_PENDING_AFTER = Duration.ofMinutes(15);
    /**
     * Sized against {@code lockAtMostFor = PT30M}: one refund call is at worst the Stripe SDK's default
     * 30s connect + 80s read timeout (~110s, no SDK retries configured), so 15 rows ≈ 27.5 min, plus
     * the DB-only escalations. Even an overlapping second run would be harmless — same idempotency key
     * at Stripe, and every transition is conditional under the row lock — but it should not happen.
     */
    static final int BATCH_SIZE = 15;

    static final String REFUND_OUTCOME_UNKNOWN = "payment.refundOutcomeUnknown";
    private static final int MAX_ERROR_LEN = 1000;

    private final CashOutReconciliationRepository repository;
    private final CreditWalletService creditWalletService;
    private final PaymentGateway paymentGateway;
    private final ApplicationEventPublisher eventPublisher;

    /** Self-proxy so the job's per-row transitions get their own transactions. */
    @Autowired
    @Lazy
    private CashOutReconciliationService self;

    public CashOutReconciliationService(CashOutReconciliationRepository repository,
                                        CreditWalletService creditWalletService,
                                        PaymentGateway paymentGateway,
                                        ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.creditWalletService = creditWalletService;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    /** The refund was accepted by Stripe. No-op unless the row is still open. */
    @Transactional
    public void markCompleted(UUID cashOutId) {
        repository.findByIdForUpdate(cashOutId).filter(CashOutReconciliation::isOpen).ifPresent(r -> {
            boolean wasUnknown = CashOutReconciliation.UNKNOWN.equals(r.getStatus());
            r.setStatus(CashOutReconciliation.COMPLETED);
            r.setAttempts(r.getAttempts() + 1);
            r.setResolvedAt(Instant.now());
            repository.save(r);
            if (wasUnknown) {
                eventPublisher.publishEvent(
                    new CashOutRefundResolvedEvent(this, cashOutId, CashOutReconciliation.COMPLETED));
            }
        });
    }

    /**
     * Stripe REJECTED the refund (4xx), so no money moved: restore the payer's credit and close the row,
     * atomically. Returns false — and restores nothing — when the row is no longer open (already
     * completed or reversed by the other party), which is what makes the compensation exactly-once.
     */
    @Transactional
    public boolean reverseAfterRejection(UUID cashOutId, String error) {
        CashOutReconciliation r = repository.findByIdForUpdate(cashOutId).orElse(null);
        if (r == null || !r.isOpen()) {
            return false;
        }
        creditWalletService.reverseCashOut(r.getParentId(), cashOutId, r.getNetAmount(), r.getFeeAmount());
        r.setStatus(CashOutReconciliation.REVERSED);
        r.setAttempts(r.getAttempts() + 1);
        r.setLastError(truncate(error));
        r.setResolvedAt(Instant.now());
        repository.save(r);
        eventPublisher.publishEvent(new CashOutRefundResolvedEvent(this, cashOutId, CashOutReconciliation.REVERSED));
        return true;
    }

    /**
     * Stripe's outcome is unknown: the debit stands (restoring it could pay the payer twice). The first
     * time a row goes UNKNOWN an admin alert is raised; later unknown retries only count attempts.
     */
    @Transactional
    public void markUnknown(UUID cashOutId, String error) {
        repository.findByIdForUpdate(cashOutId).filter(CashOutReconciliation::isOpen).ifPresent(r -> {
            boolean firstTime = CashOutReconciliation.PENDING.equals(r.getStatus());
            r.setStatus(CashOutReconciliation.UNKNOWN);
            r.setAttempts(r.getAttempts() + 1);
            r.setLastError(truncate(error));
            repository.save(r);
            if (firstTime) {
                log.error("[CASH_OUT_UNRESOLVED] cashOutId={} parentId={} intentId={} net={} fee={} key={} — refund "
                    + "outcome unknown, wallet debit kept; re-checked automatically for {}h, then by hand",
                    cashOutId, r.getParentId(), r.getPaymentIntentId(), r.getNetAmount(), r.getFeeAmount(),
                    r.idempotencyKey(), RETRY_WINDOW.toHours());
                eventPublisher.publishEvent(
                    new CashOutRefundUnresolvedEvent(this, cashOutId, r.getParentId(), truncate(error)));
            }
        });
    }

    /**
     * Re-checks open cash-out refunds. Every 15 minutes, one instance at a time.
     */
    @Scheduled(fixedDelay = 15, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "CashOutReconciliationService_recheck", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void recheckOpenCashOuts() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(RETRY_WINDOW);

        // Past the idempotency-key window: never retry, hand to a human.
        for (UUID id : repository.findExpiredPending(windowStart, PageRequest.of(0, BATCH_SIZE))) {
            try {
                self.markUnknown(id, "refund never confirmed within " + RETRY_WINDOW.toHours()
                    + "h — Stripe idempotency key may have expired, reconcile by hand");
            } catch (RuntimeException e) {
                log.error("[CASH_OUT_RECHECK] could not escalate expired cash-out {}", id, e);
            }
        }

        List<UUID> retryable = repository.findRetryable(windowStart, now.minus(STALE_PENDING_AFTER),
            PageRequest.of(0, BATCH_SIZE));
        for (UUID id : retryable) {
            try {
                recheckOne(id);
            } catch (RuntimeException e) {
                // One row's failure (DB, lock) must not stop the others; it is retried next run.
                log.error("[CASH_OUT_RECHECK] re-check of cash-out {} failed — retried next run", id, e);
            }
        }
    }

    /** One retry of one open cash-out's refund, under its original idempotency key. */
    void recheckOne(UUID cashOutId) {
        CashOutReconciliation r = repository.findById(cashOutId).orElse(null);
        if (r == null || !r.isOpen()) {
            return;
        }
        try {
            paymentGateway.refund(r.getPaymentIntentId(), r.getNetAmount(), r.idempotencyKey());
        } catch (PaymentGatewayException e) {
            if (REFUND_OUTCOME_UNKNOWN.equals(e.getErrorCode())) {
                self.markUnknown(cashOutId, e.getErrorCode());
                return;
            }
            // A rejection replayed or produced under this key means no refund was executed for it.
            if (self.reverseAfterRejection(cashOutId, e.getErrorCode())) {
                log.warn("[CASH_OUT_RECHECK] cash-out {} refund rejected by Stripe on re-check — credit restored",
                    cashOutId);
            }
            return;
        } catch (RuntimeException e) {
            self.markUnknown(cashOutId, String.valueOf(e));
            return;
        }
        self.markCompleted(cashOutId);
        log.info("[CASH_OUT_RECHECK] cash-out {} refund confirmed on re-check", cashOutId);
    }

    private static String truncate(String s) {
        return s != null && s.length() > MAX_ERROR_LEN ? s.substring(0, MAX_ERROR_LEN) : s;
    }
}
