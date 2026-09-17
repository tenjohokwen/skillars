package com.softropic.skillars.platform.scheduler;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.service.BookingExpiryScheduler;
import com.softropic.skillars.platform.booking.service.BookingReminderScheduler;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.service.AuthCleanupService;
import com.softropic.skillars.platform.security.service.UserAdminService;
import com.softropic.skillars.platform.video.service.BandwidthResetService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

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
 * via an uncaught {@code UnexpectedRollbackException} at commit).
 *
 * <p><strong>skillars-deferred-120 AC3 restores the advisor-ordering assertion below</strong> —
 * {@code UserAdminService.removeNotActivatedUsers} and {@code AuthCleanupService}'s two methods
 * legitimately stack both {@code @SchedulerLock} and {@code @Transactional} (none of the three has
 * the booking schedulers' batch-wide-shared-transaction shape: {@code UserAdminService} is
 * {@code NOT_SUPPORTED} around a loop that already isolates each delete in its own
 * {@code REQUIRES_NEW}, and both {@code AuthCleanupService} methods are single bulk statements), per
 * this class's own prior instruction to do exactly that rather than resurrect the absence-only
 * comment. {@link #authCleanupServicePurgeExpiredRefreshTokens_shedLockAdvisorIsOutsideTheTransactionAdvisor()}
 * exercises it against {@code AuthCleanupService.purgeExpiredRefreshTokens} (plain
 * {@code @Transactional}, the REQUIRED-propagation shape the original assertion below was written
 * for); {@link #userAdminServiceRemoveNotActivatedUsers_shedLockAdvisorIsOutsideTheTransactionAdvisor()}
 * covers the distinct {@code NOT_SUPPORTED}-propagation shape this class's own extensive Javadoc
 * argues is equally safe — added by code review 2026-09-17 (Patch #9) after an earlier draft
 * asserted "one representative is enough" while leaving that specific, at-length-argued
 * combination untested, which assertion (3) below (pointcut-matches-this-method) exists
 * specifically to guard. {@code AuthCleanupService.purgeOldLoginAttempts} is not given its own
 * test: it shares {@code purgeExpiredRefreshTokens}'s exact shape (plain {@code @Transactional},
 * same class, same {@code AsyncConfig}-wide advisor ordering) rather than a distinct one.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim (no {@code @MockitoBean} /
 * {@code @TestPropertySource} / extra config) so the CI context count is unchanged.
 */
class SchedulerLockTransactionOrderingIT extends AbstractIntegrationTest {

    @Autowired private BookingExpiryScheduler bookingExpiryScheduler;
    @Autowired private BookingReminderScheduler bookingReminderScheduler;
    @Autowired private BandwidthResetService bandwidthResetService;
    @Autowired private AuthCleanupService authCleanupService;
    @Autowired private UserAdminService userAdminService;

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
     * skillars-deferred-120 AC3 Finding 2 — restores the advisor-ordering assertion this class's own
     * Javadoc instructed reintroducing "if a future scheduler legitimately needs to stack both
     * annotations again". {@code purgeExpiredRefreshTokens} keeps its existing plain
     * {@code @Transactional} alongside the new {@code @SchedulerLock} (safe here: a single bulk
     * statement, not a batch-wide transaction one item's exception could roll back) — uses {@link
     * #assertShedLockOutermost}'s three-assertion shape below (code review 2026-09-17, Patch #14:
     * an earlier draft of this Javadoc pointed at
     * {@code bookingExpiryScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor}, a method that no
     * longer exists in this file — it was renamed to {@code
     * bookingExpiryScheduler_isNotTransactional_soOneRacedBookingCannotRollBackTheBatch} by
     * {@code skillars-deferred-118} and now uses {@link #assertNotTransactional}, not this helper).
     */
    @Test
    void authCleanupServicePurgeExpiredRefreshTokens_shedLockAdvisorIsOutsideTheTransactionAdvisor() {
        assertShedLockOutermost(authCleanupService, "purgeExpiredRefreshTokens");
    }

    /**
     * skillars-deferred-120 code review (2026-09-17, Patch #9) — the restored assertion above only
     * covered {@code AuthCleanupService}'s plain {@code @Transactional} (REQUIRED) shape; this
     * story's own Javadoc on {@code UserAdminService.removeNotActivatedUsers} argues at length that
     * stacking {@code @SchedulerLock} with {@code @Transactional(propagation = NOT_SUPPORTED)} is
     * equally safe, but left that distinct combination untested. Covers it directly rather than
     * relying on "the advisor ordering is `AsyncConfig`-wide, not per-bean" as a substitute —
     * {@link #assertShedLockOutermost}'s own assertion (3) exists specifically to catch
     * {@code @Transactional}/{@code @SchedulerLock} moving to (or missing from) a particular method,
     * which a per-bean argument alone does not verify.
     */
    @Test
    void userAdminServiceRemoveNotActivatedUsers_shedLockAdvisorIsOutsideTheTransactionAdvisor() {
        assertShedLockOutermost(userAdminService, "removeNotActivatedUsers");
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

    /**
     * skillars-deferred-120 AC3 Finding 2 — restored per this class's own prior instruction (see
     * class Javadoc). Three assertions, so the guard cannot stay green through the regression it
     * exists to catch (originally skillars-deferred-89 code review P7):
     * <ol>
     *   <li>a ShedLock method advisor precedes the {@link TransactionInterceptor} advisor in
     *       {@link Advised#getAdvisors()} (application order, outermost first);</li>
     *   <li>the ShedLock advisor's {@code getOrder()} is strictly less than the transaction advisor's
     *       <em>and</em> strictly less than {@link Ordered#LOWEST_PRECEDENCE} — removing the
     *       {@code order = Ordered.LOWEST_PRECEDENCE - 100} from {@code AsyncConfig} would leave both
     *       at {@code LOWEST_PRECEDENCE}, a registration-order tie that assertion (1) alone might
     *       still pass;</li>
     *   <li>both advisors' pointcuts actually match the scheduled method — so moving
     *       {@code @Transactional} (or {@code @SchedulerLock}) onto a sibling method that assertion
     *       (1)/(2) would not notice still fails here.</li>
     * </ol>
     */
    private static void assertShedLockOutermost(Object bean, String scheduledMethodName) {
        assertThat(AopUtils.isAopProxy(bean))
            .as("%s must be an AOP proxy (it carries @SchedulerLock + @Transactional)", bean.getClass())
            .isTrue();

        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Method scheduledMethod = Arrays.stream(targetClass.getMethods())
            .filter(m -> m.getName().equals(scheduledMethodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no method %s on %s".formatted(scheduledMethodName, targetClass)));

        List<Advisor> advisors = List.of(((Advised) bean).getAdvisors());
        // getAdvisors() returns the interceptor chain in application order — outermost first.
        int shedLockIdx = indexOfFirst(advisors, SchedulerLockTransactionOrderingIT::isShedLock);
        int txIdx = indexOfFirst(advisors, a -> a.getAdvice() instanceof TransactionInterceptor);

        assertThat(shedLockIdx)
            .as("a ShedLock method advisor must be present on %s — advisors: %s",
                bean.getClass(), adviceClassNames(advisors))
            .isGreaterThanOrEqualTo(0);
        assertThat(txIdx)
            .as("a TransactionInterceptor advisor must be present on %s — advisors: %s",
                bean.getClass(), adviceClassNames(advisors))
            .isGreaterThanOrEqualTo(0);

        Advisor shedLock = advisors.get(shedLockIdx);
        Advisor tx = advisors.get(txIdx);

        // (1) chain position
        assertThat(shedLockIdx)
            .as("ShedLock advisor (idx %s) must sit OUTSIDE the transaction advisor (idx %s) so the "
                + "lock is released only after the transaction commits — advisors: %s",
                shedLockIdx, txIdx, adviceClassNames(advisors))
            .isLessThan(txIdx);

        // (2) explicit order value — guards the `order = Ordered.LOWEST_PRECEDENCE - 100` line itself.
        assertThat(shedLock).isInstanceOf(Ordered.class);
        assertThat(tx).isInstanceOf(Ordered.class);
        int shedLockOrder = ((Ordered) shedLock).getOrder();
        int txOrder = ((Ordered) tx).getOrder();
        assertThat(shedLockOrder)
            .as("ShedLock advisor order (%s) must be < the transaction advisor order (%s) AND "
                + "< Ordered.LOWEST_PRECEDENCE (%s) — i.e. AsyncConfig's explicit "
                + "@EnableSchedulerLock(order = LOWEST_PRECEDENCE - 100) is still in force; without it "
                + "both default to LOWEST_PRECEDENCE and the outermost advisor is a registration-order "
                + "coin-flip", txOrder, Ordered.LOWEST_PRECEDENCE)
            .isLessThan(txOrder)
            .isLessThan(Ordered.LOWEST_PRECEDENCE);

        // (3) both pointcuts actually match the scheduled method — catches @Transactional /
        //     @SchedulerLock moving to a sibling method (assertions 1/2 look at bean-level advisors
        //     and would not notice).
        assertThat(pointcutMatches(shedLock, scheduledMethod, targetClass))
            .as("the ShedLock advisor's pointcut must match %s.%s", targetClass.getSimpleName(), scheduledMethodName)
            .isTrue();
        assertThat(pointcutMatches(tx, scheduledMethod, targetClass))
            .as("the transaction advisor's pointcut must match %s.%s — is @Transactional still on "
                + "this method?", targetClass.getSimpleName(), scheduledMethodName)
            .isTrue();
    }

    private static boolean isShedLock(Advisor a) {
        return a.getAdvice().getClass().getName().startsWith("net.javacrumbs.shedlock");
    }

    private static boolean pointcutMatches(Advisor advisor, Method method, Class<?> targetClass) {
        if (!(advisor instanceof PointcutAdvisor pointcutAdvisor)) {
            return false;
        }
        return pointcutAdvisor.getPointcut().getClassFilter().matches(targetClass)
            && pointcutAdvisor.getPointcut().getMethodMatcher().matches(method, targetClass);
    }

    private static int indexOfFirst(List<Advisor> advisors, java.util.function.Predicate<Advisor> p) {
        for (int i = 0; i < advisors.size(); i++) {
            if (p.test(advisors.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> adviceClassNames(List<Advisor> advisors) {
        return advisors.stream().map(a -> a.getAdvice().getClass().getName()).toList();
    }
}
