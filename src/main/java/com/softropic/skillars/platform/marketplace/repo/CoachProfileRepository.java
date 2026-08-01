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

    // Bounded lock wait (review patch): without this, contention on a popular coach's row blocks
    // the requesting thread indefinitely instead of failing cleanly — see ApiAdvice's
    // PessimisticLockingFailureException handler for the resulting 409 mapping.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
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
}
