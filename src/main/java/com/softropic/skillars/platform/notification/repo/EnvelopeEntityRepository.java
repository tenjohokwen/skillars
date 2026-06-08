package com.softropic.skillars.platform.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;


public interface EnvelopeEntityRepository extends JpaRepository<EnvelopeEntity, UUID> {

    // deadline filter is intentionally absent: the scheduler reads all retryable FAILED rows
    // (including those past their deadline) so it can mark them DEADLINE_EXPIRED in-process.
    @Query(value = "SELECT * FROM main.envelope_entity e WHERE e.retry = 'true' AND e.status = 'FAILED' ORDER BY e.deadline LIMIT 10 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<EnvelopeEntity> fetchFailedEmails();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    EnvelopeEntity findBySendId(String sendId);
}
