package com.softropic.skillars.platform.messaging.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL ORDER BY m.createdAt DESC")
    Page<Message> findByConversationIdAndNotDeleted(@Param("conversationId") Long conversationId, Pageable pageable);

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

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.moderationStatus = 'APPROVED' AND m.deletedAt IS NULL ORDER BY m.createdAt DESC")
    Page<Message> findLastApproved(@Param("conversationId") Long conversationId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId AND m.createdAt < :pivot AND m.deletedAt IS NULL ORDER BY m.createdAt DESC")
    List<Message> findBeforePivot(@Param("convId") Long convId, @Param("pivot") Instant pivot, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId AND m.createdAt > :pivot AND m.deletedAt IS NULL ORDER BY m.createdAt ASC")
    List<Message> findAfterPivot(@Param("convId") Long convId, @Param("pivot") Instant pivot, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :convId ORDER BY m.createdAt ASC")
    List<Message> findAllForAdmin(@Param("convId") Long convId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM messaging.messages WHERE created_at < :cutoff", nativeQuery = true)
    int deleteExpiredMessages(@Param("cutoff") Instant cutoff);

    // Deletes ALL messages by sender — including soft-deleted rows — for GDPR Article 17 erasure
    @Modifying
    @Query(value = "DELETE FROM messaging.messages WHERE sender_id = :senderId", nativeQuery = true)
    int deleteAllBySenderId(@Param("senderId") Long senderId);

    @Query("SELECT m FROM Message m WHERE m.senderId = :senderId AND m.deletedAt IS NULL ORDER BY m.createdAt ASC")
    List<Message> findNonDeletedBySenderId(@Param("senderId") Long senderId);
}
