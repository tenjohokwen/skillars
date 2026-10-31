package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.contract.CoachStartReminderEvent;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S-07: CoachStartReminderScheduler — the coach's "press Start" reminders at the start and at start + 10 minutes. */
@ExtendWith(MockitoExtension.class)
class CoachStartReminderSchedulerTest {

    private static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingService bookingService;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private CoachStartReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        TestClockProvider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate tx = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        scheduler = new CoachStartReminderScheduler(bookingRepository, bookingService, coachProfileRepository,
            playerProfileRepository, userRepository, tx, eventPublisher);
        CoachProfile coach = new CoachProfile();
        coach.setUserId(77L);
        User user = new User();
        user.setEmail("coach@test.com");
        PlayerProfile player = new PlayerProfile();
        player.setName("Sam");
        lenient().when(coachProfileRepository.findById(any())).thenReturn(Optional.of(coach));
        lenient().when(userRepository.findById(77L)).thenReturn(Optional.of(user));
        lenient().when(playerProfileRepository.findById(any())).thenReturn(Optional.of(player));
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    private Booking due(String status, Instant start) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        b.setCoachId(UUID.randomUUID());
        b.setPlayerId(5L);
        b.setRequestedStartTime(start);
        b.setCanonicalTimezone("UTC");
        when(bookingRepository.findUnstartedIdsDueForCoachReminder(any(), any(), any())).thenReturn(List.of(b.getId()));
        when(bookingService.lockBookingForUpdate(b.getId())).thenReturn(b);
        return b;
    }

    private CoachStartReminderEvent published() {
        ArgumentCaptor<CoachStartReminderEvent> c = ArgumentCaptor.forClass(CoachStartReminderEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        return c.getValue();
    }

    @Test
    void queriesFromTheStartUntilOneHourAfter_inABoundedPage() {
        when(bookingRepository.findUnstartedIdsDueForCoachReminder(any(), any(), any())).thenReturn(List.of());

        scheduler.remind();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingRepository).findUnstartedIdsDueForCoachReminder(
            eq(NOW), eq(NOW.minus(1, ChronoUnit.HOURS)), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(CoachStartReminderScheduler.BATCH_SIZE);
    }

    @Test
    void atTheScheduledStart_theFirstReminderIsQueued_andOnlyItIsStamped() {
        Booking b = due("UPCOMING", NOW.minus(1, ChronoUnit.MINUTES));

        scheduler.remind();

        CoachStartReminderEvent event = published();
        assertThat(event.getReminderType()).isEqualTo(CoachStartReminderEvent.START);
        assertThat(event.getCoachEmail()).isEqualTo("coach@test.com");
        assertThat(event.getPlayerName()).isEqualTo("Sam");
        assertThat(b.getCoachStartReminderSentAt()).isEqualTo(NOW);
        assertThat(b.getCoachLateStartReminderSentAt()).isNull();
        verify(bookingRepository).save(b);
    }

    @Test
    void atStartPlusTenMinutes_theLateReminderIsQueued_afterTheFirst() {
        Booking b = due("UPCOMING", NOW.minus(10, ChronoUnit.MINUTES));
        b.setCoachStartReminderSentAt(NOW.minus(9, ChronoUnit.MINUTES));

        scheduler.remind();

        assertThat(published().getReminderType()).isEqualTo(CoachStartReminderEvent.LATE);
        assertThat(b.getCoachLateStartReminderSentAt()).isEqualTo(NOW);
    }

    @Test
    void justBeforeStartPlusTenMinutes_nothingMoreIsSent() {
        Booking b = due("UPCOMING", NOW.minus(9, ChronoUnit.MINUTES));
        b.setCoachStartReminderSentAt(NOW.minus(8, ChronoUnit.MINUTES));

        scheduler.remind();

        verify(eventPublisher, never()).publishEvent(any(CoachStartReminderEvent.class));
        verify(bookingRepository, never()).save(any());
    }

    /** A coach found only after start + 10 min (downtime) gets ONE reminder, never two at once. */
    @Test
    void neverRemindedAndAlreadyPastTenMinutes_getsOneLateReminderThatStampsBoth() {
        Booking b = due("UPCOMING", NOW.minus(25, ChronoUnit.MINUTES));

        scheduler.remind();

        assertThat(published().getReminderType()).isEqualTo(CoachStartReminderEvent.LATE);
        assertThat(b.getCoachStartReminderSentAt()).isEqualTo(NOW);
        assertThat(b.getCoachLateStartReminderSentAt()).isEqualTo(NOW);
    }

    @Test
    void bothAlreadySent_isNeverRepeated() {
        Booking b = due("UPCOMING", NOW.minus(20, ChronoUnit.MINUTES));
        b.setCoachStartReminderSentAt(NOW.minus(19, ChronoUnit.MINUTES));
        b.setCoachLateStartReminderSentAt(NOW.minus(9, ChronoUnit.MINUTES));

        scheduler.remind();

        verify(eventPublisher, never()).publishEvent(any(CoachStartReminderEvent.class));
    }

    @Test
    void aSessionTheCoachStartedMeanwhile_isNotRemindedAndNotStamped() {
        Booking b = due("IN_PROGRESS", NOW.minus(2, ChronoUnit.MINUTES));

        scheduler.remind();

        assertThat(b.getCoachStartReminderSentAt()).isNull();
        verify(eventPublisher, never()).publishEvent(any(CoachStartReminderEvent.class));
    }

    @Test
    void carriesASchedulerLock() throws NoSuchMethodException {
        assertThat(CoachStartReminderScheduler.class.getMethod("remind").getAnnotation(SchedulerLock.class)).isNotNull();
    }
}
