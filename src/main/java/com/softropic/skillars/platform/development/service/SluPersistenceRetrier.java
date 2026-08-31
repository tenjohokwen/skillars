package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;

/**
 * Split into its own bean so {@code @Retryable} goes through the Spring AOP proxy — a call from
 * SluCalculationService.onBookingCompleted via {@code this.saveSluWithRetry(...)} would bypass the
 * proxy entirely and the retry would silently never fire (same self-invocation pitfall documented on
 * BookingService.acceptAndInitiatePayment and TimelineEventListener's {@code @Lazy @Autowired} self).
 *
 * <p>Retries (and, on exhaustion, structured-recovers) both {@link DataAccessException} and the two
 * transaction-boundary failures {@link TransactionSystemException} (commit failed → whole tx rolled
 * back) and {@link CannotCreateTransactionException} (begin failed → nothing ran). A whole-method
 * retry is safe for both: {@code saveAll} runs in its own implicit transaction, {@code PlayerSkillStat}
 * ids are assigned in-memory before INSERT ({@code GenerationType.UUID}), so a rolled-back first
 * attempt followed by a retry re-INSERTs the same rows with no duplicates and no lost writes.
 * Transaction-<em>usage</em> programming errors (e.g. {@code IllegalTransactionStateException}) are
 * deliberately not in {@code retryFor} — they are not reachable from this well-formed delegate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluPersistenceRetrier {

    private final SluRepository sluRepository;

    @Retryable(
        retryFor = {DataAccessException.class, TransactionSystemException.class, CannotCreateTransactionException.class},
        maxAttemptsExpression = "${app.slu.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.slu.retry.backoff-initial-ms:100}",
            multiplierExpression = "${app.slu.retry.backoff-multiplier:2.0}"
        )
    )
    public void saveSluWithRetry(List<PlayerSkillStat> rows) {
        sluRepository.saveAll(rows);
    }

    @Recover
    public void recoverSluSaveFailure(DataAccessException ex, List<PlayerSkillStat> rows) {
        log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed",
            rows.size(), rows.isEmpty() ? null : rows.get(0).getSessionId(), ex);
    }

    // Reached only after a retryFor-matched TransactionSystemException / CannotCreateTransactionException
    // exhausts its attempts. Kept separate from the DataAccessException overload: Spring Retry selects
    // the closest-matching @Recover by throwable type, and the two are unambiguous siblings (neither is
    // assignable to the other).
    @Recover
    public void recoverSluSaveFailure(TransactionException ex, List<PlayerSkillStat> rows) {
        log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed",
            rows.size(), rows.isEmpty() ? null : rows.get(0).getSessionId(), ex);
    }
}
