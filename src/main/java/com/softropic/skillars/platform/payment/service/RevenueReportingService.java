package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
import com.softropic.skillars.platform.payment.contract.CreditStatementEntryDto;
import com.softropic.skillars.platform.payment.contract.ParentReceiptDto;
import com.softropic.skillars.platform.payment.contract.ReceiptDto;
import com.softropic.skillars.platform.payment.contract.RevenueSummaryDto;
import com.softropic.skillars.platform.payment.contract.TransactionDto;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedger;
import com.softropic.skillars.platform.payment.repo.ParentCreditLedgerRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RevenueReportingService {

    private final BookingPaymentRepository bookingPaymentRepository;
    private final ParentCreditLedgerRepository parentCreditLedgerRepository;
    private final BookingRepository bookingRepository;
    private final PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    private final PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    private final CoachReliabilityStrikeRepository coachReliabilityStrikeRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ConfigService configService;

    public RevenueSummaryDto getCoachRevenueSummary(UUID coachId, Instant from, Instant to) {
        Instant effectiveFrom = from != null ? from
            : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant effectiveTo = to != null ? to : Instant.now();

        BigDecimal grossEarnings = bookingPaymentRepository
            .sumGrossByCoachAndPeriod(coachId, effectiveFrom, effectiveTo)
            .orElse(BigDecimal.ZERO);

        BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
        BigDecimal commissionDeducted = grossEarnings.multiply(commissionRate)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal stripeFeeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
        BigDecimal stripeFeeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));

        long sessionCount = bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);

        BigDecimal stripeFees = grossEarnings.multiply(stripeFeeRate)
            .add(stripeFeeFixed.multiply(BigDecimal.valueOf(sessionCount)))
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal netPayout = grossEarnings.subtract(commissionDeducted).subtract(stripeFees);

        List<UUID> bookingIds = bookingPaymentRepository.findBookingIdsByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        BigDecimal refundsIssued = bookingIds.isEmpty()
            ? BigDecimal.ZERO
            : parentCreditLedgerRepository.sumRefundsByBookingIds(bookingIds);

        return new RevenueSummaryDto(grossEarnings, commissionDeducted, stripeFees, netPayout,
            sessionCount, refundsIssued, "EUR");
    }

    public Page<TransactionDto> getCoachTransactions(UUID coachId, Instant from, Instant to, Pageable pageable) {
        Instant effectiveFrom = from != null ? from
            : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant effectiveTo = to != null ? to : Instant.now();

        BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));

        Page<BookingPayment> page = bookingPaymentRepository.findByCoachAndPeriod(coachId, effectiveFrom, effectiveTo, pageable);

        Set<UUID> bookingIds = page.getContent().stream()
            .map(BookingPayment::getBookingId)
            .collect(Collectors.toSet());

        Map<UUID, Booking> bookings = bookingRepository.findAllById(bookingIds).stream()
            .collect(Collectors.toMap(Booking::getId, b -> b));

        Set<Long> playerIds = bookings.values().stream()
            .map(Booking::getPlayerId)
            .collect(Collectors.toSet());

        Map<Long, String> playerNames = playerProfileRepository.findAllById(playerIds).stream()
            .collect(Collectors.toMap(PlayerProfile::getId, PlayerProfile::getName));

        return page.map(bp -> {
            Booking booking = bookings.get(bp.getBookingId());
            String playerName = booking != null
                ? playerNames.getOrDefault(booking.getPlayerId(), "Unknown")
                : "Unknown";
            Instant sessionDate = booking != null ? booking.getRequestedStartTime() : null;
            BigDecimal grossAmount = bp.getStripeCharged().add(bp.getCreditDebited());
            BigDecimal commissionAmount = grossAmount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netAmount = grossAmount.subtract(commissionAmount);
            return new TransactionDto(
                bp.getBookingId(),
                playerName,
                sessionDate,
                grossAmount,
                commissionAmount,
                netAmount,
                bp.getStatus(),
                bp.getCreditDebited()
            );
        });
    }

    public ReceiptDto getCoachReceipt(UUID coachId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndCoachId(bookingId, coachId)
            .orElseThrow(() -> new AccessDeniedException("Access denied to booking receipt"));

        BookingPayment payment = bookingPaymentRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking payment not found", "booking_payment"));

        PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null);
        String playerFirstName = player != null ? extractFirstName(player.getName()) : "Player";

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
        BigDecimal grossAmount = payment.getStripeCharged().add(payment.getCreditDebited());
        BigDecimal commissionDeducted = grossAmount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netReceived = grossAmount.subtract(commissionDeducted);

        return new ReceiptDto(
            bookingId,
            booking.getRequestedStartTime(),
            playerFirstName,
            coach.getDisplayName(),
            "Skillars",
            grossAmount,
            commissionDeducted,
            netReceived
        );
    }

    public ParentReceiptDto getParentReceipt(Long parentId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndParentId(bookingId, parentId)
            .orElseThrow(() -> new AccessDeniedException("Access denied to booking receipt"));

        BookingPayment payment = bookingPaymentRepository.findById(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking payment not found", "booking_payment"));

        PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null);
        String playerFirstName = player != null ? extractFirstName(player.getName()) : "Player";

        CoachProfile coach = coachProfileRepository.findById(booking.getCoachId())
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        BigDecimal totalCharged = payment.getStripeCharged().add(payment.getCreditDebited());

        return new ParentReceiptDto(
            bookingId,
            booking.getRequestedStartTime(),
            playerFirstName,
            coach.getDisplayName(),
            payment.getStripeCharged(),
            payment.getCreditDebited(),
            totalCharged
        );
    }

    public Page<CreditStatementEntryDto> getCreditStatement(Long parentId, Instant from, Instant to, Pageable pageable) {
        Instant effectiveFrom = from != null ? from
            : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant effectiveTo = to != null ? to : Instant.now();

        Page<ParentCreditLedger> page = parentCreditLedgerRepository.findByParentAndPeriod(parentId, effectiveFrom, effectiveTo, pageable);

        if (page.isEmpty()) {
            return page.map(e -> null);
        }

        List<ParentCreditLedger> entries = page.getContent();
        ParentCreditLedger oldest = entries.get(entries.size() - 1);
        BigDecimal openingBalance = parentCreditLedgerRepository.sumByParentIdAndCreatedAtBefore(parentId, oldest.getCreatedAt());

        List<ParentCreditLedger> asc = new ArrayList<>(entries);
        Collections.reverse(asc);
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        BigDecimal balance = openingBalance;
        for (ParentCreditLedger e : asc) {
            balance = balance.add(e.getAmount());
            balances.put(e.getTxId(), balance);
        }

        return page.map(e -> new CreditStatementEntryDto(
            e.getTxId(),
            e.getType(),
            e.getAmount(),
            e.getDescription(),
            e.getReferenceId(),
            e.getCreatedAt(),
            balances.get(e.getTxId())
        ));
    }

    public AdminFinanceOverviewDto getAdminOverview(Instant from, Instant to) {
        Instant effectiveFrom = from != null ? from
            : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant effectiveTo = to != null ? to : Instant.now();

        BigDecimal commissionRate = new BigDecimal(configService.getString("platform.commission.rate"));
        BigDecimal stripeFeeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
        BigDecimal stripeFeeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));

        BigDecimal totalGrossVolume = bookingPaymentRepository.sumTotalGross(effectiveFrom, effectiveTo).orElse(BigDecimal.ZERO);
        BigDecimal totalCommissionCollected = totalGrossVolume.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);

        long totalSessionCount = bookingPaymentRepository.countCapturedForPeriod(effectiveFrom, effectiveTo);
        BigDecimal totalStripeFees = totalGrossVolume.multiply(stripeFeeRate)
            .add(stripeFeeFixed.multiply(BigDecimal.valueOf(totalSessionCount)))
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalRefundCredit = parentCreditLedgerRepository.sumTotalRefundCredit(effectiveFrom, effectiveTo);
        BigDecimal totalCashOuts = parentCreditLedgerRepository.sumTotalCashOuts(effectiveFrom, effectiveTo);

        Map<String, Long> activeCoachSubscriptions = toTierCountMap(paymentCoachSubscriptionRepository.countActiveByTier());
        Map<String, Long> activePlayerSubscriptions = toTierCountMap(paymentPlayerSubscriptionRepository.countActiveByTier());

        // TODO: subscriptionRevenue requires EUR price-amount config keys not yet seeded — V64 only seeds Stripe priceId strings
        BigDecimal subscriptionRevenue = BigDecimal.ZERO;

        return new AdminFinanceOverviewDto(
            totalGrossVolume,
            totalCommissionCollected,
            totalRefundCredit,
            totalCashOuts,
            totalStripeFees,
            activeCoachSubscriptions,
            activePlayerSubscriptions,
            subscriptionRevenue
        );
    }

    public CoachRevenueAdminDto getAdminCoachRevenue(UUID coachId, Instant from, Instant to) {
        coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach not found", "coach_profile"));

        RevenueSummaryDto summary = getCoachRevenueSummary(coachId, from, to);

        int reliabilityStrikeCount = (int) coachReliabilityStrikeRepository
            .countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30));

        int outstandingDisputeCount = 0; // TODO Story 10.x: wire booking_disputes table

        return new CoachRevenueAdminDto(
            summary.grossEarnings(),
            summary.commissionDeducted(),
            summary.stripeFees(),
            summary.netPayout(),
            summary.sessionCount(),
            summary.refundsIssued(),
            summary.currency(),
            reliabilityStrikeCount,
            outstandingDisputeCount
        );
    }

    private String extractFirstName(String fullName) {
        return fullName == null ? "" : fullName.split("\\s+")[0];
    }

    private Map<String, Long> toTierCountMap(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }
}
