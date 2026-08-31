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
import java.util.UUID;

/**
 * Split into its own bean so {@code @Retryable} goes through the Spring AOP proxy — a call from
 * SluCalculationService.onBookingCompleted via {@code this.saveSluWithRetry(...)} would bypass the
 * proxy entirely and the retry would silently never fire (same self-invocation pitfall documented on
 * BookingService.acceptAndInitiatePayment and TimelineEventListener's {@code @Lazy @Autowired} self).
 *
 * <p>Retries (and, on exhaustion, structured-recovers) both {@link DataAccessException} and the two
 * transaction-boundary failures {@link TransactionSystemException} (commit-phase system error —
 * <em>includes</em> the ambiguous "committed server-side, ack lost" case) and
 * {@link CannotCreateTransactionException} (begin failed → nothing ran).
 *
 * <p><strong>Why a whole-method retry is safe.</strong> {@code PlayerSkillStat} has
 * {@code @GeneratedValue(GenerationType.UUID)} and <em>no</em> {@code @Version} / {@code Persistable},
 * so its {@code isNew()} reduces to {@code id == null}. Attempt 1's {@code persist()} assigns the id
 * in-memory onto each instance ({@code hibernate.use_identifier_rollback} defaults {@code false}), so
 * on the retry {@code SimpleJpaRepository.save} sees {@code id != null} and routes every row through
 * {@code em.merge(...)}, not {@code persist(...)}: a SELECT-by-id that either finds the
 * already-committed row (every non-key column is {@code updatable = false} → no UPDATE, no exception,
 * no duplicate) or finds nothing and INSERTs once with the retained id. There is no reachable
 * colliding {@code persist}, hence no {@code DataIntegrityViolationException} and no misleading
 * {@code @Recover} "rows lost" log on an ambiguous-commit retry.
 *
 * <p>{@link #saveSluWithRetry} additionally short-circuits on an explicit
 * {@code existsBySessionId(session_id)} check-then-act (skillars-deferred-86 AC2) — this documents
 * the idempotency contract and skips the redundant {@code merge}/SELECT round-trip on a retry; it is
 * <em>not</em> a bug fix (the {@code merge} path above is already safe) and is also a backstop the
 * day {@code PlayerSkillStat} ever gains a mutable column or a {@code @Version} field.
 *
 * <p>Transaction-<em>usage</em> programming errors (e.g. {@code IllegalTransactionStateException}) are
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
        if (rows.isEmpty()) {
            return;
        }
        // Every row in one call shares one session_id (single SluCalculationService.onBookingCompleted
        // invocation for one session). The check runs inside @Retryable's proxied method, so it
        // re-evaluates on every attempt: a genuine first-attempt failure → rows absent → saveAll runs;
        // an ambiguous-commit retry → rows present → no-op return (skipping an otherwise-harmless
        // merge/SELECT round-trip). This is idempotency-intent documentation + a saved round-trip +
        // a backstop for a future mutable/@Version column on PlayerSkillStat — NOT a bug fix; the
        // merge-on-detached path is already safe today (see class javadoc). session_id can be null on
        // the Quick Complete path, which has no session and is not reachable here.
        UUID sessionId = rows.get(0).getSessionId();
        if (sessionId != null && sluRepository.existsBySessionId(sessionId)) {
            log.debug("SLU rows already persisted for session {} — save skipped (idempotent retry no-op)", sessionId);
            return;
        }
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
