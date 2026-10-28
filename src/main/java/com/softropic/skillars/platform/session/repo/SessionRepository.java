package com.softropic.skillars.platform.session.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Optional<Session> findByBookingIdAndCoachId(UUID bookingId, UUID coachId);

    boolean existsByBookingId(UUID bookingId);

    /** feat/coach-workflow-gaps A19: which of {@code bookingIds} already carry a session plan (one query). */
    @org.springframework.data.jpa.repository.Query("SELECT s.bookingId FROM Session s WHERE s.bookingId IN :bookingIds")
    List<UUID> findBookingIdsWithPlan(@org.springframework.data.repository.query.Param("bookingIds") java.util.Collection<UUID> bookingIds);

    List<Session> findTop5ByPlayerIdOrderByCreatedAtDesc(Long playerId);

    List<Session> findByPlayerIdAndCoachIdAndStatusInOrderByCreatedAtDesc(
        Long playerId, UUID coachId, List<String> statuses, Pageable pageable);
}
