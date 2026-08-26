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
import java.util.UUID;

public interface BookingBatchRepository extends JpaRepository<BookingBatch, UUID> {

    List<BookingBatch> findByCoachIdAndStatus(UUID coachId, String status);

    // skillars-deferred-69 AC6: acceptAll's trailing transaction and updateBatchStatusFromBooking
    // (fired per-booking-status-change) both read-compute-write BookingBatch.status with no lock
    // between them — a last-writer-wins race. Annotation stack mirrors BookingRescheduleRequestRepository
    // /CoachProfileRepository/SessionPackPurchaseRepository's own findByIdForUpdate (skillars-deferred-62):
    // NO_WAIT + PessimisticLockRetryer's bounded retry, rather than an indefinite block or an immediate
    // failure on any brief overlap.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select b from BookingBatch b where b.id = :id")
    Optional<BookingBatch> findByIdForUpdate(@Param("id") UUID id);
}
