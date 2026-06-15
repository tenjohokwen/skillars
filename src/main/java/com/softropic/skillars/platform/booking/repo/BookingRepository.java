package com.softropic.skillars.platform.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findAllByParentIdOrderByRequestedStartTimeAsc(Long parentId);

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
}
