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

    // skillars-deferred-155 AC9: mirrors findStorageKeysByPlayerId above, keyed on coachId instead —
    // this story's own AC1/AC2 put the coach's verbatim recap text into generated PDFs, enlarging
    // the pre-existing gap that performance_reports was purged only on the PLAYER erasure path,
    // never the coach's. coachId is a plain indexed column here (unlike the JSONB metadata filters
    // above) so a derived query is fine.
    @Query("SELECT p.storageKey FROM PerformanceReport p WHERE p.coachId = :coachId AND p.storageKey IS NOT NULL")
    List<String> findStorageKeysByCoachId(@Param("coachId") UUID coachId);

    // skillars-deferred-155 code review, Decision 2(a): scrub-and-keep, NOT delete. performance_reports
    // is a JOINT artifact — coach_id NOT NULL *and* player_id NOT NULL (V138__baseline_schema.sql:326-327)
    // — so deleting by coachId destroys the player's own development record because a third party asked
    // for erasure. The two sibling scrubs in the same erasure block (SessionCompletionDataRepository
    // .anonymizeByCoachId, PlayerTimelineRepository.anonymizeMetadataByCoachId) both scrub-and-keep; the
    // original implementation here borrowed the PLAYER branch's delete semantics, where deleting is
    // correct only because the data subject owns the row.
    //
    // next_steps is NOT NULL, so it takes a placeholder rather than NULL — the same convention
    // GdprErasureService already uses for the "Deleted"/"User"/"deleted.<id>@erased.io" writes on
    // main.user. storage_key is nulled because the PDF itself embeds the coach's verbatim recap text
    // (AC1/AC2's "Session Notes" section) and cannot be scrubbed in place; the caller enqueues the blob
    // for deletion. A null storage_key is already a representable read state — ReportGenerationService
    // .listReports leaves `url` null for PENDING_UPLOAD rows via the same path. `status` is deliberately
    // left alone: ReportStatus has no "erased" value and adding one would need a Postgres enum migration.
    //
    // nativeQuery because next_steps is mapped `updatable = false` (PerformanceReport.java:42), which
    // makes a JPQL bulk UPDATE on it ambiguous; native SQL bypasses the persister entirely. Idempotent
    // on the retry path: findStorageKeysByCoachId above already filters `storage_key IS NOT NULL`, so a
    // re-run enqueues nothing a second time.
    @Modifying
    @Query(value = "UPDATE development.performance_reports "
        + "SET next_steps = :placeholder, storage_key = NULL "
        + "WHERE coach_id = :coachId", nativeQuery = true)
    int anonymizeByCoachId(@Param("coachId") UUID coachId, @Param("placeholder") String placeholder);
}
