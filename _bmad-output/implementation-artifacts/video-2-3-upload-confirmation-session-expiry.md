# Story Video-2.3: Upload Confirmation & Session Expiry

Status: review

## Story

As a consuming application,
I want to confirm that an upload succeeded so the Video advances to PROCESSING and quota is committed, and I want abandoned sessions to expire automatically and release their quota reservations,
so that the upload pipeline completes reliably and quota reservations never become permanently stranded.

## Acceptance Criteria

**AC-1: Confirmation happy path**
- Given a client has successfully uploaded a video to Bunny.net
- When `VideoService.confirmUpload(videoId)` is called
- Then the most recent `UploadSession` for this video with `status = PENDING` is found; if session `status = EXPIRED`, throws `VideoSessionExpiredException` (HTTP 410)
- And within a single `@Transactional` block: sets `UploadSession.status = COMMITTED`, sets `Video.operationalState = PROCESSING`, calls `QuotaProvider.commit(reservationHandle)`
- And returns `ConfirmUploadResponse(videoId, operationalState=PROCESSING)`

**AC-2: Idempotent confirmation**
- Given `confirmUpload(videoId)` is called a second time for a Video already in PROCESSING (session COMMITTED)
- Then the duplicate call returns `ConfirmUploadResponse(videoId, PROCESSING)` without re-calling `QuotaProvider.commit()`

**AC-3: No active upload session**
- Given a `Video` record exists but has no associated `UploadSession`
- When `confirmUpload(videoId)` is called
- Then `VideoValidationException` (HTTP 422) is thrown with message "No active upload session exists for this video"
- And no NullPointerException or HTTP 500 is produced

**AC-4: Video not found**
- Given `videoId` does not exist
- When `confirmUpload(videoId)` is called
- Then `VideoNotFoundException` (HTTP 404) is thrown

**AC-5: Expiry scheduler — expired sessions cleanup**
- Given `app.video.upload.session-ttl-minutes` has elapsed since an `UploadSession` was created without confirmation
- When `UploadSessionExpiryScheduler.processExpired()` fires on its `@Scheduled(fixedDelayString)` interval
- Then it queries `upload_sessions WHERE status = 'PENDING' AND expires_at < NOW()` using `SELECT … FOR UPDATE SKIP LOCKED` (safe for multi-node deployment), limited to `app.video.reconciliation.batch-size`
- And for each expired session: calls `QuotaProvider.release(reservationHandle)` OUTSIDE any `@Transactional` boundary
- And if `QuotaProvider.release()` throws, the session is skipped and will be retried on the next scheduler run
- And if release succeeds: within a `@Transactional` block, calls `VideoLifecycleService.transitionOperationalState(videoId, FAILED)` (UPLOADING → FAILED) and sets `UploadSession.status = EXPIRED`

**AC-6: Integration test — confirmation flow**
- Given an integration test extending `BaseVideoIT`
- When the full upload + confirmation flow is exercised
- Then `confirmUpload()` transitions `Video` to `PROCESSING` and `UploadSession` to `COMMITTED`
- And a duplicate `confirmUpload()` call returns the same response without re-calling `QuotaProvider.commit()`
- And after simulating TTL expiry (setting `expiresAt` to past), calling `processExpired()` directly marks the session `EXPIRED`, sets the Video to `FAILED`, and calls `QuotaProvider.release()`

## Tasks / Subtasks

- [x] Task 1: Add `ConfirmUploadResponse` record to `platform.video.contract` (AC: 1, 2, 6)
  - [x] Create `ConfirmUploadResponse.java` record with fields `UUID videoId`, `OperationalState operationalState`
  - [x] Package: `com.softropic.skillars.platform.video.contract`

- [x] Task 2: Add `expirySchedulerDelayMs` to `VideoProperties.Upload` (AC: 5)
  - [x] Add `private long expirySchedulerDelayMs = 30000L;` to `VideoProperties.Upload` inner class
  - [x] Maps to `app.video.upload.expiry-scheduler-delay-ms` property key

- [x] Task 3: Add query methods to `UploadSessionRepository` (AC: 1, 2, 3, 5)
  - [x] Add `Optional<UploadSession> findFirstByVideoIdOrderByCreatedAtDesc(UUID videoId);` — for confirmUpload
  - [x] Add native SKIP LOCKED query for expiry scheduler (see Dev Notes for exact JPQL/native query)

- [x] Task 4: Create `VideoLifecycleService` in `platform.video.service` (AC: 5)
  - [x] Annotate `@Slf4j @Service @RequiredArgsConstructor`
  - [x] Inject `VideoRepository`
  - [x] Implement `@Transactional public Video transitionOperationalState(UUID videoId, OperationalState newState)` — Story 2.3 scope: handles UPLOADING → FAILED for expiry path; DELETED guard only; Story 3.1 expands full machine
  - [x] Do NOT implement `setAccessState()` or `isPlaybackEligible()` here — those belong to Story 3.1

- [x] Task 5: Add `confirmUpload(UUID videoId)` to existing `VideoService` (AC: 1, 2, 3, 4)
  - [x] Annotate the new method with `@Transactional`
  - [x] Do NOT add `@Transactional` to the class — only to `confirmUpload`; `initializeUpload` uses `TransactionTemplate` explicitly
  - [x] Load `Video` or throw `VideoNotFoundException`
  - [x] Load most-recent `UploadSession` via `findFirstByVideoIdOrderByCreatedAtDesc` or throw `VideoValidationException("No active upload session exists for this video")`
  - [x] If `COMMITTED` → return idempotent `ConfirmUploadResponse(videoId, video.getOperationalState())` immediately
  - [x] If `EXPIRED` → throw `VideoSessionExpiredException(session.getId())`
  - [x] Mutate and save: `session.setStatus(COMMITTED)`, `video.setOperationalState(PROCESSING)`, call `quotaProvider.commit(handle)`, return `ConfirmUploadResponse(videoId, PROCESSING)`
  - [x] MDC: `videoId`, `operation="confirm_upload"` — clear in `finally`

- [x] Task 6: Create `UploadSessionExpiryScheduler` in `platform.video.service` (AC: 5)
  - [x] Annotate `@Slf4j @Service @RequiredArgsConstructor`
  - [x] Inject: `UploadSessionRepository`, `VideoLifecycleService`, `QuotaProvider`, `VideoProperties`, `TransactionTemplate`
  - [x] Implement `@Scheduled(fixedDelayString = "${app.video.upload.expiry-scheduler-delay-ms:30000}") public void processExpired()`
  - [x] Fetch batch with SKIP LOCKED inside `transactionTemplate.execute()` (see Dev Notes)
  - [x] For each session: `quotaProvider.release()` outside any transaction; on release failure → `continue`; on success → `transactionTemplate.execute()` to set `EXPIRED` + call `VideoLifecycleService.transitionOperationalState(videoId, FAILED)`
  - [x] MDC: `uploadSessionId`, `videoId` — clear per-session in `finally`

- [x] Task 7: Write `VideoUploadConfirmationIT` extending `BaseVideoIT` (AC: 6)
  - [x] `@MockitoBean QuotaProvider quotaProvider` (for call verification)
  - [x] `@MockitoBean VideoProviderAdapter videoProviderAdapter` (for initializeUpload stub)
  - [x] Happy-path test: `initializeUpload` → `confirmUpload` → verify Video=PROCESSING, Session=COMMITTED, `quotaProvider.commit()` called once
  - [x] Idempotent test: second `confirmUpload` returns same response; `quotaProvider.commit()` called exactly once total
  - [x] Expiry test: after `initializeUpload`, set `session.expiresAt = Instant.now().minusSeconds(60)` via repo → call `scheduler.processExpired()` → verify Video=FAILED, Session=EXPIRED, `quotaProvider.release()` called once
  - [x] Quota release failure test: `quotaProvider.release()` throws → session remains PENDING, Video remains UPLOADING

## Dev Notes

### ConfirmUploadResponse — Exact Record Structure

```java
package com.softropic.skillars.platform.video.contract;

import com.softropic.skillars.platform.video.contract.OperationalState;
import java.util.UUID;

public record ConfirmUploadResponse(UUID videoId, OperationalState operationalState) {}
```

### VideoService.confirmUpload() — Full Implementation

```java
@Transactional
public ConfirmUploadResponse confirmUpload(UUID videoId) {
    MDC.put("operation", "confirm_upload");
    MDC.put("videoId", videoId.toString());
    try {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        UploadSession session = uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
            .orElseThrow(() -> new VideoValidationException("No active upload session exists for this video"));

        if (session.getStatus() == UploadSessionStatus.COMMITTED) {
            return new ConfirmUploadResponse(videoId, video.getOperationalState());
        }

        if (session.getStatus() == UploadSessionStatus.EXPIRED) {
            throw new VideoSessionExpiredException(session.getId());
        }

        // PENDING — advance atomically within this @Transactional method
        session.setStatus(UploadSessionStatus.COMMITTED);
        uploadSessionRepository.save(session);

        video.setOperationalState(OperationalState.PROCESSING);
        videoRepository.save(video);

        quotaProvider.commit(session.getReservationHandle());

        return new ConfirmUploadResponse(videoId, OperationalState.PROCESSING);
    } finally {
        MDC.remove("operation");
        MDC.remove("videoId");
    }
}
```

**@Transactional placement:** Add `@Transactional` ONLY to `confirmUpload` — NOT to the class. `initializeUpload` already manages its own transactions via `TransactionTemplate` and MUST NOT be wrapped in a Spring-managed transaction.

**QuotaProvider.commit() inside @Transactional:** `commit()` is called before the DB transaction commits. If `commit()` throws, the DB rolls back (session stays PENDING — correct). This is intentional per module design — the consuming app's `QuotaProvider` implementation is responsible for idempotency.

### VideoLifecycleService — Story 2.3 Scope

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLifecycleService {

    private final VideoRepository videoRepository;

    @Transactional
    public Video transitionOperationalState(UUID videoId, OperationalState newState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getOperationalState() == OperationalState.DELETED) {
            throw new TerminalStateViolationException(videoId, OperationalState.DELETED.name());
        }

        if (video.getOperationalState() == newState) {
            return video; // idempotent
        }

        video.setOperationalState(newState);
        return videoRepository.save(video);
    }
}
```

**Story 3.1 will add:** full valid-transition map (throw `TerminalStateViolationException` for invalid non-DELETED transitions), `setAccessState()`, and `isPlaybackEligible()`. Do NOT implement those here.

**Propagation note:** When `transitionOperationalState()` is called from inside `transactionTemplate.execute()` in the scheduler, its `@Transactional(REQUIRED)` joins the outer transaction — both the session update and the video state change commit atomically.

### UploadSessionRepository — New Query Methods

```java
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    Optional<UploadSession> findFirstByVideoIdOrderByCreatedAtDesc(UUID videoId);

    @Query(value = """
        SELECT * FROM main.upload_sessions
        WHERE status = 'PENDING' AND expires_at < NOW()
        ORDER BY expires_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UploadSession> findExpiredPendingForUpdate(@Param("limit") int limit);
}
```

**Why native query:** JPQL does not support `FOR UPDATE SKIP LOCKED`. Native query targeting `main.upload_sessions` (schema = "main", per `UploadSession` entity `@Table`). ✓

### UploadSessionExpiryScheduler — Full Implementation

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.config.VideoProperties;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.QuotaProvider;
import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
import com.softropic.skillars.platform.video.repo.UploadSession;
import com.softropic.skillars.platform.video.repo.UploadSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadSessionExpiryScheduler {

    private final UploadSessionRepository uploadSessionRepository;
    private final VideoLifecycleService videoLifecycleService;
    private final QuotaProvider quotaProvider;
    private final VideoProperties properties;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${app.video.upload.expiry-scheduler-delay-ms:30000}")
    public void processExpired() {
        int batchSize = properties.getReconciliation().getBatchSize();

        List<UploadSession> expired = Objects.requireNonNullElse(
            transactionTemplate.execute(status ->
                uploadSessionRepository.findExpiredPendingForUpdate(batchSize)),
            List.of());

        for (UploadSession session : expired) {
            MDC.put("uploadSessionId", session.getId().toString());
            MDC.put("videoId", session.getVideoId().toString());
            try {
                // AC-5: QuotaProvider.release() OUTSIDE any @Transactional boundary
                quotaProvider.release(session.getReservationHandle());
            } catch (Exception e) {
                log.warn("Quota release failed for session {}, retrying next cycle", session.getId(), e);
                MDC.remove("uploadSessionId");
                MDC.remove("videoId");
                continue;
            }
            try {
                transactionTemplate.execute(txStatus -> {
                    UploadSession s = uploadSessionRepository.findById(session.getId()).orElse(null);
                    if (s == null || s.getStatus() != UploadSessionStatus.PENDING) {
                        return null; // already processed (idempotent guard)
                    }
                    s.setStatus(UploadSessionStatus.EXPIRED);
                    uploadSessionRepository.save(s);
                    videoLifecycleService.transitionOperationalState(session.getVideoId(), OperationalState.FAILED);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to mark session {} as EXPIRED after quota release", session.getId(), e);
            } finally {
                MDC.remove("uploadSessionId");
                MDC.remove("videoId");
            }
        }
    }
}
```

**Why two `transactionTemplate.execute()` calls:**
1. First call fetches rows with `SKIP LOCKED` — rows are locked during fetch, then lock is released after the short transaction commits (giving us the list of IDs to process). This prevents two concurrent scheduler nodes from claiming the same batch at fetch time.
2. `quotaProvider.release()` runs outside any transaction (per AC-5).
3. Second call commits the EXPIRED + FAILED state transition atomically. The idempotent guard (`s.getStatus() != PENDING`) handles the rare race where another node already processed the session between step 1 and step 3.

**@EnableScheduling is already active** in `AsyncConfig.java` — no configuration change needed.

### VideoProperties — Upload Inner Class Update

```java
@Getter
@Setter
public static class Upload {
    private long maxBytes = 5368709120L;
    private List<String> allowedMimeTypes = List.of(
        "video/mp4", "video/quicktime", "video/webm", "video/x-msvideo"
    );
    private List<String> allowedFormats = List.of("MP4", "MOV", "WebM", "AVI");
    private int sessionTtlMinutes = 60;
    private long expirySchedulerDelayMs = 30000L;  // ADD THIS
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class RateLimit {
        private int requestsPerMinute = 10;
    }
}
```

### Integration Test — Key Patterns

```java
class VideoUploadConfirmationIT extends BaseVideoIT {

    @MockitoBean QuotaProvider quotaProvider;
    @MockitoBean VideoProviderAdapter videoProviderAdapter;
    @Autowired VideoService videoService;
    @Autowired UploadSessionExpiryScheduler expiryScheduler;
    @Autowired UploadSessionRepository uploadSessionRepository;
    @Autowired VideoRepository videoRepository;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong())).thenReturn("test-handle");
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenReturn(new UploadCredentials("guid-123", "http://bunny/tusupload"));
    }

    @Test
    void confirmUpload_happyPath_transitionsToProcessing() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-1", "vid.mp4", 1024L, "video/mp4"));

        ConfirmUploadResponse confirm = videoService.confirmUpload(init.videoId());

        assertThat(confirm.operationalState()).isEqualTo(OperationalState.PROCESSING);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.PROCESSING);
        UploadSession session = uploadSessionRepository.findById(init.uploadSessionId()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.COMMITTED);
        verify(quotaProvider).commit("test-handle");
    }

    @Test
    void confirmUpload_duplicate_idempotentNoDuplicateCommit() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-2", "vid.mp4", 1024L, "video/mp4"));

        videoService.confirmUpload(init.videoId());
        ConfirmUploadResponse second = videoService.confirmUpload(init.videoId());

        assertThat(second.operationalState()).isEqualTo(OperationalState.PROCESSING);
        verify(quotaProvider, times(1)).commit("test-handle");
    }

    @Test
    void processExpired_expiresSessionAndReleasesQuota() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-3", "vid.mp4", 1024L, "video/mp4"));

        // Simulate TTL expiry
        UploadSession session = uploadSessionRepository.findById(init.uploadSessionId()).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        uploadSessionRepository.save(session);

        expiryScheduler.processExpired();

        UploadSession expired = uploadSessionRepository.findById(init.uploadSessionId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.FAILED);
        verify(quotaProvider).release("test-handle");
    }

    @Test
    void processExpired_quotaReleaseFails_sessionRemainsUnchanged() {
        InitializeUploadResponse init = videoService.initializeUpload(
            new InitializeUploadRequest("owner-4", "vid.mp4", 1024L, "video/mp4"));

        UploadSession session = uploadSessionRepository.findById(init.uploadSessionId()).orElseThrow();
        session.setExpiresAt(Instant.now().minusSeconds(60));
        uploadSessionRepository.save(session);

        doThrow(new RuntimeException("quota service down")).when(quotaProvider).release(anyString());

        expiryScheduler.processExpired();

        UploadSession unchanged = uploadSessionRepository.findById(init.uploadSessionId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(UploadSessionStatus.PENDING);
        Video video = videoRepository.findById(init.videoId()).orElseThrow();
        assertThat(video.getOperationalState()).isEqualTo(OperationalState.UPLOADING);
    }
}
```

**Important:** `@MockitoBean` for both `QuotaProvider` and `VideoProviderAdapter` matches the pattern from `VideoUploadInitializationIT`. Do NOT use `@TestBean` here — `@MockitoBean` is sufficient since we don't need a custom anonymous implementation.

**Scheduler auto-run:** `processExpired()` is `@Scheduled` and will auto-fire during the test. This is safe because the auto-runs find no expired sessions (all sessions are PENDING with future `expiresAt`). The test explicitly sets `expiresAt` to the past before calling `processExpired()` directly.

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── contract/
│   └── ConfirmUploadResponse.java            ← NEW: record
├── config/
│   └── VideoProperties.java                  ← UPDATE: add expirySchedulerDelayMs to Upload
├── repo/
│   └── UploadSessionRepository.java          ← UPDATE: add 2 query methods
└── service/
    ├── VideoService.java                      ← UPDATE: add confirmUpload()
    ├── VideoLifecycleService.java             ← NEW: partial state machine (Story 3.1 completes)
    └── UploadSessionExpiryScheduler.java     ← NEW: @Scheduled expiry processor

src/test/java/com/softropic/skillars/platform/video/service/
└── VideoUploadConfirmationIT.java             ← NEW: integration test
```

### What Already Exists — Do NOT Recreate

- `VideoService` (existing), `VideoRepository`, `UploadSessionRepository` (base), `UploadSession` (with `@Setter`), `Video` (with `@Setter`)
- `VideoNotFoundException`, `VideoValidationException`, `VideoSessionExpiredException`, `TerminalStateViolationException` — all exist in `contract/exception/`
- `UploadSessionStatus.{PENDING, COMMITTED, EXPIRED}` — exists in `contract/`
- `OperationalState.{UPLOADING, PROCESSING, READY, FAILED, DELETED}` — exists in `contract/`
- `QuotaProvider` interface — exists in `contract/`
- `VideoApiAdvice` handlers for all exceptions — exists, no changes needed
- `@EnableScheduling` — active in `AsyncConfig.java`, no changes needed
- `BaseVideoIT`, `VideoUploadInitializationIT` — reference for test patterns

### Architecture Compliance

- `ConfirmUploadResponse` → `platform.video.contract` ✓ (public module API — DTOs/records)
- `VideoLifecycleService` → `platform.video.service` ✓ (business logic in platform)
- `UploadSessionExpiryScheduler` → `platform.video.service` ✓ (lifecycle scheduler belongs in platform)
- `@Scheduled` workers live in `platform.video.service` per project-context.md §Background Jobs ✓
- No new `infrastructure.*` imports in platform classes ✓
- `QuotaProvider.release()` called outside `@Transactional` in scheduler matches AC-5 ✓

### Project Context Rules (Must Follow)

- Java 17: `record` for DTOs — `ConfirmUploadResponse` must be a `record`
- `@RequiredArgsConstructor` (never `@Autowired`) for all new services
- `@Slf4j` on all new services
- MDC: always clear all MDC keys in `finally` block
- No `@Transactional` at class level on `VideoService` — only on `confirmUpload()` method
- AssertJ `assertThat()` for all test assertions
- No Mockito `.when()` chains in tests without `@MockitoBean`

### References

- Story 2.2 (previous): `_bmad-output/implementation-artifacts/video-2-2-upload-initialization-quota-management.md` — `@Setter` on entities, `TransactionTemplate` two-phase pattern, `@MockitoBean` test pattern
- DeletionSchedulerService pattern: `src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java`
- `@EnableScheduling`: `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java`
- `VideoApiAdvice`: `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java` — no changes needed
- `TerminalStateViolationException`: `src/main/java/com/softropic/skillars/platform/video/contract/exception/TerminalStateViolationException.java` — constructor takes `(UUID videoId, String currentState)`
- `VideoProperties`: `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java`
- `BaseVideoIT`: `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`
- Epic 2 Story 2.3 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 2.3: Upload Confirmation & Session Expiry"
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

- Test failure: `processExpired_expiresSessionAndReleasesQuota` failed with 2 release() calls instead of 1. Root cause: test data bleeding — the quota-release-failure test left a PENDING expired session in the DB, which the next test's `processExpired()` also picked up. Fixed by adding `uploadSessionRepository.deleteAll()` / `videoRepository.deleteAll()` in `@BeforeEach`.
- Also overrode `app.video.upload.expiry-scheduler-delay-ms=3600000` in `application-test.yaml` as a defensive measure against the `@Scheduled` auto-run interfering with tests.

### Completion Notes List

- Implemented `ConfirmUploadResponse` record in `platform.video.contract` per Java 17 record convention.
- Added `expirySchedulerDelayMs = 30000L` field to `VideoProperties.Upload`; maps to `app.video.upload.expiry-scheduler-delay-ms`.
- Extended `UploadSessionRepository` with `findFirstByVideoIdOrderByCreatedAtDesc` (derived query) and `findExpiredPendingForUpdate` (native `FOR UPDATE SKIP LOCKED` query targeting `main.upload_sessions`).
- Created `VideoLifecycleService` with Story 2.3 scope only: DELETED guard + idempotent `transitionOperationalState(UPLOADING → FAILED)`. Full state machine deferred to Story 3.1.
- Added `confirmUpload()` to `VideoService` with `@Transactional` on the method only (class remains unannotated). Handles COMMITTED (idempotent), EXPIRED (throw 410), and PENDING (commit atomically). MDC cleared in `finally`.
- Created `UploadSessionExpiryScheduler` with two-phase `TransactionTemplate` pattern: first TX fetches expired batch with SKIP LOCKED, then `quotaProvider.release()` runs outside any TX, then second TX commits EXPIRED+FAILED state atomically. Idempotent guard re-checks session status before the second TX.
- Integration test `VideoUploadConfirmationIT` covers all 4 AC-6 scenarios: happy path, idempotent duplicate, expiry flow, and quota-release-failure isolation.
- All 251 tests pass (0 regressions).

### File List

- `src/main/java/com/softropic/skillars/platform/video/contract/ConfirmUploadResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/config/VideoProperties.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java` (new)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadConfirmationIT.java` (new)
- `src/test/resources/application-test.yaml` (modified)

## Change Log

- Implemented Story Video-2.3 (Upload Confirmation & Session Expiry) — all 7 tasks complete, 4 integration tests passing, 251 total tests passing (Date: 2026-05-29)
