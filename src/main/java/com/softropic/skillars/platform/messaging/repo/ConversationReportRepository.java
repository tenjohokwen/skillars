package com.softropic.skillars.platform.messaging.repo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationReportRepository extends JpaRepository<ConversationReport, Long> {
    boolean existsByConversationIdAndReportedBy(Long conversationId, Long reportedBy);
}
