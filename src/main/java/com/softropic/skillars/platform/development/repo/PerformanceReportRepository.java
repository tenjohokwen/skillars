package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PerformanceReportRepository extends JpaRepository<PerformanceReport, UUID> {
    // skillars-deferred-132 AC2 Fix 8: GDPR erasure only ever needed the storage_key column (to enqueue
    // for blob deletion), not the full entity — this projection replaces the former
    // findByPlayerIdOrderByGeneratedAtDesc(playerId).forEach(...) hydration. The WHERE clause's own
    // null-check replaces the loop-body null-check a PENDING_UPLOAD/UPLOAD_FAILED row previously needed
    // (no storage_key yet) — order was never load-bearing for that use, only for the removed method's
    // listing shape.
    @Query("SELECT p.storageKey FROM PerformanceReport p WHERE p.playerId = :playerId AND p.storageKey IS NOT NULL")
    List<String> findStorageKeysByPlayerId(@Param("playerId") Long playerId);

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
