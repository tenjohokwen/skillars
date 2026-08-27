package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Story Deferred-76 AC7: covers the two definitive booking-payment settle-outcome counters
 * (booking.payment.settle_success / settle_failed) that feed BookingPaymentSettleFailureRateHigh.
 * Mirrors CreditRoutingTest's @Spy SimpleMeterRegistry pattern — a plain @Mock MeterRegistry returns
 * null from Counter.builder(...).register(...), so a real registry is required to assert increments.
 */
@ExtendWith(MockitoExtension.class)
class BookingPaymentPersistenceServiceTest {

    @Mock CreditWalletService creditWalletService;
    @Mock BookingPaymentRepository bookingPaymentRepository;
    @Mock BookingRepository bookingRepository;
    @Mock BookingService bookingService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PessimisticLockRetryer lockRetryer;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks BookingPaymentPersistenceService service;

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final Long PARENT_ID = 1001L;

    @BeforeEach
    void setUp() {
        lenient().when(bookingPaymentRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());
        // @InjectMocks constructs the service but never invokes @PostConstruct — Mockito is not a
        // Spring container. Without this, every settle*Counter field stays null.
        service.initializeCounters();
    }

    @Test
    void persistPaymentSuccess_incrementsSettleSuccessCounter() {
        service.persistPaymentSuccess(BOOKING_ID, BigDecimal.ZERO, BigDecimal.ZERO,
            "pi_test_123", null, PARENT_ID, "parent@test.com", "Coach Name",
            Instant.now(), "Europe/Berlin");

        Counter counter = meterRegistry.find("booking.payment.settle_success").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void persistPaymentFailure_incrementsSettleFailedCounter() {
        service.persistPaymentFailure(BOOKING_ID, BigDecimal.ZERO,
            PARENT_ID, "parent@test.com", "Coach Name", Instant.now(), "Europe/Berlin");

        Counter counter = meterRegistry.find("booking.payment.settle_failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void persistPaymentSuccess_doesNotIncrementSettleFailedCounter() {
        service.persistPaymentSuccess(BOOKING_ID, BigDecimal.ZERO, BigDecimal.ZERO,
            "pi_test_123", null, PARENT_ID, "parent@test.com", "Coach Name",
            Instant.now(), "Europe/Berlin");

        // initializeCounters() eagerly pre-registers all four counters at 0 (standard Micrometer
        // practice, so they appear on dashboards before their first increment) — settle_failed is
        // always registered, just never incremented by a success path.
        Counter counter = meterRegistry.find("booking.payment.settle_failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }
}
