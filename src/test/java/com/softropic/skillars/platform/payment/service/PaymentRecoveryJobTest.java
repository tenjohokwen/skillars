package com.softropic.skillars.platform.payment.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/** skillars-deferred-169 AC3: four duties, each isolated, under one ShedLock. */
@ExtendWith(MockitoExtension.class)
class PaymentRecoveryJobTest {

    @Mock SessionPackPaymentService sessionPackPaymentService;
    @Mock SubscriptionService subscriptionService;
    @InjectMocks PaymentRecoveryJob job;

    @Test
    void run_carriesSchedulerLock() throws NoSuchMethodException {
        SchedulerLock lock = PaymentRecoveryJob.class.getMethod("run").getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("PaymentRecoveryJob_run");
        assertThat(Duration.parse(lock.lockAtMostFor())).isPositive();
        assertThat(Duration.parse(lock.lockAtLeastFor())).isPositive();
    }

    @Test
    void run_runsAllFourDuties() {
        job.run();

        verify(sessionPackPaymentService).resolveStaleAttempts();
        verify(sessionPackPaymentService).purgeTerminalAttempts();
        verify(subscriptionService).drainLinkableParkedEvents();
        verify(subscriptionService).purgeStaleParkedEvents();
    }

    @Test
    void run_aFailingDuty_doesNotStarveTheOthers() {
        doThrow(new IllegalStateException("stripe down")).when(sessionPackPaymentService).resolveStaleAttempts();
        doThrow(new IllegalStateException("db")).when(sessionPackPaymentService).purgeTerminalAttempts();
        doThrow(new IllegalStateException("db")).when(subscriptionService).drainLinkableParkedEvents();

        job.run();

        verify(subscriptionService).purgeStaleParkedEvents();
    }
}
