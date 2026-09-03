package com.softropic.skillars.platform.filestorage.contract.event;

/**
 * skillars-deferred-90 AC13: published (inside a business transaction) when storage keys have been
 * written to {@code main.pending_blob_deletions}. {@code PendingBlobDeletionService} listens for it
 * with {@code @TransactionalEventListener(AFTER_COMMIT)} and drains the table off the request path.
 */
public record BlobDeletionsEnqueuedEvent() {
}
