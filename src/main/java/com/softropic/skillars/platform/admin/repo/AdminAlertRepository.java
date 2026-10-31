package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminAlertRepository extends JpaRepository<AdminAlert, UUID> {

    /**
     * skillars-deferred-91 AC6 (skillars-deferred-16 D4): raw-string reads for the two queue
     * endpoints. During a rolling deploy an older instance can read a row whose {@code alert_type}
     * carries a value the older {@code AdminAlertType} enum does not have yet ({@code
     * MODERATION_UNRESOLVED} was the first). Entity hydration / a JPQL projection of {@code a.type}
     * would then throw {@code IllegalArgumentException} and 500 the whole {@code /queue} or
     * {@code /queue/summary} page. Selecting {@code type} as text lets the service skip the
     * unmappable row with a WARN and render everything it can map. (These replaced the JPQL
     * {@code findByTypeAndStatus} / {@code countOpenByType} the queue reads used before.)
     */
    @Query(value = """
        SELECT alert_id, type, reference_id, reference_type, status, created_at, reason
        FROM admin.admin_alerts
        WHERE (CAST(:type AS text) IS NULL OR type = CAST(:type AS text))
          AND status = 'OPEN'
        ORDER BY created_at ASC
        """,
        countQuery = """
        SELECT COUNT(*) FROM admin.admin_alerts
        WHERE (CAST(:type AS text) IS NULL OR type = CAST(:type AS text))
          AND status = 'OPEN'
        """,
        nativeQuery = true)
    Page<Object[]> findOpenRawByType(@Param("type") @Nullable String type, Pageable pageable);

    @Query(value = "SELECT type, COUNT(*) FROM admin.admin_alerts WHERE status = 'OPEN' GROUP BY type",
        nativeQuery = true)
    List<Object[]> countOpenByTypeRaw();

    Optional<AdminAlert> findFirstByReferenceIdAndTypeAndStatus(
        String referenceId, AdminAlertType type, AdminAlertStatus status);

    @Query("SELECT COUNT(a) FROM AdminAlert a WHERE a.referenceId = :referenceId AND a.status = 'OPEN'")
    long countOpenByReferenceId(@Param("referenceId") String referenceId);

    /**
     * Closes OPEN MESSAGE-referencing alerts whose message row no longer exists. Both message
     * hard-delete paths — GDPR erasure and the retention scheduler — leave the alert behind, and
     * an admin cannot clear it by hand: {@code approveMessage}/{@code blockMessage} 404 before
     * reaching their alert-resolution block, and there is no dismiss endpoint. It would sit OPEN
     * forever, inflating the queue summary. MODERATION_UNRESOLVED is the first alert type that can
     * be orphaned this way — MESSAGE_REPORT alerts are incidentally protected, because retention's
     * own predicate preserves any message carrying an open report.
     *
     * <p>{@code resolved_by} is left null: no admin made this decision, the subject disappeared.
     * The regex guard keeps the cast safe regardless of predicate evaluation order.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE admin.admin_alerts
        SET status = 'RESOLVED', resolved_at = NOW()
        WHERE status = 'OPEN'
          AND reference_type = 'MESSAGE'
          AND reference_id ~ '^[0-9]+$'
          AND NOT EXISTS (
              SELECT 1 FROM messaging.messages m WHERE m.id = reference_id::BIGINT
          )
        """, nativeQuery = true)
    int resolveOpenAlertsForDeletedMessages();

    /**
     * B6: stamps the cause onto an OPEN alert in one atomic statement (no load-check-write). Used to mark
     * a MODERATION_UNRESOLVED hold as a moderation-provider failure: the hold is raised with the
     * verdict-level reason MODERATION_UNCERTAIN, which reads as "the classifier was unsure" when in fact
     * nothing assessed the content. A no-op (0) when no matching OPEN alert exists. AdminAlert has no
     * {@code @Version}, so a native UPDATE needs no version bump.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE admin.admin_alerts
        SET reason = :reason
        WHERE reference_id = :referenceId
          AND type = :type
          AND status = 'OPEN'
        """, nativeQuery = true)
    int updateOpenAlertReason(@Param("referenceId") String referenceId, @Param("type") String type,
                              @Param("reason") String reason);

    /**
     * C2: an admin marks one OPEN alert of {@code type} handled — one atomic conditional UPDATE (no
     * load-check-write), so two admins resolving the same alert at once resolve it once; the loser gets 0.
     * AdminAlert has no {@code @Version}, so a native UPDATE needs no version bump.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE admin.admin_alerts
        SET status = 'RESOLVED', resolved_at = NOW(), resolved_by = :adminId
        WHERE alert_id = :alertId
          AND type = :type
          AND status = 'OPEN'
        """, nativeQuery = true)
    int resolveOpenAlert(@Param("alertId") java.util.UUID alertId, @Param("type") String type,
                         @Param("adminId") Long adminId);

    /**
     * S-07: a payer's REPEAT_NO_SHOW_CLAIMANT alerts raised since {@code since}, whatever their status — "one per payer
     * per window" must hold even after an admin marked the first one handled. {@code prefix} is
     * {@code "payer <userId>:%"} (the colon stops one id matching another's prefix).
     */
    @Query(value = """
        SELECT COUNT(*) FROM admin.admin_alerts
        WHERE type = 'REPEAT_NO_SHOW_CLAIMANT' AND reason LIKE :prefix AND created_at > :since
        """, nativeQuery = true)
    long countRepeatClaimantAlertsSince(@Param("prefix") String prefix, @Param("since") java.time.Instant since);

    /**
     * S-07: serialises concurrent claims by one payer so the count-then-insert above cannot raise two alerts. Two-int
     * form {@code (namespace, hashtext(key))} keeps it out of the other advisory key spaces ("RCLM"); held to the end of
     * the caller's transaction. Not {@code @Modifying}: a SELECT of a void function, like
     * {@code CoachCancellationHistoryRepository.acquireCoachCancellationLock}.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(1380142157, hashtext(:payerKey))", nativeQuery = true)
    void acquireRepeatClaimantLock(@Param("payerKey") String payerKey);

    /**
     * C6: resolves every OPEN alert of {@code type} on one reference — used when an admin cancels a
     * booking that a child-safety hold could not, so its SAFETY_HOLD_BOOKING alert is closed with it. One
     * atomic conditional UPDATE (no load-check-write); a no-op (0) when none is open.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE admin.admin_alerts
        SET status = 'RESOLVED', resolved_at = NOW(), resolved_by = :adminId
        WHERE reference_id = :referenceId
          AND type = :type
          AND status = 'OPEN'
        """, nativeQuery = true)
    int resolveOpenAlertsForReference(@Param("referenceId") String referenceId, @Param("type") String type,
                                      @Param("adminId") Long adminId);
}
