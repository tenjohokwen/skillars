package com.softropic.skillars.platform.notification.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * skillars-deferred-129 AC3: replaces the {@code findAll().stream().filter(...)} full-table scan
     * both {@code RegistrationEmailDurabilityIT.committedRowFor} and {@code
     * VideoModerationAdminAlertEnvelopeIT.committedRow} used at the one point in each test where only
     * the seeded recipient email is known (the registration/OTP listener generates {@code sendId}
     * internally, so it cannot be known upfront the way {@code committedRowBySendId}'s call sites
     * already know theirs — see skillars-deferred-111 AC7).
     *
     * <p><strong>Not an indexed lookup (M9):</strong> {@code envelope_entity_recipients}' only index
     * is its composite primary key {@code (envelope_entity_id, email)} — {@code envelope_entity_id}
     * is the LEADING column, so a predicate on {@code email} alone cannot use that B-tree, and no
     * other index on {@code email} exists in the migrations. The real, still-genuine benefit is
     * avoiding {@code findAll()}'s full materialization of every {@code EnvelopeEntity} row (and
     * hydration of each one's {@code recipients} collection) into the persistence context — a real
     * cost that grows with everything else the shared JVM-static test database accumulates across the
     * whole suite, not an indexed-lookup speedup. If real index support is ever wanted, that needs
     * its own migration, not this query.
     *
     * <p><strong>{@code LEFT JOIN FETCH}, not a plain {@code JOIN} (H3, blocking):</strong> {@code
     * EnvelopeEntity.recipients} is a LAZY {@code @ElementCollection} (no {@code fetch} attribute),
     * and the test base class ({@code AbstractIntegrationTest}) is not {@code @Transactional} — a
     * plain {@code JOIN} would filter but not initialize the collection, and every caller here reads
     * {@code getRecipients()} after the seam's own transaction has already returned, throwing
     * {@code LazyInitializationException} (mirrors the constraint {@code
     * MailManagerDuplicateSendIdIT} already documents for this same lazy collection). Filtering
     * directly on the fetched join is ALSO wrong — it would silently prune the returned collection to
     * only the matching recipient, the classic JPA trap; the {@code IN (SELECT ...)} subquery below
     * filters on a separate, un-fetched join instead, so the outer {@code LEFT JOIN FETCH} always
     * returns every recipient of a matched envelope. No {@code @Lock} needed (unlike {@code
     * findBySendId}'s {@code PESSIMISTIC_WRITE}) — this method's only callers are test code asserting
     * on already-committed state.
     */
    @Query("SELECT DISTINCT e FROM EnvelopeEntity e LEFT JOIN FETCH e.recipients WHERE e.id IN "
        + "(SELECT e2.id FROM EnvelopeEntity e2 JOIN e2.recipients r2 WHERE r2.email = :email)")
    List<EnvelopeEntity> findByRecipientsEmail(@Param("email") String email);
}
