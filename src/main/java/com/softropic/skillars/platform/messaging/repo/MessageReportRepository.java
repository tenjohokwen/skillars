package com.softropic.skillars.platform.messaging.repo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageReportRepository extends JpaRepository<MessageReport, Long> {
    boolean existsByMessageIdAndReportedBy(Long messageId, Long reportedBy);
}
