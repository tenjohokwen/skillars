package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AppealStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppealRepository extends JpaRepository<Appeal, UUID> {

    List<Appeal> findTop100ByAppellantUserIdOrderByCreatedAtDesc(Long appellantUserId);

    Page<Appeal> findByStatus(AppealStatus status, Pageable pageable);

    /**
     * The race-safe decision: flips OPEN to a final status in one statement. Postgres re-evaluates the
     * {@code status = OPEN} predicate after waiting out a concurrent decider's row lock, so exactly one
     * of two concurrent decisions gets 1 row; the loser gets 0 and is told the appeal is already decided.
     * Appeal has no {@code @Version}, so no version bump is owed (NativeModifyingVersionAuditTest).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Appeal a SET a.status = :outcome, a.decisionNote = :note, a.decidedBy = :adminId, "
        + "a.decidedAt = :decidedAt WHERE a.id = :id AND a.status = "
        + "com.softropic.skillars.platform.admin.contract.AppealStatus.OPEN")
    int decideIfOpen(@Param("id") UUID id, @Param("outcome") AppealStatus outcome, @Param("note") String note,
                     @Param("adminId") Long adminId, @Param("decidedAt") Instant decidedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Appeal a SET a.reversalApplied = :applied WHERE a.id = :id")
    int markReversalApplied(@Param("id") UUID id, @Param("applied") boolean applied);
}
