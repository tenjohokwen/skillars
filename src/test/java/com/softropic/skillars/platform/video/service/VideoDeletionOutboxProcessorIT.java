package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoDeletionLog;
import com.softropic.skillars.platform.video.repo.VideoDeletionLogRepository;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutbox;
import com.softropic.skillars.platform.video.repo.VideoDeletionOutboxRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class VideoDeletionOutboxProcessorIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    @Autowired VideoDeletionOutboxProcessor processor;
    @Autowired VideoRepository videoRepository;
    @Autowired VideoDeletionOutboxRepository outboxRepository;
    @Autowired VideoDeletionLogRepository deletionLogRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        deletionLogRepository.deleteAll();
        outboxRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void process_successfulBunnyDelete_completesRowAndNullsProviderAssetId() {
        Video video = seedPurgedVideo("asset-to-delete");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-to-delete");
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-to-delete"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        // skillars-deferred-123 code review 2026-09-18 (Patch): claimed_at clearing on the COMPLETED
        // path was previously unasserted anywhere in the suite (grep for getClaimedAt found nothing).
        assertThat(updated.getClaimedAt()).as("COMPLETED must clear claimed_at per its own invariant").isNull();

        Video updatedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updatedVideo.getProviderAssetId()).isNull();

        List<VideoDeletionLog> logs = deletionLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getVideoId()).isEqualTo(video.getId());
        assertThat(logs.get(0).getBunnyVideoId()).isEqualTo("asset-to-delete");
    }

    @Test
    void process_nullBunnyVideoId_shortCircuitsWithoutApiCall() {
        Video video = seedPurgedVideo(null);
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), null);

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(deletionLogRepository.findAll()).hasSize(1);
    }

    @Test
    void process_bunnyDeleteFails_incrementsAttemptsAndRetries() {
        Video video = seedPurgedVideo("asset-fail");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-fail");
        doThrow(new RuntimeException("Bunny 503")).when(videoProviderAdapter).deleteAsset(eq("asset-fail"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getLastError()).contains("Bunny 503");
        assertThat(updated.getNextRetryAt()).isAfter(Instant.now());
        // skillars-deferred-123 code review 2026-09-18 (Patch): PENDING (backoff, retries remain) must
        // also clear claimed_at — previously unasserted anywhere in the suite.
        assertThat(updated.getClaimedAt()).as("PENDING backoff must clear claimed_at too").isNull();
    }

    @Test
    void process_maxAttemptsExceeded_rowBecomesDeadLetter() {
        Video video = seedPurgedVideo("asset-dead");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-dead");
        // Set attempts to max - 1 so next failure triggers DEAD
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setAttempts(4); // max is 5 from config seed
            outboxRepository.save(loaded);
            return null;
        });
        doThrow(new RuntimeException("Bunny permanently down")).when(videoProviderAdapter).deleteAsset(eq("asset-dead"));

        processor.process();

        VideoDeletionOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DEAD");
        assertThat(updated.getAttempts()).isEqualTo(5);
        // skillars-deferred-123 code review 2026-09-18 (Patch): a DEAD row keeping a stale non-null
        // claimed_at would give a false claim-age reading if ever re-queued — previously unasserted.
        assertThat(updated.getClaimedAt()).as("DEAD must clear claimed_at too").isNull();
    }

    @Test
    void process_failThenSucceed_completesOnRetry() {
        Video video = seedPurgedVideo("asset-retry");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-retry");

        // First drain: Bunny fails
        doThrow(new RuntimeException("Bunny 503")).when(videoProviderAdapter).deleteAsset(eq("asset-retry"));
        processor.process();

        VideoDeletionOutbox afterFirst = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getStatus()).isEqualTo("PENDING");

        // Reset next_retry_at so the row is eligible on the next drain
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setNextRetryAt(Instant.now().minusSeconds(1));
            outboxRepository.save(loaded);
            return null;
        });

        // Second drain: Bunny succeeds
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-retry"));
        // skillars-deferred-120 AC2: @SchedulerLock's lockAtLeastFor would otherwise silently
        // skip this second same-method-invocation call (this test invokes process() twice inside
        // one test method, going through the @Autowired Spring proxy both times).
        // NOTE (code review 2026-09-17, Patch #17): if you add a THIRD process() call to this test
        // method, add another releaseSchedulerLock call immediately before it too.
        releaseSchedulerLock("VideoDeletionOutboxProcessor_process");
        processor.process();

        VideoDeletionOutbox afterSecond = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo("COMPLETED");
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Patch): no test in this class called
     * {@code claimPendingBatch} directly and asserted the resulting stamp — the two tests below both
     * hand-stamp {@code claimed_at} via plain {@code save()}. {@code process_successfulBunnyDelete_...}
     * above exercises the full pipeline including the real claim query, but a batch-fetch that matched
     * zero rows (e.g. deleting {@code claimed_at = :now} from claimPendingBatch's SET clause) would
     * make that test fail on a COMPLETED assertion rather than showing the actual claim mechanism at
     * fault, so a direct repository-level check is still worth having.
     */
    @Test
    void claimPendingBatch_stampsClaimedAtSoFindClaimedBatchReturnsIt() {
        Video video = seedPurgedVideo("asset-claim-roundtrip");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-claim-roundtrip");

        Instant runClaimedAt = Instant.now();
        int claimed = outboxRepository.claimPendingBatch(runClaimedAt, 50);
        assertThat(claimed).isEqualTo(1);

        List<VideoDeletionOutbox> batch = outboxRepository.findClaimedBatch(runClaimedAt, 50);
        assertThat(batch).extracting(VideoDeletionOutbox::getId).containsExactly(row.getId());
        assertThat(batch.get(0).getClaimedAt()).isNotNull();
    }

    /**
     * skillars-deferred-123 code review 2026-09-18 (Patch): the sibling test below only ever asserted
     * {@code isZero()} — a predicate matching nothing under every circumstance also satisfies that.
     * This pins the actual positive recovery case: a genuinely stale claim IS reclaimed, and
     * claimed_at is nulled back out per the field's own invariant.
     */
    @Test
    void resetStaleClaimed_reclaimsGenuinelyStaleClaim() {
        Video video = seedPurgedVideo("asset-genuinely-stale");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-genuinely-stale");
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setStatus("CLAIMED");
            loaded.setClaimedAt(Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES));
            outboxRepository.save(loaded);
            return null;
        });

        int reset = outboxRepository.resetStaleClaimed(Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));

        assertThat(reset).as("a genuinely stale claim must be reclaimed").isEqualTo(1);
        VideoDeletionOutbox recovered = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getClaimedAt()).isNull();
    }

    /**
     * skillars-deferred-123 AC3 Task 9(a): a row eligible (next_retry_at) well before the stale
     * window, but claimed (claimed_at) only just now, must NOT be reclaimed — staleness is judged on
     * claim time, not the eligibility time it was previously keyed on.
     */
    @Test
    void resetStaleClaimed_doesNotReclaimRecentlyClaimedButLongBacklogedRow() {
        Video video = seedPurgedVideo("asset-backlogged");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-backlogged");
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loaded = outboxRepository.findById(row.getId()).orElseThrow();
            loaded.setStatus("CLAIMED");
            loaded.setNextRetryAt(Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS));
            loaded.setClaimedAt(Instant.now());
            outboxRepository.save(loaded);
            return null;
        });

        int reset = outboxRepository.resetStaleClaimed(Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));

        assertThat(reset).as("a genuinely-just-claimed row must not be reclaimed").isZero();
        VideoDeletionOutbox stillClaimed = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(stillClaimed.getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * skillars-deferred-123 AC3 Task 9(b): the actual double-processing scenario this AC closes — a
     * second instance's findClaimedBatch must not return rows claimed by a different run's
     * claimed_at, even though both rows share status = 'CLAIMED'.
     */
    @Test
    void findClaimedBatch_doesNotReturnRowsClaimedByADifferentRun() {
        Video videoA = seedPurgedVideo("asset-run-a");
        Video videoB = seedPurgedVideo("asset-run-b");
        VideoDeletionOutbox rowA = seedPendingOutboxRow(videoA.getId(), "asset-run-a");
        VideoDeletionOutbox rowB = seedPendingOutboxRow(videoB.getId(), "asset-run-b");
        Instant runAClaimedAt = Instant.now().minusSeconds(120);
        Instant runBClaimedAt = Instant.now();
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loadedA = outboxRepository.findById(rowA.getId()).orElseThrow();
            loadedA.setStatus("CLAIMED");
            loadedA.setClaimedAt(runAClaimedAt);
            outboxRepository.save(loadedA);

            VideoDeletionOutbox loadedB = outboxRepository.findById(rowB.getId()).orElseThrow();
            loadedB.setStatus("CLAIMED");
            loadedB.setClaimedAt(runBClaimedAt);
            outboxRepository.save(loadedB);
            return null;
        });

        List<VideoDeletionOutbox> runBBatch = outboxRepository.findClaimedBatch(runBClaimedAt, 50);

        assertThat(runBBatch).extracting(VideoDeletionOutbox::getId).containsExactly(rowB.getId());
    }

    private Video seedPurgedVideo(String providerAssetId) {
        Video v = new Video();
        v.setOwnerId("test-owner");
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("outbox-test.mp4");
        v.setOperationalState(OperationalState.PURGED);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return videoRepository.save(v);
    }

    private VideoDeletionOutbox seedPendingOutboxRow(UUID videoId, String bunnyVideoId) {
        VideoDeletionOutbox row = new VideoDeletionOutbox();
        row.setVideoId(videoId);
        row.setBunnyVideoId(bunnyVideoId);
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setTriggeredBy(LifecycleTrigger.USER_DELETION);
        return outboxRepository.save(row);
    }
}
