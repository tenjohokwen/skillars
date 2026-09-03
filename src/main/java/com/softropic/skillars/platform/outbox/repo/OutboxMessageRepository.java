package com.softropic.skillars.platform.outbox.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claims the next due rows, fewest-attempts first then oldest, with {@code FOR UPDATE SKIP
     * LOCKED} — the shape skillars-deferred-90's 3-layer review forced onto
     * {@code PendingBlobDeletionRepository.claimNextChunk}:
     *
     * <ul>
     *   <li>{@code nextAttemptAt <= :now} (skillars-deferred-91 review D6) so a row that keeps
     *       failing waits out its backoff instead of being re-claimed on every drain. Without this
     *       predicate a deterministically failing row was in every chunk whenever the table held
     *       fewer than {@code CHUNK_SIZE} rows, because {@code ORDER BY attempts} only demotes a
     *       row relative to <em>fresher</em> work;</li>
     *   <li>{@code ORDER BY attempts ASC, id ASC} so a permanently failing row sinks below fresh
     *       work instead of blocking every page at the head;</li>
     *   <li>{@code @Lock(PESSIMISTIC_WRITE)} + {@code jakarta.persistence.lock.timeout = -2}
     *       (SKIP LOCKED) so the AFTER_COMMIT drain and the scheduled sweep can run concurrently
     *       without both claiming the same rows and double-dispatching a handler.</li>
     * </ul>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT m FROM OutboxMessage m WHERE m.nextAttemptAt <= :now ORDER BY m.attempts ASC, m.id ASC")
    List<OutboxMessage> claimNextDue(@Param("now") Instant now, Pageable pageable);

    /** Rows that have failed enough times to warrant a human look (see {@code OutboxService}). */
    long countByAttemptsGreaterThanEqual(int attempts);
}
