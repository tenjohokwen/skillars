package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface VideoQuotaRepository extends JpaRepository<VideoQuota, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM VideoQuota q WHERE q.userId = :userId")
    Optional<VideoQuota> findByIdForUpdate(@Param("userId") String userId);

    @Modifying
    @Transactional
    @Query("UPDATE VideoQuota vq SET vq.storageUsedBytes = 0, vq.bandwidthUsedBytes = 0 WHERE vq.userId = :ownerId")
    void resetBytesForOwner(@Param("ownerId") String ownerId);
}
