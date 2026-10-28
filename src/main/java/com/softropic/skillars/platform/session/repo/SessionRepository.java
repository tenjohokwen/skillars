package com.softropic.skillars.platform.session.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    // skillars-12-3 Task 3.2: the focus-thread window — mirrors only the *status* clause of
    // SluCalculationService.onBookingCompleted's own status check (SAVED/COMPLETED sessions have
    // meaningfully committed block content), deliberately not that method's other five gates.
    //
    // skillars-12-3 code review (2026-10-15) Patch: a derived-query method name's own "Top10" is a
    // literal baked into Spring Data's query derivation at startup — it cannot read this constant, so
    // this used to govern nothing. findByPlayerIdAndCoachIdAndStatusInOrderByCreatedAtDesc below takes
    // a Pageable instead, so SessionPrepService's PageRequest.of(0, SESSION_WINDOW) call is what
    // actually makes this constant load-bearing.
    int SESSION_WINDOW = 10;

    Optional<Session> findByBookingId(UUID bookingId);

    /**
     * fix/content-consistency-and-lost-updates: {@code Session} has no {@code @Version}, so
     * {@code SessionPlanService.updateSession}'s full-row save must hold the row lock while it
     * re-checks the status, or a coach edit racing {@code handleBookingCompleted} could flip a
     * COMPLETED plan back to SAVED. NOWAIT (lock.timeout 0) + {@code PessimisticLockRetryer}, the
     * project's standard shape — see {@code CoachProfileRepository.findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Booking-completed transition as one atomic conditional UPDATE (no read-modify-write): it
     * takes the row lock itself, so it waits for an in-flight {@code updateSession} to commit and
     * then applies on top of it, and an {@code updateSession} that locks afterwards sees COMPLETED
     * and refuses. Bulk JPQL skips {@code @PreUpdate}, hence the explicit {@code updatedAt}.
     *
     * Only an open (DRAFT/SAVED) plan moves, matching {@link #markCancelledIfOpenByBookingId}: a
     * CANCELLED plan stays locked rather than being revived by a later completion event.
     *
     * @return 1 if this call completed the plan, 0 if there is none or it is no longer open
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Session s SET s.status = 'COMPLETED', s.updatedAt = :now "
           + "WHERE s.bookingId = :bookingId AND s.status IN ('DRAFT', 'SAVED')")
    int markCompletedByBookingId(@Param("bookingId") UUID bookingId, @Param("now") Instant now);

    /**
     * Terminal-non-completion transition (Deferred-78 AC8), same atomic shape as
     * {@link #markCompletedByBookingId}: only a still-open (DRAFT/SAVED) plan becomes CANCELLED.
     *
     * @return 1 if this call cancelled the plan, else 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Session s SET s.status = 'CANCELLED', s.updatedAt = :now "
           + "WHERE s.bookingId = :bookingId AND s.status IN ('DRAFT', 'SAVED')")
    int markCancelledIfOpenByBookingId(@Param("bookingId") UUID bookingId, @Param("now") Instant now);

    Optional<Session> findByBookingIdAndCoachId(UUID bookingId, UUID coachId);

    boolean existsByBookingId(UUID bookingId);

    /** feat/coach-workflow-gaps A19: which of {@code bookingIds} already carry a session plan (one query). */
    @Query("SELECT s.bookingId FROM Session s WHERE s.bookingId IN :bookingIds")
    List<UUID> findBookingIdsWithPlan(@Param("bookingIds") java.util.Collection<UUID> bookingIds);

    List<Session> findTop5ByPlayerIdOrderByCreatedAtDesc(Long playerId);

    List<Session> findByPlayerIdAndCoachIdAndStatusInOrderByCreatedAtDesc(
        Long playerId, UUID coachId, List<String> statuses, Pageable pageable);
}
