package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CoachSafeguardingCheckRepository extends JpaRepository<CoachSafeguardingCheck, UUID> {

    /** {@code [coachId, expiresAt]} for the coaches in {@code ids} whose check is still valid at {@code now}. */
    @Query("SELECT c.coachId, c.expiresAt FROM CoachSafeguardingCheck c "
           + "WHERE c.coachId IN :ids AND c.expiresAt > :now")
    List<Object[]> findValidExpiryByCoachIdIn(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /**
     * Valid checks that expire within the reminder window and have not been reminded yet. Oldest expiry first,
     * so a backlog is worked off in urgency order.
     */
    @Query("SELECT c FROM CoachSafeguardingCheck c WHERE c.expiresAt > :now AND c.expiresAt <= :horizon "
           + "AND c.reminderSentAt IS NULL ORDER BY c.expiresAt ASC")
    List<CoachSafeguardingCheck> findDueForReminder(@Param("now") Instant now, @Param("horizon") Instant horizon,
                                                    Pageable pageable);

    /** Staff view of the records currently on file (valid first-expiring), for revoke. */
    @Query("SELECT c FROM CoachSafeguardingCheck c WHERE c.expiresAt > :now ORDER BY c.expiresAt ASC")
    Page<CoachSafeguardingCheck> findValid(@Param("now") Instant now, Pageable pageable);

    /**
     * GDPR erasure: removes the coach's record. Native bulk DELETE, run after the request delete in the same
     * transaction (see {@code GdprErasureService}); nothing to return, the record holds no blob.
     */
    @Modifying
    @Query(value = "DELETE FROM marketplace.coach_safeguarding_checks WHERE coach_id = :coachId", nativeQuery = true)
    int deleteByCoachIdNative(@Param("coachId") UUID coachId);
}
