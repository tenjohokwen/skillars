package com.softropic.skillars.infrastructure.e2e;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.platform.booking.service.BookingReminderScheduler;
import com.softropic.skillars.platform.booking.service.StuckCompletionSweeperService;
import com.softropic.skillars.platform.booking.service.UnresolvedSessionSweeperService;
import com.softropic.skillars.platform.payment.service.SessionPackForfeitureScheduler;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class E2eJobResourceTest {

    private final LockProvider lockProvider = mock(LockProvider.class);
    private final SessionPackForfeitureScheduler forfeiture = mock(SessionPackForfeitureScheduler.class);
    private final UnresolvedSessionSweeperService sweeper = mock(UnresolvedSessionSweeperService.class);
    private final StuckCompletionSweeperService stuck = mock(StuckCompletionSweeperService.class);
    private final BookingReminderScheduler reminders = mock(BookingReminderScheduler.class);

    private E2eJobResource resource(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new E2eJobResource(env, lockProvider, forfeiture, sweeper, stuck, reminders);
    }

    @Test
    void runsTheJobUnderItsOwnLockNameWithNoMinimumHold() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        assertThat(resource("dev", "e2e").run("pack-forfeiture").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(forfeiture).forfeitExpiredPacks();
        ArgumentCaptor<LockConfiguration> config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("SessionPackForfeitureScheduler_expire");
        assertThat(config.getValue().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
        verify(lock).unlock();
    }

    @Test
    void runsTheBookingRemindersUnderTheirSchedulerLockName() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        assertThat(resource("dev", "e2e").run("booking-reminders").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(reminders).processReminderWindows();
        ArgumentCaptor<LockConfiguration> config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("BookingReminderScheduler_remind");
        assertThat(config.getValue().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
    }

    @Test
    void runsTheUnresolvedSessionSweeperUnderItsSchedulerLockName() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        assertThat(resource("dev", "e2e").run("unresolved-sessions").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(sweeper).sweep();
        ArgumentCaptor<LockConfiguration> config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("UnresolvedSessionSweeperService_sweep");
        verify(forfeiture, never()).forfeitExpiredPacks();
    }

    @Test
    void runsTheStuckCompletionSweeperUnderItsSchedulerLockName() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        assertThat(resource("dev", "e2e").run("stuck-completions").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(stuck).processStuckCompletions();
        ArgumentCaptor<LockConfiguration> config = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(config.capture());
        assertThat(config.getValue().getName()).isEqualTo("StuckCompletionSweeperService_processStuckCompletions");
        verify(forfeiture, never()).forfeitExpiredPacks();
    }

    @Test
    void answersConflictWithoutRunningWhileTheLockIsHeld() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        assertThat(resource("dev", "e2e").run("pack-forfeiture").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        verify(forfeiture, never()).forfeitExpiredPacks();
    }

    @Test
    void answersNotFoundForAJobNotOnTheList() {
        assertThat(resource("dev", "e2e").run("forfeitExpiredPacks").getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        verify(lockProvider, never()).lock(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "uat"})
    void refusesToStartAlongsideADeployedProfile(String deployed) {
        assertThatThrownBy(() -> resource(deployed, "e2e").refuseDeployedProfiles())
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining(deployed);
    }
}
