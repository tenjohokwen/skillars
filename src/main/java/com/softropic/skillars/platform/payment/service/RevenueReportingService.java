package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachReliabilityStrikeRepository;
import com.softropic.skillars.platform.payment.contract.AdminFinanceOverviewDto;
import com.softropic.skillars.platform.payment.contract.BookingPaymentStatus;
import com.softropic.skillars.platform.payment.contract.CoachRevenueAdminDto;
import com.softropic.skillars.platform.payment.contract.CreditStatementEntryDto;
import com.softropic.skillars.platform.payment.contract.ParentReceiptDto;
import com.softropic.skillars.platform.payment.contract.ReceiptDto;
import com.softropic.skillars.platform.payment.contract.CoachPayoutStatus;
import com.softropic.skillars.platform.payment.contract.RevenueSummaryDto;
import com.softropic.skillars.platform.payment.contract.TransactionDto;
import com.softropic.skillars.platform.payment.repo.BookingPayment;
import com.softropic.skillars.platform.payment.repo.BookingPaymentRepository;
import com.softropic.skillars.platform.payment.repo.CoachPayout;
import com.softropic.skillars.platform.payment.repo.CoachPayoutRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RevenueReportingService {

    private final BookingPaymentRepository bookingPaymentRepository;
    private final CoachPayoutRepository coachPayoutRepository;
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

        // skillars-deferred-106 AC12.1: coach figures reflect payouts actually RELEASED to the coach's
        // Stripe account (dated by released_at), read straight from the payment.coach_payouts ledger —
        // no longer bp.status = 'CAPTURED' and no longer recomputed from stripeCharged + creditDebited.
        BigDecimal grossEarnings = coachPayoutRepository
            .sumReleasedGrossByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        BigDecimal commissionDeducted = coachPayoutRepository
            .sumReleasedCommissionByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        BigDecimal netFromLedger = coachPayoutRepository
            .sumReleasedNetByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        long sessionCount = coachPayoutRepository.countReleasedByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);

        BigDecimal stripeFeeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"));
        BigDecimal stripeFeeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"));
        BigDecimal stripeFees = grossEarnings.multiply(stripeFeeRate)
            .add(stripeFeeFixed.multiply(BigDecimal.valueOf(sessionCount)))
            .setScale(2, RoundingMode.HALF_UP);

        // net_amount on the ledger row is gross - commission; the coach's take-home is that minus
        // the platform's estimated Stripe processing fee.
        BigDecimal netPayout = netFromLedger.subtract(stripeFees);

        List<UUID> bookingIds = coachPayoutRepository
            .findReleasedBookingIdsByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        BigDecimal refundsIssued = bookingIds.isEmpty()
            ? BigDecimal.ZERO
            : parentCreditLedgerRepository.sumRefundsByBookingIds(bookingIds);

        // AC12.2: completed-but-not-yet-released sessions are a separate line, never folded in.
        BigDecimal pendingReleaseAmount = coachPayoutRepository
            .sumPendingReleaseNetByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);
        long pendingReleaseCount = coachPayoutRepository
            .countPendingReleaseByCoachAndPeriod(coachId, effectiveFrom, effectiveTo);

        return new RevenueSummaryDto(grossEarnings, commissionDeducted, stripeFees, netPayout,
            sessionCount, refundsIssued, "EUR", pendingReleaseAmount, pendingReleaseCount);
    }

    public Page<TransactionDto> getCoachTransactions(UUID coachId, Instant from, Instant to, Pageable pageable) {
        Instant effectiveFrom = from != null ? from
            : YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant effectiveTo = to != null ? to : Instant.now();

        // skillars-deferred-106 AC12: one row per coach payout — RELEASED and PENDING_RELEASE (the
        // status field carries the released-vs-pending distinction). Amounts come straight from the
        // ledger row, no recompute.
        Page<CoachPayout> page = coachPayoutRepository
            .findPayoutTransactionsByCoachAndPeriod(coachId, effectiveFrom, effectiveTo, pageable);

        Set<UUID> bookingIds = page.getContent().stream()
            .map(CoachPayout::getBookingId)
            .collect(Collectors.toSet());

        Map<UUID, Booking> bookings = bookingRepository.findAllById(bookingIds).stream()
            .collect(Collectors.toMap(Booking::getId, b -> b));
        Map<UUID, BigDecimal> creditByBooking = bookingPaymentRepository.findAllById(bookingIds).stream()
            .collect(Collectors.toMap(BookingPayment::getBookingId, BookingPayment::getCreditDebited));

        Set<Long> playerIds = bookings.values().stream()
            .map(Booking::getPlayerId)
            .collect(Collectors.toSet());

        Map<Long, String> playerNames = playerProfileRepository.findAllById(playerIds).stream()
            .collect(Collectors.toMap(PlayerProfile::getId, PlayerProfile::getName));

        return page.map(payout -> {
            Booking booking = bookings.get(payout.getBookingId());
            String playerName = booking != null
                ? playerNames.getOrDefault(booking.getPlayerId(), "Unknown")
                : "Unknown";
            Instant sessionDate = booking != null ? booking.getRequestedStartTime() : null;
            return new TransactionDto(
                payout.getBookingId(),
                playerName,
                sessionDate,
                payout.getGrossAmount(),
                payout.getCommissionAmount(),
                payout.getNetAmount(),
                payout.getStatus(),
                creditByBooking.getOrDefault(payout.getBookingId(), BigDecimal.ZERO)
            );
        });
    }

    public ReceiptDto getCoachReceipt(UUID coachId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndCoachId(bookingId, coachId)
            .orElseThrow(() -> new AccessDeniedException("Access denied to booking receipt"));

        // skillars-deferred-106 AC12.2: a coach receipt is money released to the coach, so it is
        // gated on a RELEASED payment.coach_payouts row — same reasoning as the old CAPTURED-only
        // gate (UAT.3 AC1): a receipt for money not yet released is misleading. Anything else 404s.
        Optional<CoachPayout> payoutLookup = coachPayoutRepository.findById(bookingId);
        payoutLookup
            .filter(p -> !CoachPayoutStatus.RELEASED.equals(p.getStatus()))
            .ifPresent(p -> log.warn(
                "Coach receipt requested for bookingId={} coachId={} but coach_payouts status={} "
                    + "(not RELEASED) — returning 404, same as a missing payout record",
                bookingId, coachId, p.getStatus()));
        CoachPayout payout = payoutLookup
            .filter(p -> CoachPayoutStatus.RELEASED.equals(p.getStatus()))
            .orElseThrow(() -> new ResourceNotFoundException("Coach payout not found", "coach_payout"));

        PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null);
        String playerFirstName = player != null ? extractFirstName(player.getName()) : "Player";

        CoachProfile coach = coachProfileRepository.findById(coachId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));

        return new ReceiptDto(
            bookingId,
            booking.getRequestedStartTime(),
            playerFirstName,
            coach.getDisplayName(),
            "Skillars",
            payout.getGrossAmount(),
            payout.getCommissionAmount(),
            payout.getNetAmount()
        );
    }

    public ParentReceiptDto getParentReceipt(Long parentId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndParentId(bookingId, parentId)
            .orElseThrow(() -> new AccessDeniedException("Access denied to booking receipt"));

        // UAT.3 AC1: see getCoachReceipt — a pre-capture row must not render as a paid receipt.
        Optional<BookingPayment> parentPaymentLookup = bookingPaymentRepository.findById(bookingId);
        parentPaymentLookup
            .filter(bp -> !BookingPaymentStatus.CAPTURED.equals(bp.getStatus()))
            .ifPresent(bp -> log.warn(
                "Parent receipt requested for bookingId={} parentId={} but BookingPayment status={} "
                    + "(not CAPTURED) — returning 404, same as a missing payment record",
                bookingId, parentId, bp.getStatus()));
        BookingPayment payment = parentPaymentLookup
            .filter(bp -> BookingPaymentStatus.CAPTURED.equals(bp.getStatus()))
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
        BigDecimal openingBalance = parentCreditLedgerRepository.sumByParentIdBeforeAnchor(parentId, oldest.getCreatedAt(), oldest.getTxId());

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

        // skillars-deferred-106 AC10.5: wiring the real dispute count stays out of scope here.
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
            outstandingDisputeCount,
            summary.pendingReleaseAmount(),
            summary.pendingReleaseCount()
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
