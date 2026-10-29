package com.softropic.skillars.platform.booking.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
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

    /** C2: a safety hold's unlocked read of the coach's live sessions (reported, never modified). */
    List<Booking> findByCoachIdAndStatusInOrderByRequestedStartTimeAsc(UUID coachId, Collection<String> statuses);

    /**
     * C2: the coach-suspension sweep's candidate list — ids only, deliberately. Loading the entities
     * here would put each booking (at its current @Version) into the persistence context, and the
     * per-booking locked re-read ({@code findByIdForUpdate}) on an already-managed, since-updated row
     * fails with StaleObjectStateException instead of returning the fresh state — the exact race (a
     * parent cancelling mid-sweep) the re-read exists for. Each row is re-checked under its own lock.
     */
    @Query("SELECT b.id FROM Booking b WHERE b.coachId = :coachId AND b.status IN :statuses "
        + "ORDER BY b.requestedStartTime ASC")
    List<UUID> findIdsByCoachIdAndStatusIn(@Param("coachId") UUID coachId,
                                           @Param("statuses") Collection<String> statuses);

    // fix/booking-completion-integrity: also a request whose start time has passed — it can no longer be
    // accepted (BookingService.requireRequestStartNotPassed), so it must not hold the slot until
    // createdAt + expiry. Paged (BookingExpiryScheduler drains page 0 each tick; expired rows leave the
    // result), deterministic order.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'REQUESTED'
          AND (b.createdAt < :threshold OR b.requestedStartTime <= :now)
        ORDER BY b.createdAt ASC, b.id ASC
        """)
    List<Booking> findExpiredRequestedBookings(@Param("threshold") Instant threshold, @Param("now") Instant now,
                                               Pageable pageable);

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

    /**
     * fix/booking-request-consent (M2): the parent's own requests still awaiting this coach's answer — the
     * input to {@code BookingService.requireRequestQuota}. Replaces the never-called countInFlightBookings
     * (keyed on the player, so a parent with two children got two quotas, and it counted paid bookings that
     * hold no free slot). Coach-proposed bookings are excluded: the parent did not make them. Read under the
     * coach row lock on every create path, so two concurrent creates cannot both pass the cap.
     */
    @Query("""
        SELECT COUNT(b) FROM Booking b
        WHERE b.parentId = :parentId
          AND b.coachId = :coachId
          AND b.status = 'REQUESTED'
          AND b.proposedByCoach = false
        """)
    long countPendingParentRequests(@Param("parentId") Long parentId, @Param("coachId") UUID coachId);

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
    // that overclaimed safety here; code review 2026-10-17 corrected this paragraph a second time,
    // because skillars-deferred-154 AC4 below invalidated its original premise).
    //
    // That premise WAS: "ORDER BY b.playerId gives the caller's actually-owned playerId no special
    // preference -- the ownership check (ReviewSubmissionService's Java loop) runs strictly AFTER
    // this query's own truncation, so ordering cannot protect any specific row." That is no longer
    // true: as of AC4 the ownership predicate lives in THIS query's own WHERE clause (see below), so
    // it is applied BEFORE truncation, not after it by a Java loop. Every row that survives to be
    // truncated is already owned by the caller.
    //
    // What ORDER BY buys is therefore determinism, and only that: WITHOUT it, an unordered SELECT DISTINCT truncated by a
    // bare `limit` returns a Postgres-internal, plan-dependent, call-to-call-unstable subset --
    // the exact same input could pass on one call and false-deny on the next. WITH it, the same
    // input always truncates to the same 50 rows. The bound (50) is a generous insurance cap,
    // safely above any realistic distinct-player-per-author-per-coach count; a literal HQL
    // `limit` is used instead of Pageable to keep this method's 4-arg signature unchanged
    // (Pageable would break ReviewSubmissionServiceTest's existing mock stub at compile time).
    //
    // skillars-deferred-154 AC4: the ownership predicate is now pushed into THIS query via a join
    // to PlayerProfile's current parentId/userId, rather than relying on Booking's own
    // (b.parentId = :authorId OR b.playerId = :authorId) filter, which had two problems. First,
    // `b.playerId = :authorId` compared two unrelated id spaces (authorId is always a User id;
    // Booking.playerId is a PlayerProfile primary key) -- it could only ever "match" by numeric
    // coincidence, never by a real relationship, and is dropped entirely below. Second,
    // booking.bookings has no FK on player_id, so a row whose PlayerProfile no longer existed
    // could in principle occupy a cap slot -- currently unreachable (nothing in this codebase
    // deletes a PlayerProfile row), but the plain (inner) JOIN below closes that gap
    // defensively anyway, for free. Every row in the capped result is now correctly-owned by
    // construction: the previously-accepted residual risk above ("the owned playerId may have
    // been truncated if it sorts above the cap") no longer applies -- the one remaining residual
    // is a single author genuinely exceeding 50 distinct, currently-owned children with the same
    // coach. See ReviewSubmissionService.evaluateEligibility's own QUALIFYING_BOOKINGS_CAP log
    // line and its own comment block for the matching update there.
    //
    // fix/booking-integrity (B2): maturity is measured from when the booking became COMPLETED
    // (statusChangedAt, V178), not updatedAt -- any later write (a recap edit, GDPR note
    // anonymisation) bumped updatedAt and reset maturity. COALESCE keeps pre-V178 rows on updatedAt;
    // same rule as Booking.getStatusAnchor.
    @Query("""
        SELECT DISTINCT b.playerId AS playerId
        FROM Booking b
        JOIN PlayerProfile pp ON pp.id = b.playerId
        WHERE b.coachId = :coachId
          AND (pp.userId = :authorId OR pp.parentId = :authorId)
          AND b.status = 'COMPLETED'
          AND COALESCE(b.statusChangedAt, b.updatedAt) <= :maturedBefore
          AND COALESCE(b.statusChangedAt, b.updatedAt) > :sinceAfter
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

    // Parent-requested child erasure (V172): refused while the child still has a live booking.
    boolean existsByPlayerIdAndStatusIn(Long playerId, List<String> statuses);

    // fix/booking-integrity (B2): the booking-side half of the child-erasure race. A child with a
    // child-scoped ERASURE request that is still in flight must not be booked: PENDING/PROCESSING
    // are about to erase it, and FAILED is re-driven by GdprErasureService.retryFailedErasures.
    // Native SQL rather than a dependency on platform.admin (which already depends on this module) --
    // read only by BookingService.requirePlayerBookableUnderLock, under the player_profiles row lock
    // GdprRequestService.requestChildErasure also takes before it inserts the request row.
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM admin.gdpr_requests g
            WHERE g.player_id = :playerId
              AND g.request_type = 'ERASURE'
              AND g.status IN ('PENDING', 'PROCESSING', 'FAILED'))
        """, nativeQuery = true)
    boolean existsInFlightChildErasureRequest(@Param("playerId") Long playerId);

    // skillars-12-10 AC2: the READ-clock for the coach-access window — the latest booking END for the pair
    // over the given statuses (null = no relationship). A future end is returned as-is, which is what keeps
    // an in-progress session and the wrap-up-to-completion gap inside the window.
    @Query("""
        SELECT MAX(b.requestedEndTime)
        FROM Booking b
        WHERE b.coachId = :coachId
          AND b.playerId = :playerId
          AND b.status IN :statuses
        """)
    Instant findLatestEndTimeByCoachIdAndPlayerIdAndStatusIn(
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

    // skillars-12-1 AC1/Task 1.1: deliberately NOT CoachPlayerAuthorizationService's
    // ACTIVE_RELATIONSHIP_STATUSES (that answers an authorization question, this a roster-membership
    // one — see Task 1.2). recencyFloor is AC1's own 730-day bound.
    //
    // skillars-deferred-153 AC3: capped by Pageable (CoachRosterService passes
    // PageRequest.of(0, maxPlayers)) to bound the per-player fan-out that runs on this query's
    // result BEFORE scoring, not just the response after — a real academy coach's candidate set can
    // be 300+ players, i.e. hundreds of synchronous per-player queries on one GET. Two load-bearing
    // constraints: this method must keep its List<> return type (a Page<> would derive a COUNT query
    // over this GROUP BY, which Spring Data evaluates per-group, throwing
    // IncorrectResultSizeDataAccessException), and the ORDER BY must stay written into this @Query
    // text with an UNSORTED PageRequest — Spring Data appends any Pageable-carried Sort AFTER the
    // query's own ORDER BY, making a sorted PageRequest a silent no-op here. ORDER BY switched from
    // "deterministic tie-break only" (b.playerId) to "most-recently-active first, then deterministic
    // tie-break" (MAX(b.requestedStartTime) DESC, b.playerId ASC) — capping by most-recently-booked
    // before scoring (rather than by attentionScore after scoring, which the owner rejected as not
    // reducing DB load) means a long-dormant-but-genuinely-neglected player with an old
    // latestBookingTime can now be excluded from the roster in favor of a recently-booked but
    // otherwise unremarkable one, for a coach whose candidate set exceeds maxPlayers. The pre-existing
    // missing-player-profile filter (CoachRosterService.getRoster) runs AFTER this query, so a
    // filtered-out row still consumes one of the maxPlayers slots — a minor, latent shrinkage of the
    // effective cap; not worth a design change given how rare a missing profile is, but worth
    // knowing if the cap is ever tuned against observed roster sizes.
    // skillars-deferred-153 code review (2026-10-17), OWNER DECISION — the cap's ranking key is
    // clamped to :now, so it matches how CoachRosterService.getRoster already treats the same value.
    // The projection still returns the raw, unclamped MAX(b.requestedStartTime), because getRoster
    // needs the future-dated value to derive hasUpcomingBooking.
    //
    // Be precise about what this does and does not do. ROSTER_MEMBERSHIP_STATUSES deliberately
    // includes ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING, all of which can be future-dated. Before
    // the clamp, distance INTO THE FUTURE bought a slot: a booking three weeks out outranked one three
    // days out, which is meaningless as an activity signal. The clamp removes that — every future-dated
    // player now ties at :now and the b.playerId tie-break decides.
    //
    // It does NOT demote a future-only player below a player with genuine recent past activity: their
    // clamped key is exactly :now, which is still greater than any past timestamp. That is deliberate
    // and consistent, not an oversight — getRoster's own scoring does the identical thing
    // (`latestPastBookingTime = hasUpcomingBooking ? now : latestBookingTime`), so a future-only player
    // already scores as maximally recent there too. Making the cap demote them would put the cap and
    // the scorer back into disagreement, in the opposite direction. Ranking by true last activity is
    // not available here at all: it merges lastSessionDate, which is resolved per player in Java after
    // this query returns, and computing it in SQL is the aggregate rewrite the owner rejected.
    //
    // CASE rather than LEAST: CASE is standard JPQL, LEAST is Hibernate-specific. Roster MEMBERSHIP is
    // unchanged — a player whose only booking is future-dated still appears on the roster.
    @Query("""
        SELECT b.playerId AS playerId, MAX(b.requestedStartTime) AS latestBookingTime,
               MAX(b.requestedEndTime) AS latestBookingEndTime
        FROM Booking b
        WHERE b.coachId = :coachId
          AND b.status IN :statuses
          AND b.requestedStartTime >= :recencyFloor
        GROUP BY b.playerId
        ORDER BY MAX(CASE WHEN b.requestedStartTime > :now THEN :now ELSE b.requestedStartTime END) DESC,
                 b.playerId ASC
        """)
    List<RosterPlayerActivityProjection> findRosterPlayerActivityByCoachIdAndStatusIn(
        @Param("coachId") UUID coachId,
        @Param("now") Instant now,
        @Param("statuses") List<String> statuses,
        @Param("recencyFloor") Instant recencyFloor,
        Pageable pageable);

    // skillars-deferred-153 AC1: a coach who Quick-Completes a session and then abandons
    // WrapUpSequence.vue before its mandatory step-5 submit leaves the booking in
    // COMPLETED_PENDING_CONFIRMATION with no SessionCompletionData row at all (the only existing
    // sweeper, QuickCompleteTimeoutService, matches rows that already exist, so it can never pick
    // this case up). updatedAt, not createdAt: Booking's own @PreUpdate means this measures time
    // since the booking last changed, which — since nothing else writes to a row-less
    // COMPLETED_PENDING_CONFIRMATION booking — is exactly the time since Quick Complete was tapped.
    @Query("""
        SELECT b FROM Booking b
        WHERE b.status = 'COMPLETED_PENDING_CONFIRMATION'
          AND b.updatedAt < :cutoff
          AND NOT EXISTS (
              SELECT s FROM SessionCompletionData s WHERE s.bookingId = b.id
          )
        """)
    // skillars-deferred-153 code review (2026-10-17): capped with a Pageable. Each returned row costs
    // an INSERT, a pessimistically-locked state transition and a payout-outbox enqueue, all inside
    // StuckCompletionSweeperService's lockAtMostFor = PT15M. Uncapped, the first run after deploy
    // drains the entire historical backlog of abandoned wrap-ups in one pass; if that outlives the
    // ShedLock lease, a second instance starts from a stale list and every re-processed row races the
    // first. The sweeper reruns every 5 minutes, so a bounded batch simply drains the backlog across
    // several ticks instead of one. (The sibling findPendingQuickCompletes is uncapped but carries an
    // explicit sizing rationale and does strictly less work per row.)
    List<Booking> findStuckCompletionsWithNoSessionData(@Param("cutoff") Instant cutoff, Pageable pageable);

    // fix/booking-completion-integrity: UnresolvedSessionSweeperService — live sessions (IN_PROGRESS /
    // PAUSED) whose scheduled end is older than the cutoff. Ids only, like findIdsByCoachIdAndStatusIn:
    // each row is re-checked under its own lock by BookingService.transition.
    @Query("""
        SELECT b.id FROM Booking b
        WHERE b.status IN ('IN_PROGRESS', 'PAUSED') AND b.requestedEndTime < :cutoff
        ORDER BY b.requestedEndTime ASC, b.id ASC
        """)
    List<UUID> findLiveSessionIdsEndedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    // fix/booking-completion-integrity: UPCOMING (never started) sessions whose scheduled end is older than
    // the cutoff and that have not been alerted yet — captured money with nothing settled.
    @Query("""
        SELECT b.id FROM Booking b
        WHERE b.status = 'UPCOMING' AND b.requestedEndTime < :cutoff AND b.unresolvedAlertedAt IS NULL
        ORDER BY b.requestedEndTime ASC, b.id ASC
        """)
    List<UUID> findUnalertedNeverStartedIdsEndedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    // skillars-12-7 AC8: COMPLETED bookings of this coach, inside the recap edit window, whose
    // session_completion_data row carries no recap text, and whose player has NOT been erased (player
    // erasure also nulls both recap columns, which would otherwise make every erased player's session
    // nag forever -- and every "Add recap" is rejected for them). Driven from Booking because
    // session_completion_data has no coach_id index (only idx_scd_booking_id). `cutoff` = now - window,
    // since "requestedEndTime + window > now" is "requestedEndTime > now - window". Deterministic order.
    // skillars-deferred-162 AC6.3: a recap locked by a dispute is not nudged either (it cannot be added). The NOT
    // EXISTS is a deliberate cross-module read of admin's Dispute entity by name only (no Java import, so booking
    // still does not depend on admin); its predicate must stay identical to
    // DisputeRepository.existsRecapLockingDispute, which BookingDisputeLookup serves to recapEditability.
    @Query("""
        SELECT b FROM Booking b, SessionCompletionData s, PlayerProfile p
        WHERE s.bookingId = b.id AND p.id = b.playerId
          AND b.coachId = :coachId AND b.status = 'COMPLETED'
          AND s.whatWeDid IS NULL AND s.whatsNext IS NULL
          AND p.developmentDataErasedAt IS NULL
          AND b.requestedEndTime > :cutoff
          AND NOT EXISTS (SELECT 1 FROM Dispute d WHERE d.bookingId = b.id
              AND (d.status NOT IN ('RESOLVED', 'DISMISSED')
                   OR (d.status = 'RESOLVED' AND COALESCE(d.resolution, '') <> 'NO_ACTION')))
        ORDER BY b.requestedEndTime DESC, b.id DESC
        """)
    List<Booking> findBookingsMissingRecap(@Param("coachId") UUID coachId, @Param("cutoff") Instant cutoff,
                                           Pageable pageable);

    @Query("""
        SELECT COUNT(b) FROM Booking b, SessionCompletionData s, PlayerProfile p
        WHERE s.bookingId = b.id AND p.id = b.playerId
          AND b.coachId = :coachId AND b.status = 'COMPLETED'
          AND s.whatWeDid IS NULL AND s.whatsNext IS NULL
          AND p.developmentDataErasedAt IS NULL
          AND b.requestedEndTime > :cutoff
          AND NOT EXISTS (SELECT 1 FROM Dispute d WHERE d.bookingId = b.id
              AND (d.status NOT IN ('RESOLVED', 'DISMISSED')
                   OR (d.status = 'RESOLVED' AND COALESCE(d.resolution, '') <> 'NO_ACTION')))
        """)
    long countBookingsMissingRecap(@Param("coachId") UUID coachId, @Param("cutoff") Instant cutoff);

    // skillars-deferred-156 AC1: GdprErasureService injected this repository with zero call sites
    // until now — notes/playerRequest are player/parent-authored free text (coachPlanNote, added by
    // skillars-12-6, is coach-authored but describes the player), so a single playerId-keyed method covers both the self-registered-PLAYER
    // erasure path and the per-child path inside PARENT erasure. Since skillars-12-6 there IS a
    // coach-keyed half as well — anonymizeCoachPlanNoteByCoachId below, mirroring
    // SessionCompletionDataRepository's anonymizeByPlayerId/anonymizeByCoachId pair. The explicit `b.version = b.version + 1` (the codebase convention, see
    // RefreshTokenRepository/VideoRepository, and what NativeModifyingVersionAuditTest enforces —
    // it does not recognise Hibernate's equivalent "UPDATE VERSIONED") bumps Booking's own
    // @Version column for every row this touches — Booking is not @DynamicUpdate, so a booking
    // loaded concurrently (e.g. setPlayerRequest/save, setCancelReason/save) would otherwise rewrite every column,
    // including these two, from its stale in-memory snapshot and silently resurrect just-erased
    // text with no optimistic-lock failure at all. The bump makes that race surface as the same
    // OptimisticLockingFailureException/CONCURRENT_MODIFICATION path those writers already handle.
    // skillars-12-6 AC5: also nulls coachPlanNote — the coach authored it but it describes the player,
    // so player erasure scrubs it too (the coach-keyed counterpart is anonymizeCoachPlanNoteByCoachId).
    @Modifying
    @Query("UPDATE Booking b SET b.notes = NULL, b.playerRequest = NULL, b.coachPlanNote = NULL, "
        + "b.version = b.version + 1 WHERE b.playerId = :playerId")
    void anonymizeNotesAndPlayerRequestByPlayerId(@Param("playerId") Long playerId);

    // skillars-12-6 AC5: coach erasure — the plan note is coach-authored free text. The version bump
    // is mandatory for the same stale-snapshot-resurrection reason documented above.
    @Modifying
    @Query("UPDATE Booking b SET b.coachPlanNote = NULL, b.version = b.version + 1 "
        + "WHERE b.coachId = :coachId AND b.coachPlanNote IS NOT NULL")
    void anonymizeCoachPlanNoteByCoachId(@Param("coachId") UUID coachId);
}
