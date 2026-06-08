# Story Video-3.1: Video State Machine & FAILED Retry

Status: review

## Story

As a consuming application,
I want a fully enforced video state machine with two orthogonal state dimensions and support for restarting failed uploads on the same video record,
So that I can reliably manage video lifecycle and recover failed uploads without creating new video entities or breaking downstream references.

## Acceptance Criteria

**AC-1: Operational state transition validation**
- Given a `Video` record exists
- When `VideoLifecycleService.transitionOperationalState(videoId, newState)` is called
- Then the following transitions are **valid**: UPLOADING → PROCESSING, UPLOADING → FAILED (expiry path), PROCESSING → READY, PROCESSING → FAILED, FAILED → UPLOADING (retry path)
- And any transition where the current state is `DELETED` throws `TerminalStateViolationException` (HTTP 409)
- And any invalid transition (e.g., READY → UPLOADING, PROCESSING → UPLOADING) throws `TerminalStateViolationException` (HTTP 409)
- And an idempotent call with the same current state as newState is a no-op (returns video unchanged) — this preserves the existing behavior used by the expiry scheduler's UPLOADING → FAILED path when the video is already FAILED

**AC-2: Access state management**
- Given `VideoLifecycleService.setAccessState(videoId, newAccessState)` is called
- When the video is in `operationalState = DELETED`
- Then `TerminalStateViolationException` (HTTP 409) is thrown — DELETED videos cannot have access state modified
- And for non-DELETED videos, any access state transition between ACTIVE, BLOCKED, and ARCHIVED is permitted

**AC-3: Playback eligibility gate**
- Given a video with any combination of operational and access states
- When `VideoLifecycleService.isPlaybackEligible(UUID videoId)` is called
- Then it returns `true` only when `operationalState == READY AND accessState == ACTIVE`
- And for all other combinations it returns `false`
- And throws `VideoNotFoundException` (HTTP 404) if videoId does not exist

**AC-4: FAILED retry — happy path**
- Given a `Video` in `operationalState = FAILED`
- When `VideoService.retryUpload(RetryUploadRequest)` is called with `(videoId, ownerId, fileSizeBytes)`
- Then `VideoValidationChain` runs first; a validation failure throws `VideoValidationException` (HTTP 422)
- And `QuotaProvider.check(ownerId, fileSizeBytes)` is invoked; `QuotaExceededException` (HTTP 429) if denied
- And `QuotaProvider.reserve(ownerId, fileSizeBytes)` is invoked and a new `UploadSession` is created in `PENDING` status on the **existing** `Video` record — no new `Video` entity is created, `videoId` is unchanged
- And `VideoProviderAdapter.initializeUpload()` is called to obtain new upload credentials
- And `Video.providerAssetId` is updated with the new `providerUploadId` from the credentials
- And `Video.operationalState` remains `FAILED` until the new session is confirmed via `confirmUpload()` (Story 2.3)
- And returns `InitializeUploadResponse` with the **same** `videoId`, new `uploadSessionId`, and new `signedUploadUrl`

**AC-5: FAILED retry — non-FAILED video rejected**
- Given `retryUpload()` is called on a video NOT in `FAILED` state (e.g., PROCESSING)
- When the precondition check runs
- Then `VideoValidationException` (HTTP 422) is thrown with message `"Retry is only permitted for videos in FAILED state"`

**AC-6: FAILED retry — quota release on failure**
- Given `retryUpload()` fails after `QuotaProvider.reserve()` but before the DB insert commits
- When the exception propagates
- Then `QuotaProvider.release(reservationHandle)` is called in a `finally` block

**AC-7: Unit tests for VideoLifecycleService**
- Given `VideoLifecycleServiceTest` runs (pure unit test, no containers)
- Then it covers: all valid transitions succeed; all invalid transitions from DELETED throw `TerminalStateViolationException`; all invalid non-DELETED transitions throw `TerminalStateViolationException`; `setAccessState()` on DELETED video throws; `setAccessState()` on non-DELETED succeeds for all access states; `isPlaybackEligible()` returns `false` for all combinations where `operationalState != READY` or `accessState != ACTIVE`; `isPlaybackEligible()` returns `true` only for READY+ACTIVE

**AC-8: Integration test for retryUpload()**
- Given `VideoRetryUploadIT` extending `BaseVideoIT`
- When exercised
- Then: retryUpload on a FAILED video creates a new `UploadSession`, leaves Video in FAILED state, updates `providerAssetId`; calling `confirmUpload()` after retry transitions Video to PROCESSING and Session to COMMITTED; retryUpload on a non-FAILED video throws `VideoValidationException`; quota release failure guard prevents state corruption

## Tasks / Subtasks

- [x] Task 1: Expand `VideoLifecycleService.transitionOperationalState()` with full transition map (AC: 1, 7)
  - [x] Add a `private static final Set<String>` or `Map` for valid transitions (see Dev Notes for exact map)
  - [x] Replace current "just check DELETED" guard with: (a) check DELETED terminal guard, (b) check same-state idempotency guard, (c) validate transition is in the allowed set; throw `TerminalStateViolationException` if not
  - [x] Keep signature and `@Transactional` annotation unchanged — all callers (expiry scheduler, future webhook processor) rely on `transitionOperationalState(UUID videoId, OperationalState newState)` returning `Video`

- [x] Task 2: Add `setAccessState()` to `VideoLifecycleService` (AC: 2, 7)
  - [x] Annotate `@Transactional`
  - [x] Load video or throw `VideoNotFoundException`
  - [x] If `operationalState == DELETED` → throw `TerminalStateViolationException(videoId, DELETED.name())`
  - [x] Set `video.setAccessState(newAccessState)` and `videoRepository.save(video)`; return saved video

- [x] Task 3: Add `isPlaybackEligible()` to `VideoLifecycleService` (AC: 3, 7)
  - [x] Mark `@Transactional(readOnly = true)` (read-only optimization)
  - [x] Load video or throw `VideoNotFoundException`
  - [x] Return `video.getOperationalState() == OperationalState.READY && video.getAccessState() == AccessState.ACTIVE`

- [x] Task 4: Create `RetryUploadRequest` record in `platform.video.contract` (AC: 4)
  - [x] Package: `com.softropic.skillars.platform.video.contract`
  - [x] Fields: `UUID videoId`, `String ownerId`, `long fileSizeBytes`
  - [x] No Jakarta Validation annotations needed — service layer validates state guard first

- [x] Task 5: Add `retryUpload()` to `VideoService` (AC: 4, 5, 6)
  - [x] Load video or throw `VideoNotFoundException`
  - [x] Guard: `if (video.getOperationalState() != FAILED)` → throw `VideoValidationException("Retry is only permitted for videos in FAILED state")`
  - [x] Extract extension from `video.getTitle()` (same pattern as `initializeUpload`)
  - [x] Call `validationChain.validate(new UploadValidationRequest(video.getTitle(), fileSizeBytes, null, ext.toUpperCase()))` — `MimeTypeValidationStep` skips null MIME (see Dev Notes)
  - [x] `quotaProvider.check()` → reject with `QuotaExceededException` if false
  - [x] `quotaProvider.reserve()` → store `reservationHandle`
  - [x] In `transactionTemplate.execute()`: create new `UploadSession` (PENDING, new expiresAt, reservedBytes, reservationHandle) on **existing** `video.getId()`; do NOT create a new Video
  - [x] Call `videoProviderAdapter.initializeUpload(video.getTitle(), request.fileSizeBytes())`
  - [x] In second `transactionTemplate.execute()`: update `video.setProviderAssetId(credentials.providerUploadId())`; set `session.setProviderUploadId(credentials.providerUploadId())`
  - [x] MDC: `videoId`, `ownerId`, `operation="retry_upload"` — clear in `finally`
  - [x] `success` flag + `finally` block for quota release (same pattern as `initializeUpload`)
  - [x] Return `new InitializeUploadResponse(video.getId(), sessionId, credentials.providerUploadId(), credentials.signedUploadUrl(), expiresAt)`

- [x] Task 6: Validate `MimeTypeValidationStep` handles null MIME gracefully (AC: 4)
  - [x] Read `MimeTypeValidationStep.java` — if it throws NPE on null MIME, add null guard: `if (request.mimeType() == null) return;`
  - [x] This is a defensive update to support the retry flow; existing behavior is preserved for non-null MIME types

- [x] Task 7: Write `VideoLifecycleServiceTest` (AC: 7)
  - [x] Pure unit test — `@ExtendWith(MockitoExtension.class)`, mock `VideoRepository`, no containers
  - [x] See Dev Notes for full test list

- [x] Task 8: Write `VideoRetryUploadIT` extending `BaseVideoIT` (AC: 8)
  - [x] `@MockitoBean QuotaProvider quotaProvider` + `@MockitoBean VideoProviderAdapter videoProviderAdapter`
  - [x] See Dev Notes for full test structure

## Dev Notes

### VideoLifecycleService — Current State & Changes Required

**Current implementation** (`src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`):
```java
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
```

**Story 2.3 NOTE in dev notes explicitly said:** "Story 3.1 will add: full valid-transition map (throw `TerminalStateViolationException` for invalid non-DELETED transitions), `setAccessState()`, and `isPlaybackEligible()`. Do NOT implement those here."

**This story completes that promise.** UPDATE this class — do NOT recreate it.

### VideoLifecycleService — Full Expanded Implementation

```java
package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.exception.TerminalStateViolationException;
import com.softropic.skillars.platform.video.contract.exception.VideoNotFoundException;
import com.softropic.skillars.platform.video.repo.Video;
import com.softropic.skillars.platform.video.repo.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLifecycleService {

    private final VideoRepository videoRepository;

    // Valid (from, to) transitions for transitionOperationalState()
    private static final Map<OperationalState, Set<OperationalState>> VALID_TRANSITIONS = Map.of(
        OperationalState.UPLOADING, Set.of(OperationalState.PROCESSING, OperationalState.FAILED),
        OperationalState.PROCESSING, Set.of(OperationalState.READY, OperationalState.FAILED),
        OperationalState.FAILED, Set.of(OperationalState.UPLOADING),
        OperationalState.READY, Set.of(),
        OperationalState.DELETED, Set.of()
    );

    @Transactional
    public Video transitionOperationalState(UUID videoId, OperationalState newState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        OperationalState current = video.getOperationalState();

        if (current == OperationalState.DELETED) {
            throw new TerminalStateViolationException(videoId, current.name());
        }

        if (current == newState) {
            return video; // idempotent — preserves existing scheduler behavior
        }

        if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(newState)) {
            throw new TerminalStateViolationException(videoId, current.name());
        }

        video.setOperationalState(newState);
        return videoRepository.save(video);
    }

    @Transactional
    public Video setAccessState(UUID videoId, AccessState newAccessState) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (video.getOperationalState() == OperationalState.DELETED) {
            throw new TerminalStateViolationException(videoId, OperationalState.DELETED.name());
        }

        video.setAccessState(newAccessState);
        return videoRepository.save(video);
    }

    @Transactional(readOnly = true)
    public boolean isPlaybackEligible(UUID videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        return video.getOperationalState() == OperationalState.READY
            && video.getAccessState() == AccessState.ACTIVE;
    }
}
```

**Critical:** `VALID_TRANSITIONS` uses `Map.of()` — this is immutable and fine for Java 17. The `TerminalStateViolationException` constructor is `(UUID videoId, String currentState)` — confirmed from the actual file at `contract/exception/TerminalStateViolationException.java`.

**Why idempotent same-state returns early:** The `UploadSessionExpiryScheduler` can call `transitionOperationalState(videoId, FAILED)` when the video is already FAILED (race condition where two nodes process the same expired session). Keeping this early return prevents a false `TerminalStateViolationException`.

### VideoService.retryUpload() — Full Implementation

```java
public InitializeUploadResponse retryUpload(RetryUploadRequest request) {
    MDC.put("operation", "retry_upload");
    MDC.put("ownerId", request.ownerId());
    MDC.put("videoId", request.videoId().toString());

    Video video = videoRepository.findById(request.videoId())
        .orElseThrow(() -> new VideoNotFoundException(request.videoId()));

    if (video.getOperationalState() != OperationalState.FAILED) {
        throw new VideoValidationException("Retry is only permitted for videos in FAILED state");
    }

    // Validate file size (MIME skipped — null handled by MimeTypeValidationStep guard)
    String ext = video.getTitle().contains(".")
        ? video.getTitle().substring(video.getTitle().lastIndexOf('.') + 1)
        : "";
    validationChain.validate(
        new UploadValidationRequest(video.getTitle(), request.fileSizeBytes(), null, ext.toUpperCase()));

    if (!quotaProvider.check(request.ownerId(), request.fileSizeBytes())) {
        throw new QuotaExceededException(request.ownerId(), 0L, request.fileSizeBytes());
    }

    String reservationHandle = null;
    boolean success = false;
    try {
        reservationHandle = quotaProvider.reserve(request.ownerId(), request.fileSizeBytes());

        Instant expiresAt = Instant.now().plus(
            properties.getUpload().getSessionTtlMinutes(), ChronoUnit.MINUTES);
        final String handle = reservationHandle;

        UUID[] ids = Objects.requireNonNull(transactionTemplate.execute(status -> {
            UploadSession session = new UploadSession();
            session.setVideoId(request.videoId());            // existing Video ID
            session.setStatus(UploadSessionStatus.PENDING);
            session.setReservedBytes(request.fileSizeBytes());
            session.setReservationHandle(handle);
            session.setExpiresAt(expiresAt);
            UploadSession saved = uploadSessionRepository.save(session);
            return new UUID[]{request.videoId(), saved.getId()};
        }));

        UUID videoId = ids[0];
        UUID sessionId = ids[1];

        // Provider call outside @Transactional
        UploadCredentials credentials = videoProviderAdapter.initializeUpload(
            video.getTitle(), request.fileSizeBytes());

        // Update Video.providerAssetId + Session.providerUploadId
        transactionTemplate.execute(status -> {
            Video v = videoRepository.findById(videoId).orElseThrow();
            v.setProviderAssetId(credentials.providerUploadId());
            videoRepository.save(v);

            UploadSession s = uploadSessionRepository.findById(sessionId).orElseThrow();
            s.setProviderUploadId(credentials.providerUploadId());
            uploadSessionRepository.save(s);
            return null;
        });

        success = true;
        return new InitializeUploadResponse(
            videoId, sessionId,
            credentials.providerUploadId(), credentials.signedUploadUrl(),
            expiresAt);

    } finally {
        if (!success && reservationHandle != null) {
            try {
                quotaProvider.release(reservationHandle);
            } catch (Exception e) {
                log.warn("Failed to release quota reservation after retry failure", e);
            }
        }
        MDC.remove("operation");
        MDC.remove("ownerId");
        MDC.remove("videoId");
    }
}
```

**Design decisions:**
- Video stays `FAILED` throughout `retryUpload()` — `confirmUpload()` (Story 2.3) handles the final transition to PROCESSING by directly setting `video.setOperationalState(PROCESSING)`. This bypasses the lifecycle service, which is intentional since Story 2.3 confirms both normal UPLOADING→PROCESSING and retry FAILED→PROCESSING paths.
- `retryUpload()` does NOT call `transitionOperationalState(FAILED→UPLOADING)` — the "retry path" transition in `VALID_TRANSITIONS` exists for external callers (e.g., webhook handler in Story 4.1) that may need to explicitly transition to UPLOADING, not for `retryUpload()` itself.
- Two `transactionTemplate.execute()` calls mirror the `initializeUpload()` pattern — provider call stays outside any transaction.

### MimeTypeValidationStep — Null Guard (REQUIRED FIX)

**Current behavior** (`src/main/java/com/softropic/skillars/platform/video/service/validation/MimeTypeValidationStep.java`):
```java
public void validate(UploadValidationRequest request) {
    String mimeType = request.mimeType();
    if (mimeType == null || properties.getUpload().getAllowedMimeTypes().stream()
            .noneMatch(allowed -> allowed.equalsIgnoreCase(mimeType))) {
        throw new VideoValidationException("MIME type not allowed: " + mimeType);
    }
}
```

**Problem:** Currently throws `VideoValidationException("MIME type not allowed: null")` when `mimeType` is null. The retry flow passes null because the Video entity does not store the original MIME type.

**Required change** — change the null branch to skip rather than reject:
```java
@Override
public void validate(UploadValidationRequest request) {
    String mimeType = request.mimeType();
    if (mimeType == null) {
        return;  // mimeType not available (retry flow) — skip MIME check
    }
    if (properties.getUpload().getAllowedMimeTypes().stream()
            .noneMatch(allowed -> allowed.equalsIgnoreCase(mimeType))) {
        throw new VideoValidationException("MIME type not allowed: " + mimeType);
    }
}
```

**Existing tests:** Read `VideoValidationChainTest.java` to understand current test coverage. Add one test case: passing null mimeType does NOT throw.

### RetryUploadRequest — Record Definition

```java
package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

public record RetryUploadRequest(UUID videoId, String ownerId, long fileSizeBytes) {}
```

No Jakarta Validation annotations — service layer validates state guard first before any field checks.

### Unit Test — VideoLifecycleServiceTest

Use `@ExtendWith(MockitoExtension.class)` — no containers, no `@SpringBootTest`. Mock `VideoRepository` with `@Mock`.

```java
@ExtendWith(MockitoExtension.class)
class VideoLifecycleServiceTest {

    @Mock VideoRepository videoRepository;
    @InjectMocks VideoLifecycleService service;

    // Helper: build a minimal Video with given states
    private Video videoWith(UUID id, OperationalState op, AccessState ac) {
        Video v = new Video();  // no-args constructor via @NoArgsConstructor
        // ... set fields via setters
        return v;
    }
```

**Required test coverage:**

| Test method | What it tests |
|---|---|
| `transitionOperationalState_validTransitions_succeed` | UPLOADING→PROCESSING, UPLOADING→FAILED, PROCESSING→READY, PROCESSING→FAILED, FAILED→UPLOADING all succeed |
| `transitionOperationalState_sameState_isIdempotent` | UPLOADING→UPLOADING returns without saving |
| `transitionOperationalState_deleted_throwsTerminalViolation` | DELETED→any throws |
| `transitionOperationalState_invalidTransition_throws` | READY→UPLOADING, PROCESSING→UPLOADING each throw |
| `setAccessState_onDeletedVideo_throws` | DELETED + any AccessState throws |
| `setAccessState_onNonDeletedVideo_succeeds` | UPLOADING+ACTIVE→BLOCKED succeeds |
| `isPlaybackEligible_readyAndActive_returnsTrue` | READY+ACTIVE = true |
| `isPlaybackEligible_notReady_returnsFalse` | UPLOADING+ACTIVE, PROCESSING+ACTIVE, FAILED+ACTIVE = false |
| `isPlaybackEligible_notActive_returnsFalse` | READY+BLOCKED, READY+ARCHIVED = false |
| `isPlaybackEligible_videoNotFound_throws` | VideoNotFoundException |

**Note on Video construction in tests:** `Video` has `@NoArgsConstructor` (Lombok). Set fields with setters. No need for `Instancio` for pure unit tests — direct construction is cleaner here. Use `Instancio` in integration tests.

### Integration Test — VideoRetryUploadIT

```java
class VideoRetryUploadIT extends BaseVideoIT {

    @MockitoBean QuotaProvider quotaProvider;
    @MockitoBean VideoProviderAdapter videoProviderAdapter;
    @Autowired VideoService videoService;
    @Autowired VideoRepository videoRepository;
    @Autowired UploadSessionRepository uploadSessionRepository;

    @BeforeEach
    void setUp() {
        uploadSessionRepository.deleteAll();
        videoRepository.deleteAll();
        wireMockServer.resetAll();
        when(quotaProvider.check(anyString(), anyLong())).thenReturn(true);
        when(quotaProvider.reserve(anyString(), anyLong())).thenReturn("retry-handle");
        when(videoProviderAdapter.initializeUpload(anyString(), anyLong()))
            .thenReturn(new UploadCredentials("new-bunny-guid", "https://bunny/tus/retry"));
    }
```

**Required test coverage:**

1. `retryUpload_onFailedVideo_createsNewSessionVideoStaysFailed` — seed Video in FAILED, call `retryUpload()`, assert new UploadSession (PENDING) created, Video still FAILED, Video.providerAssetId updated to "new-bunny-guid"
2. `retryUpload_thenConfirmUpload_transitionsToProcessing` — seed FAILED Video + call `retryUpload()` then `confirmUpload()`, assert Video=PROCESSING and UploadSession=COMMITTED
3. `retryUpload_onNonFailedVideo_throwsValidationException` — seed Video in PROCESSING, assert `VideoValidationException` thrown
4. `retryUpload_quotaReleaseFails_sessionNotCreated` — mock `quotaProvider.release()` to throw; cause provider call to fail (mock `videoProviderAdapter.initializeUpload()` to throw `VideoProviderException`) after reserve; assert no UploadSession created, Video still FAILED, `quotaProvider.release()` was attempted

**Seeding FAILED video in integration tests** — use `videoRepository.save()` directly:
```java
Video failedVideo = new Video();
failedVideo.setOwnerId("owner-retry");
failedVideo.setProvider("bunny");
failedVideo.setProviderAssetId("old-bunny-guid");
failedVideo.setTitle("retry.mp4");
failedVideo.setOperationalState(OperationalState.FAILED);
failedVideo.setAccessState(AccessState.ACTIVE);
failedVideo.setVisibility(Visibility.PRIVATE);
failedVideo = videoRepository.save(failedVideo);
```

Do NOT call `initializeUpload()` then manually expire — seed directly for isolation.

### Package & File Structure

```
src/main/java/com/softropic/skillars/platform/video/
├── contract/
│   └── RetryUploadRequest.java               ← NEW: record(videoId, ownerId, fileSizeBytes)
├── service/
│   ├── VideoLifecycleService.java            ← UPDATE: add full transition map + setAccessState + isPlaybackEligible
│   ├── VideoService.java                     ← UPDATE: add retryUpload()
│   └── validation/
│       └── MimeTypeValidationStep.java       ← UPDATE: add null-MIME guard (read first!)

src/test/java/com/softropic/skillars/platform/video/
├── service/
│   ├── VideoLifecycleServiceTest.java        ← NEW: pure unit test (no containers)
│   └── VideoRetryUploadIT.java               ← NEW: integration test extends BaseVideoIT
```

### What MUST NOT Be Recreated (Already Exists)

| Component | Path | Notes |
|---|---|---|
| `VideoLifecycleService` | `service/VideoLifecycleService.java` | UPDATE only — it already has `transitionOperationalState()` |
| `TerminalStateViolationException` | `contract/exception/TerminalStateViolationException.java` | Constructor: `(UUID videoId, String currentState)` |
| `VideoNotFoundException` | `contract/exception/VideoNotFoundException.java` | Exists |
| `VideoValidationException` | `contract/exception/VideoValidationException.java` | Exists |
| `QuotaExceededException` | `contract/exception/QuotaExceededException.java` | Constructor: `(String ownerId, long current, long requested)` |
| `OperationalState` | `contract/OperationalState.java` | `UPLOADING, PROCESSING, READY, FAILED, DELETED` |
| `AccessState` | `contract/AccessState.java` | `ACTIVE, BLOCKED, ARCHIVED` |
| `Visibility` | `contract/Visibility.java` | `PRIVATE, GROUP, UNLISTED` |
| `InitializeUploadResponse` | `contract/InitializeUploadResponse.java` | Reuse as retryUpload return type |
| `UploadSessionStatus` | `contract/UploadSessionStatus.java` | `PENDING, COMMITTED, EXPIRED` |
| `VideoValidationChain` | `service/VideoValidationChain.java` | Reuse as-is |
| `VideoApiAdvice` | `api/VideoApiAdvice.java` | No new exceptions introduced — no changes needed |
| `Video`, `UploadSession` entities | `repo/` | Both have `@Getter @Setter @NoArgsConstructor` |
| `VideoRepository`, `UploadSessionRepository` | `repo/` | No new queries needed for this story |
| `TransactionTemplate` | already in `VideoService` | Reuse — do not add new wiring |
| `BaseVideoIT` | `test/java/.../video/BaseVideoIT.java` | PostgreSQL + WireMock |
| `VideoUploadConfirmationIT` | `test/java/.../video/service/` | Reference for test patterns |

### Existing Callers of `transitionOperationalState()` — Must Not Break

`UploadSessionExpiryScheduler.java` calls:
```java
videoLifecycleService.transitionOperationalState(session.getVideoId(), OperationalState.FAILED);
```
from inside a `transactionTemplate.execute()`. After this story, UPLOADING→FAILED is valid and FAILED→FAILED is idempotent (same-state early return). Both cases still work. ✓

### Architecture Compliance

- `VideoLifecycleService` → `platform.video.service` ✓
- `RetryUploadRequest` → `platform.video.contract` ✓
- No `infrastructure.*` business logic or imports ✓
- `@Transactional(readOnly = true)` on `isPlaybackEligible()` — correct Spring optimization ✓
- `@RequiredArgsConstructor` (not `@Autowired`) on all services ✓
- MDC cleared in `finally` block for `retryUpload()` ✓
- Provider call (`videoProviderAdapter.initializeUpload()`) outside `@Transactional` boundary ✓

### Project Context Rules (Must Follow)

- Java 17: `record` for `RetryUploadRequest`
- `@Slf4j @Service @RequiredArgsConstructor` on `VideoLifecycleService` (already applied — preserve)
- `AssertJ` (`assertThat`) for ALL assertions — no JUnit `assertEquals` or `assertTrue`
- `@MockitoBean` (not `@Mock` or `@MockBean`) for Spring context injection in `*IT` tests
- Verify mock calls with `verify(quotaProvider).release(...)` not raw assertions
- All dates/times via `Instant` — no `LocalDateTime` or `Date`

### References

- `VideoLifecycleService.java` (current): `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`
- `VideoService.java`: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` — follow `initializeUpload()` for TransactionTemplate two-phase pattern
- `TerminalStateViolationException.java`: `src/main/java/com/softropic/skillars/platform/video/contract/exception/TerminalStateViolationException.java`
- `VideoUploadConfirmationIT.java`: `src/test/java/com/softropic/skillars/platform/video/service/VideoUploadConfirmationIT.java` — follow `@MockitoBean`, `@BeforeEach deleteAll()`, `@Autowired` patterns
- `BaseVideoIT.java`: `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`
- `MimeTypeValidationStep.java`: `src/main/java/com/softropic/skillars/platform/video/service/validation/MimeTypeValidationStep.java` — read before modifying
- Epic 3 Story 3.1 AC: `_bmad-output/planning-artifacts/video-module/epics.md` §"Story 3.1: Video State Machine & FAILED Retry"
- Project context rules: `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

- ✅ Task 1: Expanded `VideoLifecycleService.transitionOperationalState()` with full `VALID_TRANSITIONS` Map. DELETED guard and idempotent same-state early return preserved. Invalid non-DELETED transitions now throw `TerminalStateViolationException`.
- ✅ Task 2: Added `setAccessState()` — rejects DELETED videos, allows all access state changes for non-DELETED.
- ✅ Task 3: Added `isPlaybackEligible()` with `@Transactional(readOnly = true)` — true only for READY+ACTIVE.
- ✅ Task 4: Created `RetryUploadRequest` record (videoId, ownerId, fileSizeBytes) — no Jakarta Validation, state guard comes first.
- ✅ Task 5: Added `retryUpload()` to `VideoService` — mirrors `initializeUpload()` two-phase TransactionTemplate pattern. Video stays FAILED throughout; confirmUpload() handles PROCESSING transition. MDC + quota release in finally block.
- ✅ Task 6: Fixed `MimeTypeValidationStep` to return early on null MIME instead of throwing. Updated `VideoValidationChainTest.mimeType_rejectsNullMimeType` → `mimeType_skipsNullMimeType` to reflect new behavior.
- ✅ Task 7: `VideoLifecycleServiceTest` — 10 pure unit tests, no containers. Covers all valid/invalid transitions, idempotent same-state, DELETED guards, setAccessState, isPlaybackEligible matrix.
- ✅ Task 8: `VideoRetryUploadIT` — 4 integration tests. Note: AC-8's "assert no UploadSession created" in the quota-release-fails test is inaccurate for this implementation — the session IS persisted before the provider call (same behavior as initializeUpload, orphaned sessions are cleaned by expiry scheduler). Test asserts accurate actual behavior: session exists as PENDING with null providerUploadId, quota.release() was attempted.

### File List

- src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java (modified)
- src/main/java/com/softropic/skillars/platform/video/service/VideoService.java (modified)
- src/main/java/com/softropic/skillars/platform/video/service/validation/MimeTypeValidationStep.java (modified)
- src/main/java/com/softropic/skillars/platform/video/contract/RetryUploadRequest.java (new)
- src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleServiceTest.java (new)
- src/test/java/com/softropic/skillars/platform/video/service/VideoRetryUploadIT.java (new)
- src/test/java/com/softropic/skillars/platform/video/service/VideoValidationChainTest.java (modified)
