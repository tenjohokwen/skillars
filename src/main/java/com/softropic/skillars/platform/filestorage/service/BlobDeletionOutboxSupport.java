package com.softropic.skillars.platform.filestorage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.Collection;

/**
 * skillars-deferred-100 AC6: enqueues storage-key deletions onto the generic
 * {@code platform.outbox} — the consolidation of skillars-deferred-90's bespoke
 * {@code PendingBlobDeletionService} mini-outbox, so the codebase has one transactional-outbox
 * implementation.
 *
 * <p>Producers call {@link #enqueue} <em>inside</em> their business transaction (so the rows commit
 * atomically with the work that decided they should be deleted), then
 * {@link #requestDrainAfterCommit()} (also inside it). {@link BlobDeletionOutboxHandler} re-drives
 * each row off the request path via {@code FileStorageService.deleteRawBytes}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlobDeletionOutboxSupport {

    /** {@code aggregate_type} for a single storage-key deletion. */
    public static final String AGGREGATE_TYPE = "BLOB_DELETION";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Enqueue storage keys for post-commit deletion, one outbox row per distinct non-blank key.
     * MUST be called inside the producing business transaction.
     */
    public void enqueue(Collection<String> storageKeys) {
        if (storageKeys == null) {
            return;
        }
        storageKeys.stream()
            .filter(k -> k != null && !k.isBlank())
            .distinct()
            .forEach(this::enqueueOne);
    }

    private void enqueueOne(String storageKey) {
        try {
            outboxService.enqueue(AGGREGATE_TYPE,
                objectMapper.writeValueAsString(new BlobDeletionPayload(storageKey)));
        } catch (JsonProcessingException e) {
            // A single string field; serialisation should never fail. skillars-deferred-100 code
            // review (2026-09-08): if it somehow does, rethrow so the producing (e.g. GDPR erasure)
            // transaction rolls back rather than committing COMPLETED with a PII key that was never
            // scheduled for deletion. The old bespoke repository.saveAll(...) failed the same way.
            log.error("[BLOB_DELETION_ENQUEUE_FAILED] key={} — NOT enqueued; failing the caller transaction",
                storageKey, e);
            throw new UncheckedIOException(e);
        }
    }

    /** Ask for exactly one outbox drain after the producing transaction commits. */
    public void requestDrainAfterCommit() {
        outboxService.requestDrainAfterCommit();
    }

    /** Outbox payload for a re-drivable single storage-key deletion. */
    public record BlobDeletionPayload(String storageKey) {
    }
}
