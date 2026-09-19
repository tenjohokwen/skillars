package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.session.repo.DrillVideoRefRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class VideoDeletionOutboxProcessorIT extends BaseVideoIT {

    @MockitoBean VideoProviderAdapter videoProviderAdapter;

    // skillars-deferred-124 AC2 Task 5: needed to force a failure OUTSIDE the already-mocked
    // deleteAsset path (the only seam this class had before), so the new loop-level per-row isolation
    // guard (VideoDeletionOutboxProcessor.process()'s outer try/catch) is actually exercised rather
    // than the pre-existing inner try/catch around deleteAsset+completeRowWithNullAsset. A distinct
    // ContextCustomizer set from every other class already using BaseVideoIT — per
    // AccountDeletionCascadeIT's own identical precedent (adding a second @MockitoBean/@MockitoSpyBean
    // to fork a new Spring test context), this forks a new context; pre-authorised as this story's own
    // cost (see .github/scripts/assert-context-count.sh's ceiling history).
    @MockitoSpyBean DrillVideoRefRepository drillVideoRefRepository;

    // skillars-deferred-124 code review 2026-09-19 (Patch): changed from @Autowired to
    // @MockitoSpyBean so handleFailure_itselfThrows_... below can force failClaimed to throw, without
    // adding a new ConfigService mock (which would need its own bean override). Same test class
    // already forks its own dedicated Spring context for the drillVideoRefRepository spy above — adding
    // this second override to that SAME class does not fork an additional context (no other class in
    // the suite shares either combination), so this does not need its own assert-context-count.sh
    // ceiling bump.
    @MockitoSpyBean VideoDeletionOutboxRepository outboxRepository;

    @Autowired VideoDeletionOutboxProcessor processor;
    @Autowired VideoRepository videoRepository;
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
        // skillars-deferred-124 AC4 Task 7: claimed_by must clear too, same invariant.
        assertThat(updated.getClaimedBy()).as("COMPLETED must clear claimed_by too").isNull();

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
        // skillars-deferred-124 AC4 Task 7: claimed_by must clear too, same invariant.
        assertThat(updated.getClaimedBy()).as("PENDING backoff must clear claimed_by too").isNull();
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
        // skillars-deferred-124 AC4 Task 7: claimed_by must clear too, same invariant.
        assertThat(updated.getClaimedBy()).as("DEAD must clear claimed_by too").isNull();
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
     * skillars-deferred-124 AC2 Task 5. Proves the new loop-level guard in
     * {@code VideoDeletionOutboxProcessor.process()} — not the pre-existing inner try/catch around
     * {@code deleteAsset}+{@code completeRowWithNullAsset}, which this failure is deliberately outside
     * of ({@code drillVideoRefRepository.findByVideoId} runs before that pair, at :169). Before AC2, an
     * exception here propagated out of {@code processRow}, out of the loop, and abandoned every row
     * after the failing one.
     */
    @Test
    void process_middleRowThrowsOutsideInnerTryCatch_isolatesBatchAndReachesFailureBookkeeping() {
        Video videoA = seedPurgedVideo("asset-iso-a");
        Video videoMiddle = seedPurgedVideo("asset-iso-middle");
        Video videoC = seedPurgedVideo("asset-iso-c");
        // Explicit, strictly increasing next_retry_at so findClaimedBatch's ORDER BY next_retry_at ASC
        // deterministically returns A, middle, C in that order — not relying on real-time ordering
        // between three back-to-back save() calls.
        VideoDeletionOutbox rowA = seedPendingOutboxRowAt(videoA.getId(), "asset-iso-a", Instant.now().minusSeconds(30));
        VideoDeletionOutbox rowMiddle = seedPendingOutboxRowAt(videoMiddle.getId(), "asset-iso-middle", Instant.now().minusSeconds(20));
        VideoDeletionOutbox rowC = seedPendingOutboxRowAt(videoC.getId(), "asset-iso-c", Instant.now().minusSeconds(10));

        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-iso-a"));
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-iso-c"));
        doThrow(new RuntimeException("unexpected drill-ref lookup failure"))
            .when(drillVideoRefRepository).findByVideoId(eq(videoMiddle.getId()));

        processor.process();

        VideoDeletionOutbox updatedA = outboxRepository.findById(rowA.getId()).orElseThrow();
        VideoDeletionOutbox updatedMiddle = outboxRepository.findById(rowMiddle.getId()).orElseThrow();
        VideoDeletionOutbox updatedC = outboxRepository.findById(rowC.getId()).orElseThrow();

        assertThat(updatedA.getStatus())
            .as("the row before the failing one must still complete normally").isEqualTo("COMPLETED");
        assertThat(updatedC.getStatus())
            .as("the row after the failing one must still complete normally — proves batch isolation")
            .isEqualTo("COMPLETED");

        assertThat(updatedMiddle.getAttempts())
            .as("the failing row must have genuinely reached handleFailure via the new outer guard, "
                + "not merely avoided crashing the loop")
            .isEqualTo(1);
        assertThat(updatedMiddle.getStatus()).isEqualTo("PENDING");
        assertThat(updatedMiddle.getLastError()).contains("unexpected drill-ref lookup failure");
        assertThat(updatedMiddle.getClaimedAt())
            .as("PENDING backoff must clear claimed_at, same invariant as every other failure path")
            .isNull();
        // skillars-deferred-124 code review 2026-09-19 (Patch): every other failure path in this suite
        // asserts getClaimedBy() alongside getClaimedAt() — this test had been missing it.
        assertThat(updatedMiddle.getClaimedBy())
            .as("PENDING backoff must clear claimed_by too, same invariant as every other failure path")
            .isNull();

        verify(drillVideoRefRepository).findByVideoId(eq(videoA.getId()));
        verify(drillVideoRefRepository).findByVideoId(eq(videoC.getId()));
    }

    /**
     * skillars-deferred-124 AC2 Task 6 mutation check (documented, not a test): temporarily removing
     * {@code VideoDeletionOutboxProcessor.process()}'s new outer try/catch and re-running
     * {@code process_middleRowThrowsOutsideInnerTryCatch_isolatesBatchAndReachesFailureBookkeeping}
     * makes it fail — {@code updatedC} stays PENDING (never reached) because the exception from
     * {@code findByVideoId} propagates out of the loop entirely. Confirmed by hand during
     * implementation; not committed as a permanent mutant test.
     */

    /**
     * skillars-deferred-124 code review 2026-09-19 (Patch): proves the {@code catch (Exception inner)}
     * branch in {@code process()}'s outer guard — {@code handleFailure} itself throwing must not abort
     * the batch, and the row it was handling must be left {@code CLAIMED} (recovered later by the next
     * stale-claim sweep) rather than partially updated. Forces the throw via the repository's own
     * {@code failClaimed} rather than adding a new {@code ConfigService} mock/context — same effective
     * failure point (a throw inside {@code handleFailure}'s {@code transactionTemplate.execute}, before
     * the transaction commits) without a further bean-override/context cost.
     */
    @Test
    void handleFailure_itselfThrows_stillIsolatesBatchAndLeavesRowClaimed() {
        Video videoA = seedPurgedVideo("asset-hf-a");
        Video videoMiddle = seedPurgedVideo("asset-hf-middle");
        Video videoC = seedPurgedVideo("asset-hf-c");
        VideoDeletionOutbox rowA = seedPendingOutboxRowAt(videoA.getId(), "asset-hf-a", Instant.now().minusSeconds(30));
        VideoDeletionOutbox rowMiddle = seedPendingOutboxRowAt(videoMiddle.getId(), "asset-hf-middle", Instant.now().minusSeconds(20));
        VideoDeletionOutbox rowC = seedPendingOutboxRowAt(videoC.getId(), "asset-hf-c", Instant.now().minusSeconds(10));

        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-hf-a"));
        doNothing().when(videoProviderAdapter).deleteAsset(eq("asset-hf-c"));
        doThrow(new RuntimeException("bunny down")).when(videoProviderAdapter).deleteAsset(eq("asset-hf-middle"));
        // handleFailure's own transactionTemplate.execute throws before reaching its return statement —
        // the same effective gap as configService.getBoundedLong throwing.
        doThrow(new RuntimeException("db write boom"))
            .when(outboxRepository).failClaimed(eq(rowMiddle.getId()), any(), any(), anyInt(), any(), any());

        assertThatCode(() -> processor.process())
            .as("a failure inside handleFailure itself must not abort the batch")
            .doesNotThrowAnyException();

        VideoDeletionOutbox updatedA = outboxRepository.findById(rowA.getId()).orElseThrow();
        VideoDeletionOutbox updatedC = outboxRepository.findById(rowC.getId()).orElseThrow();
        assertThat(updatedA.getStatus())
            .as("the row before the failing one must still complete normally").isEqualTo("COMPLETED");
        assertThat(updatedC.getStatus())
            .as("the row after the failing one must still complete normally — proves batch isolation")
            .isEqualTo("COMPLETED");

        VideoDeletionOutbox updatedMiddle = outboxRepository.findById(rowMiddle.getId()).orElseThrow();
        assertThat(updatedMiddle.getStatus())
            .as("handleFailure's own DB write threw before persisting — row must remain CLAIMED, "
                + "recovered later by the next stale-claim sweep, not left in an inconsistent state")
            .isEqualTo("CLAIMED");
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
    void claimPendingBatch_stampsClaimedAtAndClaimedBySoFindClaimedBatchReturnsIt() {
        Video video = seedPurgedVideo("asset-claim-roundtrip");
        VideoDeletionOutbox row = seedPendingOutboxRow(video.getId(), "asset-claim-roundtrip");

        Instant runClaimedAt = Instant.now();
        UUID runId = UUID.randomUUID();
        int claimed = outboxRepository.claimPendingBatch(runClaimedAt, runId, 50);
        assertThat(claimed).isEqualTo(1);

        // skillars-deferred-124 AC4: findClaimedBatch now keys on claimed_by (runId), not
        // claimed_at-exact-equality.
        List<VideoDeletionOutbox> batch = outboxRepository.findClaimedBatch(runId, 50);
        assertThat(batch).extracting(VideoDeletionOutbox::getId).containsExactly(row.getId());
        assertThat(batch.get(0).getClaimedAt()).isNotNull();
        assertThat(batch.get(0).getClaimedBy()).isEqualTo(runId);
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
            loaded.setClaimedBy(UUID.randomUUID());
            outboxRepository.save(loaded);
            return null;
        });

        int reset = outboxRepository.resetStaleClaimed(Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));

        assertThat(reset).as("a genuinely stale claim must be reclaimed").isEqualTo(1);
        VideoDeletionOutbox recovered = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("PENDING");
        assertThat(recovered.getClaimedAt()).isNull();
        // skillars-deferred-124 AC4 Task 7: claimed_by must also clear on this transition, mirroring
        // claimed_at's own invariant.
        assertThat(recovered.getClaimedBy()).isNull();
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
     * second instance's findClaimedBatch must not return rows claimed by a different run, even though
     * both rows share status = 'CLAIMED'. skillars-deferred-124 AC4: identity now keyed on claimed_by
     * (a dedicated UUID token) rather than claimed_at-exact-equality — this test moves with it.
     */
    @Test
    void findClaimedBatch_doesNotReturnRowsClaimedByADifferentRun() {
        Video videoA = seedPurgedVideo("asset-run-a");
        Video videoB = seedPurgedVideo("asset-run-b");
        VideoDeletionOutbox rowA = seedPendingOutboxRow(videoA.getId(), "asset-run-a");
        VideoDeletionOutbox rowB = seedPendingOutboxRow(videoB.getId(), "asset-run-b");
        UUID runAId = UUID.randomUUID();
        UUID runBId = UUID.randomUUID();
        transactionTemplate.execute(status -> {
            VideoDeletionOutbox loadedA = outboxRepository.findById(rowA.getId()).orElseThrow();
            loadedA.setStatus("CLAIMED");
            loadedA.setClaimedAt(Instant.now().minusSeconds(120));
            loadedA.setClaimedBy(runAId);
            outboxRepository.save(loadedA);

            VideoDeletionOutbox loadedB = outboxRepository.findById(rowB.getId()).orElseThrow();
            loadedB.setStatus("CLAIMED");
            loadedB.setClaimedAt(Instant.now());
            loadedB.setClaimedBy(runBId);
            outboxRepository.save(loadedB);
            return null;
        });

        List<VideoDeletionOutbox> runBBatch = outboxRepository.findClaimedBatch(runBId, 50);

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

    private VideoDeletionOutbox seedPendingOutboxRowAt(UUID videoId, String bunnyVideoId, Instant nextRetryAt) {
        VideoDeletionOutbox row = new VideoDeletionOutbox();
        row.setVideoId(videoId);
        row.setBunnyVideoId(bunnyVideoId);
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setNextRetryAt(nextRetryAt);
        row.setTriggeredBy(LifecycleTrigger.USER_DELETION);
        return outboxRepository.save(row);
    }
}
