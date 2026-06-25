package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrike;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.event.CoachVisibilityReducedEvent;
import com.softropic.skillars.platform.payment.contract.event.StrikeThresholdReachedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliabilityStrikeServiceTest {

    @Mock CoachReliabilityStrikeRepository strikeRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock ConfigService configService;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks ReliabilityStrikeService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    private CoachProfile coach;

    @BeforeEach
    void setUp() {
        when(configService.getString("reliability.strike.visibilityThreshold")).thenReturn("3");
        when(configService.getString("reliability.strike.suspensionThreshold")).thenReturn("5");
        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(9001L);
        coach.setDisplayName("Test Coach");
        coach.setCanonicalTimezone("UTC");
        coach.setStatus(CoachProfileStatus.ACTIVE);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        when(strikeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firstStrike_noStatusChange() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(1L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_UNEXCUSED");

        assertThat(coach.getStatus()).isEqualTo(CoachProfileStatus.ACTIVE);
        verify(coachProfileRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void belowThreshold_count2_noStatusChange() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(2L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");

        assertThat(coach.getStatus()).isEqualTo(CoachProfileStatus.ACTIVE);
        verify(coachProfileRepository, never()).save(any());
    }

    @Test
    void thirdStrike_reachesVisibilityThreshold_statusSetToReduced() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(3L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_CANCELLATION_UNEXCUSED");

        assertThat(coach.getStatus()).isEqualTo(CoachProfileStatus.REDUCED);
        verify(coachProfileRepository).save(coach);
        verify(eventPublisher).publishEvent(any(CoachVisibilityReducedEvent.class));
    }

    @Test
    void fifthStrike_reachesSuspensionThreshold_statusSetToPendingReview() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(5L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");

        assertThat(coach.getStatus()).isEqualTo(CoachProfileStatus.PENDING_REVIEW);
        verify(coachProfileRepository).save(coach);
        verify(eventPublisher).publishEvent(any(StrikeThresholdReachedEvent.class));
    }

    @Test
    void countQueryUsesRolling30DayWindow() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(1L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");

        ArgumentCaptor<OffsetDateTime> sinceCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(strikeRepository).countByCoachIdAndCreatedAtAfter(eq(COACH_ID), sinceCaptor.capture());
        OffsetDateTime since = sinceCaptor.getValue();
        // Should be approximately now() - 30 days
        OffsetDateTime expected = OffsetDateTime.now().minusDays(30);
        assertThat(since).isAfter(expected.minusMinutes(1)).isBefore(expected.plusMinutes(1));
    }

    @Test
    void fifthStrike_doesNotAlsoSetReduced() {
        when(strikeRepository.countByCoachIdAndCreatedAtAfter(eq(COACH_ID), any(OffsetDateTime.class))).thenReturn(5L);

        service.issue(COACH_ID, BOOKING_ID, "COACH_NO_SHOW");

        // Status should be PENDING_REVIEW, NOT REDUCED — mutually exclusive check
        assertThat(coach.getStatus()).isEqualTo(CoachProfileStatus.PENDING_REVIEW);
        assertThat(coach.getStatus()).isNotEqualTo(CoachProfileStatus.REDUCED);
    }

}
