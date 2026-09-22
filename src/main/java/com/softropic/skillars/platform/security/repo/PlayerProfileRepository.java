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

    // skillars-deferred-127 code review (2026-09-21): ORDER BY id — deterministic acquisition order
    // for GdprErasureService.erase's PARENT-branch loop, each iteration of which now takes a
    // player_profiles pessimistic lock (see deletePlayerDevelopmentData). NOWAIT means a lock
    // conflict fails fast rather than waits, so unordered acquisition cannot itself produce a
    // circular-wait deadlock (40P01) — but it does make which acquisition attempt (if any) exhausts
    // its retry budget nondeterministic, heap-order-dependent, and undocumented. Zero-cost fix.
    List<PlayerProfile> findByParentIdOrderByIdAsc(Long parentId);

    /** Always use this instead of findById — parentId enforces family isolation. */
    Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId);

    boolean existsByIdAndParentId(Long id, Long parentId);

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

    // NO_WAIT + PessimisticLockRetryer bounded-retry pair — mirrors CoachProfileRepository's identical
    // findByIdForUpdate (skillars-deferred-62): jakarta.persistence.lock.timeout has no effect on
    // Postgres for any finite value, so "0" here means NO_WAIT, and every call site wraps this in
    // PessimisticLockRetryer.withBoundedRetry(...) to absorb brief legitimate contention.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT p FROM PlayerProfile p WHERE p.id = :id")
    Optional<PlayerProfile> findByIdForUpdate(@Param("id") Long id);
}
