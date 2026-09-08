package com.softropic.skillars.platform.filestorage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * skillars-deferred-100 AC6: re-drives a single storage-key deletion enqueued by
 * {@link BlobDeletionOutboxSupport} onto the generic {@code platform.outbox}.
 *
 * <p><strong>Idempotent</strong> (as {@link OutboxMessageHandler} requires): S3 {@code DeleteObject}
 * succeeds whether or not the key exists, so re-driving an already-deleted key is a no-op. A
 * genuine transport failure throws, leaving the row for the next drain with {@code attempts++} /
 * backoff.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlobDeletionOutboxHandler implements OutboxMessageHandler {

    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    @Override
    public String aggregateType() {
        return BlobDeletionOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final BlobDeletionOutboxSupport.BlobDeletionPayload p;
        try {
            p = objectMapper.readValue(payload, BlobDeletionOutboxSupport.BlobDeletionPayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        fileStorageService.deleteRawBytes(p.storageKey());
        log.info("[BLOB_DELETION] re-driven storage-key deletion key={}", p.storageKey());
    }
}
