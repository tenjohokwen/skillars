# Story 7.5: Revenue Dashboard & Financial Reporting

Status: done

## Story

As a coach,
I want a clear view of my gross earnings, platform commission, Stripe fees, and net payout for any period,
And as a platform operator, I want financial oversight of total transaction volume, commission collected, and refund exposure.

## Acceptance Criteria

1. **Given** a coach navigates to their revenue dashboard
   **When** `GET /api/payment/coaches/me/revenue?from={date}&to={date}` is called
   **Then** the response contains a `RevenueSummaryDto`: `grossEarnings` (sum of all `booking_payments.stripeCharged + creditDebited` for CAPTURED bookings in the period), `commissionDeducted` (8% of gross, from ConfigService rate), `stripeFees` (estimated from ConfigService fee rate × gross — not pulled from Stripe API), `netPayout` (grossEarnings − commissionDeducted − stripeFees), `sessionCount` (count of CAPTURED bookings), `refundsIssued` (sum of BOOKING_REFUND credit entries linked to this coach's bookings in the period), `currency: "EUR"`
   **And** if no period is specified, defaults to the current calendar month (`from = first day of month, to = last instant of today`)
   **And** all figures computed from `booking_payments` and `parent_credit_ledger` — never from the Stripe dashboard

2. **Given** a coach views their transaction list
   **When** `GET /api/payment/coaches/me/transactions?from={date}&to={date}&page={n}` is called
   **Then** a paginated list of `TransactionDto` is returned, one entry per booking payment: `bookingId`, `playerName`, `sessionDate`, `grossAmount`, `commissionAmount`, `netAmount`, `status`, `creditUsed` (how much of the payment came from parent credit)
   **And** cancelled/refunded bookings appear in the list with their refund credit amounts shown

3. **Given** a coach requests a receipt for a specific booking
   **When** `GET /api/payment/coaches/bookings/{bookingId}/receipt` is called
   **Then** a `ReceiptDto` is returned containing: booking details, gross amount, commission deducted, net received, session date, player first name (no surname — minor data minimisation), coach name, platform name
   **And** the frontend renders this as a printable PDF-ready layout (`ReceiptView.vue`)
   **And** the coach must own the booking — `403` if `booking.coachId != authenticatedCoach.id`

4. **Given** a parent requests a receipt for a booking they paid for
   **When** `GET /api/payment/parents/bookings/{bookingId}/receipt` is called
   **Then** a `ParentReceiptDto` is returned: booking details, amount charged (credit + Stripe), session date, coach name, player first name
   **And** the parent must own the booking — verified via `booking.parentId == authenticatedParentId` — `403` on mismatch

5. **Given** a parent views their credit statement
   **When** `GET /api/payment/credits/statement?from={date}&to={date}` is called
   **Then** a paginated list of `CreditStatementEntryDto` is returned from `parent_credit_ledger`, ordered by `createdAt DESC`: `txId`, `type`, `amount`, `description`, `referenceId`, `createdAt`
   **And** the running balance after each entry is computed in the service layer and included in the response
   **And** only entries belonging to the authenticated parent are returned — no cross-parent access

6. **Given** a platform admin views the financial oversight dashboard
   **When** `GET /api/admin/payment/overview?from={date}&to={date}` is called (`@PreAuthorize` admin role)
   **Then** the response contains: `totalGrossVolume`, `totalCommissionCollected`, `totalRefundCredit`, `totalCashOuts`, `totalStripeFees` (estimated), `activeCoachSubscriptions` (count by tier), `activePlayerSubscriptions` (count by tier), `subscriptionRevenue`
   **And** all figures computed from `booking_payments` and `parent_credit_ledger` — not from Stripe API calls at request time

7. **Given** a platform admin views coach-level financial detail
   **When** `GET /api/admin/payment/coaches/{coachId}/revenue` is called
   **Then** the same `RevenueSummaryDto` structure as the coach self-service endpoint is returned, plus `reliabilityStrikeCount` (rolling 30 days) and `outstandingDisputeCount`
   **And** `@PreAuthorize` admin role; `404` if coach not found
   **And** `outstandingDisputeCount` returns 0 (dispute table does not exist until Story 10.x — hardcode 0 with a `// TODO Story 10.x: wire booking_disputes table` comment)

## Tasks / Subtasks

- [x] **Task 1 — Contract DTOs** (AC: 1–7)
  - [x] Create `RevenueSummaryDto.java` record in `platform.payment.contract`:
    ```java
    public record RevenueSummaryDto(
        BigDecimal grossEarnings,
        BigDecimal commissionDeducted,
        BigDecimal stripeFees,
        BigDecimal netPayout,
        long sessionCount,
        BigDecimal refundsIssued,
        String currency
    ) {}
    ```
  - [x] Create `TransactionDto.java` record in `platform.payment.contract`:
    ```java
    public record TransactionDto(
        UUID bookingId,
        String playerName,
        Instant sessionDate,
        BigDecimal grossAmount,
        BigDecimal commissionAmount,
        BigDecimal netAmount,
        String status,
        BigDecimal creditUsed
    ) {}
    ```
  - [x] Create `ReceiptDto.java` record in `platform.payment.contract`:
    ```java
    public record ReceiptDto(
        UUID bookingId,
        Instant sessionDate,
        String playerFirstName,
        String coachName,
        String platformName,
        BigDecimal grossAmount,
        BigDecimal commissionDeducted,
        BigDecimal netReceived
    ) {}
    ```
  - [x] Create `ParentReceiptDto.java` record in `platform.payment.contract`:
    ```java
    public record ParentReceiptDto(
        UUID bookingId,
        Instant sessionDate,
        String playerFirstName,
        String coachName,
        BigDecimal stripeCharged,
        BigDecimal creditUsed,
        BigDecimal totalCharged
    ) {}
    ```
  - [x] Create `CreditStatementEntryDto.java` record in `platform.payment.contract`:
    ```java
    public record CreditStatementEntryDto(
        UUID txId,
        String type,
        BigDecimal amount,
        String description,
        UUID referenceId,
        Instant createdAt,
        BigDecimal runningBalance
    ) {}
    ```
  - [x] Create `AdminFinanceOverviewDto.java` record in `platform.payment.contract`:
    ```java
    public record AdminFinanceOverviewDto(
        BigDecimal totalGrossVolume,
        BigDecimal totalCommissionCollected,
        BigDecimal totalRefundCredit,
        BigDecimal totalCashOuts,
        BigDecimal totalStripeFees,
        Map<String, Long> activeCoachSubscriptions,
        Map<String, Long> activePlayerSubscriptions,
        BigDecimal subscriptionRevenue
    ) {}
    ```
    Note: `subscriptionRevenue` = always `BigDecimal.ZERO` in this story with comment `// TODO: subscriptionRevenue requires EUR price-amount config keys not yet seeded — V64 only seeds Stripe priceId strings`. V64 config seeds `subscription.coach.*.priceId` and `subscription.player.*.priceId` keys only; no EUR amount keys exist in ConfigService to estimate from.
  - [x] Create `CoachRevenueAdminDto.java` record in `platform.payment.contract` — **flat record; Java records cannot extend other records, so all `RevenueSummaryDto` fields are inlined**:
    ```java
    public record CoachRevenueAdminDto(
        BigDecimal grossEarnings,
        BigDecimal commissionDeducted,
        BigDecimal stripeFees,
        BigDecimal netPayout,
        long sessionCount,
        BigDecimal refundsIssued,
        String currency,
        int reliabilityStrikeCount,
        int outstandingDisputeCount  // always 0 until Story 10.x
    ) {}
    ```

- [x] **Task 2 — Repository extensions** (AC: 1–7)
  - [x] Add to `BookingPaymentRepository` — **use `nativeQuery = true` for all queries joining `booking.bookings`**. `BookingPayment` and `Booking` have no `@ManyToOne` association and are in different schemas; the codebase pattern for cross-schema queries is native SQL (see `RadarAssessmentRepository`, `VideoRepository`). JPQL theta-style non-association joins are unreliable with cross-schema Postgres setups in Hibernate 6:
    ```java
    // Sum of stripe_charged + credit_debited for CAPTURED bookings for a coach in period
    @Query(value = """
        SELECT COALESCE(SUM(bp.stripe_charged + bp.credit_debited), 0)
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId
          AND bp.status = 'CAPTURED'
          AND bp.captured_at BETWEEN :from AND :to
        """, nativeQuery = true)
    Optional<BigDecimal> sumGrossByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to);

    @Query(value = """
        SELECT COUNT(*)
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId
          AND bp.status = 'CAPTURED'
          AND bp.captured_at BETWEEN :from AND :to
        """, nativeQuery = true)
    long countCapturedByCoachAndPeriod(@Param("coachId") UUID coachId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    // For transaction list — paginated; no status filter (returns all statuses including REFUNDED)
    @Query(value = """
        SELECT bp.*
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId
          AND bp.captured_at BETWEEN :from AND :to
        ORDER BY bp.captured_at DESC
        """,
        countQuery = """
        SELECT COUNT(*)
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId
          AND bp.captured_at BETWEEN :from AND :to
        """,
        nativeQuery = true)
    Page<BookingPayment> findByCoachAndPeriod(@Param("coachId") UUID coachId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to,
                                              Pageable pageable);

    // Platform-wide sum for admin — no join needed
    @Query("SELECT SUM(bp.stripeCharged + bp.creditDebited) FROM BookingPayment bp WHERE bp.status = 'CAPTURED' AND bp.capturedAt BETWEEN :from AND :to")
    Optional<BigDecimal> sumTotalGross(@Param("from") Instant from, @Param("to") Instant to);

    // Total captured session count for admin Stripe fee estimate (includes fixed per-tx fee)
    @Query("SELECT COUNT(bp) FROM BookingPayment bp WHERE bp.status = 'CAPTURED' AND bp.capturedAt BETWEEN :from AND :to")
    long countCapturedForPeriod(@Param("from") Instant from, @Param("to") Instant to);

    // Booking IDs for a coach in a period (for refund ledger join)
    @Query(value = """
        SELECT bp.booking_id
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId AND bp.captured_at BETWEEN :from AND :to
        """, nativeQuery = true)
    List<UUID> findBookingIdsByCoachAndPeriod(@Param("coachId") UUID coachId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);
    ```
  - [x] Add to `ParentCreditLedgerRepository`:
    ```java
    // Sum of refund credits linked to a set of booking IDs (for coach refunds view)
    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'BOOKING_REFUND' AND l.referenceId IN :bookingIds")
    BigDecimal sumRefundsByBookingIds(@Param("bookingIds") List<UUID> bookingIds);

    // Parent credit statement — paginated, ordered by createdAt DESC
    @Query("SELECT l FROM ParentCreditLedger l WHERE l.parentId = :parentId AND l.createdAt BETWEEN :from AND :to ORDER BY l.createdAt DESC")
    Page<ParentCreditLedger> findByParentAndPeriod(@Param("parentId") Long parentId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to,
                                                   Pageable pageable);

    // Sum of all entries strictly BEFORE a given instant — used to compute running balance anchor for any page
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.parentId = :parentId AND l.createdAt < :before")
    BigDecimal sumByParentIdAndCreatedAtBefore(@Param("parentId") Long parentId,
                                               @Param("before") Instant before);

    // Admin: total refund credit (BOOKING_REFUND entries are negative)
    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'BOOKING_REFUND' AND l.createdAt BETWEEN :from AND :to")
    BigDecimal sumTotalRefundCredit(@Param("from") Instant from, @Param("to") Instant to);

    // Admin: total cash-outs (CASH_OUT_DEBIT entries)
    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'CASH_OUT_DEBIT' AND l.createdAt BETWEEN :from AND :to")
    BigDecimal sumTotalCashOuts(@Param("from") Instant from, @Param("to") Instant to);
    ```
  - [x] Add to `BookingRepository` (in `platform.booking.repo`):
    ```java
    Optional<Booking> findByIdAndCoachId(UUID bookingId, UUID coachId);
    Optional<Booking> findByIdAndParentId(UUID bookingId, Long parentId);
    ```
    These are needed for receipt ownership checks. **Do NOT create a new `BookingRepository`** — it already exists in `platform.booking.repo`.

- [x] **Task 3 — `RevenueReportingService`** (AC: 1–7)
  - [x] Create `RevenueReportingService.java` in `platform.payment.service` — `@Service @RequiredArgsConstructor @Slf4j @Transactional(readOnly = true)`
  - [x] Inject: `BookingPaymentRepository`, `ParentCreditLedgerRepository`, `BookingRepository` (from `platform.booking.repo`), `PaymentCoachSubscriptionRepository`, `PaymentPlayerSubscriptionRepository`, `CoachReliabilityStrikeRepository` (from `platform.marketplace.repo`), `PlayerProfileRepository` (from `platform.security.repo`), `CoachProfileRepository` (from `platform.marketplace.repo`), `ConfigService`
  - [x] `getCoachRevenueSummary(UUID coachId, Instant from, Instant to): RevenueSummaryDto`:
    1. If `from == null`: first instant of current calendar month UTC (`YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()`)
    2. If `to == null`: `Instant.now()`
    3. `grossEarnings = bookingPaymentRepository.sumGrossByCoachAndPeriod(coachId, from, to).orElse(BigDecimal.ZERO)`
    4. `commissionRate = new BigDecimal(configService.getString("platform.commission.rate"))`
    5. `commissionDeducted = grossEarnings.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP)`
    6. `stripeFeeRate = new BigDecimal(configService.getString("payment.stripe.feeRate"))` (percentage, e.g. 0.029)
    7. `stripeFeeFixed = new BigDecimal(configService.getString("payment.stripe.feeFixed"))` (per transaction, e.g. 0.30)
    8. `sessionCount = bookingPaymentRepository.countCapturedByCoachAndPeriod(coachId, from, to)`
    9. `stripeFees = grossEarnings.multiply(stripeFeeRate).add(stripeFeeFixed.multiply(BigDecimal.valueOf(sessionCount))).setScale(2, RoundingMode.HALF_UP)` — estimate only
    10. `netPayout = grossEarnings.subtract(commissionDeducted).subtract(stripeFees)`
    11. `bookingIds = bookingPaymentRepository.findBookingIdsByCoachAndPeriod(coachId, from, to)`
    12. `refundsIssued = bookingIds.isEmpty() ? ZERO : parentCreditLedgerRepository.sumRefundsByBookingIds(bookingIds)` — guard against empty IN list
    13. Return `new RevenueSummaryDto(grossEarnings, commissionDeducted, stripeFees, netPayout, sessionCount, refundsIssued, "EUR")`
  - [x] `getCoachTransactions(UUID coachId, Instant from, Instant to, Pageable pageable): Page<TransactionDto>`:
    1. Same null default for from/to
    2. `Page<BookingPayment> page = bookingPaymentRepository.findByCoachAndPeriod(coachId, from, to, pageable)`
    3. Batch-load bookings for page: `Set<UUID> bookingIds = page.map(BookingPayment::getBookingId).toSet()`; `Map<UUID, Booking> bookings = bookingRepository.findAllById(bookingIds).stream().collect(toMap(Booking::getId, b->b))`
    4. Batch-load player names: `Set<Long> playerIds = bookings.values().stream().map(Booking::getPlayerId).collect(toSet())`; `Map<Long, String> playerNames = playerProfileRepository.findAllById(playerIds).stream().collect(toMap(PlayerProfile::getId, PlayerProfile::getName))`
    5. Map each `BookingPayment` to `TransactionDto`:
       - `playerName = playerNames.getOrDefault(booking.getPlayerId(), "Unknown")`
       - `sessionDate = booking.getRequestedStartTime()`
       - `grossAmount = bp.getStripeCharged().add(bp.getCreditDebited())`
       - `commissionAmount = grossAmount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP)`
       - `netAmount = grossAmount.subtract(commissionAmount)` — note: Stripe fee estimate is not deducted at transaction level (only in the summary `netPayout`); the frontend should display a footnote explaining this
       - `status = bp.getStatus()`
       - `creditUsed = bp.getCreditDebited()`
    6. Return `page.map(...)` preserving page metadata
  - [x] `getCoachReceipt(UUID coachId, UUID bookingId): ReceiptDto`:
    1. `Booking booking = bookingRepository.findByIdAndCoachId(bookingId, coachId).orElseThrow(() -> new ResourceForbiddenException(...))` — **403** on not-found (don't leak booking existence to unauthorised callers)
    2. `BookingPayment payment = bookingPaymentRepository.findById(bookingId).orElseThrow(...)`
    3. `PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null)`
    4. `playerFirstName = player != null ? extractFirstName(player.getName()) : "Player"`
    5. `CoachProfile coach = coachProfileRepository.findById(coachId).orElseThrow(...)`
    6. `grossAmount = payment.getStripeCharged().add(payment.getCreditDebited())`
    7. `commissionDeducted = grossAmount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP)`
    8. `netReceived = grossAmount.subtract(commissionDeducted)`
    9. Return `ReceiptDto`
  - [x] `extractFirstName(String fullName): String` private helper — `return fullName == null ? "" : fullName.split("\\s+")[0]`
  - [x] `getParentReceipt(Long parentId, UUID bookingId): ParentReceiptDto`:
    1. `Booking booking = bookingRepository.findByIdAndParentId(bookingId, parentId).orElseThrow(() -> new ResourceForbiddenException(...))`
    2. `BookingPayment payment = bookingPaymentRepository.findById(bookingId).orElseThrow(...)`
    3. `PlayerProfile player = playerProfileRepository.findById(booking.getPlayerId()).orElse(null)`
    4. `CoachProfile coach = coachProfileRepository.findById(booking.getCoachId()).orElseThrow(...)`
    5. `coachName = coach.getDisplayName()` — confirmed field name on `CoachProfile.java`
    6. Return `ParentReceiptDto`
  - [x] `getCreditStatement(Long parentId, Instant from, Instant to, Pageable pageable): Page<CreditStatementEntryDto>`:
    1. `Page<ParentCreditLedger> page = parentCreditLedgerRepository.findByParentAndPeriod(parentId, from, to, pageable)`
    2. Compute running balance — correct for any page offset (not just page 0):
       - The page is ordered DESC (most recent first). The **oldest** entry on the page is `page.getContent().get(page.getContent().size() - 1)`.
       - `openingBalance = parentCreditLedgerRepository.sumByParentIdAndCreatedAtBefore(parentId, oldestEntry.getCreatedAt())` — this is the balance immediately before the oldest entry on the page.
       - Iterate entries in **reverse** (ASC chronological order within the page), accumulating: `runningBalance = openingBalance + entry.amount; openingBalance = runningBalance`.
       - Store each `runningBalance` keyed by `txId`.
       - When mapping to DTO, look up the precomputed `runningBalance` for each entry (still in DESC order).
    3. Guard: if page is empty, return the mapped empty page (no balance query needed).
    4. Return mapped entries
  - [x] `getAdminOverview(Instant from, Instant to): AdminFinanceOverviewDto`:
    1. Aggregates all-coach booking data in the period
    2. `totalGrossVolume = bookingPaymentRepository.sumTotalGross(from, to).orElse(ZERO)`
    3. `totalCommissionCollected = totalGrossVolume.multiply(commissionRate).setScale(2, ...)`
    4. `long totalSessionCount = bookingPaymentRepository.countCapturedForPeriod(from, to)`
       `totalStripeFees = totalGrossVolume.multiply(stripeFeeRate).add(stripeFeeFixed.multiply(BigDecimal.valueOf(totalSessionCount))).setScale(2, RoundingMode.HALF_UP)` — consistent with coach-level calculation
    5. `totalRefundCredit = parentCreditLedgerRepository.sumTotalRefundCredit(from, to)`
    6. `totalCashOuts = parentCreditLedgerRepository.sumTotalCashOuts(from, to)`
    7. Coach subscription counts: `paymentCoachSubscriptionRepository.countByStatusIn(List.of("ACTIVE","TRIALLING"))` — add this query to the repository, grouped by tier. Return as `Map<String, Long>` (e.g. `{"SCOUT": 10, "INSTRUCTOR": 3, "ACADEMY": 1}`)
    8. Player subscription counts: similar from `paymentPlayerSubscriptionRepository`
    9. `subscriptionRevenue = BigDecimal.ZERO` with comment `// TODO: subscriptionRevenue requires EUR price-amount config keys not yet seeded — V64 only seeds Stripe priceId strings`
    10. Return `AdminFinanceOverviewDto`
  - [x] `getAdminCoachRevenue(UUID coachId, Instant from, Instant to): CoachRevenueAdminDto`:
    1. Verify coach exists: `coachProfileRepository.findById(coachId).orElseThrow(() -> new ResourceNotFoundException(...))`
    2. `RevenueSummaryDto summary = getCoachRevenueSummary(coachId, from, to)`
    3. `reliabilityStrikeCount = (int) coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(coachId, OffsetDateTime.now().minusDays(30))`
    4. `outstandingDisputeCount = 0; // TODO Story 10.x: wire booking_disputes table`
    5. Return `new CoachRevenueAdminDto(summary.grossEarnings(), summary.commissionDeducted(), summary.stripeFees(), summary.netPayout(), summary.sessionCount(), summary.refundsIssued(), summary.currency(), reliabilityStrikeCount, outstandingDisputeCount)`

- [x] **Task 4 — `RevenueResource`** (AC: 1–5)
  - [x] Create `RevenueResource.java` in `platform.payment.api`:
    `@RestController @RequestMapping("/api/payment") @RequiredArgsConstructor @Observed(name = "payment.revenue")`
  - [x] `GET /coaches/me/revenue` — `@PreAuthorize(HAS_COACH_ROLE)` — query params `from` (optional LocalDate), `to` (optional LocalDate) → `RevenueSummaryDto` 200
    - Resolve `coachId` via `resolveCoachId()` helper (same pattern as `StripeOnboardingResource`)
    - Convert LocalDate params to Instant: `from → from.atStartOfDay(ZoneOffset.UTC).toInstant()`; `to → to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()` (exclusive upper bound so the full last day is included in BETWEEN queries). Pass `null` for missing params — service applies default period.
  - [x] `GET /coaches/me/transactions` — `@PreAuthorize(HAS_COACH_ROLE)` — query params `from`, `to` (optional LocalDate), `page` (default 0), `size` (default 20) → `Page<TransactionDto>` 200
  - [x] `GET /coaches/bookings/{bookingId}/receipt` — `@PreAuthorize(HAS_COACH_ROLE)` — path `bookingId` UUID → `ReceiptDto` 200
  - [x] `GET /parents/bookings/{bookingId}/receipt` — `@PreAuthorize(HAS_PARENT_ROLE)` — path `bookingId` UUID → `ParentReceiptDto` 200
    - `parentId = securityUtil.getCurrentCoachUserId()` — note: `getCurrentCoachUserId()` returns the businessId which for parents is their Long userId (same method used in `CreditWalletResource`)
  - [x] `GET /credits/statement` — `@PreAuthorize(HAS_PARENT_ROLE)` — query params `from`, `to` (optional LocalDate), `page` (default 0), `size` (default 20) → `Page<CreditStatementEntryDto>` 200
    - Add to `CreditWalletResource` OR create a new `GET /credits/statement` endpoint in `RevenueResource` under `/api/payment/credits/statement`. **Prefer adding to `RevenueResource`** to keep financial reporting together. Do NOT add to `CreditWalletResource` (it already handles balance/cashout, different concern).
  - [x] `resolveCoachId()` private helper — same pattern as in `StripeOnboardingResource`:
    ```java
    private UUID resolveCoachId() {
        Long coachUserId = securityUtil.getCurrentCoachUserId();
        CoachProfile coach = coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
        return coach.getId();
    }
    ```
  - [x] `resolveParentId()` private helper:
    ```java
    private Long resolveParentId() {
        return securityUtil.getCurrentCoachUserId(); // businessId is parentId for parent role
    }
    ```

- [x] **Task 5 — `AdminFinanceResource`** (AC: 6–7)
  - [x] Create `AdminFinanceResource.java` in `platform.payment.api`:
    `@RestController @RequestMapping("/api/admin/payment") @RequiredArgsConstructor @Observed(name = "payment.admin.finance") @PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`  — `@PreAuthorize` at class level applies to all methods
  - [x] `GET /overview` — query params `from`, `to` (optional LocalDate) → `AdminFinanceOverviewDto` 200
  - [x] `GET /coaches/{coachId}/revenue` — path `coachId` UUID → `CoachRevenueAdminDto` 200; 404 if coach not found

- [x] **Task 6 — Additional repository queries** (AC: 6)
  - [x] Add to `PaymentCoachSubscriptionRepository`:
    ```java
    @Query("SELECT p.tier, COUNT(p) FROM PaymentCoachSubscription p WHERE p.status IN ('ACTIVE', 'TRIALLING') GROUP BY p.tier")
    List<Object[]> countActiveByTier();
    ```
  - [x] Add to `PaymentPlayerSubscriptionRepository`:
    ```java
    @Query("SELECT p.tier, COUNT(p) FROM PaymentPlayerSubscription p WHERE p.status IN ('ACTIVE', 'TRIALLING') GROUP BY p.tier")
    List<Object[]> countActiveByTier();
    ```
  - [x] Helper in `RevenueReportingService` to convert `List<Object[]>` to `Map<String, Long>`

- [x] **Task 7 — Frontend** (AC: 1–5)
  - [x] Add to `src/frontend/src/api/payment.api.js`:
    - `fetchCoachRevenueSummary(from, to)` → `GET /api/payment/coaches/me/revenue?from={from}&to={to}`
    - `fetchCoachTransactions(from, to, page, size)` → `GET /api/payment/coaches/me/transactions`
    - `fetchCoachReceipt(bookingId)` → `GET /api/payment/coaches/bookings/{bookingId}/receipt`
    - `fetchParentReceipt(bookingId)` → `GET /api/payment/parents/bookings/{bookingId}/receipt`
    - `fetchCreditStatement(from, to, page, size)` → `GET /api/payment/credits/statement`
  - [x] Extend `src/frontend/src/stores/payment.store.js`:
    - State: `revenueSummary: null`, `transactions: []`, `creditStatement: []`
    - Actions: `fetchRevenueSummary(from, to)`, `fetchTransactions(from, to, page)`, `fetchCreditStatement(from, to, page)`
  - [x] Create `src/frontend/src/pages/coach/RevenueDashboardPage.vue`:
    - Glassmorphism design following `CoachReliabilityPage.vue` / `CoachSubscriptionPage.vue` patterns
    - Period date range picker (defaults to current month)
    - Summary cards: Gross Earnings, Commission, Stripe Fees, Net Payout, Refunds Issued
    - Transaction table (paginated): playerName, sessionDate (formatted), grossAmount, commissionAmount, netAmount, creditUsed
    - "View Receipt" button per row that navigates to `ReceiptView.vue` in a new tab or dialog
  - [x] Create `src/frontend/src/pages/coach/ReceiptView.vue`:
    - Printable layout: `window.print()` triggered by a "Print / Download PDF" button
    - Use `@media print` CSS (inline in `<style>`) to hide nav and show only receipt content
    - Display all `ReceiptDto` fields: platformName (`"Skillars"` hardcoded from i18n), coach name, player first name, session date, amounts with line items
  - [x] Create `src/frontend/src/pages/parent/CreditStatementPage.vue`:
    - Credit ledger table with running balance column
    - Paginated; period date range picker
    - "Receipt" link for `BOOKING_DEDUCTION` type entries
  - [x] Add i18n keys `revenue.*` and `creditStatement.*` to `src/frontend/src/i18n/en/index.js` and `de/index.js`
  - [x] Add routes to `src/frontend/src/router/routes.js`:
    - `/coach/revenue` → `RevenueDashboardPage.vue`
    - `/coach/bookings/:bookingId/receipt` → `ReceiptView.vue`
    - `/parent/credit-statement` → `CreditStatementPage.vue`
  - [x] Add "Revenue" tab/link to coach Command Center navigation (check `CoachCommandCenterPage.vue` sidebar)

- [x] **Task 8 — Tests** (AC: 1–7)
  - [x] `RevenueReportingServiceTest.java` (unit — Mockito):
    - `getCoachRevenueSummary`: CAPTURED bookings summed correctly; commission = 8% of gross; Stripe fees = rate × gross + fixed × count; refundsIssued pulls from ledger BOOKING_REFUND entries only; default period = current calendar month
    - `getCoachRevenueSummary` with no bookings: returns all-zero DTO (no NPE)
    - `getCreditStatement` running balance: balance computed correctly for page 0 AND for non-zero page offsets (use mock that returns deterministic `sumByParentIdAndCreatedAtBefore` values); entries on page correctly processed in reverse-then-re-mapped order
    - `getAdminOverview`: aggregates cross-coach data; subscription counts grouped by tier correctly
    - `getCoachReceipt`: 403 if coachId doesn't own booking
    - `getParentReceipt`: 403 if parentId doesn't own booking
  - [x] `ReceiptOwnershipIT.java` (`@SpringBootTest @Testcontainers`):
    - Coach A requests receipt for Coach B's booking: `403` (not `404`)
    - Parent A requests receipt for Parent B's booking: `403` (not `404`)
    - Admin calls `/api/admin/payment/coaches/{id}/revenue` for non-existent coach: `404`
    - Admin calls overview with valid period: `200` with expected structure
  - [x] `AdminFinanceResourceIT.java` (`@SpringBootTest @Testcontainers`):
    - Admin endpoint requires `ROLE_ADMIN` — `403` for coach/parent roles
    - `GET /api/admin/payment/coaches/{coachId}/revenue` returns `outstandingDisputeCount = 0`
    - Coach subscription counts by tier match inserted test data

## Dev Notes

### CRITICAL: `booking_payments` Has No `coach_id` — Always JOIN `booking.bookings`

`payment.booking_payments` schema (V62): `booking_id` (PK, FK to booking.bookings), `stripe_charged`, `credit_debited`, `status`, `captured_at`. **No `coach_id` column.**

All coach-scoped revenue queries MUST JOIN `Booking` on `Booking.id = BookingPayment.bookingId` and filter by `Booking.coachId`. JPA entity class for this is `Booking` in `platform.booking.repo`, table `booking.bookings`. Cross-package JPQL entity join works because both entities are registered in the same persistence unit.

### CRITICAL: `PlayerProfile.name` is Full Name — Extract First Name Manually

`PlayerProfile.name` (column `main.player_profiles.name`) is a single `VARCHAR(100)` full name field — NOT split into firstName/lastName. For receipt endpoints (data minimisation), extract first name via `name.split("\\s+")[0]`. Never return the full name in receipts.

For `TransactionDto.playerName` in the transaction list, use the full `name` field — the coach knows who their players are and this is not a minor-facing view.

### CRITICAL: `securityUtil.getCurrentCoachUserId()` Returns `businessId` (Long) for Both Coach and Parent Roles

The `getCurrentCoachUserId()` method name is misleading — it returns `principal.getBusinessId()` parsed as Long, which for coaches is the coachUserId, and for parents is the parentId (Long). See usage in `CreditWalletResource` (line 33: `Long parentId = securityUtil.getCurrentCoachUserId()`). Follow this pattern in `RevenueResource.resolveParentId()`.

For coaches, additionally resolve `coachId` (UUID) from `CoachProfileRepository.findByUserId(coachUserId)` — same pattern as `StripeOnboardingResource.resolveCoachId()`.

### CRITICAL: Admin Role is `SecurityConstants.HAS_ADMIN_ROLE`

`"hasRole('ROLE_ADMIN') or hasRole('ROLE_LTD_ADMIN')"` — use `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` on all admin endpoints. Never use `HAS_COACH_ROLE` or `HAS_PARENT_ROLE` for admin endpoints.

### CRITICAL: Admin Subscription Counts Are Current-State, Not Period-Scoped

The `countActiveByTier()` queries on `PaymentCoachSubscriptionRepository` and `PaymentPlayerSubscriptionRepository` return counts for subscriptions currently in `ACTIVE` or `TRIALLING` status — they have no date filter. This means `activeCoachSubscriptions` and `activePlayerSubscriptions` in `AdminFinanceOverviewDto` always reflect today's state, even when the admin is querying a historical period. There is no historical subscription snapshot table, so true period-accurate counts are not feasible in this story. The admin frontend should label these as "Current active subscriptions" rather than implying they reflect the reporting period.

### CRITICAL: `outstandingDisputeCount` = 0 (No Dispute Table Yet)

No `booking_disputes` table or Dispute entity exists in the codebase. Story 10.x introduces dispute resolution. Return hardcoded `0` with comment `// TODO Story 10.x: wire booking_disputes table`.

### CRITICAL: `CoachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter` Uses `OffsetDateTime`

The method signature is `countByCoachIdAndCreatedAtAfter(UUID coachId, OffsetDateTime since)` — pass `OffsetDateTime.now().minusDays(30)`. `CoachReliabilityStrikeRepository` is in `platform.marketplace.repo` — inject it directly (no new repository needed).

### CRITICAL: No New Flyway Migration Needed

Story 7.5 adds NO new tables. All reporting queries run against existing tables. The next migration number is **V65** — reserve it for a future story. Do NOT create `V65__*.sql` in this story.

### ConfigService Keys for Fee Calculation

- `"platform.commission.rate"` → `"0.08"` (8%) — seeded in V61, used in `StripePaymentGateway`
- `"payment.stripe.feeRate"` → percentage rate (e.g. `"0.029"` for 2.9%) — used in `CashOutService`
- `"payment.stripe.feeFixed"` → fixed per-transaction EUR (e.g. `"0.30"`) — used in `CashOutService`

Use `new BigDecimal(configService.getString(...))` — the ConfigService getString method is safe for rate lookups; do NOT cache the value in a field (same rule as commission rate in prior stories).

### Cross-Module Repository Injection Pattern

`RevenueReportingService` injects from multiple modules:
- `BookingRepository` from `platform.booking.repo` — already used in `CancellationRefundService`
- `PlayerProfileRepository` from `platform.security.repo` — already used in other services
- `CoachProfileRepository` from `platform.marketplace.repo` — already used in `StripeOnboardingResource`
- `CoachReliabilityStrikeRepository` from `platform.marketplace.repo` — already used in `ReliabilityStrikeService`

This cross-module pattern is established in `BookingPaymentPersistenceService`, `CancellationRefundService`. Follow it — no new abstractions needed.

### Cross-Schema JOIN Approach — Use Native SQL

`BookingPayment` and `Booking` have no `@ManyToOne`/`@JoinColumn` association. All existing cross-schema queries in the codebase (e.g., `RadarAssessmentRepository`, `VideoRepository`) use `nativeQuery = true`. Use native SQL for all `BookingPaymentRepository` queries that join `booking.bookings`:

```sql
-- Example: sum gross for coach in period
SELECT COALESCE(SUM(bp.stripe_charged + bp.credit_debited), 0)
FROM payment.booking_payments bp
JOIN booking.bookings b ON b.id = bp.booking_id
WHERE b.coach_id = :coachId AND bp.status = 'CAPTURED'
  AND bp.captured_at BETWEEN :from AND :to
```

JPQL theta-style non-association joins (`FROM BookingPayment bp JOIN Booking b ON b.id = bp.bookingId`) are unreliable in Hibernate 6 + cross-schema Postgres setups and are NOT used elsewhere in the codebase. Native SQL is the established and safe pattern here.

### Period Defaulting Logic

- `from == null` → `YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()` (first instant of current month)
- `to == null` → `Instant.now()` (current moment)
- Frontend sends `LocalDate` ISO strings (e.g. `"2026-06-01"`); controller converts:
  - `from` → `from.atStartOfDay(ZoneOffset.UTC).toInstant()` (inclusive start of the from date)
  - `to` → `to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()` (exclusive upper bound — ensures the full `to` date is included in `BETWEEN from AND to` queries; without this, bookings captured on the `to` date after midnight UTC would be excluded)

### Booking `sessionDate` = `requestedStartTime`

`Booking` entity has `requestedStartTime` (Instant) — this IS the session date. Use it as `TransactionDto.sessionDate` and receipt `sessionDate`. The frontend should format it via `Intl.DateTimeFormat` using the coach's `canonicalTimezone`.

### CoachProfile Name Field

`CoachProfile.java` in `platform.marketplace.repo` has `displayName` (`@Column(name = "display_name")`). Use `coach.getDisplayName()` for coach name in receipts. Do NOT use `fullName` (that field does not exist).

### Running Balance in Credit Statement

The running balance is accurate for any page (not just page 0) using the anchor query approach:

1. After loading the page from `findByParentAndPeriod`, get the **oldest** entry on the page: `ParentCreditLedger oldest = entries.get(entries.size() - 1)`.
2. `BigDecimal openingBalance = parentCreditLedgerRepository.sumByParentIdAndCreatedAtBefore(parentId, oldest.getCreatedAt())` — this is the balance immediately before the oldest entry on this page, accurate for any page offset.
3. Iterate entries in **reverse** (ascending chronological order within the page):
   ```java
   Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
   BigDecimal balance = openingBalance;
   List<ParentCreditLedger> asc = new ArrayList<>(entries);
   Collections.reverse(asc);
   for (ParentCreditLedger e : asc) {
       balance = balance.add(e.getAmount());
       balances.put(e.getTxId(), balance);
   }
   ```
4. Map each entry to DTO in the original DESC order using `balances.get(e.getTxId())`.

If two entries share an identical `createdAt` (rare but possible), the `sumByParentIdAndCreatedAtBefore` query uses `<` (strictly before), which means both entries' contributions are NOT in the opening balance — both are computed in the forward pass. This is correct and consistent.

### Frontend: Print-Ready ReceiptView

`ReceiptView.vue` uses CSS `@media print { nav, header, .no-print { display: none } }` to hide chrome. Trigger via `window.print()` on "Print Receipt" button click. No external PDF library needed — browser print-to-PDF is sufficient.

### Project Structure for New Files

```
platform.payment.service:
  RevenueReportingService.java             — NEW

platform.payment.api:
  RevenueResource.java                     — NEW
  AdminFinanceResource.java                — NEW

platform.payment.contract:
  RevenueSummaryDto.java                   — NEW (record)
  TransactionDto.java                      — NEW (record)
  ReceiptDto.java                          — NEW (record)
  ParentReceiptDto.java                    — NEW (record)
  CreditStatementEntryDto.java             — NEW (record)
  AdminFinanceOverviewDto.java             — NEW (record)
  CoachRevenueAdminDto.java                — NEW (record)

platform.payment.repo:
  BookingPaymentRepository.java            — MODIFIED (add aggregation queries)
  ParentCreditLedgerRepository.java        — MODIFIED (add refund/cashout sum, statement query)
  PaymentCoachSubscriptionRepository.java  — MODIFIED (add countActiveByTier)
  PaymentPlayerSubscriptionRepository.java — MODIFIED (add countActiveByTier)

platform.booking.repo:
  BookingRepository.java                   — MODIFIED (add findByIdAndCoachId, findByIdAndParentId)

src/frontend/src/pages/coach:
  RevenueDashboardPage.vue                 — NEW
  ReceiptView.vue                          — NEW

src/frontend/src/pages/parent:
  CreditStatementPage.vue                  — NEW

src/frontend/src/api:
  payment.api.js                           — MODIFIED (add 5 revenue functions)

src/frontend/src/stores:
  payment.store.js                         — MODIFIED (add revenue state/actions)

src/frontend/src/i18n/en/index.js         — MODIFIED (revenue.*, creditStatement.*)
src/frontend/src/i18n/de/index.js         — MODIFIED (same keys in German)
src/frontend/src/router/routes.js          — MODIFIED (3 new routes)
```

### Previous Story Patterns to Follow

From Story 7.4:
- `@Observed(name="payment.*")` on all Resource classes
- All request/response DTOs are `record` types with Jakarta Validation on request types
- `@PreAuthorize` on every endpoint — no exceptions
- `SecurityUtil.getCurrentCoachUserId()` for both coach (returns coachUserId to resolve coachId) and parent (returns parentId directly)
- Use `securityUtil.getCurrentCoachUserId()` not direct principal cast (though direct cast pattern exists in `ReliabilityStrikeResource` — prefer SecurityUtil)

From Story 7.1 (`StripeOnboardingResource`):
- `resolveCoachId()` helper pattern — copy verbatim into `RevenueResource`

From Story 7.2 (`CreditWalletResource`):
- Parent uses `securityUtil.getCurrentCoachUserId()` to get parentId (Long) — confirmed line 33

### References

- Epics: `_bmad-output/planning-artifacts/skillars-epics.md` lines 2609–2656 (Story 7.5 AC + dev notes)
- `booking_payments` table: `V62__session_payment_credit_wallet.sql` — columns, CHECK constraint, no coach_id
- `parent_credit_ledger` types: `V62__session_payment_credit_wallet.sql` — BOOKING_REFUND, CASH_OUT_DEBIT, etc.
- `platform.commission.rate` config key: `V61__payment_module_init.sql` line 31
- `payment.stripe.feeRate`/`feeFixed` keys: `CashOutService.java` lines 37–38
- `CoachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter`: `platform.marketplace.repo.CoachReliabilityStrikeRepository`
- `PlayerProfile.name` field: `platform.security.repo.PlayerProfile.java`
- Admin role: `SecurityConstants.HAS_ADMIN_ROLE`
- `securityUtil.getCurrentCoachUserId()` for parentId: `CreditWalletResource.java` line 33
- `resolveCoachId()` pattern: `StripeOnboardingResource.java`
- Cross-module repo injection: `CancellationRefundService.java`, `BookingPaymentPersistenceService.java`
- Glassmorphism UI pattern: `CoachReliabilityPage.vue`, `CoachSubscriptionPage.vue`
- Payment store/API patterns: `payment.store.js`, `payment.api.js`
- FR-PAY-003 (fee transparency), FR-PAY-014 (financial reporting): `skillars-epics.md` lines 125, 136

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — implementation proceeded without blockers.

### Completion Notes List

- `ResourceForbiddenException` does not exist; used Spring Security `AccessDeniedException` instead (already handled by `ApiAdvice` as HTTP 403).
- `subscriptionRevenue = BigDecimal.ZERO` in `AdminFinanceOverviewDto` — V64 seeds only Stripe priceId strings, no EUR amount keys in ConfigService.
- `outstandingDisputeCount = 0` hardcoded with `// TODO Story 10.x: wire booking_disputes table` — no `booking_disputes` table exists.
- `PlayerProfile.name` is a full name field; first name extracted via `name.split("\\s+")[0]` for receipt endpoints (data minimisation).
- Credit statement running balance uses anchor query approach: `sumByParentIdAndCreatedAtBefore` for opening balance, then forward iteration in ASC order within the page.
- `ParentReceiptView.vue` created (in addition to coach `ReceiptView.vue`) since the parent receipt uses different fields (`stripeCharged`, `creditUsed`, `totalCharged`).
- Nav links added to `MainLayout.vue` for both Coach (Revenue Dashboard) and Parent (Credit Statement) sections.

### File List

**Backend — new:**
- `src/main/java/com/softropic/skillars/platform/payment/contract/RevenueSummaryDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/TransactionDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/ReceiptDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/ParentReceiptDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CreditStatementEntryDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/AdminFinanceOverviewDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachRevenueAdminDto.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/RevenueResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/AdminFinanceResource.java`

**Backend — modified:**
- `src/main/java/com/softropic/skillars/platform/payment/repo/BookingPaymentRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/ParentCreditLedgerRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentCoachSubscriptionRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentPlayerSubscriptionRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`

**Frontend — new:**
- `src/frontend/src/pages/coach/RevenueDashboardPage.vue`
- `src/frontend/src/pages/coach/ReceiptView.vue`
- `src/frontend/src/pages/parent/CreditStatementPage.vue`
- `src/frontend/src/pages/parent/ParentReceiptView.vue`

**Frontend — modified:**
- `src/frontend/src/api/payment.api.js`
- `src/frontend/src/stores/payment.store.js`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/layouts/MainLayout.vue`

**Tests — new:**
- `src/test/java/com/softropic/skillars/platform/payment/service/RevenueReportingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/ReceiptOwnershipIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/api/AdminFinanceResourceIT.java`

### Change Log

- Implemented Story 7.5: Revenue Dashboard & Financial Reporting (Date: 2026-06-26)

### Review Findings

- [x] [Review][Patch] fmt() strips sign via Math.abs() — debit entries display as positive on credit statement [src/frontend/src/pages/parent/CreditStatementPage.vue:1510]
- [x] [Review][Patch] getAdminOverview has no null-period defaults — returns all-zero results silently when from/to omitted [src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java]
- [x] [Review][Patch] window.print() called directly in Vue 3 template — print button throws TypeError at runtime [src/frontend/src/pages/coach/ReceiptView.vue:1284, src/frontend/src/pages/parent/ParentReceiptView.vue:1532]
- [x] [Review][Patch] ParentReceiptView.vue uses revenue.receipt.net i18n key for totalCharged field — wrong label ("Net Received" instead of "Total Charged") [src/frontend/src/pages/parent/ParentReceiptView.vue:~1567]
- [x] [Review][Patch] CreditStatementPage running balance column uses fmt() which applies Math.abs() — negative balance displays without sign [src/frontend/src/pages/parent/CreditStatementPage.vue:47]
- [x] [Review][Patch] MainLayout.vue nav items not role-gated — Coach Revenue, Parent Credit Statement, and Admin sections visible to all authenticated users regardless of role [src/frontend/src/layouts/MainLayout.vue:129-150]
- [x] [Review][Defer] Running balance is incorrect when two ledger entries share an identical createdAt instant and straddle a page boundary — the < predicate in sumByParentIdAndCreatedAtBefore excludes the prior-page entry from the opening balance, understating the running balance for the current page [src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java:211] — deferred, pre-existing design choice
