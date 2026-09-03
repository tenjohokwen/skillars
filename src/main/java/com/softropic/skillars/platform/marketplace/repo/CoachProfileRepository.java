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

    boolean existsByUserId(Long userId);

    // skillars-deferred-62: jakarta.persistence.lock.timeout has no effect on Postgres for any finite
    // value — Hibernate's PostgreSQLDialect only special-cases NO_WAIT (0) and SKIP_LOCKED (-2). "0"
    // here means NO_WAIT: contention fails immediately with PessimisticLockingFailureException rather
    // than blocking. Every one of this method's 7 call sites wraps it in PessimisticLockRetryer,
    // which retries that failure from a JDBC savepoint with a short backoff (~3.2s budget across 8
    // attempts) so a brief, legitimate overlap between two requests still succeeds — contention that
    // outlasts the budget surfaces as ApiAdvice's PessimisticLockingFailureException handler's 409.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT c FROM CoachProfile c WHERE c.id = :id")
    Optional<CoachProfile> findByIdForUpdate(@Param("id") UUID id);

    // Language filter via native query — PostgreSQL array ANY() is not portable in JPA Criteria API
    // City constraint added to avoid a global scan of all active coaches (P1)
    @Query(value = "SELECT id FROM marketplace.coach_profiles " +
                   "WHERE status = 'ACTIVE' AND lower(city) = lower(:city) AND lower(:lang) = ANY(languages)",
           nativeQuery = true)
    List<UUID> findIdsByLanguage(@Param("lang") String lang, @Param("city") String city);

    // Accurate totalElements for language-filtered responses (P8)
    @Query(value = "SELECT COUNT(*) FROM marketplace.coach_profiles " +
                   "WHERE status = 'ACTIVE' AND lower(city) = lower(:city) AND lower(:lang) = ANY(languages)",
           nativeQuery = true)
    long countByLanguageAndCity(@Param("lang") String lang, @Param("city") String city);

    @Query("SELECT p FROM CoachProfile p WHERE p.status IN :statuses ORDER BY p.statusChangedAt ASC NULLS LAST")
    Page<CoachProfile> findByStatusInOrderByStatusChangedAtAsc(
        @Param("statuses") List<CoachProfileStatus> statuses, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CoachProfile p SET p.averageRating = :avgRating, p.reviewCount = :reviewCount WHERE p.id = :coachId")
    void updateRatingAggregate(@Param("coachId") UUID coachId,
                               @Param("avgRating") Double avgRating,
                               @Param("reviewCount") int reviewCount);

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
