package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.PlayerSkillStat;
import com.softropic.skillars.platform.development.repo.SluRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluPersistenceRetrierTest {

    @Mock private SluRepository sluRepository;

    private SluPersistenceRetrier retrier;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        retrier = new SluPersistenceRetrier(sluRepository);
    }

    private static PlayerSkillStat stat(UUID sessionId) {
        PlayerSkillStat s = new PlayerSkillStat();
        s.setSessionId(sessionId);
        return s;
    }

    private static DataIntegrityViolationException dive(String constraintName) {
        org.hibernate.exception.ConstraintViolationException cve =
            new org.hibernate.exception.ConstraintViolationException(
                "duplicate key value violates unique constraint", new SQLException("23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cve);
    }

    @Test
    void saveSluWithRetry_delegatesToRepository_andReturnsSaved() {
        List<PlayerSkillStat> rows = List.of(stat(UUID.randomUUID()));

        assertThat(retrier.saveSluWithRetry(rows)).isEqualTo(SluSaveOutcome.SAVED);

        verify(sluRepository).saveAll(rows);
    }

    @Test
    void saveSluWithRetry_sessionAlreadyPersisted_skipsSaveAll_andReturnsAlreadyPersisted() {
        UUID sessionId = UUID.randomUUID();
        List<PlayerSkillStat> rows = List.of(stat(sessionId));
        when(sluRepository.existsBySessionId(sessionId)).thenReturn(true);

        // skillars-deferred-86 AC2: the explicit check-then-act short-circuits an idempotent retry.
        // skillars-deferred-89 AC2 (code review): ALREADY_PERSISTED, not SAVED — the dispatcher must
        // skip the snapshot write for a session another delivery already owns.
        assertThat(retrier.saveSluWithRetry(rows)).isEqualTo(SluSaveOutcome.ALREADY_PERSISTED);
        verify(sluRepository, never()).saveAll(any());
    }

    @Test
    void saveSluWithRetry_sessionNotYetPersisted_callsSaveAll_andReturnsSaved() {
        UUID sessionId = UUID.randomUUID();
        List<PlayerSkillStat> rows = List.of(stat(sessionId));
        when(sluRepository.existsBySessionId(sessionId)).thenReturn(false);

        assertThat(retrier.saveSluWithRetry(rows)).isEqualTo(SluSaveOutcome.SAVED);

        verify(sluRepository).saveAll(rows);
    }

    @Test
    void saveSluWithRetry_emptyList_callsNothing_andReturnsSaved() {
        // skillars-deferred-89 AC2: an empty batch is not a failed save — must return SAVED (not
        // FAILED) so the dispatcher does not log a spurious "detail save exhausted" ERROR.
        assertThat(retrier.saveSluWithRetry(List.of())).isEqualTo(SluSaveOutcome.SAVED);

        verify(sluRepository, never()).saveAll(any());
        verify(sluRepository, never()).existsBySessionId(any());
    }

    @Test
    void saveSluWithRetry_concurrentDeliveryUniqueCollision_isIdempotentNoOp() {
        // skillars-deferred-89 AC1: two BookingCompletedEvent deliveries for one session both pass the
        // non-locking guards; the loser's saveAll hits V47's uq_player_skill_stats_session_skill with
        // PG 23505. The rows are already persisted by the winner → treat as a no-op: return
        // ALREADY_PERSISTED, saveAll attempted exactly once (the in-method catch consumes it before
        // @Retryable sees it), neither @Recover invoked. ALREADY_PERSISTED (not SAVED) is what stops
        // the dispatcher writing this task's iso-week delta for a bucket the winner never marked.
        UUID sessionId = UUID.randomUUID();
        List<PlayerSkillStat> rows = List.of(stat(sessionId));
        when(sluRepository.existsBySessionId(sessionId)).thenReturn(false);
        when(sluRepository.saveAll(rows)).thenThrow(dive("uq_player_skill_stats_session_skill"));

        assertThat(retrier.saveSluWithRetry(rows)).isEqualTo(SluSaveOutcome.ALREADY_PERSISTED);

        verify(sluRepository, times(1)).saveAll(rows);
    }

    @Test
    void saveSluWithRetry_otherConstraintViolation_propagates() {
        // A different constraint (skill_code FK, PK, NOT NULL) is a real failure — must NOT be
        // swallowed as "already persisted", or the method would report success for a session with
        // zero rows and defeat the AC2 gate. Propagate so @Retryable retries and @Recover fires.
        UUID sessionId = UUID.randomUUID();
        List<PlayerSkillStat> rows = List.of(stat(sessionId));
        when(sluRepository.existsBySessionId(sessionId)).thenReturn(false);
        DataIntegrityViolationException fkViolation = dive("fk_player_skill_stats_skill_code");
        when(sluRepository.saveAll(rows)).thenThrow(fkViolation);

        assertThatThrownBy(() -> retrier.saveSluWithRetry(rows)).isSameAs(fkViolation);
    }

    @Test
    void recoverSluSaveFailure_logsAndReturnsFailed() {
        List<PlayerSkillStat> rows = List.of(stat(UUID.randomUUID()));
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");

        // The @Recover method is the terminal handler after retries are exhausted — it must not
        // rethrow (matching the AOP contract: @Retryable's caller sees a clean return, not a
        // propagated exception). skillars-deferred-89 AC2: it now returns FAILED so the dispatcher
        // skips the weekly-snapshot write.
        assertThat(retrier.recoverSluSaveFailure(ex, rows)).isEqualTo(SluSaveOutcome.FAILED);
    }

    @Test
    void recoverSluSaveFailure_transactionException_logsAndReturnsFailed() {
        List<PlayerSkillStat> rows = List.of(stat(UUID.randomUUID()));
        TransactionSystemException ex = new TransactionSystemException("commit failed");

        // The TransactionException @Recover overload is the terminal handler for a retried
        // transaction begin/commit failure — same no-rethrow contract, also returns FAILED.
        assertThat(retrier.recoverSluSaveFailure(ex, rows)).isEqualTo(SluSaveOutcome.FAILED);
    }

    @Test
    void saveSluWithRetry_isNotTransactional_soTheSaveAllCollisionFiresInsideTheCatch() throws Exception {
        // skillars-deferred-89 AC1 (code review P8): the V47 collision catch works ONLY because
        // saveSluWithRetry is not @Transactional — SimpleJpaRepository.saveAll then opens/commits its
        // own transaction and 23505 fires inside the try block. A @Transactional on the class or the
        // method would move the flush to the outer boundary, past the catch, silently restoring the
        // retry storm + the false "rows lost" ERROR + (post-AC2) a snapshot under-report. Reflective
        // guard so that regression fails a fast unit test, not just review vigilance.
        assertThat(SluPersistenceRetrier.class.isAnnotationPresent(
            org.springframework.transaction.annotation.Transactional.class))
            .as("SluPersistenceRetrier must not be @Transactional — see class javadoc")
            .isFalse();
        assertThat(SluPersistenceRetrier.class
            .getMethod("saveSluWithRetry", List.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
            .as("saveSluWithRetry must not be @Transactional — see class javadoc")
            .isFalse();
    }

    @Test
    void saveSluWithRetry_repositoryThrows_propagatesToCaller() {
        List<PlayerSkillStat> rows = List.of(new PlayerSkillStat());
        TransientDataAccessResourceException ex = new TransientDataAccessResourceException("db unavailable");
        org.mockito.Mockito.doThrow(ex).when(sluRepository).saveAll(rows);

        // Without the Spring AOP proxy (plain unit instantiation), @Retryable never intercepts —
        // this pins that the un-proxied method itself is a thin pass-through with no retry logic
        // baked into the method body, confirming retries come from the proxy, not self-invocation.
        assertThatThrownBy(() -> retrier.saveSluWithRetry(rows)).isSameAs(ex);
    }
}
