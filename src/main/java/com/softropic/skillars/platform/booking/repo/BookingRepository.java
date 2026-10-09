package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByParentIdOrderByRequestedStartTimeAsc(Long parentId);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.coachId = :coachId
          AND b.status IN :statuses
          AND b.requestedStartTime < :endTime
          AND b.requestedEndTime > :startTime
          AND (:excludeBookingId IS NULL OR b.id <> :excludeBookingId)
        """)
    List<Booking> findOverlappingBookings(
        @Param("coachId") UUID coachId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime,
        @Param("statuses") List<String> statuses,
        @Param("excludeBookingId") UUID excludeBookingId);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.coachId = :coachId
          AND b.status IN :statuses
          AND b.requestedStartTime >= :weekStart
          AND b.requestedStartTime < :weekEnd
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findByCoachIdAndStatusInAndTimeBetween(
        @Param("coachId") UUID coachId,
        @Param("statuses") List<String> statuses,
        @Param("weekStart") Instant weekStart,
        @Param("weekEnd") Instant weekEnd);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.parentId = :parentId
          AND b.playerId = :playerId
          AND b.status IN :statuses
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findByParentIdAndPlayerIdAndStatusIn(
        @Param("parentId") Long parentId,
        @Param("playerId") Long playerId,
        @Param("statuses") List<String> statuses);

    List<Booking> findByCoachIdAndStatusOrderByRequestedStartTimeAsc(UUID coachId, String status);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'REQUESTED' AND b.createdAt < :threshold
        """)
    List<Booking> findRequestedBookingsOlderThan(@Param("threshold") Instant threshold);

    // Deferred-15 AC1. updatedAt, not createdAt: createdAt predates the accept by however long the
    // request sat in the coach's inbox, so it says nothing about how long settlement has been stuck.
    // updatedAt is stamped by Booking's @PreUpdate on the transition into PAYMENT_PENDING.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'PAYMENT_PENDING' AND b.updatedAt < :threshold
        """)
    List<Booking> findPaymentPendingOlderThan(@Param("threshold") Instant threshold);

    // Uses <= :windowEnd (not BETWEEN :now AND :windowEnd) so bookings whose start time
    // is already past (scheduler downtime) are caught as well — catch-up behaviour.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'CONFIRMED'
          AND b.requestedStartTime <= :windowEnd
          AND b.primaryReminderSentAt IS NULL
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findConfirmedForUpcomingTransition(@Param("windowEnd") Instant windowEnd);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'UPCOMING'
          AND b.requestedStartTime BETWEEN :now AND :windowEnd
          AND b.secondaryReminderSentAt IS NULL
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findUpcomingWithin2hWindow(@Param("now") Instant now, @Param("windowEnd") Instant windowEnd);

    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.playerId = :playerId
          AND b.coachId = :coachId
          AND b.status IN ('REQUESTED', 'ACCEPTED', 'CONFIRMED', 'UPCOMING')
        """)
    long countInFlightBookings(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);

    List<Booking> findByBatchId(UUID batchId);

    List<Booking> findByBatchIdAndStatus(UUID batchId, String status);

    @Query("SELECT b.batchId, COUNT(b) FROM Booking b WHERE b.batchId IN :batchIds GROUP BY b.batchId")
    List<Object[]> countByBatchIdIn(@Param("batchIds") Set<UUID> batchIds);

    @Query("""
        SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
        FROM Booking b
        WHERE b.coachId = :coachId
          AND b.playerId = :playerId
          AND b.status = 'COMPLETED'
          AND b.updatedAt >= :windowStart
        """)
    boolean existsRecentCompletedBooking(
        @Param("coachId") UUID coachId,
        @Param("playerId") Long playerId,
        @Param("windowStart") java.time.Instant windowStart);

    // skillars-deferred-150 AC3: ORDER BY is required, not optional, alongside the bound -- but
    // be precise about WHY (code review 2026-10-09 corrected an earlier draft of this comment
    // that overclaimed safety here). ORDER BY b.playerId gives the caller's actually-owned
    // playerId no special preference -- the ownership check (ReviewSubmissionService's Java loop)
    // runs strictly AFTER this query's own truncation, so ordering cannot protect any specific
    // row. What it buys is determinism: WITHOUT it, an unordered SELECT DISTINCT truncated by a
    // bare `limit` returns a Postgres-internal, plan-dependent, call-to-call-unstable subset --
    // the exact same input could pass on one call and false-deny on the next. WITH it, the same
    // input always truncates to the same 50 rows, so the (already decided, see this story's
    // Review Findings) accepted residual risk -- a parent/author with more than 50 distinct
    // qualifying playerIds, where the one actually-owned row sorts above the cap -- is at least
    // reproducible rather than flaky. The bound (50) is a generous insurance cap, safely above
    // any realistic distinct-player-per-author-per-coach count; a literal HQL `limit` is used
    // instead of Pageable to keep this method's 4-arg signature unchanged (Pageable would break
    // ReviewSubmissionServiceTest's existing mock stub at compile time). See
    // ReviewSubmissionService.evaluateEligibility's own QUALIFYING_BOOKINGS_CAP log line for the
    // observability this residual risk did not have before this review.
    @Query("""
        SELECT DISTINCT b.playerId AS playerId
        FROM Booking b
        WHERE b.coachId = :coachId
          AND (b.parentId = :authorId OR b.playerId = :authorId)
          AND b.status = 'COMPLETED'
          AND b.updatedAt <= :maturedBefore
          AND b.updatedAt > :sinceAfter
        ORDER BY b.playerId
        limit 50
        """)
    List<BookingReviewEligibilityProjection> findQualifyingCompletedBookings(
        @Param("coachId") UUID coachId,
        @Param("authorId") Long authorId,
        @Param("maturedBefore") Instant maturedBefore,
        @Param("sinceAfter") Instant sinceAfter);

    @Query("""
        SELECT b FROM Booking b
        WHERE b.playerId = :playerId
          AND b.coachId = :coachId
          AND b.status IN :statuses
          AND b.requestedStartTime >= :pauseStart
          AND b.requestedStartTime <  :pauseEnd
        ORDER BY b.requestedStartTime ASC
        """)
    List<Booking> findConflictingBookingsForPause(
        @Param("playerId")   Long playerId,
        @Param("coachId")    UUID coachId,
        @Param("pauseStart") Instant pauseStart,
        @Param("pauseEnd")   Instant pauseEnd,
        @Param("statuses")   List<String> statuses);

    Optional<Booking> findByIdAndCoachId(UUID bookingId, UUID coachId);

    Optional<Booking> findByIdAndParentId(UUID bookingId, Long parentId);

    List<Booking> findAllByCoachId(UUID coachId);

    List<Booking> findAllByPlayerId(Long playerId);

    @Query("""
        SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
        FROM Booking b
        WHERE b.coachId = :coachId
          AND b.status IN :statuses
        """)
    boolean existsByCoachIdAndStatusIn(@Param("coachId") UUID coachId, @Param("statuses") List<String> statuses);

    @Query("""
        SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
        FROM Booking b
        WHERE b.coachId = :coachId
          AND b.playerId = :playerId
          AND b.status IN :statuses
        """)
    boolean existsByCoachIdAndPlayerIdAndStatusIn(
        @Param("coachId") UUID coachId,
        @Param("playerId") Long playerId,
        @Param("statuses") List<String> statuses);

    // UAT.3 AC1/AC2: the capture reservation and the parent's cancel both read this row and write
    // much later, in separate transactions, so @Version cannot serialise them — a cancel could
    // commit inside the window between a Stripe capture and the row that records it. Both now take
    // this lock and re-read under it. Annotation stack copied from
    // BookingRescheduleRequestRepository.findByIdForUpdate, including the NO_WAIT +
    // PessimisticLockRetryer bounded-retry pair (skillars-deferred-62) — see
    // CoachProfileRepository.findByIdForUpdate's comment for the full mechanism.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);
}
