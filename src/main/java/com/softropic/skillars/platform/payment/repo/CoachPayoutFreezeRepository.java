package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** feat/coach-payouts-pack-and-credit (V186). See {@link CoachPayoutFreeze}. */
public interface CoachPayoutFreezeRepository extends JpaRepository<CoachPayoutFreeze, UUID> {

    /** True while a safety-hold freeze stands for the coach (plain read — reporting/tests). */
    boolean existsByCoachIdAndLiftedAtIsNull(UUID coachId);

    /**
     * The freeze check the payout writers use: reads the active freeze row {@code FOR SHARE}. A writer
     * that is about to record a payout FROZEN (or skip a transfer) because of the freeze therefore holds
     * that row until it commits, and {@link #lift}'s UPDATE waits for it — so the lift's subsequent
     * "select the FROZEN payouts" statement always sees the writer's committed FROZEN row and re-enqueues
     * it, instead of missing it and stranding the payout FROZEN forever. Conversely a writer arriving
     * while a lift is uncommitted waits, then re-reads the row as lifted.
     */
    @Query(value = """
        SELECT coach_id FROM payment.coach_payout_freezes
        WHERE coach_id = :coachId AND lifted_at IS NULL
        FOR SHARE
        """, nativeQuery = true)
    List<UUID> findActiveForShare(@Param("coachId") UUID coachId);

    default boolean isFrozenForShare(UUID coachId) {
        return !findActiveForShare(coachId).isEmpty();
    }

    /**
     * Activates the coach's freeze. Atomic upsert, so two concurrent holds for the same coach cannot
     * collide on the primary key; an already-active freeze is left untouched (its original
     * {@code frozen_at} / reason are kept — a re-delivered event changes nothing).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO payment.coach_payout_freezes (coach_id, reason, frozen_at)
        VALUES (:coachId, :reason, :now)
        ON CONFLICT (coach_id) DO UPDATE
            SET reason = EXCLUDED.reason, frozen_at = EXCLUDED.frozen_at, lifted_at = NULL, lifted_by = NULL
            WHERE payment.coach_payout_freezes.lifted_at IS NOT NULL
        """, nativeQuery = true)
    int activate(@Param("coachId") UUID coachId, @Param("reason") String reason, @Param("now") Instant now);

    /** Lifts an active freeze; returns 0 when there was none. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payment.coach_payout_freezes
        SET lifted_at = :now, lifted_by = :adminId
        WHERE coach_id = :coachId AND lifted_at IS NULL
        """, nativeQuery = true)
    int lift(@Param("coachId") UUID coachId, @Param("adminId") Long adminId, @Param("now") Instant now);
}
