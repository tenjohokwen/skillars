package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickCompleteTimeoutServiceTest {

    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private BookingService bookingService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConfigService configService;

    private QuickCompleteTimeoutService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long PLAYER_ID = 200L;
    private static final Long PARENT_ID = 300L;

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate that executes the callback immediately (no real TX in unit test)
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        service = new QuickCompleteTimeoutService(
            completionDataRepository, bookingService, eventPublisher, configService, txTemplate
        );
        lenient().when(configService.getLong("booking.quick_complete_timeout_hours")).thenReturn(24L);
    }

    @Test
    void processExpiredQuickCompletes_expiredCompletion_transitionsAndPublishesEventWithoutTouchingAnySessionPackDependency() {
        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(BOOKING_ID);
        scd.setCoachId(COACH_ID);
        scd.setPlayerId(PLAYER_ID);
        scd.setPlayerAttended(true);
        scd.setEffortRating(4);
        scd.setFocusRating(3);
        scd.setTechniqueRating(5);
        when(completionDataRepository.findPendingQuickCompletes(any(Instant.class))).thenReturn(List.of(scd));

        Booking booking = new Booking();
        booking.setParentId(PARENT_ID);
        when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);

        service.processExpiredQuickCompletes();

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE), any(TransitionContext.class));

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingCompletedEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.getParentId()).isEqualTo(PARENT_ID);
    }

    @Test
    void processExpiredQuickCompletes_noExpiredCompletions_doesNothing() {
        when(completionDataRepository.findPendingQuickCompletes(any(Instant.class))).thenReturn(List.of());

        service.processExpiredQuickCompletes();

        verify(bookingService, org.mockito.Mockito.never()).transition(any(), any(), any());
        verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any());
    }

    @Test
    void hasNoSessionPackDependency() {
        // Regression guard for the accept/completion double-deduction bug (Story 11.2 Task 2):
        // QuickCompleteTimeoutService must have no dependency on PackSessionService,
        // SessionPackPurchaseRepository, or legacy SessionPackService — accept-time deduction is
        // owned entirely by PaymentLifecycleService. Reflection on declared fields is a stronger
        // guard than a runtime mock-verify: it fails at the class level the moment such a
        // dependency is reintroduced, regardless of which test path is exercised.
        List<String> dependencyTypeNames = java.util.Arrays.stream(QuickCompleteTimeoutService.class.getDeclaredFields())
            .map(f -> f.getType().getSimpleName())
            .toList();

        assertThat(dependencyTypeNames)
            .doesNotContain("PackSessionService", "SessionPackPurchaseRepository", "SessionPackService");
    }
}
