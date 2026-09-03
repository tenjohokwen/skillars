package com.softropic.skillars.platform.filestorage.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PendingBlobDeletionRepository extends JpaRepository<PendingBlobDeletion, Long> {

    /**
     * Claims the next chunk of pending deletions, fewest-attempts first then oldest.
     *
     * <p>Code review of skillars-deferred-90 (D1): ordering by {@code id} alone let a permanently
     * failing row (lowest id, retried forever) sit at the head of every page, so once a batch's
     * worth of such rows accumulated no newer key was ever deleted. Ordering by {@code attempts}
     * first means a row that keeps failing sinks below fresh work instead of blocking it.
     *
     * <p>Code review (3-layer run): {@code FOR UPDATE SKIP LOCKED}. The AFTER_COMMIT drain and the
     * scheduled sweep can run concurrently on the same instance — {@code @SchedulerLock} only
     * serialises {@code sweep()} across instances — and without a row lock both claimed the same
     * rows, producing duplicate {@code deleteRawBytes} calls and stale-entity delete/save errors.
     * {@code SKIP LOCKED} lets a second drainer take the next free chunk instead of blocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT p FROM PendingBlobDeletion p ORDER BY p.attempts ASC, p.id ASC")
    List<PendingBlobDeletion> claimNextChunk(Pageable pageable);

    /** Rows that have failed enough times to be worth a human look (see PendingBlobDeletionService). */
    long countByAttemptsGreaterThanEqual(int attempts);
}
