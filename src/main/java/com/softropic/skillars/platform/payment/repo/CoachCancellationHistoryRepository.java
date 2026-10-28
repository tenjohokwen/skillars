package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
