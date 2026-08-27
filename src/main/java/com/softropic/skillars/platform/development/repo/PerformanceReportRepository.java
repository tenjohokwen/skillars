package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PerformanceReportRepository extends JpaRepository<PerformanceReport, UUID> {
    // Used by GDPR erasure, which must clean up every report regardless of status — callers there
    // must null-check getStorageKey() since a PENDING_UPLOAD/UPLOAD_FAILED row may not have one yet.
    List<PerformanceReport> findByPlayerIdOrderByGeneratedAtDesc(Long playerId);

    // READY-only: PENDING_UPLOAD/UPLOAD_FAILED reports must never be visible to listReports (AC2) —
    // their storage_key is either null or points at whatever the failed upload left behind.
    List<PerformanceReport> findByPlayerIdAndStatusOrderByGeneratedAtDesc(Long playerId, ReportStatus status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PerformanceReport p WHERE p.playerId = :playerId")
    int deleteAllByPlayerId(@org.springframework.data.repository.query.Param("playerId") Long playerId);

    @Modifying
    @Query("UPDATE PerformanceReport p SET p.status = :status, p.storageKey = :storageKey WHERE p.id = :id")
    int updateStatusAndStorageKey(@Param("id") UUID id, @Param("status") ReportStatus status,
                                   @Param("storageKey") String storageKey);
}
