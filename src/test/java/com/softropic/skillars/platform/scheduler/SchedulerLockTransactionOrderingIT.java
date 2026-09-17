package com.softropic.skillars.platform.scheduler;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.service.BookingExpiryScheduler;
import com.softropic.skillars.platform.booking.service.BookingReminderScheduler;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.video.service.BandwidthResetService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-89 AC4 — a regression guard on an advisor order that was pinned in production at
 * the time this test was written.
 *
 * <p>{@code AsyncConfig}'s {@code @EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)}
 * forces the ShedLock advisor to higher precedence than the (bare, {@code LOWEST_PRECEDENCE})
 * transaction advisor from {@code DataSourceConfig}'s {@code @EnableTransactionManagement} — so on
 * any scheduler bean that stacks both {@code @SchedulerLock} and a method-level {@code @Transactional},
 * ShedLock sits <em>outermost</em> and {@code proceed()} runs the DB transaction to commit/rollback
 * <em>before</em> the lock is released. Shipped {@code 7e697d4} (2026-07-02).
 *
 * <p>All three schedulers this test class covers have since moved to per-item
 * {@code TransactionTemplate} scopes and must NOT carry a method-level {@code @Transactional} any
 * more — {@code BookingReminderScheduler.processReminderWindows} and
 * {@code BandwidthResetService.resetMonthlyBandwidth} first, and
 * {@code BookingExpiryScheduler.expireStaleRequests} joining them via skillars-deferred-118 AC1 (it
 * had the identical bug: a method-level {@code @Transactional} around a per-booking loop let one
 * concurrently-raced booking's {@code BookingStateTransitionException} mark the whole physical
 * transaction rollback-only, silently discarding every other booking's already-committed auto-expiry
 * via an uncaught {@code UnexpectedRollbackException} at commit). With no scheduler left in this
 * codebase stacking both annotations, the advisor-ordering assertion this class used to make (whether
 * the ShedLock advisor sits outside the transaction advisor) no longer has a bean to exercise it
 * against; the property worth pinning for all three is instead the <em>absence</em> of
 * {@code @Transactional}. If a future scheduler legitimately needs to stack both annotations again,
 * reintroduce the advisor-ordering assertion alongside it rather than resurrecting this comment.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim (no {@code @MockitoBean} /
 * {@code @TestPropertySource} / extra config) so the CI context count is unchanged.
 */
class SchedulerLockTransactionOrderingIT extends AbstractIntegrationTest {

    @Autowired private BookingExpiryScheduler bookingExpiryScheduler;
    @Autowired private BookingReminderScheduler bookingReminderScheduler;
    @Autowired private BandwidthResetService bandwidthResetService;

    /**
     * skillars-deferred-118 AC1 changed this bean's shape, so the assertion changed with it.
     *
     * <p>{@code expireStaleRequests} no longer stacks {@code @Transactional} — it must not, for the
     * identical reason as its two siblings below: joining {@link BookingService#transition}'s
     * {@code REQUIRED}-propagation transaction inside one batch-wide transaction let a single
     * concurrently-raced booking's {@code BookingStateTransitionException} mark the whole physical
     * transaction rollback-only, discarding every other booking's already-logged-successful expiry
     * from the same run at an uncaught {@code UnexpectedRollbackException} commit. Each booking now
     * commits in its own {@code TransactionTemplate} scope, mirroring
     * {@code BookingReminderScheduler.processReminderWindows} exactly.
     *
     * <p>Pinned as an absence for the same reason as the two siblings below: re-adding
     * {@code @Transactional} looks like tidying up a bare scheduled method, and nothing else would
     * notice.
     */
    @Test
    void bookingExpiryScheduler_isNotTransactional_soOneRacedBookingCannotRollBackTheBatch() {
        assertNotTransactional(bookingExpiryScheduler, "expireStaleRequests", """
            BookingExpiryScheduler.expireStaleRequests must NOT be @Transactional \
            (skillars-deferred-118 AC1). A concurrent coach-accept or parent-cancel racing the \
            scheduler throws BookingStateTransitionException inside BookingService.transition's \
            REQUIRED-propagation transaction; under one batch-wide transaction that marks the whole \
            physical transaction rollback-only and silently discards every other booking's \
            already-committed auto-expiry from the same run via an uncaught \
            UnexpectedRollbackException at commit. The transaction boundary belongs to the \
            per-booking TransactionTemplate scope.""");
    }

    /**
     * skillars-deferred-92's code review changed this bean's shape, so the assertion changed with it.
     *
     * <p>{@code processReminderWindows} no longer stacks {@code @Transactional} — it must not.
     * {@code BookingEmailListener.onBookingReminder} is
     * {@code @TransactionalEventListener(BEFORE_COMMIT)}, so under one batch-wide transaction every
     * booking's outbox enqueue ran at the batch's commit, outside every per-iteration
     * {@code catch}: one failing enqueue rolled back the whole batch — all the transitions, all the
     * {@code *ReminderSentAt} stamps, every other booking's reminder — and because the next run
     * re-selects the same bookings, a deterministic failure meant no reminder was ever sent again.
     * Each booking now commits in its own {@code TransactionTemplate} scope.
     *
     * <p>Pinned as an absence for the same reason as {@code BandwidthResetService} below: re-adding
     * {@code @Transactional} looks like tidying up next to its annotated sibling, and nothing else
     * would notice.
     */
    @Test
    void bookingReminderScheduler_isNotTransactional_soOneBadEnqueueCannotRollBackTheBatch() {
        assertNotTransactional(bookingReminderScheduler, "processReminderWindows", """
            BookingReminderScheduler.processReminderWindows must NOT be @Transactional \
            (skillars-deferred-92 code review). Its BEFORE_COMMIT enqueue listener runs at the \
            enclosing transaction's commit, so a batch-wide transaction turns one failed enqueue \
            into a full-batch rollback and, if the failure is deterministic, a permanent stall. The \
            transaction boundary belongs to the per-booking TransactionTemplate scope.""");
    }

    /**
     * skillars-deferred-92 AC9.2 changed this bean's shape, so the assertion changed with it.
     *
     * <p>{@code resetMonthlyBandwidth} no longer stacks {@code @Transactional} — it must not. It is
     * now a loop of independently-committed chunks, and wrapping that loop in one transaction would
     * hold every row lock it takes until the very end: the same total lock footprint as the single
     * {@code UPDATE} it replaced, held for longer, which is strictly worse than doing nothing. The
     * per-chunk boundary lives in {@code BandwidthResetChunkProcessor}.
     *
     * <p>What is worth pinning instead is the <em>absence</em> of {@code @Transactional}, because it
     * is exactly the kind of thing a later reader re-adds while tidying up — it looks like an
     * oversight next to an annotated sibling, and nothing else would notice.
     */
    @Test
    void bandwidthResetService_isNotTransactional_soChunksCommitIndependently() {
        assertNotTransactional(bandwidthResetService, "resetMonthlyBandwidth", """
            BandwidthResetService.resetMonthlyBandwidth must NOT be @Transactional \
            (skillars-deferred-92 AC9.2). It drives a chunked loop; one enclosing transaction \
            would hold every row lock until the end and block QuotaService.reserve() for the whole \
            run — worse than the single UPDATE the chunking replaced. The transaction boundary \
            belongs to BandwidthResetChunkProcessor.resetChunk(), one per chunk.""");
    }

    /**
     * Asserts a scheduled method carries no {@code @Transactional} (method- or type-level) while
     * keeping its {@code @SchedulerLock} — dropping the lock alongside the transaction would let a
     * second node run the job concurrently, which none of these beans tolerate.
     */
    private static void assertNotTransactional(Object bean, String scheduledMethodName, String why) {
        Class<?> target = AopUtils.getTargetClass(bean);
        Method scheduled = Arrays.stream(target.getMethods())
            .filter(m -> m.getName().equals(scheduledMethodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no method %s on %s".formatted(scheduledMethodName, target)));

        assertThat(scheduled.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
            .as(why)
            .isNull();
        assertThat(target.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
            .as("nor may %s carry a type-level @Transactional", target.getSimpleName())
            .isNull();
        assertThat(scheduled.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class))
            .as("@SchedulerLock must stay — a second node running this job concurrently is still wrong")
            .isNotNull();
    }
}
