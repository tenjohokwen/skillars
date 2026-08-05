package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BookingRescheduleRequestRepository extends JpaRepository<BookingRescheduleRequest, UUID> {

    Optional<BookingRescheduleRequest> findFirstByBookingIdAndStatusOrderByCreatedAtDesc(UUID bookingId, String status);

    @Query("SELECT r FROM BookingRescheduleRequest r WHERE r.bookingId IN :bookingIds AND r.status = 'PENDING'")
    List<BookingRescheduleRequest> findPendingByBookingIdIn(@Param("bookingIds") Set<UUID> bookingIds);

    // Deferred-15 AC3: accept and decline both read the status and write it much later, with no
    // lock in between, so a decline could commit inside an accept's window and be silently
    // overwritten. Both now take this lock and re-check PENDING under it. Annotation stack copied
    // from CoachProfileRepository.findByIdForUpdate, including the bounded lock wait — without it,
    // contention blocks the caller indefinitely instead of surfacing as ApiAdvice's 409.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("SELECT r FROM BookingRescheduleRequest r WHERE r.id = :id")
    Optional<BookingRescheduleRequest> findByIdForUpdate(@Param("id") UUID id);
}
