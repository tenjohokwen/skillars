package com.softropic.skillars.platform.notification.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Every write here is a single atomic statement scoped by recipient in its own {@code WHERE} clause
 * (no load-check-write), so a caller can never touch another user's row, and concurrent duplicate
 * calls are idempotent.
 */
public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

    Page<UserNotification> findByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    /** Idempotent: an already-read or another user's notification matches no row. */
    @Modifying
    @Query(value = "UPDATE main.user_notifications SET read_at = now() "
        + "WHERE id = :id AND recipient_user_id = :recipient AND read_at IS NULL", nativeQuery = true)
    int markRead(@Param("id") UUID id, @Param("recipient") long recipientUserId);

    @Modifying
    @Query(value = "UPDATE main.user_notifications SET read_at = now() "
        + "WHERE recipient_user_id = :recipient AND read_at IS NULL", nativeQuery = true)
    int markAllRead(@Param("recipient") long recipientUserId);

    @Modifying
    @Query(value = "DELETE FROM main.user_notifications WHERE recipient_user_id = :recipient", nativeQuery = true)
    int deleteAllForRecipient(@Param("recipient") long recipientUserId);

    /**
     * Retention sweep, bounded per call so one run never holds a long lock over a large backlog —
     * the scheduler loops until a batch comes back short.
     */
    @Modifying
    @Query(value = "DELETE FROM main.user_notifications WHERE id IN ("
        + "SELECT id FROM main.user_notifications WHERE created_at < :cutoff LIMIT :batchSize)",
        nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
