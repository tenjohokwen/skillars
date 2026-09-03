package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletion;
import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Processes one small chunk of the pending-deletion outbox in its own short transaction.
 *
 * <p>Code review of skillars-deferred-90 (3-layer run). This exists as a <em>separate bean</em> on
 * purpose: {@code PendingBlobDeletionService.drain()} previously self-invoked, so its
 * {@code @Transactional(REQUIRES_NEW)} never went through the Spring proxy. On the AFTER_COMMIT
 * path that left the whole drain running inside the listener's transaction — one pooled DB
 * connection pinned across up to 500 sequential blocking S3 deletes, which is precisely the
 * anti-pattern {@code V122} was introduced to remove, merely relocated off the request thread.
 *
 * <p>Now each chunk is claimed and completed in its own {@code REQUIRES_NEW} transaction that is
 * open only for the DB work; the connection is released between chunks, so total connection-hold
 * time no longer scales with the number of blobs. Chunks are small so a single S3 stall cannot pin
 * a connection for long.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingBlobDeletionChunkProcessor {

    /** Small on purpose: the transaction stays open for the S3 calls of one chunk only. */
    static final int CHUNK_SIZE = 25;
    private static final int MAX_LAST_ERROR_LEN = 1000;

    private final PendingBlobDeletionRepository repository;
    private final FileStorageService fileStorageService;

    /** Outcome of one chunk, so the caller can loop until the outbox is empty. */
    public record ChunkResult(int claimed, int deleted, int failed) {
        public boolean drainedSomething() {
            return claimed > 0;
        }
    }

    /**
     * Claims up to {@link #CHUNK_SIZE} rows with {@code FOR UPDATE SKIP LOCKED}, deletes each key
     * from storage, removes the row on success and leaves it with {@code attempts++} on failure.
     *
     * <p>{@code REQUIRES_NEW} genuinely applies here — this is a cross-bean call, so it goes through
     * the proxy. It also detaches the chunk from any caller transaction, which is what stops an
     * AFTER_COMMIT listener from holding its connection for the whole drain.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkResult processChunk() {
        final List<PendingBlobDeletion> chunk = repository.claimNextChunk(PageRequest.of(0, CHUNK_SIZE));
        int deleted = 0;
        int failed = 0;
        for (PendingBlobDeletion row : chunk) {
            try {
                fileStorageService.deleteRawBytes(row.getStorageKey());
                repository.delete(row);
                deleted++;
            } catch (RuntimeException e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(truncate(e.getMessage()));
                repository.save(row);
                failed++;
                log.warn("[PENDING_BLOB_DELETION_RETRY] key={} attempts={}",
                    row.getStorageKey(), row.getAttempts(), e);
            }
        }
        return new ChunkResult(chunk.size(), deleted, failed);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_LAST_ERROR_LEN ? s : s.substring(0, MAX_LAST_ERROR_LEN);
    }
}
