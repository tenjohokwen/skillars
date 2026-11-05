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

    @Query("SELECT a FROM SessionPackPurchaseAttempt a WHERE a.status IN :open AND a.createdAt <= :cutoff ORDER BY a.createdAt")
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
