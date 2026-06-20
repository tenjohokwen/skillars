package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VideoQuotaReservationRepository extends JpaRepository<VideoQuotaReservation, UUID> {

    @Query(value = """
        SELECT * FROM main.video_quota_reservations
        WHERE status = 'ACTIVE' AND expires_at < NOW()
        ORDER BY expires_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<VideoQuotaReservation> findExpiredReservationsForUpdate(@Param("limit") int limit);

    @Query(value = "SELECT COALESCE(SUM(reserved_bytes), 0) FROM main.video_quota_reservations WHERE user_id = :userId AND status = 'ACTIVE'", nativeQuery = true)
    long sumActiveReservedBytes(@Param("userId") String userId);
}
