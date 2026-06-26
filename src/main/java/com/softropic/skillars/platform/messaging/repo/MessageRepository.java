package com.softropic.skillars.platform.messaging.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

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
}
