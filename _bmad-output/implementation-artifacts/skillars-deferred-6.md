# Story Deferred-6: Video Module Hardening

Status: backlog

## Story

As a platform engineer,
I want the video module's state machine, deletion events, and cascade flows to be correct,
so that videos are never stuck in wrong states, deletion consumers are safe to add, and account deletion properly cleans up approval rows.

## Acceptance Criteria

1. **Given** `VideoPhysicalDeletionEvent` is published from `VideoDeletionService.deleteVideo()`
   **When** the event is published inside a `@Transactional` method before the TX commits
   **Then** any `@EventListener` (synchronous) consumer would process a deletion that could still roll back — the event MUST be published via `@TransactionalEventListener(AFTER_COMMIT)` instead, OR the event must be published after the transaction commits
   **And** the event is renamed from `VideoPhysicalDeletionEvent` to `VideoPurgedEvent` to accurately describe what happened (Story 6.5 W6 — "physical deletion" is misleading; the video is logically deleted; Bunny.net purge is async)
   **And** all existing consumers of `VideoPhysicalDeletionEvent` (including `VideoPhysicalDeletionListener` in `DrillUploadService`) are updated to `VideoPurgedEvent`

2. **Given** `VideoService.confirmUpload()` is called to mark an upload as confirmed
   **When** it transitions the video state to `PROCESSING`
   **Then** the transition goes through `VideoLifecycleService.transition(videoId, PROCESSING)` rather than a direct `videoRepository.save()` call — so `VALID_TRANSITIONS` is enforced and future state refactors are safe

3. **Given** `VideoService.retryUpload()` is called to retry a failed upload
   **When** the method runs
   **Then** `videos.operational_state` is transitioned back to `UPLOADING` before the new upload session is initiated — so the video is not left in `FAILED` while the retry is in progress (which causes the ReconciliationWorker to re-FAIL it)

4. **Given** an account deletion cascade runs for a user who owns videos
   **When** `VideoDeletionService.cascadeDeleteForAccount()` completes
   **Then** all pending `VideoApprovalRequest` rows for the deleted user's videos are cancelled/deleted — parents no longer see stale approval cards for purged videos
   **And** `VideoApprovalRequestRepository.cancelAllPendingForVideo(videoId)` is called for each video processed in the cascade

## Tasks / Subtasks

- [ ] **Task 1 — Rename `VideoPhysicalDeletionEvent` → `VideoPurgedEvent`** (AC: 1)
  - [ ] Rename the class: `src/main/java/com/softropic/skillars/platform/video/contract/VideoPhysicalDeletionEvent.java` → `VideoPurgedEvent.java`
  - [ ] Update the class declaration: `public class VideoPurgedEvent extends ApplicationEvent { ... }` — keep the same fields (videoId, ownerId, or whatever the current payload is)
  - [ ] Find and update all references:
    `find src -name "*.java" | xargs grep -l "VideoPhysicalDeletionEvent"` → rename in each file
  - [ ] Known consumers: `VideoPhysicalDeletionListener.java` (in `DrillUploadService` package or wherever Story 4.3 placed it) — rename both the import and the `@EventListener` / `@TransactionalEventListener` method parameter type

- [ ] **Task 2 — Move `VideoPurgedEvent` publication to AFTER_COMMIT** (AC: 1)
  - [ ] Read `VideoDeletionService.java` — find the `deleteVideo()` method (or `logicallyDelete()` — confirm the method name)
  - [ ] Current: `eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(...))` called inside `@Transactional deleteVideo()`
  - [ ] Fix: replace with `@TransactionalEventListener(AFTER_COMMIT)` publication pattern. Two options:
    - **Option A** (preferred): change the `publishEvent` call to use Spring's `TransactionSynchronizationManager` to fire AFTER_COMMIT:
      ```java
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
          @Override
          public void afterCommit() {
              eventPublisher.publishEvent(new VideoPurgedEvent(VideoDeletionService.this, videoId, ownerId));
          }
      });
      ```
    - **Option B**: annotate the consuming listener with `@TransactionalEventListener(phase = AFTER_COMMIT)` so it only fires after the TX commits (safer if multiple consumers exist or will exist)
  - [ ] Option B is preferred if consumers are few and known — it is the existing pattern used by `AccountDeletionCascadeListener`
  - [ ] If the existing `VideoPhysicalDeletionListener` already uses `@TransactionalEventListener`, this task may already be partially correct — verify before editing
  - [ ] **CRITICAL**: any future consumer of `VideoPurgedEvent` MUST use `@TransactionalEventListener(phase = AFTER_COMMIT)` — add a Javadoc comment on `VideoPurgedEvent` stating this requirement

- [ ] **Task 3 — Fix `confirmUpload()` to go through lifecycle enforcement** (AC: 2)
  - [ ] Read `VideoService.java` — find `confirmUpload()` — locate the line that writes `PROCESSING` via direct `videoRepository.save()`
  - [ ] Replace:
    ```java
    // BEFORE: direct save bypasses VALID_TRANSITIONS
    video.setOperationalState("PROCESSING");
    videoRepository.save(video);

    // AFTER: go through lifecycle enforcement
    videoLifecycleService.transition(videoId, OperationalState.PROCESSING);
    ```
  - [ ] Confirm `VideoLifecycleService.transition()` method signature and `OperationalState.PROCESSING` enum value
  - [ ] If `UPLOADING → PROCESSING` is not in `VALID_TRANSITIONS`, add it — read `VALID_TRANSITIONS` map before editing; adding a missing transition is expected

- [ ] **Task 4 — Fix `retryUpload()` to transition back to UPLOADING** (AC: 3)
  - [ ] Read `VideoService.java` — find `retryUpload()` — confirm what state it leaves the video in
  - [ ] At the start of the retry logic, before creating a new upload session:
    ```java
    videoLifecycleService.transition(videoId, OperationalState.UPLOADING);
    ```
  - [ ] This must run before the new `UploadSession` is created so the ReconciliationWorker's `findScanningOlderThan` / `findFailedOlderThan` queries do not re-FAIL the video while it's mid-retry
  - [ ] Add `FAILED → UPLOADING` to `VALID_TRANSITIONS` if not already present

- [ ] **Task 5 — Cancel pending approvals on account deletion cascade** (AC: 4)
  - [ ] Add to `VideoApprovalRequestRepository`:
    ```java
    @Modifying
    @Query("DELETE FROM VideoApprovalRequest r WHERE r.videoId = :videoId AND r.status = 'PENDING'")
    int cancelAllPendingForVideo(@Param("videoId") UUID videoId);
    ```
    — or use `status = 'CANCELLED'` if the approval workflow soft-deletes rather than hard-deletes; read `VideoApprovalRequest.java` to confirm the status lifecycle
  - [ ] In `VideoDeletionService.cascadeDeleteForAccount()`:
    ```java
    for (Video video : videosToDelete) {
        // existing per-video deletion logic
        deleteVideoPhysically(video);
        // NEW: cancel stale approval requests
        videoApprovalRequestRepository.cancelAllPendingForVideo(video.getId());
    }
    ```
  - [ ] Inject `VideoApprovalRequestRepository` into `VideoDeletionService` if not already injected

- [ ] **Task 6 — Integration tests** (AC: 1, 2, 3, 4)
  - [ ] TSID range `9340_xxx`
  - [ ] `confirmUpload_transitionsViaLifecycleService()` — call `confirmUpload()` on a video in `UPLOADING` state; verify `operational_state = 'PROCESSING'` was written via lifecycle (mock `VideoLifecycleService` and verify `transition()` was called, not a direct repo save)
  - [ ] `retryUpload_setsUploadingBeforeNewSession()` — call `retryUpload()` on a FAILED video; verify `operational_state = 'UPLOADING'` immediately after the call (before the upload session is created)
  - [ ] `cascadeDeleteAccount_cancelsPendingApprovals()` — seed: user with 2 videos, 1 pending approval request per video; run cascade; verify approval rows are deleted/cancelled
  - [ ] `videoPurgedEvent_publishedAfterCommit()` — verify the event fires after the TX commits (verify the listener's `@TransactionalEventListener` annotation fires correctly; use `@Commit` test + event capture)

## Dev Notes

### `VideoPurgedEvent` vs "physical deletion"

The original name `VideoPhysicalDeletionEvent` was misleading: the event fires when a video is logically deleted in the DB. The actual Bunny.net CDN/storage purge is asynchronous and happens through `VideoPhysicalDeletionListener` calling the provider. `VideoPurgedEvent` is accurate: the video has been purged from the platform's perspective (DB state = PURGED), regardless of whether the provider has physically removed it yet.

### VALID_TRANSITIONS and the state machine

Read `VideoLifecycleService.java` fully before making changes. The valid transitions map determines what state changes are legal. Adding `FAILED → UPLOADING` and verifying `UPLOADING → PROCESSING` are present is necessary before calling `transition()` from `retryUpload()` and `confirmUpload()`. If `transition()` throws on an invalid transition, the current code (direct save) would have bypassed the error — the fix may surface a latent bug if the transition is truly invalid by the intended state machine design.

### `VideoApprovalRequest` status lifecycle

Read `VideoApprovalRequest.java` to confirm:
- Whether `status` is a String or enum
- The valid terminal states (`APPROVED`, `REJECTED`, `CANCELLED`, or similar)
- Whether a hard-delete or soft-cancel is correct for the cascade

If the approval flow uses hard-delete, `@Modifying @Query("DELETE FROM VideoApprovalRequest ...")` is correct. If it uses soft-cancel (`status = 'CANCELLED'`), use an UPDATE query instead.

### `VideoPhysicalDeletionListener` location

This listener was added in Story 4.3 (`DrillUploadService` territory) to handle `VideoPhysicalDeletionEvent` for drill video orphan cleanup. Its exact package location must be found before renaming:
`find src -name "VideoPhysicalDeletionListener.java"`
Update the import, the `@EventListener` parameter type, and the log message to reference `VideoPurgedEvent`.

### References — Files to Read Before Implementing

- `VideoDeletionService.java` — `deleteVideo()` and `cascadeDeleteForAccount()` methods
- `VideoService.java` — `confirmUpload()` and `retryUpload()` methods
- `VideoLifecycleService.java` — `VALID_TRANSITIONS` map and `transition()` signature
- `VideoPhysicalDeletionEvent.java` — current payload fields (to replicate in `VideoPurgedEvent`)
- `VideoPhysicalDeletionListener.java` — current consumer to update
- `VideoApprovalRequest.java` — status field and lifecycle
- `VideoApprovalRequestRepository.java` — existing query methods
- `AccountDeletionCascadeListener.java` — `@TransactionalEventListener(AFTER_COMMIT)` pattern to follow

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

**Renamed Files:**
- `VideoPhysicalDeletionEvent.java` → `VideoPurgedEvent.java`

**Modified Files:**
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/listener/VideoPhysicalDeletionListener.java` → `VideoPurgedEventListener.java`
