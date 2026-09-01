package com.softropic.skillars.platform.scheduler;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.service.BookingExpiryScheduler;
import com.softropic.skillars.platform.booking.service.BookingReminderScheduler;
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
 * skillars-deferred-89 AC4 — a regression guard on an advisor order that is ALREADY pinned in
 * production; no production code changes with this story.
 *
 * <p>{@code BookingExpiryScheduler.expireStaleRequests}, {@code BookingReminderScheduler
 * .processReminderWindows} and {@code BandwidthResetService.resetMonthlyBandwidth} each stack
 * {@code @SchedulerLock} and {@code @Transactional} on one method. {@code AsyncConfig}'s
 * {@code @EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)} forces the ShedLock advisor
 * to higher precedence than the (bare, {@code LOWEST_PRECEDENCE}) transaction advisor from
 * {@code DataSourceConfig}'s {@code @EnableTransactionManagement} — so ShedLock sits <em>outermost</em>
 * and {@code proceed()} runs the DB transaction to commit/rollback <em>before</em> the lock is
 * released. Shipped {@code 7e697d4} (2026-07-02).
 *
 * <p>Three assertions per scheduler bean, so the guard cannot stay green through the regression it
 * exists to catch (code review P7):
 * <ol>
 *   <li>a ShedLock method advisor precedes the {@link TransactionInterceptor} advisor in
 *       {@link Advised#getAdvisors()} (application order, outermost first);</li>
 *   <li>the ShedLock advisor's {@code getOrder()} is strictly less than the transaction advisor's
 *       <em>and</em> strictly less than {@link Ordered#LOWEST_PRECEDENCE} — removing the
 *       {@code order = Ordered.LOWEST_PRECEDENCE - 100} from {@code AsyncConfig} would leave both at
 *       {@code LOWEST_PRECEDENCE}, a registration-order tie that assertion (1) alone might still
 *       pass;</li>
 *   <li>both advisors' pointcuts actually match the scheduled method — so moving {@code @Transactional}
 *       (or {@code @SchedulerLock}) onto a sibling method that assertion (1)/(2) would not notice
 *       still fails here.</li>
 * </ol>
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim (no {@code @MockitoBean} /
 * {@code @TestPropertySource} / extra config) so the CI context count is unchanged.
 */
class SchedulerLockTransactionOrderingIT extends AbstractIntegrationTest {

    @Autowired private BookingExpiryScheduler bookingExpiryScheduler;
    @Autowired private BookingReminderScheduler bookingReminderScheduler;
    @Autowired private BandwidthResetService bandwidthResetService;

    @Test
    void bookingExpiryScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor() {
        assertShedLockOutermost(bookingExpiryScheduler, "expireStaleRequests");
    }

    @Test
    void bookingReminderScheduler_shedLockAdvisorIsOutsideTheTransactionAdvisor() {
        assertShedLockOutermost(bookingReminderScheduler, "processReminderWindows");
    }

    @Test
    void bandwidthResetService_shedLockAdvisorIsOutsideTheTransactionAdvisor() {
        assertShedLockOutermost(bandwidthResetService, "resetMonthlyBandwidth");
    }

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
