package com.softropic.skillars.platform.security.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, Long> {

    // skillars-deferred-127 code review (2026-09-21): ORDER BY id — deterministic iteration order for
    // GdprErasureService.eraseParentChildren's PARENT-branch loop, each iteration of which takes a
    // player_profiles pessimistic lock (see deletePlayerDevelopmentData). NOWAIT means a lock
    // conflict fails fast rather than waits, so unordered iteration cannot itself produce a
    // circular-wait deadlock (40P01) — but it does make which iteration (if any) exhausts its retry
    // budget nondeterministic, heap-order-dependent, and undocumented. Zero-cost fix.
    //
    // skillars-deferred-128 AC1/AC7 Task 5: as of AC1, each child's lock is acquired and released in
    // its OWN inner (REQUIRES_NEW) transaction, one at a time — the loop no longer holds every
    // child's lock simultaneously the way it did when all of them accumulated on erase()'s single
    // outer transaction. The retry-budget-determinism half of this rationale is unchanged (still only
    // one lock outstanding at a time, so still only one attempt's outcome to make deterministic); the
    // "deterministic ACQUISITION order across N simultaneously-held locks" framing above is now
    // moot — reworded here, not deleted, since a stable iteration order is still a genuine (if
    // smaller) benefit: it keeps which specific child processes first, and therefore which child's
    // log lines/AdminAlert-processed-count appear first on a deadline-exceeded run, reproducible.
    List<PlayerProfile> findByParentIdOrderByIdAsc(Long parentId);

    /** Always use this instead of findById — parentId enforces family isolation. */
    Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId);

    boolean existsByIdAndParentId(Long id, Long parentId);

    /**
     * Self-registered adult player's own-profile isolation — the {@code existsByIdAndParentId}
     * counterpart for a profile whose {@code parentId} is null (chk_pp_owner). skillars-deferred-147:
     * added for {@link com.softropic.skillars.platform.security.service.PlayerOwnershipGuard}, which
     * previously only checked parent ownership and unconditionally denied a self-registered player
     * access to their own development/session/payment resources.
     */
    boolean existsByIdAndUserId(Long id, Long userId);

    /** For self-registered adult players — userId is their own account, no parent involved. */
    Optional<PlayerProfile> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /**
     * Single-query JOIN avoids the TOCTOU gap between separately fetching parentId then looking up
     * the parent's user row, and returns no row (rather than throwing) for self-registered adult
     * players whose parentId is null — see chk_pp_owner in V84.
     */
    @Query(nativeQuery = true, value = """
        SELECT u.email FROM main."user" u
        JOIN main.player_profiles p ON u.id = p.parent_id
        WHERE p.id = :playerId
        """)
    Optional<String> findParentEmailByPlayerId(@Param("playerId") Long playerId);

    /**
     * V184 (video subject link): returns the id iff the profile exists and its data has NOT been
     * GDPR-erased, holding a FOR SHARE row lock for the caller's transaction. A concurrent erasure takes
     * this row with {@link #findByIdForUpdate} (NOWAIT + bounded retry), so either it committed first —
     * and this returns empty — or its lock attempt fails while the caller's short insert transaction
     * holds the share lock: it retries (and then sees the committed row), or exhausts its retries, marks
     * the request FAILED and is re-driven by {@code GdprErasureService.retryFailedErasures}, whose
     * AFTER_COMMIT purge then sees the row. Must run inside a transaction.
     */
    @Query(nativeQuery = true, value = """
        SELECT p.id FROM main.player_profiles p
        WHERE p.id = :id AND p.development_data_erased_at IS NULL
        FOR SHARE
        """)
    Optional<Long> lockLiveProfileForShare(@Param("id") Long id);

    // NO_WAIT + PessimisticLockRetryer bounded-retry pair — mirrors CoachProfileRepository's identical
    // findByIdForUpdate (skillars-deferred-62): jakarta.persistence.lock.timeout has no effect on
    // Postgres for any finite value, so "0" here means NO_WAIT, and every call site wraps this in
    // PessimisticLockRetryer.withBoundedRetry(...) to absorb brief legitimate contention.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT p FROM PlayerProfile p WHERE p.id = :id")
    Optional<PlayerProfile> findByIdForUpdate(@Param("id") Long id);
}
