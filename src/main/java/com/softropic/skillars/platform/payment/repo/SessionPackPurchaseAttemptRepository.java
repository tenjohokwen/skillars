package com.softropic.skillars.platform.payment.repo;

import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * skillars-deferred-169 AC1. {@code purchasePack} is deliberately non-{@code @Transactional}, so every method here that writes
 * carries its own {@code @Transactional} (a bare {@code @Modifying} would throw {@code TransactionRequiredException}).
 */
public interface SessionPackPurchaseAttemptRepository extends JpaRepository<SessionPackPurchaseAttempt, Long> {

    Optional<SessionPackPurchaseAttempt> findByParentIdAndAttemptKey(Long parentId, String attemptKey);

    @Query("SELECT a FROM SessionPackPurchaseAttempt a WHERE a.parentId = :parentId AND a.status IN :open")
    Optional<SessionPackPurchaseAttempt> findOpenByParentId(@Param("parentId") Long parentId,
                                                            @Param("open") Collection<Status> open);

    /** The job's work list: open rows old enough to resolve, minus those marked as needing an operator (skillars-deferred-174 AC6). */
    @Query("SELECT a FROM SessionPackPurchaseAttempt a WHERE a.status IN :open AND a.createdAt <= :cutoff "
        + "AND a.needsOperatorSince IS NULL ORDER BY a.createdAt")
    List<SessionPackPurchaseAttempt> findOpenOlderThan(@Param("open") Collection<Status> open,
                                                       @Param("cutoff") Instant cutoff);

    /**
     * Compare-and-set: moves the row to {@code newStatus} only if it is still in one of {@code expected}, recording the
     * PaymentIntent id when given. Returns 0 when it lost the race; the caller re-reads and never overwrites a terminal state.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE payment.session_pack_purchase_attempts SET status = :newStatus, "
        + "payment_intent_id = COALESCE(:paymentIntentId, payment_intent_id), updated_at = now() "
        + "WHERE id = :id AND status IN (:expected)", nativeQuery = true)
    int transitionRaw(@Param("id") Long id, @Param("newStatus") String newStatus,
                      @Param("paymentIntentId") String paymentIntentId, @Param("expected") Collection<String> expected);

    default boolean transition(Long id, Status newStatus, String paymentIntentId, Collection<Status> expected) {
        return transitionRaw(id, newStatus.name(), paymentIntentId, expected.stream().map(Enum::name).toList()) == 1;
    }

    /**
     * skillars-deferred-174 AC6: marks an open row as needing an operator, once. Returns 0 when it was already marked or is no
     * longer open, so the caller logs the one-shot tag only on the write that set it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE payment.session_pack_purchase_attempts SET needs_operator_since = now() "
        + "WHERE id = :id AND needs_operator_since IS NULL AND status IN (:open)", nativeQuery = true)
    int markNeedsOperator(@Param("id") Long id, @Param("open") Collection<String> open);

    /**
     * skillars-deferred-174 AC5: who closed the row with the admin close action, and why. The status itself is only ever
     * written by {@link #transition}; this runs after the row closed, and clears the needs-operator mark.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE payment.session_pack_purchase_attempts SET closed_by = :adminUserId, close_reason = :reason, "
        + "needs_operator_since = NULL WHERE id = :id", nativeQuery = true)
    int recordAdminClose(@Param("id") Long id, @Param("adminUserId") Long adminUserId, @Param("reason") String reason);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM payment.session_pack_purchase_attempts WHERE status NOT IN ('IN_PROGRESS','UNKNOWN','UNRECONCILED') "
        + "AND updated_at < :cutoff", nativeQuery = true)
    int purgeTerminalOlderThan(@Param("cutoff") Instant cutoff);

    /** GDPR (AC13): the row is retained as a financial record; the card ids tied to a person are not. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE payment.session_pack_purchase_attempts SET previous_card = NULL, attempted_card = NULL "
        + "WHERE parent_id = :parentId", nativeQuery = true)
    int nullCardsForParent(@Param("parentId") Long parentId);
}
