package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CoachCancellationHistoryRepository extends JpaRepository<CoachCancellationHistory, UUID> {

    List<CoachCancellationHistory> findByCoachIdOrderByCreatedAtDesc(UUID coachId);

    List<CoachCancellationHistory> findByBookingId(UUID bookingId);

    // fix/booking-cancel-policy-loopholes: rolling-window excused-cancellation count.
    long countByCoachIdAndCancelReasonInAndCreatedAtAfter(UUID coachId, Collection<String> cancelReasons,
                                                          Instant after);

    /**
     * fix/booking-price-and-excused-race: per-coach serialisation of the excused-cancellation
     * count-and-strike in {@code CancellationRefundService.onBookingCancelledByCoach}. Without it two
     * concurrent excused cancels by one coach each counted the window before the other's history row
     * committed (READ COMMITTED), both saw a count within the cap, and the strike that the later one
     * owed was never issued. Transaction-scoped, so released at the listener's commit/rollback — after
     * its history row is visible to the next waiter.
     *
     * <p>An advisory lock rather than {@code SELECT ... FOR UPDATE} on the coach_profiles row on purpose:
     * {@code ReliabilityStrikeService.issue} runs REQUIRES_NEW on a second connection and locks that same
     * coach row, so holding it here would self-deadlock (55P03). The two-int form
     * {@code (namespace, hashtext(coachId))} keeps it out of the other advisory key spaces. Not
     * {@code @Modifying}: a SELECT of a void function, same shape as
     * {@code FileStorageObjectRepository.acquireOwnerQuotaLock}.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(" + EXCUSED_CANCEL_LOCK_NAMESPACE + ", hashtext(:coachId))",
        nativeQuery = true)
    void acquireCoachCancellationLock(@Param("coachId") String coachId);

    /** {@code pg_advisory_xact_lock(int, int)} namespace for {@link #acquireCoachCancellationLock} ("CCXL"). */
    int EXCUSED_CANCEL_LOCK_NAMESPACE = 0x4343584C;
}
