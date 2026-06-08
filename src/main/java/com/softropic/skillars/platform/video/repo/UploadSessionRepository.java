package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    Optional<UploadSession> findFirstByVideoIdOrderByCreatedAtDesc(UUID videoId);

    long countByStatus(UploadSessionStatus status);

    List<UploadSession> findAllByVideoIdOrderByCreatedAtDesc(UUID videoId);

    @Query(value = """
        SELECT * FROM main.upload_sessions
        WHERE status = 'PENDING' AND expires_at < NOW()
        ORDER BY expires_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UploadSession> findExpiredPendingForUpdate(@Param("limit") int limit);
}
