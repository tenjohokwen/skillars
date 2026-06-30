package com.softropic.skillars.platform.messaging.repo;

import com.softropic.skillars.platform.messaging.contract.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ConversationReportRepository extends JpaRepository<ConversationReport, Long> {

    boolean existsByConversationIdAndReportedBy(Long conversationId, Long reportedBy);

    List<ConversationReport> findByConversationId(Long conversationId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ConversationReport r SET r.resolvedAt = :resolvedAt, r.resolvedBy = :resolvedBy, r.status = :status WHERE r.conversationId = :convId AND r.resolvedAt IS NULL")
    void resolveAllOpenByConversationId(@Param("convId") Long convId,
                                        @Param("resolvedAt") Instant resolvedAt,
                                        @Param("resolvedBy") Long resolvedBy,
                                        @Param("status") ReportStatus status);
}
