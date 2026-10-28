package com.softropic.skillars.platform.marketplace.repo;

import com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachProfileRepository
        extends JpaRepository<CoachProfile, UUID>,
                JpaSpecificationExecutor<CoachProfile> {

    Optional<CoachProfile> findByUserId(Long userId);

    /**
     * Scalar ID lookup that does not put a CoachProfile into the persistence context. A caller
     * that will then take findByIdForUpdate needs this, because Hibernate returns an already
     * managed instance as-is (it is not refreshed), so an earlier unlocked entity load would
     * leave the locked caller working on a pre-lock snapshot.
     */
    @Query("SELECT p.id FROM CoachProfile p WHERE p.userId = :userId")
    Optional<UUID> findIdByUserId(@Param("userId") Long userId);

    boolean existsByUserId(Long userId);

    // skillars-deferred-62: jakarta.persistence.lock.timeout has no effect on Postgres for any finite
    // value — Hibernate's PostgreSQLDialect only special-cases NO_WAIT (0) and SKIP_LOCKED (-2). "0"
    // here means NO_WAIT: contention fails immediately with PessimisticLockingFailureException rather
    // than blocking. Every one of this method's 14 call sites wraps it in PessimisticLockRetryer,
    // which retries that failure from a JDBC savepoint with a short backoff (~3.2s budget across 8
    // attempts) so a brief, legitimate overlap between two requests still succeeds — contention that
    // outlasts the budget surfaces as ApiAdvice's PessimisticLockingFailureException handler's 409.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT c FROM CoachProfile c WHERE c.id = :id")
    Optional<CoachProfile> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Language filter via native query — PostgreSQL array ANY() is not expressible through the JPA
     * Criteria API for the hypersistence {@code ListArrayType} column. {@code CoachSearchService} feeds
     * these ids into the paged Specification query as an {@code id IN (...)} predicate, so pagination
     * and totals come from the DB. City-scoped and restricted to the searchable statuses so the bind
     * list stays bounded by the city's live coaches (not every historical DRAFT/DEACTIVATED profile).
     * The status list MUST mirror {@code CoachSearchSpecification.isActive()} (ACTIVE and REDUCED); a
     * hard-coded {@code status = 'ACTIVE'} here used to drop REDUCED coaches whenever a language was given.
     */
    @Query(value = "SELECT id FROM marketplace.coach_profiles " +
                   "WHERE status IN ('ACTIVE', 'REDUCED') AND lower(city) = lower(:city) AND lower(:lang) = ANY(languages)",
           nativeQuery = true)
    List<UUID> findIdsByLanguage(@Param("lang") String lang, @Param("city") String city);

    @Query("SELECT p FROM CoachProfile p WHERE p.status IN :statuses ORDER BY p.statusChangedAt ASC NULLS LAST")
    Page<CoachProfile> findByStatusInOrderByStatusChangedAtAsc(
        @Param("statuses") List<CoachProfileStatus> statuses, Pageable pageable);

    /**
     * Admin console D6: the sole post-insert writer of {@code verification_tier} (the column is
     * {@code updatable = false} on the entity — see {@code CoachProfile.verificationTier}). Callers
     * take {@link #findByIdForUpdate} first so concurrent tier changes serialise.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE CoachProfile p SET p.verificationTier = :tier WHERE p.id = :coachId")
    int updateVerificationTier(@Param("coachId") UUID coachId, @Param("tier") String tier);

    /**
     * fix/content-consistency-and-lost-updates: targeted single-column write for the two photo-key
     * writers ({@code CoachProfileService.saveStep5} and {@code CoachProfilePhotoEventListener}).
     * {@code CoachProfile} has no {@code @Version}/{@code @DynamicUpdate}, so their former
     * load-then-{@code save()} rewrote every column from a possibly stale snapshot and could silently
     * revert a concurrent status write (e.g. {@code suspendCoach}). This UPDATE touches only
     * {@code photo_url}, so it cannot. Not {@code clearAutomatically}: the listener runs inside the
     * file-storage confirm transaction and must not detach that caller's entities; callers do not read
     * {@code photoUrl} back from a managed instance after calling this.
     *
     * @return rows updated (0 when the user has no coach profile)
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE CoachProfile p SET p.photoUrl = :photoUrl WHERE p.userId = :userId")
    int updatePhotoUrlByUserId(@Param("userId") Long userId, @Param("photoUrl") String photoUrl);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CoachProfile p SET p.averageRating = :avgRating, p.reviewCount = :reviewCount WHERE p.id = :coachId")
    void updateRatingAggregate(@Param("coachId") UUID coachId,
                               @Param("avgRating") Double avgRating,
                               @Param("reviewCount") int reviewCount);

    // skillars-12-7 code review (Decision 1(a)): the coach-side recap erasure tombstone (V166). Native and
    // unmapped on purpose -- see V166's header: the entity's own flush would otherwise null it again.
    // COALESCE keeps the first erasure time when the retry scheduler re-runs the scrub.
    @Modifying
    @Query(value = "UPDATE marketplace.coach_profiles SET recap_data_erased_at = COALESCE(recap_data_erased_at, now()) "
                   + "WHERE id = :coachId", nativeQuery = true)
    void markRecapDataErased(@Param("coachId") UUID coachId);

    // A scalar query, never the cached entity: callers read it after taking a row lock and need the
    // committed value as of now, not the snapshot loaded at the start of the request.
    @Query(value = "SELECT recap_data_erased_at IS NOT NULL FROM marketplace.coach_profiles WHERE id = :coachId",
           nativeQuery = true)
    boolean isRecapDataErased(@Param("coachId") UUID coachId);

    /**
     * The public profile and its pricing row in one round trip (skillars-deferred-91 AC11 / code
     * review D9). {@code coach_pricing} is one-to-one on {@code coach_id}, so an ad-hoc
     * {@code LEFT JOIN … ON} adds no rows and costs no cartesian product — it simply removes the
     * separate {@code coachPricingRepository.findByCoachId} round trip. LEFT, not INNER: a coach
     * without a pricing row must still render.
     *
     * @return a single {@code [CoachProfile, CoachPricing-or-null]} pair, or empty if no such coach
     */
    @Query("SELECT p, pr FROM CoachProfile p LEFT JOIN CoachPricing pr ON pr.coachId = p.id WHERE p.id = :coachId")
    List<Object[]> findByIdWithPricing(@Param("coachId") UUID coachId);
}
