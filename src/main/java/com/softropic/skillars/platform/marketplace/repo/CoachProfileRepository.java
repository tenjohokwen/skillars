package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}
