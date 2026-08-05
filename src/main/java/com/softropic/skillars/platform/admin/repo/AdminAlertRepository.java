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

    @Query("""
        SELECT a FROM AdminAlert a
        WHERE (:type IS NULL OR a.type = :type)
          AND a.status = :status
        ORDER BY a.createdAt ASC
        """)
    Page<AdminAlert> findByTypeAndStatus(
        @Param("type") @Nullable AdminAlertType type,
        @Param("status") AdminAlertStatus status,
        Pageable pageable);

    @Query("SELECT a.type, COUNT(a) FROM AdminAlert a WHERE a.status = 'OPEN' GROUP BY a.type")
    List<Object[]> countOpenByType();

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
}
