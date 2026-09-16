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

    // skillars-deferred-114 AC1: cluster-wide serialization on the FIRST-EVER send for a given
    // sendId, before any EnvelopeEntity row exists for findBySendId's PESSIMISTIC_WRITE lock above
    // to serialize against. Called as the first statement inside MailManager.sendEmailSync's
    // existing @Transactional(REQUIRES_NEW) boundary, before findBySendId. hashtext(text) returns a
    // 32-bit int, which widens implicitly to the bigint overload of pg_advisory_xact_lock. The lock
    // is released automatically at that transaction's commit or rollback — no manual unlock, no leak
    // risk. pg_advisory_xact_lock returns SQL void, not a row of data — unlike every other native
    // query in this codebase (fetchFailedEmails() above returns real rows; every other repository's
    // native query is a real DML @Modifying statement) — verified via a real Testcontainers-backed
    // test (MailManagerDuplicateSendIdIT) that this plain (non-@Modifying) native SELECT of a
    // void-returning function executes cleanly through Hibernate/pgjdbc rather than assumed to
    // "just work" the way the DML @Modifying queries do. @Modifying is deliberately NOT used here:
    // it routes through JDBC's executeUpdate(), which the Postgres driver rejects for a SELECT
    // statement ("A result was returned when none was expected").
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(?1))", nativeQuery = true)
    void acquireSendIdLock(String sendId);
}
