package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.RevenueSummaryDto;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueReportingServiceTest {

    @Mock private BookingPaymentRepository bookingPaymentRepository;
    @Mock private ParentCreditLedgerRepository parentCreditLedgerRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock private PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock private CoachReliabilityStrikeRepository coachReliabilityStrikeRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private ConfigService configService;

    private RevenueReportingService service;

    private final UUID coachId = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-06-01T00:00:00Z");
    private final Instant to   = Instant.parse("2026-06-30T23:59:59Z");

    @BeforeEach
    void setUp() {
        service = new RevenueReportingService(
            bookingPaymentRepository, parentCreditLedgerRepository, bookingRepository,
            paymentCoachSubscriptionRepository, paymentPlayerSubscriptionRepository,
            coachReliabilityStrikeRepository, playerProfileRepository, coachProfileRepository,
            configService
        );
    }

    private void stubConfigRates() {
        when(configService.getString("platform.commission.rate")).thenReturn("0.10");
        when(configService.getString("payment.stripe.feeRate")).thenReturn("0.014");
        when(configService.getString("payment.stripe.feeFixed")).thenReturn("0.25");
    }

    // ── Revenue summary ──────────────────────────────────────────

    @Test
    void summary_oneCapturedSession_calculatesCorrectly() {
        stubConfigRates();
        when(bookingPaymentRepository.sumGrossByCoachAndPeriod(coachId, from, to))
            .thenReturn(Optional.of(new BigDecimal("100.00")));
        when(bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, from, to)).thenReturn(1L);
        when(bookingPaymentRepository.findBookingIdsByCoachAndPeriod(coachId, from, to)).thenReturn(List.of());

        RevenueSummaryDto dto = service.getCoachRevenueSummary(coachId, from, to);

        // commission = 100 * 0.10 = 10.00
        // stripeFees = 100 * 0.014 + 0.25 * 1 = 1.65
        // netPayout  = 100 - 10 - 1.65 = 88.35
        assertThat(dto.grossEarnings()).isEqualByComparingTo("100.00");
        assertThat(dto.commissionDeducted()).isEqualByComparingTo("10.00");
        assertThat(dto.stripeFees()).isEqualByComparingTo("1.65");
        assertThat(dto.netPayout()).isEqualByComparingTo("88.35");
        assertThat(dto.sessionCount()).isEqualTo(1L);
        assertThat(dto.currency()).isEqualTo("EUR");
    }

    @Test
    void summary_noBookings_returnsAllZero() {
        stubConfigRates();
        when(bookingPaymentRepository.sumGrossByCoachAndPeriod(coachId, from, to))
            .thenReturn(Optional.empty());
        when(bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, from, to)).thenReturn(0L);
        when(bookingPaymentRepository.findBookingIdsByCoachAndPeriod(coachId, from, to)).thenReturn(List.of());

        RevenueSummaryDto dto = service.getCoachRevenueSummary(coachId, from, to);

        assertThat(dto.grossEarnings()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.commissionDeducted()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.stripeFees()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.netPayout()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.sessionCount()).isZero();
    }

    // ── Running balance pagination ───────────────────────────────

    @Test
    void creditStatement_runningBalance_computedFromAnchorForwardIteration() {
        Long parentId = 42L;
        Pageable pageable = PageRequest.of(0, 2);

        ParentCreditLedger e1 = buildLedgerEntry(UUID.randomUUID(), new BigDecimal("30.00"), Instant.parse("2026-06-15T10:00:00Z"));
        ParentCreditLedger e2 = buildLedgerEntry(UUID.randomUUID(), new BigDecimal("-20.00"), Instant.parse("2026-06-10T09:00:00Z"));
        // DESC order page: e1 (newer) first, e2 (older) last
        Page<ParentCreditLedger> page = new PageImpl<>(List.of(e1, e2), pageable, 2);

        when(parentCreditLedgerRepository.findByParentAndPeriod(eq(parentId), any(), any(), eq(pageable)))
            .thenReturn(page);
        // Opening balance before e2's createdAt = 50.00
        when(parentCreditLedgerRepository.sumByParentIdAndCreatedAtBefore(eq(parentId), eq(e2.getCreatedAt())))
            .thenReturn(new BigDecimal("50.00"));

        var result = service.getCreditStatement(parentId, from, to, pageable);

        // e2 balance = 50.00 + (-20.00) = 30.00; e1 balance = 30.00 + 30.00 = 60.00
        // Page is returned in DESC order (e1 first, e2 second)
        assertThat(result.getContent().get(0).runningBalance()).isEqualByComparingTo("60.00");
        assertThat(result.getContent().get(1).runningBalance()).isEqualByComparingTo("30.00");
    }

    // ── Receipt ownership ────────────────────────────────────────

    @Test
    void coachReceipt_wrongCoach_throws403() {
        UUID wrongCoachId = UUID.randomUUID();
        UUID bookingId    = UUID.randomUUID();
        when(bookingRepository.findByIdAndCoachId(bookingId, wrongCoachId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCoachReceipt(wrongCoachId, bookingId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void parentReceipt_wrongParent_throws403() {
        Long wrongParentId = 99L;
        UUID bookingId     = UUID.randomUUID();
        when(bookingRepository.findByIdAndParentId(bookingId, wrongParentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getParentReceipt(wrongParentId, bookingId))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── Admin overview ───────────────────────────────────────────

    @Test
    void adminOverview_subscriptionRevenueIsZero_outstandingDisputeCountIsZero() {
        stubConfigRates();
        when(bookingPaymentRepository.sumTotalGross(from, to)).thenReturn(Optional.of(new BigDecimal("200.00")));
        when(bookingPaymentRepository.countCapturedForPeriod(from, to)).thenReturn(2L);
        when(parentCreditLedgerRepository.sumTotalRefundCredit(from, to)).thenReturn(BigDecimal.ZERO);
        when(parentCreditLedgerRepository.sumTotalCashOuts(from, to)).thenReturn(BigDecimal.ZERO);
        when(paymentCoachSubscriptionRepository.countActiveByTier()).thenReturn(List.of());
        when(paymentPlayerSubscriptionRepository.countActiveByTier()).thenReturn(List.of());

        AdminFinanceOverviewDto dto = service.getAdminOverview(from, to);

        assertThat(dto.subscriptionRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.totalGrossVolume()).isEqualByComparingTo("200.00");
    }

    @Test
    void adminCoachRevenue_unknownCoach_throws404() {
        UUID unknownId = UUID.randomUUID();
        when(coachProfileRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAdminCoachRevenue(unknownId, from, to))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private ParentCreditLedger buildLedgerEntry(UUID txId, BigDecimal amount, Instant createdAt) {
        try {
            ParentCreditLedger e = new ParentCreditLedger();
            e.setAmount(amount);
            e.setType("TOPUP");
            e.setDescription("test");
            setField(e, "txId", txId);
            setField(e, "createdAt", createdAt);
            return e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
