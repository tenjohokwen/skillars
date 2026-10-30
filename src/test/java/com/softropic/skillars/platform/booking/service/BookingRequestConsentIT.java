package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.booking.contract.BatchSlot;
import com.softropic.skillars.platform.booking.contract.BatchBookingCreatedResponse;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingResponse;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.contract.CreateBookingRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fix/booking-request-consent, end to end on the shared test context (no extra mocks; the context's
 * StubPaymentGateway settles AFTER_COMMIT of the accept):
 * <ul>
 *   <li>H3 — a coach "repeat next week" proposal cannot be accepted by the coach; the parent confirms it,
 *       which charges it, or withdraws it;</li>
 *   <li>M1 — a parent withdraws any REQUESTED booking, and a withdraw never cancels a charged one;</li>
 *   <li>M2 — the per-parent pending-request cap and the booking horizon;</li>
 *   <li>M3 — a second, concurrent accept-all on one batch is refused under the batch row lock.</li>
 * </ul>
 * fix/audit3-booking-scheduling-integrity adds: duplicate idempotency, pack-session reservation at request
 * time, the pack pause on the duplicate / reschedule paths, and the slot-grid check against the live
 * availability, block and booking rows.
 */
class BookingRequestConsentIT extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private BookingBatchService bookingBatchService;
    @Autowired private BookingDuplicationService bookingDuplicationService;
    @Autowired private RescheduleService rescheduleService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final long PARENT_ID = 9520200001L;
    private static final long PLAYER_ID = 9520200011L;
    private static final long COACH_USER_ID = 9520200021L;
    private static final String TZ = "Europe/Berlin";
    private static final long BATCH_LOCK_HOLD_MILLIS = 1500;

    private UUID coachProfileId;
    private Instant slotStart;
    private Instant slotEnd;

    @BeforeEach
    void setUp() {
        // One base instant for start and end (chk_bkg_end_after_start).
        ZonedDateTime nextDaySlot = ZonedDateTime.now(ZoneId.of(TZ)).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
        slotStart = nextDaySlot.toInstant();
        slotEnd = nextDaySlot.plusHours(1).toInstant();

        transactionTemplate.execute(status -> {
            insertUser(PARENT_ID, "parent.consent@skillars-test.com", "PARENT");
            insertUser(COACH_USER_ID, "coach.consent@skillars-test.com", "COACH");
            jdbcTemplate.update(
                "INSERT INTO main.player_profiles " +
                "(id, name, date_of_birth, position, age_tier, parent_id, independent_account_allowed, created_at, created_by) " +
                "VALUES (?, 'Consent Player', ?, 'MIDFIELDER', 'ADULT', ?, true, ?, 'system')",
                PLAYER_ID, Date.valueOf(LocalDate.now().minusYears(16)), PARENT_ID, Timestamp.from(Instant.now()));
            coachProfileId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_profiles " +
                "(id, user_id, display_name, bio, city, languages, canonical_timezone, status) " +
                "VALUES (?, ?, 'Consent Coach', 'Bio', 'Berlin', ARRAY['English']::varchar[], ?, 'ACTIVE')",
                coachProfileId, COACH_USER_ID, TZ);
            jdbcTemplate.update(
                "INSERT INTO marketplace.coach_pricing (coach_id, per_session_price, currency) VALUES (?, 50.00, 'EUR')",
                coachProfileId);
            // Every day 08:00-18:00, so the slots below (tomorrow, +3 days, +130 days) are all inside a window.
            for (short dow = 1; dow <= 7; dow++) {
                jdbcTemplate.update(
                    "INSERT INTO marketplace.coach_availability_windows " +
                    "(id, coach_id, day_of_week, start_time, end_time, canonical_timezone) " +
                    "VALUES (?, ?, ?, '08:00', '18:00', ?)",
                    UUID.randomUUID(), coachProfileId, dow, TZ);
            }
            return null;
        });
    }

    // ---- H3: coach proposals need the parent's confirmation ----

    @Test
    void coachProposal_coachCannotAcceptIt_parentConfirmChargesIt() {
        UUID proposalId = proposeRepeatOfLastWeek();

        Booking proposal = bookingRepository.findById(proposalId).orElseThrow();
        assertThat(proposal.isProposedByCoach()).isTrue();
        assertThat(proposal.getStatus()).isEqualTo("REQUESTED");
        assertThat(proposal.getAgreedPrice()).isEqualByComparingTo("50.00");

        assertThatThrownBy(() -> bookingService.acceptBooking(proposalId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.PARENT_CONFIRMATION_REQUIRED);
        assertThat(bookingRepository.findById(proposalId).orElseThrow().getStatus()).isEqualTo("REQUESTED");
        assertThat(paymentRowCount(proposalId)).as("the coach's refused accept charged nothing").isZero();

        bookingService.confirmProposedBooking(proposalId, PARENT_ID);

        Map<String, Object> bp = jdbcTemplate.queryForMap(
            "SELECT status, stripe_charged FROM payment.booking_payments WHERE booking_id = ?", proposalId);
        assertThat(bp.get("status")).isEqualTo("CAPTURED");
        assertThat((BigDecimal) bp.get("stripe_charged")).isEqualByComparingTo("50.00");
        assertThat(bookingRepository.findById(proposalId).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void coachProposal_parentDeclines_cancelledAndNeverCharged() {
        UUID proposalId = proposeRepeatOfLastWeek();

        bookingService.withdrawBookingRequest(proposalId, PARENT_ID);

        assertThat(bookingRepository.findById(proposalId).orElseThrow().getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThat(paymentRowCount(proposalId)).isZero();
        assertThat(ledgerRowCount()).as("nothing to refund: nothing was taken").isZero();
        assertThatThrownBy(() -> bookingService.confirmProposedBooking(proposalId, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.REQUEST_NOT_PENDING);
    }

    @Test
    void parentCannotConfirmTheirOwnRequest() {
        BookingResponse created = request(slotStart, slotEnd);

        assertThatThrownBy(() -> bookingService.confirmProposedBooking(created.id(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.NOT_A_COACH_PROPOSAL);
        assertThat(paymentRowCount(created.id())).isZero();
    }

    // ---- M1: parent withdraws a REQUESTED booking ----

    @Test
    void parentWithdrawsOwnRequest_slotIsFreedAndTheCoachCanNoLongerAcceptIt() {
        BookingResponse created = request(slotStart, slotEnd);

        bookingService.withdrawBookingRequest(created.id(), PARENT_ID);

        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo("CANCELLED_PARENT");
        assertThatThrownBy(() -> bookingService.acceptBooking(created.id(), COACH_USER_ID))
            .isInstanceOfAny(OperationNotAllowedException.class,
                com.softropic.skillars.platform.booking.contract.BookingStateTransitionException.class);
        assertThat(paymentRowCount(created.id())).isZero();
        // The REQUESTED hold is gone, so the same slot can be requested again.
        assertThat(request(slotStart, slotEnd).status()).isEqualTo("REQUESTED");
    }

    @Test
    void withdrawAfterTheCoachAccepted_isRefused_theChargedBookingStaysConfirmed() {
        BookingResponse created = request(slotStart, slotEnd);
        bookingService.acceptBooking(created.id(), COACH_USER_ID);
        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo("CONFIRMED");

        assertThatThrownBy(() -> bookingService.withdrawBookingRequest(created.id(), PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.REQUEST_NOT_PENDING);
        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void anotherParentCannotWithdrawTheRequest() {
        BookingResponse created = request(slotStart, slotEnd);

        assertThatThrownBy(() -> bookingService.withdrawBookingRequest(created.id(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(bookingRepository.findById(created.id()).orElseThrow().getStatus()).isEqualTo("REQUESTED");
    }

    // ---- M2: pending-request cap and horizon ----

    @Test
    void pendingRequestCap_theEleventhRequestToOneCoachIsRefused_coachProposalsDoNotCount() {
        Instant base = slotStart.plus(Duration.ofDays(3));
        for (int i = 0; i < 9; i++) {
            seedRequested(base.plus(Duration.ofHours(i)), false);
        }
        // A coach proposal is REQUESTED too, but the parent did not make it.
        seedRequested(base.plus(Duration.ofDays(1)), true);

        assertThat(request(slotStart, slotEnd).status()).as("the 10th of the parent's own requests").isEqualTo("REQUESTED");

        Instant next = slotStart.plus(Duration.ofHours(2));
        assertThatThrownBy(() -> request(next, next.plus(Duration.ofHours(1))))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.TOO_MANY_PENDING_REQUESTS);
        assertThat(bookingCountForCoach()).isEqualTo(11);
    }

    @Test
    void pendingRequestCap_aBatchThatWouldCrossItIsRefusedWhole() {
        Instant base = slotStart.plus(Duration.ofDays(3));
        for (int i = 0; i < 9; i++) {
            seedRequested(base.plus(Duration.ofHours(i)), false);
        }
        Instant second = slotStart.plus(Duration.ofHours(2));

        assertThatThrownBy(() -> bookingBatchService.createBatch(PARENT_ID, new CreateBatchRequest(
                coachProfileId, PLAYER_ID,
                List.of(new BatchSlot(slotStart, slotEnd), new BatchSlot(second, second.plus(Duration.ofHours(1)))),
                null, null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.TOO_MANY_PENDING_REQUESTS);
        assertThat(bookingCountForCoach()).isEqualTo(9);
    }

    @Test
    void requestBeyondTheBookingHorizon_isRefused() {
        ZonedDateTime farAhead = ZonedDateTime.now(ZoneId.of(TZ)).plusDays(130)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);

        assertThatThrownBy(() -> request(farAhead.toInstant(), farAhead.plusHours(1).toInstant()))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.BEYOND_BOOKING_HORIZON);
        assertThat(bookingCountForCoach()).isZero();
    }

    // ---- M3: concurrent accept-all on one batch ----

    /**
     * A concurrent accept-all holds the batch row and has flipped it to ACCEPTING but not committed yet, so
     * this call's unlocked PENDING check passes. Its claim then waits for the row lock (NO_WAIT + bounded
     * retry), sees ACCEPTING once the holder commits, and is refused — no booking is accepted by it.
     */
    @Test
    @Timeout(30)
    void acceptAll_whileAConcurrentCallHoldsTheClaim_isRefusedAndAcceptsNothing() throws Exception {
        BatchBookingCreatedResponse batch = bookingBatchService.createBatch(PARENT_ID, new CreateBatchRequest(
            coachProfileId, PLAYER_ID, List.of(new BatchSlot(slotStart, slotEnd)), null, null));
        UUID batchId = batch.batchId();

        CountDownLatch claimHeld = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        AtomicReference<Throwable> acceptOutcome = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.queryForObject(
                            "SELECT status FROM booking.booking_batches WHERE id = ? FOR UPDATE", String.class, batchId);
                        jdbcTemplate.update(
                            "UPDATE booking.booking_batches SET status = 'ACCEPTING' WHERE id = ?", batchId);
                        claimHeld.countDown();
                        try {
                            Thread.sleep(BATCH_LOCK_HOLD_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("Interrupted while holding the batch lock", e);
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    holderFailure.set(t);
                }
            });
            Future<?> accepter = executor.submit(() -> {
                try {
                    claimHeld.await(10, TimeUnit.SECONDS);
                    bookingBatchService.acceptAll(batchId, COACH_USER_ID);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    acceptOutcome.set(t);
                }
            });
            holder.get(30, TimeUnit.SECONDS);
            accepter.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertThat(holderFailure.get()).isNull();
        assertThat(acceptOutcome.get())
            .as("the claim re-checks PENDING under the batch row lock")
            .isInstanceOf(OperationNotAllowedException.class);
        assertThat(((OperationNotAllowedException) acceptOutcome.get()).getErrorCode())
            .isEqualTo(BookingError.BATCH_ALREADY_PROCESSED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM booking.bookings WHERE batch_id = ?", String.class, batchId))
            .containsExactly("REQUESTED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId))
            .as("the other call's claim is left alone").isEqualTo("ACCEPTING");
    }

    @Test
    void acceptAll_thenASecondAcceptAll_isRefusedAndTheBatchEndsFullyAccepted() {
        BatchBookingCreatedResponse batch = bookingBatchService.createBatch(PARENT_ID, new CreateBatchRequest(
            coachProfileId, PLAYER_ID, List.of(new BatchSlot(slotStart, slotEnd)), null, null));

        bookingBatchService.acceptAll(batch.batchId(), COACH_USER_ID);

        assertThatThrownBy(() -> bookingBatchService.acceptAll(batch.batchId(), COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.BATCH_ALREADY_PROCESSED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batch.batchId()))
            .isEqualTo("FULLY_ACCEPTED");
    }

    // ---- fix/audit3-booking-scheduling-integrity ----

    /** "Repeat next week" clicked twice proposed the same session twice; the open proposal now holds the slot. */
    @Test
    void coachRepeatNextWeek_twice_secondIsRefusedSlotUnavailable() {
        UUID first = proposeRepeatOfLastWeek();
        UUID originalId = jdbcTemplate.queryForObject(
            "SELECT id FROM booking.bookings WHERE coach_id = ? AND status = 'COMPLETED'", UUID.class, coachProfileId);

        assertThatThrownBy(() -> bookingDuplicationService.duplicateNextWeek(originalId, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.SLOT_UNAVAILABLE);
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM booking.bookings WHERE coach_id = ? AND proposed_by_coach = true", UUID.class,
                coachProfileId))
            .containsExactly(first);
    }

    /** A duplicate can no longer be stacked on the family's own pending request for that time. */
    @Test
    void coachRepeatNextWeek_ontoAPendingParentRequest_isRefused() {
        request(slotStart, slotEnd);

        assertThatThrownBy(this::proposeRepeatOfLastWeek)
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.SLOT_UNAVAILABLE);
    }

    /**
     * A one-session pack backs one open request, not any number: the session is deducted only at settlement,
     * so the open (unsettled) bookings drawing on the pack are counted. Withdrawing the first frees it again.
     */
    @Test
    void packRequests_onlyAsManyOpenRequestsAsTheRemainingSessions() {
        UUID packId = insertPack(1, null, null);
        Instant second = slotStart.plus(Duration.ofHours(2));

        BookingResponse first = requestWithPack(slotStart, slotEnd, packId);
        assertThatThrownBy(() -> requestWithPack(second, second.plus(Duration.ofHours(1)), packId))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.packSessionsReserved");

        bookingService.withdrawBookingRequest(first.id(), PARENT_ID);
        assertThat(requestWithPack(second, second.plus(Duration.ofHours(1)), packId).status()).isEqualTo("REQUESTED");
    }

    /** findActivePacks lists a pack whose pause starts later; the duplicate inside that pause is refused. */
    @Test
    void coachRepeatNextWeek_intoTheParentsPackPause_isRefusedPackPaused() {
        insertPack(5, slotStart.minus(Duration.ofHours(6)), slotStart.plus(Duration.ofDays(30)));

        assertThatThrownBy(this::proposeRepeatOfLastWeek)
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.packPaused");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.bookings WHERE proposed_by_coach = true", Integer.class)).isZero();
    }

    /** Moving a paid pack session into the pack's pause (proposal or accept) is refused. */
    @Test
    void rescheduleAPackBookingIntoThePause_isRefused() {
        Instant pauseFrom = slotStart.plus(Duration.ofDays(2));
        UUID packId = insertPack(5, pauseFrom, pauseFrom.plus(Duration.ofDays(30)));
        UUID bookingId = transactionTemplate.execute(status -> {
            Booking b = new Booking();
            b.setParentId(PARENT_ID);
            b.setPlayerId(PLAYER_ID);
            b.setCoachId(coachProfileId);
            b.setRequestedStartTime(slotStart);
            b.setRequestedEndTime(slotEnd);
            b.setCanonicalTimezone(TZ);
            b.setStatus("CONFIRMED");
            b.setSessionPackPurchaseId(packId);
            return bookingRepository.save(b).getId();
        });
        Instant intoPause = slotStart.plus(Duration.ofDays(3));

        assertThatThrownBy(() -> rescheduleService.requestReschedule(bookingId, PARENT_ID,
                new com.softropic.skillars.platform.booking.contract.CreateRescheduleRequest(
                    intoPause, intoPause.plus(Duration.ofHours(1)), null)))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.packPaused");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.booking_reschedule_requests WHERE booking_id = ?", Integer.class, bookingId))
            .isZero();
    }

    /**
     * The grid is the calendar's: 10:30 is off the 08:00 hourly grid; after a 12:00-12:20 block the next slot
     * is 12:20, which is accepted, while 13:00 (window grid, but not offered after the block) is refused.
     */
    @Test
    void requestOffTheCalendarsSlotGrid_isRefused_blockReAnchoredSlotIsAccepted() {
        Instant offGrid = slotStart.plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> request(offGrid, offGrid.plus(Duration.ofHours(1))))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.SLOT_NOT_ON_GRID);

        Instant blockStart = slotStart.plus(Duration.ofHours(2));
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "INSERT INTO booking.coach_availability_blocks (id, coach_id, start_datetime, end_datetime, reason) "
                + "VALUES (?, ?, ?, ?, 'Lunch')",
            UUID.randomUUID(), coachProfileId, Timestamp.from(blockStart),
            Timestamp.from(blockStart.plus(Duration.ofMinutes(20)))));
        Instant afterBlock = blockStart.plus(Duration.ofMinutes(20));
        assertThat(request(afterBlock, afterBlock.plus(Duration.ofHours(1))).status()).isEqualTo("REQUESTED");
        Instant windowGrid = slotStart.plus(Duration.ofHours(4));
        // 14:00 is not offered either: after the 12:20 slot the grid runs 13:20, 14:20 ...
        assertThatThrownBy(() -> request(windowGrid, windowGrid.plus(Duration.ofHours(1))))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(e -> ((OperationNotAllowedException) e).getErrorCode())
            .isEqualTo(BookingError.SLOT_NOT_ON_GRID);
        assertThat(bookingCountForCoach()).isEqualTo(1);
    }

    // ---- fixtures ----

    /** A COMPLETED session exactly one week before {@code slotStart}, repeated by the coach. */
    private UUID proposeRepeatOfLastWeek() {
        // fix/audit3-booking-scheduling-integrity: a calendar week in the booking's zone, as duplicateNextWeek
        // computes it — 7 x 24h put the duplicate an hour off slotStart in a week with a DST change.
        Instant lastWeek = slotStart.atZone(ZoneId.of(TZ)).minusWeeks(1).toInstant();
        UUID originalId = transactionTemplate.execute(status -> {
            Booking original = new Booking();
            original.setParentId(PARENT_ID);
            original.setPlayerId(PLAYER_ID);
            original.setCoachId(coachProfileId);
            original.setRequestedStartTime(lastWeek);
            original.setRequestedEndTime(lastWeek.plus(Duration.ofHours(1)));
            original.setCanonicalTimezone(TZ);
            original.setStatus("COMPLETED");
            original.setAgreedPrice(new BigDecimal("50.00"));
            return bookingRepository.save(original).getId();
        });
        return bookingDuplicationService.duplicateNextWeek(originalId, COACH_USER_ID);
    }

    private BookingResponse request(Instant start, Instant end) {
        return bookingService.createBookingRequest(PARENT_ID,
            new CreateBookingRequest(coachProfileId, PLAYER_ID, start, end, null, null, null));
    }

    private BookingResponse requestWithPack(Instant start, Instant end, UUID packId) {
        return bookingService.createBookingRequest(PARENT_ID,
            new CreateBookingRequest(coachProfileId, PLAYER_ID, start, end, null, packId, null));
    }

    /** A pack of this parent/player/coach, optionally with a pause over [pausedFrom, pausedUntil). */
    private UUID insertPack(int remaining, Instant pausedFrom, Instant pausedUntil) {
        UUID tierId = UUID.randomUUID();
        UUID packId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_tiers "
                    + "(pack_tier_id, coach_id, label, session_count, total_price, price_per_session, is_active, version, created_at) "
                    + "VALUES (?, ?, '5-Pack', 5, 150.00, 30.00, true, 0, now())",
                tierId, coachProfileId);
            jdbcTemplate.update(
                "INSERT INTO payment.session_pack_purchases "
                    + "(purchase_id, parent_id, player_id, coach_id, pack_tier_id, price_per_session, remaining_sessions, "
                    + "expires_at, paused_from, paused_until, version, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, 30.00, ?, ?, ?, ?, 0, now())",
                packId, PARENT_ID, PLAYER_ID, coachProfileId, tierId, remaining,
                Timestamp.from(Instant.now().plus(Duration.ofDays(90))),
                pausedFrom == null ? null : Timestamp.from(pausedFrom),
                pausedUntil == null ? null : Timestamp.from(pausedUntil));
            return null;
        });
        return packId;
    }

    private void seedRequested(Instant start, boolean proposedByCoach) {
        transactionTemplate.execute(status -> {
            Booking booking = new Booking();
            booking.setParentId(PARENT_ID);
            booking.setPlayerId(PLAYER_ID);
            booking.setCoachId(coachProfileId);
            booking.setRequestedStartTime(start);
            booking.setRequestedEndTime(start.plus(Duration.ofHours(1)));
            booking.setCanonicalTimezone(TZ);
            booking.setStatus("REQUESTED");
            booking.setAgreedPrice(new BigDecimal("50.00"));
            booking.setProposedByCoach(proposedByCoach);
            return bookingRepository.save(booking);
        });
    }

    private int paymentRowCount(UUID bookingId) {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.booking_payments WHERE booking_id = ?", Integer.class, bookingId);
        return n == null ? 0 : n;
    }

    private int ledgerRowCount() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.parent_credit_ledger WHERE parent_id = ?", Integer.class, PARENT_ID);
        return n == null ? 0 : n;
    }

    private int bookingCountForCoach() {
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking.bookings WHERE coach_id = ?", Integer.class, coachProfileId);
        return n == null ? 0 : n;
    }

    private void insertUser(long id, String email, String role) {
        jdbcTemplate.update(
            "INSERT INTO main.\"user\" " +
            "(id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, " +
            "status, dob, email, first_name, gender, lang_key, last_name, iso2_country, phone, " +
            "activated, locked, login, login_id_type, password_hash, otp_enabled, " +
            "skillars_role, verification_status) " +
            "VALUES (?, 'system', ?, 'system', ?, 'test-req', NULL, " +
            "'ACTIVE', '1985-06-01', ?, 'Test', 'OTHER', 'en', 'User', 'DE', ?, " +
            "true, false, ?, 'EMAIL', 'hash', false, " +
            "?, 'BASIC_VERIFIED')",
            id,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
            email,
            "69" + (id % 100000000),
            email,
            role);
    }
}
