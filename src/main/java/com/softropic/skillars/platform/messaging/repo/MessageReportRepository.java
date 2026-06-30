package com.softropic.skillars.platform.messaging.repo;

import com.softropic.skillars.platform.messaging.contract.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MessageReportRepository extends JpaRepository<MessageReport, Long> {

    boolean existsByMessageIdAndReportedBy(Long messageId, Long reportedBy);

    List<MessageReport> findByMessageId(Long messageId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageReport r SET r.resolvedAt = :resolvedAt, r.resolvedBy = :resolvedBy, r.status = :status WHERE r.messageId = :messageId AND r.resolvedAt IS NULL")
    void resolveAllOpenByMessageId(@Param("messageId") Long messageId,
                                   @Param("resolvedAt") Instant resolvedAt,
                                   @Param("resolvedBy") Long resolvedBy,
                                   @Param("status") ReportStatus status);
}
