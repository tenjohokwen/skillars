package com.softropic.skillars.platform.filestorage.repo;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * skillars-deferred-100 AC6: retained <em>only</em> so
 * {@code PendingBlobDeletionResidualDrainRunner} can read any rows a prior release's
 * {@code PendingBlobDeletionService} left behind and re-enqueue them onto the generic
 * {@code platform.outbox}. The claim / stuck-count queries the bespoke drain used are gone with it.
 * This repo, the {@link PendingBlobDeletion} entity and the {@code main.pending_blob_deletions}
 * table are dropped in a later release (see skillars-deferred-100 AC7).
 */
public interface PendingBlobDeletionRepository extends JpaRepository<PendingBlobDeletion, Long> {
}
