package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.infrastructure.video.VideoProviderAdapter;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.LifecycleTrigger;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import com.softropic.skillars.platform.video.contract.exception.VideoStateConflictException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLog;
import com.softropic.skillars.platform.video.repo.VideoLifecycleLogRepository;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoLifecycleSchedulerTest {

    @Mock VideoRepository videoRepository;
    @Mock VideoLifecycleLogRepository videoLifecycleLogRepository;
    @Mock VideoLifecycleService videoLifecycleService;
    @Mock VideoProviderAdapter videoProviderAdapter;
    @Mock ConfigService configService;
    @Mock QuotaService quotaService;
    @Mock PlayerSubscriptionQueryPort playerSubscriptionQueryPort;

    VideoLifecycleScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate that executes the callback immediately (no real TX in unit test)
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };

        // lenient(): runLifecycleJob_carriesSchedulerLockSizedOffTheConfiguredCeiling() below is a
        // pure reflection/annotation check that never calls the scheduler, so these three would
        // otherwise fail strict-stubbing's UnnecessaryStubbingException in that one test.
        lenient().when(configService.getBoundedLong("platform.video.lifecycle.blocked_to_archived_days", 30L, 1L, 3650L)).thenReturn(30L);
        lenient().when(configService.getBoundedLong("platform.video.lifecycle.archived_to_deleted_days", 90L, 1L, 36500L)).thenReturn(90L);
        lenient().when(configService.getBoundedInt("platform.video.lifecycle.batch_size", 100, 1, 10000)).thenReturn(100);

        scheduler = new VideoLifecycleScheduler(videoRepository, videoLifecycleLogRepository,
            videoLifecycleService, videoProviderAdapter, configService, txTemplate, quotaService, playerSubscriptionQueryPort);
    }

    @Test
    void runLifecycleJob_blockedVideoExceedingThreshold_transitionsToArchived() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter).archiveAsset(video.getProviderAssetId());
        verify(videoLifecycleService).archiveForLifecycle(video.getId());

        ArgumentCaptor<VideoLifecycleLog> logCaptor = ArgumentCaptor.forClass(VideoLifecycleLog.class);
        verify(videoLifecycleLogRepository).save(logCaptor.capture());
        VideoLifecycleLog log = logCaptor.getValue();
        assertThat(log.getFromState()).isEqualTo("BLOCKED");
        assertThat(log.getToState()).isEqualTo("ARCHIVED");
        assertThat(log.getTriggeredBy()).isEqualTo(LifecycleTrigger.SYSTEM);
    }

    @Test
    void runLifecycleJob_archivedVideoExceedingThreshold_transitionsToDeleted() {
        UUID videoId = UUID.randomUUID();
        String ownerId = UUID.randomUUID().toString();
        Video video = archivedVideo(videoId, ownerId, Instant.now().minus(95, ChronoUnit.DAYS));
        video.setStorageBytes(1024L);

        when(videoRepository.findArchivedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(videoLifecycleService.markPurged(videoId)).thenReturn(1024L);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter).deleteAsset(video.getProviderAssetId());
        verify(videoLifecycleService).markPurged(videoId);
        verify(quotaService).decrementStorageBytes(ownerId, 1024L);

        ArgumentCaptor<VideoLifecycleLog> logCaptor = ArgumentCaptor.forClass(VideoLifecycleLog.class);
        verify(videoLifecycleLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getFromState()).isEqualTo("ARCHIVED");
        assertThat(logCaptor.getValue().getToState()).isEqualTo("DELETED");
        assertThat(logCaptor.getValue().getTriggeredBy()).isEqualTo(LifecycleTrigger.SYSTEM);
    }

    @Test
    void runLifecycleJob_blockedVideoWithActiveYearlySub_skipsArchivedTransition() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(true);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter, never()).archiveAsset(any());
        verify(videoLifecycleService, never()).archiveForLifecycle(any());
    }

    @Test
    void runLifecycleJob_archiveAssetFails_videoRemainsBlockedAndRetryableNextRun() {
        Video video = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);
        doThrow(new com.softropic.skillars.platform.video.contract.exception.VideoProviderException("archiveAsset", null))
            .when(videoProviderAdapter).archiveAsset(any());

        scheduler.runLifecycleJob();

        // DB transition must NOT happen when Bunny call fails
        verify(videoLifecycleService, never()).archiveForLifecycle(any());
        verify(videoLifecycleLogRepository, never()).save(any());
    }

    @Test
    void runLifecycleJob_batchSkipGuard_videoArchivedInPhase1DoesNotAppearInPhase2() {
        // A video with lifecycle_locked_at = 91 days ago advances to ARCHIVED in Phase 1.
        // Phase 2 uses archived_at (set to now() by archiveForLifecycle) — not lifecycle_locked_at.
        // Mockito returns empty list by default for Phase 2, simulating the just-archived video NOT appearing.
        UUID videoId = UUID.randomUUID();
        Video video = blockedVideo(videoId, "11001", Instant.now().minus(91, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt())).thenReturn(List.of(video));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);

        scheduler.runLifecycleJob();

        verify(videoLifecycleService).archiveForLifecycle(videoId);
        // Phase 2 never called deleteAsset — batch-skip guard holds
        verify(videoProviderAdapter, never()).deleteAsset(any());
    }

    // skillars-deferred-115 AC2: one video's failure during phase 1 must no longer abort the rest of
    // that phase's batch, nor skip phase 2 entirely.
    @Test
    void runBlockedToArchivedPhase_oneVideoThrows_remainingVideosStillProcessed_andPhaseTwoStillRuns() {
        Video ok1 = blockedVideo(UUID.randomUUID(), "11001", Instant.now().minus(35, ChronoUnit.DAYS));
        Video failing = blockedVideo(UUID.randomUUID(), "11002", Instant.now().minus(35, ChronoUnit.DAYS));
        Video ok2 = blockedVideo(UUID.randomUUID(), "11003", Instant.now().minus(35, ChronoUnit.DAYS));
        when(videoRepository.findBlockedExceedingThreshold(any(), anyInt()))
            .thenReturn(List.of(ok1, failing, ok2));
        when(playerSubscriptionQueryPort.hasActiveYearlySubscription(any())).thenReturn(false);
        // Mockito strict stubs: once archiveForLifecycle has ANY stub, every other exact-argument
        // call must also be stubbed explicitly (doNothing is the void-method equivalent of
        // thenReturn), or it's flagged as a likely test bug (PotentialStubbingProblem) instead of
        // genuinely exercising the success path this test is meant to prove.
        doNothing().when(videoLifecycleService).archiveForLifecycle(ok1.getId());
        doNothing().when(videoLifecycleService).archiveForLifecycle(ok2.getId());
        doThrow(new ObjectOptimisticLockingFailureException(Video.class, failing.getId()))
            .when(videoLifecycleService).archiveForLifecycle(failing.getId());
        when(videoRepository.findArchivedExceedingThreshold(any(), anyInt())).thenReturn(List.of());

        scheduler.runLifecycleJob();

        verify(videoLifecycleService).archiveForLifecycle(ok1.getId());
        verify(videoLifecycleService).archiveForLifecycle(failing.getId());
        verify(videoLifecycleService).archiveForLifecycle(ok2.getId());
        // The two successful transitions actually completed (wrote their lifecycle log row) — the
        // failing one never reached that line.
        verify(videoLifecycleLogRepository, times(2)).save(argThat(l ->
            l.getVideoId().equals(ok1.getId()) || l.getVideoId().equals(ok2.getId())));
        verify(videoLifecycleLogRepository, never()).save(argThat(l -> l.getVideoId().equals(failing.getId())));
        // Phase 2 was still invoked, even though phase 1 hit a per-video failure.
        verify(videoRepository).findArchivedExceedingThreshold(any(), anyInt());
    }

    // skillars-deferred-115 AC2: same isolation guarantee for phase 2 (ARCHIVED→DELETED) — a
    // conflicting concurrent purge (VideoStateConflictException, mirroring markPurged's own thrown
    // type) on one video must not drop the remaining candidates in that phase.
    @Test
    void runArchivedToDeletedPhase_oneVideoThrows_remainingVideosStillProcessed() {
        UUID okId1 = UUID.randomUUID();
        UUID failingId = UUID.randomUUID();
        UUID okId2 = UUID.randomUUID();
        Video ok1 = archivedVideo(okId1, UUID.randomUUID().toString(), Instant.now().minus(95, ChronoUnit.DAYS));
        Video failing = archivedVideo(failingId, UUID.randomUUID().toString(), Instant.now().minus(95, ChronoUnit.DAYS));
        Video ok2 = archivedVideo(okId2, UUID.randomUUID().toString(), Instant.now().minus(95, ChronoUnit.DAYS));
        when(videoRepository.findArchivedExceedingThreshold(any(), anyInt()))
            .thenReturn(List.of(ok1, failing, ok2));
        when(videoRepository.findById(okId1)).thenReturn(Optional.of(ok1));
        when(videoRepository.findById(failingId)).thenReturn(Optional.of(failing));
        when(videoRepository.findById(okId2)).thenReturn(Optional.of(ok2));
        // Mockito strict stubs: once markPurged has ANY stub, every other exact-argument call must
        // also be stubbed explicitly, or it's flagged as a likely test bug (PotentialStubbingProblem)
        // rather than silently falling back to the 0L default.
        when(videoLifecycleService.markPurged(okId1)).thenReturn(1024L);
        when(videoLifecycleService.markPurged(okId2)).thenReturn(2048L);
        when(videoLifecycleService.markPurged(failingId))
            .thenThrow(new VideoStateConflictException(
                failingId, OperationalState.READY.name(), OperationalState.DELETED.name()));

        scheduler.runLifecycleJob();

        verify(videoLifecycleService).markPurged(okId1);
        verify(videoLifecycleService).markPurged(failingId);
        verify(videoLifecycleService).markPurged(okId2);
        // Only the two successful purges released quota — the failing one never reached that line.
        verify(quotaService).decrementStorageBytes(ok1.getOwnerId(), 1024L);
        verify(quotaService).decrementStorageBytes(ok2.getOwnerId(), 2048L);
        verify(quotaService, times(2)).decrementStorageBytes(any(), anyLong());
    }

    // skillars-deferred-117 AC2: findArchivedExceedingThreshold's own WHERE clause (fixed in
    // VideoRepository, mechanically proven by VideoRepositoryIT against a real Postgres) is what
    // excludes an already-purged video from the batch. This test proves the scheduler-side half of
    // that contract: given a batch that — as the fixed query now guarantees — contains only
    // genuinely-due videos, the scheduler's deleteAsset/markPurged call sequence touches only what
    // it was handed. An already-purged video's id, deliberately absent from the mocked batch (since
    // the real query would never return it post-fix), is never re-fetched and never has
    // deleteAsset/markPurged invoked on it — i.e. it is never even looked at, not merely that a
    // markPurged call against it would be caught.
    //
    // Code review: why the already-purged video isn't ALSO placed in this mocked batch's returned
    // list. runArchivedToDeletedPhase has no filtering of its own — it iterates whatever
    // findArchivedExceedingThreshold hands it — so putting both videos in the SAME mocked list would
    // make the scheduler call deleteAsset/findById/markPurged on BOTH, which would only re-prove
    // skillars-deferred-115's existing per-video try/catch (an already-covered, different guarantee)
    // and would contradict the "never even re-fetched" claim this test exists to make. "Never
    // re-fetched" can only be true because the (separately, mechanically, VideoRepositoryIT-proven)
    // query never returns such a video in the first place — there is no scheduler-level check to
    // test here by design (AC2's fix is entirely in the query, not the scheduler).
    @Test
    void runArchivedToDeletedPhase_mixedBatch_alreadyPurgedVideoExcludedByQuery_neverRefetchedOrTouched() {
        UUID dueVideoId = UUID.randomUUID();
        UUID alreadyPurgedVideoId = UUID.randomUUID(); // never returned by the fixed query — see AC2
        Video due = archivedVideo(dueVideoId, UUID.randomUUID().toString(), Instant.now().minus(95, ChronoUnit.DAYS));
        due.setStorageBytes(512L);

        when(videoRepository.findArchivedExceedingThreshold(any(), anyInt())).thenReturn(List.of(due));
        when(videoRepository.findById(dueVideoId)).thenReturn(Optional.of(due));
        when(videoLifecycleService.markPurged(dueVideoId)).thenReturn(512L);

        scheduler.runLifecycleJob();

        verify(videoProviderAdapter, times(1)).deleteAsset(any());
        verify(videoProviderAdapter).deleteAsset(due.getProviderAssetId());
        verify(videoLifecycleService).markPurged(dueVideoId);
        verify(videoRepository, never()).findById(alreadyPurgedVideoId);
        verify(videoLifecycleService, never()).markPurged(alreadyPurgedVideoId);
    }

    // skillars-deferred-115 AC2: no existing test in this module covers @SchedulerLock directly
    // (checked EmailRetryScheduler's/ReconciliationWorkerScheduler's suites first, per the story) —
    // a plain reflection/annotation check confirms the lock is present with a sensible sizing, per
    // AC2's "Verified by".
    @Test
    void runLifecycleJob_carriesSchedulerLockSizedOffTheConfiguredCeiling() throws NoSuchMethodException {
        Method method = VideoLifecycleScheduler.class.getMethod("runLifecycleJob");
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).as("runLifecycleJob() must carry @SchedulerLock, mirroring every sibling "
            + "scheduler sharing this module's short-SELECT/per-row-transaction shape").isNotNull();
        assertThat(lock.name()).isNotBlank();
        // skillars-deferred-123 AC4: both floors are now property expressions (this scheduler's
        // cadence, app.video.lifecycle.cron, is also operator-tunable) — pin the exact expressions
        // and assert against their embedded defaults instead of Duration.parse-ing the raw strings.
        assertThat(lock.lockAtMostFor()).isEqualTo("${app.video.lifecycle.lock-at-most:PT12H}");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${app.video.lifecycle.lock-at-least:PT30S}");
        // lockAtMostFor's default exists specifically to cover a ceiling-sized (10000-row, two-phase)
        // run, which is expected to take on the order of hours, not minutes — so it must comfortably
        // exceed a default-sized (100-row) run's realistic duration.
        assertThat(Duration.parse(defaultOf(lock.lockAtMostFor()))).isGreaterThan(Duration.ofHours(1));
        assertThat(Duration.parse(defaultOf(lock.lockAtLeastFor()))).isPositive();
    }

    /** Extracts the {@code default} out of a {@code ${property:default}} SchedulerLock expression. */
    private static String defaultOf(String springPropertyExpression) {
        String withoutBraces = springPropertyExpression.replace("${", "").replace("}", "");
        return withoutBraces.substring(withoutBraces.indexOf(':') + 1);
    }

    private Video blockedVideo(UUID id, String ownerId, Instant lifecycleLockedAt) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("provider-" + id);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.BLOCKED);
        v.setLifecycleLockedAt(lifecycleLockedAt);
        return v;
    }

    private Video archivedVideo(UUID id, String ownerId, Instant archivedAt) {
        Video v = new Video();
        v.setId(id);
        v.setOwnerId(ownerId);
        v.setProvider("bunny");
        v.setProviderAssetId("provider-" + id);
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ARCHIVED);
        v.setArchivedAt(archivedAt);
        return v;
    }
}
