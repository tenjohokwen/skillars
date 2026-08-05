package com.softropic.skillars.platform.messaging.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.id = :messageId")
    Optional<Message> findByIdForUpdate(@Param("messageId") Long messageId);

    // Every ORDER BY in this interface breaks the createdAt tie on id. Ties are real — two sends
    // can share a createdAt to the microsecond, and rows created before this column was written
    // explicitly took the NOW() default — and an unbroken tie makes an OFFSET-paginated query
    // non-deterministic between executions: a tied message can be returned on two adjacent pages
    // or on neither. Message.id is a TSID and therefore time-ordered, so it is a correct secondary
    // key; createdAt stays primary because it is what the column semantics and idx_messages_created_at
    // are built on.
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL ORDER BY m.createdAt DESC, m.id DESC")
    Page<Message> findByConversationIdAndNotDeleted(@Param("conversationId") Long conversationId, Pageable pageable);

    @Query("""
        SELECT m FROM Message m
        WHERE m.moderationStatus = 'PENDING' AND m.deletedAt IS NULL AND m.createdAt < :threshold
        ORDER BY m.createdAt ASC, m.id ASC
        """)
    List<Message> findPendingOlderThan(@Param("threshold") Instant threshold, Pageable pageable);

    @Query("""
        SELECT COUNT(m) FROM Message m
        WHERE m.conversationId = :conversationId
          AND m.deletedAt IS NULL
          AND m.moderationStatus = 'APPROVED'
          AND m.createdAt > :since
          AND m.senderId != :userId
        """)
    long countUnread(@Param("conversationId") Long conversationId,
                     @Param("userId") Long userId,
                     @Param("since") Instant since);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.moderationStatus = 'APPROVED' AND m.deletedAt IS NULL ORDER BY m.createdAt DESC, m.id DESC")
    Page<Message> findLastApproved(@Param("conversationId") Long conversationId, Pageable pageable);

    @Query("""
        SELECT m FROM Message m
        WHERE m.conversationId = :convId AND m.deletedAt IS NULL
          AND (m.createdAt < :pivot OR (m.createdAt = :pivot AND m.id < :pivotId))
        ORDER BY m.createdAt DESC, m.id DESC
        """)
    List<Message> findBeforePivot(@Param("convId") Long convId, @Param("pivot") Instant pivot,
                                   @Param("pivotId") Long pivotId, Pageable pageable);

    @Query("""
        SELECT m FROM Message m
        WHERE m.conversationId = :convId AND m.deletedAt IS NULL
          AND (m.createdAt > :pivot OR (m.createdAt = :pivot AND m.id > :pivotId))
        ORDER BY m.createdAt ASC, m.id ASC
        """)
    List<Message> findAfterPivot(@Param("convId") Long convId, @Param("pivot") Instant pivot,
                                  @Param("pivotId") Long pivotId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId ORDER BY m.createdAt ASC, m.id ASC")
    List<Message> findAllForAdmin(@Param("convId") Long convId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        DELETE FROM messaging.messages
        WHERE created_at < :cutoff
          AND id NOT IN (
              SELECT message_id FROM messaging.message_reports
              WHERE status IN ('OPEN', 'UNDER_REVIEW')
          )
        """, nativeQuery = true)
    int deleteOldMessagesWithNoOpenReports(@Param("cutoff") Instant cutoff);

    // Deletes ALL messages by sender — including soft-deleted rows — for GDPR Article 17 erasure
    @Modifying
    @Query(value = "DELETE FROM messaging.messages WHERE sender_id = :senderId", nativeQuery = true)
    int deleteAllBySenderId(@Param("senderId") Long senderId);

    @Query("SELECT m FROM Message m WHERE m.senderId = :senderId AND m.deletedAt IS NULL ORDER BY m.createdAt ASC, m.id ASC")
    List<Message> findNonDeletedBySenderId(@Param("senderId") Long senderId);
}
