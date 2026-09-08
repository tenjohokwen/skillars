package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletion;
import com.softropic.skillars.platform.filestorage.repo.PendingBlobDeletionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * skillars-deferred-100 AC6: one-shot migration of any residual {@code main.pending_blob_deletions}
 * rows onto the generic {@code platform.outbox}, so nothing enqueued by the just-superseded
 * {@code PendingBlobDeletionService} in a prior release is lost.
 *
 * <p>The bespoke {@code PendingBlobDeletionService} / {@code …ChunkProcessor} /
 * {@code BlobDeletionsEnqueuedEvent} are deleted in this release; the {@link PendingBlobDeletion}
 * entity + {@link PendingBlobDeletionRepository} are kept <em>only</em> so this runner can read the
 * table. The {@code DROP TABLE main.pending_blob_deletions} + removing this runner + the entity/repo
 * is a follow-up for a <em>later</em> release (cannot drop a table in the same release that stops
 * using it — {@code docs/deployment/migration-conventions.md}); see skillars-deferred-100 AC7.
 *
 * <p>Runs once at startup. A no-op when the table is empty, which it is expected to be in every
 * environment.
 *
 * <p><strong>skillars-deferred-100 code review (2026-09-08):</strong>
 * <ul>
 *   <li>Migration runs in bounded {@value #CHUNK_SIZE}-row chunks, each in its own transaction —
 *       not one unbounded {@code findAll()} + load-everything-into-memory transaction (a large
 *       backlog would otherwise mean a statement-timeout / OOM at startup).</li>
 *   <li>The whole body is wrapped so a failure — DB slow/unavailable at boot, an {@code enqueue}
 *       error, or a lost race with the old release's still-scheduled {@code sweep()} deleting a row
 *       mid-chunk — is logged loudly and startup <em>continues</em>. The generic
 *       {@code OutboxService.sweep()} is not the safety net for un-migrated legacy rows, so an
 *       ERROR here is an ops task, not a boot blocker.</li>
 *   <li>Deletes via {@code deleteAllByIdInBatch} (a bulk {@code DELETE … WHERE id IN (…)} with no
 *       per-row existence check) so a row the old {@code sweep()} already removed does not raise
 *       {@code OptimisticLockingFailureException}. A key both sweeps handle is re-enqueued and
 *       re-deleted from S3 idempotently ({@code deleteRawBytes}).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingBlobDeletionResidualDrainRunner implements ApplicationRunner {

    static final int CHUNK_SIZE = 100;
    /** Safety stop: at CHUNK_SIZE this covers 500k rows, far above any realistic residual backlog. */
    static final int MAX_CHUNKS = 5000;

    private final PendingBlobDeletionRepository legacyRepository;
    private final BlobDeletionOutboxSupport blobDeletionOutboxSupport;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrateResidualRows();
        } catch (RuntimeException e) {
            log.error("[PENDING_BLOB_DELETION_RESIDUAL] failed to migrate legacy "
                + "main.pending_blob_deletions rows onto the generic outbox — startup continues; "
                + "re-run is safe and the rows are still in the legacy table (skillars-deferred-100 AC6). "
                + "Manual ops follow-up required.", e);
        }
    }

    private void migrateResidualRows() {
        long total = legacyRepository.count();
        if (total == 0) {
            return;
        }
        log.warn("[PENDING_BLOB_DELETION_RESIDUAL] {} legacy main.pending_blob_deletions row(s) found at "
            + "startup — re-enqueueing onto the generic outbox in chunks of {} (skillars-deferred-100 AC6)",
            total, CHUNK_SIZE);

        long migrated = 0;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            long done = migrated;
            Long chunkMigrated = transactionTemplate.execute(s -> {
                List<PendingBlobDeletion> rows =
                    legacyRepository.findAll(PageRequest.of(0, CHUNK_SIZE)).getContent();
                if (rows.isEmpty()) {
                    return 0L;
                }
                blobDeletionOutboxSupport.enqueue(rows.stream().map(PendingBlobDeletion::getStorageKey).toList());
                legacyRepository.deleteAllByIdInBatch(rows.stream().map(PendingBlobDeletion::getId).toList());
                blobDeletionOutboxSupport.requestDrainAfterCommit();
                return (long) rows.size();
            });
            if (chunkMigrated == null || chunkMigrated == 0L) {
                break;
            }
            migrated = done + chunkMigrated;
        }
        log.warn("[PENDING_BLOB_DELETION_RESIDUAL] migrated {} legacy row(s) onto the generic outbox", migrated);
    }
}
