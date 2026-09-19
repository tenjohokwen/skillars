package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqEntry;
import com.softropic.skillars.platform.development.repo.RadarCompositeDlqRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RadarCompositeDlqProcessorTest {

    @Mock private RadarCompositeDlqRepository dlqRepository;
    @Mock private RadarCompositeCalculationService compositeCalculationService;
    @Mock private ConfigService configService;
    @Mock private TransactionTemplate transactionTemplate;

    private RadarCompositeDlqProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RadarCompositeDlqProcessor(dlqRepository, compositeCalculationService, configService, transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private RadarCompositeDlqEntry entry() {
        RadarCompositeDlqEntry row = new RadarCompositeDlqEntry();
        row.setId(UUID.randomUUID());
        row.setPlayerId(500L);
        row.setParentId(600L);
        row.setSkillCodes(List.of("PAC"));
        row.setAttempts(0);
        row.setNextRetryAt(Instant.now());
        return row;
    }

    @Test
    void process_successfulReplay_marksRowCompleted() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(dlqRepository.completeClaimed(eq(row.getId()), any())).thenReturn(1);

        processor.process();

        verify(compositeCalculationService).recalculateComposite(500L, 600L, Set.of("PAC"));
        // skillars-deferred-123 code review 2026-09-18 (Decision 5): the terminal write is now a
        // conditional UPDATE guarded on this run still owning the claim, not an unconditional
        // save(row) of a detached entity — so assert on the guarded call, not on the in-memory status.
        verify(dlqRepository).completeClaimed(eq(row.getId()), any());
        verify(dlqRepository, never()).save(any());
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 5). {@code process()} is not
     * {@code @Transactional} and {@code findClaimedBatch} is a native query run outside a transaction,
     * so every row is DETACHED; the old {@code save(row)} was an {@code em.merge()} that rewrote every
     * column from a possibly-stale snapshot, with no {@code @Version} on this entity. If another
     * instance re-claimed the row after this one's lock expired, that merge silently erased its
     * attempts/backoff — or resurrected a COMPLETED row to PENDING with attempts reset to 0, so
     * {@code max_attempts} could never be reached and the row would be dispatched forever.
     *
     * <p>A zero affected-row count means exactly that case: the claim is gone. The run must then do
     * nothing at all rather than write stale columns over the new owner's.
     */
    @Test
    void process_claimLostBeforeCompletion_writesNothingAndDoesNotThrow() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(dlqRepository.completeClaimed(eq(row.getId()), any())).thenReturn(0);

        assertThatCode(() -> processor.process()).doesNotThrowAnyException();

        verify(dlqRepository).completeClaimed(eq(row.getId()), any());
        verify(dlqRepository, never()).save(any());
        verify(dlqRepository, never()).failClaimed(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void process_failureBelowMaxAttempts_writesPendingThroughTheClaimGuard() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong())).thenReturn(5L);
        when(dlqRepository.failClaimed(eq(row.getId()), any(), eq("PENDING"), eq(1), any(), any())).thenReturn(1);
        doThrow(new RuntimeException("still failing")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        // The guard carries the same values the old unconditional merge would have written.
        verify(dlqRepository).failClaimed(eq(row.getId()), any(), eq("PENDING"), eq(1), any(), any());
        verify(dlqRepository, never()).save(any());
    }

    @Test
    void process_failureBelowMaxAttempts_reschedulesAsPending() {
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong())).thenReturn(5L);
        doThrow(new RuntimeException("still failing")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getNextRetryAt()).isAfter(Instant.now());
    }

    @Test
    void process_failureAtMaxAttempts_marksDead() {
        RadarCompositeDlqEntry row = entry();
        row.setAttempts(4);
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong())).thenReturn(5L);
        doThrow(new RuntimeException("still failing")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        assertThat(row.getStatus()).isEqualTo("DEAD");
        assertThat(row.getAttempts()).isEqualTo(5);
    }

    @Test
    void handleFailure_readsMaxAttemptsThroughTheRangeBoundedAccessor() {
        // skillars-deferred-107 AC2: revert the call site to getLong(key, 5L) and this verify fails —
        // a 0/negative stored value must not be able to dead-letter every row on its first attempt.
        RadarCompositeDlqEntry row = entry();
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(row));
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong())).thenReturn(5L);
        doThrow(new RuntimeException("boom")).when(compositeCalculationService)
            .recalculateComposite(500L, 600L, Set.of("PAC"));

        processor.process();

        verify(configService).getBoundedLong("platform.development.radar_composite_dlq.max_attempts", 5L, 1L, 100L);
    }

    /**
     * skillars-deferred-124 AC2 Task 5 (design revised by its own code review, 2026-09-19).
     * {@code processRow} no longer catches anything itself — {@code process()}'s outer guard is the
     * sole call site for {@code handleFailure}, exercised here via an ordinary {@code
     * recalculateComposite} failure. See {@code handleFailure_itselfThrows_...} below for the case
     * where {@code handleFailure} itself throws.
     */
    @Test
    void process_middleRowThrows_isolatesBatchAndReachesFailureBookkeeping() {
        RadarCompositeDlqEntry rowA = entry();
        RadarCompositeDlqEntry rowMiddle = entry();
        rowMiddle.setPlayerId(501L);
        RadarCompositeDlqEntry rowC = entry();
        rowC.setPlayerId(502L);
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(rowA, rowMiddle, rowC));
        when(dlqRepository.completeClaimed(eq(rowA.getId()), any())).thenReturn(1);
        when(dlqRepository.completeClaimed(eq(rowC.getId()), any())).thenReturn(1);
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong())).thenReturn(5L);
        when(dlqRepository.failClaimed(eq(rowMiddle.getId()), any(), eq("PENDING"), eq(1), any(), any())).thenReturn(1);
        lenient().doThrow(new RuntimeException("middle row recalculation failure"))
            .when(compositeCalculationService).recalculateComposite(501L, 600L, Set.of("PAC"));

        processor.process();

        verify(dlqRepository).completeClaimed(eq(rowA.getId()), any());
        verify(dlqRepository).completeClaimed(eq(rowC.getId()), any());
        assertThat(rowMiddle.getAttempts())
            .as("the failing row must genuinely reach failure bookkeeping, not just avoid crashing the loop")
            .isEqualTo(1);
        assertThat(rowMiddle.getStatus()).isEqualTo("PENDING");
    }

    /**
     * skillars-deferred-124 AC2 Task 5 / Task 1 (design revised by its own code review, 2026-09-19):
     * the case that exercises the {@code catch (Exception inner)} branch in {@code process()}'s outer
     * guard — {@code handleFailure} itself throwing, which used to (pre-AC2) abort the loop for every
     * remaining row. {@code handleFailure} is now the sole call site reachable from {@code processRow},
     * so this also regression-guards the double-{@code handleFailure}-call bug the review found:
     * {@code rowMiddle.getAttempts()} below must stay at exactly 1 (one {@code handleFailure} call
     * mutates the detached POJO once before {@code getBoundedLong} throws and the transaction rolls
     * back), never 2 — a prior design that called {@code handleFailure} a second time from here on the
     * same row would double it.
     */
    @Test
    void handleFailure_itselfThrows_stillIsolatesBatchAndDoesNotAbortTheLoop() {
        RadarCompositeDlqEntry rowA = entry();
        RadarCompositeDlqEntry rowMiddle = entry();
        rowMiddle.setPlayerId(501L);
        RadarCompositeDlqEntry rowC = entry();
        rowC.setPlayerId(502L);
        when(dlqRepository.findClaimedBatch(any(), anyInt())).thenReturn(List.of(rowA, rowMiddle, rowC));
        when(dlqRepository.completeClaimed(eq(rowA.getId()), any())).thenReturn(1);
        when(dlqRepository.completeClaimed(eq(rowC.getId()), any())).thenReturn(1);
        lenient().doThrow(new RuntimeException("middle row recalculation failure"))
            .when(compositeCalculationService).recalculateComposite(501L, 600L, Set.of("PAC"));
        // configService.getBoundedLong is the first call inside handleFailure's own
        // transactionTemplate.execute — throwing here means handleFailure itself never completes,
        // exactly the gap the outer guard's inner catch exists for.
        when(configService.getBoundedLong(eq("platform.development.radar_composite_dlq.max_attempts"), anyLong(), anyLong(), anyLong()))
            .thenThrow(new IllegalStateException("config lookup boom"));

        assertThatCode(() -> processor.process())
            .as("a failure inside handleFailure itself must not abort the batch")
            .doesNotThrowAnyException();

        verify(dlqRepository).completeClaimed(eq(rowA.getId()), any());
        // row C, after the doubly-failing middle row, must still be reached and complete normally.
        verify(dlqRepository).completeClaimed(eq(rowC.getId()), any());
        verify(dlqRepository, never()).failClaimed(any(), any(), any(), anyInt(), any(), any());
        assertThat(rowMiddle.getAttempts())
            .as("handleFailure must be called at most once per row per tick — a design that let the "
                + "outer guard call it a second time would double this")
            .isEqualTo(1);
    }

    @Test
    void process_carriesSchedulerLock() throws NoSuchMethodException {
        // skillars-deferred-118 AC3: findClaimedBatch() is not scoped to the calling invocation's
        // own claim and RadarCompositeDlqEntry carries no @Version, so a concurrent invocation could
        // overwrite another's status/attempts/lastError/nextRetryAt with no optimistic-lock
        // protection — @SchedulerLock closes this by preventing the concurrent invocation entirely.
        Method method = RadarCompositeDlqProcessor.class.getMethod("process");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("process() must carry @SchedulerLock").isNotNull();
        assertThat(lock.name()).isNotBlank();
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        // skillars-deferred-123 AC4: lockAtLeastFor is now a property expression (an operator
        // lowering platform.development.radar_composite_dlq.poll_delay_ms now has a matching knob to
        // raise this floor) — pin the exact expression AND assert the embedded default resolves to a
        // positive duration (code review 2026-09-18 Patch: this comment previously claimed the latter
        // without the code doing it).
        assertThat(lock.lockAtLeastFor()).isEqualTo("${platform.development.radar_composite_dlq.lock_at_least:PT30S}");
        assertThat(Duration.parse(defaultOf(lock.lockAtLeastFor()))).isPositive();
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Decision 3). This class's stale-claim window and
     * its {@code lockAtMostFor} are both 10 minutes — exactly equal, where its structural twin
     * {@code VideoDeletionOutboxProcessor} documents strict inequality as mandatory
     * (skillars-deferred-120 Decision 1). That equality only became dangerous when AC3 re-keyed
     * {@code resetStaleClaimed} from {@code next_retry_at} (eligibility time) to {@code claimed_at}
     * (actual claim time): at lock expiry the next instance's deadline is already past this run's
     * claim stamp, so it resets and re-claims rows this instance is still processing.
     *
     * <p>Rather than widen the window, {@code process()} now self-terminates at
     * {@code MAX_RUN_DURATION}, so the run cannot still be in flight when the lock expires. That makes
     * {@code MAX_RUN_DURATION < lockAtMostFor} the load-bearing inequality here, and this test is what
     * stops a future edit from raising the budget to or past the lock and silently restoring the
     * duplicate-processing path.
     */
    @Test
    void runtimeBudget_staysStrictlyInsideLock() throws Exception {
        Duration maxRun = readDuration("MAX_RUN_DURATION");
        Duration staleWindow = readDuration("STALE_CLAIM_WINDOW");
        Duration lockAtMostFor = Duration.parse(
            RadarCompositeDlqProcessor.class.getMethod("process")
                .getAnnotation(SchedulerLock.class).lockAtMostFor());

        assertThat(maxRun)
            .as("the run must self-terminate strictly before its own lock can expire")
            .isLessThan(lockAtMostFor);
        assertThat(maxRun)
            .as("a run must also not outlast the stale-claim window, or it could have its own rows reclaimed")
            .isLessThan(staleWindow);
    }

    private static Duration readDuration(String fieldName) throws Exception {
        java.lang.reflect.Field field = RadarCompositeDlqProcessor.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Duration) field.get(null);
    }

    /** Extracts the {@code default} out of a {@code ${property:default}} SchedulerLock expression. */
    private static String defaultOf(String springPropertyExpression) {
        String withoutBraces = springPropertyExpression.replace("${", "").replace("}", "");
        return withoutBraces.substring(withoutBraces.indexOf(':') + 1);
    }
}
