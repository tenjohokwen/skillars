package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CoachBrandingRepository extends JpaRepository<CoachBranding, UUID> {

    /**
     * GDPR erasure: deletes the coach's branding row and returns the {@code logo_key} it held, in one
     * atomic statement — the key returned is exactly the one removed, even if a concurrent
     * {@code saveBranding} changed it after the caller last looked. A non-{@code @Modifying} native
     * query on purpose: {@code DELETE ... RETURNING} produces a result set, which JDBC's
     * {@code executeUpdate} (the {@code @Modifying} path) rejects. Empty list when there is no row;
     * a single null element when the row had no logo. {@code @Transactional} (read-write) overrides
     * the read-only default Spring Data applies to query methods when no transaction is active.
     */
    @Transactional
    @Query(value = "DELETE FROM development.coach_branding WHERE coach_id = :coachId RETURNING logo_key",
           nativeQuery = true)
    List<String> deleteByCoachIdReturningLogoKey(@Param("coachId") UUID coachId);
}
