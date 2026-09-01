package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
 * colliding {@code persist} <em>on a retry</em>, hence no {@code DataIntegrityViolationException} and
 * no misleading {@code @Recover} "rows lost" log on an ambiguous-commit retry.
 *
 * <p><strong>The concurrent-<em>delivery</em> backstop (V47).</strong> Two <em>distinct</em>
 * {@code BookingCompletedEvent} deliveries for one session are a different race from a retry: both can
 * pass the non-locking {@code findBySessionId(...).isEmpty()} / {@code existsBySessionId(...)} guards,
 * both reach {@code saveAll}, and the loser's INSERT collides with
 * {@code V47__player_skill_stats_unique_constraint.sql}'s partial unique index
 * {@value #SESSION_SKILL_UNIQUE_CONSTRAINT} on {@code (session_id, skill_code) WHERE session_id IS
 * NOT NULL} — PG {@code 23505}. {@link #saveSluWithRetry} catches <em>that specific constraint</em>
 * around {@code saveAll} and treats it as the idempotent no-op it is (the winner already persisted
 * those rows): {@code log.info} + return {@link SluSaveOutcome#ALREADY_PERSISTED}, no retry, no
 * {@code @Recover}. Every <em>other</em> constraint (the {@code skill_code} FK, the PK, a NOT NULL)
 * is re-thrown and retries / recovers exactly as before.
 *
 * <p><strong>Why the catch works.</strong> {@code saveSluWithRetry} is <em>not</em>
 * {@code @Transactional}, so {@code SimpleJpaRepository.saveAll} opens and commits its own
 * transaction and the constraint fires <em>inside</em> the {@code sluRepository.saveAll(rows)} call —
 * within reach of the {@code try/catch}. If anyone ever adds {@code @Transactional} to this retrier
 * the flush/commit moves to the outer boundary, <em>after</em> the catch, and this no-op handling
 * silently stops working — flag that in review.
 *
 * <p>{@link #saveSluWithRetry} additionally short-circuits on an explicit
 * {@code existsBySessionId(session_id)} check-then-act (skillars-deferred-86 AC2) — this documents
 * the idempotency contract and skips the redundant {@code merge}/SELECT round-trip on a retry; it is
 * <em>not</em> a bug fix (the {@code merge} path above is already safe) and is also a backstop the
 * day {@code PlayerSkillStat} ever gains a mutable column or a {@code @Version} field.
 *
 * <p><strong>Return contract (skillars-deferred-89 AC2, tightened by its code review).</strong>
 * {@link #saveSluWithRetry} returns a {@link SluSaveOutcome} tri-state:
 * <ul>
 *   <li>{@link SluSaveOutcome#SAVED} — this call persisted the rows (or the batch was empty);</li>
 *   <li>{@link SluSaveOutcome#ALREADY_PERSISTED} — a <em>different</em> delivery persisted them
 *       (the {@code existsBySessionId} short-circuit or the V47 collision catch);</li>
 *   <li>{@link SluSaveOutcome#FAILED} — retries exhausted, rows lost (the two {@code @Recover}
 *       paths).</li>
 * </ul>
 * {@code SluPersistenceDispatcher} runs the weekly-snapshot write <em>only</em> on {@code SAVED}: a
 * permanently lost detail save cannot leave {@code player_slu_weekly_snapshot} over-reporting, and
 * neither can a concurrent delivery whose own {@code isoYear}/{@code isoWeek} bucket (derived from a
 * per-invocation {@code Instant.now()}) differs from the delivery that actually persisted the rows.
 *
 * <p><strong>Known residual (skillars-deferred-89 AC1, recorded — not fixed here).</strong> When two
 * deliveries race, the loser's whole {@code saveAll} batch is rolled back by PG on the first
 * {@code (session_id, skill_code)} collision. If the winner persisted only a <em>subset</em> of
 * skills (reachable if {@code slu.*.scale} config or {@code skill_definitions.active} changed between
 * the two per-invocation reads), the loser's extra skills are silently dropped — same shape as the
 * existing {@code existsBySessionId} short-circuit, so not a regression. The {@code log.info} line
 * reads "already persisted by a concurrent delivery"; it does <em>not</em> assert the two deliveries
 * computed an identical skill set. Returning {@link SluSaveOutcome#ALREADY_PERSISTED} (not
 * {@code SAVED}) on this path is what keeps the snapshot write from applying the loser's deltas to a
 * bucket the winner never marked — see {@link SluSaveOutcome}.
 *
 * <p>Transaction-<em>usage</em> programming errors (e.g. {@code IllegalTransactionStateException}) are
 * deliberately not in {@code retryFor} — they are not reachable from this well-formed delegate.
 *
 * <p><strong>Do not add {@code @Transactional} to this class or method.</strong> The V47 collision
 * catch works only because {@code SimpleJpaRepository.saveAll} opens and commits its own transaction,
 * so {@code 23505} fires inside the {@code try} block. A {@code @Transactional} here would move the
 * flush to the outer boundary, past the catch — silently restoring the retry storm, the false
 * "rows lost" {@code @Recover} ERROR, and (post-AC2) a snapshot under-report. {@code
 * SluPersistenceRetrierTest.saveSluWithRetry_isNotTransactional_soTheSaveAllCollisionFiresInsideTheCatch}
 * is a reflective regression guard on exactly that.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SluPersistenceRetrier {

    /**
     * V47's partial unique index on {@code development.player_skill_stats (session_id, skill_code)
     * WHERE session_id IS NOT NULL}. A {@code 23505} on this index means a concurrent delivery already
     * persisted the same session's rows — an idempotent no-op, not a failure.
     */
    static final String SESSION_SKILL_UNIQUE_CONSTRAINT = "uq_player_skill_stats_session_skill";

    private final SluRepository sluRepository;

    @Retryable(
        retryFor = {DataAccessException.class, TransactionSystemException.class, CannotCreateTransactionException.class},
        maxAttemptsExpression = "${app.slu.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.slu.retry.backoff-initial-ms:100}",
            multiplierExpression = "${app.slu.retry.backoff-multiplier:2.0}"
        )
    )
    public SluSaveOutcome saveSluWithRetry(List<PlayerSkillStat> rows) {
        if (rows.isEmpty()) {
            // An empty batch is not a failed save — nothing to persist, nothing lost. Returning
            // FAILED here would fire SluPersistenceDispatcher's AC2 "detail save exhausted" ERROR on
            // a benign empty list. Production never reaches this (SluCalculationService returns
            // before dispatching when stats.isEmpty()), but the direct test callers do. SAVED (not
            // ALREADY_PERSISTED) preserves the pre-AC2 behaviour exactly — the snapshot write over
            // an empty list is a documented no-op.
            return SluSaveOutcome.SAVED;
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
            // ALREADY_PERSISTED, not SAVED: another delivery (or an idempotent retry) persisted these
            // rows and owns the weekly-snapshot write for the bucket they belong to. Returning SAVED
            // would have the dispatcher run writeAllWithRetry with THIS task's iso-week, which can
            // differ across a week boundary — an over-report the winning delivery never caused.
            return SluSaveOutcome.ALREADY_PERSISTED;
        }
        try {
            sluRepository.saveAll(rows);
            return SluSaveOutcome.SAVED;
        } catch (DataIntegrityViolationException ex) {
            if (isSessionSkillUniqueViolation(ex)) {
                // Two concurrent BookingCompletedEvent deliveries for this session both passed the
                // non-locking existsBySessionId / findBySessionId guards; the winner committed first
                // and this (losing) saveAll hit V47's uq_player_skill_stats_session_skill with PG
                // 23505. The rows this call would have written are already persisted by the winner —
                // treat it as the idempotent no-op it is, instead of retrying a guaranteed-fail
                // insert 3× (each preceded by a @Backoff sleep on the bounded sluRetryExecutor) and
                // then logging a false "rows lost … manual recovery needed" @Recover ERROR for rows
                // that ARE persisted. The message reads "already persisted" — it does NOT assert the
                // two deliveries computed an identical skill set (see the class javadoc's "Known
                // residual" paragraph). ALREADY_PERSISTED, not SAVED: the winning delivery owns the
                // weekly-snapshot write; running it here with this task's iso-week can over-report
                // across a week boundary.
                log.info("SLU detail rows for session {} skipped — already persisted by a concurrent delivery", sessionId);
                return SluSaveOutcome.ALREADY_PERSISTED;
            }
            // Any other constraint (skill_code FK — pre-filtered by SluCalculationService but the
            // guarded-against case; PK; NOT NULL) is a real failure. Re-throw so @Retryable retries
            // and @Recover fires exactly as it does today. A blanket catch (DataIntegrityViolationException)
            // would let this method report success for a session with zero persisted rows and defeat
            // SluPersistenceDispatcher's AC2 gate — do not widen it.
            throw ex;
        }
    }

    private static boolean isSessionSkillUniqueViolation(DataIntegrityViolationException ex) {
        // Single-level unwrap, mirroring ApiAdvice.integrityViolationHandler.
        return ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
            && SESSION_SKILL_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }

    @Recover
    public SluSaveOutcome recoverSluSaveFailure(DataAccessException ex, List<PlayerSkillStat> rows) {
        log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed",
            rows.size(), rows.isEmpty() ? null : rows.get(0).getSessionId(), ex);
        return SluSaveOutcome.FAILED;
    }

    // Reached only after a retryFor-matched TransactionSystemException / CannotCreateTransactionException
    // exhausts its attempts. Kept separate from the DataAccessException overload: Spring Retry selects
    // the closest-matching @Recover by throwable type, and the two are unambiguous siblings (neither is
    // assignable to the other). Both return FAILED so SluPersistenceDispatcher skips the snapshot write.
    @Recover
    public SluSaveOutcome recoverSluSaveFailure(TransactionException ex, List<PlayerSkillStat> rows) {
        log.error("Failed to save SLU after retries — {} rows lost for session {}, manual recovery needed",
            rows.size(), rows.isEmpty() ? null : rows.get(0).getSessionId(), ex);
        return SluSaveOutcome.FAILED;
    }
}
