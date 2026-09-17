package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.OperationalState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface VideoRepository extends JpaRepository<Video, UUID> {

    Optional<Video> findByProviderAssetId(String providerAssetId);

    // Deferred-64 AC3: mirrors CoachProfileRepository.findByIdForUpdate exactly — NO_WAIT (0) is
    // the only lock.timeout value Hibernate's PostgreSQLDialect special-cases, so every call site
    // wraps this in PessimisticLockRetryer, which retries the resulting failure from a JDBC
    // savepoint with a short backoff.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT v FROM Video v WHERE v.id = :id")
    Optional<Video> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE operational_state IN ('UPLOADING', 'PROCESSING')
        ORDER BY updated_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findNonTerminalForUpdate(@Param("limit") int limit);

    @Query("SELECT v FROM Video v WHERE v.id IN :ids AND v.operationalState = com.softropic.skillars.platform.video.contract.OperationalState.READY AND v.accessState = com.softropic.skillars.platform.video.contract.AccessState.ACTIVE")
    List<Video> findReadyAndActiveByIds(@Param("ids") List<UUID> ids);

    // skillars-deferred-115 AC1: no longer @Transactional here — the caller
    // (ModerationSlaMonitorService.detectSlaViolations()) now wraps this call in its own short
    // TransactionTemplate, mirroring this same interface's findNonTerminalForUpdate (no explicit
    // @Transactional either) and VideoWebhookEventRepository.findPendingForUpdate (used the same way
    // by WebhookEventProcessorScheduler) — code review 2026-09-16: the latter is a different
    // repository interface, clarified here since a reader grepping only this file wouldn't find it.
    // So the FOR UPDATE locks below release as soon as the load returns rather than being held
    // (implicitly, via a method-level @Transactional that used to wrap the whole scheduler body) for
    // the entire per-video processing loop.
    @Query(value = """
        SELECT * FROM main.videos
        WHERE operational_state = 'SCANNING'
          AND scanning_started_at < :threshold
          AND (moderation_lock_until IS NULL OR moderation_lock_until < :now)
        ORDER BY scanning_started_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findScanningOlderThan(@Param("threshold") Instant threshold, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE access_state = 'BLOCKED'
          AND lifecycle_locked_at < :threshold
        ORDER BY lifecycle_locked_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findBlockedExceedingThreshold(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);

    // skillars-deferred-117 AC2: operational_state = 'READY' added. markPurged() sets
    // operationalState=DELETED on a successful purge but never touches accessState, which stays
    // ARCHIVED forever. Without this predicate, every already-purged video permanently re-qualifies
    // for this query — ORDER BY archived_at ASC sorts the oldest purged videos first, and the hard
    // LIMIT :batchSize means their accumulation eventually starves genuinely-due videos out of every
    // batch slot entirely. READY mirrors the exact precondition markPurged() itself enforces
    // (VideoLifecycleService.markPurged), so a video this query returns can never fail that check.
    @Query(value = """
        SELECT * FROM main.videos
        WHERE access_state = 'ARCHIVED'
          AND operational_state = 'READY'
          AND archived_at < :threshold
        ORDER BY archived_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findArchivedExceedingThreshold(@Param("threshold") Instant threshold, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE owner_id = :ownerId
          AND operational_state = 'READY'
          AND access_state = 'ACTIVE'
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findActiveReadyByOwner(@Param("ownerId") String ownerId, @Param("batchSize") int batchSize);

    @Query(value = """
        SELECT * FROM main.videos
        WHERE owner_id = :ownerId
          AND operational_state = 'READY'
          AND access_state = 'BLOCKED'
        ORDER BY lifecycle_locked_at ASC NULLS LAST
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Video> findBlockedReadyByOwner(@Param("ownerId") String ownerId, @Param("batchSize") int batchSize);

    Page<Video> findByOwnerIdAndOperationalStateNot(String ownerId, OperationalState state, Pageable pageable);

    List<Video> findByOwnerIdAndOperationalStateNotInOrderByCreatedAtDesc(
        String ownerId, java.util.Collection<OperationalState> excludedStates);

    /**
     * skillars-deferred-100 AC3: this is a bulk {@code UPDATE} against {@code main.videos}, whose
     * entity {@link Video} carries {@code @Version}. A bulk update bypasses optimistic locking, so
     * the {@code version = version + 1} is added by hand — {@code VideoLifecycleService}'s
     * per-row writers ({@code blockForSubscriptionExpiry}, {@code archiveForLifecycle},
     * {@code resetLifecycleClock}) load a managed {@link Video} and {@code save()} it, and one of
     * those running concurrently with this reset for the same owner would otherwise re-persist a
     * stale {@code lifecycleLockedAt} without ever seeing the bulk change (matching version). The
     * bump turns that silent overwrite into a loud {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * which the only caller ({@code VideoSubscriptionLifecycleListener.processAndSaveEntry}) already
     * catches, retries and dead-letters.
     */
    @Modifying
    @Query("UPDATE Video v SET v.lifecycleLockedAt = CURRENT_TIMESTAMP, v.version = v.version + 1 "
        + "WHERE v.ownerId = :ownerId AND v.accessState = com.softropic.skillars.platform.video.contract.AccessState.BLOCKED")
    void resetLifecycleLockedAt(@Param("ownerId") String ownerId);
}
