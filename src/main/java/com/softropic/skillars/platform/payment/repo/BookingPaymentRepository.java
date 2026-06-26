package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, UUID> {

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

    @Query("SELECT SUM(bp.stripeCharged + bp.creditDebited) FROM BookingPayment bp WHERE bp.status = 'CAPTURED' AND bp.capturedAt BETWEEN :from AND :to")
    Optional<BigDecimal> sumTotalGross(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COUNT(bp) FROM BookingPayment bp WHERE bp.status = 'CAPTURED' AND bp.capturedAt BETWEEN :from AND :to")
    long countCapturedForPeriod(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        SELECT bp.booking_id
        FROM payment.booking_payments bp
        JOIN booking.bookings b ON b.id = bp.booking_id
        WHERE b.coach_id = :coachId AND bp.captured_at BETWEEN :from AND :to
        """, nativeQuery = true)
    List<UUID> findBookingIdsByCoachAndPeriod(@Param("coachId") UUID coachId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);
}
