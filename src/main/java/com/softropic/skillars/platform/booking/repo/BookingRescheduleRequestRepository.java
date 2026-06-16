package com.softropic.skillars.platform.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BookingRescheduleRequestRepository extends JpaRepository<BookingRescheduleRequest, UUID> {

    Optional<BookingRescheduleRequest> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, String status);

    @Query("SELECT r FROM BookingRescheduleRequest r WHERE r.bookingId IN :bookingIds AND r.status = 'PENDING'")
    List<BookingRescheduleRequest> findPendingByBookingIdIn(@Param("bookingIds") Set<UUID> bookingIds);
}
